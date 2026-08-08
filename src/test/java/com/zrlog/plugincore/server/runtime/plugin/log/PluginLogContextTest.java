package com.zrlog.plugincore.server.runtime.plugin.log;

import com.hibegin.common.dao.DaoLogContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PluginLogContextTest {

    @Before
    public void clearCacheBeforeTest() {
        PluginLogContext.clearCachedShortNames();
    }

    @After
    public void clearCacheAfterTest() {
        PluginLogContext.clearCachedShortNames();
    }

    @Test
    public void openUsesShortNameOnlyForLogLabel() {
        try (PluginLogContext.Scope ignored = PluginLogContext.open("plugin-id", "backup-sql-file", "备份数据文件")) {
            assertEquals("backup-sql-file", PluginLogContext.currentLabel());
            assertEquals("backup-sql-file", DaoLogContext.currentLabel());
            assertEquals("[backup-sql-file] select 1", DaoLogContext.format("select 1"));
            assertEquals("[backup-sql-file] run plugin", PluginLogContext.prefix("run plugin"));
        }
        assertNull(PluginLogContext.currentLabel());
        assertNull(DaoLogContext.currentLabel());
    }

    @Test
    public void pluginIdOnlyScopeDoesNotOverrideExistingShortName() {
        try (PluginLogContext.Scope ignored = PluginLogContext.open("plugin-id", "backup-sql-file", "备份数据文件")) {
            try (PluginLogContext.Scope nested = PluginLogContext.open("other-plugin-id", null, null)) {
                assertEquals("backup-sql-file", PluginLogContext.currentLabel());
                assertEquals("backup-sql-file", DaoLogContext.currentLabel());
            }
            assertEquals("backup-sql-file", PluginLogContext.currentLabel());
            assertEquals("backup-sql-file", DaoLogContext.currentLabel());
        }
        assertNull(PluginLogContext.currentLabel());
        assertNull(DaoLogContext.currentLabel());
    }

    @Test
    public void pluginIdScopeUsesCachedShortNameInsteadOfDisplayName() {
        try (PluginLogContext.Scope ignored = PluginLogContext.open("site-check-id", "site-check", "站点检查")) {
            assertEquals("site-check", PluginLogContext.currentLabel());
        }

        try (PluginLogContext.Scope ignored = PluginLogContext.open("site-check-id", null, "站点检查")) {
            assertEquals("site-check", PluginLogContext.currentLabel());
            assertEquals("[site-check] select 1", DaoLogContext.format("select 1"));
        }
    }

    @Test
    public void displayNameOnlyDoesNotBecomeLogLabel() {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(null, null, "站点检查")) {
            assertNull(PluginLogContext.currentLabel());
            assertNull(DaoLogContext.currentLabel());
            assertEquals("select 1", DaoLogContext.format("select 1"));
        }
    }

    @Test
    public void cacheShouldRemainBoundedAndKeepNewestLabel() {
        for (int i = 0; i <= PluginLogContext.MAX_CACHED_PLUGIN_LABELS; i++) {
            PluginLogContext.register("plugin-" + i, "short-name-" + i);
        }

        assertEquals(PluginLogContext.MAX_CACHED_PLUGIN_LABELS, PluginLogContext.cachedShortNameCount());
        try (PluginLogContext.Scope ignored = PluginLogContext.open(
                "plugin-" + PluginLogContext.MAX_CACHED_PLUGIN_LABELS, null, null)) {
            assertEquals("short-name-" + PluginLogContext.MAX_CACHED_PLUGIN_LABELS,
                    PluginLogContext.currentLabel());
        }
        try (PluginLogContext.Scope ignored = PluginLogContext.open("plugin-0", null, null)) {
            assertNull(PluginLogContext.currentLabel());
        }
    }

    @Test
    public void oversizedIdentifiersShouldNotEnterCacheOrThreadLocal() {
        String oversized = repeat('x', PluginLogContext.MAX_PLUGIN_LABEL_LENGTH + 1);

        PluginLogContext.register(oversized, "short-name");
        PluginLogContext.register("plugin-id", oversized);

        assertEquals(0, PluginLogContext.cachedShortNameCount());
        try (PluginLogContext.Scope ignored = PluginLogContext.open("plugin-id", oversized, null)) {
            assertNull(PluginLogContext.currentLabel());
            assertNull(DaoLogContext.currentLabel());
        }
    }

    @Test
    public void concurrentRegistrationShouldRemainBounded() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < threadCount; thread++) {
                final int threadIndex = thread;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        PluginLogContext.register("plugin-" + threadIndex + "-" + i,
                                "short-name-" + threadIndex + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(PluginLogContext.MAX_CACHED_PLUGIN_LABELS, PluginLogContext.cachedShortNameCount());
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
