package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityInvoker;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.InvokeContext;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SchedulerTickServiceTest {

    @Test
    public void shouldSkipTickInNativeAgentMode() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        SchedulerTickService service = new SchedulerTickService(new SchedulerSetting(), runtime(kvStore, runStore));
        RunType previous = RunConstants.runType;
        try {
            RunConstants.runType = RunType.AGENT;

            SchedulerTickResult result = service.tick(now());

            assertEquals(0, result.getExecutedCount());
            assertEquals(0, runStore.list().size());
        } finally {
            RunConstants.runType = previous;
        }
    }

    @Test
    public void shouldIgnoreLegacySchedulerDisabledFlag() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        SchedulerSetting setting = new SchedulerSetting();
        setting.setEnabled(Boolean.FALSE);
        SchedulerTickService service = new SchedulerTickService(setting, runtime(kvStore, runStore));

        SchedulerTickResult result = service.tick(now());

        assertEquals(1, result.getExecutedCount());
        assertEquals(1, runStore.list().size());
    }

    @Test
    public void shouldIgnoreDisabledDefaultProviderForPlatformScheduler() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        SchedulerSetting setting = new SchedulerSetting();
        setting.ensureDefaultProvider().setEnabled(Boolean.FALSE);
        SchedulerTickService service = new SchedulerTickService(setting, runtime(kvStore, runStore));

        SchedulerTickResult result = service.tick(now());

        assertEquals(1, result.getExecutedCount());
        assertEquals(1, runStore.list().size());
    }

    @Test
    public void shouldTickWhenSchedulerEnabled() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        SchedulerTickService service = new SchedulerTickService(new SchedulerSetting(), runtime(kvStore, runStore));

        SchedulerTickResult result = service.tick(now());

        assertEquals(1, result.getExecutedCount());
        assertEquals(1, runStore.list().size());
    }

    @Test
    public void shouldRunNowWhenSchedulerEnabled() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        SchedulerTickService service = new SchedulerTickService(new SchedulerSetting(), runtime(kvStore, runStore));

        PluginAutomationRun run = service.runNow("a1", now());

        assertEquals("success", run.getStatus());
        assertEquals(1, runStore.list().size());
    }

    @Test
    public void shouldRunNowWhenLegacySchedulerDisabledFlagExists() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AutomationRunStore runStore = new AutomationRunStore(kvStore);
        SchedulerSetting setting = new SchedulerSetting();
        setting.setEnabled(Boolean.FALSE);
        SchedulerTickService service = new SchedulerTickService(setting, runtime(kvStore, runStore));

        PluginAutomationRun run = service.runNow("a1", now());

        assertEquals("success", run.getStatus());
        assertEquals(1, runStore.list().size());
    }

    private SchedulerRuntime runtime(InMemoryRuntimeKvStore kvStore, AutomationRunStore runStore) {
        AutomationStore automationStore = new AutomationStore(kvStore);
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks"));
        PluginAutomation automation = automation("a1");
        automation.setNextRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(automation));
        return new SchedulerRuntime(automationStore, runStore, capabilityStore, successInvoker(),
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

    private PluginAutomation automation(String id) {
        PluginAutomation automation = new PluginAutomation();
        automation.setId(id);
        automation.setPluginId("plugin-a");
        automation.setCapabilityKey("reminder.scanDueTasks");
        automation.setName("Scan");
        automation.setCron("*/5 * * * *");
        automation.setTimezone("Asia/Shanghai");
        automation.setEnabled(Boolean.TRUE);
        return automation;
    }

    private PluginCapability capability(String pluginId, String key) {
        PluginCapability capability = new PluginCapability();
        capability.setPluginId(pluginId);
        capability.setPluginName(pluginId);
        capability.setKey(key);
        capability.setType("scheduled");
        capability.setExposure(Arrays.asList("scheduler"));
        capability.setEnabled(Boolean.TRUE);
        return capability;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.of(2026, 5, 29, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
    }
}
