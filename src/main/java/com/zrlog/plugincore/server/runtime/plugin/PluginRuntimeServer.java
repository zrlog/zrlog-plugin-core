package com.zrlog.plugincore.server.runtime.plugin;

import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.transport.PluginNioServer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class PluginRuntimeServer {

    private final PluginNioServer nioServer;
    private final PluginBootstrapService pluginBootstrap;
    private final Runnable schedulerStarter;
    private final Runnable schedulerStopper;
    private final Runnable runtimeShutdownStarter;
    private final Runnable runtimeStopper;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public PluginRuntimeServer(PluginNioServer nioServer,
                               PluginBootstrapService pluginBootstrap,
                               Runnable schedulerStarter) {
        this(nioServer, pluginBootstrap, schedulerStarter, () -> {
        }, () -> {
        }, () -> {
        });
    }

    public PluginRuntimeServer(PluginNioServer nioServer,
                               PluginBootstrapService pluginBootstrap,
                               Runnable schedulerStarter,
                               Runnable schedulerStopper,
                               Runnable runtimeShutdownStarter,
                               Runnable runtimeStopper) {
        this.nioServer = Objects.requireNonNull(nioServer);
        this.pluginBootstrap = Objects.requireNonNull(pluginBootstrap);
        this.schedulerStarter = Objects.requireNonNull(schedulerStarter);
        this.schedulerStopper = Objects.requireNonNull(schedulerStopper);
        this.runtimeShutdownStarter = Objects.requireNonNull(runtimeShutdownStarter);
        this.runtimeStopper = Objects.requireNonNull(runtimeStopper);
    }

    public boolean start(boolean bootstrapRuntimeWorkers) {
        if (stopped.get()) {
            return false;
        }
        if (!started.compareAndSet(false, true)) {
            return !stopped.get();
        }
        try {
            if (bootstrapRuntimeWorkers) {
                pluginBootstrap.verifyPluginCoreReadable();
            }
            if (stopped.get() || !nioServer.start()) {
                stop("plugin socket server failed");
                return false;
            }
            if (bootstrapRuntimeWorkers) {
                pluginBootstrap.loadPluginsAsync();
                schedulerStarter.run();
            }
            return !stopped.get();
        } catch (RuntimeException | Error e) {
            try {
                stop("plugin bootstrap failed");
            } catch (RuntimeException | Error stopFailure) {
                e.addSuppressed(stopFailure);
            }
            throw e;
        }
    }

    public void stop(String reason) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            schedulerStopper.run();
        } finally {
            try {
                runtimeShutdownStarter.run();
            } finally {
                try {
                    nioServer.stop(reason);
                } finally {
                    runtimeStopper.run();
                }
            }
        }
    }
}
