package com.zrlog.plugincore.server.runtime.state;

import com.google.gson.Gson;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugincore.server.runtime.lock.DistributedLock;
import com.zrlog.plugincore.server.runtime.store.ConditionalKvRepository;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.util.RuntimeDates;
import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;
import com.zrlog.plugincore.server.type.PluginStatus;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class PluginRuntimeStateStore {

    public static final String KEY = "plugin.runtime.states";
    private static final int STORE_UPDATE_RETRIES = 10;
    private static final long STORE_UPDATE_RETRY_BACKOFF_MS = 5L;
    private static final long STORE_UPDATE_LOCK_WAIT_MS = 5000L;
    private static final String STORE_UPDATE_LOCK_KEY = "plugin_runtime_states";
    private static final Object STORE_UPDATE_LOCK = new Object();

    private final KvRepository kvStore;
    private final Lock updateLock;
    private final Gson gson = new Gson();

    public PluginRuntimeStateStore(KvRepository kvStore) {
        this(kvStore, newUpdateLock(kvStore));
    }

    PluginRuntimeStateStore(KvRepository kvStore, Lock updateLock) {
        this.kvStore = kvStore;
        this.updateLock = updateLock;
    }

    public List<PluginRuntimeState> list() {
        return loadDocument().getItems();
    }

    public Optional<PluginRuntimeState> find(String pluginId) {
        return list().stream()
                .filter(item -> Objects.equals(pluginId, item.getPluginId()))
                .findFirst();
    }

    public void update(String pluginId, Consumer<PluginRuntimeState> consumer) {
        mutateDocument("Failed to update plugin runtime states due to concurrent modification", snapshot -> {
            PluginRuntimeStateDocument document = snapshot.getDocument();
            PluginRuntimeState state = findItem(document.getItems(), pluginId).orElseGet(PluginRuntimeState::new);
            state.setPluginId(pluginId);
            consumer.accept(state);
            document.setItems(upsertItems(document.getItems(), state));
            return StoreMutationResult.changed();
        });
    }

    public void upsert(PluginRuntimeState state) {
        mutateDocument("Failed to update plugin runtime states due to concurrent modification", snapshot -> {
            PluginRuntimeStateDocument document = snapshot.getDocument();
            document.setItems(upsertItems(document.getItems(), state));
            return StoreMutationResult.changed();
        });
    }

    private List<PluginRuntimeState> upsertItems(List<PluginRuntimeState> currentItems, PluginRuntimeState state) {
        List<PluginRuntimeState> items = new ArrayList<PluginRuntimeState>();
        boolean replaced = false;
        Set<String> addedPluginIds = new HashSet<String>();
        for (PluginRuntimeState item : currentItems) {
            if (Objects.equals(item.getPluginId(), state.getPluginId())) {
                if (!replaced) {
                    items.add(state);
                    addedPluginIds.add(state.getPluginId());
                    replaced = true;
                }
            } else {
                if (addedPluginIds.add(item.getPluginId())) {
                    items.add(item);
                }
            }
        }
        if (!replaced) {
            items.add(state);
        }
        return items;
    }

    private Optional<PluginRuntimeState> findItem(List<PluginRuntimeState> items, String pluginId) {
        for (PluginRuntimeState item : items) {
            if (Objects.equals(pluginId, item.getPluginId())) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    public void delete(String pluginId) {
        mutateDocument("Failed to delete plugin runtime state due to concurrent modification", snapshot -> {
            RemoveItemsResult result = removeItems(snapshot.getDocument().getItems(), pluginId);
            if (!result.isRemoved()) {
                return StoreMutationResult.unchanged();
            }
            snapshot.getDocument().setItems(result.getItems());
            return StoreMutationResult.changed();
        });
    }

    public void removeInstances(String pluginId, Predicate<PluginRuntimeInstanceState> predicate) {
        mutateDocument("Failed to remove plugin runtime instances due to concurrent modification", snapshot -> {
            Optional<PluginRuntimeState> optional = findItem(snapshot.getDocument().getItems(), pluginId);
            if (!optional.isPresent() || optional.get().getInstances() == null || optional.get().getInstances().isEmpty()) {
                return StoreMutationResult.unchanged();
            }
            PluginRuntimeState state = optional.get();
            boolean removed = state.getInstances().removeIf(predicate);
            if (!removed) {
                return StoreMutationResult.unchanged();
            }
            PluginRuntimeStateAggregator.aggregate(state);
            snapshot.getDocument().setItems(upsertItems(snapshot.getDocument().getItems(), state));
            return StoreMutationResult.changed();
        });
    }

    public void pruneInactiveInstances(long nowMs, long inactiveTtlMs) {
        if (inactiveTtlMs <= 0) {
            return;
        }
        mutateDocument("Failed to prune inactive plugin runtime instances due to concurrent modification", snapshot -> {
            boolean changed = false;
            for (PluginRuntimeState state : snapshot.getDocument().getItems()) {
                if (state.getInstances() == null || state.getInstances().isEmpty()) {
                    continue;
                }
                boolean removed = state.getInstances().removeIf(instance ->
                        isInactiveInstance(instance, nowMs, inactiveTtlMs));
                if (removed) {
                    PluginRuntimeStateAggregator.aggregate(state);
                    changed = true;
                }
            }
            if (!changed) {
                return StoreMutationResult.unchanged();
            }
            return StoreMutationResult.changed();
        });
    }

    public void pruneStaleTransientInstances(long nowMs, long transientTtlMs) {
        if (transientTtlMs <= 0) {
            return;
        }
        pruneInstances(instance -> isStaleTransientInstance(instance, nowMs, transientTtlMs),
                "Failed to prune stale transient plugin runtime instances due to concurrent modification");
    }

    public PluginRuntimeStateDocument cleanupInstancesAndLoad(long nowMs, long legacyTtlMs, long transientTtlMs) {
        return cleanupInstancesAndRemoveStates(nowMs, legacyTtlMs, transientTtlMs, null);
    }

    public PluginRuntimeStateDocument cleanupInstancesAndRemoveStates(long nowMs,
                                                                      long legacyTtlMs,
                                                                      long transientTtlMs,
                                                                      Predicate<PluginRuntimeState> removeStatePredicate) {
        if (legacyTtlMs <= 0 && transientTtlMs <= 0 && removeStatePredicate == null) {
            return loadDocument();
        }
        return mutateDocument("Failed to cleanup plugin runtime states due to concurrent modification", snapshot -> {
            boolean changed = cleanupDocumentInstances(snapshot.getDocument(), nowMs, legacyTtlMs, transientTtlMs);
            if (removeStatePredicate != null) {
                changed = snapshot.getDocument().getItems().removeIf(removeStatePredicate) || changed;
            }
            if (!changed) {
                return StoreMutationResult.unchanged(snapshot.getDocument());
            }
            return StoreMutationResult.changed(snapshot.getDocument());
        });
    }

    private boolean cleanupDocumentInstances(PluginRuntimeStateDocument document,
                                             long nowMs,
                                             long legacyTtlMs,
                                             long transientTtlMs) {
        boolean changed = false;
        for (PluginRuntimeState state : document.getItems()) {
            if (state.getInstances() == null || state.getInstances().isEmpty()) {
                continue;
            }
            boolean removed = state.getInstances().removeIf(instance ->
                    isExpiredLease(instance, nowMs, legacyTtlMs)
                            || isStaleTransientLease(instance, nowMs, transientTtlMs));
            if (removed) {
                PluginRuntimeStateAggregator.aggregate(state);
                changed = true;
            }
        }
        return changed;
    }

    private boolean isExpiredLease(PluginRuntimeInstanceState instance, long nowMs, long legacyTtlMs) {
        return legacyTtlMs > 0 && PluginRuntimeLeases.isExpired(instance, nowMs, legacyTtlMs);
    }

    private boolean isStaleTransientLease(PluginRuntimeInstanceState instance, long nowMs, long transientTtlMs) {
        return transientTtlMs > 0 && isStaleTransientInstance(instance, nowMs, transientTtlMs);
    }

    private void pruneInstances(Predicate<PluginRuntimeInstanceState> predicate, String failureMessage) {
        mutateDocument(failureMessage, snapshot -> {
            boolean changed = false;
            for (PluginRuntimeState state : snapshot.getDocument().getItems()) {
                if (state.getInstances() == null || state.getInstances().isEmpty()) {
                    continue;
                }
                boolean removed = state.getInstances().removeIf(predicate);
                if (removed) {
                    PluginRuntimeStateAggregator.aggregate(state);
                    changed = true;
                }
            }
            if (!changed) {
                return StoreMutationResult.unchanged();
            }
            return StoreMutationResult.changed();
        });
    }

    private <T> T mutateDocument(String failureMessage, StoreMutation<T> mutation) {
        synchronized (STORE_UPDATE_LOCK) {
            boolean locked = acquireUpdateLock(failureMessage);
            try {
                for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
                    PluginRuntimeStateDocumentSnapshot snapshot = loadSnapshot();
                    StoreMutationResult<T> result = mutation.mutate(snapshot);
                    if (!result.isChanged()) {
                        return result.getValue();
                    }
                    if (saveDocumentIfUnchanged(snapshot)) {
                        return result.getValue();
                    }
                    if (i + 1 < STORE_UPDATE_RETRIES && !sleepBeforeRetry(i)) {
                        throw new IllegalStateException(failureMessage);
                    }
                }
            } finally {
                if (locked) {
                    updateLock.unlock();
                }
            }
        }
        throw new IllegalStateException(failureMessage);
    }

    private boolean acquireUpdateLock(String failureMessage) {
        if (updateLock == null) {
            return false;
        }
        try {
            // The DB lock reduces cross-process write contention; CAS remains the source of truth if the lock is stale.
            return updateLock.tryLock(STORE_UPDATE_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        }
    }

    private static Lock newUpdateLock(KvRepository kvStore) {
        if (kvStore instanceof WebsiteRuntimeKvStore) {
            return new DistributedLock(STORE_UPDATE_LOCK_KEY);
        }
        return null;
    }

    private boolean sleepBeforeRetry(int attempt) {
        long sleepMs = Math.min(50L, (attempt + 1L) * STORE_UPDATE_RETRY_BACKOFF_MS);
        try {
            Thread.sleep(sleepMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RemoveItemsResult removeItems(List<PluginRuntimeState> currentItems, String pluginId) {
        List<PluginRuntimeState> items = new ArrayList<PluginRuntimeState>();
        boolean removed = false;
        for (PluginRuntimeState item : currentItems) {
            if (Objects.equals(pluginId, item.getPluginId())) {
                removed = true;
                continue;
            }
            items.add(item);
        }
        return new RemoveItemsResult(items, removed);
    }

    private boolean isInactiveInstance(PluginRuntimeInstanceState instance, long nowMs, long inactiveTtlMs) {
        Long activeAt = instance.getLastActiveAt();
        if (activeAt == null) {
            activeAt = instance.getReadyAt();
        }
        if (activeAt == null) {
            activeAt = instance.getStartedAt();
        }
        return activeAt != null && nowMs - activeAt >= inactiveTtlMs;
    }

    private boolean isStaleTransientInstance(PluginRuntimeInstanceState instance, long nowMs, long transientTtlMs) {
        PluginStatus status = PluginStatus.fromRuntimeStatus(instance.getStatus());
        if (status != PluginStatus.STARTING && status != PluginStatus.INITIALIZING) {
            return false;
        }
        Long activeAt = instance.getLastActiveAt();
        if (activeAt == null) {
            activeAt = instance.getStartedAt();
        }
        return activeAt != null && nowMs - activeAt >= transientTtlMs;
    }

    public PluginRuntimeStateDocument loadDocument() {
        return parseDocument(kvStore.get(KEY));
    }

    public PluginRuntimeStateDocumentSnapshot loadSnapshot() {
        Optional<String> raw = kvStore.get(KEY);
        return new PluginRuntimeStateDocumentSnapshot(raw, parseDocument(raw));
    }

    private PluginRuntimeStateDocument parseDocument(Optional<String> json) {
        if (!json.isPresent() || json.get().trim().isEmpty()) {
            PluginRuntimeStateDocument document = new PluginRuntimeStateDocument();
            document.setUpdatedAt(RuntimeDates.nowString());
            return document;
        }
        PersistentJsonLimits.requireUtf8Length("Plugin runtime state document", json.get(),
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return normalizeErrorMessages(gson.fromJson(json.get(), PluginRuntimeStateDocument.class));
    }

    public void saveDocument(PluginRuntimeStateDocument document) {
        kvStore.put(KEY, documentJson(document));
    }

    public boolean saveDocumentIfUnchanged(PluginRuntimeStateDocumentSnapshot snapshot) {
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

    private String documentJson(PluginRuntimeStateDocument document) {
        document = normalizeErrorMessages(document);
        document.setSchema(KEY);
        document.setVersion(1);
        document.setUpdatedAt(RuntimeDates.nowString());
        for (PluginRuntimeState item : document.getItems()) {
            if (item != null) {
                item.setEffectiveStatus(null);
            }
        }
        String json = gson.toJson(document);
        PersistentJsonLimits.requireUtf8Length("Plugin runtime state document", json,
                PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES);
        return json;
    }

    private PluginRuntimeStateDocument normalizeErrorMessages(PluginRuntimeStateDocument document) {
        if (document == null) {
            document = new PluginRuntimeStateDocument();
        }
        List<PluginRuntimeState> currentItems = document.getItems();
        if (currentItems == null || currentItems.isEmpty()) {
            document.setItems(new ArrayList<PluginRuntimeState>());
            return document;
        }
        List<PluginRuntimeState> items = new ArrayList<PluginRuntimeState>(currentItems.size());
        for (PluginRuntimeState state : currentItems) {
            if (state == null) {
                continue;
            }
            state.setLastError(RuntimeTextLimits.truncateErrorMessage(state.getLastError()));
            List<PluginRuntimeInstanceState> currentInstances = state.getInstances();
            List<PluginRuntimeInstanceState> instances = new ArrayList<PluginRuntimeInstanceState>(
                    currentInstances == null ? 0 : currentInstances.size());
            if (currentInstances != null) {
                for (PluginRuntimeInstanceState instance : currentInstances) {
                    if (instance != null) {
                        instance.setLastError(RuntimeTextLimits.truncateErrorMessage(instance.getLastError()));
                        normalizeActiveInvocations(instance);
                        instances.add(instance);
                    }
                }
            }
            state.setInstances(instances);
            items.add(state);
        }
        document.setItems(items);
        return document;
    }

    private void normalizeActiveInvocations(PluginRuntimeInstanceState instance) {
        Set<String> currentInvocationIds = instance.getActiveInvocationIds();
        if (currentInvocationIds == null) {
            return;
        }
        List<String> validInvocationIds = new ArrayList<String>(currentInvocationIds.size());
        for (String invocationId : currentInvocationIds) {
            if (invocationId != null && !invocationId.trim().isEmpty()) {
                validInvocationIds.add(invocationId);
            }
        }
        Collections.sort(validInvocationIds);
        int retainedCount = Math.min(validInvocationIds.size(),
                PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS);
        Set<String> normalizedInvocationIds = new LinkedHashSet<String>(retainedCount);
        for (int i = 0; i < retainedCount; i++) {
            normalizedInvocationIds.add(validInvocationIds.get(i));
        }
        int currentActiveCount = instance.getActiveInvocationCount() == null
                ? validInvocationIds.size() : Math.max(instance.getActiveInvocationCount(), 0);
        long legacyInvocationCount = Math.max((long) currentActiveCount - validInvocationIds.size(), 0L);
        long normalizedActiveCount = legacyInvocationCount + normalizedInvocationIds.size();
        instance.setActiveInvocationIds(normalizedInvocationIds);
        instance.setActiveInvocationCount((int) Math.min(normalizedActiveCount, Integer.MAX_VALUE));
    }

    private interface StoreMutation<T> {
        StoreMutationResult<T> mutate(PluginRuntimeStateDocumentSnapshot snapshot);
    }

    private static class StoreMutationResult<T> {
        private final boolean changed;
        private final T value;

        private StoreMutationResult(boolean changed, T value) {
            this.changed = changed;
            this.value = value;
        }

        private static StoreMutationResult<Void> changed() {
            return new StoreMutationResult<Void>(true, null);
        }

        private static <T> StoreMutationResult<T> changed(T value) {
            return new StoreMutationResult<T>(true, value);
        }

        private static StoreMutationResult<Void> unchanged() {
            return new StoreMutationResult<Void>(false, null);
        }

        private static <T> StoreMutationResult<T> unchanged(T value) {
            return new StoreMutationResult<T>(false, value);
        }

        private boolean isChanged() {
            return changed;
        }

        private T getValue() {
            return value;
        }
    }

    private static class RemoveItemsResult {
        private final List<PluginRuntimeState> items;
        private final boolean removed;

        private RemoveItemsResult(List<PluginRuntimeState> items, boolean removed) {
            this.items = items;
            this.removed = removed;
        }

        public List<PluginRuntimeState> getItems() {
            return items;
        }

        public boolean isRemoved() {
            return removed;
        }
    }

    public static class PluginRuntimeStateDocumentSnapshot {
        private final Optional<String> rawJson;
        private final PluginRuntimeStateDocument document;

        public PluginRuntimeStateDocumentSnapshot(Optional<String> rawJson, PluginRuntimeStateDocument document) {
            this.rawJson = rawJson;
            this.document = document;
        }

        public Optional<String> getRawJson() {
            return rawJson;
        }

        public PluginRuntimeStateDocument getDocument() {
            return document;
        }
    }
}
