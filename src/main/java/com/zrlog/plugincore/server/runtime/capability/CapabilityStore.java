package com.zrlog.plugincore.server.runtime.capability;

import com.google.gson.Gson;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugincore.server.runtime.store.ConditionalKvRepository;
import com.zrlog.plugincore.server.runtime.util.RuntimeDates;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CapabilityStore {

    public static final String KEY = "plugin.runtime.capabilities";
    private static final int STORE_UPDATE_RETRIES = 3;
    private static final Pattern DOTTED_KEY_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+$");

    private final KvRepository kvStore;
    private final Gson gson = new Gson();

    public CapabilityStore(KvRepository kvStore) {
        this.kvStore = kvStore;
    }

    public void register(PluginCapability capability) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            CapabilityDocumentSnapshot snapshot = loadSnapshot();
            CapabilityMutationResult result = registerItems(snapshot.getDocument().getItems(), capability);
            if (!result.isChanged()) {
                return;
            }
            snapshot.getDocument().setItems(result.getItems());
            if (saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
        throw new IllegalStateException("Failed to register capability due to concurrent modification");
    }

    private CapabilityMutationResult registerItems(List<PluginCapability> currentItems, PluginCapability capability) {
        List<PluginCapability> next = new ArrayList<>();
        for (PluginCapability item : currentItems) {
            if (!sameIdentity(item, capability)) {
                next.add(item);
            }
        }
        normalize(capability);
        next.add(capability);
        return new CapabilityMutationResult(next, !sameCapabilityItems(currentItems, next));
    }

    public void replacePluginCapabilities(String pluginId, List<PluginCapability> capabilities) {
        replacePluginCapabilities(pluginId, (List<String>) null, capabilities);
    }

    public void replacePluginCapabilities(String pluginId, String pluginName, List<PluginCapability> capabilities) {
        replacePluginCapabilities(pluginId, pluginName == null ? null : Arrays.asList(pluginName), capabilities);
    }

    public void replacePluginCapabilities(String pluginId, List<String> pluginNames, List<PluginCapability> capabilities) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            CapabilityDocumentSnapshot snapshot = loadSnapshot();
            CapabilityMutationResult result = replacePluginCapabilityItems(
                    snapshot.getDocument().getItems(), pluginId, pluginNames, capabilities);
            if (!result.isChanged()) {
                return;
            }
            snapshot.getDocument().setItems(result.getItems());
            if (saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
        throw new IllegalStateException("Failed to replace plugin capabilities due to concurrent modification");
    }

    public void validatePluginCapabilitiesReplacement(String pluginId,
                                                      List<String> pluginNames,
                                                      List<PluginCapability> capabilities) {
        CapabilityDocumentSnapshot snapshot = loadSnapshot();
        CapabilityMutationResult result = replacePluginCapabilityItems(
                snapshot.getDocument().getItems(), pluginId, pluginNames, capabilities);
        snapshot.getDocument().setItems(result.getItems());
        documentJson(snapshot.getDocument());
    }

    private CapabilityMutationResult replacePluginCapabilityItems(List<PluginCapability> currentItems,
                                                                  String pluginId,
                                                                  List<String> pluginNames,
                                                                  List<PluginCapability> capabilities) {
        List<PluginCapability> next = new ArrayList<>();
        for (PluginCapability item : currentItems) {
            if (!samePlugin(pluginId, pluginNames, item)) {
                next.add(item);
            }
        }
        if (capabilities != null) {
            for (PluginCapability capability : capabilities) {
                normalize(capability);
                next.add(capability);
            }
        }
        return new CapabilityMutationResult(next, !sameCapabilityItems(currentItems, next));
    }

    private boolean sameCapabilityItems(List<PluginCapability> left, List<PluginCapability> right) {
        return Objects.equals(gson.toJson(left), gson.toJson(right));
    }

    private boolean samePlugin(String pluginId, List<String> pluginNames, PluginCapability capability) {
        if (Objects.equals(pluginId, capability.getPluginId())) {
            return true;
        }
        if (pluginNames == null) {
            return false;
        }
        for (String pluginName : pluginNames) {
            if (pluginName != null && !pluginName.trim().isEmpty()
                    && Objects.equals(pluginName, capability.getPluginName())) {
                return true;
            }
        }
        return false;
    }

    public List<PluginCapability> listAll() {
        return loadDocument().getItems();
    }

    public List<PluginCapability> listByExposure(String exposure) {
        return listAll().stream()
                .filter(item -> item.getExposure() != null && item.getExposure().contains(exposure))
                .collect(Collectors.toList());
    }

    public List<PluginCapability> listByType(String type) {
        return listByType(listAll(), type);
    }

    public static List<PluginCapability> listByType(List<PluginCapability> items, String type) {
        if (items == null) {
            return new ArrayList<>();
        }
        return items.stream()
                .filter(item -> Objects.equals(type, item.getType()))
                .collect(Collectors.toList());
    }

    public Optional<PluginCapability> find(String pluginId, String key) {
        return find(listAll(), pluginId, key);
    }

    public static Optional<PluginCapability> find(List<PluginCapability> items, String pluginId, String key) {
        if (items == null) {
            return Optional.empty();
        }
        return items.stream()
                .filter(item -> Objects.equals(pluginId, item.getPluginId()))
                .filter(item -> Objects.equals(key, item.getKey()))
                .findFirst();
    }

    public boolean isAmbiguous(String key) {
        return listAll().stream()
                .filter(item -> Objects.equals(key, item.getKey()))
                .map(PluginCapability::getPluginId)
                .filter(Objects::nonNull)
                .distinct()
                .count() > 1;
    }

    public CapabilityDocument loadDocument() {
        return parseDocument(kvStore.get(KEY));
    }

    public CapabilityDocumentSnapshot loadSnapshot() {
        Optional<String> raw = kvStore.get(KEY);
        return new CapabilityDocumentSnapshot(raw, parseDocument(raw));
    }

    private CapabilityDocument parseDocument(Optional<String> json) {
        if (!json.isPresent() || json.get().trim().isEmpty()) {
            CapabilityDocument document = new CapabilityDocument();
            document.setUpdatedAt(RuntimeDates.nowString());
            return document;
        }
        PersistentJsonLimits.requireUtf8Length("Capability document", json.get(),
                PersistentJsonLimits.MAX_CAPABILITY_DOCUMENT_BYTES);
        CapabilityDocument document = normalizeDocument(gson.fromJson(json.get(), CapabilityDocument.class));
        validateDocumentItemCount(document);
        return document;
    }

    public void saveDocument(CapabilityDocument document) {
        kvStore.put(KEY, documentJson(document));
    }

    public boolean saveDocumentIfUnchanged(CapabilityDocumentSnapshot snapshot) {
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

    private String documentJson(CapabilityDocument document) {
        document = normalizeDocument(document);
        validateDocumentItemCount(document);
        document.setSchema(KEY);
        document.setVersion(1);
        document.setUpdatedAt(RuntimeDates.nowString());
        String json = gson.toJson(document);
        PersistentJsonLimits.requireUtf8Length("Capability document", json,
                PersistentJsonLimits.MAX_CAPABILITY_DOCUMENT_BYTES);
        return json;
    }

    private void validateDocumentItemCount(CapabilityDocument document) {
        if (document.getItems().size() > PersistentJsonLimits.MAX_CAPABILITY_ENTRIES) {
            throw new IllegalArgumentException("Capability document item count exceeds "
                    + PersistentJsonLimits.MAX_CAPABILITY_ENTRIES);
        }
    }

    private CapabilityDocument normalizeDocument(CapabilityDocument document) {
        if (document == null) {
            document = new CapabilityDocument();
        }
        List<PluginCapability> currentItems = document.getItems();
        if (currentItems == null || currentItems.isEmpty()) {
            document.setItems(new ArrayList<>());
            return document;
        }
        List<PluginCapability> items = new ArrayList<>(currentItems.size());
        for (PluginCapability capability : currentItems) {
            if (capability != null) {
                items.add(capability);
            }
        }
        document.setItems(items);
        return document;
    }

    public static boolean canGenerateLegacyCapability(String serviceName) {
        return serviceName != null && DOTTED_KEY_PATTERN.matcher(serviceName).matches();
    }

    public static Optional<PluginCapability> fromLegacyService(String pluginId, String pluginName, String serviceName) {
        if (!canGenerateLegacyCapability(serviceName)) {
            return Optional.empty();
        }
        PluginCapability capability = new PluginCapability();
        capability.setPluginId(pluginId);
        capability.setPluginName(pluginName);
        capability.setKey(serviceName);
        capability.setServiceName(serviceName);
        capability.setType("service");
        capability.setLabel(serviceName);
        capability.setExposure(Arrays.asList("internal"));
        capability.setRiskLevel("low");
        capability.setReadOnly(Boolean.FALSE);
        capability.setRequiresConfirmation(Boolean.FALSE);
        capability.setTimeoutSeconds(PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT_SECONDS);
        capability.setConcurrency(1);
        capability.setEnabled(Boolean.TRUE);
        capability.setLegacy(Boolean.TRUE);
        capability.setGenerated(Boolean.TRUE);
        return Optional.of(capability);
    }

    private void normalize(PluginCapability capability) {
        if (capability.getExposure() == null) {
            capability.setExposure(new ArrayList<>());
        }
        if (capability.getEnabled() == null) {
            capability.setEnabled(Boolean.TRUE);
        }
        if (capability.getReadOnly() == null) {
            capability.setReadOnly(Boolean.FALSE);
        }
        if (capability.getRequiresConfirmation() == null) {
            capability.setRequiresConfirmation(Boolean.FALSE);
        }
        if (capability.getTimeoutSeconds() == null) {
            capability.setTimeoutSeconds(PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT_SECONDS);
        }
        if (capability.getConcurrency() == null) {
            capability.setConcurrency(1);
        }
        CapabilityRiskPolicy.normalize(capability);
    }

    private boolean sameIdentity(PluginCapability left, PluginCapability right) {
        return Objects.equals(left.getPluginId(), right.getPluginId()) && Objects.equals(left.getKey(), right.getKey());
    }

    private static class CapabilityMutationResult {
        private final List<PluginCapability> items;
        private final boolean changed;

        private CapabilityMutationResult(List<PluginCapability> items, boolean changed) {
            this.items = items;
            this.changed = changed;
        }

        public List<PluginCapability> getItems() {
            return items;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    public static class CapabilityDocumentSnapshot {
        private final Optional<String> rawJson;
        private final CapabilityDocument document;

        public CapabilityDocumentSnapshot(Optional<String> rawJson, CapabilityDocument document) {
            this.rawJson = rawJson;
            this.document = document;
        }

        public Optional<String> getRawJson() {
            return rawJson;
        }

        public CapabilityDocument getDocument() {
            return document;
        }
    }
}
