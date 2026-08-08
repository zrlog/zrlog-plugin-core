package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessRuntime;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.scheduler.AutomationStore;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.type.PluginStatus;
import com.zrlog.plugincore.server.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PluginRuntimeStates {

    private static final long TRANSIENT_UNKNOWN_STATE_TTL_MS = 60000L;
    private static final long STALE_TRANSIENT_INSTANCE_TTL_MS = 30000L;
    private static final int STORE_UPDATE_RETRIES = 3;

    private PluginRuntimeStates() {
    }

    public static void reconcileRuntimeStates() {
        PluginRuntimeStateStore stateStore = new PluginRuntimeStateStore(new WebsiteRuntimeKvStore());
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        PluginRuntimeStateService stateService = new PluginRuntimeStateService(stateStore, new DefaultPluginRuntimeStarter(pluginCore));
        long now = System.currentTimeMillis();
        PluginRuntimeStateDocument document = cleanupRuntimeInstances(stateStore, now);
        Map<String, PluginVO> pluginVOById = pluginVOById(pluginCore);
        Set<String> knownPluginIds = pluginVOById.keySet();
        Set<String> sessionPluginIds = currentSessionPluginIds();
        for (PluginRuntimeState state : document.getItems()) {
            if (state.getPluginId() == null) {
                stateStore.delete(state.getPluginId());
                continue;
            }
            if (!knownPluginIds.contains(state.getPluginId()) && !sessionPluginIds.contains(state.getPluginId())) {
                if (isRecentTransientRuntimeState(state, now)) {
                    continue;
                }
                if (isTerminalRuntimeStatus(state.getStatus())) {
                    continue;
                }
                if (hasRuntimeInstances(state)) {
                    continue;
                }
                stateService.markStopped(state.getPluginId(), state.getPluginName());
                continue;
            }
            if (PluginSessions.isRunningByPluginId(state.getPluginId()) || isTerminalRuntimeStatus(state.getStatus())) {
                continue;
            }
            PluginVO pluginVO = pluginVOById.get(state.getPluginId());
            if ((pluginVO == null || pluginVO.getPlugin() == null) && !hasRuntimeInstances(state)) {
                stateService.markStopped(state.getPluginId(), state.getPluginName());
            }
        }
    }

    public static void cleanupDirtyRuntimeStates(PluginCore pluginCore) {
        PluginRuntimeStateStore stateStore = new PluginRuntimeStateStore(new WebsiteRuntimeKvStore());
        Set<String> knownPluginIds = pluginIds(pluginCore);
        Set<String> sessionPluginIds = currentSessionPluginIds();
        stateStore.cleanupInstancesAndRemoveStates(System.currentTimeMillis(),
                PluginRuntimeLeases.LEGACY_INSTANCE_TTL_MS,
                STALE_TRANSIENT_INSTANCE_TTL_MS,
                state -> {
                    String pluginId = state.getPluginId();
                    return StringUtils.isEmpty(pluginId)
                            || (!knownPluginIds.contains(pluginId) && !sessionPluginIds.contains(pluginId));
                });
    }

    private static Set<String> pluginIds(PluginCore pluginCore) {
        Set<String> pluginIds = new HashSet<>();
        if (pluginCore == null || pluginCore.getPluginInfoMap() == null) {
            return pluginIds;
        }
        for (PluginVO pluginVO : pluginCore.getPluginInfoMap().values()) {
            if (pluginVO != null && pluginVO.getPlugin() != null
                    && !StringUtils.isEmpty(pluginVO.getPlugin().getId())) {
                pluginIds.add(pluginVO.getPlugin().getId());
            }
        }
        return pluginIds;
    }

    private static Map<String, PluginVO> pluginVOById(PluginCore pluginCore) {
        Map<String, PluginVO> pluginVOById = new HashMap<>();
        for (PluginVO pluginVO : pluginVOs(pluginCore)) {
            if (pluginVO.getPlugin() != null && !StringUtils.isEmpty(pluginVO.getPlugin().getId())) {
                pluginVOById.put(pluginVO.getPlugin().getId(), pluginVO);
            }
        }
        return pluginVOById;
    }

    private static Set<String> currentSessionPluginIds() {
        Set<String> pluginIds = new HashSet<>();
        for (IOSession session : PluginSessions.getAllLocalSessions()) {
            if (session.getPlugin() != null && !StringUtils.isEmpty(session.getPlugin().getId())) {
                pluginIds.add(session.getPlugin().getId());
            }
        }
        return pluginIds;
    }

    private static boolean isRecentTransientRuntimeState(PluginRuntimeState state, long now) {
        if (isTerminalRuntimeStatus(state.getStatus())) {
            return false;
        }
        Long activeAt = state.getLastActiveAt() == null ? state.getStartedAt() : state.getLastActiveAt();
        return activeAt != null && now - activeAt < TRANSIENT_UNKNOWN_STATE_TTL_MS;
    }

    private static boolean isTerminalRuntimeStatus(String status) {
        return PluginStatus.fromRuntimeStatus(status).isTerminal();
    }

    public static void markStoppedIfCurrent(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session == null || session.getPlugin() == null || !PluginSessions.isCurrentPluginIdentity(session.getPlugin())) {
                return;
            }
            newStateService(session).markStopped(session.getPlugin().getId(), pluginDisplayName(session.getPlugin()));
        }
    }

    public static void markStoppedByPluginId(String pluginId, String pluginName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, pluginName)) {
            newStateService().markStopped(pluginId, pluginName);
        }
    }

    public static void deletePluginRuntimeReferences(String pluginId) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            if (StringUtils.isEmpty(pluginId)) {
                return;
            }
            WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
            new PluginRuntimeStateStore(kvStore).delete(pluginId);
            new CapabilityStore(kvStore).replacePluginCapabilities(pluginId, Collections.emptyList());
            deleteAutomations(pluginId, kvStore);
            PluginCoreDAO.getInstance().update(pluginCore -> {
                pluginCore.getSetting().getNotification().getDefaultProviders().entrySet()
                        .removeIf(entry -> entry.getValue() != null && Objects.equals(pluginId, entry.getValue().getPluginId()));
                pluginCore.getSetting().getService().getDefaultProviders().entrySet()
                        .removeIf(entry -> entry.getValue() != null && Objects.equals(pluginId, entry.getValue().getPluginId()));
            });
        }
    }

    private static void deleteAutomations(String pluginId, WebsiteRuntimeKvStore kvStore) {
        AutomationStore automationStore = new AutomationStore(kvStore);
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            List<PluginAutomation> automations = new ArrayList<>(snapshot.getDocument().getItems());
            boolean removed = automations.removeIf(item -> Objects.equals(pluginId, item.getPluginId()));
            if (!removed) {
                return;
            }
            snapshot.getDocument().setItems(automations);
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
        throw new IllegalStateException("Failed to delete plugin automations due to concurrent modification");
    }

    public static List<PluginRuntimeState> runtimeStatesForDisplay() {
        List<PluginRuntimeInstanceView> instances = runtimeInstancesForDisplay();
        List<PluginRuntimeState> states = new ArrayList<>();
        for (PluginRuntimeInstanceView instance : instances) {
            PluginRuntimeState state = new PluginRuntimeState();
            state.setPluginId(instance.getPluginId());
            state.setPluginName(instance.getPluginName());
            state.setStatus(instance.getStatus());
            state.setEffectiveStatus(instance.getEffectiveStatus());
            state.setRuntimeMode(instance.getRuntimeMode());
            state.setStartedAt(instance.getStartedAt());
            state.setReadyAt(instance.getReadyAt());
            state.setLastActiveAt(instance.getLastActiveAt());
            state.setActiveInvocationCount(instance.getActiveInvocationCount());
            state.setLastError(instance.getLastError());
            states.add(state);
        }
        return states;
    }

    public static List<PluginRuntimeInstanceView> runtimeInstancesForDisplay() {
        Map<String, PluginRuntimeState> persistedStates = new HashMap<>();
        PluginRuntimeStateStore stateStore = new PluginRuntimeStateStore(new WebsiteRuntimeKvStore());
        PluginRuntimeStateDocument document = stateStore.loadDocument();
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        for (PluginRuntimeState state : document.getItems()) {
            if (!StringUtils.isEmpty(state.getPluginId())) {
                persistedStates.put(state.getPluginId(), state);
            }
        }
        List<PluginRuntimeInstanceView> instances = new ArrayList<>();
        for (PluginVO pluginVO : pluginVOs(pluginCore)) {
            if (pluginVO.getPlugin() == null || StringUtils.isEmpty(pluginVO.getPlugin().getId())) {
                continue;
            }
            Plugin plugin = pluginVO.getPlugin();
            PluginRuntimeState persisted = persistedStates.get(plugin.getId());
            if (persisted == null || persisted.getInstances() == null || persisted.getInstances().isEmpty()) {
                continue;
            }
            String pluginName = pluginDisplayName(plugin);
            for (PluginRuntimeInstanceState persistedInstance : persisted.getInstances()) {
                instances.add(instanceView(plugin, pluginName, persistedInstance));
            }
        }
        return instances;
    }

    public static void removeLocalRuntimeInstances(String pluginId) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            if (StringUtils.isEmpty(pluginId)) {
                return;
            }
            new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()).removeInstances(pluginId,
                    PluginRuntimeStates::isLocalRuntimeInstance);
        }
    }

    private static PluginRuntimeStateDocument cleanupRuntimeInstances(PluginRuntimeStateStore stateStore, long now) {
        return stateStore.cleanupInstancesAndLoad(now,
                PluginRuntimeLeases.LEGACY_INSTANCE_TTL_MS,
                STALE_TRANSIENT_INSTANCE_TTL_MS);
    }

    static PluginRuntimeInstanceView instanceView(Plugin plugin, String pluginName, PluginRuntimeInstanceState instance) {
        PluginRuntimeInstanceView view = new PluginRuntimeInstanceView();
        view.setPluginId(plugin.getId());
        view.setPluginName(pluginName);
        view.setPluginVersion(plugin.getVersion());
        view.setPluginPreviewImageBase64(StringUtils.isEmpty(plugin.getPreviewImageBase64())
                ? ""
                : plugin.getPreviewImageBase64());
        view.setInstanceId(instance.getInstanceId());
        view.setOwnerId(instance.getOwnerId());
        String status = PluginStatus.lifecycleRuntimeStatus(instance.getStatus());
        view.setStatus(status);
        view.setRuntimeMode(StringUtils.isEmpty(instance.getRuntimeMode())
                ? PluginProcessRuntime.runtimeMode(PluginFiles.getPluginFile(plugin.getShortName()))
                : instance.getRuntimeMode());
        view.setProcessId(instance.getProcessId());
        view.setLocal(isLocalRuntimeInstance(instance));
        view.setStartedAt(instance.getStartedAt());
        view.setReadyAt(instance.getReadyAt());
        view.setLastActiveAt(instance.getLastActiveAt());
        view.setHeartbeatAt(instance.getHeartbeatAt());
        view.setLeaseExpiresAt(instance.getLeaseExpiresAt());
        view.setActiveInvocationCount(instance.getActiveInvocationCount() == null ? 0 : instance.getActiveInvocationCount());
        view.setLastError(instance.getLastError());
        view.setEffectiveStatus(effectiveInstanceStatus(view));
        return view;
    }

    private static String pluginDisplayName(Plugin plugin) {
        return plugin == null || StringUtils.isEmpty(plugin.getName()) ? "未命名插件" : plugin.getName();
    }

    private static String effectiveInstanceStatus(PluginRuntimeInstanceView instance) {
        if (instance.getActiveInvocationCount() != null && instance.getActiveInvocationCount() > 0
                && PluginStatus.fromRuntimeStatus(instance.getStatus()).isStarted()) {
            return PluginStatus.EXECUTING.runtimeStatus();
        }
        return instance.getStatus();
    }

    private static boolean isLocalRuntimeInstance(PluginRuntimeInstanceState instance) {
        if (instance == null) {
            return false;
        }
        if (Objects.equals(runtimeInstancePrefix(), instance.getOwnerId())) {
            return true;
        }
        String instanceId = instance.getInstanceId();
        return !StringUtils.isEmpty(instanceId)
                && (Objects.equals(runtimeInstancePrefix(), instanceId)
                || instanceId.startsWith(runtimeInstancePrefix() + "/"));
    }

    public static IOSession getOrStartLocalSessionByPluginId(String pluginId) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            IOSession session = PluginSessions.claimReadyLocalSessionByPluginId(pluginId);
            if (session != null) {
                return session;
            }
            boolean started = newStateService().ensureStarted(pluginId);
            if (!started) {
                return null;
            }
            return PluginSessions.claimReadyLocalSessionByPluginId(pluginId);
        }
    }

    public static boolean ensureStarted(Plugin plugin) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(plugin)) {
            if (plugin == null || StringUtils.isEmpty(plugin.getId())) {
                return false;
            }
            PluginIdentity identity = new PluginIdentity(plugin.getId(), plugin.getShortName(), pluginDisplayName(plugin));
            return newStateService().ensureStarted(identity);
        }
    }

    private static PluginRuntimeStateService newStateService() {
        return new PluginRuntimeStateService(new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()), new DefaultPluginRuntimeStarter());
    }

    public static PluginRuntimeStateService newStateService(IOSession session) {
        return new PluginRuntimeStateService(
                new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()),
                new DefaultPluginRuntimeStarter(),
                PluginSessions.runtimeInstanceId(session)
        );
    }

    public static String runtimeInstancePrefix() {
        return PluginRuntimeInstances.currentInstanceId();
    }

    public static String newRuntimeInstanceId() {
        return PluginRuntimeInstances.newInstanceId();
    }

    public static String newRuntimeInstanceId(long processId) {
        return PluginRuntimeInstances.newProcessInstanceId(processId);
    }

    private static boolean hasRuntimeInstances(PluginRuntimeState state) {
        return state.getInstances() != null && !state.getInstances().isEmpty();
    }

    private static List<PluginVO> pluginVOs(PluginCore pluginCore) {
        if (pluginCore == null || pluginCore.getPluginInfoMap() == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(pluginCore.getPluginInfoMap().values());
    }
}
