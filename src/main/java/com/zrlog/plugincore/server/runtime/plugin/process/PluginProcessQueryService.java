package com.zrlog.plugincore.server.runtime.plugin.process;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginProcessInfo;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeInstanceView;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;

import java.nio.channels.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class PluginProcessQueryService {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);

    private final Supplier<List<IOSession>> sessionSupplier;
    private final Supplier<List<PluginRuntimeInstanceView>> runtimeInstanceSupplier;
    private final SessionProcessInfoClient processInfoClient;

    public PluginProcessQueryService() {
        this(PluginSessions::getAllLocalSessions,
                PluginRuntimeStates::runtimeInstancesForDisplay,
                PluginProcessQueryService::requestProcessInfo);
    }

    PluginProcessQueryService(Supplier<List<IOSession>> sessionSupplier,
                              Supplier<List<PluginRuntimeInstanceView>> runtimeInstanceSupplier,
                              SessionProcessInfoClient processInfoClient) {
        this.sessionSupplier = sessionSupplier;
        this.runtimeInstanceSupplier = runtimeInstanceSupplier;
        this.processInfoClient = processInfoClient;
    }

    public List<PluginRuntimeInstanceView> query() {
        List<PluginRuntimeInstanceView> instances = safeRuntimeInstances();
        RuntimeInstanceIndex runtimeIndex = RuntimeInstanceIndex.create(instances);
        for (IOSession session : safeSessions()) {
            if (session == null || session.getPlugin() == null) {
                continue;
            }
            PluginRuntimeInstanceView runtimeView = runtimeIndex.find(pluginId(session), runtimeInstanceId(session));
            if (runtimeView != null) {
                query(session, runtimeView);
            }
        }
        return instances;
    }

    private List<IOSession> safeSessions() {
        List<IOSession> sessions = sessionSupplier.get();
        return sessions == null ? new ArrayList<IOSession>() : sessions;
    }

    private List<PluginRuntimeInstanceView> safeRuntimeInstances() {
        List<PluginRuntimeInstanceView> instances = runtimeInstanceSupplier.get();
        return instances == null ? new ArrayList<PluginRuntimeInstanceView>() : instances;
    }

    private void query(IOSession session, PluginRuntimeInstanceView runtimeView) {
        PluginProcessInfo info;
        if (!isSessionOpen(session)) {
            info = unavailable("Plugin process channel is closed");
        } else {
            try {
                info = processInfoClient.query(session, QUERY_TIMEOUT);
            } catch (RuntimeException e) {
                info = unavailable(e.getMessage() == null ? "Plugin process query failed" : e.getMessage());
            }
        }
        if (info == null) {
            info = unavailable("Plugin process query returned empty response");
        }
        applyProcessInfo(runtimeView, info);
    }

    private static PluginProcessInfo requestProcessInfo(IOSession session, Duration timeout) {
        int msgId = session.queryPluginProcess(null, timeout);
        try (ResponseLease responseLease = session.getResponseLeaseByMsgId(msgId, timeout)) {
            if (responseLease == null) {
                throw new IllegalStateException("Plugin process query timeout");
            }
            MsgPacket response = responseLease.getPacket();
            if (response.getContentType() != ContentType.JSON) {
                throw new IllegalStateException("Unsupported plugin process response " + response.getContentType());
            }
            PluginProcessInfo info = response.convertToClass(PluginProcessInfo.class);
            if (info == null) {
                throw new IllegalStateException("Plugin process query returned empty response");
            }
            if (response.getStatus() == MsgPacketStatus.RESPONSE_ERROR && isBlank(info.getErrorMessage())) {
                info.setErrorMessage("Plugin process query failed");
            }
            if (info.getAlive() == null) {
                info.setAlive(response.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS);
            }
            return info;
        }
    }

    private void applyProcessInfo(PluginRuntimeInstanceView runtimeView, PluginProcessInfo info) {
        runtimeView.setProcessAlive(info.getAlive());
        runtimeView.setProcessSampledAt(info.getSampledAt());
        runtimeView.setProcessErrorMessage(info.getErrorMessage());
        runtimeView.setTotalCpuDurationMillis(info.getTotalCpuDurationMillis());
        runtimeView.setResidentMemoryBytes(info.getResidentMemoryBytes());
        runtimeView.setVirtualMemoryBytes(info.getVirtualMemoryBytes());
        runtimeView.setThreadCount(info.getThreadCount());
        runtimeView.setHeapUsedBytes(info.getHeapUsedBytes());
        runtimeView.setHeapCommittedBytes(info.getHeapCommittedBytes());
        runtimeView.setHeapMaxBytes(info.getHeapMaxBytes());
    }

    private static PluginProcessInfo unavailable(String message) {
        PluginProcessInfo info = new PluginProcessInfo();
        info.setAlive(Boolean.FALSE);
        info.setSampledAt(System.currentTimeMillis());
        info.setErrorMessage(message);
        return info;
    }

    private static boolean isSessionOpen(IOSession session) {
        Object channel = session.getSystemAttr().get("_channel");
        return channel instanceof Channel && ((Channel) channel).isOpen();
    }

    private static String pluginId(IOSession session) {
        Plugin plugin = session.getPlugin();
        return plugin == null ? null : plugin.getId();
    }

    private static String runtimeInstanceId(IOSession session) {
        Object value = session.getSystemAttr().get(PluginSessionRegistry.SESSION_ID_ATTR);
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    interface SessionProcessInfoClient {
        PluginProcessInfo query(IOSession session, Duration timeout);
    }

    private static final class RuntimeInstanceIndex {
        private final Map<String, PluginRuntimeInstanceView> byInstanceId = new HashMap<>();
        private final Map<String, PluginRuntimeInstanceView> byPluginId = new HashMap<>();

        static RuntimeInstanceIndex create(List<PluginRuntimeInstanceView> instances) {
            RuntimeInstanceIndex index = new RuntimeInstanceIndex();
            if (instances == null) {
                return index;
            }
            for (PluginRuntimeInstanceView instance : instances) {
                if (instance == null) {
                    continue;
                }
                if (!isBlank(instance.getInstanceId())) {
                    index.byInstanceId.put(instance.getInstanceId(), instance);
                }
                if (!isBlank(instance.getPluginId())) {
                    index.byPluginId.put(instance.getPluginId(), instance);
                }
            }
            return index;
        }

        PluginRuntimeInstanceView find(String pluginId, String instanceId) {
            if (!isBlank(instanceId)) {
                PluginRuntimeInstanceView instance = byInstanceId.get(instanceId);
                if (instance != null) {
                    return instance;
                }
            }
            if (isBlank(pluginId)) {
                return null;
            }
            return byPluginId.get(pluginId);
        }
    }
}
