package com.zrlog.plugincore.server.web.config;

import com.hibegin.http.HttpMethod;
import com.hibegin.http.server.config.ServerConfig;
import com.hibegin.http.server.web.Router;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerExternalEndpoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PluginHttpServerConfigTest {

    private RunType originalRunType;

    @Before
    public void setUp() {
        originalRunType = RunConstants.runType;
    }

    @After
    public void tearDown() {
        RunConstants.runType = originalRunType;
        PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
    }

    @Test
    public void shouldRegisterRootAndAdminPluginRuntimeRoutesInBlogMode() {
        RunConstants.runType = RunType.BLOG;
        Router router = new PluginHttpServerConfig(0).getServerConfig().getRouter();

        assertRuntimePages(router, "");
        assertRuntimeApis(router, "/api");
        assertRuntimePages(router, "/admin/plugins");
        assertRuntimeApis(router, "/admin/plugins/api");
        assertLegacyNestedRuntimePathsMissing(router, "");
        assertLegacyNestedRuntimePathsMissing(router, "/admin/plugins");
    }

    @Test
    public void shouldRegisterRootAndAdminPluginRuntimeRoutesInDevMode() {
        RunConstants.runType = RunType.DEV;
        Router router = new PluginHttpServerConfig(0).getServerConfig().getRouter();

        assertRuntimePages(router, "");
        assertRuntimeApis(router, "/api");
        assertRuntimePages(router, "/admin/plugins");
        assertRuntimeApis(router, "/admin/plugins/api");
        assertLegacyNestedRuntimePathsMissing(router, "");
        assertLegacyNestedRuntimePathsMissing(router, "/admin/plugins");
    }

    @Test
    public void shouldRegisterExternalSchedulerTickPathOnce() {
        RunConstants.runType = RunType.BLOG;
        Router router = new PluginHttpServerConfig(0).getServerConfig().getRouter();

        assertRoute(router, SchedulerExternalEndpoint.EXTERNAL_TICK_EXPOSE_PATH, HttpMethod.POST);
        assertMissing(router, "/admin/plugins" + SchedulerExternalEndpoint.EXTERNAL_TICK_EXPOSE_PATH, HttpMethod.POST);
    }

    @Test
    public void shouldBindRuntimeServicesForWebServer() {
        PluginRuntimeServices services = PluginRuntimeServices.unconfigured();

        new PluginHttpServerConfig(0, services);

        assertSame(services.pluginBootstrap(), PluginRuntimeBridge.pluginBootstrap());
        assertSame(services.pluginConfig(), PluginRuntimeBridge.pluginConfig());
    }

    @Test
    public void shouldUseBoundedHttpExecutorsAndExplicitBodyLimit() {
        PluginHttpServerConfig config = new PluginHttpServerConfig(0);
        ServerConfig serverConfig = config.getServerConfig();
        ThreadPoolExecutor requestExecutor = (ThreadPoolExecutor) serverConfig.getRequestExecutor();
        ThreadPoolExecutor decodeExecutor = (ThreadPoolExecutor) serverConfig.getDecodeExecutor();
        try {
            assertExecutor(requestExecutor, PluginHttpServerConfig.REQUEST_THREADS,
                    PluginHttpServerConfig.REQUEST_QUEUE_CAPACITY, ThreadPoolExecutor.CallerRunsPolicy.class,
                    "plugin-http-request-");
            assertExecutor(decodeExecutor, PluginHttpServerConfig.DECODE_THREADS,
                    PluginHttpServerConfig.DECODE_QUEUE_CAPACITY, ThreadPoolExecutor.CallerRunsPolicy.class,
                    "plugin-http-decode-");
            assertEquals(PluginHttpServerConfig.MAX_REQUEST_BODY_SIZE,
                    config.getRequestConfig().getMaxRequestBodySize());
            assertEquals(PluginHttpServerConfig.REQUEST_TIMEOUT_SECONDS, serverConfig.getTimeout());
            assertEquals(PluginHttpServerConfig.REQUEST_STORAGE_MEMORY_THRESHOLD,
                    serverConfig.getHybridStorage().getMemoryThreshold());
        } finally {
            requestExecutor.shutdownNow();
            decodeExecutor.shutdownNow();
        }
    }

    @Test
    public void shouldRunRequestWorkOnCallerAfterExecutorIsSaturated() throws Exception {
        ThreadPoolExecutor executor = PluginHttpServerConfig.newRequestExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            saturate(executor, PluginHttpServerConfig.REQUEST_THREADS,
                    PluginHttpServerConfig.REQUEST_QUEUE_CAPACITY, release);

            AtomicReference<Thread> executionThread = new AtomicReference<>();

            executor.execute(() -> executionThread.set(Thread.currentThread()));

            assertSame(Thread.currentThread(), executionThread.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void shouldRunDecodeWorkOnCallerAfterExecutorIsSaturated() throws Exception {
        ThreadPoolExecutor executor = PluginHttpServerConfig.newDecodeExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            saturate(executor, PluginHttpServerConfig.DECODE_THREADS,
                    PluginHttpServerConfig.DECODE_QUEUE_CAPACITY, release);
            AtomicReference<Thread> executionThread = new AtomicReference<>();

            executor.execute(() -> executionThread.set(Thread.currentThread()));

            assertSame(Thread.currentThread(), executionThread.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertExecutor(ThreadPoolExecutor executor, int threads, int queueCapacity,
                                Class<?> rejectionPolicy, String threadNamePrefix) {
        assertEquals(threads, executor.getCorePoolSize());
        assertEquals(threads, executor.getMaximumPoolSize());
        assertEquals(queueCapacity, executor.getQueue().remainingCapacity());
        assertTrue(rejectionPolicy.isInstance(executor.getRejectedExecutionHandler()));
        Thread thread = executor.getThreadFactory().newThread(() -> {
        });
        assertTrue(thread.getName().startsWith(threadNamePrefix));
        assertFalse(thread.isDaemon());
    }

    private void saturate(ThreadPoolExecutor executor, int threads, int queueCapacity, CountDownLatch release)
            throws InterruptedException {
        CountDownLatch workersStarted = new CountDownLatch(threads);
        Runnable blockingTask = () -> {
            workersStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        for (int i = 0; i < threads; i++) {
            executor.execute(blockingTask);
        }
        assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < queueCapacity; i++) {
            executor.execute(() -> {
            });
        }
        assertEquals(queueCapacity, executor.getQueue().size());
    }

    private void assertRuntimePages(Router router, String prefix) {
        assertRoute(router, prefix + "/runtime-scheduler", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-scheduler/runs", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-scheduler/settings", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-states", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-notification", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-services", HttpMethod.GET);
    }

    private void assertRuntimeApis(Router router, String prefix) {
        assertRoute(router, prefix + "/runtime-scheduler/settings", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-scheduler/settings", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-scheduler/tick", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-automations", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-automations", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-automations/update", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-automations/run", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-automations/delete", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-automation-runs", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-capabilities", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-states", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-states/start", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-states/stop", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-settings", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-settings", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-invocation-logs", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-notification/channels", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-notification/provider", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-notification/provider/auto", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-notification/test", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-notification/deliveries", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-services/providers", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-services/provider", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-services/provider/auto", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-services/comment-providers", HttpMethod.GET);
        assertRoute(router, prefix + "/runtime-services/comment-provider", HttpMethod.POST);
        assertRoute(router, prefix + "/runtime-services/comment-provider/default", HttpMethod.POST);
    }

    private void assertMissingRuntimePages(Router router, String prefix) {
        assertMissing(router, prefix + "/runtime-scheduler", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime-states", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime-notification", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime-services", HttpMethod.GET);
    }

    private void assertMissingRuntimeApis(Router router, String prefix) {
        assertMissing(router, prefix + "/runtime-automations", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime-scheduler/settings", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime-states", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime-services/providers", HttpMethod.GET);
    }

    private void assertLegacyNestedRuntimePathsMissing(Router router, String prefix) {
        assertMissing(router, prefix + "/runtime/scheduler", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime/states", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime/notification", HttpMethod.GET);
        assertMissing(router, prefix + "/runtime/services", HttpMethod.GET);
        assertMissing(router, prefix + "/api/runtime/automations", HttpMethod.GET);
        assertMissing(router, prefix + "/api/runtime/scheduler/settings", HttpMethod.GET);
    }

    private void assertRoute(Router router, String path, HttpMethod method) {
        assertNotNull(path, router.getMethod(path, method));
    }

    private void assertMissing(Router router, String path, HttpMethod method) {
        assertNull(path, router.getMethod(path, method));
    }
}
