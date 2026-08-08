package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;

import java.util.Set;

public class PluginRuntimeInstanceState {

    private String instanceId;
    private String ownerId;
    private String status;
    private String runtimeMode;
    private Long processId;
    private Long startedAt;
    private Long readyAt;
    private Long lastActiveAt;
    private Long heartbeatAt;
    private Long leaseExpiresAt;
    private Integer activeInvocationCount;
    private Set<String> activeInvocationIds;
    private String lastError;

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
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

    public Long getProcessId() {
        return processId;
    }

    public void setProcessId(Long processId) {
        this.processId = processId;
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

    public Long getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Long heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Long getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Long leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Integer getActiveInvocationCount() {
        return activeInvocationCount;
    }

    public void setActiveInvocationCount(Integer activeInvocationCount) {
        this.activeInvocationCount = activeInvocationCount;
    }

    public Set<String> getActiveInvocationIds() {
        return activeInvocationIds;
    }

    public void setActiveInvocationIds(Set<String> activeInvocationIds) {
        this.activeInvocationIds = activeInvocationIds;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = RuntimeTextLimits.truncateErrorMessage(lastError);
    }
}
