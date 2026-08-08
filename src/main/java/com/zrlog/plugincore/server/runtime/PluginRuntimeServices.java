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
import com.zrlog.plugincore.server.runtime.state.PluginStartCoordinator;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PluginRuntimeServices {

    private final PluginBootstrapService pluginBootstrap;
    private final PluginSessionRegistry pluginSessions;
    private final PluginConfig pluginConfig;
    private final PluginHostConnection hostConnection;
    private final PluginStartCoordinator pluginStarts;
    private final PluginProcessRuntime processRuntime;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private final AtomicBoolean shutdownFinished = new AtomicBoolean(false);

    private PluginRuntimeServices(PluginBootstrapService pluginBootstrap,
                                 PluginSessionRegistry pluginSessions,
                                 PluginConfig pluginConfig,
                                 PluginHostConnection hostConnection,
                                 PluginStartCoordinator pluginStarts,
                                 PluginProcessRuntime processRuntime) {
        this.pluginBootstrap = pluginBootstrap;
        this.pluginSessions = pluginSessions;
        this.pluginConfig = pluginConfig;
        this.hostConnection = hostConnection;
        this.pluginStarts = pluginStarts;
        this.processRuntime = processRuntime;
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

    public PluginStartCoordinator pluginStarts() {
        return pluginStarts;
    }

    public void beginShutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        pluginStarts.shutdown();
        try {
            pluginBootstrap.shutdown();
        } finally {
            processRuntime.beginShutdown();
        }
    }

    public void shutdown() {
        beginShutdown();
        if (!shutdownFinished.compareAndSet(false, true)) {
            return;
        }
        try {
            pluginSessions.shutdown();
        } finally {
            processRuntime.shutdown();
        }
    }

    public boolean isShutdown() {
        return shutdownStarted.get();
    }

    private static PluginRuntimeServices buildServices(PluginConfig pluginConfig, PluginHostConnection hostConnection) {
        Map<String, String> requiredPlugins = requiredPlugins();
        PluginStartCoordinator pluginStarts = new PluginStartCoordinator();
        PluginSessionRegistry sessionRegistry = new PluginSessionRegistry(requiredPlugins, pluginStarts);
        PluginProcessRuntime processRuntime = new PluginProcessRuntime(sessionRegistry, pluginConfig, pluginStarts);
        PluginLifecycleService lifecycleService = new PluginLifecycleService(processRuntime, sessionRegistry);
        PluginMetadataBootstrapper metadataBootstrapper =
                new PluginMetadataBootstrapper(processRuntime, sessionRegistry, lifecycleService::stopPlugin, pluginStarts);
        PluginArtifactBootstrapper artifactBootstrapper =
                new PluginArtifactBootstrapper(requiredPlugins, metadataBootstrapper, sessionRegistry, pluginConfig);
        PluginStartupCoordinator startupCoordinator =
                new PluginStartupCoordinator(processRuntime, artifactBootstrapper);
        return new PluginRuntimeServices(new PluginBootstrapService(
                requiredPlugins, startupCoordinator, metadataBootstrapper, lifecycleService),
                sessionRegistry, pluginConfig, hostConnection, pluginStarts, processRuntime);
    }

    private static Map<String, String> requiredPlugins() {
        Map<String, String> requiredPlugins = new HashMap<>();
        requiredPlugins.put("comment", "comment");
        return requiredPlugins;
    }
}
