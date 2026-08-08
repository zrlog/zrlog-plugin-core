package com.zrlog.plugincore.server.web.controller;

import com.hibegin.common.dao.dto.PageData;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.notification.NotificationDelivery;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomationRun;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerProviderSetting;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerTickResult;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RuntimeApiModels {

    private RuntimeApiModels() {
    }

    public static class Response {
        private int code;
        private String message;

        public Response() {
        }

        public Response(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public static Response success() {
            return new Response(0, "成功");
        }

        public static Response error(String message) {
            return new Response(1, message == null ? "失败" : message);
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class ItemsResponse<T> extends Response {
        private List<T> items = new ArrayList<T>();

        public ItemsResponse() {
            super(0, "成功");
        }

        public ItemsResponse(List<? extends T> items) {
            super(0, "成功");
            this.items = items == null ? new ArrayList<T>() : new ArrayList<T>(items);
        }

        public List<T> getItems() {
            return items;
        }

        public void setItems(List<T> items) {
            this.items = items;
        }
    }

    public static class PageResponse<T> extends Response {
        private List<T> rows = new ArrayList<T>();
        private Long page;
        private Long size;
        private Long totalElements;

        public PageResponse() {
            super(0, "成功");
        }

        public PageResponse(List<? extends T> rows, PageData<?> pageData) {
            super(0, "成功");
            this.rows = rows == null ? new ArrayList<T>() : new ArrayList<T>(rows);
            this.page = pageData.getPage();
            this.size = pageData.getSize();
            this.totalElements = pageData.getTotalElements();
        }

        public List<T> getRows() {
            return rows;
        }

        public void setRows(List<T> rows) {
            this.rows = rows;
        }

        public Long getPage() {
            return page;
        }

        public void setPage(Long page) {
            this.page = page;
        }

        public Long getSize() {
            return size;
        }

        public void setSize(Long size) {
            this.size = size;
        }

        public Long getTotalElements() {
            return totalElements;
        }

        public void setTotalElements(Long totalElements) {
            this.totalElements = totalElements;
        }
    }

    public static class ItemResponse<T> extends Response {
        private T item;

        public ItemResponse() {
            super(0, "成功");
        }

        public ItemResponse(T item) {
            super(0, "成功");
            this.item = item;
        }

        public T getItem() {
            return item;
        }

        public void setItem(T item) {
            this.item = item;
        }
    }

    public static class ResultResponse extends Response {
        private SchedulerTickResult result;

        public ResultResponse() {
            super(0, "成功");
        }

        public ResultResponse(SchedulerTickResult result) {
            super(0, "成功");
            this.result = result;
        }

        public SchedulerTickResult getResult() {
            return result;
        }

        public void setResult(SchedulerTickResult result) {
            this.result = result;
        }
    }

    public static class ActionResponse extends Response {
        private Boolean started;
        private Boolean removed;
        private Boolean success;

        public ActionResponse() {
            super(0, "成功");
        }

        public static ActionResponse started() {
            ActionResponse response = new ActionResponse();
            response.setStarted(true);
            return response;
        }

        public static ActionResponse removed(boolean removed) {
            ActionResponse response = new ActionResponse();
            response.setRemoved(removed);
            return response;
        }

        public static ActionResponse successFlag(boolean success) {
            ActionResponse response = new ActionResponse();
            response.setSuccess(success);
            return response;
        }

        public Boolean getStarted() {
            return started;
        }

        public void setStarted(Boolean started) {
            this.started = started;
        }

        public Boolean getRemoved() {
            return removed;
        }

        public void setRemoved(Boolean removed) {
            this.removed = removed;
        }

        public Boolean getSuccess() {
            return success;
        }

        public void setSuccess(Boolean success) {
            this.success = success;
        }
    }

    public static class SchedulerSettingsResponse extends Response {
        private Boolean enabled;
        private String externalHost;
        private String effectiveExternalHost;
        private String externalTickPath;
        private String externalTickUrl;
        private List<SchedulerProviderSetting> providers;
        private String systemTimezone;

        public SchedulerSettingsResponse() {
            super(0, "成功");
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getExternalHost() {
            return externalHost;
        }

        public void setExternalHost(String externalHost) {
            this.externalHost = externalHost;
        }

        public String getEffectiveExternalHost() {
            return effectiveExternalHost;
        }

        public void setEffectiveExternalHost(String effectiveExternalHost) {
            this.effectiveExternalHost = effectiveExternalHost;
        }

        public String getExternalTickPath() {
            return externalTickPath;
        }

        public void setExternalTickPath(String externalTickPath) {
            this.externalTickPath = externalTickPath;
        }

        public String getExternalTickUrl() {
            return externalTickUrl;
        }

        public void setExternalTickUrl(String externalTickUrl) {
            this.externalTickUrl = externalTickUrl;
        }

        public List<SchedulerProviderSetting> getProviders() {
            return providers;
        }

        public void setProviders(List<SchedulerProviderSetting> providers) {
            this.providers = providers;
        }

        public String getSystemTimezone() {
            return systemTimezone;
        }

        public void setSystemTimezone(String systemTimezone) {
            this.systemTimezone = systemTimezone;
        }
    }

    public static class RuntimeSettingsResponse extends Response {
        private Boolean onDemandEnabled;
        private Boolean autoDownloadMissingPluginFileEnabled;
        private Boolean idleStopEnabled;
        private Long idleTimeoutSeconds;
        private Long idleScanIntervalSeconds;
        private Long maxRunningPlugins;
        private Long maxConcurrentStarts;
        private Long startFailureBackoffSeconds;

        public RuntimeSettingsResponse() {
            super(0, "成功");
        }

        public RuntimeSettingsResponse(PluginRuntimeSetting setting) {
            this();
            this.onDemandEnabled = setting.getOnDemandEnabled();
            this.autoDownloadMissingPluginFileEnabled = setting.getAutoDownloadMissingPluginFileEnabled();
            this.idleStopEnabled = setting.getIdleStopEnabled();
            this.idleTimeoutSeconds = setting.getIdleTimeoutSeconds();
            this.idleScanIntervalSeconds = setting.getIdleScanIntervalSeconds();
            this.maxRunningPlugins = setting.getMaxRunningPlugins();
            this.maxConcurrentStarts = setting.getMaxConcurrentStarts();
            this.startFailureBackoffSeconds = setting.getStartFailureBackoffSeconds();
        }

        public Boolean getOnDemandEnabled() {
            return onDemandEnabled;
        }

        public void setOnDemandEnabled(Boolean onDemandEnabled) {
            this.onDemandEnabled = onDemandEnabled;
        }

        public Boolean getAutoDownloadMissingPluginFileEnabled() {
            return autoDownloadMissingPluginFileEnabled;
        }

        public void setAutoDownloadMissingPluginFileEnabled(Boolean autoDownloadMissingPluginFileEnabled) {
            this.autoDownloadMissingPluginFileEnabled = autoDownloadMissingPluginFileEnabled;
        }

        public Boolean getIdleStopEnabled() {
            return idleStopEnabled;
        }

        public void setIdleStopEnabled(Boolean idleStopEnabled) {
            this.idleStopEnabled = idleStopEnabled;
        }

        public Long getIdleTimeoutSeconds() {
            return idleTimeoutSeconds;
        }

        public void setIdleTimeoutSeconds(Long idleTimeoutSeconds) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
        }

        public Long getIdleScanIntervalSeconds() {
            return idleScanIntervalSeconds;
        }

        public void setIdleScanIntervalSeconds(Long idleScanIntervalSeconds) {
            this.idleScanIntervalSeconds = idleScanIntervalSeconds;
        }

        public Long getMaxRunningPlugins() {
            return maxRunningPlugins;
        }

        public void setMaxRunningPlugins(Long maxRunningPlugins) {
            this.maxRunningPlugins = maxRunningPlugins;
        }

        public Long getMaxConcurrentStarts() {
            return maxConcurrentStarts;
        }

        public void setMaxConcurrentStarts(Long maxConcurrentStarts) {
            this.maxConcurrentStarts = maxConcurrentStarts;
        }

        public Long getStartFailureBackoffSeconds() {
            return startFailureBackoffSeconds;
        }

        public void setStartFailureBackoffSeconds(Long startFailureBackoffSeconds) {
            this.startFailureBackoffSeconds = startFailureBackoffSeconds;
        }
    }

    public static class AutomationsResponse extends ItemsResponse<AutomationResponse> {
        private String systemTimezone;

        public AutomationsResponse() {
        }

        public AutomationsResponse(List<AutomationResponse> items, String systemTimezone) {
            super(items);
            this.systemTimezone = systemTimezone;
        }

        public String getSystemTimezone() {
            return systemTimezone;
        }

        public void setSystemTimezone(String systemTimezone) {
            this.systemTimezone = systemTimezone;
        }
    }

    public static class NotificationTestResponse extends Response {
        private Boolean success;
        private NotificationDeliveryResponse delivery;

        public NotificationTestResponse() {
            super(0, "成功");
        }

        public NotificationTestResponse(boolean success, NotificationDeliveryResponse delivery) {
            super(0, "成功");
            this.success = success;
            this.delivery = delivery;
        }

        public Boolean getSuccess() {
            return success;
        }

        public void setSuccess(Boolean success) {
            this.success = success;
        }

        public NotificationDeliveryResponse getDelivery() {
            return delivery;
        }

        public void setDelivery(NotificationDeliveryResponse delivery) {
            this.delivery = delivery;
        }
    }

    public static class CommentProvidersResponse extends ItemsResponse<CommentProviderRow> {
        private String selectedShortName;

        public CommentProvidersResponse() {
        }

        public CommentProvidersResponse(List<CommentProviderRow> items, String selectedShortName) {
            super(items);
            this.selectedShortName = selectedShortName;
        }

        public String getSelectedShortName() {
            return selectedShortName;
        }

        public void setSelectedShortName(String selectedShortName) {
            this.selectedShortName = selectedShortName;
        }
    }

    public static class CapabilityResponse extends PluginCapability {
        private String pluginPreviewImageBase64;

        public static CapabilityResponse from(PluginCapability capability) {
            CapabilityResponse response = new CapabilityResponse();
            response.setPluginId(capability.getPluginId());
            response.setPluginName(capability.getPluginName());
            response.setKey(capability.getKey());
            response.setServiceName(capability.getServiceName());
            response.setType(capability.getType());
            response.setLabel(capability.getLabel());
            response.setDescription(capability.getDescription());
            response.setExposure(capability.getExposure());
            response.setRiskLevel(capability.getRiskLevel());
            response.setReadOnly(capability.getReadOnly());
            response.setRequiresConfirmation(capability.getRequiresConfirmation());
            response.setTimeoutSeconds(capability.getTimeoutSeconds());
            response.setConcurrency(capability.getConcurrency());
            response.setEnabled(capability.getEnabled());
            response.setLegacy(capability.getLegacy());
            response.setGenerated(capability.getGenerated());
            response.setChannel(capability.getChannel());
            response.setDefaultCron(capability.getDefaultCron());
            response.setTimezone(capability.getTimezone());
            return response;
        }

        public String getPluginPreviewImageBase64() {
            return pluginPreviewImageBase64;
        }

        public void setPluginPreviewImageBase64(String pluginPreviewImageBase64) {
            this.pluginPreviewImageBase64 = pluginPreviewImageBase64;
        }
    }

    public static class AutomationResponse {
        private String id;
        private String pluginId;
        private String pluginName;
        private String pluginPreviewImageBase64;
        private String capabilityKey;
        private String name;
        private String triggerType;
        private String cron;
        private String timezone;
        private Boolean enabled;
        private Boolean system;
        private Boolean deletable;
        private Long nextRunAt;
        private Long lastRunAt;
        private String leaseOwner;
        private String leaseUntil;
        private Map<String, Object> payload;
        private String targetLabel;

        public static AutomationResponse from(PluginAutomation automation) {
            AutomationResponse response = new AutomationResponse();
            response.id = automation.getId();
            response.pluginId = automation.getPluginId();
            response.capabilityKey = automation.getCapabilityKey();
            response.name = automation.getName();
            response.triggerType = automation.getTriggerType();
            response.cron = automation.getCron();
            response.timezone = automation.getTimezone();
            response.enabled = automation.getEnabled();
            response.system = automation.getSystem();
            response.deletable = automation.getDeletable();
            response.nextRunAt = automation.getNextRunAt();
            response.lastRunAt = automation.getLastRunAt();
            response.leaseOwner = automation.getLeaseOwner();
            response.leaseUntil = automation.getLeaseUntil();
            response.payload = automation.getPayload();
            return response;
        }

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

        public String getPluginName() {
            return pluginName;
        }

        public void setPluginName(String pluginName) {
            this.pluginName = pluginName;
        }

        public String getPluginPreviewImageBase64() {
            return pluginPreviewImageBase64;
        }

        public void setPluginPreviewImageBase64(String pluginPreviewImageBase64) {
            this.pluginPreviewImageBase64 = pluginPreviewImageBase64;
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

        public String getTargetLabel() {
            return targetLabel;
        }

        public void setTargetLabel(String targetLabel) {
            this.targetLabel = targetLabel;
        }
    }

    public static class AutomationRunResponse {
        private String id;
        private String automationId;
        private String pluginId;
        private String pluginName;
        private String pluginPreviewImageBase64;
        private String capabilityKey;
        private String status;
        private Long startedAt;
        private Long finishedAt;
        private Long durationMs;
        private String errorMessage;
        private String targetLabel;

        public static AutomationRunResponse from(PluginAutomationRun run) {
            AutomationRunResponse response = new AutomationRunResponse();
            response.id = run.getId();
            response.automationId = run.getAutomationId();
            response.pluginId = run.getPluginId();
            response.capabilityKey = run.getCapabilityKey();
            response.status = run.getStatus();
            response.startedAt = run.getStartedAt();
            response.finishedAt = run.getFinishedAt();
            response.durationMs = run.getDurationMs();
            response.errorMessage = run.getErrorMessage();
            return response;
        }

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

        public String getPluginName() {
            return pluginName;
        }

        public void setPluginName(String pluginName) {
            this.pluginName = pluginName;
        }

        public String getPluginPreviewImageBase64() {
            return pluginPreviewImageBase64;
        }

        public void setPluginPreviewImageBase64(String pluginPreviewImageBase64) {
            this.pluginPreviewImageBase64 = pluginPreviewImageBase64;
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
            this.errorMessage = errorMessage;
        }

        public String getTargetLabel() {
            return targetLabel;
        }

        public void setTargetLabel(String targetLabel) {
            this.targetLabel = targetLabel;
        }
    }

    public static class InvocationLogResponse {
        private String id;
        private String pluginId;
        private String pluginName;
        private String pluginPreviewImageBase64;
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

        public String getPluginName() {
            return pluginName;
        }

        public void setPluginName(String pluginName) {
            this.pluginName = pluginName;
        }

        public String getPluginPreviewImageBase64() {
            return pluginPreviewImageBase64;
        }

        public void setPluginPreviewImageBase64(String pluginPreviewImageBase64) {
            this.pluginPreviewImageBase64 = pluginPreviewImageBase64;
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
            this.errorMessage = errorMessage;
        }
    }

    public static class NotificationDeliveryResponse {
        private String id;
        private String channel;
        private String providerPluginId;
        private String providerPluginName;
        private String providerPluginPreviewImageBase64;
        private String capabilityKey;
        private String status;
        private String errorMessage;
        private Long createdAt;

        public static NotificationDeliveryResponse from(NotificationDelivery delivery) {
            NotificationDeliveryResponse response = new NotificationDeliveryResponse();
            response.id = delivery.getId();
            response.channel = delivery.getChannel();
            response.providerPluginId = delivery.getProviderPluginId();
            response.capabilityKey = delivery.getCapabilityKey();
            response.status = delivery.getStatus();
            response.errorMessage = delivery.getErrorMessage();
            response.createdAt = delivery.getCreatedAt();
            return response;
        }

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

        public String getProviderPluginName() {
            return providerPluginName;
        }

        public void setProviderPluginName(String providerPluginName) {
            this.providerPluginName = providerPluginName;
        }

        public String getProviderPluginPreviewImageBase64() {
            return providerPluginPreviewImageBase64;
        }

        public void setProviderPluginPreviewImageBase64(String providerPluginPreviewImageBase64) {
            this.providerPluginPreviewImageBase64 = providerPluginPreviewImageBase64;
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
            this.errorMessage = errorMessage;
        }

        public Long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Long createdAt) {
            this.createdAt = createdAt;
        }
    }

    public static class ServiceProviderRow {
        private String serviceName;
        private String serviceLabel;
        private String providerPluginId;
        private String providerPluginName;
        private String providerPluginPreviewImageBase64;
        private String capabilityKey;
        private String capabilityLabel;
        private Boolean selected;
        private Boolean confirmed;
        private Boolean reviewRequired;

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getServiceLabel() {
            return serviceLabel;
        }

        public void setServiceLabel(String serviceLabel) {
            this.serviceLabel = serviceLabel;
        }

        public String getProviderPluginId() {
            return providerPluginId;
        }

        public void setProviderPluginId(String providerPluginId) {
            this.providerPluginId = providerPluginId;
        }

        public String getProviderPluginName() {
            return providerPluginName;
        }

        public void setProviderPluginName(String providerPluginName) {
            this.providerPluginName = providerPluginName;
        }

        public String getProviderPluginPreviewImageBase64() {
            return providerPluginPreviewImageBase64;
        }

        public void setProviderPluginPreviewImageBase64(String providerPluginPreviewImageBase64) {
            this.providerPluginPreviewImageBase64 = providerPluginPreviewImageBase64;
        }

        public String getCapabilityKey() {
            return capabilityKey;
        }

        public void setCapabilityKey(String capabilityKey) {
            this.capabilityKey = capabilityKey;
        }

        public String getCapabilityLabel() {
            return capabilityLabel;
        }

        public void setCapabilityLabel(String capabilityLabel) {
            this.capabilityLabel = capabilityLabel;
        }

        public Boolean getSelected() {
            return selected;
        }

        public void setSelected(Boolean selected) {
            this.selected = selected;
        }

        public Boolean getConfirmed() {
            return confirmed;
        }

        public void setConfirmed(Boolean confirmed) {
            this.confirmed = confirmed;
        }

        public Boolean getReviewRequired() {
            return reviewRequired;
        }

        public void setReviewRequired(Boolean reviewRequired) {
            this.reviewRequired = reviewRequired;
        }
    }

    public static class CommentProviderRow {
        private String shortName;
        private String pluginId;
        private String pluginName;
        private String pluginPreviewImageBase64;
        private String description;
        private Boolean selected;
        private Boolean confirmed;
        private Boolean reviewRequired;

        public String getShortName() {
            return shortName;
        }

        public void setShortName(String shortName) {
            this.shortName = shortName;
        }

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

        public String getPluginPreviewImageBase64() {
            return pluginPreviewImageBase64;
        }

        public void setPluginPreviewImageBase64(String pluginPreviewImageBase64) {
            this.pluginPreviewImageBase64 = pluginPreviewImageBase64;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Boolean getSelected() {
            return selected;
        }

        public void setSelected(Boolean selected) {
            this.selected = selected;
        }

        public Boolean getConfirmed() {
            return confirmed;
        }

        public void setConfirmed(Boolean confirmed) {
            this.confirmed = confirmed;
        }

        public Boolean getReviewRequired() {
            return reviewRequired;
        }

        public void setReviewRequired(Boolean reviewRequired) {
            this.reviewRequired = reviewRequired;
        }
    }
}
