package com.zrlog.plugincore.server.runtime.invocation;

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

public class InvocationLogStore {

    public static final String KEY = "plugin.runtime.invocationLogs.v2";
    private static final int MAX_ITEMS = 1000;
    private static final int STORE_UPDATE_RETRIES = 3;

    private final KvRepository kvStore;
    private final Gson gson = new Gson();

    public InvocationLogStore(KvRepository kvStore) {
        this.kvStore = kvStore;
    }

    public List<CapabilityInvocationLog> list() {
        return loadDocument().getItems();
    }

    public void append(CapabilityInvocationLog log) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            InvocationLogDocumentSnapshot snapshot = loadSnapshot();
            snapshot.getDocument().setItems(appendItems(snapshot.getDocument().getItems(), log));
            if (saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
    }

    private List<CapabilityInvocationLog> appendItems(List<CapabilityInvocationLog> currentItems, CapabilityInvocationLog log) {
        List<CapabilityInvocationLog> items = new ArrayList<CapabilityInvocationLog>(currentItems);
        normalizeLog(log);
        items.add(log);
        while (items.size() > MAX_ITEMS) {
            items.remove(0);
        }
        return items;
    }

    public InvocationLogDocument loadDocument() {
        return parseDocument(kvStore.get(KEY));
    }

    public InvocationLogDocumentSnapshot loadSnapshot() {
        Optional<String> raw = kvStore.get(KEY);
        return new InvocationLogDocumentSnapshot(raw, parseDocument(raw));
    }

    private InvocationLogDocument parseDocument(Optional<String> json) {
        if (!json.isPresent() || json.get().trim().isEmpty()) {
            InvocationLogDocument document = new InvocationLogDocument();
            document.setUpdatedAt(RuntimeDates.nowString());
            return document;
        }
        PersistentJsonLimits.requireUtf8Length("Invocation log document", json.get(),
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return normalizeDocument(gson.fromJson(json.get(), InvocationLogDocument.class));
    }

    public void saveDocument(InvocationLogDocument document) {
        kvStore.put(KEY, documentJson(document));
    }

    public boolean saveDocumentIfUnchanged(InvocationLogDocumentSnapshot snapshot) {
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

    private String documentJson(InvocationLogDocument document) {
        document = normalizeDocument(document);
        document.setSchema(KEY);
        document.setVersion(2);
        document.setUpdatedAt(RuntimeDates.nowString());
        String json = gson.toJson(document);
        PersistentJsonLimits.requireUtf8Length("Invocation log document", json,
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return json;
    }

    private InvocationLogDocument normalizeDocument(InvocationLogDocument document) {
        if (document == null) {
            document = new InvocationLogDocument();
        }
        List<CapabilityInvocationLog> currentItems = document.getItems();
        if (currentItems == null || currentItems.isEmpty()) {
            document.setItems(new ArrayList<CapabilityInvocationLog>());
            return document;
        }
        int fromIndex = Math.max(0, currentItems.size() - MAX_ITEMS);
        List<CapabilityInvocationLog> items = new ArrayList<CapabilityInvocationLog>(currentItems.size() - fromIndex);
        for (int i = fromIndex; i < currentItems.size(); i++) {
            CapabilityInvocationLog log = currentItems.get(i);
            if (log == null) {
                continue;
            }
            normalizeLog(log);
            items.add(log);
        }
        document.setItems(items);
        return document;
    }

    private void normalizeLog(CapabilityInvocationLog log) {
        if (log != null) {
            log.setErrorMessage(RuntimeTextLimits.truncateErrorMessage(log.getErrorMessage()));
        }
    }

    public static class InvocationLogDocumentSnapshot {
        private final Optional<String> rawJson;
        private final InvocationLogDocument document;

        public InvocationLogDocumentSnapshot(Optional<String> rawJson, InvocationLogDocument document) {
            this.rawJson = rawJson;
            this.document = document;
        }

        public Optional<String> getRawJson() {
            return rawJson;
        }

        public InvocationLogDocument getDocument() {
            return document;
        }
    }
}
