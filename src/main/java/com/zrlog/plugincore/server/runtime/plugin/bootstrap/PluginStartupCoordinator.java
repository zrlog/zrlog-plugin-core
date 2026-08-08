package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessRuntime;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginStartupCoordinator {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginStartupCoordinator.class);

    private final PluginProcessRuntime processRuntime;
    private final PluginArtifactBootstrapper artifactBootstrapper;

    public PluginStartupCoordinator(PluginProcessRuntime processRuntime, PluginArtifactBootstrapper artifactBootstrapper) {
        this.processRuntime = processRuntime;
        this.artifactBootstrapper = artifactBootstrapper;
    }

    PluginProcessRuntime processRuntime() {
        return processRuntime;
    }

    public boolean destroy(String pluginShortName) {
        return processRuntime.destroy(pluginShortName);
    }

    public boolean destroyByPluginId(String pluginId, String pluginShortName) {
        return processRuntime.destroyByPluginId(pluginId, pluginShortName);
    }

    public void loadPlugin(final File pluginFile, String pluginId) {
        processRuntime.loadPlugin(pluginFile, pluginId);
    }

    public boolean hasManagedProcessSlot(String pluginId, String pluginShortName) {
        return processRuntime.hasManagedProcessSlot(pluginId, pluginShortName);
    }

    public boolean isManagedProcessStartViable(String pluginId, String pluginShortName) {
        return processRuntime.isManagedProcessStartViable(pluginId, pluginShortName);
    }

    public void startRunnablePlugins() {
        startRunnablePlugins(currentPluginCore());
    }

    void startRunnablePlugins(PluginCore currentPluginCore) {
        artifactBootstrapper.reconcileRequiredPluginArtifacts(currentPluginCore);
        startRunnablePluginBatch(artifactBootstrapper.getRequiredRunnablePlugins(currentPluginCore), currentPluginCore);
        artifactBootstrapper.reconcileOptionalPluginArtifacts(currentPluginCore);
        startRunnablePluginBatch(artifactBootstrapper.getOptionalRunnablePlugins(currentPluginCore), currentPluginCore);
    }

    private void startRunnablePluginBatch(Map<String, String> runnablePlugins, PluginCore currentPluginCore) {
        BootstrapBatchRunner.run(runnablePlugins.entrySet(), runnablePlugins.size(),
                artifactBootstrapper.pluginStartThreads(runnablePlugins.size(), currentPluginCore), pluginVO -> {
            File file = artifactBootstrapper.availablePluginFile(pluginVO.getKey());
            if (!file.getName().endsWith(".jar") && !file.getName().endsWith(".bin") && !file.getName().endsWith(".exe")) {
                return;
            }
            if (!file.exists() || file.length() == 0) {
                return;
            }
            String pluginId = pluginVO.getValue();
            try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginVO.getKey(), pluginVO.getKey())) {
                try {
                    if (!artifactBootstrapper.startPluginAndAwait(file, pluginId)) {
                        LOGGER.warning(PluginLogContext.prefix("plugin " + file.getName() + " did not become ready"));
                    }
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE, PluginLogContext.prefix("start plugin " + file.getName() + " error"), e);
                }
            }
        });
    }

    public void prepare() {
        artifactBootstrapper.reconcilePluginArtifacts(currentPluginCore());
    }

    public List<String> getAllRunnablePluginIds() {
        return artifactBootstrapper.getAllRunnablePluginIds(currentPluginCore());
    }

    private PluginCore currentPluginCore() {
        return PluginCoreDAO.getInstance().loadSnapshot();
    }
}
