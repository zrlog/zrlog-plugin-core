package com.zrlog.plugincore.server.runtime.capability;

import com.google.gson.Gson;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.store.ConditionalKvRepository;
import org.junit.Test;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CapabilityStoreTest {

    @Test
    public void shouldRegisterAndQueryCapability() {
        CapabilityStore store = new CapabilityStore(new InMemoryRuntimeKvStore());
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduled", "scheduler");

        store.register(capability);

        assertEquals(1, store.listAll().size());
        assertTrue(store.find("plugin-a", "reminder.scanDueTasks").isPresent());
        assertEquals(1, store.listByExposure("scheduler").size());
        assertEquals(1, store.listByType("scheduled").size());
    }

    @Test
    public void shouldNormalizeNullCapabilityDocumentsAndItems() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(CapabilityStore.KEY, "null");
        CapabilityStore store = new CapabilityStore(kvStore);

        assertEquals(0, store.listAll().size());

        kvStore.put(CapabilityStore.KEY,
                "{\"items\":[null,{\"pluginId\":\"plugin-a\",\"key\":\"task.run\"},null]}");
        CapabilityDocument loaded = store.loadDocument();
        assertEquals(1, loaded.getItems().size());
        assertEquals("task.run", loaded.getItems().get(0).getKey());

        store.saveDocument(loaded);
        CapabilityDocument persisted = new Gson().fromJson(
                kvStore.get(CapabilityStore.KEY).get(), CapabilityDocument.class);
        assertEquals(1, persisted.getItems().size());

        store.saveDocument(null);
        persisted = new Gson().fromJson(kvStore.get(CapabilityStore.KEY).get(), CapabilityDocument.class);
        assertEquals(0, persisted.getItems().size());
    }

    @Test
    public void shouldSkipRegisterWriteWhenCapabilityIsUnchanged() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore store = new CapabilityStore(kvStore);
        store.register(capability("plugin-a", "reminder.scanDueTasks", "scheduled", "scheduler"));
        kvStore.resetCounts();

        store.register(capability("plugin-a", "reminder.scanDueTasks", "scheduled", "scheduler"));

        assertEquals(1, kvStore.getCount(CapabilityStore.KEY));
        assertEquals(0, kvStore.putCount(CapabilityStore.KEY));
    }

    @Test
    public void shouldSkipReplaceWriteWhenPluginCapabilitiesAreUnchanged() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore store = new CapabilityStore(kvStore);
        store.replacePluginCapabilities("plugin-a",
                Arrays.asList(capability("plugin-a", "reminder.scanDueTasks", "scheduled", "scheduler")));
        kvStore.resetCounts();

        store.replacePluginCapabilities("plugin-a",
                Arrays.asList(capability("plugin-a", "reminder.scanDueTasks", "scheduled", "scheduler")));

        assertEquals(1, kvStore.getCount(CapabilityStore.KEY));
        assertEquals(0, kvStore.putCount(CapabilityStore.KEY));
    }

    @Test
    public void shouldMarkAmbiguousKeyAcrossPlugins() {
        CapabilityStore store = new CapabilityStore(new InMemoryRuntimeKvStore());
        store.register(capability("plugin-a", "same.key", "service", "internal"));
        store.register(capability("plugin-b", "same.key", "service", "internal"));

        assertTrue(store.isAmbiguous("same.key"));
    }

    @Test
    public void shouldFilterLegacyServiceNames() {
        Optional<PluginCapability> generated = CapabilityStore.fromLegacyService("plugin-a", "Plugin A", "legacy.validService");
        Optional<PluginCapability> skipped = CapabilityStore.fromLegacyService("plugin-a", "Plugin A", "run");

        assertTrue(generated.isPresent());
        assertTrue(generated.get().getLegacy());
        assertTrue(generated.get().getGenerated());
        assertFalse(skipped.isPresent());
    }

    @Test
    public void shouldNormalizeCapabilityRiskPolicy() {
        CapabilityStore store = new CapabilityStore(new InMemoryRuntimeKvStore());
        PluginCapability capability = capability("plugin-a", "critical.task", "service", "internal");
        capability.setRiskLevel("HIGH");
        capability.setExposure(Arrays.asList("internal", "mcp"));

        store.register(capability);

        PluginCapability stored = store.find("plugin-a", "critical.task").get();
        assertEquals("high", stored.getRiskLevel());
        assertFalse(stored.getExposure().contains("mcp"));
        assertEquals(Boolean.FALSE, stored.getReadOnly());
        assertEquals(Boolean.FALSE, stored.getRequiresConfirmation());
        assertEquals(Integer.valueOf(600), stored.getTimeoutSeconds());
        assertEquals(Integer.valueOf(1), stored.getConcurrency());
    }

    @Test
    public void shouldRejectUnsupportedRiskLevel() {
        CapabilityStore store = new CapabilityStore(new InMemoryRuntimeKvStore());
        PluginCapability capability = capability("plugin-a", "invalid.task", "service", "internal");
        capability.setRiskLevel("dangerous");

        try {
            store.register(capability);
            fail("unsupported riskLevel should fail registration");
        } catch (IllegalArgumentException e) {
            assertEquals("Unsupported capability riskLevel: dangerous", e.getMessage());
        }
    }

    @Test
    public void shouldRetryRegisterWhenConcurrentCapabilityWriteWinsFirst() {
        StaleOnceKvStore kvStore = new StaleOnceKvStore(
                Optional.<String>empty(),
                documentJson(capability("plugin-b", "email.send", "notification_channel", "email"))
        );
        CapabilityStore store = new CapabilityStore(kvStore);

        store.register(capability("plugin-a", "reminder.scanDueTasks", "scheduled", "scheduler"));

        assertEquals(2, store.listAll().size());
        assertTrue(store.find("plugin-a", "reminder.scanDueTasks").isPresent());
        assertTrue(store.find("plugin-b", "email.send").isPresent());
        assertEquals(2, kvStore.getCompareAndSetCount());
    }

    @Test
    public void shouldRetryReplaceWhenConcurrentCapabilityWriteWinsFirst() {
        StaleOnceKvStore kvStore = new StaleOnceKvStore(
                Optional.of(documentJson(capability("plugin-a", "reminder.oldTask", "scheduled", "scheduler"))),
                documentJson(
                        capability("plugin-a", "reminder.oldTask", "scheduled", "scheduler"),
                        capability("plugin-b", "email.send", "notification_channel", "email")
                )
        );
        CapabilityStore store = new CapabilityStore(kvStore);

        store.replacePluginCapabilities("plugin-a",
                Arrays.asList(capability("plugin-a", "reminder.newTask", "scheduled", "scheduler")));

        assertEquals(2, store.listAll().size());
        assertFalse(store.find("plugin-a", "reminder.oldTask").isPresent());
        assertTrue(store.find("plugin-a", "reminder.newTask").isPresent());
        assertTrue(store.find("plugin-b", "email.send").isPresent());
        assertEquals(2, kvStore.getCompareAndSetCount());
    }

    private PluginCapability capability(String pluginId, String key, String type, String exposure) {
        PluginCapability capability = new PluginCapability();
        capability.setPluginId(pluginId);
        capability.setPluginName(pluginId);
        capability.setKey(key);
        capability.setType(type);
        capability.setExposure(Arrays.asList(exposure));
        capability.setEnabled(Boolean.TRUE);
        return capability;
    }

    private String documentJson(PluginCapability... capabilities) {
        CapabilityDocument document = new CapabilityDocument();
        document.setItems(Arrays.asList(capabilities));
        return new Gson().toJson(document);
    }

    private static class StaleOnceKvStore implements KvRepository, ConditionalKvRepository {
        private Optional<String> value;
        private final String staleValue;
        private boolean staleInjected;
        private int compareAndSetCount;

        private StaleOnceKvStore(Optional<String> value, String staleValue) {
            this.value = value;
            this.staleValue = staleValue;
        }

        @Override
        public Optional<String> get(String key) {
            return value;
        }

        @Override
        public void put(String key, String value) {
            this.value = Optional.ofNullable(value);
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            compareAndSetCount++;
            if (!staleInjected) {
                this.value = Optional.of(staleValue);
                staleInjected = true;
                return false;
            }
            if (!Objects.equals(this.value, expectedValue)) {
                return false;
            }
            this.value = Optional.ofNullable(value);
            return true;
        }

        private int getCompareAndSetCount() {
            return compareAndSetCount;
        }
    }
}
