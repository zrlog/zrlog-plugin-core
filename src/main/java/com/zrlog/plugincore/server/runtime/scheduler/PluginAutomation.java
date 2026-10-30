package com.zrlog.plugincore.server.runtime.scheduler;

import java.util.HashMap;
import java.util.Map;

public class PluginAutomation {

    private String id;
    private String pluginId;
    private String capabilityKey;
    private String name;
    private String triggerType;
    private String cron;
    private String timezone;
    private Boolean enabled;
    private boolean reviewRequired;
    private String reviewRunId;
    private Boolean system;
    private Boolean deletable;
    private Long nextRunAt;
    private Long lastRunAt;
    private String leaseOwner;
    private String leaseUntil;
    private Map<String, Object> payload = new HashMap<>();

    public boolean isReviewRequired() { return reviewRequired; }

    public void setReviewRequired(boolean reviewRequired) { this.reviewRequired = reviewRequired; }

    public String getReviewRunId() { return reviewRunId; }

    public void setReviewRunId(String reviewRunId) { this.reviewRunId = reviewRunId; }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getSystem() {
        return system;
    }

    public void setSystem(Boolean system) {
        this.system = system;
    }

    public Boolean getDeletable() {
        return deletable;
    }

    public void setDeletable(Boolean deletable) {
        this.deletable = deletable;
    }

    public Long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Long nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Long getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Long lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public String getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(String leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
