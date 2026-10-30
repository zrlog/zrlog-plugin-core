package com.zrlog.plugincore.server.runtime.capability;

import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;

public class InvokeContext {

    private boolean legacyCompletion;
    public boolean isLegacyCompletion() { return legacyCompletion; }
    public void setLegacyCompletion(boolean legacyCompletion) { this.legacyCompletion = legacyCompletion; }


    private String source;
    private String userId;
    private String requestId;
    private String traceId;
    private Integer timeoutSeconds;
    private boolean auditRequired;
    private boolean outcomeUncertain;
    private Long deadlineEpochMillis;
    private boolean allowStartup = true;
    private boolean requireCompletion;
    private RuntimeDeadline deadline = RuntimeDeadline.unlimited();

    public Long getDeadlineEpochMillis() { return deadlineEpochMillis; }
    public void setDeadlineEpochMillis(Long deadlineEpochMillis) {
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.deadline = RuntimeDeadline.atEpochMillis(deadlineEpochMillis);
    }
    public RuntimeDeadline deadline() { return deadline; }
    public void setDeadline(RuntimeDeadline deadline) {
        this.deadline = java.util.Objects.requireNonNull(deadline);
        this.deadlineEpochMillis = deadline.isBounded() ? System.currentTimeMillis() + deadline.remainingMillis() : null;
    }
    public boolean isRequireCompletion() { return requireCompletion; }
    public void setRequireCompletion(boolean requireCompletion) { this.requireCompletion = requireCompletion; }
    public boolean isAllowStartup() { return allowStartup; }
    public void setAllowStartup(boolean allowStartup) { this.allowStartup = allowStartup; }
    public long remainingMillis() {
        return deadline.remainingMillis();
    }

    public boolean isOutcomeUncertain() { return outcomeUncertain; }

    public void markOutcomeUncertain() { outcomeUncertain = true; }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isAuditRequired() {
        return auditRequired;
    }

    public void setAuditRequired(boolean auditRequired) {
        this.auditRequired = auditRequired;
    }
}
