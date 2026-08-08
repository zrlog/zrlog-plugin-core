package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.CronParseException;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityInvoker;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.InvokeContext;
import com.zrlog.plugincore.server.runtime.capability.RuntimeSources;
import com.zrlog.plugincore.server.runtime.capability.TrackingCapabilityInvoker;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.state.PluginIdentity;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStarter;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SchedulerRuntimeTest {

    @Test
    public void shouldExecuteDueAutomationAndUpdateNextRunAt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        PluginAutomation automation = automation("a1", true);
        automation.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(automation));

        SchedulerTickResult result = runtime(kvStore, automationStore, runStore, capabilityStore, successInvoker()).tick(now());

        assertEquals(1, result.getExecutedCount());
        assertEquals(1, runStore.list().size());
        assertEquals("success", runStore.list().get(0).getStatus());
        assertEquals("scheduler", new InvocationLogStore(kvStore).list().get(0).getSource());
        assertEquals(Long.valueOf(SchedulerTimes.millis(now())), automationStore.list().get(0).getLastRunAt());
        assertEquals(Long.valueOf(SchedulerTimes.millis(now().plusMinutes(5))), automationStore.list().get(0).getNextRunAt());
    }

    @Test
    public void shouldSkipDisabledAutomation() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        automationStore.saveAll(Collections.singletonList(automation("a1", false)));

        SchedulerTickResult result = runtime(kvStore, automationStore, runStore, capabilityStore, successInvoker()).tick(now());

        assertEquals(2, result.getSkippedCount());
        assertEquals(0, runStore.list().size());
    }

    @Test
    public void shouldRejectCapabilityWithoutSchedulerExposure() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "internal"));
        PluginAutomation automation = automation("a1", true);
        automation.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(automation));

        SchedulerTickResult result = runtime(kvStore, automationStore, runStore, capabilityStore, successInvoker()).tick(now());

        assertEquals(1, result.getFailedCount());
        assertEquals("Capability is not exposed to scheduler", runStore.list().get(0).getErrorMessage());
        assertEquals("Capability is not exposed to scheduler", new InvocationLogStore(kvStore).list().get(0).getErrorMessage());
    }

    @Test
    public void shouldRejectDisabledCapability() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        capability.setEnabled(Boolean.FALSE);
        capabilityStore.register(capability);
        PluginAutomation automation = automation("a1", true);
        automation.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(automation));

        SchedulerTickResult result = runtime(kvStore, automationStore, runStore, capabilityStore, successInvoker()).tick(now());

        assertEquals(1, result.getFailedCount());
        assertEquals("Capability is disabled", runStore.list().get(0).getErrorMessage());
        assertEquals("Capability is disabled", new InvocationLogStore(kvStore).list().get(0).getErrorMessage());
    }

    @Test
    public void shouldRunAutomationImmediatelyEvenWhenNextRunIsFuture() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        PluginAutomation automation = automation("a1", true);
        automation.setNextRunAt(SchedulerTimes.millis(now().plusMinutes(30)));
        automationStore.saveAll(Collections.singletonList(automation));

        PluginAutomationRun run = runtime(kvStore, automationStore, runStore, capabilityStore, successInvoker()).runNow("a1", now());

        assertEquals("success", run.getStatus());
        assertEquals(1, runStore.list().size());
        assertEquals("tick", new InvocationLogStore(kvStore).list().get(0).getSource());
        assertEquals(Long.valueOf(SchedulerTimes.millis(now())), automationStore.list().get(0).getLastRunAt());
        assertEquals(Long.valueOf(SchedulerTimes.millis(now().plusMinutes(30))), automationStore.list().get(0).getNextRunAt());
    }

    @Test
    public void shouldRecordAutomationRunDurationFromExecutionClock() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        PluginAutomation automation = automation("a1", true);
        automation.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(automation));

        SchedulerTickResult result = runtime(kvStore, automationStore, runStore, capabilityStore, sleepingInvoker(25)).tick(now());

        assertEquals(1, result.getExecutedCount());
        assertTrue(runStore.list().get(0).getDurationMs() > 0);
    }

    @Test
    public void shouldLogTickSourceWhenManualTickExecutesDueAutomation() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        PluginAutomation automation = automation("a1", true);
        automation.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(automation));

        SchedulerTickResult result = runtime(kvStore, automationStore, runStore, capabilityStore, successInvoker()).tick(now(), RuntimeSources.TICK);

        assertEquals(1, result.getExecutedCount());
        assertEquals("tick", new InvocationLogStore(kvStore).list().get(0).getSource());
    }

    @Test
    public void shouldExecuteDueAutomationsConcurrentlyAndWaitForAll() throws Exception {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "task.one", "scheduler"));
        capabilityStore.register(capability("plugin-b", "task.two", "scheduler"));
        PluginAutomation first = automation("a1", true, "plugin-a", "task.one");
        first.setNextRunAt(SchedulerTimes.millis(now()));
        PluginAutomation second = automation("a2", true, "plugin-b", "task.two");
        second.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Arrays.asList(first, second));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        SchedulerRuntime runtime = runtimeWithoutTracking(automationStore, runStore, capabilityStore, blockingInvoker(started, release));
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<SchedulerTickResult> tickResult = new AtomicReference<>();

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                tickResult.set(runtime.tick(now()));
                completed.set(true);
            }
        });
        thread.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertFalse(completed.get());

        release.countDown();
        thread.join(3000);

        assertTrue(completed.get());
        assertEquals(2, tickResult.get().getExecutedCount());
        assertEquals(2, runStore.list().size());
    }

    @Test
    public void shouldBoundEachTickAndContinueWithOldestDueAutomations() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        int automationCount = SchedulerRuntime.maxTickTasks() + 16;
        assertEquals(64, SchedulerRuntime.maxTickTasks());
        List<PluginAutomation> automations = dueAutomationsWithDistinctLockStripes(automationCount);
        automationStore.saveAll(automations);
        Set<String> invokedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger invocationCount = new AtomicInteger();
        SchedulerRuntime runtime = runtimeWithoutTracking(automationStore, runStore, capabilityStore,
                recordingInvoker(invokedKeys, invocationCount));

        SchedulerTickResult firstTick = runtime.tick(now());

        assertEquals(SchedulerRuntime.maxTickTasks(), firstTick.getExecutedCount());
        assertEquals(SchedulerRuntime.maxTickTasks(), invocationCount.get());
        Set<String> remainingKeys = automationKeys(automations);
        remainingKeys.removeAll(invokedKeys);
        assertEquals(16, remainingKeys.size());
        for (PluginAutomation current : automationStore.list()) {
            if (remainingKeys.contains(executionKey(current))) {
                assertTrue(current.getNextRunAt() <= SchedulerTimes.millis(now()));
                assertTrue(current.getLeaseOwner() == null);
            }
        }

        invokedKeys.clear();
        invocationCount.set(0);
        SchedulerTickResult secondTick = runtime.tick(now().plusMinutes(1));

        assertTrue(secondTick.getExecutedCount() >= remainingKeys.size());
        assertTrue(invocationCount.get() <= SchedulerRuntime.maxTickTasks());
        assertTrue(invokedKeys.containsAll(remainingKeys));
    }

    @Test
    public void shouldRejectRunNowWhenSameCapabilityIsRunning() throws Exception {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationStore automationStore = new AutomationStore(kvStore);
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        PluginAutomation automation = automation("a1", true);
        automation.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(automation));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SchedulerRuntime runtime = runtime(kvStore, automationStore, runStore, capabilityStore, blockingInvoker(started, release));

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runtime.tick(now());
            }
        });
        thread.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));

        try {
            runtime.runNow("a1", now());
            fail("runNow should reject running automation");
        } catch (CronParseException e) {
            assertEquals("Automation is already running", e.getMessage());
        } finally {
            release.countDown();
            thread.join(3000);
        }
    }

    @Test
    public void shouldUseFixedExecutionLockStripes() {
        assertEquals(256, SchedulerRuntime.executionLockStripeCount());
        assertEquals(SchedulerRuntime.executionLockIndex("plugin-a:capability-a"),
                SchedulerRuntime.executionLockIndex("plugin-a:capability-a"));
        for (int i = 0; i < 10000; i++) {
            int index = SchedulerRuntime.executionLockIndex("plugin-" + i + ":capability-" + i);
            assertTrue(index >= 0);
            assertTrue(index < SchedulerRuntime.executionLockStripeCount());
        }
        assertEquals(256, SchedulerRuntime.executionLockStripeCount());
    }

    @Test
    public void shouldNormalizeRuntimeMaintenanceLoadStrategy() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("onDemandEnabled", Boolean.FALSE);
        payload.put("autoDownloadMissingPluginFileEnabled", Boolean.FALSE);
        payload.put("idleStopEnabled", Boolean.TRUE);
        payload.put("idleTimeoutSeconds", 300L);
        payload.put("idleScanIntervalSeconds", 61L);
        payload.put("maxRunningPlugins", 100L);
        payload.put("maxConcurrentStarts", 0L);
        payload.put("startFailureBackoffSeconds", 0L);

        PluginRuntimeSetting setting = RuntimeSystemAutomations.runtimeSettingFromPayload(payload);
        Map<String, Object> normalizedPayload = RuntimeSystemAutomations.runtimePayload(setting);

        assertFalse(setting.getOnDemandEnabled());
        assertFalse(setting.getAutoDownloadMissingPluginFileEnabled());
        assertFalse(setting.getIdleStopEnabled());
        assertEquals(Long.valueOf(120L), setting.getIdleScanIntervalSeconds());
        assertEquals(Long.valueOf(32L), setting.getMaxRunningPlugins());
        assertEquals(Long.valueOf(1L), setting.getMaxConcurrentStarts());
        assertEquals(Long.valueOf(1L), setting.getStartFailureBackoffSeconds());
        assertEquals(Boolean.FALSE, normalizedPayload.get("onDemandEnabled"));
        assertEquals(Boolean.FALSE, normalizedPayload.get("autoDownloadMissingPluginFileEnabled"));
        assertEquals(Boolean.FALSE, normalizedPayload.get("idleStopEnabled"));
        assertEquals(Long.valueOf(120L), normalizedPayload.get("idleScanIntervalSeconds"));
        assertEquals(Long.valueOf(32L), normalizedPayload.get("maxRunningPlugins"));
        assertEquals(Long.valueOf(1L), normalizedPayload.get("maxConcurrentStarts"));
        assertEquals(Long.valueOf(1L), normalizedPayload.get("startFailureBackoffSeconds"));
    }

    @Test
    public void shouldKeepRuntimeMaintenanceSystemTaskEnabled() {
        PluginAutomation automation = new PluginAutomation();
        automation.setId(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_ID);
        automation.setPluginId(RuntimeSystemAutomations.SYSTEM_PLUGIN_ID);
        automation.setCapabilityKey(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_KEY);
        automation.setCron("*/5 * * * *");
        automation.setEnabled(Boolean.FALSE);
        Map<String, Object> payload = new HashMap<>();
        payload.put("onDemandEnabled", Boolean.FALSE);
        payload.put("idleStopEnabled", Boolean.TRUE);
        automation.setPayload(payload);
        ArrayList<PluginAutomation> automations = new ArrayList<>();
        automations.add(automation);

        PluginRuntimeSetting setting = new PluginRuntimeSetting();
        setting.setOnDemandEnabled(Boolean.FALSE);
        boolean changed = RuntimeSystemAutomations.ensureRuntimeMaintenance(
                automations, setting, new BasicCronParser(), now());

        assertTrue(changed);
        assertTrue(automation.getEnabled());
        assertFalse((Boolean) automation.getPayload().get("idleStopEnabled"));
    }

    @Test
    public void shouldCreateRuntimeMaintenanceFromConfiguredInterval() {
        ArrayList<PluginAutomation> automations = new ArrayList<>();
        PluginRuntimeSetting setting = new PluginRuntimeSetting();
        setting.setIdleScanIntervalSeconds(421L);

        boolean changed = RuntimeSystemAutomations.ensureRuntimeMaintenance(
                automations, setting, new BasicCronParser(), now());

        assertTrue(changed);
        assertEquals(1, automations.size());
        assertEquals("*/10 * * * *", automations.get(0).getCron());
        assertEquals(Long.valueOf(600L), automations.get(0).getPayload().get("idleScanIntervalSeconds"));
        assertEquals(Long.valueOf(SchedulerTimes.millis(now().plusMinutes(10))), automations.get(0).getNextRunAt());
    }

    @Test
    public void shouldMigrateLegacyRuntimeMaintenanceCronWithoutChangingCadence() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("onDemandEnabled", Boolean.FALSE);
        payload.put("autoDownloadMissingPluginFileEnabled", Boolean.FALSE);
        payload.put("idleStopEnabled", Boolean.FALSE);
        payload.put("idleTimeoutSeconds", 900L);
        PluginAutomation automation = runtimeMaintenanceAutomation("*/15 * * * *", payload);
        ArrayList<PluginAutomation> automations = new ArrayList<>();
        automations.add(automation);

        boolean changed = RuntimeSystemAutomations.ensureRuntimeMaintenance(
                automations, new PluginRuntimeSetting(), new BasicCronParser(), now());

        assertTrue(changed);
        assertEquals("*/15 * * * *", automation.getCron());
        assertEquals(Long.valueOf(900L), automation.getPayload().get("idleScanIntervalSeconds"));
        assertEquals(Boolean.FALSE, automation.getPayload().get("onDemandEnabled"));
        assertEquals(Boolean.FALSE, automation.getPayload().get("autoDownloadMissingPluginFileEnabled"));
        assertEquals(Boolean.FALSE, automation.getPayload().get("idleStopEnabled"));
        assertEquals(Long.valueOf(900L), automation.getPayload().get("idleTimeoutSeconds"));
        assertEquals(Long.valueOf(SchedulerTimes.millis(now().plusMinutes(15))), automation.getNextRunAt());
        assertFalse(RuntimeSystemAutomations.ensureRuntimeMaintenance(
                automations, new PluginRuntimeSetting(), new BasicCronParser(), now()));
    }

    @Test
    public void shouldPreserveUnsupportedLegacyRuntimeMaintenanceCron() {
        PluginAutomation automation = runtimeMaintenanceAutomation("*/7 * * * *", new HashMap<>());
        ArrayList<PluginAutomation> automations = new ArrayList<>();
        automations.add(automation);

        RuntimeSystemAutomations.ensureRuntimeMaintenance(
                automations, new PluginRuntimeSetting(), new BasicCronParser(), now());

        assertEquals("*/7 * * * *", automation.getCron());
        assertFalse(automation.getPayload().containsKey("idleScanIntervalSeconds"));
    }

    @Test
    public void shouldUseExplicitRuntimeMaintenanceIntervalAndRecomputeNextRun() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("idleScanIntervalSeconds", 600L);
        PluginAutomation automation = runtimeMaintenanceAutomation("*/5 * * * *", payload);
        automation.setNextRunAt(SchedulerTimes.millis(now().plusMinutes(5)));
        ArrayList<PluginAutomation> automations = new ArrayList<>();
        automations.add(automation);

        RuntimeSystemAutomations.ensureRuntimeMaintenance(
                automations, new PluginRuntimeSetting(), new BasicCronParser(), now());

        assertEquals("*/10 * * * *", automation.getCron());
        assertEquals(Long.valueOf(600L), automation.getPayload().get("idleScanIntervalSeconds"));
        assertEquals(Long.valueOf(SchedulerTimes.millis(now().plusMinutes(10))), automation.getNextRunAt());
    }

    private PluginAutomation runtimeMaintenanceAutomation(String cron, Map<String, Object> payload) {
        PluginAutomation automation = new PluginAutomation();
        automation.setId(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_ID);
        automation.setName("运行态维护");
        automation.setPluginId(RuntimeSystemAutomations.SYSTEM_PLUGIN_ID);
        automation.setCapabilityKey(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_KEY);
        automation.setCron(cron);
        automation.setEnabled(Boolean.TRUE);
        automation.setPayload(payload);
        return automation;
    }

    private SchedulerRuntime runtime(InMemoryRuntimeKvStore kvStore,
                                     AutomationStore automationStore,
                                     AutomationRunStore runStore,
                                     CapabilityStore capabilityStore,
                                     CapabilityInvoker invoker) {
        return new SchedulerRuntime(automationStore, runStore, capabilityStore, trackingInvoker(kvStore, capabilityStore, invoker),
                new BasicCronParser(), localTaskLockFactory(), PluginRuntimeSetting::new);
    }

    private SchedulerRuntime runtimeWithoutTracking(AutomationStore automationStore,
                                                    AutomationRunStore runStore,
                                                    CapabilityStore capabilityStore,
                                                    CapabilityInvoker invoker) {
        return new SchedulerRuntime(automationStore, runStore, capabilityStore, invoker,
                new BasicCronParser(), localTaskLockFactory(), PluginRuntimeSetting::new);
    }

    private SchedulerRuntime.SchedulerTaskLockFactory localTaskLockFactory() {
        return new SchedulerRuntime.SchedulerTaskLockFactory() {
            @Override
            public SchedulerRuntime.SchedulerTaskLock create(String key) {
                return new SchedulerRuntime.SchedulerTaskLock() {
                    @Override
                    public boolean tryLock() {
                        return true;
                    }

                    @Override
                    public void unlock() {
                    }
                };
            }
        };
    }

    private CapabilityInvoker trackingInvoker(InMemoryRuntimeKvStore kvStore,
                                              CapabilityStore capabilityStore,
                                              CapabilityInvoker delegate) {
        return new TrackingCapabilityInvoker(
                delegate,
                new PluginRuntimeStateService(new PluginRuntimeStateStore(kvStore), new FakeStarter(), 1, 1),
                new InvocationLogStore(kvStore),
                capabilityStore
        );
    }

    private CapabilityInvoker successInvoker() {
        return new CapabilityInvoker() {
            @Override
            public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
                CapabilityInvokeResult result = new CapabilityInvokeResult();
                result.setSuccess(true);
                return result;
            }
        };
    }

    private CapabilityInvoker blockingInvoker(final CountDownLatch started, final CountDownLatch release) {
        return new CapabilityInvoker() {
            @Override
            public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                CapabilityInvokeResult result = new CapabilityInvokeResult();
                result.setSuccess(true);
                return result;
            }
        };
    }

    private CapabilityInvoker sleepingInvoker(final long sleepMillis) {
        return new CapabilityInvoker() {
            @Override
            public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                CapabilityInvokeResult result = new CapabilityInvokeResult();
                result.setSuccess(true);
                return result;
            }
        };
    }

    private CapabilityInvoker recordingInvoker(final Set<String> invokedKeys, final AtomicInteger invocationCount) {
        return new CapabilityInvoker() {
            @Override
            public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
                invokedKeys.add(pluginId + ":" + capabilityKey);
                invocationCount.incrementAndGet();
                CapabilityInvokeResult result = new CapabilityInvokeResult();
                result.setSuccess(true);
                return result;
            }
        };
    }

    private List<PluginAutomation> dueAutomationsWithDistinctLockStripes(int count) {
        List<PluginAutomation> automations = new ArrayList<>();
        Set<Integer> lockStripes = new HashSet<>();
        for (int candidate = 0; automations.size() < count; candidate++) {
            String pluginId = "plugin-" + candidate;
            String capabilityKey = "task-" + candidate;
            if (!lockStripes.add(SchedulerRuntime.executionLockIndex(pluginId + ":" + capabilityKey))) {
                continue;
            }
            PluginAutomation automation = automation("automation-" + candidate, true, pluginId, capabilityKey);
            automation.setCron("* * * * *");
            automation.setNextRunAt(SchedulerTimes.millis(now()));
            automations.add(automation);
        }
        return automations;
    }

    private Set<String> automationKeys(List<PluginAutomation> automations) {
        Set<String> keys = new HashSet<>();
        for (PluginAutomation automation : automations) {
            keys.add(executionKey(automation));
        }
        return keys;
    }

    private String executionKey(PluginAutomation automation) {
        return automation.getPluginId() + ":" + automation.getCapabilityKey();
    }

    private PluginAutomation automation(String id, boolean enabled) {
        return automation(id, enabled, "plugin-a", "reminder.scanDueTasks");
    }

    private PluginAutomation automation(String id, boolean enabled, String pluginId, String capabilityKey) {
        PluginAutomation automation = new PluginAutomation();
        automation.setId(id);
        automation.setPluginId(pluginId);
        automation.setCapabilityKey(capabilityKey);
        automation.setName("Scan");
        automation.setCron("*/5 * * * *");
        automation.setTimezone("Asia/Shanghai");
        automation.setEnabled(enabled);
        return automation;
    }

    private PluginCapability capability(String pluginId, String key, String exposure) {
        PluginCapability capability = new PluginCapability();
        capability.setPluginId(pluginId);
        capability.setPluginName(pluginId);
        capability.setKey(key);
        capability.setType("scheduled");
        capability.setExposure(Arrays.asList(exposure));
        capability.setEnabled(Boolean.TRUE);
        return capability;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.of(2026, 5, 29, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
    }

    private static class FakeStarter implements PluginRuntimeStarter {

        private boolean startedAfterStart = true;
        private boolean startCalled;

        private FakeStarter() {
        }

        private FakeStarter(boolean startedAfterStart) {
            this.startedAfterStart = startedAfterStart;
        }

        @Override
        public boolean isStarted(String pluginId) {
            return startCalled && startedAfterStart;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, pluginId));
        }

        @Override
        public void start(PluginIdentity identity) {
            startCalled = true;
        }
    }
}
