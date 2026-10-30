package com.zrlog.plugincore.server.runtime.event;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RuntimeEvents;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Request-scoped cache refresh. The caller owns its durable run and never retries an uncertain dispatch. */
public final class HostCacheRefreshRuntime {
    private final RuntimeEventRuntime events;
    private final Supplier<List<IOSession>> sessions;
    private final InvocationLogStore logs;

    public HostCacheRefreshRuntime(RuntimeEventRuntime events, Supplier<List<IOSession>> sessions, InvocationLogStore logs) {
        this.events = events;
        this.sessions = sessions;
        this.logs = logs;
    }

    public HostCacheRefreshResult refresh(String triggerId, int actorId, String cacheVersion, long budgetMillis) {
        if (triggerId == null || !triggerId.matches("[a-zA-Z0-9_.-]{1,128}") || actorId < 1
                || cacheVersion == null || !cacheVersion.matches("[a-zA-Z0-9_.-]{1,128}")
                || budgetMillis < 1000 || budgetMillis > 645000)
            throw new IllegalArgumentException("Invalid host cache refresh request");
        RuntimeDeadline deadline = RuntimeDeadline.afterMillis(budgetMillis);
        long started = System.currentTimeMillis();
        HostCacheRefreshResult result = new HostCacheRefreshResult();
        result.triggerId = triggerId;
        result.cacheVersion = cacheVersion;
        // Default lifecycle-aware SDK handlers have no legacy refresh callback. Custom legacy handlers
        // may still do work after a notification, so they must migrate to a declared event handler.
        for (IOSession session : sessions.get()) {
            if (session.getPlugin() == null || session.getPlugin().getCapabilityLifecycleVersion() < 1)
                result.unsupportedCount++;
        }
        if (result.unsupportedCount == 0) {
            RuntimeEventPublishResult published = events.publishWithinBudget(request(triggerId, cacheVersion),
                    String.valueOf(actorId), deadline);
            result.successCount = published.getSuccessCount();
            result.failedCount = published.getFailedCount();
            result.outcomeUncertain = published.isOutcomeUncertain();
            result.completed = !result.outcomeUncertain && result.failedCount == 0;
        }
        CapabilityInvocationLog log = new CapabilityInvocationLog();
        log.setId(java.util.UUID.randomUUID().toString());
        log.setPluginId("__system__");
        log.setCapabilityKey(RuntimeEvents.REFRESH_CACHE);
        log.setSource(RuntimeEventRuntime.SOURCE);
        log.setRequestId(triggerId);
        log.setTraceId(triggerId);
        log.setAuditRequired(true);
        log.setStartedAt(started);
        log.setFinishedAt(System.currentTimeMillis());
        log.setDurationMs(log.getFinishedAt() - started);
        log.setStatus(result.outcomeUncertain ? "uncertain" : result.completed ? "success" : "error");
        if (!result.completed) log.setErrorMessage(result.unsupportedCount > 0
                ? "Legacy cache callbacks require a lifecycle-aware event handler"
                : "Cache refresh handlers did not all complete successfully");
        logs.append(log);
        return result;
    }

    private RuntimeEventRequest request(String triggerId, String cacheVersion) {
        RuntimeEventRequest event = new RuntimeEventRequest();
        event.setEventType(RuntimeEvents.REFRESH_CACHE);
        event.setAliases(Arrays.asList(RuntimeEvents.LEGACY_REFRESH_CACHE, ActionType.REFRESH_CACHE.name()));
        event.setSource("host");
        event.setRequestId(triggerId);
        event.setTraceId(triggerId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionType", ActionType.REFRESH_CACHE.name());
        payload.put("cacheVersion", cacheVersion);
        event.setPayload(payload);
        return event;
    }
}
