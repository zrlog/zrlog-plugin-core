package com.zrlog.plugincore.server.runtime.scheduler;

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

public class AutomationRunStore {

    public static final String KEY = "plugin.runtime.automationRuns.v2";
    private static final int MAX_ITEMS = 500;
    private static final int STORE_UPDATE_RETRIES = 3;

    private final KvRepository kvStore;
    private final Gson gson = new Gson();

    public AutomationRunStore(KvRepository kvStore) {
        this.kvStore = kvStore;
    }

    public List<PluginAutomationRun> list() {
        return loadDocument().getItems();
    }

    public void append(PluginAutomationRun run) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationRunDocumentSnapshot snapshot = loadSnapshot();
            snapshot.getDocument().setItems(appendItems(snapshot.getDocument().getItems(), run));
            if (saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
    }

    private List<PluginAutomationRun> appendItems(List<PluginAutomationRun> currentItems, PluginAutomationRun run) {
        List<PluginAutomationRun> items = new ArrayList<>(currentItems);
        normalizeRun(run);
        items.add(run);
        while (items.size() > MAX_ITEMS) {
            items.remove(0);
        }
        return items;
    }

    public AutomationRunDocument loadDocument() {
        return parseDocument(kvStore.get(KEY));
    }

    public AutomationRunDocumentSnapshot loadSnapshot() {
        Optional<String> raw = kvStore.get(KEY);
        return new AutomationRunDocumentSnapshot(raw, parseDocument(raw));
    }

    private AutomationRunDocument parseDocument(Optional<String> json) {
        if (!json.isPresent() || json.get().trim().isEmpty()) {
            AutomationRunDocument document = new AutomationRunDocument();
            document.setUpdatedAt(RuntimeDates.nowString());
            return document;
        }
        PersistentJsonLimits.requireUtf8Length("Automation run document", json.get(),
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return normalizeDocument(gson.fromJson(json.get(), AutomationRunDocument.class));
    }

    public void saveDocument(AutomationRunDocument document) {
        kvStore.put(KEY, documentJson(document));
    }

    public boolean saveDocumentIfUnchanged(AutomationRunDocumentSnapshot snapshot) {
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

    private String documentJson(AutomationRunDocument document) {
        document = normalizeDocument(document);
        document.setSchema(KEY);
        document.setVersion(2);
        document.setUpdatedAt(RuntimeDates.nowString());
        String json = gson.toJson(document);
        PersistentJsonLimits.requireUtf8Length("Automation run document", json,
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return json;
    }

    private AutomationRunDocument normalizeDocument(AutomationRunDocument document) {
        if (document == null) {
            document = new AutomationRunDocument();
        }
        List<PluginAutomationRun> currentItems = document.getItems();
        if (currentItems == null || currentItems.isEmpty()) {
            document.setItems(new ArrayList<>());
            return document;
        }
        int fromIndex = Math.max(0, currentItems.size() - MAX_ITEMS);
        List<PluginAutomationRun> items = new ArrayList<>(currentItems.size() - fromIndex);
        for (int i = fromIndex; i < currentItems.size(); i++) {
            PluginAutomationRun run = currentItems.get(i);
            if (run == null) {
                continue;
            }
            normalizeRun(run);
            items.add(run);
        }
        document.setItems(items);
        return document;
    }

    private void normalizeRun(PluginAutomationRun run) {
        if (run != null) {
            run.setErrorMessage(RuntimeTextLimits.truncateErrorMessage(run.getErrorMessage()));
        }
    }

    public static class AutomationRunDocumentSnapshot {
        private final Optional<String> rawJson;
        private final AutomationRunDocument document;

        public AutomationRunDocumentSnapshot(Optional<String> rawJson, AutomationRunDocument document) {
            this.rawJson = rawJson;
            this.document = document;
        }

        public Optional<String> getRawJson() {
            return rawJson;
        }

        public AutomationRunDocument getDocument() {
            return document;
        }
    }
}
