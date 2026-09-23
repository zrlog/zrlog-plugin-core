package com.zrlog.plugincore.server.web.controller;

import com.zrlog.plugincore.server.util.PluginI18n;

import com.hibegin.common.dao.dto.PageData;
import com.hibegin.http.annotation.ResponseBody;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.message.NotificationChannelProvider;
import com.zrlog.plugin.message.NotificationChannelQueryResult;
import com.zrlog.plugin.message.NotificationRequest;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.RuntimeCapabilityInvokerFactory;
import com.zrlog.plugincore.server.runtime.notification.NotificationDelivery;
import com.zrlog.plugincore.server.runtime.notification.NotificationDeliveryStore;
import com.zrlog.plugincore.server.runtime.notification.NotificationProviderResolver;
import com.zrlog.plugincore.server.runtime.notification.NotificationProviderSetting;
import com.zrlog.plugincore.server.runtime.notification.NotificationPublishResult;
import com.zrlog.plugincore.server.runtime.notification.NotificationRuntime;
import com.zrlog.plugincore.server.runtime.notification.NotificationSetting;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.NotificationDeliveryResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.NotificationTestResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.PageResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.Response;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.*;
import static com.zrlog.plugincore.server.web.controller.RuntimeProviderResponses.notificationChannelProviders;
import static com.zrlog.plugincore.server.web.controller.RuntimeProviderResponses.validNotificationProvider;

public class RuntimeNotificationApiController extends RuntimeBaseApiController {

    @ResponseBody
    public NotificationChannelQueryResult notificationChannels() {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        List<NotificationChannelProvider> items = notificationChannelProviders(
                capabilityStore().listByType("notification_channel"),
                pluginCore.getSetting().getNotification(),
                pluginsById(pluginCore),
                notificationDeliveryStore().list());
        return NotificationChannelQueryResult.success(items);
    }

    @ResponseBody
    public Response notificationProviderUpdate() {
        String channel = getRequest().getParaToStr("channel");
        String pluginId = getRequest().getParaToStr("pluginId");
        String capabilityKey = getRequest().getParaToStr("capabilityKey");
        PluginCapability provider = capabilityStore().find(pluginId, capabilityKey).orElse(null);
        if (!validNotificationProvider(provider, channel)) {
            return error(PluginI18n.text("plugin.notification.notFound"));
        }
        NotificationProviderSetting providerSetting = new NotificationProviderSetting();
        providerSetting.setChannel(channel);
        providerSetting.setPluginId(pluginId);
        providerSetting.setCapabilityKey(capabilityKey);
        PluginCoreDAO.getInstance().update(pluginCore ->
                pluginCore.getSetting().getNotification().getDefaultProviders().put(channel, providerSetting));
        return success();
    }

    @ResponseBody
    public Response notificationProviderAuto() {
        String channel = getRequest().getParaToStr("channel");
        PluginCoreDAO.getInstance().update(pluginCore ->
                pluginCore.getSetting().getNotification().getDefaultProviders().remove(channel));
        return success();
    }

    @ResponseBody
    public Response notificationTest() {
        String channel = getRequest().getParaToStr("channel");
        String pluginId = getRequest().getParaToStr("pluginId");
        String capabilityKey = getRequest().getParaToStr("capabilityKey");
        KvRepository kvStore = kvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability provider = capabilityStore.find(pluginId, capabilityKey).orElse(null);
        if (!validNotificationProvider(provider, channel)) {
            return error(PluginI18n.text("plugin.notification.notFound"));
        }
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        NotificationProviderSetting providerSetting = new NotificationProviderSetting();
        providerSetting.setChannel(channel);
        providerSetting.setPluginId(pluginId);
        providerSetting.setCapabilityKey(capabilityKey);
        NotificationSetting notificationSetting = new NotificationSetting();
        notificationSetting.getDefaultProviders().put(channel, providerSetting);
        NotificationRequest request = notificationTestRequest(channel);
        NotificationPublishResult result = new NotificationRuntime(
                capabilityStore,
                new NotificationDeliveryStore(kvStore),
                new NotificationProviderResolver(),
                notificationSetting,
                RuntimeCapabilityInvokerFactory.socket(kvStore, pluginCore)
        ).publish(request);
        NotificationDelivery delivery = result.getDeliveries() == null || result.getDeliveries().isEmpty()
                ? null
                : result.getDeliveries().get(0);
        if (delivery == null) {
            return error(PluginI18n.text("plugin.notification.noDelivery"));
        }
        return new NotificationTestResponse(result.getSuccessCount() > 0,
                notificationDeliveryResponse(delivery, pluginsById(pluginCore)));
    }

    @ResponseBody
    public PageResponse<NotificationDeliveryResponse> notificationDeliveries() {
        PageData<NotificationDelivery> page = newestPage(notificationDeliveryStore().list(), 8);
        return pageResponse(notificationDeliveryResponses(page.getRows(), pluginsById()), page);
    }

    private NotificationRequest notificationTestRequest(String channel) {
        NotificationRequest request = new NotificationRequest();
        request.setSourcePluginId("__system__");
        request.setSourcePluginName(PluginI18n.text("plugin.common.system"));
        request.setSourceCapabilityKey("runtime.notification.test");
        request.setEventType("runtime.notification.test");
        request.setNotificationType("test");
        request.setChannels(Collections.singletonList(channel));
        request.setTitle(PluginI18n.text("plugin.notification.testTitle"));
        request.setContent(PluginI18n.text("plugin.notification.testContent"));
        request.setLevel("info");
        request.setRequestId(UUID.randomUUID().toString());
        request.setTraceId(UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("test", Boolean.TRUE);
        request.setPayload(payload);
        return request;
    }
}
