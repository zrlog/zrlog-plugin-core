package com.zrlog.plugincore.server.runtime.store;

import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase.DatabaseType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class WebsiteRuntimeKvStoreTest {

    private final DatabaseType databaseType;

    public WebsiteRuntimeKvStoreTest(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    @Parameterized.Parameters(name = "{0}")
    public static DatabaseType[] databases() {
        return DatabaseType.values();
    }

    @Test
    public void shouldReadWriteAndCompareAndSetWithSupportedDatabase() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            WebsiteRuntimeKvStore store = new WebsiteRuntimeKvStore();

            assertEquals(Optional.empty(), store.get("runtime.event"));
            assertTrue(store.compareAndSet("runtime.event", Optional.empty(), "v1"));
            assertEquals(Optional.of("v1"), store.get("runtime.event"));
            assertTrue(store.compareAndSet("runtime.event", Optional.of("v1"), "v2"));
            assertFalse(store.compareAndSet("runtime.event", Optional.of("v1"), "v3"));
            assertEquals(Optional.of("v2"), store.get("runtime.event"));

            store.put("runtime.event", "v4");
            assertEquals(Optional.of("v4"), store.get("runtime.event"));
        }
    }
}
