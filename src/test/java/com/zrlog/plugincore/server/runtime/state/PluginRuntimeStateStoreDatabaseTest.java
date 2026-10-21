package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugincore.server.dao.WebSiteDAO;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase.DatabaseType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class PluginRuntimeStateStoreDatabaseTest {

    private static final String LEGACY_LEASE_KEY = "distributed_lock_plugin_runtime_states";
    private final DatabaseType databaseType;

    public PluginRuntimeStateStoreDatabaseTest(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    @Parameterized.Parameters(name = "{0}")
    public static DatabaseType[] databases() {
        return DatabaseType.values();
    }

    @Test(timeout = 15000)
    public void shouldUpdateMultiplePluginsWithoutWaitingForOrDeletingAnOrphanLease() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            String orphanLease = "previous-process|" + (System.currentTimeMillis() + 300000L);
            WebSiteDAO dao = new WebSiteDAO();
            dao.saveOrUpdate(LEGACY_LEASE_KEY, orphanLease);
            PluginRuntimeStateStore store = new PluginRuntimeStateStore(new WebsiteRuntimeKvStore());

            for (String plugin : new String[]{"mail", "reminder", "backup-sql-file", "static-plus"}) {
                store.update(plugin, state -> state.setStatus("ready"));
            }

            assertEquals(4, store.list().size());
            for (PluginRuntimeState state : store.list()) {
                assertEquals("ready", state.getStatus());
            }
            assertEquals(orphanLease, dao.queryValueByName(LEGACY_LEASE_KEY));
        }
    }

    @Test
    public void shouldNotCreateALeaseWhenUpdatingOrDeletingState() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            PluginRuntimeStateStore store = new PluginRuntimeStateStore(new WebsiteRuntimeKvStore());

            store.update("mail", state -> state.setStatus("ready"));
            store.delete("mail");

            assertTrue(store.list().isEmpty());
            assertEquals(null, new WebSiteDAO().queryValueByName(LEGACY_LEASE_KEY));
        }
    }

    @Test
    public void shouldMergeAnIndependentWriterAfterARealDatabaseCasConflict() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            PluginRuntimeStateStore otherRuntime = new PluginRuntimeStateStore(new WebsiteRuntimeKvStore());
            AtomicBoolean competingWrite = new AtomicBoolean();
            WebsiteRuntimeKvStore repository = new WebsiteRuntimeKvStore() {
                @Override
                public boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
                    if (competingWrite.compareAndSet(false, true)) {
                        // Commit another Runtime's change after this writer has read its snapshot.
                        otherRuntime.update("reminder", state -> state.setStatus("ready"));
                    }
                    return super.compareAndSet(key, expectedValue, value);
                }
            };
            PluginRuntimeStateStore store = new PluginRuntimeStateStore(repository);

            store.update("mail", state -> state.setStatus("initializing"));

            assertEquals(2, otherRuntime.list().size());
            assertEquals("initializing", otherRuntime.find("mail").get().getStatus());
            assertEquals("ready", otherRuntime.find("reminder").get().getStatus());
        }
    }

    @Test
    public void shouldRejectAnOldVersionEvenWhenAnotherWriterRestoresTheSameJson() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            WebsiteRuntimeKvStore first = new WebsiteRuntimeKvStore();
            WebsiteRuntimeKvStore second = new WebsiteRuntimeKvStore();
            first.put(PluginRuntimeStateStore.KEY, "original");
            assertEquals(Optional.of("original"), first.get(PluginRuntimeStateStore.KEY));
            second.put(PluginRuntimeStateStore.KEY, "changed");
            second.put(PluginRuntimeStateStore.KEY, "original");

            assertFalse(first.compareAndSet(PluginRuntimeStateStore.KEY, Optional.of("original"), "stale overwrite"));
            assertEquals(Optional.of("original"), second.get(PluginRuntimeStateStore.KEY));
        }
    }
}
