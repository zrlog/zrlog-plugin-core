package com.zrlog.plugincore.server.runtime.scheduler;

import com.google.gson.Gson;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityInvoker;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class HostSchedulerTickTest {
    private final InMemoryRuntimeKvStore kv = new InMemoryRuntimeKvStore();
    private final AutomationStore tasks = new AutomationStore(kv);
    private final AutomationRunStore runs = new AutomationRunStore(kv);
    private final CapabilityStore capabilities = new CapabilityStore(kv);
    private final ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);

    @Test
    public void oneTriggerContainsIndividualResultsAndKeepsPluginSchedulesOwnedByPlugin() {
        tasks.saveAll(List.of(task("a"), task("b")));
        SchedulerTickResult result = runtime((plugin, key, payload, context) -> {
            assertEquals("29", context.getUserId());
            assertEquals("parent-run", context.getTraceId());
            assertTrue(context.isAllowStartup());
            assertTrue(context.remainingMillis() > 0);
            assertEquals("running", runs.find(context.getRequestId()).get().getStatus());
            PluginAutomation claimed = tasks.list().stream().filter(t -> t.getPluginId().equals(plugin)).findFirst().get();
            assertTrue(ZonedDateTime.parse(claimed.getLeaseUntil()).isAfter(now.plusMinutes(10)));
            CapabilityInvokeResult response = new CapabilityInvokeResult();
            response.setSuccess(plugin.equals("a"));
            response.setErrorMessage(plugin.equals("b") ? "Remote task rejected the operation" : null);
            return response;
        }).tickFromHost(now, "parent-run", 29, 645000);
        assertEquals("parent-run", result.getTriggerId());
        assertEquals(1, result.getExecutedCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(2, result.getChildren().size());
        for (SchedulerChildResult child : result.getChildren()) {
            assertEquals("Task " + child.pluginId, child.name);
            assertEquals(child.status, runs.find(child.runId).get().getStatus());
            assertEquals("parent-run", runs.find(child.runId).get().getTriggerId());
            assertEquals("29", runs.find(child.runId).get().getActorId());
            assertNotNull(child.finishedAt);
        }
        assertEquals(2, tasks.list().size());
        assertTrue(tasks.list().stream().allMatch(t -> t.getNextRunAt() > now.toInstant().toEpochMilli()));
        String json = new Gson().toJson(result);
        assertFalse(json.contains("payload"));
        assertFalse(json.contains("secret-value"));
    }

    @Test
    public void insufficientBudgetLeavesTaskDueWithoutClaimOrSideEffects() {
        tasks.saveAll(List.of(task("a")));
        SchedulerRuntime runtime = runtime((plugin, key, payload, context) -> { throw new AssertionError("must not execute"); });
        SchedulerTickResult tooShort = runtime.tickFromHost(now, "short-run", 1, 1000);
        assertEquals("insufficient_budget", tooShort.getChildren().get(0).reason);
        assertEquals("skipped", tooShort.getChildren().get(0).status);
        assertNull(tooShort.getChildren().get(0).runId);
        assertNull(tasks.list().get(0).getLeaseOwner());
        assertEquals(Long.valueOf(now.toInstant().toEpochMilli()), tasks.list().get(0).getNextRunAt());
        assertEquals(0, runs.list().size());
    }

    @Test
    public void unconfirmedChildPausesOnlyThatPluginTaskAndIsNotReplayed() {
        tasks.saveAll(List.of(task("a")));
        AtomicInteger effects = new AtomicInteger();
        SchedulerRuntime runtime = runtime((plugin, key, payload, context) -> {
            effects.incrementAndGet(); context.markOutcomeUncertain();
            CapabilityInvokeResult response = new CapabilityInvokeResult(); response.setSuccess(false); return response;
        });
        SchedulerTickResult result = runtime.tickFromHost(now, "uncertain-run", 1, 645000);
        assertEquals(1, result.getUncertainCount());
        assertEquals("uncertain", result.getChildren().get(0).status);
        assertTrue(tasks.list().get(0).isReviewRequired());
        assertEquals(0, runtime.tickFromHost(now.plusMinutes(20), "later-run", 1, 645000).getChildren().size());
        assertEquals(1, effects.get());
    }

    @Test
    public void storageFailureAfterEffectsReturnsUncertainChildWithCorrelation() {
        tasks.saveAll(List.of(task("a")));
        AutomationRunStore lostResults = new AutomationRunStore(kv) {
            @Override public boolean complete(PluginAutomationRun run) { throw new IllegalStateException("offline"); }
        };
        SchedulerRuntime runtime = new SchedulerRuntime(tasks, lostResults, capabilities, (plugin, key, payload, context) -> {
            CapabilityInvokeResult response = new CapabilityInvokeResult(); response.setSuccess(true); return response;
        }, new BasicCronParser(), key -> lock());
        SchedulerTickResult result = runtime.tickFromHost(now, "lost-result", 1, 645000);
        assertEquals(1, result.getUncertainCount());
        assertEquals("uncertain", result.getChildren().get(0).status);
        assertEquals(result.getChildren().get(0).runId, tasks.list().get(0).getLeaseOwner());
        assertEquals("running", runs.list().get(0).getStatus());
    }

    private PluginAutomation task(String id) {
        PluginAutomation task = new PluginAutomation();
        task.setId("task-" + id); task.setPluginId(id); task.setCapabilityKey("test.run");
        task.setName("Task " + id); task.setCron("*/5 * * * *"); task.setTimezone("UTC"); task.setEnabled(true);
        task.setNextRunAt(now.toInstant().toEpochMilli());
        task.setPayload(java.util.Map.of("private", "secret-value"));
        PluginCapability capability = new PluginCapability();
        capability.setPluginId(id); capability.setKey("test.run"); capability.setEnabled(true);
        capability.setExposure(List.of("scheduler")); capabilities.register(capability);
        return task;
    }

    private SchedulerRuntime runtime(CapabilityInvoker invoker) {
        return new SchedulerRuntime(tasks, runs, capabilities, invoker, new BasicCronParser(), key -> lock());
    }

    private SchedulerRuntime.SchedulerTaskLock lock() {
        return new SchedulerRuntime.SchedulerTaskLock() {
            public boolean tryLock() { return true; }
            public void unlock() { }
        };
    }
}
