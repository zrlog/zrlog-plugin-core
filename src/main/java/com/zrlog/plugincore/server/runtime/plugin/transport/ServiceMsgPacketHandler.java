package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.Plugin;
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

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServiceMsgPacketHandler {

    private static final Logger LOGGER = LoggerUtil.getLogger(ServiceMsgPacketHandler.class);
    private static final Gson GSON = new Gson();
    static final int MAX_SERVICE_REQUEST_BYTES = 4 * 1024 * 1024;
    static final String SERVICE_REQUEST_TOO_LARGE_MESSAGE = "Service request exceeds 4 MiB";
    private static final int SERVICE_SESSION_RETRY_COUNT = 60;
    private static final long SERVICE_SESSION_RETRY_INTERVAL_MS = 1000L;
    private static final long DEFAULT_START_WAIT_TIMEOUT_MS = 30000L;
    private static final long START_CAPACITY_WAIT_TIMEOUT_MS = 5000L;
    private static final long COLD_RESOLUTION_TIMEOUT_MS = DEFAULT_START_WAIT_TIMEOUT_MS
            + START_CAPACITY_WAIT_TIMEOUT_MS
            + (SERVICE_SESSION_RETRY_COUNT - 1L) * SERVICE_SESSION_RETRY_INTERVAL_MS;
    private static final String SERVICE_BUSY_MESSAGE = "Service invocation capacity reached";

    private final IOSession session;
    private final ServiceInvocationDispatcher serviceInvocationDispatcher;

    public ServiceMsgPacketHandler(IOSession session) {
        this(session, null);
    }

    ServiceMsgPacketHandler(IOSession session, ServiceInvocationDispatcher serviceInvocationDispatcher) {
        this.session = session;
        this.serviceInvocationDispatcher = serviceInvocationDispatcher;
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
        IOSession serviceSession = PluginSessions.claimReadyLocalSessionByPluginId(servicePluginId);
        if (Objects.nonNull(serviceSession)) {
            return serviceSession;
        }
        if (!ensureServiceStarted(pluginVO, pluginCore)) {
            return null;
        }
        for (int i = 0; i < loopCount; i++) {
            serviceSession = PluginSessions.claimReadyLocalSessionByPluginId(servicePluginId);
            if (Objects.nonNull(serviceSession)) {
                return serviceSession;
            }
            if (i + 1 < loopCount) {
                Thread.sleep(SERVICE_SESSION_RETRY_INTERVAL_MS);
            }
        }
        return null;
    }

    private static boolean ensureServiceStarted(PluginVO pluginVO, PluginCore pluginCore) {
        return runtimeStateService(pluginCore).ensureStarted(pluginVO.getPlugin().getId());
    }

    public void doHandle(final MsgPacket msgPacket) {
        try (PluginLogContext.Scope sourceScope = PluginLogContext.open(session)) {
            if (msgPacket.getDataLength() > MAX_SERVICE_REQUEST_BYTES) {
                sendServiceError(session, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                        SERVICE_REQUEST_TOO_LARGE_MESSAGE);
                return;
            }
            long receivedAtMs = System.currentTimeMillis();
            long resolutionDeadlineNanos = resolutionDeadlineNanos();
            Map<String, Object> map;
            try {
                map = parseServicePayload(msgPacket);
            } catch (RuntimeException e) {
                sendServiceError(session, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                        "Invalid service request");
                return;
            }
            Object nameValue = map == null ? null : map.get("name");
            String name = nameValue instanceof String ? (String) nameValue : null;
            if (isBlank(name)) {
                sendServiceError(session, msgPacket.getMethodStr(), msgPacket.getMsgId(), "Service name is required");
                return;
            }
            KvRepository runtimeKvStore = kvStore();
            PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
            List<PluginCapability> serviceCapabilities = new CapabilityStore(runtimeKvStore).listByType("service");
            try {
                PluginCapability provider = resolveServiceProvider(name, serviceCapabilities, pluginCore);
                String providerPluginId = provider == null ? null : provider.getPluginId();
                PluginVO pluginVO = findPluginByService(name, providerPluginId, pluginCore);
                if (pluginVO == null || pluginVO.getPlugin() == null) {
                    throw new IllegalStateException("Not found serviceSession " + name);
                }
                String targetPluginId = pluginVO.getPlugin().getId();
                String capabilityKey = serviceCapabilityKey(name, targetPluginId, provider, serviceCapabilities);
                ServiceInvocationContext context = new ServiceInvocationContext(
                        session,
                        sourceIdentity(session),
                        msgPacket.getMethodStr(),
                        msgPacket.getMsgId(),
                        msgPacket.getDataLength(),
                        name,
                        targetPluginId,
                        capabilityKey,
                        PluginExecutionTimeouts.executionTimeout(provider == null ? null : provider.getTimeoutSeconds()),
                        String.valueOf(msgPacket.getMsgId()),
                        receivedAtMs,
                        resolutionDeadlineNanos,
                        runtimeKvStore,
                        map
                );
                IOSession readySession = PluginSessions.claimReadyLocalSessionByPluginId(targetPluginId, 1L);
                if (readySession != null) {
                    invokeService(context, readySession);
                    return;
                }
                dispatchColdInvocation(context);
            } catch (Exception e) {
                sendServiceError(session, msgPacket.getMethodStr(), msgPacket.getMsgId(), errorMessage(e));
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseServicePayload(MsgPacket msgPacket) {
        Objects.requireNonNull(msgPacket, "msgPacket");
        int dataLength = msgPacket.getDataLength();
        if (dataLength < 0) {
            throw new IllegalArgumentException("Invalid service request length");
        }
        if (dataLength > MAX_SERVICE_REQUEST_BYTES) {
            throw new IllegalArgumentException(SERVICE_REQUEST_TOO_LARGE_MESSAGE);
        }
        ByteBuffer data = Objects.requireNonNull(msgPacket.getData(), "Service request payload is required");
        if (!data.hasArray()) {
            throw new IllegalArgumentException("Service request payload must be array-backed");
        }
        byte[] bytes = data.array();
        int offset = data.arrayOffset();
        if (dataLength > bytes.length - offset) {
            throw new IllegalArgumentException("Invalid service request length");
        }
        Reader reader = new InputStreamReader(
                new ByteArrayInputStream(bytes, offset, dataLength), StandardCharsets.UTF_8);
        return GSON.fromJson(reader, Map.class);
    }

    private void dispatchColdInvocation(ServiceInvocationContext context) {
        if (context.sourceSession.isClosed()) {
            return;
        }
        if (serviceInvocationDispatcher == null) {
            continueColdInvocation(context);
            return;
        }
        boolean accepted = serviceInvocationDispatcher.dispatch(context.payloadBytes,
                () -> continueColdInvocation(context));
        if (!accepted) {
            sendServiceError(context.sourceSession, context.sourceMethod, context.sourceMsgId, SERVICE_BUSY_MESSAGE);
        }
    }

    private void continueColdInvocation(ServiceInvocationContext context) {
        try (PluginLogContext.Scope ignored = context.sourceIdentity.open()) {
            if (context.sourceSession.isClosed()) {
                return;
            }
            IOSession serviceSession = getServiceSessionBeforeDeadline(context);
            if (context.sourceSession.isClosed()) {
                return;
            }
            if (serviceSession == null || serviceSession.getPlugin() == null) {
                sendServiceError(context.sourceSession, context.sourceMethod, context.sourceMsgId,
                        "Not found serviceSession " + context.serviceName);
                return;
            }
            invokeService(context, serviceSession);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendServiceError(context.sourceSession, context.sourceMethod, context.sourceMsgId,
                    "Service startup interrupted");
        } catch (Exception e) {
            sendServiceError(context.sourceSession, context.sourceMethod, context.sourceMsgId, errorMessage(e));
        }
    }

    private IOSession getServiceSessionBeforeDeadline(ServiceInvocationContext context) throws InterruptedException {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        PluginVO pluginVO = findPluginByService(context.serviceName, context.targetPluginId, pluginCore);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return null;
        }
        long startWaitTimeoutMs = startWaitTimeoutMs(context.resolutionDeadlineNanos);
        if (startWaitTimeoutMs <= 0L) {
            return null;
        }
        IOSession serviceSession = PluginSessions.claimReadyLocalSessionByPluginId(
                context.targetPluginId, startWaitTimeoutMs);
        if (serviceSession != null) {
            return serviceSession;
        }
        startWaitTimeoutMs = startWaitTimeoutMs(context.resolutionDeadlineNanos);
        if (startWaitTimeoutMs <= 0L
                || !runtimeStateService(pluginCore, startWaitTimeoutMs).ensureStarted(context.targetPluginId)) {
            return null;
        }
        while (true) {
            long remainingMs = remainingMillis(context.resolutionDeadlineNanos);
            if (remainingMs <= 0L) {
                return null;
            }
            serviceSession = PluginSessions.claimReadyLocalSessionByPluginId(
                    context.targetPluginId, Math.min(SERVICE_SESSION_RETRY_INTERVAL_MS, remainingMs));
            if (serviceSession != null) {
                return serviceSession;
            }
            remainingMs = remainingMillis(context.resolutionDeadlineNanos);
            if (remainingMs <= 0L) {
                return null;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Service startup interrupted");
            }
            Thread.sleep(Math.min(SERVICE_SESSION_RETRY_INTERVAL_MS, remainingMs));
        }
    }

    private void invokeService(ServiceInvocationContext context, IOSession serviceSession) {
        final IOSession sourceSession = context.sourceSession;
        final String sourceMethod = context.sourceMethod;
        final int sourceMsgId = context.sourceMsgId;
        final String serviceName = context.serviceName;
        final String capabilityKey = context.capabilityKey;
        final String requestId = context.requestId;
        final long startedAtMs = context.startedAtMs;
        final Duration executionTimeout = context.executionTimeout;
        final KvRepository runtimeKvStore = context.runtimeKvStore;
        final Map<String, Object> payload = context.payload;
        final SourceIdentity sourceIdentity = context.sourceIdentity;
        InvocationCompletion invocationCompletion = null;
        try {
            if (sourceSession.isClosed() || serviceSession.isClosed() || serviceSession.getPlugin() == null) {
                return;
            }
            String targetPluginId = serviceSession.getPlugin().getId();
            String targetPluginName = PluginSessions.nameOrShortName(serviceSession.getPlugin());
            PluginRuntimeStateService invocationStateService = PluginRuntimeStates.newStateService(serviceSession);
            String invocationId = UUID.randomUUID().toString();
            final IOSession callbackServiceSession = serviceSession;
            final String invocationPluginId = targetPluginId;
            final String invocationPluginName = targetPluginName;
            final String callbackInvocationId = invocationId;
            final PluginRuntimeStateService callbackStateService = invocationStateService;
            invocationCompletion = new InvocationCompletion(errorMessage -> finishInvocation(
                    callbackServiceSession,
                    callbackStateService,
                    runtimeKvStore,
                    invocationPluginId,
                    invocationPluginName,
                    callbackInvocationId,
                    capabilityKey,
                    requestId,
                    startedAtMs,
                    errorMessage));
            final InvocationCompletion callbackCompletion = invocationCompletion;
            try (PluginLogContext.Scope targetScope = PluginLogContext.open(serviceSession)) {
                invocationStateService.markInvocationStart(targetPluginId, targetPluginName, invocationId);
            }
            if (sourceSession.isClosed()) {
                callbackCompletion.complete("Source service session closed");
                return;
            }
            serviceSession.requestService(serviceName, payload, responseMsgPacket -> {
                try (PluginLogContext.Scope callbackScope = PluginLogContext.open(callbackServiceSession)) {
                    String callbackErrorMessage = responseMsgPacket.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS
                            ? null : "service response error";
                    try {
                        if (!sourceSession.isClosed()) {
                            responseMsgPacket.setMsgId(sourceMsgId);
                            sourceSession.sendMsg(responseMsgPacket);
                        }
                    } finally {
                        callbackCompletion.complete(callbackErrorMessage);
                    }
                }
            }, executionTimeout, () -> {
                String errorMessage = "Service request timed out or target session closed";
                if (callbackCompletion.complete(errorMessage)) {
                    try (PluginLogContext.Scope sourceScope = sourceIdentity.open()) {
                        sendServiceError(sourceSession, sourceMethod, sourceMsgId, errorMessage);
                    }
                }
            });
        } catch (Exception e) {
            boolean sendError = invocationCompletion == null || invocationCompletion.complete(errorMessage(e));
            if (sendError) {
                sendServiceError(sourceSession, sourceMethod, sourceMsgId, errorMessage(e));
            }
        } catch (Error e) {
            if (invocationCompletion != null) {
                invocationCompletion.complete(errorMessage(e));
            }
            throw e;
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

    private static PluginRuntimeStateService runtimeStateService(PluginCore pluginCore, long startWaitTimeoutMs) {
        return new PluginRuntimeStateService(new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()),
                new DefaultPluginRuntimeStarter(pluginCore),
                Math.max(1L, startWaitTimeoutMs),
                Math.max(1L, Math.min(100L, startWaitTimeoutMs)));
    }

    private static long resolutionDeadlineNanos() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COLD_RESOLUTION_TIMEOUT_MS);
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static long startWaitTimeoutMs(long deadlineNanos) {
        long remainingMs = remainingMillis(deadlineNanos);
        if (remainingMs <= 2L) {
            return 0L;
        }
        long usableMs = Math.max(1L, remainingMs - 25L);
        long waitMs = usableMs <= START_CAPACITY_WAIT_TIMEOUT_MS * 2L
                ? usableMs / 2L
                : usableMs - START_CAPACITY_WAIT_TIMEOUT_MS;
        return Math.max(1L, Math.min(DEFAULT_START_WAIT_TIMEOUT_MS, waitMs));
    }

    private static SourceIdentity sourceIdentity(IOSession sourceSession) {
        Plugin plugin = sourceSession == null ? null : sourceSession.getPlugin();
        if (plugin == null) {
            return new SourceIdentity(null, null, null);
        }
        return new SourceIdentity(plugin.getId(), plugin.getShortName(), plugin.getName());
    }

    private static String errorMessage(Throwable exception) {
        if (exception == null) {
            return "Service invocation failed";
        }
        String message = exception.getMessage();
        return isBlank(message) ? exception.getClass().getSimpleName() : message;
    }

    private static void sendServiceError(IOSession sourceSession, String method, int msgId, String message) {
        if (sourceSession == null || sourceSession.isClosed()) {
            return;
        }
        try {
            sourceSession.sendJsonMsg(PluginTransportModels.ServiceErrorResponse.error(
                    isBlank(message) ? "Service invocation failed" : message),
                    method, msgId, MsgPacketStatus.RESPONSE_ERROR);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Unable to send plugin service error", e);
        }
    }

    private KvRepository kvStore() {
        return new WebsiteRuntimeKvStore();
    }

    void finishInvocation(IOSession serviceSession,
                          PluginRuntimeStateService stateService,
                          KvRepository runtimeKvStore,
                          String pluginId,
                          String pluginName,
                          String invocationId,
                          String capabilityKey,
                          String requestId,
                          long startedAtMs,
                          String errorMessage) {
        try (PluginLogContext.Scope targetScope = PluginLogContext.open(serviceSession)) {
            try {
                stateService.markInvocationEndWithRetry(pluginId, pluginName, invocationId, errorMessage);
            } catch (RuntimeException | Error e) {
                LOGGER.log(Level.WARNING, "Unable to finish plugin service invocation state tracking", e);
            }
            try {
                ServiceInvocationLogs.append(runtimeKvStore, pluginId, capabilityKey, requestId, null,
                        startedAtMs, System.currentTimeMillis(), errorMessage);
            } catch (RuntimeException | Error e) {
                LOGGER.log(Level.WARNING, "Unable to append plugin service invocation log", e);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class ServiceInvocationContext {

        private final IOSession sourceSession;
        private final SourceIdentity sourceIdentity;
        private final String sourceMethod;
        private final int sourceMsgId;
        private final int payloadBytes;
        private final String serviceName;
        private final String targetPluginId;
        private final String capabilityKey;
        private final Duration executionTimeout;
        private final String requestId;
        private final long startedAtMs;
        private final long resolutionDeadlineNanos;
        private final KvRepository runtimeKvStore;
        private final Map<String, Object> payload;

        private ServiceInvocationContext(IOSession sourceSession,
                                         SourceIdentity sourceIdentity,
                                         String sourceMethod,
                                         int sourceMsgId,
                                         int payloadBytes,
                                         String serviceName,
                                         String targetPluginId,
                                         String capabilityKey,
                                         Duration executionTimeout,
                                         String requestId,
                                         long startedAtMs,
                                         long resolutionDeadlineNanos,
                                         KvRepository runtimeKvStore,
                                         Map<String, Object> payload) {
            this.sourceSession = sourceSession;
            this.sourceIdentity = sourceIdentity;
            this.sourceMethod = sourceMethod;
            this.sourceMsgId = sourceMsgId;
            this.payloadBytes = payloadBytes;
            this.serviceName = serviceName;
            this.targetPluginId = targetPluginId;
            this.capabilityKey = capabilityKey;
            this.executionTimeout = executionTimeout;
            this.requestId = requestId;
            this.startedAtMs = startedAtMs;
            this.resolutionDeadlineNanos = resolutionDeadlineNanos;
            this.runtimeKvStore = runtimeKvStore;
            this.payload = payload;
        }
    }

    private static final class SourceIdentity {

        private final String pluginId;
        private final String pluginShortName;
        private final String pluginName;

        private SourceIdentity(String pluginId, String pluginShortName, String pluginName) {
            this.pluginId = pluginId;
            this.pluginShortName = pluginShortName;
            this.pluginName = pluginName;
        }

        private PluginLogContext.Scope open() {
            return PluginLogContext.open(pluginId, pluginShortName, pluginName);
        }
    }

    static final class InvocationCompletion {

        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final Consumer<String> completion;

        InvocationCompletion(Consumer<String> completion) {
            this.completion = Objects.requireNonNull(completion);
        }

        boolean complete(String errorMessage) {
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            try {
                completion.accept(errorMessage);
            } catch (RuntimeException | Error e) {
                LOGGER.log(Level.WARNING, "Unable to finish plugin service invocation", e);
            }
            return true;
        }
    }
}
