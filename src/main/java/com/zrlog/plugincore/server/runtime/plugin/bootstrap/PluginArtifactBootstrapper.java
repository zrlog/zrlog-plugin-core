package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginArtifactBootstrapper {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginArtifactBootstrapper.class);
    private static final int MAX_PLUGIN_START_THREADS = 10;

    private final Map<String, String> requiredPlugins;
    private final PluginMetadataBootstrapper metadataBootstrapper;
    private final PluginSessionRegistry sessionRegistry;
    private final PluginConfig pluginConfig;

    public PluginArtifactBootstrapper(Map<String, String> requiredPlugins, PluginMetadataBootstrapper metadataBootstrapper) {
        this(requiredPlugins, metadataBootstrapper, metadataBootstrapper.sessionRegistry(), PluginConfig.unconfigured());
    }

    public PluginArtifactBootstrapper(Map<String, String> requiredPlugins, PluginMetadataBootstrapper metadataBootstrapper,
                                      PluginSessionRegistry sessionRegistry) {
        this(requiredPlugins, metadataBootstrapper, sessionRegistry, PluginConfig.unconfigured());
    }

    public PluginArtifactBootstrapper(Map<String, String> requiredPlugins, PluginMetadataBootstrapper metadataBootstrapper,
                                      PluginSessionRegistry sessionRegistry, PluginConfig pluginConfig) {
        this.requiredPlugins = requiredPlugins;
        this.metadataBootstrapper = metadataBootstrapper;
        this.sessionRegistry = sessionRegistry;
        this.pluginConfig = pluginConfig;
    }

    public void reconcilePluginArtifacts(PluginCore currentPluginCore) {
        if (isOnDemandEnabled(currentPluginCore)) {
            LOGGER.info("skip missing plugin download during bootstrap because on-demand loading is enabled");
        }
        reconcileRequiredPluginArtifacts(currentPluginCore);
        reconcileOptionalPluginArtifacts(currentPluginCore);
    }

    void reconcileRequiredPluginArtifacts(PluginCore currentPluginCore) {
        bootstrapInstalledPluginArtifacts(currentPluginCore, true);
        downloadMissingPluginFiles(currentPluginCore, getRequiredRunnablePlugins(currentPluginCore));
    }

    void reconcileOptionalPluginArtifacts(PluginCore currentPluginCore) {
        bootstrapInstalledPluginArtifacts(currentPluginCore, false);
        downloadMissingPluginFiles(currentPluginCore, getOptionalRunnablePlugins(currentPluginCore));
    }

    public List<String> getAllRunnablePluginIds(PluginCore currentPluginCore) {
        return new ArrayList<>(getAllRunnablePlugin(currentPluginCore).values());
    }

    public Map<String, String> getAllRunnablePlugin(PluginCore currentPluginCore) {
        Map<String, String> runnablePlugins = new LinkedHashMap<>();
        runnablePlugins.putAll(getRequiredRunnablePlugins(currentPluginCore));
        runnablePlugins.putAll(getOptionalRunnablePlugins(currentPluginCore));
        return runnablePlugins;
    }

    Map<String, String> getRequiredRunnablePlugins(PluginCore currentPluginCore) {
        Map<String, String> runnablePlugins = new LinkedHashMap<>();
        requiredPlugins.forEach((pluginShortName, fallbackPluginId) -> {
            if (!sessionRegistry.isRunningByPluginShortName(pluginShortName)) {
                runnablePlugins.put(pluginShortName,
                        pluginIdForInstalledArtifact(currentPluginCore, pluginShortName, fallbackPluginId));
            }
        });
        return runnablePlugins;
    }

    Map<String, String> getOptionalRunnablePlugins(PluginCore currentPluginCore) {
        Map<String, String> runnablePlugins = new LinkedHashMap<>();
        if (currentPluginCore != null && currentPluginCore.getPluginInfoMap() != null) {
            currentPluginCore.getPluginInfoMap().values().forEach(pluginVO -> {
                if (pluginVO.getPlugin() == null
                        || requiredPlugins.containsKey(pluginVO.getPlugin().getShortName())
                        || sessionRegistry.isRunningByPluginShortName(pluginVO.getPlugin().getShortName())) {
                    return;
                }
                runnablePlugins.put(pluginVO.getPlugin().getShortName(), pluginVO.getPlugin().getId());
            });
        }
        getInstalledPluginArtifactIds(currentPluginCore).forEach((pluginShortName, pluginId) -> {
            if (requiredPlugins.containsKey(pluginShortName) || runnablePlugins.containsKey(pluginShortName)) {
                return;
            }
            runnablePlugins.put(pluginShortName, pluginId);
        });
        return runnablePlugins;
    }

    public int pluginStartThreads(int pluginCount) {
        if (pluginCount <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_PLUGIN_START_THREADS, pluginCount));
    }

    private Map<String, String> getInstalledPluginArtifactIds(PluginCore currentPluginCore) {
        Map<String, String> runnablePlugins = new LinkedHashMap<>();
        for (File file : PluginFiles.pluginFilesIn(new File(pluginConfig().getPluginBasePath()))) {
            String pluginShortName = PluginFiles.getPluginShortName(file);
            if (StringUtils.isEmpty(pluginShortName)) {
                continue;
            }
            if (sessionRegistry.isRunningByPluginShortName(pluginShortName)) {
                continue;
            }
            runnablePlugins.put(pluginShortName, pluginIdForInstalledArtifact(currentPluginCore, pluginShortName));
        }
        return runnablePlugins;
    }

    private void downloadMissingPluginFiles(PluginCore currentPluginCore, Map<String, String> runnablePlugins) {
        if (!shouldDownloadMissingPluginFilesDuringBootstrap(currentPluginCore)) {
            return;
        }
        BootstrapBatchRunner.run(runnablePlugins.entrySet(), runnablePlugins.size(),
                pluginStartThreads(runnablePlugins.size(), currentPluginCore), pluginEntry -> {
                String pluginShortName = pluginEntry.getKey();
                String pluginId = pluginEntry.getValue();
                File file = PluginFiles.getAvailablePluginFile(pluginShortName);
                if (file.exists() && file.length() > 0) {
                    return;
                }
                try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginShortName, pluginShortName)) {
                    try {
                        File downloadedFile = PluginFiles.downloadPlugin(file.getName());
                        if (!metadataBootstrapper.startPluginFileForMetadata(downloadedFile, pluginId)) {
                            LOGGER.warning(PluginLogContext.prefix("downloaded plugin " + pluginShortName + " but metadata was not registered"));
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, PluginLogContext.prefix("download error"), e);
                    }
                }
        });
    }

    static boolean shouldDownloadMissingPluginFilesDuringBootstrap(PluginCore currentPluginCore) {
        return isAutoDownloadMissingPluginFileEnabled(currentPluginCore) && !isOnDemandEnabled(currentPluginCore);
    }

    private static boolean isAutoDownloadMissingPluginFileEnabled(PluginCore currentPluginCore) {
        if (currentPluginCore == null || currentPluginCore.getSetting() == null) {
            return true;
        }
        return currentPluginCore.getSetting().isAutoDownloadMissingPluginFileEnabled();
    }

    private static boolean isOnDemandEnabled(PluginCore currentPluginCore) {
        if (currentPluginCore == null || currentPluginCore.getSetting() == null
                || currentPluginCore.getSetting().getRuntime() == null) {
            return true;
        }
        return currentPluginCore.getSetting().getRuntime().getOnDemandEnabled();
    }

    private void bootstrapInstalledPluginArtifacts(PluginCore currentPluginCore, boolean requiredPhase) {
        List<File> pluginFiles = PluginFiles.pluginFilesIn(new File(pluginConfig().getPluginBasePath()));
        BootstrapBatchRunner.run(pluginFiles, pluginFiles.size(),
                pluginStartThreads(pluginFiles.size(), currentPluginCore), file -> {
            String pluginShortName = PluginFiles.getPluginShortName(file);
            if (StringUtils.isEmpty(pluginShortName)
                    || requiredPlugins.containsKey(pluginShortName) != requiredPhase) {
                return;
            }
            String pluginId = pluginIdForInstalledArtifact(currentPluginCore, pluginShortName);
            if (!metadataBootstrapper.shouldStartPluginFileForMetadata(file, pluginId, currentPluginCore)) {
                return;
            }
            try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginShortName, pluginShortName)) {
                if (!metadataBootstrapper.startPluginFileForMetadata(file, pluginId)) {
                    LOGGER.warning(PluginLogContext.prefix("plugin " + pluginShortName + " file exists but metadata was not registered"));
                }
            }
        });
    }

    int pluginStartThreads(int pluginCount, PluginCore pluginCore) {
        int configuredMax = pluginCore == null || pluginCore.getSetting() == null
                ? 1
                : pluginCore.getSetting().getRuntime().getMaxConcurrentStarts().intValue();
        return Math.min(pluginStartThreads(pluginCount), Math.max(1, configuredMax));
    }

    boolean startPluginAndAwait(File pluginFile, String pluginId) {
        return metadataBootstrapper.startPluginFileForMetadata(pluginFile, pluginId);
    }

    File availablePluginFile(String pluginShortName) {
        return PluginFiles.getAvailablePluginFile(pluginShortName);
    }

    static String pluginIdForInstalledArtifact(PluginCore pluginCore, String pluginShortName) {
        return pluginIdForInstalledArtifact(pluginCore, pluginShortName, null);
    }

    private static String pluginIdForInstalledArtifact(PluginCore pluginCore, String pluginShortName, String fallbackPluginId) {
        PluginVO pluginVO = pluginVOForInstalledArtifact(pluginCore, pluginShortName);
        if (pluginVO != null && pluginVO.getPlugin() != null
                && !StringUtils.isEmpty(pluginVO.getPlugin().getId())) {
            return pluginVO.getPlugin().getId();
        }
        if (!StringUtils.isEmpty(fallbackPluginId)) {
            return fallbackPluginId;
        }
        return UUID.randomUUID().toString();
    }

    private static PluginVO pluginVOForInstalledArtifact(PluginCore pluginCore, String pluginShortName) {
        if (pluginCore == null || pluginCore.getPluginInfoMap() == null || StringUtils.isEmpty(pluginShortName)) {
            return null;
        }
        PluginVO pluginVO = pluginCore.getPluginInfoMap().get(pluginShortName);
        if (pluginVO != null) {
            return pluginVO;
        }
        for (PluginVO item : pluginCore.getPluginInfoMap().values()) {
            if (item != null && item.getPlugin() != null
                    && Objects.equals(pluginShortName, item.getPlugin().getShortName())) {
                return item;
            }
        }
        return null;
    }

    private PluginConfig pluginConfig() {
        return pluginConfig;
    }
}
