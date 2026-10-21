package com.zrlog.plugincore.server;

import com.hibegin.common.util.ParseArgsUtil;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.common.ConfigKit;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginDataSourceInitializer;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import com.zrlog.plugincore.server.util.ListenWebServerThread;

import java.io.IOException;
import java.util.Objects;

class ApplicationStartup {

    private final ApplicationServers servers;

    ApplicationStartup() {
        this(new ApplicationServers());
    }

    ApplicationStartup(ApplicationServers servers) {
        this.servers = servers;
    }

    void start(String[] args) throws IOException {
        ApplicationEnvironment.configureFaaSRuntimeRoot(args);
        ApplicationEnvironment.initLogging();
        if (shouldShowTips(args)) {
            return;
        }
        ApplicationEnvironment.logArgsIfNeeded(args);
        ApplicationEnvironment.configureStandaloneRunMode(args);

        ApplicationStartupOptions options = ApplicationStartupOptions.parse(args);
        ApplicationEnvironment.configureBlogRunModeIfNeeded(options.hasExternalDbProperties());
        PluginConfig pluginConfig = new PluginConfig(RunConstants.runType, options.getDbProperties(), options.getMasterPort(),
                options.getPluginPath(), options.getBlogRunTime());
        PluginHostConnection hostConnection = new PluginHostConnection(options.getBlogApiHomeUrl(),
                options.getBlogPluginToken(), options.getNativeInfo(), options.getContextPath());
        PluginRuntimeServices services = PluginRuntimeServices.create(pluginConfig, hostConnection);
        new PluginDataSourceInitializer().initialize(pluginConfig.getDbPropertiesFile());
        // The host treats the watcher connection as a live Runtime. Do not
        // expose it for a child that failed to bind either application server.
        servers.start(options.getHttpPort(), services, () -> startBlogListener(options.getListenBlogPort()));
    }

    private boolean shouldShowTips(String[] args) {
        return Objects.nonNull(args) && ParseArgsUtil.justTips(args, "plugin-core", (String) ConfigKit.get("version", ""));
    }

    private void startBlogListener(int port) {
        if (port > 0) {
            new ListenWebServerThread(port).start();
        }
    }
}
