package com.zrlog.plugincore.server.runtime.plugin.session;

import com.hibegin.common.util.LoggerUtil;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.common.PluginVersionUtils;
import com.zrlog.plugin.common.type.PluginVersion;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;

import java.nio.channels.Channel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PluginSessionHeartbeat {

    static final String LAST_PING_AT_ATTR = "_zrlog_last_ping_at";
    static final String LAST_HEARTBEAT_AT_ATTR = "_zrlog_last_heartbeat_at";
    static final String IN_FLIGHT_PING_ID_ATTR = "_zrlog_heartbeat_ping_id";
    static final long HEARTBEAT_INTERVAL_MS = 15 * 1000L;
    static final long HEARTBEAT_TIMEOUT_MS = 3 * 1000L;
    static final long HEARTBEAT_FRESH_MS = 10 * 1000L;
    static final long HEARTBEAT_EXPIRE_MS = HEARTBEAT_INTERVAL_MS * 2 + HEARTBEAT_TIMEOUT_MS;

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginSessionHeartbeat.class);

    private final Supplier<List<IOSession>> sessionSupplier;
    private final Consumer<IOSession> staleSessionHandler;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final boolean enabled;

    static PluginSessionHeartbeat active(Supplier<List<IOSession>> sessionSupplier,
                                         Consumer<IOSession> staleSessionHandler) {
        return new PluginSessionHeartbeat(sessionSupplier, staleSessionHandler,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "zrlog-plugin-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }), true);
    }

    static PluginSessionHeartbeat disabled() {
        return new PluginSessionHeartbeat(null, null, null, false);
    }

    private PluginSessionHeartbeat(Supplier<List<IOSession>> sessionSupplier,
                                   Consumer<IOSession> staleSessionHandler,
                                   ScheduledExecutorService executor,
                                   boolean enabled) {
        this.sessionSupplier = sessionSupplier;
        this.staleSessionHandler = staleSessionHandler;
        this.executor = executor;
        this.enabled = enabled;
    }

    void start() {
        if (!enabled || shutdown.get() || !started.compareAndSet(false, true)) {
            return;
        }
        synchronized (shutdown) {
            if (shutdown.get()) {
                return;
            }
            executor.scheduleWithFixedDelay(this::pingSessions,
                    HEARTBEAT_INTERVAL_MS,
                    HEARTBEAT_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    void register(IOSession session) {
        if (shutdown.get()) {
            return;
        }
        markHeartbeat(session, System.currentTimeMillis());
    }

    void shutdown() {
        if (!enabled) {
            shutdown.set(true);
            return;
        }
        synchronized (shutdown) {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            executor.shutdownNow();
        }
    }

    boolean hasRecentHeartbeat(IOSession session, long nowMs) {
        Object lastHeartbeatAt = session.getSystemAttr().get(LAST_HEARTBEAT_AT_ATTR);
        return lastHeartbeatAt instanceof Number
                && nowMs - ((Number) lastHeartbeatAt).longValue() < HEARTBEAT_EXPIRE_MS;
    }

    boolean ensureRecentHeartbeat(IOSession session, long nowMs) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (shutdown.get()) {
                return false;
            }
            if (!hasRecentHeartbeat(session, nowMs)) {
                return false;
            }
            if (!enabled) {
                return true;
            }
            if (hasFreshHeartbeat(session, nowMs)) {
                return true;
            }
            synchronized (session) {
                long now = System.currentTimeMillis();
                if (hasFreshHeartbeat(session, now)) {
                    return true;
                }
                return pingAndWait(session, now);
            }
        }
    }

    private void pingSessions() {
        if (shutdown.get()) {
            return;
        }
        try {
            for (IOSession session : sessionSupplier.get()) {
                pingSession(session, System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "plugin heartbeat scan error", e);
        }
    }

    private void pingSession(IOSession session, long nowMs) {
        if (session == null) {
            return;
        }
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            try {
                if (!isSessionOpen(session)) {
                    closeStaleSession(session);
                    return;
                }
                if (!hasRecentHeartbeat(session, nowMs)) {
                    closeStaleSession(session);
                    return;
                }
                if (!shouldSendPing(session, nowMs)) {
                    return;
                }
                if (!PluginVersionUtils.isUpper(session.getPlugin(), PluginVersion.V4)) {
                    return;
                }
                sendPing(session, nowMs, true);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, PluginLogContext.prefix("plugin heartbeat error"), e);
            }
        }
    }

    private boolean shouldSendPing(IOSession session, long nowMs) {
        Object inFlightPingId = session.getSystemAttr().get(IN_FLIGHT_PING_ID_ATTR);
        Object lastPingAt = session.getSystemAttr().get(LAST_PING_AT_ATTR);
        if (!(lastPingAt instanceof Number)) {
            return true;
        }
        long pingAgeMs = nowMs - ((Number) lastPingAt).longValue();
        if (inFlightPingId != null && pingAgeMs < HEARTBEAT_TIMEOUT_MS) {
            return false;
        }
        return pingAgeMs >= HEARTBEAT_INTERVAL_MS || inFlightPingId != null;
    }

    private boolean hasFreshHeartbeat(IOSession session, long nowMs) {
        Object lastHeartbeatAt = session.getSystemAttr().get(LAST_HEARTBEAT_AT_ATTR);
        return lastHeartbeatAt instanceof Number
                && nowMs - ((Number) lastHeartbeatAt).longValue() < HEARTBEAT_FRESH_MS;
    }

    private boolean pingAndWait(IOSession session, long nowMs) {
        if (!PluginVersionUtils.isUpper(session.getPlugin(), PluginVersion.V4)) {
            return true;
        }
        int msgId = sendPing(session, nowMs, false);
        try (ResponseLease responseLease = session.getResponseLeaseByMsgId(msgId,
                Duration.ofMillis(HEARTBEAT_TIMEOUT_MS))) {
            MsgPacket response = responseLease == null ? null : responseLease.getPacket();
            if (isPingResponse(response)) {
                markHeartbeat(session, System.currentTimeMillis());
                return true;
            }
            return false;
        }
    }

    private int sendPing(IOSession session, long nowMs, boolean async) {
        int msgId = IdUtil.getInt();
        session.getSystemAttr().put(LAST_PING_AT_ATTR, nowMs);
        session.getSystemAttr().put(IN_FLIGHT_PING_ID_ATTR, msgId);
        MsgPacket msgPacket = new MsgPacket(new byte[0],
                ContentType.BYTE,
                MsgPacketStatus.SEND_REQUEST,
                msgId,
                ActionType.HTTP_METHOD.name());
        if (!async) {
            session.sendMsg(msgPacket, null, Duration.ofMillis(HEARTBEAT_TIMEOUT_MS));
            return msgId;
        }
        session.sendMsg(msgPacket, response -> {
            try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
                if (isPingResponse(response)) {
                    markHeartbeat(session, System.currentTimeMillis());
                }
            }
        }, Duration.ofMillis(HEARTBEAT_TIMEOUT_MS));
        return msgId;
    }

    private boolean isPingResponse(MsgPacket response) {
        return response != null
                && ActionType.HTTP_METHOD.name().equals(response.getMethodStr());
    }

    private void markHeartbeat(IOSession session, long nowMs) {
        if (session == null) {
            return;
        }
        session.getSystemAttr().put(LAST_HEARTBEAT_AT_ATTR, nowMs);
        session.getSystemAttr().remove(IN_FLIGHT_PING_ID_ATTR);
    }

    private boolean isSessionOpen(IOSession session) {
        Object channel = session.getSystemAttr().get("_channel");
        return channel instanceof Channel && ((Channel) channel).isOpen();
    }

    private void closeStaleSession(IOSession session) {
        if (staleSessionHandler != null) {
            staleSessionHandler.accept(session);
        }
    }
}
