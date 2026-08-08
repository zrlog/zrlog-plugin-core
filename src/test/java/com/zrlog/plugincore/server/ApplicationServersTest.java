package com.zrlog.plugincore.server;

import com.hibegin.http.server.api.ISocketServer;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.plugin.PluginRuntimeServer;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.transport.PluginNioServer;
import com.zrlog.plugincore.server.web.PluginHttpServer;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApplicationServersTest {

    private PluginRuntimeServices services;

    @After
    public void tearDown() {
        if (services != null) {
            services.shutdown();
        }
        PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
    }

    @Test
    public void shouldRemainBlockedUntilExplicitStopClosesHttpThenRuntime() throws Exception {
        List<String> stopOrder = new CopyOnWriteArrayList<>();
        RecordingRuntimeServer runtimeServer = new RecordingRuntimeServer(stopOrder);
        BlockingHttpServer httpServer = new BlockingHttpServer(stopOrder, true);
        ApplicationServers applicationServers = new ApplicationServers(ignored -> runtimeServer, () -> httpServer);
        services = PluginRuntimeServices.unconfigured();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread applicationThread = new Thread(() -> {
            try {
                applicationServers.start(0, services);
            } catch (Throwable e) {
                failure.set(e);
            }
        });

        applicationThread.start();
        assertTrue(httpServer.started.await(1, TimeUnit.SECONDS));
        assertTrue(applicationThread.isAlive());

        applicationServers.stop("test stop");
        applicationThread.join(1000L);

        assertFalse(applicationThread.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1, httpServer.stopCount.get());
        assertEquals(1, runtimeServer.stopCount.get());
        assertEquals(List.of("http", "runtime"), stopOrder);
    }

    @Test
    public void shouldCloseRuntimeWhenHttpServerCannotStart() {
        List<String> stopOrder = new CopyOnWriteArrayList<>();
        RecordingRuntimeServer runtimeServer = new RecordingRuntimeServer(stopOrder);
        BlockingHttpServer httpServer = new BlockingHttpServer(stopOrder, false);
        ApplicationServers applicationServers = new ApplicationServers(ignored -> runtimeServer, () -> httpServer);
        services = PluginRuntimeServices.unconfigured();

        try {
            applicationServers.start(0, services);
            fail("Expected HTTP startup failure");
        } catch (IllegalStateException e) {
            assertEquals("Unable to start plugin http server", e.getMessage());
        }

        assertEquals(1, httpServer.stopCount.get());
        assertEquals(1, runtimeServer.stopCount.get());
        assertEquals(List.of("http", "runtime"), stopOrder);
    }

    @Test
    public void shouldRouteJvmShutdownHookThroughUnifiedStopOnce() throws Exception {
        List<String> stopOrder = new CopyOnWriteArrayList<>();
        RecordingRuntimeServer runtimeServer = new RecordingRuntimeServer(stopOrder);
        BlockingHttpServer httpServer = new BlockingHttpServer(stopOrder, true);
        AtomicReference<Thread> registeredHook = new AtomicReference<>();
        AtomicInteger registrationCount = new AtomicInteger();
        ApplicationServers applicationServers = new ApplicationServers(
                ignored -> runtimeServer,
                () -> httpServer,
                hook -> {
                    registrationCount.incrementAndGet();
                    registeredHook.set(hook);
                });
        services = PluginRuntimeServices.unconfigured();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread applicationThread = new Thread(() -> {
            try {
                applicationServers.start(0, services);
            } catch (Throwable e) {
                failure.set(e);
            }
        });

        applicationThread.start();
        assertTrue(httpServer.started.await(1, TimeUnit.SECONDS));
        Thread shutdownHook = registeredHook.get();
        assertNotNull(shutdownHook);
        assertEquals("zrlog-plugin-core-shutdown", shutdownHook.getName());

        shutdownHook.run();
        applicationServers.stop("duplicate stop");
        applicationThread.join(1000L);

        assertFalse(applicationThread.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1, registrationCount.get());
        assertEquals(1, httpServer.stopCount.get());
        assertEquals(1, runtimeServer.stopCount.get());
        assertEquals("jvm shutdown", httpServer.stopReason.get());
        assertEquals("jvm shutdown", runtimeServer.stopReason.get());
        assertEquals(List.of("http", "runtime"), stopOrder);
    }

    private static class RecordingRuntimeServer extends PluginRuntimeServer {

        private final AtomicInteger stopCount = new AtomicInteger();
        private final AtomicReference<String> stopReason = new AtomicReference<>();
        private final List<String> stopOrder;

        private RecordingRuntimeServer(List<String> stopOrder) {
            super(new PluginNioServer(new NoopSocketServer()),
                    new PluginBootstrapService(Collections.emptyMap(), null, null, null),
                    () -> {
                    });
            this.stopOrder = stopOrder;
        }

        @Override
        public boolean start(boolean bootstrapRuntimeWorkers) {
            return true;
        }

        @Override
        public void stop(String reason) {
            stopCount.incrementAndGet();
            stopReason.compareAndSet(null, reason);
            stopOrder.add("runtime");
        }
    }

    private static class BlockingHttpServer extends PluginHttpServer {

        private final List<String> stopOrder;
        private final boolean startResult;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicInteger stopCount = new AtomicInteger();
        private final AtomicReference<String> stopReason = new AtomicReference<>();

        private BlockingHttpServer(List<String> stopOrder, boolean startResult) {
            this.stopOrder = stopOrder;
            this.startResult = startResult;
        }

        @Override
        public boolean start(Integer serverPort, boolean nativeAgent, PluginRuntimeServices runtimeServices) {
            started.countDown();
            return startResult;
        }

        @Override
        public void awaitStopped() {
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void stop(String reason) {
            stopCount.incrementAndGet();
            stopReason.compareAndSet(null, reason);
            stopOrder.add("http");
            stopped.countDown();
        }
    }

    private static class NoopSocketServer implements ISocketServer {

        @Override
        public void listen() {
        }

        @Override
        public void destroy(String reason) {
        }

        @Override
        public boolean create() {
            return true;
        }

        @Override
        public boolean create(int port) {
            return true;
        }

        @Override
        public boolean create(String hostname, int port) {
            return true;
        }

        @Override
        public int getPort() {
            return 0;
        }
    }
}
