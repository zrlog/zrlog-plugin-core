package com.zrlog.plugincore.server.runtime.plugin.transport;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ServiceInvocationDispatcher {

    static final int WORKER_THREADS = 2;
    static final int QUEUE_CAPACITY = 8;
    static final long MAX_PENDING_BYTES = 32L * 1024L * 1024L;

    private final ExecutorService executor;
    private final PendingByteBudget pendingByteBudget;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ServiceInvocationDispatcher() {
        this(newExecutor(), MAX_PENDING_BYTES);
    }

    ServiceInvocationDispatcher(ExecutorService executor, long maxPendingBytes) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.pendingByteBudget = new PendingByteBudget(maxPendingBytes);
    }

    boolean dispatch(int payloadBytes, Runnable action) {
        Objects.requireNonNull(action, "action");
        int reservedBytes = Math.max(1, payloadBytes);
        if (closed.get() || !pendingByteBudget.tryReserve(reservedBytes)) {
            return false;
        }
        InvocationTask task;
        try {
            task = new InvocationTask(action, pendingByteBudget, reservedBytes);
        } catch (RuntimeException | Error e) {
            pendingByteBudget.release(reservedBytes);
            throw e;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            task.cancelIfQueued();
            return false;
        } catch (RuntimeException | Error e) {
            task.cancelIfQueued();
            throw e;
        }
    }

    void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> queuedTasks = executor.shutdownNow();
        for (Runnable queuedTask : queuedTasks) {
            cancelQueued(queuedTask);
        }
    }

    long reservedBytes() {
        return pendingByteBudget.reservedBytes();
    }

    boolean isClosed() {
        return closed.get();
    }

    static boolean cancelQueued(Runnable task) {
        return task instanceof InvocationTask && ((InvocationTask) task).cancelIfQueued();
    }

    private static ThreadPoolExecutor newExecutor() {
        AtomicInteger threadIndex = new AtomicInteger();
        return new ThreadPoolExecutor(
                WORKER_THREADS,
                WORKER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> new Thread(runnable, "zrlog-plugin-service-start-" + threadIndex.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static final class InvocationTask implements Runnable {

        private final Runnable action;
        private final PendingByteBudget pendingByteBudget;
        private final int reservedBytes;
        private final AtomicBoolean claimed = new AtomicBoolean(false);
        private final AtomicBoolean released = new AtomicBoolean(false);

        private InvocationTask(Runnable action, PendingByteBudget pendingByteBudget, int reservedBytes) {
            this.action = action;
            this.pendingByteBudget = pendingByteBudget;
            this.reservedBytes = reservedBytes;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                action.run();
            } finally {
                release();
            }
        }

        private boolean cancelIfQueued() {
            if (!claimed.compareAndSet(false, true)) {
                return false;
            }
            release();
            return true;
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                pendingByteBudget.release(reservedBytes);
            }
        }
    }

    private static final class PendingByteBudget {

        private final long maxBytes;
        private final AtomicLong reservedBytes = new AtomicLong();

        private PendingByteBudget(long maxBytes) {
            if (maxBytes <= 0L) {
                throw new IllegalArgumentException("maxPendingBytes must be greater than zero");
            }
            this.maxBytes = maxBytes;
        }

        private boolean tryReserve(long bytes) {
            while (true) {
                long current = reservedBytes.get();
                if (bytes > maxBytes || current > maxBytes - bytes) {
                    return false;
                }
                if (reservedBytes.compareAndSet(current, current + bytes)) {
                    return true;
                }
            }
        }

        private void release(long bytes) {
            reservedBytes.getAndUpdate(current -> Math.max(0L, current - bytes));
        }

        private long reservedBytes() {
            return reservedBytes.get();
        }
    }
}
