package com.zrlog.plugincore.server.runtime;

import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginArtifactBootstrapper;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import com.zrlog.plugincore.server.runtime.plugin.lifecycle.PluginLifecycleService;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginMetadataBootstrapper;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessRuntime;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginStartupCoordinator;

import java.util.HashMap;
import java.util.Map;

public final class PluginRuntimeServices {

    private final PluginBootstrapService pluginBootstrap;
    private final PluginSessionRegistry pluginSessions;
    private final PluginConfig pluginConfig;
    private final PluginHostConnection hostConnection;

    private PluginRuntimeServices(PluginBootstrapService pluginBootstrap,
                                 PluginSessionRegistry pluginSessions,
                                 PluginConfig pluginConfig,
                                 PluginHostConnection hostConnection) {
        this.pluginBootstrap = pluginBootstrap;
        this.pluginSessions = pluginSessions;
        this.pluginConfig = pluginConfig;
        this.hostConnection = hostConnection;
    }

    public static PluginRuntimeServices unconfigured() {
        return buildServices(PluginConfig.unconfigured(), PluginHostConnection.defaults());
    }

    public static PluginRuntimeServices create(PluginConfig pluginConfig, PluginHostConnection hostConnection) {
        return buildServices(pluginConfig, hostConnection);
    }

    public PluginBootstrapService pluginBootstrap() {
        return pluginBootstrap;
    }

    public PluginSessionRegistry pluginSessions() {
        return pluginSessions;
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public PluginHostConnection hostConnection() {
        return hostConnection;
    }

    private static PluginRuntimeServices buildServices(PluginConfig pluginConfig, PluginHostConnection hostConnection) {
        Map<String, String> requiredPlugins = requiredPlugins();
        PluginSessionRegistry sessionRegistry = new PluginSessionRegistry(requiredPlugins);
        PluginProcessRuntime processRuntime = new PluginProcessRuntime(sessionRegistry, pluginConfig);
        PluginLifecycleService lifecycleService = new PluginLifecycleService(processRuntime, sessionRegistry);
        PluginMetadataBootstrapper metadataBootstrapper =
                new PluginMetadataBootstrapper(processRuntime, sessionRegistry, lifecycleService::stopPlugin);
        PluginArtifactBootstrapper artifactBootstrapper =
                new PluginArtifactBootstrapper(requiredPlugins, metadataBootstrapper, sessionRegistry, pluginConfig);
        PluginStartupCoordinator startupCoordinator =
                new PluginStartupCoordinator(processRuntime, artifactBootstrapper);
        return new PluginRuntimeServices(new PluginBootstrapService(
                requiredPlugins, startupCoordinator, metadataBootstrapper, lifecycleService),
                sessionRegistry, pluginConfig, hostConnection);
    }

    private static Map<String, String> requiredPlugins() {
        Map<String, String> requiredPlugins = new HashMap<>();
        requiredPlugins.put("comment", "comment");
        return requiredPlugins;
    }
}
