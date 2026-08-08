package com.zrlog.plugincore.server.runtime.plugin.artifact;

import com.hibegin.common.util.EnvKit;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.SecurityUtils;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.util.HttpUtils;
import com.zrlog.plugincore.server.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PluginFiles {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginFiles.class);

    private PluginFiles() {
    }

    public static String getPluginShortName(File file) {
        return file.getName()
                .replace("-Darwin", "")
                .replace("-x86_64", "")
                .replace("-Linux", "")
                .replace("-Windows", "")
                .replace("-arm64", "")
                .replace("-amd64", "")
                .replace(".bin", "")
                .replace(".exe", "")
                .replace(".jar", "");
    }

    public static List<File> pluginFilesIn(File pluginBasePath) {
        if (pluginBasePath == null || !pluginBasePath.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = pluginBasePath.listFiles(file -> file.isFile() && file.length() > 0 && isPluginFile(file));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> pluginFiles = new ArrayList<>(Arrays.asList(files));
        pluginFiles.sort(Comparator.comparing(File::getName));
        return pluginFiles;
    }

    private static boolean isPluginFile(File file) {
        if (file == null) {
            return false;
        }
        String name = file.getName();
        return name.endsWith(".jar") || name.endsWith(".bin") || name.endsWith(".exe");
    }

    public static String pluginFileMd5(File pluginFile) {
        if (pluginFile == null || !pluginFile.exists() || pluginFile.length() == 0) {
            return "";
        }
        try {
            String md5 = SecurityUtils.md5ByFile(pluginFile);
            return md5 == null ? "" : md5;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "calculate plugin md5 error", e);
            return "";
        }
    }

    public static String pluginFileRemoteMd5(File pluginFile) {
        if (pluginFile == null || StringUtils.isEmpty(pluginFile.getName())) {
            return "";
        }
        return pluginFileRemoteMd5(pluginFile.getName());
    }

    public static String pluginFileRemoteMd5(String pluginFileName) {
        if (StringUtils.isEmpty(pluginFileName)) {
            return "";
        }
        String md5Url = "https://dl.zrlog.com/plugin/" + pluginFileName + ".md5";
        try {
            String md5Text = new String(HttpUtils.sendGetRequest(md5Url, new HashMap<>()), StandardCharsets.UTF_8).trim();
            if (md5Text.isEmpty()) {
                return "";
            }
            String[] tokens = md5Text.split("\\s+");
            return tokens.length == 0 ? "" : tokens[0];
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "get plugin md5 from remote error, pluginFile=" + pluginFileName, e);
            return "";
        }
    }

    public static File getPluginFile(String pluginShortName) {
        String nativeInfo = PluginRuntimeBridge.hostConnection().getNativeInfo();
        String filename = StringUtils.isEmpty(nativeInfo) ? pluginShortName + ".jar" :
                pluginShortName + "-" + nativeInfo + (nativeInfo.contains("Window") ? ".exe" : ".bin");
        return new File(pluginConfig().getPluginBasePath() + "/" + filename);
    }

    public static File getAvailablePluginFile(String pluginShortName) {
        File configuredFile = getPluginFile(pluginShortName);
        if (configuredFile.exists() && configuredFile.length() > 0) {
            return configuredFile;
        }
        File downloadedFile = downloadPluginFile(configuredFile.getName());
        if (downloadedFile.exists() && downloadedFile.length() > 0) {
            return downloadedFile;
        }
        return configuredFile;
    }

    public static File ensurePluginFile(String pluginShortName) {
        return ensurePluginFile(pluginShortName, isAutoDownloadLostFileDisabled());
    }

    public static File ensurePluginFile(String pluginShortName, boolean autoDownloadDisabled) {
        File file = getAvailablePluginFile(pluginShortName);
        if (file.exists() && file.length() > 0) {
            return file;
        }
        if (autoDownloadDisabled) {
            LOGGER.warning(missingPluginFileMessage(pluginShortName, true));
            return file;
        }
        try {
            return downloadPlugin(file.getName());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "download plugin " + file.getName() + " error", e);
            return file;
        }
    }

    private static boolean isAutoDownloadLostFileDisabled() {
        return !PluginCoreDAO.getInstance().loadSnapshot().getSetting().isAutoDownloadMissingPluginFileEnabled();
    }

    public static String missingPluginFileMessage(String pluginShortName) {
        return missingPluginFileMessage(pluginShortName, isAutoDownloadLostFileDisabled());
    }

    public static String missingPluginFileMessage(String pluginShortName, boolean autoDownloadDisabled) {
        if (autoDownloadDisabled) {
            return "Plugin file not found: " + pluginShortName
                    + ". Automatic plugin download is disabled by runtime.autoDownloadMissingPluginFileEnabled.";
        }
        return "Plugin file not found: " + pluginShortName;
    }

    private static File downloadPluginByUrl(String url, String fileName) throws Exception {
        LOGGER.info("download plugin " + fileName + " from " + url);
        File downloadFile = downloadPluginFile(fileName);
        File parentFile = downloadFile.getParentFile();
        if (parentFile != null) {
            parentFile.mkdirs();
        }
        HttpUtils.downloadToFile(url, new HashMap<>(), downloadFile);
        if (downloadFile.length() == 0) {
            throw new RuntimeException("Download error");
        }
        return downloadFile;
    }

    static File downloadPluginFile(String fileName) {
        PluginConfig pluginConfig = pluginConfig();
        return downloadPluginFile(fileName, EnvKit.isFaaSMode(), pluginConfig.getMasterPort(),
                pluginConfig.getPluginBasePath());
    }

    static File downloadPluginFile(String fileName, boolean faaSMode, int masterPort, String pluginBasePath) {
        if (!faaSMode) {
            return new File(pluginBasePath + "/" + fileName);
        }
        return new File(PluginConfig.getFaaSRuntimeRoot(masterPort) + "/plugins/installed-plugins/" + fileName);
    }

    public static File downloadPlugin(String fileName) throws Exception {
        String downloadUrl = "https://dl.zrlog.com/plugin/" + fileName;
        return downloadPluginByUrl(downloadUrl + "?v=" + System.currentTimeMillis(), fileName);
    }

    private static PluginConfig pluginConfig() {
        return PluginRuntimeBridge.pluginConfig();
    }
}
