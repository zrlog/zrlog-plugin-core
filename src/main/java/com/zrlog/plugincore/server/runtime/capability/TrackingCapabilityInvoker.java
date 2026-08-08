package com.zrlog.plugincore.server.runtime.capability;

import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TrackingCapabilityInvoker implements CapabilityInvoker {

    private static final Logger LOGGER = LoggerUtil.getLogger(TrackingCapabilityInvoker.class);

    private final CapabilityInvoker delegate;
    private final PluginRuntimeStateService stateService;
    private final InvocationLogStore invocationLogStore;
    private final CapabilityStore capabilityStore;

    public TrackingCapabilityInvoker(CapabilityInvoker delegate,
                                     PluginRuntimeStateService stateService,
                                     InvocationLogStore invocationLogStore) {
        this(delegate, stateService, invocationLogStore, null);
    }

    public TrackingCapabilityInvoker(CapabilityInvoker delegate,
                                     PluginRuntimeStateService stateService,
                                     InvocationLogStore invocationLogStore,
                                     CapabilityStore capabilityStore) {
        this.delegate = delegate;
        this.stateService = stateService;
        this.invocationLogStore = invocationLogStore;
        this.capabilityStore = capabilityStore;
    }

    @Override
    public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
        if (context == null) {
            context = new InvokeContext();
        }
        if (context.getRequestId() == null || context.getRequestId().trim().isEmpty()) {
            context.setRequestId(UUID.randomUUID().toString());
        }
        long startedAtMs = System.currentTimeMillis();
        CapabilityInvokeResult result;
        Optional<PluginCapability> capability = capability(pluginId, capabilityKey);
        String validationError = validationError(pluginId, capabilityKey, context, capability);
        if (validationError != null) {
            result = error(validationError);
            appendLogBestEffort(pluginId, capabilityKey, context, result, startedAtMs, capability);
            return result;
        }
        if (context.getTimeoutSeconds() == null) {
            context.setTimeoutSeconds(timeoutSeconds(capability));
        }
        String pluginName = capability.map(PluginCapability::getPluginName).orElse(null);
        if (!stateService.ensureStarted(pluginId)) {
            result = error("Plugin start failed");
            appendLogBestEffort(pluginId, capabilityKey, context, result, startedAtMs, capability);
            return result;
        }
        String invocationId = UUID.randomUUID().toString();
        Error fatalError = null;
        result = null;
        try {
            stateService.markInvocationStart(pluginId, pluginName, invocationId);
            try {
                result = delegate.invoke(pluginId, capabilityKey, payload, context);
                if (result == null) {
                    result = error("Capability invoker returned no result");
                }
            } catch (RuntimeException e) {
                result = error(e.getMessage());
            } catch (Error e) {
                result = error(errorMessage(e));
                fatalError = e;
            }
        } catch (RuntimeException e) {
            result = error(e.getMessage());
            throw e;
        } catch (Error e) {
            result = error(errorMessage(e));
            throw e;
        } finally {
            CapabilityInvokeResult completedResult = result == null
                    ? error("Capability invocation tracking failed") : result;
            finishTracking(pluginId, pluginName, invocationId, capabilityKey, context,
                    completedResult, startedAtMs, capability);
        }
        if (fatalError != null) {
            throw fatalError;
        }
        return result;
    }

    private void finishTracking(String pluginId,
                                String pluginName,
                                String invocationId,
                                String capabilityKey,
                                InvokeContext context,
                                CapabilityInvokeResult result,
                                long startedAtMs,
                                Optional<PluginCapability> capability) {
        String invocationError = result.isSuccess() ? null : result.getErrorMessage();
        try {
            stateService.markInvocationEndWithRetry(pluginId, pluginName, invocationId, invocationError);
        } catch (RuntimeException | Error e) {
            LOGGER.log(Level.WARNING, "Unable to finish capability invocation state tracking", e);
        }
        appendLogBestEffort(pluginId, capabilityKey, context, result, startedAtMs, capability);
    }

    private void appendLogBestEffort(String pluginId,
                                     String capabilityKey,
                                     InvokeContext context,
                                     CapabilityInvokeResult result,
                                     long startedAtMs,
                                     Optional<PluginCapability> capability) {
        try {
            appendLog(pluginId, capabilityKey, context, result, startedAtMs, capability);
        } catch (RuntimeException | Error e) {
            LOGGER.log(Level.WARNING, "Unable to append capability invocation log", e);
        }
    }

    private String validationError(String pluginId,
                                   String capabilityKey,
                                   InvokeContext context,
                                   Optional<PluginCapability> capability) {
        if (capabilityStore == null) {
            return null;
        }
        if (isBlank(pluginId)) {
            return "Plugin id is empty";
        }
        if (isBlank(capabilityKey)) {
            return "Capability key is empty";
        }
        if (context == null || isBlank(context.getSource())) {
            return "Capability invoke source is empty";
        }
        if (!capability.isPresent()) {
            return "Capability not found";
        }
        if (Boolean.FALSE.equals(capability.get().getEnabled())) {
            return "Capability is disabled";
        }
        if (!isExposedTo(capability.get(), context.getSource())) {
            return "Capability is not exposed to " + context.getSource();
        }
        String policyError = CapabilityRiskPolicy.invocationError(capability.get(), context);
        if (policyError != null) {
            return policyError;
        }
        return null;
    }

    private Optional<PluginCapability> capability(String pluginId, String capabilityKey) {
        if (capabilityStore == null) {
            return Optional.empty();
        }
        return capabilityStore.find(pluginId, capabilityKey);
    }

    private Integer timeoutSeconds(Optional<PluginCapability> capability) {
        if (capability.isPresent()
                && capability.get().getTimeoutSeconds() != null
                && capability.get().getTimeoutSeconds() > 0) {
            return capability.get().getTimeoutSeconds();
        }
        return PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT_SECONDS;
    }

    private boolean isExposedTo(PluginCapability capability, String source) {
        return RuntimeSources.isExposedTo(capability.getExposure(), source);
    }

    private void appendLog(String pluginId,
                           String capabilityKey,
                           InvokeContext context,
                           CapabilityInvokeResult result,
                           long startedAtMs,
                           Optional<PluginCapability> capability) {
        long finishedAtMs = System.currentTimeMillis();
        CapabilityInvocationLog log = new CapabilityInvocationLog();
        log.setId(UUID.randomUUID().toString());
        log.setPluginId(pluginId);
        log.setCapabilityKey(capabilityKey);
        log.setSource(context.getSource());
        if (capability.isPresent()) {
            log.setRiskLevel(CapabilityRiskPolicy.riskLevel(capability.get()));
        }
        log.setAuditRequired(CapabilityRiskPolicy.auditRequired(capability.orElse(null), context));
        log.setRequestId(context.getRequestId());
        log.setTraceId(context.getTraceId());
        log.setStartedAt(startedAtMs);
        log.setFinishedAt(finishedAtMs);
        log.setDurationMs(finishedAtMs - startedAtMs);
        log.setStatus(result.isSuccess() ? "success" : "error");
        log.setErrorMessage(result.getErrorMessage());
        invocationLogStore.append(log);
    }

    private CapabilityInvokeResult error(String message) {
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        result.setSuccess(false);
        result.setErrorMessage(message == null || message.trim().isEmpty() ? "Capability invoke failed" : message);
        return result;
    }

    private String errorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Capability invoke failed";
        }
        String message = throwable.getMessage();
        return isBlank(message) ? throwable.getClass().getSimpleName() : message;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
