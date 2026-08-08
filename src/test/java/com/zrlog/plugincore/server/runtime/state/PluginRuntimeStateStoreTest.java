package com.zrlog.plugincore.server.runtime.state;

import com.google.gson.Gson;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.store.ConditionalKvRepository;
import com.zrlog.plugincore.server.runtime.util.RuntimeTextLimits;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginRuntimeStateStoreTest {

    @Test
    public void shouldNormalizeNullRuntimeStateDocumentsItemsAndInstances() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(PluginRuntimeStateStore.KEY, "null");
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);

        assertEquals(0, store.list().size());

        kvStore.put(PluginRuntimeStateStore.KEY,
                "{\"items\":[null,{\"pluginId\":\"plugin-a\",\"instances\":[null,"
                        + "{\"instanceId\":\"instance-a\"}]},null]}");
        PluginRuntimeStateDocument loaded = store.loadDocument();
        assertEquals(1, loaded.getItems().size());
        assertEquals("plugin-a", loaded.getItems().get(0).getPluginId());
        assertEquals(1, loaded.getItems().get(0).getInstances().size());
        assertEquals("instance-a", loaded.getItems().get(0).getInstances().get(0).getInstanceId());

        store.saveDocument(loaded);
        PluginRuntimeStateDocument persisted = new Gson().fromJson(
                kvStore.get(PluginRuntimeStateStore.KEY).get(), PluginRuntimeStateDocument.class);
        assertEquals(1, persisted.getItems().size());
        assertEquals(1, persisted.getItems().get(0).getInstances().size());

        store.saveDocument(null);
        persisted = new Gson().fromJson(
                kvStore.get(PluginRuntimeStateStore.KEY).get(), PluginRuntimeStateDocument.class);
        assertEquals(0, persisted.getItems().size());
    }

    @Test
    public void shouldRejectOversizedStoredRuntimeStateDocumentBeforeParsingIt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        kvStore.put(PluginRuntimeStateStore.KEY,
                "x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES + 1));

        assertRejected(() -> new PluginRuntimeStateStore(kvStore).loadDocument(), "exceeds");
    }

    @Test
    public void shouldRejectOversizedRuntimeStateDocumentBeforeWritingIt() {
        PluginRuntimeStateDocument document = new PluginRuntimeStateDocument();
        document.getItems().add(state(
                "x".repeat(PersistentJsonLimits.MAX_RUNTIME_DOCUMENT_BYTES), "large plugin"));

        assertRejected(() -> new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()).saveDocument(document),
                "exceeds");
    }

    @Test
    public void shouldCompactDuplicatePluginIdOnUpsert() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeStateDocument document = new PluginRuntimeStateDocument();
        document.setItems(Arrays.asList(
                state("plugin-a", "old"),
                state("plugin-a", "older"),
                state("plugin-b", "other")
        ));
        store.saveDocument(document);

        store.upsert(state("plugin-a", "current"));

        List<PluginRuntimeState> items = store.list();
        assertEquals(2, items.size());
        assertEquals("plugin-a", items.get(0).getPluginId());
        assertEquals("current", items.get(0).getPluginName());
        assertEquals("plugin-b", items.get(1).getPluginId());
    }

    @Test
    public void shouldDeletePluginStateById() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        store.upsert(state("plugin-a", "one"));
        store.upsert(state("plugin-b", "two"));

        store.delete("plugin-a");

        List<PluginRuntimeState> items = store.list();
        assertEquals(1, items.size());
        assertEquals("plugin-b", items.get(0).getPluginId());
    }

    @Test
    public void shouldTruncateLegacyRuntimeStateErrorsOnLoadAndSave() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        String oversizedError = repeat("x", RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS + 100);
        kvStore.put(PluginRuntimeStateStore.KEY,
                "{\"items\":[{\"pluginId\":\"plugin-a\",\"lastError\":\"" + oversizedError
                        + "\",\"instances\":[{\"instanceId\":\"instance-a\",\"lastError\":\""
                        + oversizedError + "\"}]}]}");
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);

        PluginRuntimeState loaded = store.find("plugin-a").get();
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS, codePointLength(loaded.getLastError()));
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(loaded.getInstances().get(0).getLastError()));

        store.upsert(loaded);

        PluginRuntimeStateDocument persisted = new Gson().fromJson(
                kvStore.get(PluginRuntimeStateStore.KEY).get(), PluginRuntimeStateDocument.class);
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(0).getLastError()));
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                codePointLength(persisted.getItems().get(0).getInstances().get(0).getLastError()));
    }

    @Test
    public void shouldNormalizeOversizedLegacyInvocationTokensOnLoadAndSave() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateDocument oversizedDocument = oversizedInvocationDocument();
        kvStore.put(PluginRuntimeStateStore.KEY, new Gson().toJson(oversizedDocument));
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);

        assertNormalizedInvocationTokens(store.loadDocument());

        store.saveDocument(oversizedDocument);
        PluginRuntimeStateDocument persisted = new Gson().fromJson(
                kvStore.get(PluginRuntimeStateStore.KEY).get(), PluginRuntimeStateDocument.class);
        assertNormalizedInvocationTokens(persisted);
    }

    @Test
    public void shouldRetryUpsertWhenConcurrentRuntimeStateWriteWinsFirst() {
        StaleOnceKvStore kvStore = new StaleOnceKvStore(Optional.<String>empty(), documentJson(state("plugin-b", "two")));
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);

        store.upsert(state("plugin-a", "one"));

        assertEquals(2, store.list().size());
        assertEquals("one", store.find("plugin-a").get().getPluginName());
        assertEquals("two", store.find("plugin-b").get().getPluginName());
        assertEquals(2, kvStore.getCompareAndSetCount());
    }

    @Test
    public void shouldRetryUpdateWhenRepeatedConcurrentRuntimeStateWritesWin() {
        StaleOnceKvStore kvStore = new StaleOnceKvStore(
                Optional.<String>empty(),
                documentJson(state("plugin-b", "two")),
                5
        );
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);

        store.update("plugin-a", state -> {
            state.setPluginName("one");
            state.setStatus("initializing");
        });

        assertEquals(2, store.list().size());
        assertEquals("initializing", store.find("plugin-a").get().getStatus());
        assertEquals("two", store.find("plugin-b").get().getPluginName());
        assertEquals(6, kvStore.getCompareAndSetCount());
    }

    @Test
    public void shouldUseUpdateLockAroundRuntimeStateMutationWhenProvided() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        RecordingLock lock = new RecordingLock(true);
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore, lock);

        store.upsert(state("plugin-a", "one"));

        assertEquals(1, lock.getTryLockCount());
        assertEquals(1, lock.getUnlockCount());
        assertEquals("one", store.find("plugin-a").get().getPluginName());
    }

    @Test
    public void shouldFallbackToCasWhenUpdateLockIsBusy() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        RecordingLock lock = new RecordingLock(false);
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore, lock);

        store.upsert(state("plugin-a", "one"));

        assertEquals(1, lock.getTryLockCount());
        assertEquals(0, lock.getUnlockCount());
        assertEquals("one", store.find("plugin-a").get().getPluginName());
    }

    @Test
    public void shouldRetryDeleteWhenConcurrentRuntimeStateWriteWinsFirst() {
        StaleOnceKvStore kvStore = new StaleOnceKvStore(
                Optional.of(documentJson(state("plugin-a", "one"))),
                documentJson(state("plugin-a", "one"), state("plugin-b", "two"))
        );
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);

        store.delete("plugin-a");

        List<PluginRuntimeState> items = store.list();
        assertEquals(1, items.size());
        assertEquals("plugin-b", items.get(0).getPluginId());
        assertEquals(2, kvStore.getCompareAndSetCount());
    }

    @Test
    public void shouldPruneInactiveInstancesAndReaggregateState() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeState state = state("reminder", "待办提醒");
        state.setInstances(Arrays.asList(
                instance("reminder-1", 1000L, 1),
                instance("reminder-2", 5000L, 2)
        ));
        store.upsert(state);

        store.pruneInactiveInstances(7000L, 3000L);

        PluginRuntimeState next = store.find("reminder").get();
        assertEquals(1, next.getInstances().size());
        assertEquals("reminder-2", next.getInstances().get(0).getInstanceId());
        assertEquals(Integer.valueOf(2), next.getActiveInvocationCount());
        assertEquals("ready", next.getStatus());
    }

    @Test
    public void shouldPruneStaleTransientInstances() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeState state = state("reminder", "待办提醒");
        state.setInstances(Arrays.asList(
                instance("starting-old", "starting", 1000L, 0),
                instance("initializing-old", "initializing", 2000L, 0),
                instance("ready", "ready", 1000L, 1),
                instance("starting-fresh", "starting", 6500L, 0)
        ));
        store.upsert(state);

        store.pruneStaleTransientInstances(7000L, 3000L);

        PluginRuntimeState next = store.find("reminder").get();
        assertEquals(2, next.getInstances().size());
        assertEquals("ready", next.getInstances().get(0).getInstanceId());
        assertEquals("starting-fresh", next.getInstances().get(1).getInstanceId());
        assertEquals(Integer.valueOf(1), next.getActiveInvocationCount());
        assertEquals("ready", next.getStatus());
    }

    @Test
    public void shouldCleanupInstancesAndRemoveStatesInSingleMutation() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        PluginRuntimeStateStore store = new PluginRuntimeStateStore(kvStore);
        PluginRuntimeState state = state("reminder", "待办提醒");
        state.setInstances(Arrays.asList(
                instance("expired", "ready", 1000L, 1),
                instance("fresh", "ready", 6500L, 2)
        ));
        store.upsert(state);
        store.upsert(state("orphan", "孤儿插件"));

        PluginRuntimeStateDocument document = store.cleanupInstancesAndRemoveStates(7000L, 3000L, 3000L,
                item -> Objects.equals("orphan", item.getPluginId()));

        assertEquals(1, document.getItems().size());
        PluginRuntimeState next = document.getItems().get(0);
        assertEquals("reminder", next.getPluginId());
        assertEquals(1, next.getInstances().size());
        assertEquals("fresh", next.getInstances().get(0).getInstanceId());
        assertEquals(Integer.valueOf(2), next.getActiveInvocationCount());
    }

    private PluginRuntimeState state(String pluginId, String pluginName) {
        PluginRuntimeState state = new PluginRuntimeState();
        state.setPluginId(pluginId);
        state.setPluginName(pluginName);
        state.setStatus("ready");
        return state;
    }

    private PluginRuntimeInstanceState instance(String instanceId, long lastActiveAt, int activeCount) {
        return instance(instanceId, "ready", lastActiveAt, activeCount);
    }

    private PluginRuntimeInstanceState instance(String instanceId, String status, long lastActiveAt, int activeCount) {
        PluginRuntimeInstanceState instance = new PluginRuntimeInstanceState();
        instance.setInstanceId(instanceId);
        instance.setStatus(status);
        instance.setRuntimeMode("process");
        instance.setLastActiveAt(lastActiveAt);
        instance.setActiveInvocationCount(activeCount);
        return instance;
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

    private String documentJson(PluginRuntimeState... states) {
        PluginRuntimeStateDocument document = new PluginRuntimeStateDocument();
        document.setItems(Arrays.asList(states));
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

    private PluginRuntimeStateDocument oversizedInvocationDocument() {
        int validTokenCount = PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS + 5;
        LinkedHashSet<String> invocationIds = new LinkedHashSet<String>();
        for (int i = validTokenCount - 1; i >= 0; i--) {
            invocationIds.add(String.format("invocation-%03d", i));
        }
        invocationIds.add(null);
        invocationIds.add(" ");
        invocationIds.add("\t");
        PluginRuntimeInstanceState instance = instance(
                "oversized-instance", "ready", 1000L, validTokenCount + 3);
        instance.setActiveInvocationIds(invocationIds);
        PluginRuntimeState state = state("plugin-a", "reminder");
        state.setInstances(Arrays.asList(instance));
        PluginRuntimeStateDocument document = new PluginRuntimeStateDocument();
        document.setItems(Arrays.asList(state));
        return document;
    }

    private void assertNormalizedInvocationTokens(PluginRuntimeStateDocument document) {
        PluginRuntimeInstanceState instance = document.getItems().get(0).getInstances().get(0);
        List<String> invocationIds = new ArrayList<String>(instance.getActiveInvocationIds());
        assertEquals(PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS, invocationIds.size());
        assertEquals("invocation-000", invocationIds.get(0));
        assertEquals("invocation-255", invocationIds.get(invocationIds.size() - 1));
        assertEquals(Integer.valueOf(PluginRuntimeStateService.MAX_ACTIVE_INVOCATION_IDS + 3),
                instance.getActiveInvocationCount());
    }

    private static class StaleOnceKvStore implements KvRepository, ConditionalKvRepository {
        private Optional<String> value;
        private final String staleValue;
        private final int staleWriteCount;
        private int staleInjectedCount;
        private int compareAndSetCount;

        private StaleOnceKvStore(Optional<String> value, String staleValue) {
            this(value, staleValue, 1);
        }

        private StaleOnceKvStore(Optional<String> value, String staleValue, int staleWriteCount) {
            this.value = value;
            this.staleValue = staleValue;
            this.staleWriteCount = staleWriteCount;
        }

        @Override
        public Optional<String> get(String key) {
            return value;
        }

        @Override
        public void put(String key, String value) {
            this.value = Optional.ofNullable(value);
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            compareAndSetCount++;
            if (staleInjectedCount < staleWriteCount) {
                this.value = Optional.of(staleValue);
                staleInjectedCount++;
                return false;
            }
            if (!Objects.equals(this.value, expectedValue)) {
                return false;
            }
            this.value = Optional.ofNullable(value);
            return true;
        }

        private int getCompareAndSetCount() {
            return compareAndSetCount;
        }
    }

    private static class RecordingLock implements Lock {
        private final boolean acquired;
        private int tryLockCount;
        private int unlockCount;

        private RecordingLock(boolean acquired) {
            this.acquired = acquired;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) {
            tryLockCount++;
            return acquired;
        }

        @Override
        public void unlock() {
            unlockCount++;
        }

        @Override
        public void lock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lockInterruptibly() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryLock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        private int getTryLockCount() {
            return tryLockCount;
        }

        private int getUnlockCount() {
            return unlockCount;
        }
    }
}
