package com.zrlog.plugincore.server;

import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.DbPropertiesResponse;
import com.zrlog.plugin.message.NotificationRequest;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugin.message.PluginProcessInfo;
import com.zrlog.plugin.message.SchedulerQueryRequest;
import com.zrlog.plugin.message.SchedulerQueryResult;
import com.zrlog.plugin.message.SchedulerUpdateRequest;
import com.zrlog.plugin.message.SchedulerUpdateResult;
import com.zrlog.plugincore.server.runtime.notification.NotificationDelivery;
import com.zrlog.plugincore.server.runtime.notification.NotificationProviderSetting;
import com.zrlog.plugincore.server.runtime.notification.NotificationSetting;
import com.zrlog.plugincore.server.runtime.plugin.transport.PluginTransportModels;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomationRun;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerProviderSetting;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerSetting;
import com.zrlog.plugincore.server.runtime.service.ServiceProviderSetting;
import com.zrlog.plugincore.server.runtime.service.ServiceSetting;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeState;
import com.zrlog.plugincore.server.web.controller.RuntimeApiModels;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class GraalvmAgentApplicationTest {

    @Test
    public void shouldRegisterNestedRuntimeGsonModels() {
        List<Class<?>> classes = GraalvmAgentApplication.runtimeGsonClasses();

        assertTrue(classes.contains(Plugin.class));
        assertTrue(classes.contains(PluginCapability.class));
        assertTrue(classes.contains(CapabilityInvokeRequest.class));
        assertTrue(classes.contains(CapabilityInvokeResult.class));
        assertTrue(classes.contains(NotificationRequest.class));
        assertTrue(classes.contains(SchedulerQueryRequest.class));
        assertTrue(classes.contains(SchedulerQueryResult.class));
        assertTrue(classes.contains(SchedulerUpdateRequest.class));
        assertTrue(classes.contains(SchedulerUpdateResult.class));
        assertTrue(classes.contains(PluginProcessInfo.class));
        assertTrue(classes.contains(PluginAutomation.class));
        assertTrue(classes.contains(PluginAutomationRun.class));
        assertTrue(classes.contains(SchedulerSetting.class));
        assertTrue(classes.contains(SchedulerProviderSetting.class));
        assertTrue(classes.contains(NotificationDelivery.class));
        assertTrue(classes.contains(NotificationSetting.class));
        assertTrue(classes.contains(NotificationProviderSetting.class));
        assertTrue(classes.contains(ServiceSetting.class));
        assertTrue(classes.contains(ServiceProviderSetting.class));
        assertTrue(classes.contains(PluginRuntimeState.class));
        assertTrue(classes.contains(RuntimeApiModels.Response.class));
        assertTrue(classes.contains(RuntimeApiModels.ItemsResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.PageResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.ItemResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.ResultResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.ActionResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.SchedulerSettingsResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.RuntimeSettingsResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.AutomationsResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.NotificationTestResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.CommentProvidersResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.CapabilityResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.AutomationResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.AutomationRunResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.InvocationLogResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.NotificationDeliveryResponse.class));
        assertTrue(classes.contains(RuntimeApiModels.ServiceProviderRow.class));
        assertTrue(classes.contains(RuntimeApiModels.CommentProviderRow.class));
        assertTrue(classes.contains(PluginTransportModels.EmptyResponse.class));
        assertTrue(classes.contains(PluginTransportModels.ServiceRequest.class));
        assertTrue(classes.contains(PluginTransportModels.WebsiteLoadRequest.class));
        assertTrue(classes.contains(PluginTransportModels.WebsiteSyncOptions.class));
        assertTrue(classes.contains(PluginTransportModels.ArticleVisitRequest.class));
        assertTrue(classes.contains(PluginTransportModels.ServiceErrorResponse.class));
        assertTrue(classes.contains(PluginTransportModels.InitResponse.class));
        assertTrue(classes.contains(PluginTransportModels.InitErrorResponse.class));
        assertTrue(classes.contains(PluginTransportModels.OperationResult.class));
        assertTrue(classes.contains(DbPropertiesResponse.class));
    }

    @Test
    public void shouldWarmupRuntimeRegistrationAndActionModels() {
        NativeRuntimeWarmup.Result result = NativeRuntimeWarmup.run();

        assertEquals(2, result.getCapabilityCount());
        assertTrue(result.getAutomationCount() >= 1);
        assertTrue(result.isSchedulerQuerySuccess());
        assertTrue(result.isSchedulerUpdateRejected());
        assertEquals(1, result.getNotificationSuccessCount());
        assertEquals(0, result.getNotificationFailedCount());
        assertEquals(2, result.getAnnotatedCapabilityCount());
        assertEquals(6, result.getActionDispatchCount());
    }
}
