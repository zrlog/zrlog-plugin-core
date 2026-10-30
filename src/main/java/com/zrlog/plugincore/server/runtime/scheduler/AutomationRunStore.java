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
    private static final int STORE_UPDATE_RETRIES = 16;
    private static final int MAX_RUNNING_ITEMS = 128;

    private final KvRepository kvStore;
    private final Gson gson = new Gson();

    public AutomationRunStore(KvRepository kvStore) {
        this.kvStore = kvStore;
    }

    public List<PluginAutomationRun> list() {
        return loadDocument().getItems();
    }

    public Optional<PluginAutomationRun> find(String id) {
        return list().stream().filter(run -> Objects.equals(id, run.getId())).findFirst();
    }

    /** The durable running record must exist before invoking a plugin.
     * The monitor reduces contention between this tick's workers; CAS remains the cross-process guard. */
    public synchronized void begin(PluginAutomationRun run) {
        if (run.getId() == null || !"running".equals(run.getStatus())) {
            throw new IllegalArgumentException("A running automation record requires an id");
        }
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationRunDocumentSnapshot snapshot = loadSnapshot();
            List<PluginAutomationRun> items = snapshot.getDocument().getItems();
            if (items.stream().anyMatch(item -> Objects.equals(run.getId(), item.getId()))) {
                throw new IllegalStateException("Automation run already exists");
            }
            if (items.stream().filter(AutomationRunStore::isRunning).count() >= MAX_RUNNING_ITEMS) {
                throw new IllegalStateException("Too many unfinished automation runs");
            }
            items.add(run);
            if (saveDocumentIfUnchanged(snapshot)) return;
        }
        throw new IllegalStateException("Unable to persist automation run before execution");
    }

    /** A late worker cannot overwrite recovery's terminal outcome. */
    public synchronized boolean complete(PluginAutomationRun run) {
        if (run.getId() == null || isRunning(run)) {
            throw new IllegalArgumentException("A completed automation record requires an id and result");
        }
        for (int attempt = 0; attempt < STORE_UPDATE_RETRIES; attempt++) {
            AutomationRunDocumentSnapshot snapshot = loadSnapshot();
            List<PluginAutomationRun> items = snapshot.getDocument().getItems();
            for (int i = 0; i < items.size(); i++) {
                if (!Objects.equals(run.getId(), items.get(i).getId())) continue;
                if (!isRunning(items.get(i))) return false;
                items.set(i, run);
                if (saveDocumentIfUnchanged(snapshot)) return true;
                break;
            }
        }
        throw new IllegalStateException("Unable to persist automation result");
    }

    private static boolean isRunning(PluginAutomationRun run) {
        return "running".equals(run.getStatus());
    }

    public synchronized void append(PluginAutomationRun run) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationRunDocumentSnapshot snapshot = loadSnapshot();
            snapshot.getDocument().setItems(appendItems(snapshot.getDocument().getItems(), run));
            if (saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
        throw new IllegalStateException("Unable to append automation result");
    }

    private List<PluginAutomationRun> appendItems(List<PluginAutomationRun> currentItems, PluginAutomationRun run) {
        List<PluginAutomationRun> items = new ArrayList<>(currentItems);
        normalizeRun(run);
        items.add(run);
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
        List<PluginAutomationRun> items = new ArrayList<>();
        int completed = 0;
        for (int i = currentItems.size() - 1; i >= 0; i--) {
            PluginAutomationRun run = currentItems.get(i);
            if (run == null) {
                continue;
            }
            if (!isRunning(run) && ++completed > MAX_ITEMS) continue;
            normalizeRun(run);
            items.add(run);
        }
        java.util.Collections.reverse(items);
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
