package com.zrlog.plugincore.server.runtime.notification;

import com.google.gson.Gson;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;
import org.junit.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NotificationDeliveryStoreTest {

    @Test
    public void shouldNormalizeNullNotificationDeliveryDocumentsAndItems() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(NotificationDeliveryStore.KEY, "null");
        NotificationDeliveryStore store = new NotificationDeliveryStore(kvStore);

        assertEquals(0, store.list().size());

        kvStore.put(NotificationDeliveryStore.KEY,
                "{\"items\":[null,{\"id\":\"delivery-a\"},null]}");
        NotificationDeliveryDocument loaded = store.loadDocument();
        assertEquals(1, loaded.getItems().size());
        assertEquals("delivery-a", loaded.getItems().get(0).getId());

        store.saveDocument(loaded);
        NotificationDeliveryDocument persisted = new Gson().fromJson(
                kvStore.get(NotificationDeliveryStore.KEY).get(), NotificationDeliveryDocument.class);
        assertEquals(1, persisted.getItems().size());

        store.saveDocument(null);
        persisted = new Gson().fromJson(
                kvStore.get(NotificationDeliveryStore.KEY).get(), NotificationDeliveryDocument.class);
        assertEquals(0, persisted.getItems().size());
    }

    @Test
    public void shouldRejectOversizedStoredNotificationDeliveryDocumentBeforeParsingIt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(NotificationDeliveryStore.KEY,
                "x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES + 1));

        assertRejected(() -> new NotificationDeliveryStore(kvStore).loadDocument(), "exceeds");
    }

    @Test
    public void shouldRejectOversizedNotificationDeliveryDocumentBeforeWritingIt() {
        NotificationDeliveryDocument document = new NotificationDeliveryDocument();
        document.getItems().add(delivery("x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES)));

        assertRejected(() -> new NotificationDeliveryStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "exceeds");
    }

    @Test
    public void shouldTruncateNewAndLegacyNotificationErrors() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        String oversizedError = repeat("x", RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS + 100);
        kvStore.put(NotificationDeliveryStore.KEY,
                "{\"items\":[{\"id\":\"legacy\",\"errorMessage\":\"" + oversizedError + "\"}]}");
        NotificationDeliveryStore store = new NotificationDeliveryStore(kvStore);

        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(store.list().get(0).getErrorMessage()));

        NotificationDelivery current = delivery("current");
        current.setErrorMessage(oversizedError);
        store.append(current);

        NotificationDeliveryDocument persisted = new Gson().fromJson(
                kvStore.get(NotificationDeliveryStore.KEY).get(), NotificationDeliveryDocument.class);
        assertEquals(2, persisted.getItems().size());
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(0).getErrorMessage()));
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(1).getErrorMessage()));
    }

    @Test
    public void shouldRetryNotificationDeliveryAppendWhenConcurrentWriteWinsFirst() {
        StaleNotificationDeliveryKvStore kvStore = new StaleNotificationDeliveryKvStore(documentJson(delivery("external")));
        NotificationDeliveryStore store = new NotificationDeliveryStore(kvStore);

        store.append(delivery("local"));

        assertEquals(2, store.list().size());
        assertTrue(store.list().stream().anyMatch(item -> "external".equals(item.getId())));
        assertTrue(store.list().stream().anyMatch(item -> "local".equals(item.getId())));
        assertEquals(2, kvStore.getNotificationDeliveryCompareAndSetCount());
    }

    private NotificationDelivery delivery(String id) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setId(id);
        return delivery;
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

    private String documentJson(NotificationDelivery... deliveries) {
        NotificationDeliveryDocument document = new NotificationDeliveryDocument();
        document.setItems(Arrays.asList(deliveries));
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

    private static class StaleNotificationDeliveryKvStore extends InMemoryRuntimeKvStore {
        private final String staleValue;
        private boolean staleInjected;
        private int notificationDeliveryCompareAndSetCount;

        private StaleNotificationDeliveryKvStore(String staleValue) {
            this.staleValue = staleValue;
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            if (!NotificationDeliveryStore.KEY.equals(key)) {
                return super.compareAndSet(key, expectedValue, value);
            }
            notificationDeliveryCompareAndSetCount++;
            if (!staleInjected) {
                put(key, staleValue);
                staleInjected = true;
                return false;
            }
            return super.compareAndSet(key, expectedValue, value);
        }

        private int getNotificationDeliveryCompareAndSetCount() {
            return notificationDeliveryCompareAndSetCount;
        }
    }
}
