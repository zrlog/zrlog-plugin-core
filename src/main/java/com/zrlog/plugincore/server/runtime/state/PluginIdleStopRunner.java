package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginIdleStopRunner {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginIdleStopRunner.class);
    private static final long IDLE_STOP_OPERATION_WAIT_MS = 5000L;
    private static final int MAX_CAPACITY_RECLAIM_CANDIDATES = 32;

    private final PluginIdleStopPolicy idleStopPolicy = new PluginIdleStopPolicy();
    private final PluginBootstrapService pluginBootstrapService;
    private final PluginStartCoordinator startCoordinator;

    public PluginIdleStopRunner() {
        this(PluginRuntimeBridge.pluginBootstrap(), PluginRuntimeBridge.pluginStarts());
    }

    public PluginIdleStopRunner(PluginBootstrapService pluginBootstrapService) {
        this(pluginBootstrapService, PluginRuntimeBridge.pluginStarts());
    }

    PluginIdleStopRunner(PluginBootstrapService pluginBootstrapService,
                         PluginStartCoordinator startCoordinator) {
        this.pluginBootstrapService = pluginBootstrapService;
        this.startCoordinator = startCoordinator;
    }

    void stopIdlePlugins(long nowMs) {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        stopIdlePlugins(nowMs, pluginCore.getSetting().getRuntime(), pluginCore);
    }

    public void stopIdlePlugins(long nowMs, PluginRuntimeSetting runtimeSetting) {
        stopIdlePlugins(nowMs, runtimeSetting, PluginCoreDAO.getInstance().loadSnapshot());
    }

    public void stopIdlePlugins(long nowMs, PluginRuntimeSetting runtimeSetting, PluginCore pluginCore) {
        stopIdlePlugins(nowMs, runtimeSetting, pluginCore,
                new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()));
    }

    void stopIdlePlugins(long nowMs,
                         PluginRuntimeSetting runtimeSetting,
                         PluginCore pluginCore,
                         PluginRuntimeStateStore stateStore) {
        if (!runtimeSetting.getOnDemandEnabled() || !runtimeSetting.getIdleStopEnabled()) {
            return;
        }
        long idleTimeoutMs = Math.max(1L, runtimeSetting.getIdleTimeoutSeconds()) * 1000L;
        PluginRuntimeStateService stateService = new PluginRuntimeStateService(stateStore,
                new DefaultPluginRuntimeStarter(() -> pluginCore, pluginBootstrapService));
        for (PluginRuntimeState state : stateStore.list()) {
            if (!idleStopPolicy.shouldStop(state, nowMs, idleTimeoutMs)) {
                continue;
            }
            String pluginId = state.getPluginId();
            if (pluginId == null || pluginId.trim().isEmpty()) {
                continue;
            }
            startCoordinator.withPluginOperation(pluginId, IDLE_STOP_OPERATION_WAIT_MS,
                    () -> startCoordinator.runIfUnclaimed(pluginId,
                            () -> stopIfStillIdle(pluginId, nowMs, idleTimeoutMs, pluginCore, stateStore,
                                    stateService, false)));
        }
    }

    public boolean reclaimOneIdlePluginForCapacity(long nowMs,
                                                   PluginRuntimeSetting runtimeSetting,
                                                   PluginCore pluginCore,
                                                   String excludedPluginId) {
        return reclaimOneIdlePluginForCapacity(nowMs, runtimeSetting, pluginCore, excludedPluginId,
                new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()));
    }

    boolean reclaimOneIdlePluginForCapacity(long nowMs,
                                             PluginRuntimeSetting runtimeSetting,
                                             PluginCore pluginCore,
                                             String excludedPluginId,
                                             PluginRuntimeStateStore stateStore) {
        if (!runtimeSetting.getOnDemandEnabled() || !runtimeSetting.getIdleStopEnabled()) {
            return false;
        }
        long idleTimeoutMs = Math.max(1L, runtimeSetting.getIdleTimeoutSeconds()) * 1000L;
        PluginRuntimeStateService stateService = new PluginRuntimeStateService(stateStore,
                new DefaultPluginRuntimeStarter(() -> pluginCore, pluginBootstrapService));
        for (PluginRuntimeState candidate : idleCandidates(stateStore, pluginCore, excludedPluginId,
                nowMs, idleTimeoutMs)) {
            String pluginId = candidate.getPluginId();
            AtomicBoolean stopped = new AtomicBoolean();
            boolean operationCompleted = startCoordinator.withPluginOperation(pluginId, IDLE_STOP_OPERATION_WAIT_MS,
                    () -> startCoordinator.runIfUnclaimed(pluginId,
                            () -> stopped.set(stopIfStillIdle(pluginId, nowMs, idleTimeoutMs, pluginCore, stateStore,
                                    stateService, true))));
            if (operationCompleted && stopped.get()) {
                return true;
            }
        }
        return false;
    }

    private List<PluginRuntimeState> idleCandidates(PluginRuntimeStateStore stateStore,
                                                    PluginCore pluginCore,
                                                    String excludedPluginId,
                                                    long nowMs,
                                                    long idleTimeoutMs) {
        List<PluginRuntimeState> candidates = new ArrayList<>();
        for (PluginRuntimeState state : stateStore.list()) {
            if (state == null || Objects.equals(excludedPluginId, state.getPluginId())
                    || !idleStopPolicy.shouldStop(state, nowMs, idleTimeoutMs)
                    || !hasManagedProcess(state.getPluginId(), pluginCore)) {
                continue;
            }
            candidates.add(state);
        }
        candidates.sort(Comparator.comparingLong(PluginRuntimeState::getLastActiveAt));
        if (candidates.size() <= MAX_CAPACITY_RECLAIM_CANDIDATES) {
            return candidates;
        }
        return new ArrayList<>(candidates.subList(0, MAX_CAPACITY_RECLAIM_CANDIDATES));
    }

    private boolean stopIfStillIdle(String pluginId,
                                    long nowMs,
                                    long idleTimeoutMs,
                                    PluginCore pluginCore,
                                    PluginRuntimeStateStore stateStore,
                                    PluginRuntimeStateService stateService,
                                    boolean requireManagedProcess) {
        PluginRuntimeState currentState = stateStore.find(pluginId).orElse(null);
        if (!idleStopPolicy.shouldStop(currentState, nowMs, idleTimeoutMs)) {
            return false;
        }
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOById(pluginCore, pluginId);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return false;
        }
        if (requireManagedProcess && !pluginBootstrapService.hasManagedProcessSlot(pluginVO.getPlugin())) {
            return false;
        }
        String pluginShortName = pluginVO.getPlugin().getShortName();
        String pluginName = PluginSessions.nameOrShortName(pluginVO.getPlugin());
        stateService.markStopping(pluginId, pluginName);
        try {
            if (pluginBootstrapService.stopPlugin(pluginId, pluginShortName)) {
                return true;
            }
            stateService.markFailed(pluginId, pluginName, "Plugin process is still alive after stop");
            return false;
        } catch (RuntimeException e) {
            stateService.markFailed(pluginId, pluginName, e.getMessage());
            LOGGER.log(Level.WARNING, "stop idle plugin " + pluginShortName + " error", e);
            return false;
        }
    }

    private boolean hasManagedProcess(String pluginId, PluginCore pluginCore) {
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOById(pluginCore, pluginId);
        return pluginVO != null && pluginVO.getPlugin() != null
                && pluginBootstrapService.hasManagedProcessSlot(pluginVO.getPlugin());
    }
}
