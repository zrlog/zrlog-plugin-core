package com.zrlog.plugincore.server.web.controller;

import com.zrlog.plugincore.server.util.PluginI18n;

import com.zrlog.plugin.message.NotificationChannelProvider;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.PluginCoreRunMode;
import com.zrlog.plugincore.server.runtime.notification.NotificationDelivery;
import com.zrlog.plugincore.server.runtime.notification.NotificationProviderResolver;
import com.zrlog.plugincore.server.runtime.notification.NotificationProviderSetting;
import com.zrlog.plugincore.server.runtime.notification.NotificationSetting;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.service.ServiceProviderResolver;
import com.zrlog.plugincore.server.runtime.service.ServiceProviderSetting;
import com.zrlog.plugincore.server.runtime.service.ServiceSetting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.latestNotificationDeliveryByProvider;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.notificationProviderKey;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.pluginDisplayName;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.pluginPreviewImageBase64;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.CommentProviderRow;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.CommentProvidersResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ServiceProviderRow;

final class RuntimeProviderResponses {

    static final String DEFAULT_COMMENT_PLUGIN = "comment";

    private RuntimeProviderResponses() {
    }

    static List<NotificationChannelProvider> notificationChannelProviders(List<PluginCapability> capabilities,
                                                                           NotificationSetting setting,
                                                                           Map<String, Plugin> pluginsById,
                                                                           List<NotificationDelivery> deliveries) {
        List<PluginCapability> providers = capabilities.stream()
                .filter(item -> item.getExposure() != null && item.getExposure().contains("notification"))
                .filter(item -> !isBlank(item.getChannel()))
                .collect(Collectors.toList());
        NotificationProviderResolver resolver = new NotificationProviderResolver();
        Set<String> channels = new HashSet<String>();
        for (PluginCapability provider : providers) {
            channels.add(provider.getChannel());
        }
        Map<String, NotificationDelivery> latestDeliveryByProvider = latestNotificationDeliveryByProvider(deliveries);
        List<NotificationChannelProvider> items = new ArrayList<NotificationChannelProvider>();
        for (String channel : channels) {
            PluginCapability selected = resolver.resolve(channel, providers, setting).orElse(null);
            boolean reviewRequired = resolver.reviewRequired(channel, providers, setting);
            for (PluginCapability provider : providers) {
                if (!Objects.equals(channel, provider.getChannel())) {
                    continue;
                }
                Plugin plugin = pluginsById.get(provider.getPluginId());
                NotificationChannelProvider item = new NotificationChannelProvider();
                item.setChannel(channel);
                item.setProviderPluginId(provider.getPluginId());
                item.setProviderPluginName(pluginDisplayName(plugin));
                item.setProviderPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
                item.setChannelIconBase64(pluginPreviewImageBase64(plugin));
                item.setCapabilityKey(provider.getKey());
                item.setCapabilityLabel(provider.getLabel());
                item.setProviderStatus("available");
                item.setSelected(selected != null
                        && Objects.equals(selected.getPluginId(), provider.getPluginId())
                        && Objects.equals(selected.getKey(), provider.getKey()));
                item.setConfirmed(isConfiguredProvider(setting, channel, provider));
                item.setReviewRequired(reviewRequired);
                NotificationDelivery lastDelivery = latestDeliveryByProvider.get(notificationProviderKey(channel, provider.getPluginId(), provider.getKey()));
                if (lastDelivery != null) {
                    item.setLastDeliveryStatus(lastDelivery.getStatus());
                    item.setLastDeliveryAt(lastDelivery.getCreatedAt());
                    item.setLastDeliveryError(lastDelivery.getErrorMessage());
                    item.setUpdatedAt(lastDelivery.getCreatedAt());
                }
                items.add(item);
            }
        }
        return items;
    }

    static boolean validNotificationProvider(PluginCapability provider, String channel) {
        return provider != null
                && Objects.equals("notification_channel", provider.getType())
                && provider.getExposure() != null
                && provider.getExposure().contains("notification")
                && Objects.equals(channel, provider.getChannel());
    }

    static List<ServiceProviderRow> serviceProviderRows(List<PluginCapability> capabilities,
                                                        ServiceSetting setting,
                                                        Map<String, Plugin> pluginsById) {
        ServiceProviderResolver resolver = new ServiceProviderResolver();
        List<PluginCapability> providers = capabilities.stream()
                .filter(item -> item.getExposure() != null && item.getExposure().contains("internal"))
                .filter(item -> !isBlank(resolver.serviceNameFor(item)))
                .collect(Collectors.toList());
        Set<String> serviceNames = new HashSet<String>();
        for (PluginCapability provider : providers) {
            serviceNames.add(resolver.serviceNameFor(provider));
        }
        List<String> sortedServiceNames = new ArrayList<String>(serviceNames);
        Collections.sort(sortedServiceNames);
        List<ServiceProviderRow> items = new ArrayList<ServiceProviderRow>();
        for (String serviceName : sortedServiceNames) {
            PluginCapability selected = resolver.resolve(serviceName, providers, setting).orElse(null);
            boolean reviewRequired = resolver.reviewRequired(serviceName, providers, setting);
            for (PluginCapability provider : resolver.providersFor(serviceName, providers)) {
                ServiceProviderRow item = new ServiceProviderRow();
                item.setServiceName(serviceName);
                item.setServiceLabel(serviceLabel(serviceName));
                item.setProviderPluginId(provider.getPluginId());
                Plugin plugin = pluginsById.get(provider.getPluginId());
                item.setProviderPluginName(pluginDisplayName(plugin));
                item.setProviderPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
                item.setCapabilityKey(provider.getKey());
                item.setCapabilityLabel(provider.getLabel());
                item.setSelected(selected != null
                        && Objects.equals(selected.getPluginId(), provider.getPluginId())
                        && Objects.equals(selected.getKey(), provider.getKey()));
                item.setConfirmed(isConfiguredServiceProvider(setting, serviceName, provider));
                item.setReviewRequired(reviewRequired);
                items.add(item);
            }
        }
        return items;
    }

    static List<Plugin> commentProviderPlugins() {
        if (PluginCoreRunMode.isNativeAgent()) {
            return Collections.emptyList();
        }
        List<Plugin> providers = new ArrayList<Plugin>();
        for (Plugin plugin : PluginSessions.allPlugins()) {
            if (isCommentProviderPlugin(plugin)) {
                providers.add(plugin);
            }
        }
        providers.sort((left, right) -> pluginDisplayName(left).compareTo(pluginDisplayName(right)));
        return providers;
    }

    static CommentProvidersResponse commentProviderResponse(List<Plugin> providers, String configured) {
        String selectedShortName = selectedCommentShortName(providers, configured);
        boolean reviewRequired = providers.size() > 1 && (isBlank(configured) || findPluginByShortName(providers, configured) == null);
        List<CommentProviderRow> items = new ArrayList<CommentProviderRow>();
        for (Plugin provider : providers) {
            CommentProviderRow item = new CommentProviderRow();
            item.setShortName(provider.getShortName());
            item.setPluginId(provider.getId());
            item.setPluginName(pluginDisplayName(provider));
            item.setPluginPreviewImageBase64(pluginPreviewImageBase64(provider));
            item.setDescription(provider.getDesc());
            item.setSelected(Objects.equals(selectedShortName, provider.getShortName()));
            item.setConfirmed(!isBlank(configured) && Objects.equals(configured, provider.getShortName()));
            item.setReviewRequired(reviewRequired);
            items.add(item);
        }
        return new CommentProvidersResponse(items, selectedShortName);
    }

    static Plugin findPluginByShortName(List<Plugin> providers, String shortName) {
        if (isBlank(shortName)) {
            return null;
        }
        for (Plugin provider : providers) {
            if (Objects.equals(shortName, provider.getShortName())) {
                return provider;
            }
        }
        return null;
    }

    private static boolean isConfiguredProvider(NotificationSetting setting, String channel, PluginCapability provider) {
        if (setting == null || setting.getDefaultProviders() == null) {
            return false;
        }
        NotificationProviderSetting configured = setting.getDefaultProviders().get(channel);
        return configured != null
                && Objects.equals(configured.getPluginId(), provider.getPluginId())
                && Objects.equals(configured.getCapabilityKey(), provider.getKey());
    }

    private static boolean isConfiguredServiceProvider(ServiceSetting setting, String serviceName, PluginCapability provider) {
        if (setting == null || setting.getDefaultProviders() == null) {
            return false;
        }
        ServiceProviderSetting configured = setting.getDefaultProviders().get(serviceName);
        return configured != null
                && Objects.equals(configured.getPluginId(), provider.getPluginId())
                && Objects.equals(configured.getCapabilityKey(), provider.getKey());
    }

    private static String serviceLabel(String serviceName) {
        if (Objects.equals("uploadService", serviceName)) {
            return PluginI18n.text("plugin.service.upload");
        }
        if (Objects.equals("uploadToPrivateService", serviceName)) {
            return PluginI18n.text("plugin.service.privateUpload");
        }
        if (Objects.equals("emailService", serviceName)) {
            return PluginI18n.text("plugin.service.email");
        }
        return serviceName;
    }

    private static String selectedCommentShortName(List<Plugin> providers, String configured) {
        if (!isBlank(configured) && findPluginByShortName(providers, configured) != null) {
            return configured;
        }
        Plugin defaultPlugin = findPluginByShortName(providers, DEFAULT_COMMENT_PLUGIN);
        if (defaultPlugin != null) {
            return defaultPlugin.getShortName();
        }
        return providers.isEmpty() ? "" : providers.get(0).getShortName();
    }

    private static boolean isCommentProviderPlugin(Plugin plugin) {
        if (plugin == null || plugin.getPaths() == null || plugin.getShortName() == null) {
            return false;
        }
        boolean hasWidget = false;
        boolean hasCommentEndpoint = false;
        for (String path : plugin.getPaths()) {
            String normalizedPath = path == null ? "" : path.trim();
            if (Objects.equals("/widget", normalizedPath) || Objects.equals("/widget/", normalizedPath)) {
                hasWidget = true;
            }
            if (normalizedPath.contains("addComment") || normalizedPath.contains("sync")) {
                hasCommentEndpoint = true;
            }
        }
        if (!hasWidget) {
            return false;
        }
        String text = (plugin.getShortName() + " " + plugin.getName() + " " + plugin.getDesc()).toLowerCase();
        return hasCommentEndpoint || text.contains("comment") || text.contains("评论") || text.contains("changyan");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
