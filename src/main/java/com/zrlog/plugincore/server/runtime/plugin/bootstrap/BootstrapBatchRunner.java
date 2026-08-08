package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class BootstrapBatchRunner {

    private static final AtomicInteger POOL_NUMBER = new AtomicInteger();

    private BootstrapBatchRunner() {
    }

    static <T> void run(Iterable<T> items, int itemCount, int maxConcurrency, Consumer<T> action) {
        if (itemCount <= 0) {
            return;
        }
        int workerCount = Math.max(1, Math.min(itemCount, maxConcurrency));
        Iterator<T> iterator = items.iterator();
        AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
        ExecutorService executor = newExecutor(workerCount);
        List<CompletableFuture<Void>> workers = new ArrayList<>(workerCount);
        try {
            for (int i = 0; i < workerCount; i++) {
                workers.add(CompletableFuture.runAsync(() -> consume(iterator, action, firstFailure), executor));
            }
            CompletableFuture.allOf(workers.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
        RuntimeException failure = firstFailure.get();
        if (failure != null) {
            throw new CompletionException(failure);
        }
    }

    private static <T> void consume(Iterator<T> iterator, Consumer<T> action,
                                    AtomicReference<RuntimeException> firstFailure) {
        while (true) {
            T item;
            synchronized (iterator) {
                if (!iterator.hasNext()) {
                    return;
                }
                item = iterator.next();
            }
            try {
                action.accept(item);
            } catch (RuntimeException failure) {
                firstFailure.compareAndSet(null, failure);
            }
        }
    }

    private static ThreadPoolExecutor newExecutor(int workerCount) {
        int poolNumber = POOL_NUMBER.incrementAndGet();
        AtomicInteger threadNumber = new AtomicInteger();
        return new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workerCount),
                runnable -> new Thread(runnable,
                        "zrlog-plugin-bootstrap-batch-" + poolNumber + "-" + threadNumber.incrementAndGet()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
