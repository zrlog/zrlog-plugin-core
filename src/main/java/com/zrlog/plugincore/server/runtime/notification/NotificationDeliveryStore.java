package com.zrlog.plugincore.server.runtime.notification;

import com.google.gson.Gson;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugincore.server.runtime.store.ConditionalKvRepository;
import com.zrlog.plugincore.server.runtime.util.RuntimeDates;
import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class NotificationDeliveryStore {

    public static final String KEY = "plugin.runtime.notificationDeliveries.v2";
    private static final int MAX_ITEMS = 500;
    private static final int STORE_UPDATE_RETRIES = 3;

    private final KvRepository kvStore;
    private final Gson gson = new Gson();

    public NotificationDeliveryStore(KvRepository kvStore) {
        this.kvStore = kvStore;
    }

    public List<NotificationDelivery> list() {
        return loadDocument().getItems();
    }

    public void append(NotificationDelivery delivery) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            NotificationDeliveryDocumentSnapshot snapshot = loadSnapshot();
            snapshot.getDocument().setItems(appendItems(snapshot.getDocument().getItems(), delivery));
            if (saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
    }

    private List<NotificationDelivery> appendItems(List<NotificationDelivery> currentItems, NotificationDelivery delivery) {
        List<NotificationDelivery> items = new ArrayList<>(currentItems);
        normalizeDelivery(delivery);
        items.add(delivery);
        while (items.size() > MAX_ITEMS) {
            items.remove(0);
        }
        return items;
    }

    public NotificationDeliveryDocument loadDocument() {
        return parseDocument(kvStore.get(KEY));
    }

    public NotificationDeliveryDocumentSnapshot loadSnapshot() {
        Optional<String> raw = kvStore.get(KEY);
        return new NotificationDeliveryDocumentSnapshot(raw, parseDocument(raw));
    }

    private NotificationDeliveryDocument parseDocument(Optional<String> json) {
        if (!json.isPresent() || json.get().trim().isEmpty()) {
            NotificationDeliveryDocument document = new NotificationDeliveryDocument();
            document.setUpdatedAt(RuntimeDates.nowString());
            return document;
        }
        PersistentJsonLimits.requireUtf8Length("Notification delivery document", json.get(),
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return normalizeDocument(gson.fromJson(json.get(), NotificationDeliveryDocument.class));
    }

    public void saveDocument(NotificationDeliveryDocument document) {
        kvStore.put(KEY, documentJson(document));
    }

    public boolean saveDocumentIfUnchanged(NotificationDeliveryDocumentSnapshot snapshot) {
        String json = documentJson(snapshot.getDocument());
        if (kvStore instanceof ConditionalKvRepository) {
            return ((ConditionalKvRepository) kvStore).compareAndSet(KEY, snapshot.getRawJson(), json);
        }
        Optional<String> current = kvStore.get(KEY);
        if (!Objects.equals(current, snapshot.getRawJson())) {
            return false;
        }
        kvStore.put(KEY, json);
        return true;
    }

    private String documentJson(NotificationDeliveryDocument document) {
        document = normalizeDocument(document);
        document.setSchema(KEY);
        document.setVersion(2);
        document.setUpdatedAt(RuntimeDates.nowString());
        String json = gson.toJson(document);
        PersistentJsonLimits.requireUtf8Length("Notification delivery document", json,
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return json;
    }

    private NotificationDeliveryDocument normalizeDocument(NotificationDeliveryDocument document) {
        if (document == null) {
            document = new NotificationDeliveryDocument();
        }
        List<NotificationDelivery> currentItems = document.getItems();
        if (currentItems == null || currentItems.isEmpty()) {
            document.setItems(new ArrayList<>());
            return document;
        }
        int fromIndex = Math.max(0, currentItems.size() - MAX_ITEMS);
        List<NotificationDelivery> items = new ArrayList<>(currentItems.size() - fromIndex);
        for (int i = fromIndex; i < currentItems.size(); i++) {
            NotificationDelivery delivery = currentItems.get(i);
            if (delivery == null) {
                continue;
            }
            normalizeDelivery(delivery);
            items.add(delivery);
        }
        document.setItems(items);
        return document;
    }

    private void normalizeDelivery(NotificationDelivery delivery) {
        if (delivery != null) {
            delivery.setErrorMessage(RuntimeTextLimits.truncateErrorMessage(delivery.getErrorMessage()));
        }
    }

    public static class NotificationDeliveryDocumentSnapshot {
        private final Optional<String> rawJson;
        private final NotificationDeliveryDocument document;

        public NotificationDeliveryDocumentSnapshot(Optional<String> rawJson, NotificationDeliveryDocument document) {
            this.rawJson = rawJson;
            this.document = document;
        }

        public Optional<String> getRawJson() {
            return rawJson;
        }

        public NotificationDeliveryDocument getDocument() {
            return document;
        }
    }
}
