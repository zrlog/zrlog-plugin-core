package com.zrlog.plugincore.server.runtime.scheduler;

import com.google.gson.Gson;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AutomationStoreTest {

    @Test
    public void shouldReturnDefaultDocumentWhenKvMissing() {
        AutomationStore store = new AutomationStore(new InMemoryRuntimeKvStore());

        assertEquals(0, store.list().size());
        assertEquals(AutomationStore.KEY, store.loadDocument().getSchema());
    }

    @Test
    public void shouldNormalizeNullAutomationDocumentsAndItems() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(AutomationStore.KEY, "null");
        AutomationStore store = new AutomationStore(kvStore);

        assertEquals(0, store.list().size());

        kvStore.put(AutomationStore.KEY, "{\"items\":[null,{\"id\":\"automation-a\"},null]}");
        AutomationDocument loaded = store.loadDocument();
        assertEquals(1, loaded.getItems().size());
        assertEquals("automation-a", loaded.getItems().get(0).getId());

        store.saveDocument(loaded);
        AutomationDocument persisted = new Gson().fromJson(
                kvStore.get(AutomationStore.KEY).get(), AutomationDocument.class);
        assertEquals(1, persisted.getItems().size());

        store.saveDocument(null);
        persisted = new Gson().fromJson(kvStore.get(AutomationStore.KEY).get(), AutomationDocument.class);
        assertEquals(0, persisted.getItems().size());
    }

    @Test
    public void shouldRejectOversizedStoredAutomationDocumentBeforeParsingIt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(AutomationStore.KEY,
                "x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES + 1));

        assertRejected(() -> new AutomationStore(kvStore).loadDocument(), "exceeds");
    }

    @Test
    public void shouldRejectOversizedAutomationDocumentBeforeWritingIt() {
        AutomationDocument document = new AutomationDocument();
        document.getItems().add(automation("x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES)));

        assertRejected(() -> new AutomationStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "exceeds");
    }

    @Test
    public void shouldRejectTooManyStoredAutomationsAfterNormalization() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(AutomationStore.KEY, new Gson().toJson(
                automationDocument(PersistentJsonLimits.MAX_PLUGIN_ENTRIES + 1)));

        assertRejected(() -> new AutomationStore(kvStore).loadDocument(), "item count");
    }

    @Test
    public void shouldRejectTooManyAutomationsBeforeWritingThem() {
        AutomationDocument document = automationDocument(PersistentJsonLimits.MAX_PLUGIN_ENTRIES + 1);

        assertRejected(() -> new AutomationStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "item count");
    }

    @Test
    public void shouldApplyAutomationItemLimitAfterNullNormalizationOnReadAndWrite() {
        AutomationDocument document = automationDocument(PersistentJsonLimits.MAX_PLUGIN_ENTRIES);
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(AutomationStore.KEY, new Gson().toJson(document));
        AutomationStore store = new AutomationStore(kvStore);

        assertEquals(PersistentJsonLimits.MAX_PLUGIN_ENTRIES, store.loadDocument().getItems().size());

        store.saveDocument(document);
        assertEquals(PersistentJsonLimits.MAX_PLUGIN_ENTRIES, store.loadDocument().getItems().size());
    }

    @Test
    public void shouldNormalizeNullAutomationRunDocumentsAndItems() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(AutomationRunStore.KEY, "null");
        AutomationRunStore store = new AutomationRunStore(kvStore);

        assertEquals(0, store.list().size());

        kvStore.put(AutomationRunStore.KEY, "{\"items\":[null,{\"id\":\"run-a\"},null]}");
        AutomationRunDocument loaded = store.loadDocument();
        assertEquals(1, loaded.getItems().size());
        assertEquals("run-a", loaded.getItems().get(0).getId());

        store.saveDocument(loaded);
        AutomationRunDocument persisted = new Gson().fromJson(
                kvStore.get(AutomationRunStore.KEY).get(), AutomationRunDocument.class);
        assertEquals(1, persisted.getItems().size());

        store.saveDocument(null);
        persisted = new Gson().fromJson(kvStore.get(AutomationRunStore.KEY).get(), AutomationRunDocument.class);
        assertEquals(0, persisted.getItems().size());
    }

    @Test
    public void shouldRejectOversizedStoredAutomationRunDocumentBeforeParsingIt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(AutomationRunStore.KEY,
                "x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES + 1));

        assertRejected(() -> new AutomationRunStore(kvStore).loadDocument(), "exceeds");
    }

    @Test
    public void shouldRejectOversizedAutomationRunDocumentBeforeWritingIt() {
        AutomationRunDocument document = new AutomationRunDocument();
        document.getItems().add(run("x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES)));

        assertRejected(() -> new AutomationRunStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "exceeds");
    }

    @Test
    public void shouldTrimAutomationRuns() {
        AutomationRunStore store = new AutomationRunStore(new InMemoryRuntimeKvStore());
        for (int i = 0; i < 501; i++) {
            PluginAutomationRun run = new PluginAutomationRun();
            run.setId("run-" + i);
            store.append(run);
        }

        assertEquals(500, store.list().size());
        assertTrue("run-1".equals(store.list().get(0).getId()));
    }

    @Test
    public void shouldTruncateNewAndLegacyAutomationRunErrors() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        String oversizedError = repeat("x", RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS + 100);
        kvStore.put(AutomationRunStore.KEY,
                "{\"items\":[{\"id\":\"legacy\",\"errorMessage\":\"" + oversizedError + "\"}]}");
        AutomationRunStore store = new AutomationRunStore(kvStore);

        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(store.list().get(0).getErrorMessage()));

        PluginAutomationRun current = run("current");
        current.setErrorMessage(oversizedError);
        store.append(current);

        AutomationRunDocument persisted = new Gson().fromJson(
                kvStore.get(AutomationRunStore.KEY).get(), AutomationRunDocument.class);
        assertEquals(2, persisted.getItems().size());
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(0).getErrorMessage()));
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(1).getErrorMessage()));
    }

    @Test
    public void shouldRetryAutomationRunAppendWhenConcurrentWriteWinsFirst() {
        StaleAutomationRunKvStore kvStore = new StaleAutomationRunKvStore(documentJson(run("external")));
        AutomationRunStore store = new AutomationRunStore(kvStore);

        store.append(run("local"));

        assertEquals(2, store.list().size());
        assertTrue(store.list().stream().anyMatch(item -> "external".equals(item.getId())));
        assertTrue(store.list().stream().anyMatch(item -> "local".equals(item.getId())));
        assertEquals(2, kvStore.getAutomationRunCompareAndSetCount());
    }

    private PluginAutomationRun run(String id) {
        PluginAutomationRun run = new PluginAutomationRun();
        run.setId(id);
        return run;
    }

    private PluginAutomation automation(String id) {
        PluginAutomation automation = new PluginAutomation();
        automation.setId(id);
        return automation;
    }

    private AutomationDocument automationDocument(int itemCount) {
        AutomationDocument document = new AutomationDocument();
        List<PluginAutomation> items = new ArrayList<>();
        items.add(null);
        for (int i = 0; i < itemCount; i++) {
            items.add(automation("automation-" + i));
        }
        document.setItems(items);
        return document;
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private String documentJson(PluginAutomationRun... runs) {
        AutomationRunDocument document = new AutomationRunDocument();
        document.setItems(Arrays.asList(runs));
        return new Gson().toJson(document);
    }

    private void assertRejected(Runnable action, String messagePart) {
        try {
            action.run();
            fail("document should be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }

    private static class StaleAutomationRunKvStore extends InMemoryRuntimeKvStore {
        private final String staleValue;
        private boolean staleInjected;
        private int automationRunCompareAndSetCount;

        private StaleAutomationRunKvStore(String staleValue) {
            this.staleValue = staleValue;
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            if (!AutomationRunStore.KEY.equals(key)) {
                return super.compareAndSet(key, expectedValue, value);
            }
            automationRunCompareAndSetCount++;
            if (!staleInjected) {
                put(key, staleValue);
                staleInjected = true;
                return false;
            }
            return super.compareAndSet(key, expectedValue, value);
        }

        private int getAutomationRunCompareAndSetCount() {
            return automationRunCompareAndSetCount;
        }
    }
}
