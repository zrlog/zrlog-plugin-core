package com.zrlog.plugincore.server.runtime.event;

import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.capability.CapabilityInvoker;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.InvokeContext;
import com.zrlog.plugincore.server.runtime.capability.SocketCapabilityInvoker;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;
import com.zrlog.plugin.common.PluginExecutionTimeouts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class RuntimeEventRuntime {

    public static final String SOURCE = "runtime_event";

    private final CapabilityStore capabilityStore;
    private final RuntimeEventHandlerResolver handlerResolver;
    private final CapabilityInvoker capabilityInvoker;

    public RuntimeEventRuntime(CapabilityStore capabilityStore,
                               RuntimeEventHandlerResolver handlerResolver,
                               CapabilityInvoker capabilityInvoker) {
        this.capabilityStore = capabilityStore;
        this.handlerResolver = handlerResolver;
        this.capabilityInvoker = capabilityInvoker;
    }

    public RuntimeEventPublishResult publish(RuntimeEventRequest request) {
        RuntimeEventPublishResult result = new RuntimeEventPublishResult();
        if (request == null || request.getEventType() == null || request.getEventType().trim().isEmpty()) {
            result.setFailedCount(1);
            return result;
        }
        ensureRequestId(request);
        for (PluginCapability handler : handlerResolver.resolve(request, capabilityStore.listAll())) {
            CapabilityInvokeResult invokeResult = capabilityInvoker.invoke(
                    handler.getPluginId(), handler.getKey(), eventPayload(request), invokeContext(request));
            if (invokeResult.isSuccess()) {
                result.success(handler.getPluginId());
            } else {
                result.failed(handler.getPluginId());
            }
        }
        return result;
    }

    /** Synchronous host work shares one deadline, including plugin startup and cancellation. */
    public RuntimeEventPublishResult publishWithinBudget(RuntimeEventRequest request, String userId, RuntimeDeadline deadline) {
        if (request == null || request.getEventType() == null || request.getEventType().trim().isEmpty())
            throw new IllegalArgumentException("Runtime event type is required");
        ensureRequestId(request);
        RuntimeEventPublishResult result = new RuntimeEventPublishResult();
        for (PluginCapability handler : handlerResolver.resolve(request, capabilityStore.listAll())) {
            long available = deadline.remainingMillis();
            if (result.isOutcomeUncertain() || Thread.currentThread().isInterrupted()
                    || available <= SocketCapabilityInvoker.CLEANUP_RESERVE_MILLIS + 1000) {
                result.failed(handler.getPluginId());
                continue;
            }
            InvokeContext context = invokeContext(request);
            context.setUserId(userId);
            context.setDeadline(deadline);
            context.setRequireCompletion(true);
            // Keep startup time available even when the capability's default timeout exceeds the host request.
            long startup = Math.min(30000, available / 4);
            int executionSeconds = (int) Math.max(1, (available - startup - SocketCapabilityInvoker.CLEANUP_RESERVE_MILLIS) / 1000);
            context.setTimeoutSeconds((int) Math.min(executionSeconds,
                    PluginExecutionTimeouts.executionTimeout(handler.getTimeoutSeconds()).getSeconds()));
            try {
                CapabilityInvokeResult invoked = capabilityInvoker.invoke(
                        handler.getPluginId(), handler.getKey(), eventPayload(request), context);
                if (context.isOutcomeUncertain() || invoked == null || invoked.isOutcomeUncertain())
                    result.setOutcomeUncertain(true);
                if (invoked != null && invoked.isSuccess() && invoked.isExecutionCompleted() && !result.isOutcomeUncertain())
                    result.success(handler.getPluginId());
                else result.failed(handler.getPluginId());
            } catch (RuntimeException error) {
                // A failure after dispatch is not evidence that the plugin had no effects. Do not dispatch later handlers.
                result.setOutcomeUncertain(true);
                result.failed(handler.getPluginId());
            }
        }
        return result;
    }

    private void ensureRequestId(RuntimeEventRequest request) {
        if (request.getRequestId() == null || request.getRequestId().trim().isEmpty()) {
            request.setRequestId(UUID.randomUUID().toString());
        }
    }

    private InvokeContext invokeContext(RuntimeEventRequest request) {
        InvokeContext context = new InvokeContext();
        context.setSource(SOURCE);
        context.setRequestId(request.getRequestId() == null ? UUID.randomUUID().toString() : request.getRequestId());
        context.setTraceId(request.getTraceId());
        context.setAuditRequired(true);
        return context;
    }

    private Map<String, Object> eventPayload(RuntimeEventRequest request) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("eventType", request.getEventType());
        map.put("aliases", request.getAliases());
        map.put("source", request.getSource());
        map.put("requestId", request.getRequestId());
        map.put("traceId", request.getTraceId());
        map.put("payload", request.getPayload());
        return map;
    }
}
