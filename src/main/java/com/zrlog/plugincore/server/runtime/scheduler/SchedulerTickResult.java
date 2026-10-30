package com.zrlog.plugincore.server.runtime.scheduler;

public class SchedulerTickResult {

    private int executedCount;
    private int skippedCount;
    private int failedCount;
    private int uncertainCount;
    private String triggerId;
    private java.util.List<SchedulerChildResult> children = new java.util.ArrayList<>();

    public java.util.List<SchedulerChildResult> getChildren() { return children; }
    private java.util.List<String> runIds = new java.util.ArrayList<>();

    public int getUncertainCount() { return uncertainCount; }

    public void uncertain() { uncertainCount++; }

    public String getTriggerId() { return triggerId; }

    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }

    public java.util.List<String> getRunIds() { return runIds; }

    public int getExecutedCount() {
        return executedCount;
    }

    public void setExecutedCount(int executedCount) {
        this.executedCount = executedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public void executed() {
        executedCount++;
    }

    public void skipped() {
        skippedCount++;
    }

    public void failed() {
        failedCount++;
    }

    public void merge(SchedulerTickResult result) {
        if (result == null) {
            return;
        }
        executedCount += result.getExecutedCount();
        skippedCount += result.getSkippedCount();
        failedCount += result.getFailedCount();
        uncertainCount += result.getUncertainCount();
        runIds.addAll(result.getRunIds());
        children.addAll(result.getChildren());
    }
}
