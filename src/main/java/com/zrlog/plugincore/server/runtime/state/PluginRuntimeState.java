package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;

import java.util.List;

public class PluginRuntimeState {

    private String pluginId;
    private String pluginName;
    private String status;
    private String runtimeMode;
    private Long startedAt;
    private Long readyAt;
    private Long lastActiveAt;
    private Integer activeInvocationCount;
    private String effectiveStatus;
    private List<PluginRuntimeInstanceState> instances;
    private String lastError;

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRuntimeMode() {
        return runtimeMode;
    }

    public void setRuntimeMode(String runtimeMode) {
        this.runtimeMode = runtimeMode;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(Long readyAt) {
        this.readyAt = readyAt;
    }

    public Long getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Long lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Integer getActiveInvocationCount() {
        return activeInvocationCount;
    }

    public void setActiveInvocationCount(Integer activeInvocationCount) {
        this.activeInvocationCount = activeInvocationCount;
    }

    public String getEffectiveStatus() {
        return effectiveStatus;
    }

    public void setEffectiveStatus(String effectiveStatus) {
        this.effectiveStatus = effectiveStatus;
    }

    public List<PluginRuntimeInstanceState> getInstances() {
        return instances;
    }

    public void setInstances(List<PluginRuntimeInstanceState> instances) {
        this.instances = instances;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = RuntimeTextLimits.truncateErrorMessage(lastError);
    }
}
