package com.zrlog.plugincore.server.runtime.scheduler;

import com.google.gson.Gson;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugincore.server.runtime.store.ConditionalKvRepository;
import com.zrlog.plugincore.server.runtime.util.RuntimeDates;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AutomationStore {

    public static final String KEY = "plugin.runtime.automations.v2";

    private final KvRepository kvStore;
    private final Gson gson = new Gson();

    public AutomationStore(KvRepository kvStore) {
        this.kvStore = kvStore;
    }

    public List<PluginAutomation> list() {
        return loadDocument().getItems();
    }

    public void saveAll(List<PluginAutomation> automations) {
        AutomationDocument document = new AutomationDocument();
        document.setItems(new ArrayList<>(automations));
        saveDocument(document);
    }

    public AutomationDocument loadDocument() {
        return parseDocument(kvStore.get(KEY));
    }

    public AutomationDocumentSnapshot loadSnapshot() {
        Optional<String> raw = kvStore.get(KEY);
        return new AutomationDocumentSnapshot(raw, parseDocument(raw));
    }

    private AutomationDocument parseDocument(Optional<String> json) {
        if (!json.isPresent() || json.get().trim().isEmpty()) {
            AutomationDocument document = new AutomationDocument();
            document.setUpdatedAt(RuntimeDates.nowString());
            return document;
        }
        PersistentJsonLimits.requireUtf8Length("Automation document", json.get(),
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        AutomationDocument document = normalizeDocument(gson.fromJson(json.get(), AutomationDocument.class));
        validateDocumentItemCount(document);
        return document;
    }

    public void saveDocument(AutomationDocument document) {
        kvStore.put(KEY, documentJson(document));
    }

    public boolean saveDocumentIfUnchanged(AutomationDocumentSnapshot snapshot) {
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

    private String documentJson(AutomationDocument document) {
        document = normalizeDocument(document);
        validateDocumentItemCount(document);
        document.setSchema(KEY);
        document.setVersion(2);
        document.setUpdatedAt(RuntimeDates.nowString());
        String json = gson.toJson(document);
        PersistentJsonLimits.requireUtf8Length("Automation document", json,
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return json;
    }

    private void validateDocumentItemCount(AutomationDocument document) {
        if (document.getItems().size() > PersistentJsonLimits.MAX_PLUGIN_ENTRIES) {
            throw new IllegalArgumentException("Automation document item count exceeds "
                    + PersistentJsonLimits.MAX_PLUGIN_ENTRIES);
        }
    }

    private AutomationDocument normalizeDocument(AutomationDocument document) {
        if (document == null) {
            document = new AutomationDocument();
        }
        List<PluginAutomation> currentItems = document.getItems();
        if (currentItems == null || currentItems.isEmpty()) {
            document.setItems(new ArrayList<>());
            return document;
        }
        List<PluginAutomation> items = new ArrayList<>(currentItems.size());
        for (PluginAutomation automation : currentItems) {
            if (automation != null) {
                items.add(automation);
            }
        }
        document.setItems(items);
        return document;
    }

    public static class AutomationDocumentSnapshot {
        private final Optional<String> rawJson;
        private final AutomationDocument document;

        public AutomationDocumentSnapshot(Optional<String> rawJson, AutomationDocument document) {
            this.rawJson = rawJson;
            this.document = document;
        }

        public Optional<String> getRawJson() {
            return rawJson;
        }

        public AutomationDocument getDocument() {
            return document;
        }
    }
}
