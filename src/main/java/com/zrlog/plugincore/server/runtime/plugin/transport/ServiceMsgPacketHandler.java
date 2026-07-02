package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.invocation.ServiceInvocationLogs;
import com.zrlog.plugincore.server.runtime.service.ServiceProviderResolver;
import com.zrlog.plugincore.server.runtime.service.ServiceSetting;
import com.zrlog.plugincore.server.runtime.state.DefaultPluginRuntimeStarter;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ServiceMsgPacketHandler {

    private final IOSession session;

    public ServiceMsgPacketHandler(IOSession session) {
        this.session = session;
    }

    private static PluginVO findPluginByService(String service, String pluginId) {
        return findPluginByService(service, pluginId, PluginCoreDAO.getInstance().loadSnapshot());
    }

    private static PluginVO findPluginByService(String service, String pluginId, PluginCore pluginCore) {
        for (PluginVO pluginVO : PluginCoreDAO.getInstance().getPluginVOs(pluginCore)) {
            if (pluginVO.getPlugin() != null
                    && (pluginId == null || Objects.equals(pluginId, pluginVO.getPlugin().getId()))
                    && pluginVO.getPlugin().getServices() != null
                    && pluginVO.getPlugin().getServices().contains(service)) {
                return pluginVO;
            }
        }
        return null;
    }

    public static IOSession getServiceSessionWithRetry(String serviceName, int retryCount) throws InterruptedException {
        return getServiceSessionWithRetry(serviceName, null, retryCount);
    }

    private static IOSession getServiceSessionWithRetry(String serviceName, String pluginId, int retryCount) throws InterruptedException {
        return getServiceSessionWithRetry(serviceName, pluginId, retryCount, PluginCoreDAO.getInstance().loadSnapshot());
    }

    private static IOSession getServiceSessionWithRetry(String serviceName,
                                                        String pluginId,
                                                        int retryCount,
                                                        PluginCore pluginCore) throws InterruptedException {
        int loopCount = Math.max(retryCount, 1);
        PluginVO pluginVO = findPluginByService(serviceName, pluginId, pluginCore);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return null;
        }
        String servicePluginId = pluginVO.getPlugin().getId();
        IOSession serviceSession = PluginSessions.getLocalSessionByPluginId(servicePluginId);
        if (Objects.nonNull(serviceSession)) {
            return serviceSession;
        }
        if (!ensureServiceStarted(pluginVO, pluginCore)) {
            return null;
        }
        for (int i = 0; i < loopCount; i++) {
            serviceSession = PluginSessions.getLocalSessionByPluginId(servicePluginId);
            if (Objects.nonNull(serviceSession)) {
                return serviceSession;
            }
            if (i + 1 < loopCount) {
                Thread.sleep(1000);
            }
        }
        return null;
    }

    private static boolean ensureServiceStarted(PluginVO pluginVO, PluginCore pluginCore) {
        return runtimeStateService(pluginCore).ensureStarted(pluginVO.getPlugin().getId());
    }

    public void doHandle(final MsgPacket msgPacket) {
        try (PluginLogContext.Scope sourceScope = PluginLogContext.open(session)) {
            Gson gson = new Gson();
            PluginTransportModels.ServiceRequest request =
                    gson.fromJson(msgPacket.getDataStr(), PluginTransportModels.ServiceRequest.class);
            Map<String, Object> map = gson.fromJson(msgPacket.getDataStr(), Map.class);
            if (request == null || request.getName() == null) {
                session.sendJsonMsg(PluginTransportModels.ServiceErrorResponse.error("Service name is required"),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
                return;
            }
            String name = request.getName();
            PluginRuntimeStateService stateService = null;
            String targetPluginId = null;
            String targetPluginName = null;
            String capabilityKey = null;
            String requestId = msgPacket == null ? UUID.randomUUID().toString() : String.valueOf(msgPacket.getMsgId());
            long startedAtMs = System.currentTimeMillis();
            KvRepository runtimeKvStore = kvStore();
            PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
            List<PluginCapability> serviceCapabilities = new CapabilityStore(runtimeKvStore).listByType("service");
            try {
                PluginCapability provider = resolveServiceProvider(name, serviceCapabilities, pluginCore);
                IOSession serviceSession = provider == null
                        ? getServiceSessionWithRetry(name, null, 60, pluginCore)
                        : getServiceSessionWithRetry(name, provider.getPluginId(), 60, pluginCore);
                if (Objects.isNull(serviceSession) || serviceSession.getPlugin() == null) {
                    throw new RuntimeException("Not found serviceSession " + name);
                }
                targetPluginId = serviceSession.getPlugin().getId();
                targetPluginName = PluginSessions.nameOrShortName(serviceSession.getPlugin());
                capabilityKey = serviceCapabilityKey(name, targetPluginId, provider, serviceCapabilities);
                PluginRuntimeStateService invocationStateService = PluginRuntimeStates.newStateService(serviceSession);
                stateService = invocationStateService;
                try (PluginLogContext.Scope targetScope = PluginLogContext.open(serviceSession)) {
                    invocationStateService.markInvocationStart(targetPluginId, targetPluginName);
                }
                final String invocationPluginId = targetPluginId;
                final String invocationPluginName = targetPluginName;
                final String invocationCapabilityKey = capabilityKey;
                final long invocationStartedAtMs = startedAtMs;
                final String invocationRequestId = requestId;
                final PluginRuntimeStateService callbackStateService = invocationStateService;
                final IOSession callbackServiceSession = serviceSession;
                // 消息中转
                serviceSession.requestService(name, map, responseMsgPacket -> {
                    try (PluginLogContext.Scope callbackScope = PluginLogContext.open(callbackServiceSession)) {
                        String callbackErrorMessage = responseMsgPacket.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS ? null : "service response error";
                        try {
                            responseMsgPacket.setMsgId(msgPacket.getMsgId());
                            session.sendMsg(responseMsgPacket);
                        } finally {
                            callbackStateService.markInvocationEnd(invocationPluginId, invocationPluginName,
                                    callbackErrorMessage);
                            ServiceInvocationLogs.append(runtimeKvStore, invocationPluginId, invocationCapabilityKey, invocationRequestId, null,
                                    invocationStartedAtMs, System.currentTimeMillis(), callbackErrorMessage);
                        }
                    }
                }, PluginExecutionTimeouts.executionTimeout(provider == null ? null : provider.getTimeoutSeconds()));
            } catch (Exception e) {
                if (targetPluginId != null && stateService != null) {
                    try (PluginLogContext.Scope targetScope = PluginLogContext.open(targetPluginId, null, targetPluginName)) {
                        stateService.markInvocationEnd(targetPluginId, targetPluginName, e.getMessage());
                        ServiceInvocationLogs.append(runtimeKvStore, targetPluginId, capabilityKey == null ? name : capabilityKey, requestId, null,
                                startedAtMs, System.currentTimeMillis(), e.getMessage());
                    }
                }
                // not found service response error
                session.sendJsonMsg(PluginTransportModels.ServiceErrorResponse.error(e.getMessage()),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
            }
        }
    }

    private PluginCapability resolveServiceProvider(String serviceName,
                                                    List<PluginCapability> serviceCapabilities,
                                                    PluginCore pluginCore) {
        ServiceSetting setting = pluginCore.getSetting().getService();
        return new ServiceProviderResolver()
                .resolve(serviceName, serviceCapabilities, setting)
                .orElse(null);
    }

    private String serviceCapabilityKey(String serviceName,
                                        String pluginId,
                                        PluginCapability resolvedProvider,
                                        List<PluginCapability> serviceCapabilities) {
        if (resolvedProvider != null && !isBlank(resolvedProvider.getKey())) {
            return resolvedProvider.getKey();
        }
        PluginCapability provider = new ServiceProviderResolver()
                .providersFor(serviceName, serviceCapabilities)
                .stream()
                .filter(item -> Objects.equals(pluginId, item.getPluginId()))
                .findFirst()
                .orElse(null);
        return provider == null || isBlank(provider.getKey()) ? serviceName : provider.getKey();
    }

    private static PluginRuntimeStateService runtimeStateService(PluginCore pluginCore) {
        return new PluginRuntimeStateService(new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()),
                new DefaultPluginRuntimeStarter(pluginCore));
    }

    private KvRepository kvStore() {
        return new WebsiteRuntimeKvStore();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
