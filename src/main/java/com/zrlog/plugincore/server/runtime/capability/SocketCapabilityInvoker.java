package com.zrlog.plugincore.server.runtime.capability;

import com.google.gson.Gson;
import com.hibegin.common.util.EnvKit;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.CapabilityCancelRequest;
import com.zrlog.plugin.message.CapabilityCancelResult;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public class SocketCapabilityInvoker implements CapabilityInvoker {
    // Cancellation has one second; the remaining budget covers process locks and graceful/forced exit.
    public static final long CLEANUP_RESERVE_MILLIS = 6000L;
    private static final long CANCEL_GRACE_MILLIS = 1000L;
    private final Gson gson = new Gson();
    private final Duration readTimeout;
    private final SessionResolver sessions;
    private final ProcessStopper stopper;
    private final BooleanSupplier requireCompletion;

    public SocketCapabilityInvoker() { this(PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT); }

    public SocketCapabilityInvoker(Duration readTimeout) {
        this(readTimeout, (pluginId, context) -> context.getDeadlineEpochMillis() != null || !context.isAllowStartup()
                        ? PluginSessions.claimReadyLocalSessionByPluginId(pluginId, Math.min(1000, context.remainingMillis()))
                        : PluginRuntimeStates.getOrStartLocalSessionByPluginId(pluginId),
                PluginRuntimeBridge::stopInvocationProcess, EnvKit::isFaaSMode);
    }

    SocketCapabilityInvoker(Duration readTimeout, SessionResolver sessions, ProcessStopper stopper,
                            BooleanSupplier requireCompletion) {
        this.readTimeout = readTimeout;
        this.sessions = sessions;
        this.stopper = stopper;
        this.requireCompletion = requireCompletion;
    }

    @Override
    public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
        return invoke(pluginId, capabilityKey, payload, context, null, false);
    }

    public CapabilityInvokeResult renderView(String pluginId, com.zrlog.plugin.data.codec.HttpRequestInfo view,
                                             boolean resource, InvokeContext context) {
        Objects.requireNonNull(view);
        return invoke(pluginId, CapabilityInvokeRequest.HOST_VIEW_CAPABILITY, java.util.Collections.emptyMap(), context, view, resource);
    }

    private CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context,
                                          com.zrlog.plugin.data.codec.HttpRequestInfo view, boolean resource) {
        if (context == null) context = new InvokeContext();
        if (context.getRequestId() == null || context.getRequestId().isEmpty()) context.setRequestId(UUID.randomUUID().toString());
        if (!context.getRequestId().matches("[a-zA-Z0-9_.-]{1,128}")) return error("Invalid capability request id");
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            if (context.remainingMillis() <= CLEANUP_RESERVE_MILLIS) return error("Insufficient invocation cleanup budget");
            IOSession session = sessions.resolve(pluginId, context);
            if (session == null) return error("Plugin session not found");
            try (PluginLogContext.Scope sessionScope = PluginLogContext.open(session)) {
                boolean lifecycle = session.getPlugin() != null && session.getPlugin().getCapabilityLifecycleVersion() >= 1;
                if (view != null && (session.getPlugin() == null || session.getPlugin().getCapabilityLifecycleVersion() < 2))
                    return error("Update the plugin SDK to support completed view rendering");
                if (!lifecycle && (context.isRequireCompletion() || requireCompletion.getAsBoolean()))
                    return error("Update the plugin SDK to support completed execution before running this task");
                long timeout = context.getTimeoutSeconds() == null ? readTimeout.toMillis()
                        : PluginExecutionTimeouts.executionTimeout(context.getTimeoutSeconds(), readTimeout).toMillis();
                RuntimeDeadline deadline = context.deadline().capMillis(timeout + CLEANUP_RESERVE_MILLIS);
                RuntimeDeadline execution = deadline.reserveMillis(CLEANUP_RESERVE_MILLIS);
                if (execution.remainingMillis() == 0) return error("Insufficient invocation cleanup budget");
                CapabilityInvokeRequest request = new CapabilityInvokeRequest();
                request.setPluginId(pluginId);
                request.setCapabilityKey(capabilityKey);
                request.setSource(context.getSource());
                request.setRequestId(context.getRequestId());
                request.setTraceId(context.getTraceId());
                request.setPayload(payload);
                request.setViewRequest(view);
                request.setViewResource(resource);
                request.setRequireCompletion(lifecycle);
                request.setDeadlineEpochMillis(System.currentTimeMillis() + execution.remainingMillis());
                int id = IdUtil.getInt();
                try {
                    session.sendJsonRequest(request, ActionType.CAPABILITY_INVOKE.name(), id, remaining(execution));
                    try (ResponseLease lease = session.getResponseLeaseByMsgId(id, remaining(execution))) {
                        if (lease != null && validResponse(lease.getPacket(), ActionType.CAPABILITY_INVOKE)) {
                            CapabilityInvokeResult result = gson.fromJson(lease.getPacket().getDataStr(), CapabilityInvokeResult.class);
                            if (result != null && (!lifecycle || result.isExecutionCompleted())
                                    && (view == null || !result.isSuccess() || result.getRenderedText() != null
                                    && result.getRenderedText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= CapabilityInvokeRequest.MAX_VIEW_RESPONSE_BYTES)
                                    && result.isSuccess() == (lease.getPacket().getStatus() == MsgPacketStatus.RESPONSE_SUCCESS)) {
                                if (result.isOutcomeUncertain()) {
                                    context.markOutcomeUncertain();
                                    result.setSuccess(false);
                                }
                                if (!result.isSuccess() && (result.getErrorMessage() == null || result.getErrorMessage().trim().isEmpty()))
                                    result.setErrorMessage("Capability invoke failed");
                                return result;
                            }
                        }
                    }
                } catch (RuntimeException unavailable) {
                    // A failed send may have written part or all of the frame; it is not proof of no effects.
                } catch (Error fatal) {
                    settleUncertain(session, id, request.getRequestId(), lifecycle, deadline, context);
                    throw fatal;
                }
                return settleUncertain(session, id, request.getRequestId(), lifecycle, deadline, context);
            }
        }
    }

    private CapabilityInvokeResult settleUncertain(IOSession session, int id, String requestId, boolean lifecycle,
                                                   RuntimeDeadline deadline, InvokeContext context) {
        context.markOutcomeUncertain();
        boolean interrupted = Thread.interrupted();
        boolean completed = false;
        try {
            if (lifecycle && !session.isClosed()) {
                RuntimeDeadline grace = deadline.reserveMillis(CLEANUP_RESERVE_MILLIS - CANCEL_GRACE_MILLIS)
                        .capMillis(CANCEL_GRACE_MILLIS);
                if (grace.remainingMillis() > 0) {
                    CapabilityCancelRequest cancel = new CapabilityCancelRequest();
                    cancel.setInvocationMessageId(id);
                    cancel.setRequestId(requestId);
                    int cancelId = IdUtil.getInt();
                    try {
                        session.sendJsonRequest(cancel, ActionType.CAPABILITY_CANCEL.name(), cancelId, remaining(grace));
                        try (ResponseLease lease = session.getResponseLeaseByMsgId(cancelId, remaining(grace))) {
                            if (lease != null && validResponse(lease.getPacket(), ActionType.CAPABILITY_CANCEL)) {
                                CapabilityCancelResult receipt = gson.fromJson(lease.getPacket().getDataStr(), CapabilityCancelResult.class);
                                completed = receipt != null && receipt.isExecutionCompleted()
                                        && Objects.equals(requestId, receipt.getRequestId());
                            }
                        }
                    } catch (RuntimeException ignored) { /* Stop the captured process if cancellation cannot be proved. */ }
                }
            }
            if (!completed) {
                // Closing this session wakes every other invocation with an uncertain outcome.
                session.close();
                try { completed = stopper.stop(session, deadline); }
                catch (RuntimeException ignored) { /* Preserve uncertainty and the process runtime's reservation. */ }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        CapabilityInvokeResult result = error(completed
                ? "Capability outcome is uncertain; execution stopped. Review before retrying."
                : "Capability outcome is uncertain; process termination could not be confirmed. Review before retrying.");
        result.setOutcomeUncertain(true);
        result.setExecutionCompleted(completed);
        return result;
    }

    private boolean validResponse(MsgPacket packet, ActionType action) {
        return Objects.equals(action.name(), packet.getMethodStr()) && packet.getContentType() == ContentType.JSON
                && (packet.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS || packet.getStatus() == MsgPacketStatus.RESPONSE_ERROR);
    }

    private static Duration remaining(RuntimeDeadline deadline) {
        return Duration.ofMillis(Math.max(1, deadline.remainingMillis()));
    }

    private CapabilityInvokeResult error(String message) {
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        result.setSuccess(false);
        result.setErrorMessage(message);
        return result;
    }

    interface SessionResolver { IOSession resolve(String pluginId, InvokeContext context); }
    interface ProcessStopper { boolean stop(IOSession session, RuntimeDeadline deadline); }
}
