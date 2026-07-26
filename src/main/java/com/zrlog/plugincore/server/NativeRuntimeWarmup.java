package com.zrlog.plugincore.server;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.MsgPacketDispose;
import com.zrlog.plugin.api.Capability;
import com.zrlog.plugin.api.IPluginService;
import com.zrlog.plugin.api.ScheduledCapability;
import com.zrlog.plugin.api.Service;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.client.NioClient;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.ArticleExtensionGetRequest;
import com.zrlog.plugin.message.ArticleExtensionQueryRequest;
import com.zrlog.plugin.message.ArticleExtensionQueryResult;
import com.zrlog.plugin.message.ArticleExtensionResult;
import com.zrlog.plugin.message.ArticleExtensionSetRequest;
import com.zrlog.plugin.message.DbPropertiesResponse;
import com.zrlog.plugin.message.NotificationRequest;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugin.message.PluginProcessInfo;
import com.zrlog.plugin.message.SchedulerQueryRequest;
import com.zrlog.plugin.message.SchedulerQueryResult;
import com.zrlog.plugin.message.SchedulerUpdateRequest;
import com.zrlog.plugin.message.SchedulerUpdateResult;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.runtime.capability.CapabilityRegistrationService;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.notification.NotificationDelivery;
import com.zrlog.plugincore.server.runtime.notification.NotificationDeliveryStore;
import com.zrlog.plugincore.server.runtime.notification.NotificationProviderResolver;
import com.zrlog.plugincore.server.runtime.notification.NotificationPublishResult;
import com.zrlog.plugincore.server.runtime.notification.NotificationRuntime;
import com.zrlog.plugincore.server.runtime.notification.NotificationSetting;
import com.zrlog.plugincore.server.runtime.scheduler.AutomationStore;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomationRun;
import com.zrlog.plugincore.server.runtime.scheduler.RuntimeAutomationService;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerQueryService;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerSetting;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerTickResult;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerUpdateService;
import com.zrlog.plugincore.server.runtime.service.ServiceSetting;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import com.zrlog.plugincore.server.runtime.plugin.transport.PluginTransportModels;
import com.zrlog.plugincore.server.runtime.pwa.PluginPwaManifest;
import com.zrlog.plugincore.server.vo.PluginCoreSetting;
import com.zrlog.plugincore.server.web.controller.RuntimeApiModels;
import com.zrlog.plugincore.server.web.controller.PluginApiModels;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class NativeRuntimeWarmup {

    private NativeRuntimeWarmup() {
    }

    static Result run() {
        Gson gson = new Gson();
        warmupActionTypes();
        int annotatedCapabilityCount = warmupAnnotatedCapabilityRead(gson);
        int actionDispatchCount = warmupActionDispatch(gson);

        Plugin plugin = gson.fromJson(samplePluginJson(), Plugin.class);
        String initPayload = gson.toJson(plugin);
        KvRepository kvStore = new MemoryKvRepository();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        List<PluginCapability> capabilities = new CapabilityRegistrationService(capabilityStore)
                .registerCapabilitiesFromInitPayload(plugin, initPayload);

        AutomationStore automationStore = new AutomationStore(kvStore);
        ZonedDateTime now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        List<PluginAutomation> automations = new RuntimeAutomationService(automationStore, capabilityStore, new BasicCronParser())
                .ensureDefaultAutomations(capabilities, now);

        SchedulerQueryRequest schedulerQueryRequest = gson.fromJson("{"
                + "\"capabilityKey\":\"native.scan\""
                + "}", SchedulerQueryRequest.class);
        SchedulerQueryResult schedulerQueryResult = new SchedulerQueryService(automationStore, capabilityStore)
                .query(plugin, schedulerQueryRequest);

        SchedulerUpdateRequest schedulerRequest = gson.fromJson("{"
                + "\"capabilityKey\":\"native.scan\","
                + "\"cron\":\"*/10 * * * *\","
                + "\"enabled\":true"
                + "}", SchedulerUpdateRequest.class);
        SchedulerUpdateResult schedulerResult = new SchedulerUpdateService(automationStore, capabilityStore, new BasicCronParser())
                .update(plugin, schedulerRequest, now);

        gson.toJson(gson.fromJson("{"
                + "\"enabled\":true,"
                + "\"externalHost\":\"https://example.com\","
                + "\"providers\":[{\"id\":\"default\",\"enabled\":true,\"secret\":\"secret\"}]"
                + "}", SchedulerSetting.class));
        gson.toJson(gson.fromJson("{"
                + "\"defaultProviders\":{\"comment\":{\"serviceName\":\"comment\",\"pluginId\":\"native-plugin\","
                + "\"capabilityKey\":\"native.comment\"}}"
                + "}", ServiceSetting.class));

        NotificationSetting notificationSetting = gson.fromJson("{"
                + "\"defaultProviders\":{\"email\":{\"pluginId\":\"native-plugin\","
                + "\"capabilityKey\":\"native.email\",\"channel\":\"email\"}}"
                + "}", NotificationSetting.class);
        gson.toJson(notificationSetting);
        NotificationRequest notificationRequest = gson.fromJson("{"
                + "\"sourcePluginId\":\"native-plugin\","
                + "\"sourcePluginName\":\"Native Plugin\","
                + "\"sourceCapabilityKey\":\"native.scan\","
                + "\"eventType\":\"warmup\","
                + "\"notificationType\":\"runtime\","
                + "\"channels\":[\"email\"],"
                + "\"title\":\"warmup\","
                + "\"content\":\"warmup\","
                + "\"level\":\"info\","
                + "\"requestId\":\"warmup-request\","
                + "\"traceId\":\"warmup-trace\","
                + "\"payload\":{\"ok\":true}"
                + "}", NotificationRequest.class);
        NotificationPublishResult publishResult = new NotificationRuntime(
                capabilityStore,
                new NotificationDeliveryStore(kvStore),
                new NotificationProviderResolver(),
                notificationSetting,
                (pluginId, capabilityKey, payload, context) -> {
                    CapabilityInvokeRequest invokeRequest = new CapabilityInvokeRequest();
                    invokeRequest.setPluginId(pluginId);
                    invokeRequest.setCapabilityKey(capabilityKey);
                    invokeRequest.setSource(context == null ? null : context.getSource());
                    invokeRequest.setPayload(payload);
                    gson.toJson(gson.fromJson(gson.toJson(invokeRequest), CapabilityInvokeRequest.class));
                    CapabilityInvokeResult result = new CapabilityInvokeResult();
                    result.setSuccess(true);
                    result.setData(new HashMap<String, Object>());
                    return gson.fromJson(gson.toJson(result), CapabilityInvokeResult.class);
                })
                .publish(notificationRequest);

        warmupRuntimeApiModels(gson, capabilities, automations, publishResult);

        return new Result(capabilities.size(), automations.size(), schedulerQueryResult.isSuccess(), !schedulerResult.isSuccess(),
                publishResult.getSuccessCount(), publishResult.getFailedCount(), annotatedCapabilityCount, actionDispatchCount);
    }

    private static void warmupActionTypes() {
        ActionType.valueOf(ActionType.CAPABILITY_INVOKE.name());
        ActionType.valueOf(ActionType.NOTIFICATION_PUBLISH.name());
        ActionType.valueOf(ActionType.NOTIFICATION_CHANNEL_QUERY.name());
        ActionType.valueOf(ActionType.SCHEDULER_QUERY.name());
        ActionType.valueOf(ActionType.PLUGIN_PROCESS_QUERY.name());
        ActionType.valueOf(ActionType.SCHEDULER_UPDATE.name());
        ActionType.valueOf(ActionType.ARTICLE_EXTENSION_GET.name());
        ActionType.valueOf(ActionType.ARTICLE_EXTENSION_SET.name());
        ActionType.valueOf(ActionType.ARTICLE_EXTENSION_QUERY.name());
    }

    @SuppressWarnings("unchecked")
    private static int warmupAnnotatedCapabilityRead(Gson gson) {
        try {
            Method method = NioClient.class.getDeclaredMethod("readCapabilities", Class.class);
            method.setAccessible(true);
            NioClient client = new NioClient();
            List<PluginCapability> scheduled = (List<PluginCapability>) method.invoke(client, WarmupScheduledService.class);
            List<PluginCapability> notification = (List<PluginCapability>) method.invoke(client, WarmupNotificationService.class);
            gson.toJson(scheduled);
            gson.toJson(notification);
            return scheduled.size() + notification.size();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to warm up plugin capability annotations", e);
        }
    }

    private static int warmupActionDispatch(Gson gson) {
        CountingActionHandler actionHandler = new CountingActionHandler(gson);
        MsgPacketDispose dispose = new MsgPacketDispose();
        dispose.handler(null, packet(new CapabilityInvokeRequest(), ActionType.CAPABILITY_INVOKE), actionHandler);
        dispose.handler(null, packet(new NotificationRequest(), ActionType.NOTIFICATION_PUBLISH), actionHandler);
        dispose.handler(null, packet(new PluginApiModels.EmptyResponse(), ActionType.NOTIFICATION_CHANNEL_QUERY), actionHandler);
        dispose.handler(null, packet(new SchedulerQueryRequest(), ActionType.SCHEDULER_QUERY), actionHandler);
        dispose.handler(null, packet(new byte[0], ContentType.BYTE, ActionType.PLUGIN_PROCESS_QUERY), actionHandler);
        dispose.handler(null, packet(new SchedulerUpdateRequest(), ActionType.SCHEDULER_UPDATE), actionHandler);
        dispose.handler(null, packet(new ArticleExtensionGetRequest(), ActionType.ARTICLE_EXTENSION_GET), actionHandler);
        dispose.handler(null, packet(new ArticleExtensionSetRequest(), ActionType.ARTICLE_EXTENSION_SET), actionHandler);
        dispose.handler(null, packet(new ArticleExtensionQueryRequest(), ActionType.ARTICLE_EXTENSION_QUERY), actionHandler);
        return actionHandler.getCount();
    }

    private static MsgPacket packet(Object data, ActionType actionType) {
        return new MsgPacket(data, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, actionType.ordinal() + 1, actionType.name());
    }

    private static MsgPacket packet(Object data, ContentType contentType, ActionType actionType) {
        return new MsgPacket(data, contentType, MsgPacketStatus.SEND_REQUEST, actionType.ordinal() + 1, actionType.name());
    }

    private static void warmupRuntimeApiModels(Gson gson,
                                               List<PluginCapability> capabilities,
                                               List<PluginAutomation> automations,
                                               NotificationPublishResult publishResult) {
        List<RuntimeApiModels.CapabilityResponse> capabilityResponses = new ArrayList<RuntimeApiModels.CapabilityResponse>();
        for (PluginCapability capability : capabilities) {
            RuntimeApiModels.CapabilityResponse response = RuntimeApiModels.CapabilityResponse.from(capability);
            response.setPluginName("Native Plugin");
            response.setPluginPreviewImageBase64("preview");
            capabilityResponses.add(response);
        }

        List<RuntimeApiModels.AutomationResponse> automationResponses = new ArrayList<RuntimeApiModels.AutomationResponse>();
        for (PluginAutomation automation : automations) {
            RuntimeApiModels.AutomationResponse response = RuntimeApiModels.AutomationResponse.from(automation);
            response.setPluginName("Native Plugin");
            response.setPluginPreviewImageBase64("preview");
            response.setTargetLabel("Native Plugin / Native scan");
            automationResponses.add(response);
        }

        PluginAutomationRun automationRun = new PluginAutomationRun();
        automationRun.setId("warmup-run");
        automationRun.setAutomationId(automations.isEmpty() ? "warmup-automation" : automations.get(0).getId());
        automationRun.setPluginId("native-plugin");
        automationRun.setCapabilityKey("native.scan");
        automationRun.setStatus("success");
        automationRun.setStartedAt(1L);
        automationRun.setFinishedAt(2L);
        RuntimeApiModels.AutomationRunResponse automationRunResponse = RuntimeApiModels.AutomationRunResponse.from(automationRun);
        automationRunResponse.setPluginName("Native Plugin");
        automationRunResponse.setPluginPreviewImageBase64("preview");
        automationRunResponse.setTargetLabel("Native Plugin / Native scan");

        CapabilityInvocationLog invocationLog = new CapabilityInvocationLog();
        invocationLog.setId("warmup-log");
        invocationLog.setPluginId("native-plugin");
        invocationLog.setCapabilityKey("native.scan");
        invocationLog.setSource("native-warmup");
        invocationLog.setRiskLevel("medium");
        invocationLog.setAuditRequired(false);
        invocationLog.setRequestId("warmup-request");
        invocationLog.setTraceId("warmup-trace");
        invocationLog.setStatus("success");
        invocationLog.setStartedAt(1L);
        invocationLog.setFinishedAt(2L);
        invocationLog.setDurationMs(1L);
        RuntimeApiModels.InvocationLogResponse invocationLogResponse = new RuntimeApiModels.InvocationLogResponse();
        invocationLogResponse.setId(invocationLog.getId());
        invocationLogResponse.setPluginId(invocationLog.getPluginId());
        invocationLogResponse.setPluginName("Native Plugin");
        invocationLogResponse.setPluginPreviewImageBase64("preview");
        invocationLogResponse.setCapabilityKey(invocationLog.getCapabilityKey());
        invocationLogResponse.setSource(invocationLog.getSource());
        invocationLogResponse.setRiskLevel(invocationLog.getRiskLevel());
        invocationLogResponse.setAuditRequired(invocationLog.getAuditRequired());
        invocationLogResponse.setRequestId(invocationLog.getRequestId());
        invocationLogResponse.setTraceId(invocationLog.getTraceId());
        invocationLogResponse.setStatus(invocationLog.getStatus());
        invocationLogResponse.setStartedAt(invocationLog.getStartedAt());
        invocationLogResponse.setFinishedAt(invocationLog.getFinishedAt());
        invocationLogResponse.setDurationMs(invocationLog.getDurationMs());

        RuntimeApiModels.NotificationDeliveryResponse deliveryResponse = notificationDeliveryResponse(publishResult);
        RuntimeApiModels.SchedulerSettingsResponse schedulerSettingsResponse = new RuntimeApiModels.SchedulerSettingsResponse();
        schedulerSettingsResponse.setEnabled(true);
        schedulerSettingsResponse.setExternalHost("https://example.com");
        schedulerSettingsResponse.setEffectiveExternalHost("https://example.com");
        schedulerSettingsResponse.setExternalTickPath("/api/runtime/scheduler/tick");
        schedulerSettingsResponse.setExternalTickUrl("https://example.com/api/runtime/scheduler/tick");
        schedulerSettingsResponse.setSystemTimezone("UTC");

        RuntimeApiModels.PageResponse<RuntimeApiModels.AutomationRunResponse> automationRunPage = new RuntimeApiModels.PageResponse<RuntimeApiModels.AutomationRunResponse>();
        automationRunPage.setRows(Collections.singletonList(automationRunResponse));
        automationRunPage.setPage(1L);
        automationRunPage.setSize(8L);
        automationRunPage.setTotalElements(1L);

        RuntimeApiModels.PageResponse<RuntimeApiModels.InvocationLogResponse> invocationLogPage = new RuntimeApiModels.PageResponse<RuntimeApiModels.InvocationLogResponse>();
        invocationLogPage.setRows(Collections.singletonList(invocationLogResponse));
        invocationLogPage.setPage(1L);
        invocationLogPage.setSize(10L);
        invocationLogPage.setTotalElements(1L);

        RuntimeApiModels.ServiceProviderRow serviceProviderRow = new RuntimeApiModels.ServiceProviderRow();
        serviceProviderRow.setServiceName("uploadService");
        serviceProviderRow.setServiceLabel("上传服务");
        serviceProviderRow.setProviderPluginId("native-plugin");
        serviceProviderRow.setProviderPluginName("Native Plugin");
        serviceProviderRow.setProviderPluginPreviewImageBase64("preview");
        serviceProviderRow.setCapabilityKey("native.scan");
        serviceProviderRow.setCapabilityLabel("Native scan");
        serviceProviderRow.setSelected(true);
        serviceProviderRow.setConfirmed(true);
        serviceProviderRow.setReviewRequired(false);

        RuntimeApiModels.CommentProviderRow commentProviderRow = new RuntimeApiModels.CommentProviderRow();
        commentProviderRow.setShortName("native");
        commentProviderRow.setPluginId("native-plugin");
        commentProviderRow.setPluginName("Native Plugin");
        commentProviderRow.setPluginPreviewImageBase64("preview");
        commentProviderRow.setDescription("Native plugin");
        commentProviderRow.setSelected(true);
        commentProviderRow.setConfirmed(true);
        commentProviderRow.setReviewRequired(false);

        serializeRoundTrip(gson, RuntimeApiModels.Response.success());
        serializeRoundTrip(gson, RuntimeApiModels.Response.error("warmup"));
        serializeRoundTrip(gson, new RuntimeApiModels.ItemsResponse<RuntimeApiModels.CapabilityResponse>(capabilityResponses));
        serializeRoundTrip(gson, automationRunPage);
        serializeRoundTrip(gson, invocationLogPage);
        serializeRoundTrip(gson, new RuntimeApiModels.ItemResponse<RuntimeApiModels.AutomationResponse>(
                automationResponses.isEmpty() ? new RuntimeApiModels.AutomationResponse() : automationResponses.get(0)));
        serializeRoundTrip(gson, new RuntimeApiModels.ResultResponse(new SchedulerTickResult()));
        serializeRoundTrip(gson, RuntimeApiModels.ActionResponse.started());
        serializeRoundTrip(gson, RuntimeApiModels.ActionResponse.removed(true));
        serializeRoundTrip(gson, RuntimeApiModels.ActionResponse.successFlag(true));
        serializeRoundTrip(gson, schedulerSettingsResponse);
        serializeRoundTrip(gson, new RuntimeApiModels.RuntimeSettingsResponse(new PluginRuntimeSetting()));
        serializeRoundTrip(gson, new RuntimeApiModels.AutomationsResponse(automationResponses, "UTC"));
        serializeRoundTrip(gson, new RuntimeApiModels.NotificationTestResponse(true, deliveryResponse));
        serializeRoundTrip(gson, new RuntimeApiModels.CommentProvidersResponse(Collections.singletonList(commentProviderRow), "native"));
        serializeRoundTrip(gson, serviceProviderRow);
        serializeRoundTrip(gson, commentProviderRow);
        serializeRoundTrip(gson, deliveryResponse);
        warmupPluginApiModels(gson);
    }

    private static void warmupPluginApiModels(Gson gson) {
        PluginApiModels.PluginListResponse pluginListResponse = new PluginApiModels.PluginListResponse();
        pluginListResponse.setPlugins(Collections.<Plugin>emptyList());
        pluginListResponse.setSetting(new PluginCoreSetting());
        pluginListResponse.setPluginMetadataReady(true);
        pluginListResponse.setPluginMetadataLoading(false);
        pluginListResponse.setDark(false);
        pluginListResponse.setPrimaryColor("#1677ff");
        pluginListResponse.setPluginVersion("native");
        pluginListResponse.setPluginBuildId("native-build");
        pluginListResponse.setPluginBuildNumber("1");
        pluginListResponse.setRequiredPlugins(Collections.singleton("comment"));
        pluginListResponse.setPluginCenter("https://store.zrlog.com/plugin/index.html?upgrade-v3=true&from=#locationHref");

        PluginApiModels.RefreshCacheResponse refreshCacheResponse = new PluginApiModels.RefreshCacheResponse();
        refreshCacheResponse.setCode(0);
        refreshCacheResponse.setMessage("warmup");
        refreshCacheResponse.setRuntimeEventSuccessCount(1);
        refreshCacheResponse.setRuntimeEventFailedCount(0);
        refreshCacheResponse.setRuntimeEventHandlerCount(1);
        refreshCacheResponse.setLegacySessionCount(0);
        refreshCacheResponse.setSuccessCount(1);

        serializeRoundTrip(gson, new PluginApiModels.EmptyResponse());
        serializeRoundTrip(gson, PluginApiModels.ActionResponse.success("warmup"));
        serializeRoundTrip(gson, pluginListResponse);
        serializeRoundTrip(gson, refreshCacheResponse);
        serializeRoundTrip(gson, new PluginApiModels.StatusResponse(0, "STARTED", Collections.singletonList("comment")));
        warmupPluginTransportModels(gson);
        serializeRoundTrip(gson, pwaManifest());
    }

    private static void warmupPluginTransportModels(Gson gson) {
        serializeRoundTrip(gson, new PluginTransportModels.EmptyResponse());
        serializeRoundTrip(gson, gson.fromJson("{\"name\":\"email\"}", PluginTransportModels.ServiceRequest.class));
        serializeRoundTrip(gson, gson.fromJson("{\"key\":\"title,host\"}", PluginTransportModels.WebsiteLoadRequest.class));
        serializeRoundTrip(gson, gson.fromJson("{\"syncTemplate\":true,\"host\":\"https://static.example\",\"folder\":\"assets\"}",
                PluginTransportModels.WebsiteSyncOptions.class));
        serializeRoundTrip(gson, gson.fromJson("{\"alias\":\"hello-world\"}", PluginTransportModels.ArticleVisitRequest.class));
        serializeRoundTrip(gson, PluginTransportModels.ServiceErrorResponse.error("warmup"));
        serializeRoundTrip(gson, new PluginTransportModels.InitResponse("AGENT"));
        serializeRoundTrip(gson, PluginTransportModels.InitErrorResponse.error("warmup"));
        serializeRoundTrip(gson, PluginTransportModels.OperationResult.success(true));
        serializeRoundTrip(gson, PluginTransportModels.OperationResult.error("warmup"));
        serializeRoundTrip(gson, new DbPropertiesResponse("/tmp/db.properties"));
    }

    private static PluginPwaManifest pwaManifest() {
        PluginPwaManifest manifest = new PluginPwaManifest();
        manifest.setId("/admin/plugins/comment/");
        manifest.setName("Comment");
        manifest.setShortName("comment");
        manifest.setDescription("Native warmup");
        manifest.setStartUrl("/admin/plugins/comment/");
        manifest.setScope("/admin/plugins/comment/");
        manifest.setDisplay("standalone");
        manifest.setThemeColor("#1677ff");
        manifest.setBackgroundColor("#FFFFFF");
        PluginPwaManifest.Icon icon = new PluginPwaManifest.Icon();
        icon.setSrc("/admin/plugins/comment/pwa-icon");
        icon.setSizes("512x512");
        icon.setType("image/png");
        manifest.setIcons(Collections.singletonList(icon));
        return manifest;
    }

    private static RuntimeApiModels.NotificationDeliveryResponse notificationDeliveryResponse(NotificationPublishResult publishResult) {
        NotificationDelivery delivery = publishResult.getDeliveries() == null || publishResult.getDeliveries().isEmpty()
                ? null
                : publishResult.getDeliveries().get(0);
        RuntimeApiModels.NotificationDeliveryResponse response = delivery == null
                ? new RuntimeApiModels.NotificationDeliveryResponse()
                : RuntimeApiModels.NotificationDeliveryResponse.from(delivery);
        response.setProviderPluginName("Native Plugin");
        response.setProviderPluginPreviewImageBase64("preview");
        return response;
    }

    private static void serializeRoundTrip(Gson gson, Object value) {
        gson.fromJson(gson.toJson(value), value.getClass());
    }

    private static String samplePluginJson() {
        return "{"
                + "\"id\":\"native-plugin\","
                + "\"shortName\":\"native\","
                + "\"name\":\"Native Plugin\","
                + "\"services\":[\"native.scan\",\"native.email\"],"
                + "\"capabilities\":[{"
                + "\"key\":\"native.scan\","
                + "\"serviceName\":\"native.scan\","
                + "\"type\":\"scheduled\","
                + "\"label\":\"Native scan\","
                + "\"description\":\"Native scan warmup\","
                + "\"exposure\":[\"scheduler\"],"
                + "\"riskLevel\":\"low\","
                + "\"readOnly\":false,"
                + "\"requiresConfirmation\":false,"
                + "\"timeoutSeconds\":600,"
                + "\"concurrency\":1,"
                + "\"enabled\":true,"
                + "\"defaultCron\":\"*/5 * * * *\","
                + "\"timezone\":\"system\""
                + "},{"
                + "\"key\":\"native.email\","
                + "\"serviceName\":\"native.email\","
                + "\"type\":\"notification_channel\","
                + "\"label\":\"Native email\","
                + "\"description\":\"Native email warmup\","
                + "\"exposure\":[\"notification\"],"
                + "\"riskLevel\":\"medium\","
                + "\"readOnly\":false,"
                + "\"requiresConfirmation\":false,"
                + "\"timeoutSeconds\":600,"
                + "\"concurrency\":1,"
                + "\"enabled\":true,"
                + "\"channel\":\"email\""
                + "}]"
                + "}";
    }

    static final class Result {
        private final int capabilityCount;
        private final int automationCount;
        private final boolean schedulerQuerySuccess;
        private final boolean schedulerUpdateRejected;
        private final int notificationSuccessCount;
        private final int notificationFailedCount;
        private final int annotatedCapabilityCount;
        private final int actionDispatchCount;

        Result(int capabilityCount, int automationCount, boolean schedulerQuerySuccess, boolean schedulerUpdateRejected,
               int notificationSuccessCount, int notificationFailedCount, int annotatedCapabilityCount, int actionDispatchCount) {
            this.capabilityCount = capabilityCount;
            this.automationCount = automationCount;
            this.schedulerQuerySuccess = schedulerQuerySuccess;
            this.schedulerUpdateRejected = schedulerUpdateRejected;
            this.notificationSuccessCount = notificationSuccessCount;
            this.notificationFailedCount = notificationFailedCount;
            this.annotatedCapabilityCount = annotatedCapabilityCount;
            this.actionDispatchCount = actionDispatchCount;
        }

        int getCapabilityCount() {
            return capabilityCount;
        }

        int getAutomationCount() {
            return automationCount;
        }

        boolean isSchedulerQuerySuccess() {
            return schedulerQuerySuccess;
        }

        boolean isSchedulerUpdateRejected() {
            return schedulerUpdateRejected;
        }

        int getNotificationSuccessCount() {
            return notificationSuccessCount;
        }

        int getNotificationFailedCount() {
            return notificationFailedCount;
        }

        int getAnnotatedCapabilityCount() {
            return annotatedCapabilityCount;
        }

        int getActionDispatchCount() {
            return actionDispatchCount;
        }
    }

    private static final class CountingActionHandler extends ClientActionHandler {
        private final Gson gson;
        private int count;

        private CountingActionHandler(Gson gson) {
            this.gson = gson;
        }

        @Override
        public void capabilityInvoke(IOSession session, MsgPacket msgPacket) {
            gson.toJson(msgPacket.convertToClass(CapabilityInvokeRequest.class));
            gson.toJson(new CapabilityInvokeResult());
            count++;
        }

        @Override
        public void notificationPublish(IOSession session, MsgPacket msgPacket) {
            gson.toJson(msgPacket.convertToClass(NotificationRequest.class));
            count++;
        }

        @Override
        public void notificationChannelQuery(IOSession session, MsgPacket msgPacket) {
            gson.toJson(new PluginApiModels.EmptyResponse());
            count++;
        }

        @Override
        public void schedulerQuery(IOSession session, MsgPacket msgPacket) {
            gson.toJson(msgPacket.convertToClass(SchedulerQueryRequest.class));
            gson.toJson(new SchedulerQueryResult());
            count++;
        }

        @Override
        public void pluginProcessQuery(IOSession session, MsgPacket msgPacket) {
            gson.toJson(new PluginProcessInfo());
            count++;
        }

        @Override
        public void schedulerUpdate(IOSession session, MsgPacket msgPacket) {
            gson.toJson(msgPacket.convertToClass(SchedulerUpdateRequest.class));
            SchedulerUpdateResult result = new SchedulerUpdateResult();
            result.setSuccess(false);
            result.setErrorMessage("Scheduler writes are managed by plugin-core");
            gson.toJson(result);
            count++;
        }

        @Override
        public void getArticleExtension(IOSession session, MsgPacket msgPacket) {
            gson.toJson(msgPacket.convertToClass(ArticleExtensionGetRequest.class));
            gson.toJson(new ArticleExtensionResult());
            count++;
        }

        @Override
        public void setArticleExtension(IOSession session, MsgPacket msgPacket) {
            gson.toJson(msgPacket.convertToClass(ArticleExtensionSetRequest.class));
            gson.toJson(new ArticleExtensionResult());
            count++;
        }

        @Override
        public void queryArticleExtension(IOSession session, MsgPacket msgPacket) {
            gson.toJson(msgPacket.convertToClass(ArticleExtensionQueryRequest.class));
            gson.toJson(new ArticleExtensionQueryResult());
            count++;
        }

        private int getCount() {
            return count;
        }
    }

    @Service("native.scan")
    @Capability(key = "native.scan", riskLevel = "medium", readOnly = true)
    @ScheduledCapability(key = "native.scan", label = "Native scan", defaultCron = "*/5 * * * *")
    public static final class WarmupScheduledService implements IPluginService {
        @Override
        public void handle(IOSession session, MsgPacket msgPacket) {
        }
    }

    @Service("native.email")
    @Capability(key = "native.email",
            type = "notification_channel",
            label = "Native email",
            exposure = {"notification"},
            channel = "email")
    public static final class WarmupNotificationService implements IPluginService {
        @Override
        public void handle(IOSession session, MsgPacket msgPacket) {
        }
    }

    private static final class MemoryKvRepository implements KvRepository {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }
    }
}
