package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityInvoker;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SchedulerRecoveryTest {
    private final InMemoryRuntimeKvStore kv = new InMemoryRuntimeKvStore();
    private final AutomationStore tasks = new AutomationStore(kv);
    private final AutomationRunStore runs = new AutomationRunStore(kv);
    private final CapabilityStore capabilities = new CapabilityStore(kv);
    private final ZonedDateTime now = ZonedDateTime.parse("2026-10-29T10:00:00Z");

    @Test
    public void invocationHasDurableIdentityBeforeEffects() {
        dueTask();
        SchedulerRuntime runtime = runtime(runs, (plugin, key, payload, context) -> {
            PluginAutomationRun stored = runs.find(context.getRequestId()).orElseThrow(AssertionError::new);
            assertEquals("running", stored.getStatus());
            assertNull(stored.getFinishedAt());
            assertEquals(tasks.list().get(0).getLeaseOwner(), stored.getId());
            assertEquals(stored.getTriggerId(), context.getTraceId());
            assertNotNull(context.getTraceId());
            return success();
        });
        SchedulerTickResult result = runtime.tick(now);
        assertEquals(1, result.getExecutedCount());
        assertEquals(result.getTriggerId(), runs.list().get(0).getTriggerId());
        assertEquals(runs.list().get(0).getId(), result.getRunIds().get(0));
        assertEquals(1, runs.list().size());
        assertEquals("success", runs.list().get(0).getStatus());
        assertNull(tasks.list().get(0).getLeaseOwner());
    }

    @Test
    public void missingDurableStartPreventsExecution() {
        dueTask();
        AtomicInteger calls = new AtomicInteger();
        AutomationRunStore unavailable = new AutomationRunStore(kv) {
            @Override public void begin(PluginAutomationRun run) { throw new IllegalStateException("storage offline"); }
        };
        assertEquals(1, runtime(unavailable, (plugin, key, payload, context) -> {
            calls.incrementAndGet(); return success();
        }).tick(now).getFailedCount());
        assertEquals(0, calls.get());
        assertNotNull(tasks.list().get(0).getLeaseOwner());
    }

    @Test
    public void lostCompletionDoesNotReplayEffectsAfterRestart() {
        dueTask();
        AtomicInteger calls = new AtomicInteger();
        CapabilityInvoker effect = (plugin, key, payload, context) -> { calls.incrementAndGet(); return success(); };
        AutomationRunStore unavailable = new AutomationRunStore(kv) {
            @Override public boolean complete(PluginAutomationRun run) { throw new IllegalStateException("storage offline"); }
        };
        runtime(unavailable, effect).tick(now);
        assertEquals(1, calls.get());
        assertEquals("running", runs.list().get(0).getStatus());
        runtime(runs, effect).tick(now.plusMinutes(6));
        assertEquals("running", runs.list().get(0).getStatus());
        ZonedDateTime expires = ZonedDateTime.parse(tasks.list().get(0).getLeaseUntil());
        runtime(runs, effect).tick(expires.plusSeconds(1));
        assertEquals(1, calls.get());
        assertEquals("uncertain", runs.list().get(0).getStatus());
        assertFalse(tasks.list().get(0).getEnabled());
        assertNull(tasks.list().get(0).getLeaseOwner());
    }

    @Test
    public void expiredLegacyClaimWithoutRunRequiresReview() {
        PluginAutomation task = dueTask();
        task.setLeaseOwner("legacy-run");
        task.setLeaseUntil(now.minusSeconds(1).toString());
        tasks.saveAll(Collections.singletonList(task));
        runtime(runs, (plugin, key, payload, context) -> { fail("must not replay"); return null; }).tick(now);
        assertFalse(tasks.list().get(0).getEnabled());
        assertEquals("uncertain", runs.find("legacy-run").get().getStatus());
    }

    @Test
    public void knownCompletedRunReleasesExpiredClaimWithoutReplay() {
        PluginAutomation task = dueTask();
        task.setLeaseOwner("completed-run");
        task.setLeaseUntil(now.minusSeconds(1).toString());
        tasks.saveAll(Collections.singletonList(task));
        PluginAutomationRun run = running("completed-run");
        runs.begin(run);
        run.setStatus("success");
        assertTrue(runs.complete(run));
        runtime(runs, (plugin, key, payload, context) -> { fail("must not replay"); return null; }).tick(now);
        assertTrue(tasks.list().get(0).getEnabled());
        assertNull(tasks.list().get(0).getLeaseOwner());
        assertTrue(tasks.list().get(0).getNextRunAt() > now.toInstant().toEpochMilli());
    }

    @Test
    public void timeoutIsUncertainAndLateCompletionCannotOverwriteRecovery() {
        dueTask();
        runtime(runs, (plugin, key, payload, context) -> {
            context.markOutcomeUncertain();
            return success();
        }).tick(now);
        PluginAutomationRun run = runs.list().get(0);
        assertEquals("uncertain", run.getStatus());
        assertFalse(tasks.list().get(0).getEnabled());
        run.setStatus("success");
        assertFalse(runs.complete(run));
        assertEquals("uncertain", runs.list().get(0).getStatus());
    }

    @Test
    public void completedHistoryRetentionKeepsUnfinishedRun() {
        runs.begin(running("still-running"));
        for (int i = 0; i < 505; i++) {
            PluginAutomationRun run = running("completed-" + i);
            run.setStatus("success");
            runs.append(run);
        }
        assertEquals(501, runs.list().size());
        assertEquals("running", runs.find("still-running").get().getStatus());
    }

    @Test
    public void editingAnExecutingTaskKeepsItsClaimAndDeletionIsRejected() {
        PluginAutomation task = dueTask();
        task.setLeaseOwner("in-flight");
        task.setLeaseUntil(now.plusMinutes(5).toString());
        tasks.saveAll(Collections.singletonList(task));
        PluginCapability capability = new PluginCapability();
        capability.setPluginId("plugin-a"); capability.setKey("test.run"); capability.setEnabled(true);
        capability.setExposure(Collections.singletonList("scheduler"));
        capabilities.register(capability);
        RuntimeAutomationService service = new RuntimeAutomationService(tasks, capabilities, new BasicCronParser());
        PluginAutomation edit = new PluginAutomation();
        edit.setId(task.getId()); edit.setName("Updated"); edit.setCron("*/10 * * * *"); edit.setEnabled(false);
        service.save(edit, now);
        assertEquals("in-flight", tasks.list().get(0).getLeaseOwner());
        try { service.delete(task.getId()); fail("unfinished run must remain recoverable"); }
        catch (com.zrlog.plugin.common.CronParseException expected) { assertTrue(expected.getMessage().contains("unfinished")); }
    }

    @Test
    public void uncertainTaskRequiresExplicitReviewBeforeResuming() {
        PluginAutomation task = dueTask();
        task.setEnabled(false); task.setReviewRequired(true); task.setReviewRunId("latest-run");
        tasks.saveAll(Collections.singletonList(task));
        PluginCapability capability = new PluginCapability();
        capability.setPluginId("plugin-a"); capability.setKey("test.run"); capability.setEnabled(true);
        capability.setExposure(Collections.singletonList("scheduler"));
        capabilities.register(capability);
        RuntimeAutomationService service = new RuntimeAutomationService(tasks, capabilities, new BasicCronParser());
        task.setEnabled(true);
        try { service.save(task, now); fail("review must be explicit"); }
        catch (com.zrlog.plugin.common.CronParseException expected) { assertTrue(expected.getMessage().contains("explicitly confirm")); }
        assertTrue(tasks.list().get(0).isReviewRequired());
        try { service.save(task, now, "older-run"); fail("a stale form must not confirm a newer run"); }
        catch (com.zrlog.plugin.common.CronParseException expected) { assertTrue(expected.getMessage().contains("explicitly confirm")); }
        service.save(task, now, "latest-run");
        assertFalse(tasks.list().get(0).isReviewRequired());
        assertTrue(tasks.list().get(0).getEnabled());
    }

    @Test
    public void systemTaskNormalizationDoesNotResumeUncertainExecution() {
        RuntimeAutomationService service = new RuntimeAutomationService(tasks, capabilities, new BasicCronParser());
        PluginAutomation maintenance = service.listWithSystemAutomations().get(0);
        maintenance.setEnabled(false); maintenance.setReviewRequired(true);
        tasks.saveAll(Collections.singletonList(maintenance));
        PluginAutomation persisted = service.listWithSystemAutomations().get(0);
        assertTrue(persisted.isReviewRequired());
        assertFalse(persisted.getEnabled());
    }

    private PluginAutomation dueTask() {
        PluginAutomation task = new PluginAutomation();
        task.setId("task-a"); task.setPluginId("plugin-a"); task.setCapabilityKey("test.run");
        task.setName("Task"); task.setCron("*/5 * * * *"); task.setTimezone("UTC"); task.setEnabled(true);
        task.setNextRunAt(now.toInstant().toEpochMilli());
        tasks.saveAll(Collections.singletonList(task));
        return task;
    }

    private PluginAutomationRun running(String id) {
        PluginAutomationRun run = new PluginAutomationRun();
        run.setId(id); run.setAutomationId("task-a"); run.setPluginId("plugin-a"); run.setCapabilityKey("test.run");
        run.setStartedAt(now.toInstant().toEpochMilli()); run.setStatus("running");
        return run;
    }

    private SchedulerRuntime runtime(AutomationRunStore store, CapabilityInvoker invoker) {
        return new SchedulerRuntime(tasks, store, capabilities, invoker, new BasicCronParser(), key ->
                new SchedulerRuntime.SchedulerTaskLock() {
                    public boolean tryLock() { return true; }
                    public void unlock() { }
                });
    }

    private CapabilityInvokeResult success() {
        CapabilityInvokeResult result = new CapabilityInvokeResult(); result.setSuccess(true); return result;
    }
}
