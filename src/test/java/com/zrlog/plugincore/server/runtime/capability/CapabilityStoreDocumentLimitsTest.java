package com.zrlog.plugincore.server.runtime.capability;

import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CapabilityStoreDocumentLimitsTest {

    @Test
    public void shouldRejectTooManyPersistedCapabilityItems() {
        CapabilityDocument document = new CapabilityDocument();
        List<PluginCapability> items = new ArrayList<>();
        for (int i = 0; i <= PersistentJsonLimits.MAX_CAPABILITY_ENTRIES; i++) {
            PluginCapability capability = new PluginCapability();
            capability.setPluginId("plugin-a");
            capability.setKey("task." + i);
            items.add(capability);
        }
        document.setItems(items);

        assertRejected(() -> new CapabilityStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "item count");
    }

    @Test
    public void shouldRejectOversizedCapabilityDocumentBeforeWritingIt() {
        PluginCapability capability = new PluginCapability();
        capability.setPluginId("plugin-a");
        capability.setKey("task.run");
        capability.setDescription("x".repeat(PersistentJsonLimits.MAX_CAPABILITY_DOCUMENT_BYTES));
        CapabilityDocument document = new CapabilityDocument();
        document.getItems().add(capability);

        assertRejected(() -> new CapabilityStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "exceeds");
    }

    @Test
    public void shouldRejectOversizedStoredDocumentBeforeParsingIt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(CapabilityStore.KEY,
                "x".repeat(PersistentJsonLimits.MAX_CAPABILITY_DOCUMENT_BYTES + 1));

        assertRejected(() -> new CapabilityStore(kvStore).loadDocument(), "exceeds");
    }

    private void assertRejected(Runnable action, String messagePart) {
        try {
            action.run();
            fail("document should be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }
}
