package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessRuntime;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.state.PluginStartCoordinator;
import com.zrlog.plugincore.server.util.StringUtils;

import java.io.File;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

public class PluginMetadataBootstrapper {

    private static final long PLUGIN_METADATA_WAIT_TIMEOUT_MS = 10000L;
    private static final long PLUGIN_METADATA_WAIT_INTERVAL_MS = 100L;
    private static final long PLUGIN_START_CAPACITY_WAIT_TIMEOUT_MS = 5000L;
    private static final long PLUGIN_OPERATION_WAIT_TIMEOUT_MS = 30000L;

    private final PluginProcessRuntime processRuntime;
    private final PluginSessionRegistry sessionRegistry;
    private final Predicate<String> pluginStopper;
    private final PluginStartCoordinator startCoordinator;

    public PluginMetadataBootstrapper(PluginProcessRuntime processRuntime, Predicate<String> pluginStopper) {
        this(processRuntime, processRuntime == null ? new PluginSessionRegistry() : processRuntime.sessionRegistry(),
                pluginStopper, new PluginStartCoordinator());
    }

    public PluginMetadataBootstrapper(PluginProcessRuntime processRuntime, PluginSessionRegistry sessionRegistry,
                                      Predicate<String> pluginStopper) {
        this(processRuntime, sessionRegistry, pluginStopper, new PluginStartCoordinator());
    }

    public PluginMetadataBootstrapper(PluginProcessRuntime processRuntime, PluginSessionRegistry sessionRegistry,
                                      Predicate<String> pluginStopper, PluginStartCoordinator startCoordinator) {
        this.processRuntime = processRuntime;
        this.sessionRegistry = sessionRegistry;
        this.pluginStopper = pluginStopper;
        this.startCoordinator = startCoordinator;
    }

    public boolean startPluginFileForMetadata(File pluginFile) {
        if (pluginFile == null || !pluginFile.exists() || pluginFile.length() == 0) {
            return false;
        }
        return startPluginFileForMetadata(pluginFile, resolvePluginId(pluginFile));
    }

    public boolean startPluginFileForMetadata(File pluginFile, String pluginId) {
        if (pluginFile == null || !pluginFile.exists() || pluginFile.length() == 0 || StringUtils.isEmpty(pluginId)) {
            return false;
        }
        MetadataStartAttempt attempt = new MetadataStartAttempt();
        try {
            return startCoordinator.start(metadataCoordinationKey(pluginFile, pluginId), maxConcurrentStarts(),
                    PLUGIN_START_CAPACITY_WAIT_TIMEOUT_MS, PLUGIN_OPERATION_WAIT_TIMEOUT_MS, startFailureBackoffMs(),
                    () -> startPluginFileWithinCapacity(pluginFile, pluginId, attempt));
        } finally {
            cleanupMetadataProcess(pluginFile, pluginId, attempt);
        }
    }

    private boolean startPluginFileWithinCapacity(File pluginFile, String pluginId, MetadataStartAttempt attempt) {
        String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
        boolean wasRunning = sessionRegistry.isRunningByPluginShortName(pluginShortName);
        boolean fileChanged = wasRunning && hasPluginFileChanged(pluginFile, pluginId);
        if (fileChanged && !pluginStopper.test(pluginShortName)) {
            return false;
        }
        attempt.startedProcess = processRuntime.loadPlugin(pluginFile, pluginId);
        if (fileChanged && attempt.startedProcess == null) {
            return false;
        }
        attempt.registered = waitForPluginMetadata(pluginShortName, pluginId);
        if (attempt.registered) {
            PluginCoreDAO.getInstance().updatePluginFileMd5(pluginShortName, pluginId, PluginFiles.pluginFileMd5(pluginFile));
        }
        return attempt.registered;
    }

    private void cleanupMetadataProcess(File pluginFile, String pluginId, MetadataStartAttempt attempt) {
        if (attempt.startedProcess == null) {
            return;
        }
        String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
        Process ownedProcess = attempt.startedProcess;
        if (!attempt.registered) {
            processRuntime.destroyByPluginIdIfCurrent(pluginId, pluginShortName, ownedProcess);
            return;
        }
        if (onDemandEnabled()) {
            startCoordinator.runIfUnclaimed(pluginId,
                    () -> processRuntime.destroyByPluginIdIfCurrent(pluginId, pluginShortName, ownedProcess));
        }
    }

    private String metadataCoordinationKey(File pluginFile, String pluginId) {
        String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginShortName);
        if (pluginVO != null && pluginVO.getPlugin() != null
                && Objects.equals(pluginId, pluginVO.getPlugin().getId())) {
            return pluginId;
        }
        if (Objects.equals(pluginId, pluginShortName)) {
            return pluginId;
        }
        return "metadata:" + pluginShortName;
    }

    public boolean shouldStartPluginFileForMetadata(File pluginFile, String pluginId) {
        return shouldStartPluginFileForMetadata(pluginFile, pluginId, PluginCoreDAO.getInstance().loadSnapshot());
    }

    public boolean shouldStartPluginFileForMetadata(File pluginFile, String pluginId, PluginCore pluginCore) {
        if (pluginFile == null || !pluginFile.exists() || pluginFile.length() == 0 || StringUtils.isEmpty(pluginId)) {
            return false;
        }
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOById(pluginCore, pluginId);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginCore, PluginFiles.getPluginShortName(pluginFile));
        }
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return true;
        }
        if (StringUtils.isEmpty(pluginVO.getFileMd5())) {
            return true;
        }
        return !Objects.equals(pluginVO.getFileMd5(), PluginFiles.pluginFileMd5(pluginFile));
    }

    public File downloadAndStartPlugin(String fileName) throws Exception {
        File file = PluginFiles.downloadPlugin(fileName);
        if (!startPluginFileForMetadata(file)) {
            throw new RuntimeException("Download succeeded, but plugin metadata was not registered");
        }
        return file;
    }

    private String resolvePluginId(File pluginFile) {
        String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginCore, pluginShortName);
        if (pluginVO != null && pluginVO.getPlugin() != null && !StringUtils.isEmpty(pluginVO.getPlugin().getId())) {
            return pluginVO.getPlugin().getId();
        }
        return UUID.randomUUID().toString();
    }

    private boolean waitForPluginMetadata(String pluginShortName, String pluginId) {
        long deadline = System.currentTimeMillis() + PLUGIN_METADATA_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            IOSession session = sessionRegistry.getLocalSessionByPluginId(pluginId);
            if (session == null) {
                session = sessionRegistry.getLocalSessionByPluginShortName(pluginShortName);
            }
            if (session != null && sessionRegistry.isReady(session) && session.getPlugin() != null
                    && Objects.equals(pluginShortName, session.getPlugin().getShortName())) {
                return true;
            }
            try {
                Thread.sleep(PLUGIN_METADATA_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public PluginSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    private boolean hasPluginFileChanged(File pluginFile, String pluginId) {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOById(pluginCore, pluginId);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginCore, PluginFiles.getPluginShortName(pluginFile));
        }
        if (pluginVO == null || StringUtils.isEmpty(pluginVO.getFileMd5())) {
            return false;
        }
        return !Objects.equals(pluginVO.getFileMd5(), PluginFiles.pluginFileMd5(pluginFile));
    }

    private int maxConcurrentStarts() {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        return pluginCore.getSetting().getRuntime().getMaxConcurrentStarts().intValue();
    }

    private long startFailureBackoffMs() {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        return pluginCore.getSetting().getRuntime().getStartFailureBackoffSeconds() * 1000L;
    }

    private boolean onDemandEnabled() {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        return pluginCore.getSetting().getRuntime().getOnDemandEnabled();
    }

    private static final class MetadataStartAttempt {

        private Process startedProcess;
        private boolean registered;
    }
}
