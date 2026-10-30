package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugincore.server.runtime.PluginCoreRunMode;
import com.zrlog.plugincore.server.runtime.capability.RuntimeSources;

public class SchedulerTickService {

    private final SchedulerSetting schedulerSetting;
    private final SchedulerRuntime schedulerRuntime;

    public SchedulerTickService(SchedulerSetting schedulerSetting, SchedulerRuntime schedulerRuntime) {
        this.schedulerSetting = schedulerSetting;
        this.schedulerRuntime = schedulerRuntime;
    }

    public SchedulerTickResult tick(java.time.ZonedDateTime now) {
        return tick(now, RuntimeSources.SCHEDULER);
    }

    public SchedulerTickResult tick(java.time.ZonedDateTime now, String source) {
        if (PluginCoreRunMode.isNativeAgent()) {
            return new SchedulerTickResult();
        }
        if (schedulerSetting != null) {
            if ((schedulerSetting.getHostDriven() || com.zrlog.plugincore.server.runtime.PluginRuntimeBridge.hostConnection().isHostSchedulerDriven())
                    && (source == null || source.trim().isEmpty() || RuntimeSources.SCHEDULER.equals(source.trim()))) {
                return schedulerRuntime.tickMaintenance(now);
            }
            schedulerSetting.ensureDefaultProvider();
        }
        return schedulerRuntime.tick(now, source);
    }

    public SchedulerTickResult tickFromHost(java.time.ZonedDateTime now, String triggerId, int actorId, long budgetMillis) {
        if (PluginCoreRunMode.isNativeAgent()) return new SchedulerTickResult();
        return schedulerRuntime.tickFromHost(now, triggerId, actorId, budgetMillis);
    }

    public PluginAutomationRun runNow(String automationId, java.time.ZonedDateTime now) {
        if (schedulerSetting != null) {
            schedulerSetting.ensureDefaultProvider();
        }
        return schedulerRuntime.runNow(automationId, now);
    }
}
