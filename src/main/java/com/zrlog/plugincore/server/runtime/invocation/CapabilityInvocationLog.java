package com.zrlog.plugincore.server.runtime.invocation;

import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;

public class CapabilityInvocationLog {

    private String id;
    private String pluginId;
    private String capabilityKey;
    private String source;
    private String riskLevel;
    private Boolean auditRequired;
    private String requestId;
    private String traceId;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Boolean getAuditRequired() {
        return auditRequired;
    }

    public void setAuditRequired(Boolean auditRequired) {
        this.auditRequired = auditRequired;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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
