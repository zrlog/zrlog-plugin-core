package com.zrlog.plugincore.server.runtime.scheduler;

import com.hibegin.common.util.EnvKit;
import com.hibegin.common.util.LoggerUtil;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.RuntimeCapabilityInvokerFactory;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InternalSchedulerRunner {

    private static final long DEFAULT_TICK_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final Logger LOGGER = LoggerUtil.getLogger(InternalSchedulerRunner.class);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN = new AtomicBoolean(false);
    private static final AtomicBoolean TICKING = new AtomicBoolean(false);
    private static ScheduledExecutorService executorService;

    public static synchronized void start() {
        if (SHUTDOWN.get() || !STARTED.compareAndSet(false, true)) {
            return;
        }
        if (EnvKit.isFaaSMode()) {
            LOGGER.info("Faas mode, skip internal scheduler");
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zrlog-plugin-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executorService.execute(InternalSchedulerRunner::safeTick);
        long initialDelayMillis = millisUntilNextTick(ZonedDateTime.now());
        executorService.scheduleAtFixedRate(InternalSchedulerRunner::safeTick,
                initialDelayMillis, DEFAULT_TICK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        LOGGER.info("internal scheduler started, tickIntervalMillis=" + DEFAULT_TICK_INTERVAL_MILLIS
                + ", firstAlignedDelayMillis=" + initialDelayMillis);
    }

    public static synchronized void shutdown() {
        if (!SHUTDOWN.compareAndSet(false, true)) {
            return;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    static long millisUntilNextTick(ZonedDateTime now) {
        ZonedDateTime nextMinute = now.plusMinutes(1).withSecond(0).withNano(0);
        return Math.max(1L, Duration.between(now, nextMinute).toMillis());
    }

    private static void safeTick() {
        if (SHUTDOWN.get() || !TICKING.compareAndSet(false, true)) {
            return;
        }
        try {
            SchedulerTickResult result = tickOnce();
            if (result.getExecutedCount() > 0 || result.getFailedCount() > 0) {
                LOGGER.info("scheduler tick executed=" + result.getExecutedCount() + ", failed=" + result.getFailedCount());
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "scheduler tick error", e);
        } finally {
            TICKING.set(false);
        }
    }

    static SchedulerTickResult tickOnce() {
        WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        SchedulerRuntime schedulerRuntime = new SchedulerRuntime(
                new AutomationStore(kvStore),
                new AutomationRunStore(kvStore),
                new CapabilityStore(kvStore),
                RuntimeCapabilityInvokerFactory.socket(kvStore, pluginCore),
                new BasicCronParser(),
                pluginCore
        );
        return new SchedulerTickService(pluginCore.getSetting().getScheduler(), schedulerRuntime)
                .tick(ZonedDateTime.now());
    }
}
