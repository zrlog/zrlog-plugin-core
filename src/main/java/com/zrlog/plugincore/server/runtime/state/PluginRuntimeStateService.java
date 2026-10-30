package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.type.PluginStatus;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public class PluginRuntimeStateService {

    private static final long DEFAULT_START_WAIT_TIMEOUT_MS = 30000L;
    private static final long DEFAULT_START_WAIT_INTERVAL_MS = 100L;
    private static final long DEFAULT_DEMAND_CLAIM_MS = 30000L;
    private static final int INVOCATION_END_ATTEMPTS = 2;
    static final int MAX_ACTIVE_INVOCATION_IDS = 256;

    private final PluginRuntimeStateStore stateStore;
    private final PluginRuntimeStarter starter;
    private final long startWaitTimeoutMs;
    private final long startWaitIntervalMs;
    private final String runtimeInstanceId;
    private final PluginStartCoordinator startCoordinator;

    public PluginRuntimeStateService(PluginRuntimeStateStore stateStore, PluginRuntimeStarter starter) {
        this(stateStore, starter, DEFAULT_START_WAIT_TIMEOUT_MS, DEFAULT_START_WAIT_INTERVAL_MS,
                PluginRuntimeInstances.currentInstanceId());
    }

    public PluginRuntimeStateService(PluginRuntimeStateStore stateStore,
                                     PluginRuntimeStarter starter,
                                     String runtimeInstanceId) {
        this(stateStore, starter, DEFAULT_START_WAIT_TIMEOUT_MS, DEFAULT_START_WAIT_INTERVAL_MS, runtimeInstanceId);
    }

    public PluginRuntimeStateService(PluginRuntimeStateStore stateStore,
                                     PluginRuntimeStarter starter,
                                     long startWaitTimeoutMs,
                                     long startWaitIntervalMs) {
        this(stateStore, starter, startWaitTimeoutMs, startWaitIntervalMs, PluginRuntimeInstances.currentInstanceId());
    }

    public PluginRuntimeStateService(PluginRuntimeStateStore stateStore,
                                     PluginRuntimeStarter starter,
                                     long startWaitTimeoutMs,
                                     long startWaitIntervalMs,
                                     String runtimeInstanceId) {
        this(stateStore, starter, startWaitTimeoutMs, startWaitIntervalMs, runtimeInstanceId,
                PluginRuntimeBridge.pluginStarts());
    }

    PluginRuntimeStateService(PluginRuntimeStateStore stateStore,
                              PluginRuntimeStarter starter,
                              long startWaitTimeoutMs,
                              long startWaitIntervalMs,
                              String runtimeInstanceId,
                              PluginStartCoordinator startCoordinator) {
        this.stateStore = stateStore;
        this.starter = starter;
        this.startWaitTimeoutMs = startWaitTimeoutMs;
        this.startWaitIntervalMs = startWaitIntervalMs;
        this.runtimeInstanceId = runtimeInstanceId;
        this.startCoordinator = startCoordinator;
    }

    public boolean ensureStarted(String pluginId) {
        if (isBlank(pluginId)) {
            return false;
        }
        Optional<PluginIdentity> identity = starter.findPlugin(pluginId);
        if (!identity.isPresent()) {
            return false;
        }
        return ensureStarted(identity.get());
    }

    public boolean ensureStarted(String pluginId, RuntimeDeadline deadline) {
        if (isBlank(pluginId) || deadline.remainingMillis() == 0) return false;
        Optional<PluginIdentity> identity = starter.findPlugin(pluginId);
        return identity.isPresent() && ensureStarted(identity.get(), deadline);
    }

    private boolean claimDemandIfReady(PluginIdentity identity, RuntimeDeadline deadline) {
        Boolean ready = startCoordinator.claimDemandAndGet(identity.getPluginId(), DEFAULT_DEMAND_CLAIM_MS,
                deadline.waitMillis(startWaitTimeoutMs), () -> readyAndViable(identity));
        return Boolean.TRUE.equals(ready);
    }

    public boolean ensureStarted(PluginIdentity identity) { return ensureStarted(identity, RuntimeDeadline.unlimited()); }

    public boolean ensureStarted(PluginIdentity identity, RuntimeDeadline deadline) {
        if (deadline.remainingMillis() == 0 || identity == null || isBlank(identity.getPluginId())) {
            return false;
        }
        if (claimDemandIfReady(identity, deadline)) {
            return true;
        }
        boolean started = startCoordinator.start(
                identity.getPluginId(),
                starter.maxConcurrentStarts(),
                startWaitTimeoutMs,
                startWaitTimeoutMs + startWaitTimeoutMs,
                starter.startFailureBackoffMs(), deadline,
                () -> startAndAwait(identity, deadline)
        );
        return started && claimDemandIfReady(identity, deadline);
    }

    private boolean startAndAwait(PluginIdentity identity, RuntimeDeadline executionDeadline) {
        executionDeadline.check();
        RuntimeDeadline startupDeadline = executionDeadline.reserveMillis(Math.min(5000, executionDeadline.remainingMillis() / 4));
        Optional<PluginIdentity> currentIdentity = starter.findPlugin(identity.getPluginId());
        if (!currentIdentity.isPresent()
                || !Objects.equals(identity.getPluginShortName(), currentIdentity.get().getPluginShortName())) {
            throw new PluginStartDeferredException("Plugin identity changed before startup");
        }
        if (readyAndViable(identity)) {
            return completeReady(identity);
        }
        if (startCoordinator.isStartCancellationRequested(identity.getPluginId())) {
            return false;
        }
        if (!starter.managesRuntimeState()) {
            markStarting(identity.getPluginId(), identity.getPluginName(), starter.runtimeMode(identity));
        }
        if (!starter.isStarted(identity.getPluginId())) {
            try {
                startWithOneCapacityReclaim(identity, startupDeadline);
            } catch (RuntimeDeadline.Expired expired) {
                return cleanupFailedStart(identity, "Plugin startup time budget exhausted", executionDeadline);
            } catch (PluginStartDeferredException e) {
                throw e;
            } catch (RuntimeException e) {
                if (starter.managesRuntimeState()) {
                    starter.cleanupStartFailure(identity, executionDeadline);
                } else {
                    markFailed(identity.getPluginId(), identity.getPluginName(), e.getMessage());
                }
                return false;
            }
        }
        RuntimeDeadline readinessDeadline = startupDeadline.capMillis(startWaitTimeoutMs);
        while (readinessDeadline.remainingMillis() > 0) {
            if (startCoordinator.isStartCancellationRequested(identity.getPluginId())) {
                return cleanupFailedStart(identity, "Plugin start cancelled", executionDeadline);
            }
            if (!starter.isStartViable(identity)) {
                return cleanupFailedStart(identity, "Plugin process exited during startup", executionDeadline);
            }
            if (readyAndViable(identity)) {
                return completeReady(identity);
            }
            if (!sleepQuietly(readinessDeadline.waitMillis(startWaitIntervalMs))) {
                return cleanupFailedStart(identity, "Plugin start interrupted", executionDeadline);
            }
        }
        if (startCoordinator.isStartCancellationRequested(identity.getPluginId())) {
            return cleanupFailedStart(identity, "Plugin start cancelled", executionDeadline);
        }
        if (readyAndViable(identity)) {
            return completeReady(identity);
        }
        return cleanupFailedStart(identity, starter.isStartViable(identity)
                ? "Plugin start timeout"
                : "Plugin process exited during startup", executionDeadline);
    }

    private boolean readyAndViable(PluginIdentity identity) {
        return starter.isReady(identity.getPluginId()) && starter.isStartViable(identity);
    }

    private boolean completeReady(PluginIdentity identity) {
        startCoordinator.claimDemand(identity.getPluginId(), DEFAULT_DEMAND_CLAIM_MS);
        if (!starter.managesRuntimeState()) {
            markReady(identity.getPluginId(), identity.getPluginName());
        }
        return true;
    }

    private boolean cleanupFailedStart(PluginIdentity identity, String message, RuntimeDeadline deadline) {
        if (starter.managesRuntimeState()) {
            starter.cleanupStartFailure(identity, deadline);
        } else {
            markFailed(identity.getPluginId(), identity.getPluginName(), message);
        }
        return false;
    }

    private void startWithOneCapacityReclaim(PluginIdentity identity, RuntimeDeadline deadline) {
        try {
            starter.start(identity, deadline);
        } catch (PluginStartDeferredException capacityFailure) {
            if (!starter.reclaimIdleCapacity(identity, deadline)) {
                throw capacityFailure;
            }
            starter.start(identity, deadline);
        }
    }

    public void markStarting(String pluginId, String pluginName, String runtimeMode) {
        markStarting(pluginId, pluginName, runtimeMode, null);
    }

    public void markStarting(String pluginId, String pluginName, String runtimeMode, Long processId) {
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            instance.setStatus(PluginStatus.STARTING.runtimeStatus());
            instance.setRuntimeMode(runtimeMode);
            if (processId != null) {
                instance.setProcessId(processId);
            }
            instance.setStartedAt(now);
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
            resetActiveInvocations(instance);
            instance.setLastError(null);
        });
    }

    public void markInitializing(String pluginId, String pluginName, String runtimeMode) {
        markInitializing(pluginId, pluginName, runtimeMode, null);
    }

    public void markInitializing(String pluginId, String pluginName, String runtimeMode, Long processId) {
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            instance.setStatus(PluginStatus.INITIALIZING.runtimeStatus());
            if (!isBlank(runtimeMode)) {
                instance.setRuntimeMode(runtimeMode);
            }
            if (processId != null) {
                instance.setProcessId(processId);
            }
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
            instance.setLastError(null);
        });
    }

    public void markReady(String pluginId, String pluginName) {
        markReady(pluginId, pluginName, null);
    }

    public void markReady(String pluginId, String pluginName, Long processId) {
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            instance.setStatus(PluginStatus.READY.runtimeStatus());
            if (processId != null) {
                instance.setProcessId(processId);
            }
            instance.setReadyAt(now);
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
            resetActiveInvocations(instance);
            instance.setLastError(null);
        });
    }

    public void markStopping(String pluginId, String pluginName) {
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            instance.setStatus(PluginStatus.STOPPING.runtimeStatus());
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
        });
    }

    public void markInvocationStart(String pluginId, String pluginName) {
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            normalizeInvocationLifecycleStatus(instance);
            instance.setActiveInvocationCount(activeCount(instance) + 1);
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
        });
    }

    public void markInvocationStart(String pluginId, String pluginName, String invocationId) {
        if (isBlank(invocationId)) {
            markInvocationStart(pluginId, pluginName);
            return;
        }
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            normalizeInvocationLifecycleStatus(instance);
            Set<String> invocationIds = activeInvocationIds(instance);
            if (!invocationIds.contains(invocationId)
                    && invocationIds.size() >= MAX_ACTIVE_INVOCATION_IDS) {
                throw new IllegalStateException("Plugin invocation tracking capacity reached");
            }
            if (invocationIds.add(invocationId)) {
                instance.setActiveInvocationCount(activeCount(instance) + 1);
            }
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
        });
    }

    public void markInvocationEnd(String pluginId, String pluginName, String errorMessage) {
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            int activeCount = Math.max(activeCount(instance) - 1, 0);
            instance.setActiveInvocationCount(activeCount);
            normalizeInvocationLifecycleStatus(instance);
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
            if (errorMessage != null && !errorMessage.trim().isEmpty()) {
                instance.setLastError(errorMessage);
            }
        });
    }

    public void markInvocationEnd(String pluginId,
                                  String pluginName,
                                  String invocationId,
                                  String errorMessage) {
        if (isBlank(invocationId)) {
            markInvocationEnd(pluginId, pluginName, errorMessage);
            return;
        }
        // A stopped/replaced instance must not be recreated by a late invocation completion.
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, pluginName)) {
            stateStore.update(pluginId, state -> {
                if (state.getInstances() == null) return;
                for (PluginRuntimeInstanceState instance : state.getInstances()) {
                    if (!Objects.equals(runtimeInstanceId, instance.getInstanceId())) continue;
                    Set<String> ids = instance.getActiveInvocationIds();
                    if (ids == null || !ids.remove(invocationId)) return;
                    instance.setActiveInvocationCount(Math.max(activeCount(instance) - 1, 0));
                    normalizeInvocationLifecycleStatus(instance);
                    long now = now();
                    instance.setLastActiveAt(now);
                    PluginRuntimeLeases.renew(instance, now);
                    if (errorMessage != null && !errorMessage.trim().isEmpty()) instance.setLastError(errorMessage);
                    PluginRuntimeStateAggregator.aggregate(state);
                    return;
                }
            });
        }
    }

    public void markInvocationEndWithRetry(String pluginId,
                                           String pluginName,
                                           String invocationId,
                                           String errorMessage) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < INVOCATION_END_ATTEMPTS; attempt++) {
            try {
                markInvocationEnd(pluginId, pluginName, invocationId, errorMessage);
                return;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        throw failure;
    }

    public void markFailed(String pluginId, String pluginName, String errorMessage) {
        update(pluginId, pluginName, (state, instance) -> {
            long now = now();
            instance.setStatus(PluginStatus.FAILED.runtimeStatus());
            instance.setLastActiveAt(now);
            PluginRuntimeLeases.renew(instance, now);
            resetActiveInvocations(instance);
            instance.setLastError(errorMessage);
        });
    }

    public void markStopped(String pluginId, String pluginName) {
        update(pluginId, pluginName, (state, instance) -> {
            removeCurrentInstance(state);
            state.setStatus(PluginStatus.STOPPED.runtimeStatus());
            state.setActiveInvocationCount(0);
            state.setLastActiveAt(now());
        });
    }

    private void update(String pluginId, String pluginName, BiConsumer<PluginRuntimeState, PluginRuntimeInstanceState> consumer) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, pluginName)) {
            stateStore.update(pluginId, state -> {
                initializeState(state, pluginId, pluginName);
                PluginRuntimeInstanceState instance = currentInstance(state);
                initializeInstance(instance);
                consumer.accept(state, instance);
                PluginRuntimeStateAggregator.aggregate(state);
            });
        }
    }

    private void initializeState(PluginRuntimeState state, String pluginId, String pluginName) {
        state.setPluginId(pluginId);
        if (pluginName != null && !pluginName.trim().isEmpty()) {
            state.setPluginName(pluginName);
        }
        if (state.getRuntimeMode() == null) {
            state.setRuntimeMode("process");
        }
        if (state.getActiveInvocationCount() == null) {
            state.setActiveInvocationCount(0);
        }
        if (state.getInstances() == null) {
            state.setInstances(new ArrayList<>());
        }
    }

    private PluginRuntimeInstanceState currentInstance(PluginRuntimeState state) {
        for (PluginRuntimeInstanceState instance : state.getInstances()) {
            if (Objects.equals(runtimeInstanceId, instance.getInstanceId())) {
                return instance;
            }
        }
        PluginRuntimeInstanceState instance = new PluginRuntimeInstanceState();
        instance.setInstanceId(runtimeInstanceId);
        state.getInstances().add(instance);
        return instance;
    }

    private void initializeInstance(PluginRuntimeInstanceState instance) {
        if (instance.getOwnerId() == null) {
            instance.setOwnerId(PluginRuntimeInstances.currentInstanceId());
        }
        if (instance.getRuntimeMode() == null) {
            instance.setRuntimeMode("process");
        }
        if (instance.getActiveInvocationCount() == null) {
            instance.setActiveInvocationCount(0);
        }
    }

    private int activeCount(PluginRuntimeInstanceState instance) {
        return instance.getActiveInvocationCount() == null ? 0 : instance.getActiveInvocationCount();
    }

    private Set<String> activeInvocationIds(PluginRuntimeInstanceState instance) {
        Set<String> invocationIds = instance.getActiveInvocationIds();
        if (invocationIds == null) {
            invocationIds = new LinkedHashSet<String>();
            instance.setActiveInvocationIds(invocationIds);
        }
        return invocationIds;
    }

    private void resetActiveInvocations(PluginRuntimeInstanceState instance) {
        instance.setActiveInvocationCount(0);
        instance.setActiveInvocationIds(new LinkedHashSet<String>());
    }

    private void normalizeInvocationLifecycleStatus(PluginRuntimeInstanceState instance) {
        if (isBlank(instance.getStatus()) || PluginStatus.EXECUTING.runtimeStatus().equals(instance.getStatus())) {
            instance.setStatus(PluginStatus.READY.runtimeStatus());
        }
    }

    private void removeCurrentInstance(PluginRuntimeState state) {
        state.getInstances().removeIf(instance -> Objects.equals(runtimeInstanceId, instance.getInstanceId()));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private boolean sleepQuietly(long waitMillis) {
        try {
            Thread.sleep(Math.max(1, waitMillis));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

}
