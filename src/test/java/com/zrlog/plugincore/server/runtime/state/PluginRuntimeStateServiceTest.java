package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginRuntimeStateServiceTest {

    @Test
    public void shouldStartPluginAndMarkReady() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        FakeStarter starter = new FakeStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(new PluginRuntimeStateStore(kvStore), starter);

        assertTrue(service.ensureStarted("plugin-a"));

        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals("ready", state.getStatus());
        assertEquals("reminder", state.getPluginName());
        assertEquals(1, starter.startCount);
        assertEquals(3, kvStore.getCount(PluginRuntimeStateStore.KEY));
    }

    @Test
    public void shouldNotWriteRuntimeStateWhenPluginAlreadyStarted() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginStartCoordinator startCoordinator = new PluginStartCoordinator();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), new StartedStarter(), 100, 1,
                "started-instance", startCoordinator);

        assertTrue(service.ensureStarted("plugin-a"));

        assertEquals(0, kvStore.getCount(PluginRuntimeStateStore.KEY));
        assertEquals(0, kvStore.putCount(PluginRuntimeStateStore.KEY));
        assertFalse(startCoordinator.runIfUnclaimed("plugin-a", () -> {
            throw new AssertionError("already-running plugin demand was not claimed");
        }));
    }

    @Test
    public void shouldNotWriteRuntimeStateForBlankOrUnknownPluginId() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), new UnknownStarter());

        assertFalse(service.ensureStarted((String) null));
        assertFalse(service.ensureStarted("  "));
        assertFalse(service.ensureStarted("unknown-plugin"));

        assertEquals(0, kvStore.getCount(PluginRuntimeStateStore.KEY));
        assertEquals(0, kvStore.putCount(PluginRuntimeStateStore.KEY));
    }

    @Test
    public void shouldTrackInvocationCountAndLastError() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateService service = new PluginRuntimeStateService(new PluginRuntimeStateStore(kvStore), new FakeStarter());

        service.markInvocationStart("plugin-a", "reminder");
        PluginRuntimeState executingState = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals("ready", executingState.getStatus());
        assertEquals(Integer.valueOf(1), executingState.getActiveInvocationCount());

        service.markInvocationEnd("plugin-a", "reminder", "boom");

        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals("ready", state.getStatus());
        assertEquals(Integer.valueOf(0), state.getActiveInvocationCount());
        assertEquals("boom", state.getLastError());
    }

    @Test
    public void shouldTrackInvocationTokensIdempotently() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), new FakeStarter(), "token-instance");

        service.markInvocationStart("plugin-a", "reminder", "invocation-a");
        service.markInvocationStart("plugin-a", "reminder", "invocation-a");
        service.markInvocationStart("plugin-a", "reminder", "invocation-b");

        PluginRuntimeState executingState = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals(Integer.valueOf(2), executingState.getActiveInvocationCount());

        service.markInvocationEnd("plugin-a", "reminder", "invocation-a", null);
        service.markInvocationEnd("plugin-a", "reminder", "invocation-a", null);

        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals(Integer.valueOf(1), state.getActiveInvocationCount());
        assertEquals(Collections.singleton("invocation-b"),
                state.getInstances().get(0).getActiveInvocationIds());
    }

    @Test
    public void shouldRetryInvocationEndAfterFailureBeforeWrite() {
        FailingInvocationKvStore kvStore = new FailingInvocationKvStore();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), new FakeStarter(), "retry-instance");
        service.markInvocationStart("plugin-a", "reminder", "invocation-a");
        kvStore.failBeforeNextStateWrite();

        service.markInvocationEndWithRetry("plugin-a", "reminder", "invocation-a", "finished");

        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals(Integer.valueOf(0), state.getActiveInvocationCount());
        assertTrue(state.getInstances().get(0).getActiveInvocationIds().isEmpty());
        assertEquals("finished", state.getLastError());
    }

    @Test
    public void shouldNotDoubleDecrementWhenSuccessfulWriteReportsFailure() {
        FailingInvocationKvStore kvStore = new FailingInvocationKvStore();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), new FakeStarter(), "retry-instance");
        service.markInvocationStart("plugin-a", "reminder", "invocation-a");
        service.markInvocationStart("plugin-a", "reminder", "invocation-b");
        kvStore.failAfterNextStateWrite();

        service.markInvocationEndWithRetry("plugin-a", "reminder", "invocation-a", null);

        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals(Integer.valueOf(1), state.getActiveInvocationCount());
        assertEquals(Collections.singleton("invocation-b"),
                state.getInstances().get(0).getActiveInvocationIds());
    }

    @Test
    public void shouldBoundTrackedInvocationTokensPerRuntimeInstance() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeInstanceState instance = runtimeInstance("capacity-instance", PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS);
        LinkedHashSet<String> invocationIds = new LinkedHashSet<>();
        for (int i = 0; i < PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS; i++) {
            invocationIds.add("invocation-" + i);
        }
        instance.setActiveInvocationIds(invocationIds);
        store.upsert(runtimeState("plugin-a", instance));
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                store, new FakeStarter(), "capacity-instance");

        service.markInvocationStart("plugin-a", "reminder", "invocation-0");
        try {
            service.markInvocationStart("plugin-a", "reminder", "overflow-invocation");
            fail("tracking capacity should reject a new invocation token");
        } catch (IllegalStateException expected) {
            assertEquals("Plugin invocation tracking capacity reached", expected.getMessage());
        }

        PluginRuntimeState state = store.find("plugin-a").get();
        assertEquals(Integer.valueOf(PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS),
                state.getActiveInvocationCount());
        assertEquals(PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS,
                state.getInstances().get(0).getActiveInvocationIds().size());
    }

    @Test
    public void shouldSupportLegacyInvocationStateWithoutTokenField() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        store.upsert(runtimeState("plugin-a", runtimeInstance("legacy-instance", 2)));
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                store, new FakeStarter(), "legacy-instance");

        service.markInvocationStart("plugin-a", "reminder", "new-invocation");
        service.markInvocationEnd("plugin-a", "reminder", "new-invocation", null);

        PluginRuntimeState state = store.find("plugin-a").get();
        assertEquals(Integer.valueOf(2), state.getActiveInvocationCount());
        assertTrue(state.getInstances().get(0).getActiveInvocationIds().isEmpty());
    }

    @Test
    public void shouldClearInvocationTokensOnLifecycleResetAndStop() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                store, new FakeStarter(), "lifecycle-instance");

        service.markInvocationStart("plugin-a", "reminder", "ready-token");
        service.markReady("plugin-a", "reminder");
        assertNoActiveInvocations(store.find("plugin-a").get());

        service.markInvocationStart("plugin-a", "reminder", "failed-token");
        service.markFailed("plugin-a", "reminder", "failed");
        assertNoActiveInvocations(store.find("plugin-a").get());

        service.markInvocationStart("plugin-a", "reminder", "starting-token");
        service.markStarting("plugin-a", "reminder", "process");
        assertNoActiveInvocations(store.find("plugin-a").get());

        service.markInvocationStart("plugin-a", "reminder", "stopped-token");
        service.markStopped("plugin-a", "reminder");
        assertTrue(store.find("plugin-a").get().getInstances().isEmpty());
    }

    @Test
    public void shouldAggregateTokenizedInvocationsAcrossRuntimeInstances() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeStateService first = new PluginRuntimeStateService(store, new FakeStarter(), "instance-a");
        PluginRuntimeStateService second = new PluginRuntimeStateService(store, new FakeStarter(), "instance-b");
        first.markReady("plugin-a", "reminder");
        second.markReady("plugin-a", "reminder");

        first.markInvocationStart("plugin-a", "reminder", "invocation-a");
        second.markInvocationStart("plugin-a", "reminder", "invocation-b");
        assertEquals(Integer.valueOf(2), store.find("plugin-a").get().getActiveInvocationCount());

        first.markInvocationEnd("plugin-a", "reminder", "invocation-a", null);
        assertEquals(Integer.valueOf(1), store.find("plugin-a").get().getActiveInvocationCount());

        second.markInvocationEnd("plugin-a", "reminder", "invocation-b", null);
        assertEquals(Integer.valueOf(0), store.find("plugin-a").get().getActiveInvocationCount());
    }

    @Test
    public void shouldMarkInitializingAndStoppingBeforeReadyTransitions() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateService service = new PluginRuntimeStateService(new PluginRuntimeStateStore(kvStore), new FakeStarter());

        service.markStarting("plugin-a", "reminder", "native");
        service.markInitializing("plugin-a", "reminder", null);
        assertEquals("initializing", new PluginRuntimeStateStore(kvStore).find("plugin-a").get().getStatus());
        assertEquals("native", new PluginRuntimeStateStore(kvStore).find("plugin-a").get().getRuntimeMode());

        service.markStopping("plugin-a", "reminder");
        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals("stopping", state.getStatus());
        assertEquals("reminder", state.getPluginName());
    }

    @Test
    public void shouldFailFastWhenStarterThrows() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateService service = new PluginRuntimeStateService(new PluginRuntimeStateStore(kvStore), new FailingStarter());

        org.junit.Assert.assertFalse(service.ensureStarted("plugin-a"));

        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals("failed", state.getStatus());
        assertEquals("missing file", state.getLastError());
    }

    @Test
    public void shouldAggregateMultipleRuntimeInstancesUnderSamePluginKey() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeStateService first = new PluginRuntimeStateService(store, new FakeStarter(), "reminder-1");
        PluginRuntimeStateService second = new PluginRuntimeStateService(store, new FakeStarter(), "reminder-2");

        first.markReady("reminder", "待办提醒");
        second.markReady("reminder", "待办提醒");
        first.markInvocationStart("reminder", "待办提醒");

        PluginRuntimeState running = store.find("reminder").get();
        assertEquals("ready", running.getStatus());
        assertEquals(Integer.valueOf(1), running.getActiveInvocationCount());
        assertEquals(2, running.getInstances().size());

        first.markStopped("reminder", "待办提醒");

        PluginRuntimeState state = store.find("reminder").get();
        assertEquals("ready", state.getStatus());
        assertEquals(Integer.valueOf(0), state.getActiveInvocationCount());
        assertEquals(1, state.getInstances().size());
        assertEquals("reminder-2", state.getInstances().get(0).getInstanceId());
    }

    @Test
    public void shouldPerformFinalStartedCheckBeforeTimeoutCleanup() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        BoundaryStarter starter = new BoundaryStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), starter, 0, 1, "boundary-instance",
                new PluginStartCoordinator());

        assertTrue(service.ensureStarted("plugin-a"));
        assertEquals(0, starter.cleanupCount);
    }

    @Test
    public void shouldWaitForLifecycleReadinessAfterSessionStarts() throws Exception {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        InitializingStarter starter = new InitializingStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), starter, 1000, 5, "ready-instance",
                new PluginStartCoordinator());
        AtomicBoolean result = new AtomicBoolean();

        Thread startThread = new Thread(() -> result.set(service.ensureStarted("plugin-a")));
        startThread.start();
        assertTrue(starter.startCalled.await(1, TimeUnit.SECONDS));
        Thread.sleep(30L);
        assertTrue(startThread.isAlive());

        starter.ready = true;
        startThread.join(2000L);

        assertFalse(startThread.isAlive());
        assertTrue(result.get());
    }

    @Test
    public void shouldStopWaitingForReadinessWhenInterrupted() throws Exception {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        InitializingStarter starter = new InitializingStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), starter, 30000, 1000, "interrupted-instance",
                new PluginStartCoordinator());
        AtomicBoolean result = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Thread startThread = new Thread(() -> {
            result.set(service.ensureStarted("plugin-a"));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        startThread.start();
        assertTrue(starter.startCalled.await(1, TimeUnit.SECONDS));

        startThread.interrupt();
        startThread.join(2000L);

        assertFalse(startThread.isAlive());
        assertFalse(result.get());
        assertTrue(interrupted.get());
    }

    @Test
    public void shouldRetryStartOnceAfterIdleCapacityReclaim() {
        CapacityReclaimStarter starter = new CapacityReclaimStarter(true, false);
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()), starter, 100, 1,
                "capacity-instance", new PluginStartCoordinator());

        assertTrue(service.ensureStarted("plugin-a"));
        assertEquals(2, starter.startCount);
        assertEquals(1, starter.reclaimCount);
    }

    @Test
    public void shouldNotRetryStartWhenNoIdleCapacityCanBeReclaimed() {
        CapacityReclaimStarter starter = new CapacityReclaimStarter(false, true);
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()), starter, 100, 1,
                "capacity-instance", new PluginStartCoordinator());

        assertFalse(service.ensureStarted("plugin-a"));
        assertEquals(1, starter.startCount);
        assertEquals(1, starter.reclaimCount);
    }

    @Test
    public void shouldNotLoopWhenRetryStillHitsCapacity() {
        CapacityReclaimStarter starter = new CapacityReclaimStarter(true, true);
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()), starter, 100, 1,
                "capacity-instance", new PluginStartCoordinator());

        assertFalse(service.ensureStarted("plugin-a"));
        assertEquals(2, starter.startCount);
        assertEquals(1, starter.reclaimCount);
    }

    @Test
    public void shouldBoundWaitersBehindBlockedSingleFlightOwner() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        BlockingStartStarter starter = new BlockingStartStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()), starter, 100, 5,
                "single-flight-instance", coordinator);
        AtomicBoolean ownerResult = new AtomicBoolean();
        AtomicBoolean waiterResult = new AtomicBoolean(true);

        Thread owner = new Thread(() -> ownerResult.set(service.ensureStarted("plugin-a")));
        owner.start();
        assertTrue(starter.startEntered.await(1, TimeUnit.SECONDS));

        Thread waiter = new Thread(() -> waiterResult.set(service.ensureStarted("plugin-a")));
        waiter.start();
        waiter.join(1000L);

        assertFalse("waiter must use the in-flight deadline instead of blocking on the operation lock", waiter.isAlive());
        assertFalse(waiterResult.get());
        assertEquals(1, starter.startCount);

        starter.releaseStart.countDown();
        owner.join(2000L);
        assertFalse(owner.isAlive());
        assertTrue(ownerResult.get());
    }

    @Test
    public void shouldCancelReadinessWaitWithoutApplyingFailureBackoff() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        InitializingStarter starter = new InitializingStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()), starter, 30000, 5,
                "cancel-instance", coordinator);
        AtomicBoolean result = new AtomicBoolean(true);

        Thread startThread = new Thread(() -> result.set(service.ensureStarted("plugin-a")));
        startThread.start();
        assertTrue(starter.startCalled.await(1, TimeUnit.SECONDS));

        assertTrue(coordinator.cancelStart("plugin-a"));
        startThread.join(1000L);

        assertFalse(startThread.isAlive());
        assertFalse(result.get());
    }

    @Test
    public void shouldReleaseConcurrentStartPermitWhenProcessIsNoLongerViable() {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        ExitAwareStarter starter = new ExitAwareStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()), starter, 30000, 5,
                "exit-instance", coordinator);
        long startedAt = System.currentTimeMillis();

        assertFalse(service.ensureStarted("plugin-a"));
        assertTrue(service.ensureStarted("plugin-b"));

        assertTrue("an exited process must not hold the only start permit until the 30 second timeout",
                System.currentTimeMillis() - startedAt < 1000L);
        assertEquals(2, starter.startCount);
    }

    @Test
    public void shouldNotStartIdentityThatWasReplacedWhileWaitingForCoordination() {
        ReplacedIdentityStarter starter = new ReplacedIdentityStarter();
        PluginRuntimeStateService service = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()), starter, 100, 1,
                "replaced-identity-instance", new PluginStartCoordinator());

        assertFalse(service.ensureStarted("plugin-old"));
        assertEquals(0, starter.startCount);
    }

    private static class FakeStarter implements PluginRuntimeStarter {

        private boolean started;
        private int startCount;

        @Override
        public boolean isStarted(String pluginId) {
            return started;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public void start(PluginIdentity identity) {
            startCount++;
            started = true;
        }
    }

    private PluginRuntimeState runtimeState(String pluginId, PluginRuntimeInstanceState instance) {
        PluginRuntimeState state = new PluginRuntimeState();
        state.setPluginId(pluginId);
        state.setPluginName("reminder");
        state.setInstances(Collections.singletonList(instance));
        return state;
    }

    private PluginRuntimeInstanceState runtimeInstance(String instanceId, int activeInvocationCount) {
        PluginRuntimeInstanceState instance = new PluginRuntimeInstanceState();
        instance.setInstanceId(instanceId);
        instance.setStatus("ready");
        instance.setActiveInvocationCount(activeInvocationCount);
        return instance;
    }

    private void assertNoActiveInvocations(PluginRuntimeState state) {
        assertEquals(Integer.valueOf(0), state.getActiveInvocationCount());
        assertTrue(state.getInstances().get(0).getActiveInvocationIds().isEmpty());
    }

    private static class FailingStarter implements PluginRuntimeStarter {

        @Override
        public boolean isStarted(String pluginId) {
            return false;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public void start(PluginIdentity identity) {
            throw new RuntimeException("missing file");
        }
    }

    private static class ReplacedIdentityStarter implements PluginRuntimeStarter {

        private int findCount;
        private int startCount;

        @Override
        public boolean isStarted(String pluginId) {
            return false;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            findCount++;
            if (findCount == 1) {
                return Optional.of(new PluginIdentity(pluginId, "same-short-name"));
            }
            return Optional.empty();
        }

        @Override
        public void start(PluginIdentity identity) {
            startCount++;
        }
    }

    private static class StartedStarter implements PluginRuntimeStarter {

        @Override
        public boolean isStarted(String pluginId) {
            return true;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public void start(PluginIdentity identity) {
        }
    }

    private static class UnknownStarter implements PluginRuntimeStarter {

        @Override
        public boolean isStarted(String pluginId) {
            return false;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.empty();
        }

        @Override
        public void start(PluginIdentity identity) {
        }
    }

    private static class BoundaryStarter implements PluginRuntimeStarter {

        private int checks;
        private int cleanupCount;

        @Override
        public boolean isStarted(String pluginId) {
            return ++checks >= 4;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public boolean managesRuntimeState() {
            return true;
        }

        @Override
        public void cleanupStartFailure(PluginIdentity identity) {
            cleanupCount++;
        }

        @Override
        public void start(PluginIdentity identity) {
        }
    }

    private static class InitializingStarter implements PluginRuntimeStarter {

        private final CountDownLatch startCalled = new CountDownLatch(1);
        private volatile boolean started;
        private volatile boolean ready;

        @Override
        public boolean isStarted(String pluginId) {
            return started;
        }

        @Override
        public boolean isReady(String pluginId) {
            return ready;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public void start(PluginIdentity identity) {
            started = true;
            startCalled.countDown();
        }
    }

    private static class CapacityReclaimStarter implements PluginRuntimeStarter {

        private final boolean reclaimResult;
        private final boolean retryDeferred;
        private int startCount;
        private int reclaimCount;
        private boolean ready;

        private CapacityReclaimStarter(boolean reclaimResult, boolean retryDeferred) {
            this.reclaimResult = reclaimResult;
            this.retryDeferred = retryDeferred;
        }

        @Override
        public boolean isStarted(String pluginId) {
            return ready;
        }

        @Override
        public boolean isReady(String pluginId) {
            return ready;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public void start(PluginIdentity identity) {
            startCount++;
            if (startCount == 1 || retryDeferred) {
                throw new PluginStartDeferredException("capacity reached");
            }
            ready = true;
        }

        @Override
        public boolean reclaimIdleCapacity(PluginIdentity identity) {
            reclaimCount++;
            return reclaimResult;
        }
    }

    private static class BlockingStartStarter implements PluginRuntimeStarter {

        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStart = new CountDownLatch(1);
        private volatile boolean ready;
        private volatile int startCount;

        @Override
        public boolean isStarted(String pluginId) {
            return ready;
        }

        @Override
        public boolean isReady(String pluginId) {
            return ready;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public void start(PluginIdentity identity) {
            startCount++;
            startEntered.countDown();
            try {
                releaseStart.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            ready = true;
        }
    }

    private static class ExitAwareStarter implements PluginRuntimeStarter {

        private volatile int startCount;
        private volatile boolean pluginBReady;

        @Override
        public boolean isStarted(String pluginId) {
            return "plugin-b".equals(pluginId) && pluginBReady;
        }

        @Override
        public boolean isReady(String pluginId) {
            return isStarted(pluginId);
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, pluginId));
        }

        @Override
        public int maxConcurrentStarts() {
            return 1;
        }

        @Override
        public boolean isStartViable(PluginIdentity identity) {
            return !"plugin-a".equals(identity.getPluginId());
        }

        @Override
        public void start(PluginIdentity identity) {
            startCount++;
            if ("plugin-b".equals(identity.getPluginId())) {
                pluginBReady = true;
            }
        }
    }

    private static class FailingInvocationKvStore extends InMemoryRuntimeKvStore {

        private boolean failBeforeNextStateWrite;
        private boolean failAfterNextStateWrite;

        private void failBeforeNextStateWrite() {
            failBeforeNextStateWrite = true;
        }

        private void failAfterNextStateWrite() {
            failAfterNextStateWrite = true;
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            if (PluginRuntimeStateStore.KEY.equals(key) && failBeforeNextStateWrite) {
                failBeforeNextStateWrite = false;
                throw new IllegalStateException("state store failed before write");
            }
            boolean updated = super.compareAndSet(key, expectedValue, value);
            if (PluginRuntimeStateStore.KEY.equals(key) && updated && failAfterNextStateWrite) {
                failAfterNextStateWrite = false;
                throw new IllegalStateException("state store failed after write");
            }
            return updated;
        }
    }
}
