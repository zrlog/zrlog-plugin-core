package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;

public class PluginAutomationRun {

    private String id;
    private String automationId;
    private String pluginId;
    private String capabilityKey;
    private String status;
    private Long startedAt;
    private Long finishedAt;
    private Long durationMs;
    private String errorMessage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAutomationId() {
        return automationId;
    }

    public void setAutomationId(String automationId) {
        this.automationId = automationId;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getCapabilityKey() {
        return capabilityKey;
    }

    public void setCapabilityKey(String capabilityKey) {
        this.capabilityKey = capabilityKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = RuntimeTextLimits.truncateErrorMessage(errorMessage);
    }
}
