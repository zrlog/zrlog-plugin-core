package com.zrlog.plugincore.server.runtime.scheduler;

/** Snapshot of a child of one scheduler tick; excludes task payloads and configuration. */
public final class SchedulerChildResult {
    public String automationId;
    public String name;
    public String pluginId;
    public String capabilityKey;
    public String runId;
    public String status;
    public String reason;
    public String errorMessage;
    public Long startedAt;
    public Long finishedAt;

    static SchedulerChildResult from(PluginAutomation task, PluginAutomationRun run) {
        SchedulerChildResult child = new SchedulerChildResult();
        child.automationId = task.getId();
        child.name = task.getName();
        child.pluginId = task.getPluginId();
        child.capabilityKey = task.getCapabilityKey();
        child.runId = run.getId();
        child.status = run.getStatus();
        child.errorMessage = run.getErrorMessage();
        child.startedAt = run.getStartedAt();
        child.finishedAt = run.getFinishedAt();
        return child;
    }
}
