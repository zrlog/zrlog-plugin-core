package com.zrlog.plugincore.server.runtime.notification;

import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;

public class NotificationDelivery {

    private String id;
    private String channel;
    private String providerPluginId;
    private String capabilityKey;
    private String status;
    private String errorMessage;
    private Long createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getProviderPluginId() {
        return providerPluginId;
    }

    public void setProviderPluginId(String providerPluginId) {
        this.providerPluginId = providerPluginId;
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

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = RuntimeTextLimits.truncateErrorMessage(errorMessage);
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
