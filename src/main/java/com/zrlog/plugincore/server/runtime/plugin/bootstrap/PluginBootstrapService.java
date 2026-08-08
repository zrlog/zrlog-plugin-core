package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.lifecycle.PluginLifecycleService;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginBootstrapService {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginBootstrapService.class);
    static final int BOOTSTRAP_QUEUE_CAPACITY = 1;

    private final Map<String, String> requiredPlugins;
    private final Object bootstrapLock = new Object();
    private final AtomicBoolean bootstrapRunning = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final ExecutorService bootstrapExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(BOOTSTRAP_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "zrlog-plugin-bootstrap");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private volatile CompletableFuture<Void> bootstrapFuture = CompletableFuture.completedFuture(null);
    private final PluginMetadataBootstrapper metadataBootstrapper;
    private final PluginStartupCoordinator pluginStartupCoordinator;
    private final PluginLifecycleService lifecycleService;

    public PluginBootstrapService(Map<String, String> requiredPlugins,
                                  PluginStartupCoordinator pluginStartupCoordinator,
                                  PluginMetadataBootstrapper metadataBootstrapper,
                                  PluginLifecycleService lifecycleService) {
        this.requiredPlugins = requiredPlugins;
        this.pluginStartupCoordinator = pluginStartupCoordinator;
        this.metadataBootstrapper = metadataBootstrapper;
        this.lifecycleService = lifecycleService;
    }

    public void verifyPluginCoreReadable() {
        PluginCoreDAO.getInstance().loadSnapshot();
    }

    public void loadPlugins() {
        if (shutdown.get()) {
            return;
        }
        try {
            PluginCore currentPluginCore = PluginCoreDAO.getInstance().loadSnapshot();
            if (shutdown.get()) {
                return;
            }
            PluginRuntimeStates.reconcileRuntimeStates();
            if (shutdown.get()) {
                return;
            }
            if (!currentPluginCore.getSetting().getRuntime().getOnDemandEnabled()) {
                pluginStartupCoordinator.startRunnablePlugins();
            } else {
                pluginStartupCoordinator.prepare();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "start plugin exception ", e);
        }
    }

    public void loadPluginsAsync() {
        CompletableFuture<Void> currentFuture;
        synchronized (bootstrapLock) {
            if (shutdown.get() || bootstrapRunning.get()) {
                return;
            }
            currentFuture = new CompletableFuture<>();
            bootstrapFuture = currentFuture;
            bootstrapRunning.set(true);
        }
        Runnable bootstrapTask = () -> {
            try {
                loadPlugins();
            } catch (RuntimeException e) {
                currentFuture.completeExceptionally(e);
                throw e;
            } catch (Error e) {
                currentFuture.completeExceptionally(e);
                throw e;
            } finally {
                synchronized (bootstrapLock) {
                    bootstrapRunning.set(false);
                    if (!currentFuture.isDone()) {
                        currentFuture.complete(null);
                    }
                }
            }
        };
        try {
            bootstrapExecutor.execute(bootstrapTask);
        } catch (RuntimeException | Error e) {
            synchronized (bootstrapLock) {
                bootstrapRunning.set(false);
                currentFuture.completeExceptionally(e);
            }
            if (shutdown.get()) {
                return;
            }
            throw e;
        }
    }

    public void shutdown() {
        CompletableFuture<Void> currentFuture;
        synchronized (bootstrapLock) {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            bootstrapRunning.set(false);
            currentFuture = bootstrapFuture;
            if (currentFuture != null && !currentFuture.isDone()) {
                currentFuture.cancel(true);
            }
        }
        bootstrapExecutor.shutdownNow();
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public boolean awaitCurrentBootstrap() {
        CompletableFuture<Void> currentFuture = bootstrapFuture;
        if (currentFuture == null) {
            return true;
        }
        if (currentFuture.isCancelled() || currentFuture.isCompletedExceptionally()) {
            return false;
        }
        if (currentFuture.isDone()) {
            return true;
        }
        try {
            currentFuture.get();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            LOGGER.log(Level.WARNING, "plugin metadata bootstrap failed", e);
            return false;
        } catch (CancellationException e) {
            return false;
        }
    }

    public boolean isBootstrapRunning() {
        return bootstrapRunning.get();
    }

    public boolean isCurrentBootstrapReady() {
        CompletableFuture<Void> currentFuture = bootstrapFuture;
        return currentFuture != null
                && currentFuture.isDone()
                && !currentFuture.isCancelled()
                && !currentFuture.isCompletedExceptionally();
    }

    public Map<String, String> getRequiredPlugins() {
        return requiredPlugins;
    }

    public void loadPlugin(File pluginFile, String pluginId) {
        if (shutdown.get()) {
            return;
        }
        pluginStartupCoordinator.loadPlugin(pluginFile, pluginId);
    }

    public boolean startPluginFileForMetadata(File pluginFile) {
        return !shutdown.get() && metadataBootstrapper.startPluginFileForMetadata(pluginFile);
    }

    boolean startPluginFileForMetadata(File pluginFile, String pluginId) {
        return !shutdown.get() && metadataBootstrapper.startPluginFileForMetadata(pluginFile, pluginId);
    }

    public boolean shouldStartPluginFileForMetadata(File pluginFile, String pluginId) {
        return metadataBootstrapper.shouldStartPluginFileForMetadata(pluginFile, pluginId);
    }

    boolean shouldStartPluginFileForMetadata(File pluginFile, String pluginId, PluginCore pluginCore) {
        return metadataBootstrapper.shouldStartPluginFileForMetadata(pluginFile, pluginId, pluginCore);
    }

    public void registerPlugin(IOSession session) {
        if (shutdown.get()) {
            if (session != null) {
                session.close();
            }
            return;
        }
        lifecycleService.registerPlugin(session);
    }

    public boolean hasManagedProcessSlot(Plugin plugin) {
        return plugin != null && pluginStartupCoordinator.hasManagedProcessSlot(plugin.getId(), plugin.getShortName());
    }

    public boolean hasManagedProcessSlot(String pluginId, String pluginShortName) {
        return pluginStartupCoordinator.hasManagedProcessSlot(pluginId, pluginShortName);
    }

    public boolean isManagedProcessStartViable(String pluginId, String pluginShortName) {
        return pluginStartupCoordinator.isManagedProcessStartViable(pluginId, pluginShortName);
    }

    public void unregisterPluginSession(IOSession session) {
        lifecycleService.unregisterPluginSession(session);
    }

    public void markPluginReady(IOSession session) {
        lifecycleService.markPluginReady(session);
    }

    public boolean stopPlugin(String pluginShortName) {
        return lifecycleService.stopPlugin(pluginShortName);
    }

    public boolean stopPlugin(String pluginId, String pluginShortName) {
        return pluginStartupCoordinator.destroyByPluginId(pluginId, pluginShortName);
    }

    public boolean deletePlugin(String pluginShortName) {
        return lifecycleService.deletePlugin(pluginShortName);
    }

    public File downloadAndStartPlugin(String fileName) throws Exception {
        if (shutdown.get()) {
            throw new IllegalStateException("Plugin runtime is shutting down");
        }
        return metadataBootstrapper.downloadAndStartPlugin(fileName);
    }

    public boolean allRunning() {
        return pluginStartupCoordinator.getAllRunnablePluginIds().stream().allMatch(PluginSessions::isRunningByPluginId);
    }
}
