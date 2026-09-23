package com.zrlog.plugincore.server.web.controller;

import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.runtime.notification.NotificationDelivery;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.util.PluginI18n;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeApiControllerTest {

    @Test
    public void shouldNotLoadRuntimeStatesInNativeAgentMode() {
        RunType previous = RunConstants.runType;
        try {
            RunConstants.runType = RunType.AGENT;

            assertTrue(RuntimeStateApiController.runtimeStatesForCurrentMode().isEmpty());
        } finally {
            RunConstants.runType = previous;
        }
    }

    @Test
    public void shouldBuildPluginAutomationTargetLabel() {
        Plugin plugin = new Plugin();
        plugin.setName("RSS 订阅源");
        PluginCapability capability = new PluginCapability();
        capability.setPluginId("rss-2");
        capability.setPluginName("RSS 订阅源");
        capability.setKey("rss.refreshFeed");
        capability.setLabel("刷新 RSS 文件");

        assertEquals("RSS 订阅源 / 刷新 RSS 文件",
                RuntimeApiResponses.automationTargetLabel("default:rss-2:rss.refreshFeed",
                        "rss-2", "rss.refreshFeed", "自定义任务名", plugin, capability));
    }

    @Test
    public void shouldBuildSystemAutomationTargetLabel() {
        assertEquals("系统任务 / 运行态维护",
                RuntimeApiResponses.automationTargetLabel("system:plugin-runtime-maintenance",
                        "__system__", "plugin.runtime.maintenance", "运行态维护", null, null));
    }

    @Test
    public void shouldFindLatestNotificationDeliveryByProvider() {
        NotificationDelivery oldDelivery = delivery("email", "email-plugin", "notification.email.send", "error", 100L);
        NotificationDelivery newDelivery = delivery("email", "email-plugin", "notification.email.send", "success", 200L);
        NotificationDelivery otherDelivery = delivery("webhook", "webhook-plugin", "notification.webhook.send", "success", 300L);

        Map<String, NotificationDelivery> latest = RuntimeApiResponses.latestNotificationDeliveryByProvider(
                Arrays.asList(oldDelivery, otherDelivery, newDelivery));

        assertEquals(2, latest.size());
        assertEquals("success", latest.get("email\nemail-plugin\nnotification.email.send").getStatus());
        assertEquals(Long.valueOf(200L), latest.get("email\nemail-plugin\nnotification.email.send").getCreatedAt());
    }

    @Test
    public void shouldTranslateSystemTaskResponseWithoutChangingStoredTask() {
        PluginAutomation task = new PluginAutomation();
        task.setId("system:plugin-runtime-maintenance");
        task.setPluginId("__system__");
        task.setCapabilityKey("plugin.runtime.maintenance");
        task.setName("运行态维护");
        try (PluginI18n.Scope ignored = PluginI18n.open("en_US")) {
            RuntimeApiModels.AutomationResponse response = RuntimeApiResponses.automationResponse(
                    task, Collections.emptyMap(), Collections.emptyMap());
            assertEquals("Runtime maintenance", response.getName());
            assertEquals("System", response.getPluginName());
            assertEquals("System task / Runtime maintenance", response.getTargetLabel());
            assertEquals("Success", RuntimeApiModels.Response.success().getMessage());
        }
        assertEquals("运行态维护", task.getName());
    }

    private NotificationDelivery delivery(String channel, String pluginId, String capabilityKey, String status, Long createdAt) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setChannel(channel);
        delivery.setProviderPluginId(pluginId);
        delivery.setCapabilityKey(capabilityKey);
        delivery.setStatus(status);
        delivery.setCreatedAt(createdAt);
        return delivery;
    }
}
