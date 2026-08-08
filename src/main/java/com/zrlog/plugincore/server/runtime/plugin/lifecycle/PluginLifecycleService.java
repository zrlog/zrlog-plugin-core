package com.zrlog.plugincore.server.runtime.plugin.lifecycle;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessRuntime;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.util.StringUtils;

import java.io.File;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class PluginLifecycleService {

    private final PluginProcessRuntime processRuntime;
    private final PluginSessionRegistry sessionRegistry;

    public PluginLifecycleService(PluginProcessRuntime processRuntime) {
        this(processRuntime, processRuntime.sessionRegistry());
    }

    public PluginLifecycleService(PluginProcessRuntime processRuntime, PluginSessionRegistry sessionRegistry) {
        this.processRuntime = processRuntime;
        this.sessionRegistry = sessionRegistry;
    }

    public void registerPlugin(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session == null || session.getPlugin() == null) {
                return;
            }
            closeDuplicatePluginInstances(session);
            attachProcessInfo(session);
            PluginVO pluginVO = new PluginVO();
            pluginVO.setPlugin(session.getPlugin());
            pluginVO.setFileMd5(PluginFiles.pluginFileMd5(PluginFiles.getAvailablePluginFile(session.getPlugin().getShortName())));
            PluginCoreDAO.getInstance().update(pluginCore ->
                    pluginCore.getPluginInfoMap().put(session.getPlugin().getShortName(), pluginVO));
            sessionRegistry.addLocalSession(session);
        }
    }

    public void unregisterPluginSession(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session == null || session.getPlugin() == null) {
                return;
            }
            String pluginId = session.getPlugin().getId();
            String runtimeInstanceId = sessionRegistry.runtimeInstanceId(session);
            sessionRegistry.removeLocalSession(session);
            if (!sessionRegistry.hasOpenSessionForRuntimeInstance(pluginId, runtimeInstanceId)) {
                PluginRuntimeStates.markStoppedIfCurrent(session);
            }
        }
    }

    public void markPluginReady(IOSession session) {
        if (session == null || session.getPlugin() == null) {
            return;
        }
        processRuntime.markReadyIfCurrent(session.getPlugin().getId(),
                sessionRegistry.runtimeInstanceId(session), sessionRegistry.processId(session));
    }

    public boolean stopPlugin(String pluginShortName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(null, pluginShortName, pluginShortName)) {
            return processRuntime.destroy(pluginShortName);
        }
    }

    public boolean deletePlugin(String pluginShortName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(null, pluginShortName, pluginShortName)) {
            PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginShortName);
            if (!processRuntime.destroy(pluginShortName)) {
                return false;
            }
            if (pluginVO != null && pluginVO.getPlugin() != null) {
                try (PluginLogContext.Scope pluginScope = PluginLogContext.open(pluginVO.getPlugin())) {
                    String pluginId = pluginVO.getPlugin().getId();
                    File pluginFile = PluginFiles.getPluginFile(pluginShortName);
                    if (pluginFile.exists()) {
                        pluginFile.delete();
                    }
                    PluginRuntimeStates.deletePluginRuntimeReferences(pluginId);
                }
            }
            PluginCoreDAO.getInstance().removePluginByShortName(pluginShortName);
            return true;
        }
    }

    private void closeDuplicatePluginInstances(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            Plugin plugin = session.getPlugin();
            if (plugin == null || StringUtils.isEmpty(plugin.getShortName())) {
                return;
            }
            String shortName = plugin.getShortName();
            String currentPluginId = plugin.getId();
            Set<String> stalePluginIds = new HashSet<>();
            PluginVO existingPluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(shortName);
            if (existingPluginVO != null && existingPluginVO.getPlugin() != null
                    && !Objects.equals(currentPluginId, existingPluginVO.getPlugin().getId())) {
                stalePluginIds.add(existingPluginVO.getPlugin().getId());
            }
            for (IOSession oldSession : sessionRegistry.getAllLocalSessions()) {
                if (oldSession.getPlugin() == null || !Objects.equals(shortName, oldSession.getPlugin().getShortName())
                        || Objects.equals(currentPluginId, oldSession.getPlugin().getId())) {
                    continue;
                }
                stalePluginIds.add(oldSession.getPlugin().getId());
            }
            for (String stalePluginId : stalePluginIds) {
                if (!processRuntime.destroyByPluginId(stalePluginId, shortName)) {
                    throw new IllegalStateException("Unable to stop the previous plugin process for " + shortName);
                }
            }
        }
    }

    private void attachProcessInfo(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session == null || session.getPlugin() == null) {
                return;
            }
            String pluginId = session.getPlugin().getId();
            Long processId = processRuntime.processIdByPluginId(pluginId);
            if (processId == null) {
                return;
            }
            session.getSystemAttr().put(PluginSessionRegistry.PROCESS_ID_ATTR, processId);
            processRuntime.runtimeInstanceIdByPluginId(pluginId)
                    .ifPresent(runtimeInstanceId -> session.getSystemAttr().put(PluginSessionRegistry.SESSION_ID_ATTR, runtimeInstanceId));
        }
    }
}
