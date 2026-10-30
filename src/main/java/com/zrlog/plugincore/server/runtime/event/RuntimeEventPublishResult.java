package com.zrlog.plugincore.server.runtime.event;

import java.util.LinkedHashSet;
import java.util.Set;

public class RuntimeEventPublishResult {

    private int successCount;
    private int failedCount;
    private int handlerCount;
    private boolean outcomeUncertain;

    public boolean isOutcomeUncertain() { return outcomeUncertain; }
    public void setOutcomeUncertain(boolean outcomeUncertain) { this.outcomeUncertain = outcomeUncertain; }
    private Set<String> handlerPluginIds = new LinkedHashSet<>();

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public int getHandlerCount() {
        return handlerCount;
    }

    public void setHandlerCount(int handlerCount) {
        this.handlerCount = handlerCount;
    }

    public Set<String> getHandlerPluginIds() {
        return handlerPluginIds;
    }

    public void setHandlerPluginIds(Set<String> handlerPluginIds) {
        this.handlerPluginIds = handlerPluginIds;
    }

    public void success(String pluginId) {
        handler(pluginId);
        successCount++;
    }

    public void failed(String pluginId) {
        handler(pluginId);
        failedCount++;
    }

    private void handler(String pluginId) {
        handlerCount++;
        if (pluginId != null && !pluginId.trim().isEmpty()) {
            handlerPluginIds.add(pluginId);
        }
    }
}
