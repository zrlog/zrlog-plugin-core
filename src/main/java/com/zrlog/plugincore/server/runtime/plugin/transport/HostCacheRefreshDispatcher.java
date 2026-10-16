package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.zrlog.plugin.common.LoggerUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Keeps host refresh callbacks off the socket workers and coalesces pending refreshes. */
final class HostCacheRefreshDispatcher implements AutoCloseable {

    private static final Logger LOGGER = LoggerUtil.getLogger(HostCacheRefreshDispatcher.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "zrlog-plugin-host-cache-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable refresh;
    private boolean running;
    private boolean pending;
    private boolean closed;

    HostCacheRefreshDispatcher(Runnable refresh) {
        this.refresh = refresh;
    }

    synchronized void request() {
        if (closed) {
            return;
        }
        pending = true;
        if (running) {
            return;
        }
        running = true;
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            running = false;
            pending = false;
        }
    }

    private void drain() {
        while (true) {
            synchronized (this) {
                if (closed || !pending) {
                    running = false;
                    return;
                }
                pending = false;
            }
            try {
                refresh.run();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Refresh host cache failed", e);
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending = false;
        executor.shutdownNow();
    }
}
