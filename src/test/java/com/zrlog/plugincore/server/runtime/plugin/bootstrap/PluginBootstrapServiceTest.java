package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugincore.server.runtime.plugin.lifecycle.PluginLifecycleService;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessRuntime;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginBootstrapServiceTest {

    @Test
    public void shouldWaitForCurrentBootstrapBeforeReturningMetadata() throws Exception {
        BlockingPluginBootstrapService bootstrapService = new BlockingPluginBootstrapService();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        bootstrapService.loadPluginsAsync();
        assertTrue(bootstrapService.started.await(1, TimeUnit.SECONDS));
        Thread thread = new Thread(() -> {
            waiting.countDown();
            completed.set(bootstrapService.awaitCurrentBootstrap());
        });
        try {
            thread.start();
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            Thread.sleep(25);
            assertFalse(completed.get());

            bootstrapService.release.countDown();
            thread.join(1000);

            assertTrue(completed.get());
        } finally {
            bootstrapService.release.countDown();
            thread.join(1000);
        }
    }

    @Test
    public void shouldReportBootstrapRunningState() throws Exception {
        BlockingPluginBootstrapService bootstrapService = new BlockingPluginBootstrapService();
        try {
            bootstrapService.loadPluginsAsync();
            assertTrue(bootstrapService.started.await(1, TimeUnit.SECONDS));

            assertTrue(bootstrapService.isBootstrapRunning());
        } finally {
            bootstrapService.release.countDown();
        }
    }

    @Test
    public void shouldCoalesceRepeatedAsyncBootstrapRequests() throws Exception {
        BlockingPluginBootstrapService bootstrapService = new BlockingPluginBootstrapService();
        try {
            bootstrapService.loadPluginsAsync();
            assertTrue(bootstrapService.started.await(1, TimeUnit.SECONDS));

            for (int i = 0; i < 1000; i++) {
                bootstrapService.loadPluginsAsync();
            }

            assertEquals(1, bootstrapService.loadCount.get());
        } finally {
            bootstrapService.release.countDown();
        }
        assertTrue(bootstrapService.awaitCurrentBootstrap());
        assertEquals(1, bootstrapService.loadCount.get());
    }

    @Test
    public void shouldReportCurrentBootstrapReadyWithoutBlocking() throws Exception {
        BlockingPluginBootstrapService bootstrapService = new BlockingPluginBootstrapService();

        assertTrue(bootstrapService.isCurrentBootstrapReady());

        bootstrapService.loadPluginsAsync();
        assertTrue(bootstrapService.started.await(1, TimeUnit.SECONDS));
        assertFalse(bootstrapService.isCurrentBootstrapReady());

        bootstrapService.release.countDown();
        assertTrue(bootstrapService.awaitCurrentBootstrap());
        assertTrue(bootstrapService.isCurrentBootstrapReady());
    }

    @Test
    public void shouldInterruptCurrentBootstrapAndRejectNewWorkAfterShutdown() throws Exception {
        BlockingPluginBootstrapService bootstrapService = new BlockingPluginBootstrapService();
        bootstrapService.loadPluginsAsync();
        assertTrue(bootstrapService.started.await(1, TimeUnit.SECONDS));

        bootstrapService.shutdown();
        bootstrapService.loadPluginsAsync();

        assertTrue(bootstrapService.isShutdown());
        assertFalse(bootstrapService.isBootstrapRunning());
        assertFalse(bootstrapService.awaitCurrentBootstrap());
        assertEquals(1, bootstrapService.loadCount.get());
    }

    private static class BlockingPluginBootstrapService extends PluginBootstrapService {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger loadCount = new AtomicInteger();

        private BlockingPluginBootstrapService() {
            this(defaultComponents());
        }

        private BlockingPluginBootstrapService(Components components) {
            super(components.requiredPlugins, components.pluginStartupCoordinator,
                    components.metadataBootstrapper, components.lifecycleService);
        }

        @Override
        public void loadPlugins() {
            loadCount.incrementAndGet();
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static Components defaultComponents() {
            Map<String, String> requiredPlugins = new HashMap<>();
            requiredPlugins.put("comment", "comment");
            PluginProcessRuntime processRuntime = new PluginProcessRuntime();
            PluginLifecycleService lifecycleService = new PluginLifecycleService(processRuntime);
            PluginMetadataBootstrapper metadataBootstrapper =
                    new PluginMetadataBootstrapper(processRuntime, lifecycleService::stopPlugin);
            PluginStartupCoordinator pluginStartupCoordinator = new PluginStartupCoordinator(processRuntime,
                    new PluginArtifactBootstrapper(requiredPlugins, metadataBootstrapper));
            return new Components(requiredPlugins, pluginStartupCoordinator, metadataBootstrapper, lifecycleService);
        }
    }

    private static class Components {

        private final Map<String, String> requiredPlugins;
        private final PluginStartupCoordinator pluginStartupCoordinator;
        private final PluginMetadataBootstrapper metadataBootstrapper;
        private final PluginLifecycleService lifecycleService;

        private Components(Map<String, String> requiredPlugins,
                           PluginStartupCoordinator pluginStartupCoordinator,
                           PluginMetadataBootstrapper metadataBootstrapper,
                           PluginLifecycleService lifecycleService) {
            this.requiredPlugins = requiredPlugins;
            this.pluginStartupCoordinator = pluginStartupCoordinator;
            this.metadataBootstrapper = metadataBootstrapper;
            this.lifecycleService = lifecycleService;
        }
    }
}
