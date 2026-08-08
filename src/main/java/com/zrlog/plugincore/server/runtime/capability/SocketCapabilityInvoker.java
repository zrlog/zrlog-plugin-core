package com.zrlog.plugincore.server.runtime.capability;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public class SocketCapabilityInvoker implements CapabilityInvoker {

    private final Gson gson = new Gson();
    private final Duration readTimeout;

    public SocketCapabilityInvoker() {
        this(PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
    }

    public SocketCapabilityInvoker(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    @Override
    public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
        try (PluginLogContext.Scope requestedScope = PluginLogContext.open(pluginId, null, null)) {
            IOSession session = PluginRuntimeStates.getOrStartLocalSessionByPluginId(pluginId);
            if (Objects.isNull(session)) {
                return error("Plugin session not found");
            }
            try (PluginLogContext.Scope sessionScope = PluginLogContext.open(session)) {
                CapabilityInvokeRequest request = new CapabilityInvokeRequest();
                request.setPluginId(pluginId);
                request.setCapabilityKey(capabilityKey);
                request.setSource(context == null ? null : context.getSource());
                request.setRequestId(context == null ? null : context.getRequestId());
                request.setTraceId(context == null ? null : context.getTraceId());
                request.setPayload(payload);
                int id = IdUtil.getInt();
                session.sendJsonMsg(request, ActionType.CAPABILITY_INVOKE.name(), id, MsgPacketStatus.SEND_REQUEST);
                try (ResponseLease responseLease = session.getResponseLeaseByMsgId(id, readTimeout(context))) {
                    if (responseLease == null) {
                        return error("Capability invoke timeout or empty response");
                    }
                    MsgPacket response = responseLease.getPacket();
                    if (!Objects.equals(ActionType.CAPABILITY_INVOKE.name(), response.getMethodStr())) {
                        return error("Unexpected capability response: " + response.getMethodStr());
                    }
                    try {
                        CapabilityInvokeResult result = gson.fromJson(response.getDataStr(), CapabilityInvokeResult.class);
                        if (result == null) {
                            return error("Capability invoke response is empty");
                        }
                        if (!result.isSuccess() && (result.getErrorMessage() == null || result.getErrorMessage().trim().isEmpty())) {
                            result.setErrorMessage("Capability invoke failed");
                        }
                        return result;
                    } catch (Exception e) {
                        if (response.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS) {
                            CapabilityInvokeResult result = new CapabilityInvokeResult();
                            result.setSuccess(true);
                            return result;
                        }
                        return error(e.getMessage());
                    }
                }
            }
        }
    }

    private CapabilityInvokeResult error(String message) {
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        result.setSuccess(false);
        result.setErrorMessage(message);
        return result;
    }

    private Duration readTimeout(InvokeContext context) {
        if (context == null || context.getTimeoutSeconds() == null) {
            return readTimeout;
        }
        return PluginExecutionTimeouts.executionTimeout(context.getTimeoutSeconds(), readTimeout);
    }
}
