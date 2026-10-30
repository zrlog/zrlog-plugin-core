package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.CronParseException;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SchedulerExecutionBudgetTest {
    @Test
    public void waitingTasksStayUnclaimedAndActiveLeasesCoverTheInvocationDeadline() throws Exception {
        var kv = new InMemoryRuntimeKvStore();
        var tasks = new AutomationStore(kv);
        var runs = new AutomationRunStore(kv);
        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        List<PluginAutomation> batch = new ArrayList<>();
        var stripes = new HashSet<Integer>();
        for (int i = 0; batch.size() < 24; i++) {
            if (stripes.add(SchedulerRuntime.executionLockIndex("plugin-" + i + ":execute"))) batch.add(task("plugin-" + i, now));
        }
        tasks.saveAll(batch);
        CountDownLatch started = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<SchedulerTickResult> completed = new AtomicReference<>();
        var runtime = new SchedulerRuntime(tasks, runs, new CapabilityStore(kv), (plugin, key, payload, context) -> {
            assertTrue(context.remainingMillis() > 600000);
            started.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            CapabilityInvokeResult result = new CapabilityInvokeResult(); result.setSuccess(true); return result;
        }, new BasicCronParser(), key -> lock(true));
        Thread caller = new Thread(() -> {
            try { completed.set(runtime.tick(now)); }
            catch (Throwable failure) { error.set(failure); }
        });
        caller.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var claimed = tasks.list().stream().filter(task -> task.getLeaseOwner() != null).collect(java.util.stream.Collectors.toList());
            assertEquals(8, claimed.size());
            assertEquals(8, runs.list().size());
            assertTrue(claimed.stream().allMatch(task -> ZonedDateTime.parse(task.getLeaseUntil()).isAfter(now.plusSeconds(645))));
            assertNull(completed.get());
        } finally { release.countDown(); caller.join(10000); }
        assertFalse(caller.isAlive());
        if (error.get() != null) throw new AssertionError(error.get());
        assertEquals(24, completed.get().getExecutedCount());
        assertEquals(24, runs.list().size());
        assertTrue(tasks.list().stream().allMatch(task -> task.getLeaseOwner() == null));
    }

    @Test
    public void manualExecutionCannotBypassTheCrossProcessCapabilityLock() {
        var kv = new InMemoryRuntimeKvStore();
        var tasks = new AutomationStore(kv);
        var runs = new AutomationRunStore(kv);
        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        tasks.saveAll(List.of(task("plugin-a", now)));
        var runtime = new SchedulerRuntime(tasks, runs, new CapabilityStore(kv), (plugin, key, payload, context) -> {
            throw new AssertionError("Another process owns this capability");
        }, new BasicCronParser(), key -> lock(false));
        try { runtime.runNow("plugin-a", now); fail("Must reject cross-process overlap"); }
        catch (CronParseException expected) { assertEquals("Automation is already running", expected.getMessage()); }
        assertTrue(runs.list().isEmpty());
        assertNull(tasks.list().get(0).getLeaseOwner());
    }

    @Test
    public void legacyAndManualRoutesPreserveExplicitLongCapabilityBudgets() {
        var kv = new InMemoryRuntimeKvStore();
        var tasks = new AutomationStore(kv);
        var runs = new AutomationRunStore(kv);
        var capabilities = new CapabilityStore(kv);
        var capability = new com.zrlog.plugin.message.PluginCapability();
        capability.setPluginId("plugin-a"); capability.setKey("execute"); capability.setTimeoutSeconds(900);
        capabilities.register(capability);
        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        tasks.saveAll(List.of(task("plugin-a", now)));
        var runtime = new SchedulerRuntime(tasks, runs, capabilities, (plugin, key, payload, context) -> {
            assertEquals(Integer.valueOf(900), context.getTimeoutSeconds());
            assertTrue(context.remainingMillis() >= 900000);
            assertTrue(ZonedDateTime.parse(tasks.list().get(0).getLeaseUntil()).isAfter(now.plusSeconds(900)));
            CapabilityInvokeResult result = new CapabilityInvokeResult(); result.setSuccess(true); return result;
        }, new BasicCronParser(), key -> lock(true));
        assertEquals(1, runtime.tick(now).getExecutedCount());
        assertEquals("success", runtime.runNow("plugin-a", now).getStatus());
        assertEquals(2, runs.list().size());
    }

    private static PluginAutomation task(String id, ZonedDateTime now) {
        PluginAutomation task = new PluginAutomation();
        task.setId(id); task.setPluginId(id); task.setCapabilityKey("execute"); task.setName(id);
        task.setEnabled(true); task.setCron("*/5 * * * *"); task.setTimezone("UTC"); task.setNextRunAt(now.toInstant().toEpochMilli());
        return task;
    }

    private static SchedulerRuntime.SchedulerTaskLock lock(boolean acquired) {
        return new SchedulerRuntime.SchedulerTaskLock() {
            public boolean tryLock() { return acquired; }
            public void unlock() { }
        };
    }
}
