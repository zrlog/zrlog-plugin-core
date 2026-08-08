package com.zrlog.plugincore.server.runtime.plugin;

import com.hibegin.http.server.api.ISocketServer;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.transport.PluginNioServer;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginRuntimeServerTest {

    @Test
    public void shouldStartNioServerAndRuntimeWorkers() throws InterruptedException {
        RecordingSocketServer socketServer = new RecordingSocketServer(true);
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        AtomicInteger schedulerStarts = new AtomicInteger();
        PluginRuntimeServer server = new PluginRuntimeServer(
                new PluginNioServer(socketServer),
                bootstrapService,
                schedulerStarts::incrementAndGet);

        assertTrue(server.start(true));

        assertEquals(1, socketServer.createCount.get());
        assertTrue(socketServer.awaitListen());
        assertEquals(1, bootstrapService.verifyCount.get());
        assertEquals(1, bootstrapService.loadCount.get());
        assertEquals(1, schedulerStarts.get());
    }

    @Test
    public void shouldStartNioServerWithoutRuntimeWorkers() throws InterruptedException {
        RecordingSocketServer socketServer = new RecordingSocketServer(true);
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        AtomicInteger schedulerStarts = new AtomicInteger();
        PluginRuntimeServer server = new PluginRuntimeServer(
                new PluginNioServer(socketServer),
                bootstrapService,
                schedulerStarts::incrementAndGet);

        assertTrue(server.start(false));

        assertEquals(1, socketServer.createCount.get());
        assertTrue(socketServer.awaitListen());
        assertEquals(0, bootstrapService.verifyCount.get());
        assertEquals(0, bootstrapService.loadCount.get());
        assertEquals(0, schedulerStarts.get());
    }

    @Test
    public void shouldStopNioServerWhenRuntimeBootstrapFails() {
        RecordingSocketServer socketServer = new RecordingSocketServer(true);
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        bootstrapService.failOnLoad = true;
        PluginRuntimeServer server = new PluginRuntimeServer(
                new PluginNioServer(socketServer),
                bootstrapService,
                () -> {
                });

        try {
            server.start(true);
            fail("Expected bootstrap failure");
        } catch (RuntimeException e) {
            assertEquals("boom", e.getMessage());
        }

        assertEquals("plugin bootstrap failed", socketServer.destroyReason);
    }

    @Test
    public void shouldStopAllRuntimeStagesOnceAndRejectRestart() throws InterruptedException {
        List<String> stopOrder = new CopyOnWriteArrayList<>();
        RecordingSocketServer socketServer = new RecordingSocketServer(true, () -> stopOrder.add("nio"));
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        AtomicInteger schedulerStarts = new AtomicInteger();
        AtomicInteger schedulerStops = new AtomicInteger();
        AtomicInteger shutdownStarts = new AtomicInteger();
        AtomicInteger runtimeStops = new AtomicInteger();
        PluginRuntimeServer server = new PluginRuntimeServer(
                new PluginNioServer(socketServer),
                bootstrapService,
                schedulerStarts::incrementAndGet,
                () -> {
                    schedulerStops.incrementAndGet();
                    stopOrder.add("scheduler");
                },
                () -> {
                    shutdownStarts.incrementAndGet();
                    stopOrder.add("gate");
                },
                () -> {
                    runtimeStops.incrementAndGet();
                    stopOrder.add("runtime");
                });

        assertTrue(server.start(true));
        assertTrue(socketServer.awaitListen());

        server.stop("test stop");
        server.stop("duplicate stop");

        assertEquals(1, schedulerStarts.get());
        assertEquals(1, schedulerStops.get());
        assertEquals(1, shutdownStarts.get());
        assertEquals(1, socketServer.destroyCount.get());
        assertEquals(1, runtimeStops.get());
        assertEquals(List.of("scheduler", "gate", "nio", "runtime"), stopOrder);
        assertEquals("test stop", socketServer.destroyReason);
        assertFalse(server.start(true));
        assertEquals(1, socketServer.createCount.get());
        assertEquals(1, bootstrapService.loadCount.get());
    }

    @Test
    public void shouldFinishEveryStopStageWhenOneStageFails() {
        RecordingSocketServer socketServer = new RecordingSocketServer(true);
        AtomicInteger shutdownStarts = new AtomicInteger();
        AtomicInteger runtimeStops = new AtomicInteger();
        PluginRuntimeServer server = new PluginRuntimeServer(
                new PluginNioServer(socketServer),
                new RecordingBootstrapService(),
                () -> {
                },
                () -> {
                    throw new IllegalStateException("scheduler stop failed");
                },
                shutdownStarts::incrementAndGet,
                runtimeStops::incrementAndGet);

        try {
            server.stop("test stop");
            fail("Expected stop failure");
        } catch (IllegalStateException e) {
            assertEquals("scheduler stop failed", e.getMessage());
        }

        assertEquals(1, shutdownStarts.get());
        assertEquals(1, socketServer.destroyCount.get());
        assertEquals(1, runtimeStops.get());
    }

    private static class RecordingBootstrapService extends PluginBootstrapService {

        private final AtomicInteger verifyCount = new AtomicInteger();
        private final AtomicInteger loadCount = new AtomicInteger();
        private boolean failOnLoad;

        private RecordingBootstrapService() {
            super(Collections.emptyMap(), null, null, null);
        }

        @Override
        public void verifyPluginCoreReadable() {
            verifyCount.incrementAndGet();
        }

        @Override
        public void loadPluginsAsync() {
            loadCount.incrementAndGet();
            if (failOnLoad) {
                throw new RuntimeException("boom");
            }
        }
    }

    private static class RecordingSocketServer implements ISocketServer {

        private final boolean createResult;
        private final Runnable destroyCallback;
        private final CountDownLatch listenLatch = new CountDownLatch(1);
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger destroyCount = new AtomicInteger();
        private volatile String destroyReason;

        private RecordingSocketServer(boolean createResult) {
            this(createResult, () -> {
            });
        }

        private RecordingSocketServer(boolean createResult, Runnable destroyCallback) {
            this.createResult = createResult;
            this.destroyCallback = destroyCallback;
        }

        @Override
        public void listen() {
            listenLatch.countDown();
        }

        @Override
        public void destroy(String reason) {
            destroyCount.incrementAndGet();
            destroyReason = reason;
            destroyCallback.run();
        }

        @Override
        public boolean create() {
            createCount.incrementAndGet();
            return createResult;
        }

        @Override
        public boolean create(int port) {
            return create();
        }

        @Override
        public boolean create(String hostname, int port) {
            return create();
        }

        @Override
        public int getPort() {
            return 0;
        }

        private boolean awaitListen() throws InterruptedException {
            return listenLatch.await(1, TimeUnit.SECONDS);
        }
    }
}
