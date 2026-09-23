package com.zrlog.plugincore.server.web.controller;

import com.zrlog.plugincore.server.util.PluginI18n;

import com.hibegin.common.dao.dto.PageData;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.notification.NotificationDelivery;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomationRun;
import com.zrlog.plugincore.server.runtime.scheduler.RuntimeSystemAutomations;
import com.zrlog.plugincore.server.vo.PluginVO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.*;

final class RuntimeApiResponses {

    private RuntimeApiResponses() {
    }

    static Response success() {
        return Response.success();
    }

    static Response error(String message) {
        return Response.error(message);
    }

    static Map<String, Plugin> pluginsById() {
        return pluginsById(PluginCoreDAO.getInstance().loadSnapshot());
    }

    static Map<String, Plugin> pluginsById(PluginCore pluginCore) {
        Map<String, Plugin> pluginsById = new HashMap<String, Plugin>();
        for (PluginVO pluginVO : PluginCoreDAO.getInstance().getPluginVOs(pluginCore)) {
            if (pluginVO == null || pluginVO.getPlugin() == null || isBlank(pluginVO.getPlugin().getId())) {
                continue;
            }
            pluginsById.put(pluginVO.getPlugin().getId(), pluginVO.getPlugin());
        }
        return pluginsById;
    }

    static Map<String, PluginCapability> capabilitiesByKey(List<PluginCapability> capabilities) {
        Map<String, PluginCapability> capabilitiesByKey = new HashMap<String, PluginCapability>();
        for (PluginCapability capability : capabilities) {
            if (capability == null || isBlank(capability.getPluginId()) || isBlank(capability.getKey())) {
                continue;
            }
            capabilitiesByKey.put(capabilityMapKey(capability.getPluginId(), capability.getKey()), capability);
        }
        return capabilitiesByKey;
    }

    static List<CapabilityResponse> capabilityResponses(List<PluginCapability> capabilities, Map<String, Plugin> pluginsById) {
        List<CapabilityResponse> items = new ArrayList<CapabilityResponse>();
        for (PluginCapability capability : capabilities) {
            CapabilityResponse item = CapabilityResponse.from(capability);
            Plugin plugin = pluginsById.get(capability.getPluginId());
            item.setPluginName(pluginDisplayName(plugin));
            item.setPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
            items.add(item);
        }
        return items;
    }

    static List<AutomationResponse> automationResponses(List<PluginAutomation> automations,
                                                        Map<String, Plugin> pluginsById,
                                                        Map<String, PluginCapability> capabilitiesByKey) {
        List<AutomationResponse> items = new ArrayList<AutomationResponse>();
        for (PluginAutomation automation : automations) {
            items.add(automationResponse(automation, pluginsById, capabilitiesByKey));
        }
        return items;
    }

    static AutomationResponse automationResponse(PluginAutomation automation,
                                                 Map<String, Plugin> pluginsById,
                                                 Map<String, PluginCapability> capabilitiesByKey) {
        AutomationResponse response = AutomationResponse.from(automation);
        if (RuntimeSystemAutomations.isRuntimeMaintenanceIdentity(automation.getId(), automation.getPluginId(), automation.getCapabilityKey())) {
            response.setName(PluginI18n.text("plugin.scheduler.maintenance"));
        }
        Plugin plugin = pluginsById.get(automation.getPluginId());
        PluginCapability capability = capabilitiesByKey.get(capabilityMapKey(automation.getPluginId(), automation.getCapabilityKey()));
        response.setPluginName(runtimePluginDisplayName(automation.getPluginId(), plugin, capability));
        response.setPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
        response.setTargetLabel(automationTargetLabel(automation.getId(), automation.getPluginId(), automation.getCapabilityKey(), automation.getName(), plugin, capability));
        return response;
    }

    static List<AutomationRunResponse> automationRunResponses(List<PluginAutomationRun> runs,
                                                              Map<String, Plugin> pluginsById,
                                                              Map<String, PluginCapability> capabilitiesByKey) {
        List<AutomationRunResponse> items = new ArrayList<AutomationRunResponse>();
        for (PluginAutomationRun run : runs) {
            items.add(automationRunResponse(run, pluginsById, capabilitiesByKey));
        }
        return items;
    }

    static AutomationRunResponse automationRunResponse(PluginAutomationRun run,
                                                       Map<String, Plugin> pluginsById,
                                                       Map<String, PluginCapability> capabilitiesByKey) {
        AutomationRunResponse response = AutomationRunResponse.from(run);
        Plugin plugin = pluginsById.get(run.getPluginId());
        PluginCapability capability = capabilitiesByKey.get(capabilityMapKey(run.getPluginId(), run.getCapabilityKey()));
        response.setPluginName(runtimePluginDisplayName(run.getPluginId(), plugin, capability));
        response.setPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
        response.setTargetLabel(automationTargetLabel(run.getAutomationId(), run.getPluginId(), run.getCapabilityKey(), null, plugin, capability));
        return response;
    }

    static List<InvocationLogResponse> invocationLogResponses(List<CapabilityInvocationLog> logs, Map<String, Plugin> pluginsById) {
        List<InvocationLogResponse> items = new ArrayList<InvocationLogResponse>();
        for (CapabilityInvocationLog log : logs) {
            InvocationLogResponse response = new InvocationLogResponse();
            response.setId(log.getId());
            response.setPluginId(log.getPluginId());
            Plugin plugin = pluginsById.get(log.getPluginId());
            response.setPluginName(runtimePluginDisplayName(log.getPluginId(), plugin));
            response.setPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
            response.setCapabilityKey(log.getCapabilityKey());
            response.setSource(log.getSource());
            response.setRiskLevel(log.getRiskLevel());
            response.setAuditRequired(log.getAuditRequired());
            response.setRequestId(log.getRequestId());
            response.setTraceId(log.getTraceId());
            response.setStatus(log.getStatus());
            response.setStartedAt(log.getStartedAt());
            response.setFinishedAt(log.getFinishedAt());
            response.setDurationMs(log.getDurationMs());
            response.setErrorMessage(log.getErrorMessage());
            items.add(response);
        }
        return items;
    }

    static List<NotificationDeliveryResponse> notificationDeliveryResponses(List<NotificationDelivery> deliveries, Map<String, Plugin> pluginsById) {
        List<NotificationDeliveryResponse> items = new ArrayList<NotificationDeliveryResponse>();
        for (NotificationDelivery delivery : deliveries) {
            items.add(notificationDeliveryResponse(delivery, pluginsById));
        }
        return items;
    }

    static NotificationDeliveryResponse notificationDeliveryResponse(NotificationDelivery delivery, Map<String, Plugin> pluginsById) {
        NotificationDeliveryResponse response = NotificationDeliveryResponse.from(delivery);
        Plugin plugin = pluginsById.get(delivery.getProviderPluginId());
        response.setProviderPluginName(pluginDisplayName(plugin));
        response.setProviderPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
        return response;
    }

    static <T> PageResponse<T> pageResponse(List<T> rows, PageData<?> page) {
        return new PageResponse<T>(rows, page);
    }

    static String automationTargetLabel(String id,
                                        String pluginId,
                                        String capabilityKey,
                                        String fallbackName,
                                        Plugin plugin,
                                        PluginCapability capability) {
        String systemTargetLabel = systemAutomationTargetLabel(id, pluginId, capabilityKey);
        if (systemTargetLabel != null) {
            return systemTargetLabel;
        }
        return runtimePluginDisplayName(pluginId, plugin, capability) + " / " + capabilityDisplayLabel(capabilityKey, fallbackName, capability);
    }

    static Map<String, NotificationDelivery> latestNotificationDeliveryByProvider(List<NotificationDelivery> deliveries) {
        Map<String, NotificationDelivery> latest = new HashMap<String, NotificationDelivery>();
        if (deliveries == null) {
            return latest;
        }
        for (NotificationDelivery delivery : deliveries) {
            if (delivery == null) {
                continue;
            }
            String key = notificationProviderKey(delivery.getChannel(), delivery.getProviderPluginId(), delivery.getCapabilityKey());
            NotificationDelivery existing = latest.get(key);
            if (existing == null || deliveryTime(delivery) >= deliveryTime(existing)) {
                latest.put(key, delivery);
            }
        }
        return latest;
    }

    static String notificationProviderKey(String channel, String pluginId, String capabilityKey) {
        return Objects.toString(channel, "") + "\n" + Objects.toString(pluginId, "") + "\n" + Objects.toString(capabilityKey, "");
    }

    static String pluginPreviewImageBase64(Plugin plugin) {
        return plugin == null || isBlank(plugin.getPreviewImageBase64()) ? "" : plugin.getPreviewImageBase64();
    }

    static String pluginDisplayName(Plugin plugin) {
        return plugin == null || isBlank(plugin.getName()) ? PluginI18n.text("plugin.common.unnamedPlugin") : plugin.getName();
    }

    private static String capabilityDisplayLabel(String capabilityKey, String fallbackName, PluginCapability capability) {
        if (capability != null && !isBlank(capability.getLabel())) {
            return capability.getLabel();
        }
        if (!isBlank(fallbackName)) {
            return fallbackName;
        }
        return isBlank(capabilityKey) ? PluginI18n.text("plugin.common.unnamedTask") : capabilityKey;
    }

    private static String capabilityMapKey(String pluginId, String capabilityKey) {
        return Objects.toString(pluginId, "") + "\n" + Objects.toString(capabilityKey, "");
    }

    private static String systemAutomationTargetLabel(String id, String pluginId, String capabilityKey) {
        if (RuntimeSystemAutomations.isRuntimeMaintenanceIdentity(id, pluginId, capabilityKey)) {
            return PluginI18n.text("plugin.scheduler.maintenanceTarget");
        }
        return null;
    }

    private static String runtimePluginDisplayName(String pluginId, Plugin plugin) {
        return runtimePluginDisplayName(pluginId, plugin, null);
    }

    private static String runtimePluginDisplayName(String pluginId, Plugin plugin, PluginCapability capability) {
        if (RuntimeSystemAutomations.isSystemPluginId(pluginId)) {
            return PluginI18n.text("plugin.common.system");
        }
        if (plugin != null && !isBlank(plugin.getName())) {
            return plugin.getName();
        }
        if (capability != null && !isBlank(capability.getPluginName())) {
            return capability.getPluginName();
        }
        return pluginDisplayName(plugin);
    }

    private static long deliveryTime(NotificationDelivery delivery) {
        return delivery.getCreatedAt() == null ? 0 : delivery.getCreatedAt();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
