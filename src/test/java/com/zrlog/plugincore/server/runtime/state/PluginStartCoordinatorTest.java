package com.zrlog.plugincore.server.runtime.state;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginStartCoordinatorTest {

    @Test
    public void shouldCollapseConcurrentStartsForSamePlugin() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        AtomicInteger startCount = new AtomicInteger();
        AtomicBoolean firstResult = new AtomicBoolean();
        AtomicBoolean secondResult = new AtomicBoolean();

        Thread first = new Thread(() -> firstResult.set(coordinator.start("plugin-a", 2, 1000, 1000, 0, () -> {
            startCount.incrementAndGet();
            actionStarted.countDown();
            await(releaseAction);
            return true;
        })));
        first.start();
        assertTrue(actionStarted.await(1, TimeUnit.SECONDS));

        Thread second = new Thread(() -> secondResult.set(coordinator.start("plugin-a", 2, 1000, 1000, 0, () -> {
            startCount.incrementAndGet();
            return true;
        })));
        second.start();
        assertTrue(awaitBlocked(second));
        releaseAction.countDown();
        first.join(2000);
        second.join(2000);

        assertTrue(firstResult.get());
        assertTrue(secondResult.get());
        assertEquals(1, startCount.get());
    }

    @Test
    public void shouldBoundConcurrentStarts() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        AtomicBoolean firstResult = new AtomicBoolean();
        AtomicInteger rejectedActionCount = new AtomicInteger();

        Thread first = new Thread(() -> firstResult.set(coordinator.start("plugin-a", 1, 1000, 1000, 0, () -> {
            actionStarted.countDown();
            await(releaseAction);
            return true;
        })));
        first.start();
        assertTrue(actionStarted.await(1, TimeUnit.SECONDS));

        boolean secondResult = coordinator.start("plugin-b", 1, 20, 1000, 0, () -> {
            rejectedActionCount.incrementAndGet();
            return true;
        });
        releaseAction.countDown();
        first.join(2000);

        assertFalse(secondResult);
        assertTrue(firstResult.get());
        assertEquals(0, rejectedActionCount.get());
    }

    @Test
    public void shouldBackOffAfterStartFailure() {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        AtomicInteger startCount = new AtomicInteger();

        assertFalse(coordinator.start("plugin-a", 1, 100, 100, 1000, () -> {
            startCount.incrementAndGet();
            return false;
        }));
        assertFalse(coordinator.start("plugin-a", 1, 100, 100, 1000, () -> {
            startCount.incrementAndGet();
            return true;
        }));

        assertEquals(1, startCount.get());
    }

    @Test
    public void shouldKeepAsynchronousFailureRecordedBeforeSuccessfulStartReturns() {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        AtomicInteger startCount = new AtomicInteger();

        assertTrue(coordinator.start("plugin-a", 1, 100, 100, 1000, () -> {
            startCount.incrementAndGet();
            coordinator.recordFailure("plugin-a", 1000L);
            return true;
        }));
        assertFalse(coordinator.start("plugin-a", 1, 100, 100, 1000, () -> {
            startCount.incrementAndGet();
            return true;
        }));

        assertEquals(1, startCount.get());
    }

    @Test
    public void shouldIgnoreInvalidAsynchronousFailureRecords() {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        AtomicInteger startCount = new AtomicInteger();

        coordinator.recordFailure(null, 1000L);
        coordinator.recordFailure(" ", 1000L);
        coordinator.recordFailure("plugin-a", 0L);

        assertTrue(coordinator.start("plugin-a", 1, 100, 100, 1000, () -> {
            startCount.incrementAndGet();
            return true;
        }));
        assertEquals(1, startCount.get());
    }

    @Test
    public void shouldBackOffAfterDeferredStart() {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        AtomicInteger startCount = new AtomicInteger();

        assertFalse(coordinator.start("plugin-a", 1, 100, 100, 1000, () -> {
            startCount.incrementAndGet();
            throw new PluginStartDeferredException("capacity reached");
        }));
        assertFalse(coordinator.start("plugin-a", 1, 100, 100, 1000, () -> {
            startCount.incrementAndGet();
            return true;
        }));

        assertEquals(1, startCount.get());
    }

    @Test
    public void shouldBackOffAfterCapacityWaitTimesOut() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger secondActionCount = new AtomicInteger();

        Thread first = new Thread(() -> coordinator.start("plugin-a", 1, 1000, 1000, 0, () -> {
            firstStarted.countDown();
            await(releaseFirst);
            return true;
        }));
        first.start();
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        assertFalse(coordinator.start("plugin-b", 1, 20, 1000, 1000,
                () -> secondActionCount.incrementAndGet() > 0));
        releaseFirst.countDown();
        first.join(2000);
        assertFalse(coordinator.start("plugin-b", 1, 20, 1000, 1000,
                () -> secondActionCount.incrementAndGet() > 0));

        assertEquals(0, secondActionCount.get());
    }

    @Test
    public void shouldShareCapacityBetweenMetadataAndOnDemandStarts() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch metadataStarted = new CountDownLatch(1);
        CountDownLatch releaseMetadata = new CountDownLatch(1);
        AtomicBoolean metadataResult = new AtomicBoolean();
        AtomicInteger onDemandActionCount = new AtomicInteger();

        Thread metadata = new Thread(() -> metadataResult.set(coordinator.withCapacity(1, 1000, () -> {
            metadataStarted.countDown();
            await(releaseMetadata);
            return true;
        })));
        metadata.start();
        assertTrue(metadataStarted.await(1, TimeUnit.SECONDS));

        boolean onDemandResult = coordinator.start("plugin-b", 1, 20, 1000, 0, () -> {
            onDemandActionCount.incrementAndGet();
            return true;
        });
        releaseMetadata.countDown();
        metadata.join(2000);

        assertFalse(onDemandResult);
        assertTrue(metadataResult.get());
        assertEquals(0, onDemandActionCount.get());
    }

    @Test
    public void shouldSerializePluginOperationsForSamePlugin() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger activeOperations = new AtomicInteger();
        AtomicInteger maxActiveOperations = new AtomicInteger();
        AtomicBoolean secondResult = new AtomicBoolean();

        Thread first = new Thread(() -> coordinator.withPluginOperation("plugin-a", 1000, () -> {
            int active = activeOperations.incrementAndGet();
            maxActiveOperations.accumulateAndGet(active, Math::max);
            firstStarted.countDown();
            await(releaseFirst);
            activeOperations.decrementAndGet();
            return true;
        }));
        first.start();
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        Thread second = new Thread(() -> secondResult.set(
                coordinator.withPluginOperation("plugin-a", 1000, () -> {
                    int active = activeOperations.incrementAndGet();
                    maxActiveOperations.accumulateAndGet(active, Math::max);
                    activeOperations.decrementAndGet();
                    return true;
                })));
        second.start();
        assertTrue(awaitBlocked(second));
        releaseFirst.countDown();
        first.join(2000);
        second.join(2000);

        assertTrue(secondResult.get());
        assertEquals(1, maxActiveOperations.get());
    }

    @Test
    public void shouldPublishDemandAfterConcurrentStopForSamePlugin() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch stopStarted = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        CountDownLatch demandClaimed = new CountDownLatch(1);

        Thread stop = new Thread(() -> coordinator.runIfUnclaimed("plugin-a", () -> {
            stopStarted.countDown();
            await(releaseStop);
        }));
        stop.start();
        assertTrue(stopStarted.await(1, TimeUnit.SECONDS));

        Thread demand = new Thread(() -> {
            coordinator.claimDemand("plugin-a", 30000L);
            demandClaimed.countDown();
        });
        demand.start();
        assertFalse(demandClaimed.await(50, TimeUnit.MILLISECONDS));

        releaseStop.countDown();
        stop.join(2000L);
        demand.join(2000L);

        assertFalse(stop.isAlive());
        assertFalse(demand.isAlive());
        assertEquals(0L, demandClaimed.getCount());
        assertFalse(coordinator.runIfUnclaimed("plugin-a", () -> {
            throw new AssertionError("demand claim was lost after concurrent stop");
        }));
    }

    @Test
    public void shouldAllowUnrelatedDemandWhilePluginStopIsRunning() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch stopStarted = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        CountDownLatch unrelatedDemandClaimed = new CountDownLatch(1);

        Thread stop = new Thread(() -> coordinator.runIfUnclaimed("plugin-a", () -> {
            stopStarted.countDown();
            await(releaseStop);
        }));
        stop.start();
        assertTrue(stopStarted.await(1, TimeUnit.SECONDS));

        Thread unrelatedDemand = new Thread(() -> {
            coordinator.claimDemand("plugin-b", 30000L);
            unrelatedDemandClaimed.countDown();
        });
        unrelatedDemand.start();

        assertTrue(unrelatedDemandClaimed.await(1, TimeUnit.SECONDS));
        assertTrue(stop.isAlive());
        releaseStop.countDown();
        stop.join(2000L);
        unrelatedDemand.join(2000L);
    }

    @Test
    public void shouldKeepMetadataOwnedProcessForQueuedOnDemandWaiter() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch metadataReady = new CountDownLatch(1);
        CountDownLatch releaseMetadata = new CountDownLatch(1);
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean destroyed = new AtomicBoolean();
        AtomicBoolean metadataResult = new AtomicBoolean();
        AtomicReference<Boolean> demandResult = new AtomicReference<>();

        Thread metadataOwner = new Thread(() -> {
            metadataResult.set(coordinator.start("plugin-a", 1, 1000, 5000, 0, () -> {
                ready.set(true);
                metadataReady.countDown();
                await(releaseMetadata);
                return true;
            }));
            coordinator.runIfUnclaimed("plugin-a", () -> destroyed.set(true));
        });
        metadataOwner.start();
        assertTrue(metadataReady.await(1, TimeUnit.SECONDS));

        Thread onDemandWaiter = new Thread(() -> demandResult.set(
                coordinator.claimDemandAndGet("plugin-a", 30000L, 5000L, ready::get)));
        onDemandWaiter.start();
        assertTrue(awaitQueued(coordinator, "plugin-a", onDemandWaiter));

        releaseMetadata.countDown();
        metadataOwner.join(2000L);
        onDemandWaiter.join(2000L);

        assertFalse(metadataOwner.isAlive());
        assertFalse(onDemandWaiter.isAlive());
        assertTrue(metadataResult.get());
        assertEquals(Boolean.TRUE, demandResult.get());
        assertFalse(destroyed.get());
    }

    @Test
    public void shouldPublishReadyDemandBeforeQueuedIdleReclaim() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch operationHeld = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        AtomicReference<String> claimedSession = new AtomicReference<>();
        AtomicBoolean stopped = new AtomicBoolean();

        Thread holder = new Thread(() -> coordinator.runPluginOperation("plugin-a", () -> {
            operationHeld.countDown();
            await(releaseOperation);
        }));
        holder.start();
        assertTrue(operationHeld.await(1, TimeUnit.SECONDS));

        Thread readyRequest = new Thread(() -> claimedSession.set(
                coordinator.claimDemandAndGet("plugin-a", 30000L, 5000L, () -> "ready-session")));
        readyRequest.start();
        assertTrue(awaitQueued(coordinator, "plugin-a", readyRequest));

        Thread idleReclaim = new Thread(() -> coordinator.runIfUnclaimed("plugin-a", () -> stopped.set(true)));
        idleReclaim.start();
        assertTrue(awaitQueued(coordinator, "plugin-a", idleReclaim));

        releaseOperation.countDown();
        holder.join(2000L);
        readyRequest.join(2000L);
        idleReclaim.join(2000L);

        assertEquals("ready-session", claimedSession.get());
        assertFalse(stopped.get());
    }

    @Test
    public void shouldCancelWaitingAndRejectNewStartsAfterShutdown() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstResult = new AtomicBoolean(true);
        AtomicBoolean waitingResult = new AtomicBoolean(true);
        AtomicInteger rejectedActionCount = new AtomicInteger();

        Thread first = new Thread(() -> firstResult.set(coordinator.start("plugin-a", 1, 5000, 5000, 0, () -> {
            firstStarted.countDown();
            await(releaseFirst);
            return true;
        })));
        first.start();
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        Thread waiting = new Thread(() -> waitingResult.set(
                coordinator.start("plugin-b", 1, 5000, 5000, 0, () -> true)));
        waiting.start();
        assertTrue(awaitBlocked(waiting));

        coordinator.shutdown();
        waiting.join(1000L);

        assertFalse(waiting.isAlive());
        assertFalse(waitingResult.get());
        assertFalse(coordinator.start("plugin-c", 1, 100, 100, 0,
                () -> rejectedActionCount.incrementAndGet() > 0));
        assertEquals(0, rejectedActionCount.get());

        releaseFirst.countDown();
        first.join(1000L);
        assertFalse(first.isAlive());
        assertFalse(firstResult.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static boolean awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    private static boolean awaitQueued(PluginStartCoordinator coordinator,
                                       String pluginId,
                                       Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (System.nanoTime() < deadline) {
            if (coordinator.hasQueuedPluginOperation(pluginId, thread)) {
                return true;
            }
            if (!thread.isAlive()) {
                return false;
            }
            Thread.yield();
        }
        return false;
    }
}
