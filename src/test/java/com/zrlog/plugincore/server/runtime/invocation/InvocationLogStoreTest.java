package com.zrlog.plugincore.server.runtime.invocation;

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

public class InvocationLogStoreTest {

    @Test
    public void shouldNormalizeNullInvocationLogDocumentsAndItems() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(InvocationLogStore.KEY, "null");
        InvocationLogStore store = new InvocationLogStore(kvStore);

        assertEquals(0, store.list().size());

        kvStore.put(InvocationLogStore.KEY, "{\"items\":[null,{\"id\":\"log-a\"},null]}");
        InvocationLogDocument loaded = store.loadDocument();
        assertEquals(1, loaded.getItems().size());
        assertEquals("log-a", loaded.getItems().get(0).getId());

        store.saveDocument(loaded);
        InvocationLogDocument persisted = new Gson().fromJson(
                kvStore.get(InvocationLogStore.KEY).get(), InvocationLogDocument.class);
        assertEquals(1, persisted.getItems().size());

        store.saveDocument(null);
        persisted = new Gson().fromJson(kvStore.get(InvocationLogStore.KEY).get(), InvocationLogDocument.class);
        assertEquals(0, persisted.getItems().size());
    }

    @Test
    public void shouldRejectOversizedStoredInvocationLogDocumentBeforeParsingIt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(InvocationLogStore.KEY,
                "x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES + 1));

        assertRejected(() -> new InvocationLogStore(kvStore).loadDocument(), "exceeds");
    }

    @Test
    public void shouldRejectOversizedInvocationLogDocumentBeforeWritingIt() {
        InvocationLogDocument document = new InvocationLogDocument();
        document.getItems().add(log("x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES)));

        assertRejected(() -> new InvocationLogStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "exceeds");
    }

    @Test
    public void shouldTrimOldInvocationLogs() {
        InvocationLogStore store = new InvocationLogStore(new InMemoryRuntimeKvStore());
        InvocationLogDocument document = new InvocationLogDocument();
        List<CapabilityInvocationLog> logs = new ArrayList<CapabilityInvocationLog>();
        for (int i = 0; i < 1000; i++) {
            logs.add(log("log-" + i));
        }
        document.setItems(logs);
        store.saveDocument(document);

        for (int i = 1000; i < 1005; i++) {
            store.append(log("log-" + i));
        }

        assertEquals(1000, store.list().size());
        assertEquals("log-5", store.list().get(0).getId());
    }

    @Test
    public void shouldTruncateNewAndLegacyInvocationErrors() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        String oversizedError = repeat("x", RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS + 100);
        kvStore.put(InvocationLogStore.KEY,
                "{\"items\":[{\"id\":\"legacy\",\"errorMessage\":\"" + oversizedError + "\"}]}");
        InvocationLogStore store = new InvocationLogStore(kvStore);

        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(store.list().get(0).getErrorMessage()));

        CapabilityInvocationLog current = log("current");
        current.setErrorMessage(oversizedError);
        store.append(current);

        InvocationLogDocument persisted = new Gson().fromJson(
                kvStore.get(InvocationLogStore.KEY).get(), InvocationLogDocument.class);
        assertEquals(2, persisted.getItems().size());
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(0).getErrorMessage()));
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(1).getErrorMessage()));
    }

    @Test
    public void shouldRetryAppendWhenConcurrentInvocationLogWriteWinsFirst() {
        StaleInvocationLogKvStore kvStore = new StaleInvocationLogKvStore(documentJson(log("external")));
        InvocationLogStore store = new InvocationLogStore(kvStore);

        store.append(log("local"));

        assertEquals(2, store.list().size());
        assertTrue(store.list().stream().anyMatch(item -> "external".equals(item.getId())));
        assertTrue(store.list().stream().anyMatch(item -> "local".equals(item.getId())));
        assertEquals(2, kvStore.getInvocationLogCompareAndSetCount());
    }

    private CapabilityInvocationLog log(String id) {
        CapabilityInvocationLog log = new CapabilityInvocationLog();
        log.setId(id);
        return log;
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

    private String documentJson(CapabilityInvocationLog... logs) {
        InvocationLogDocument document = new InvocationLogDocument();
        document.setItems(Arrays.asList(logs));
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

    private static class StaleInvocationLogKvStore extends InMemoryRuntimeKvStore {
        private final String staleValue;
        private boolean staleInjected;
        private int invocationLogCompareAndSetCount;

        private StaleInvocationLogKvStore(String staleValue) {
            this.staleValue = staleValue;
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            if (!InvocationLogStore.KEY.equals(key)) {
                return super.compareAndSet(key, expectedValue, value);
            }
            invocationLogCompareAndSetCount++;
            if (!staleInjected) {
                put(key, staleValue);
                staleInjected = true;
                return false;
            }
            return super.compareAndSet(key, expectedValue, value);
        }

        private int getInvocationLogCompareAndSetCount() {
            return invocationLogCompareAndSetCount;
        }
    }
}
