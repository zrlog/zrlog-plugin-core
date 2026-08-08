package com.zrlog.plugincore.server.web;

import com.hibegin.http.server.SimpleWebServer;
import com.hibegin.http.server.WebServerBuilder;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.web.config.PluginHttpServerConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class PluginHttpServer {

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile PluginHttpServerConfig config;
    private volatile SimpleWebServer webServer;

    public boolean start(Integer serverPort, boolean nativeAgent, PluginRuntimeServices services) {
        if (stopRequested.get()) {
            return false;
        }
        PluginHttpServerConfig currentConfig = new PluginHttpServerConfig(serverPort, services);
        config = currentConfig;
        WebServerBuilder build = new WebServerBuilder.Builder().config(currentConfig).build();
        if (nativeAgent) {
            currentConfig.getServerConfig().addCreateSuccessHandle(() -> {
                Thread.sleep(5000);
                System.exit(0);
                return null;
            });
        }
        SimpleWebServer startedServer = build.startInBackground(runnable -> {
            Thread thread = new Thread(() -> {
                try {
                    runnable.run();
                } finally {
                    stopped.countDown();
                }
            }, "zrlog-plugin-http-server");
            thread.setDaemon(false);
            return thread;
        });
        if (startedServer == null) {
            shutdownExecutors(currentConfig);
            stopped.countDown();
            return false;
        }
        webServer = startedServer;
        if (stopRequested.get()) {
            try {
                stopServer(startedServer, "plugin http server stopped during startup");
            } finally {
                shutdownExecutors(currentConfig);
            }
            return false;
        }
        return true;
    }

    public void awaitStopped() {
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop(String reason) {
        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            stopServer(webServer, reason);
        } finally {
            shutdownExecutors(config);
            stopped.countDown();
        }
    }

    private void stopServer(SimpleWebServer server, String reason) {
        if (server != null) {
            server.destroy(reason);
        }
    }

    private void shutdownExecutors(PluginHttpServerConfig currentConfig) {
        if (currentConfig == null) {
            return;
        }
        Executor requestExecutor = currentConfig.getServerConfig().getRequestExecutor();
        Executor decodeExecutor = currentConfig.getServerConfig().getDecodeExecutor();
        shutdownExecutor(requestExecutor);
        if (decodeExecutor != requestExecutor) {
            shutdownExecutor(decodeExecutor);
        }
        if (currentConfig.getServerConfig().getRequestCheckerExecutor() != null) {
            currentConfig.getServerConfig().getRequestCheckerExecutor().shutdownNow();
        }
    }

    private void shutdownExecutor(Executor executor) {
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
    }
}
