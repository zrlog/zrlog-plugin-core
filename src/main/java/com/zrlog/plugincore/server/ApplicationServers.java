package com.zrlog.plugincore.server;

import com.zrlog.plugincore.server.runtime.PluginCoreRunMode;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.plugin.PluginRuntimeServer;
import com.zrlog.plugincore.server.runtime.plugin.transport.PluginNioServer;
import com.zrlog.plugincore.server.runtime.scheduler.InternalSchedulerRunner;
import com.zrlog.plugincore.server.web.PluginHttpServer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

class ApplicationServers {

    private final Function<PluginRuntimeServices, PluginRuntimeServer> runtimeServerFactory;
    private final Supplier<PluginHttpServer> httpServerFactory;
    private final ShutdownHookRegistrar shutdownHookRegistrar;
    private final AtomicReference<RunningServers> runningServers = new AtomicReference<>();
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    ApplicationServers() {
        this(services -> new PluginRuntimeServer(
                        new PluginNioServer(services.pluginConfig(), services.pluginBootstrap()),
                        services.pluginBootstrap(),
                        InternalSchedulerRunner::start,
                        InternalSchedulerRunner::shutdown,
                        services::beginShutdown,
                        services::shutdown),
                PluginHttpServer::new,
                Runtime.getRuntime()::addShutdownHook);
    }

    ApplicationServers(Function<PluginRuntimeServices, PluginRuntimeServer> runtimeServerFactory,
                       Supplier<PluginHttpServer> httpServerFactory) {
        this(runtimeServerFactory, httpServerFactory, ignored -> {
        });
    }

    ApplicationServers(Function<PluginRuntimeServices, PluginRuntimeServer> runtimeServerFactory,
                       Supplier<PluginHttpServer> httpServerFactory,
                       ShutdownHookRegistrar shutdownHookRegistrar) {
        this.runtimeServerFactory = Objects.requireNonNull(runtimeServerFactory);
        this.httpServerFactory = Objects.requireNonNull(httpServerFactory);
        this.shutdownHookRegistrar = Objects.requireNonNull(shutdownHookRegistrar);
    }

    void start(Integer httpPort, PluginRuntimeServices services) {
        Objects.requireNonNull(services);
        RunningServers servers = new RunningServers(runtimeServerFactory.apply(services), httpServerFactory.get());
        if (!runningServers.compareAndSet(null, servers)) {
            throw new IllegalStateException("Plugin servers are already running");
        }
        String stopReason = "plugin servers stopped";
        try {
            registerShutdownHook();
            PluginRuntimeBridge.install(services);
            if (!servers.runtimeServer.start(PluginCoreRunMode.shouldBootstrapRuntimeWorkers())) {
                stopReason = "plugin runtime server failed";
                return;
            }
            if (!servers.httpServer.start(httpPort, PluginCoreRunMode.isNativeAgent(), services)) {
                throw new IllegalStateException("Unable to start plugin http server");
            }
            servers.httpServer.awaitStopped();
        } catch (RuntimeException | Error e) {
            stopReason = "plugin http server failed";
            throw e;
        } finally {
            try {
                servers.stop(stopReason);
            } finally {
                runningServers.compareAndSet(servers, null);
            }
        }
    }

    private void registerShutdownHook() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            shutdownHookRegistrar.register(new Thread(
                    () -> stop("jvm shutdown"), "zrlog-plugin-core-shutdown"));
        } catch (RuntimeException | Error e) {
            shutdownHookRegistered.set(false);
            throw e;
        }
    }

    void stop(String reason) {
        RunningServers servers = runningServers.getAndSet(null);
        if (servers != null) {
            servers.stop(reason);
        }
    }

    private static final class RunningServers {

        private final PluginRuntimeServer runtimeServer;
        private final PluginHttpServer httpServer;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private RunningServers(PluginRuntimeServer runtimeServer, PluginHttpServer httpServer) {
            this.runtimeServer = Objects.requireNonNull(runtimeServer);
            this.httpServer = Objects.requireNonNull(httpServer);
        }

        private void stop(String reason) {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            try {
                httpServer.stop(reason);
            } finally {
                runtimeServer.stop(reason);
            }
        }
    }
}

@FunctionalInterface
interface ShutdownHookRegistrar {

    void register(Thread shutdownHook);
}
