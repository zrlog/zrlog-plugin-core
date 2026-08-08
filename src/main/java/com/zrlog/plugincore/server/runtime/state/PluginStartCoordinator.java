package com.zrlog.plugincore.server.runtime.state;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class PluginStartCoordinator {

    private static final int OPERATION_LOCK_STRIPES = 64;

    private final Map<String, StartOperation> inFlightStarts = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfterByPluginId = new ConcurrentHashMap<>();
    private final Map<String, Long> demandClaimUntilByPluginId = new ConcurrentHashMap<>();
    private final Object capacityMonitor = new Object();
    private final ReentrantLock[] operationLocks = new ReentrantLock[OPERATION_LOCK_STRIPES];
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private int activeStarts;

    public PluginStartCoordinator() {
        for (int i = 0; i < operationLocks.length; i++) {
            operationLocks[i] = new ReentrantLock(true);
        }
    }

    public boolean start(String pluginId,
                         int maxConcurrentStarts,
                         long capacityWaitTimeoutMs,
                         long inFlightWaitTimeoutMs,
                         long failureBackoffMs,
                         BooleanSupplier startAction) {
        if (shutdown.get()) {
            return false;
        }
        cleanupExpiredEntries(System.currentTimeMillis());
        if (isCoolingDown(pluginId)) {
            return false;
        }
        StartOperation ownedOperation = new StartOperation();
        StartOperation existingOperation = inFlightStarts.putIfAbsent(pluginId, ownedOperation);
        if (existingOperation != null) {
            return await(existingOperation.result, inFlightWaitTimeoutMs);
        }
        if (shutdown.get() || isCoolingDown(pluginId)) {
            ownedOperation.result.complete(false);
            inFlightStarts.remove(pluginId, ownedOperation);
            return false;
        }
        try {
            boolean started = withPluginOperation(pluginId, inFlightWaitTimeoutMs, () -> {
                if (shutdown.get() || ownedOperation.cancelled.get() || isCoolingDown(pluginId)) {
                    return false;
                }
                return withCapacity(maxConcurrentStarts, capacityWaitTimeoutMs, () -> {
                    if (shutdown.get() || ownedOperation.cancelled.get()) {
                        return false;
                    }
                    return startAction.getAsBoolean();
                });
            });
            if (shutdown.get()) {
                started = false;
            }
            if (!started && !ownedOperation.cancelled.get()
                    && failureBackoffMs > 0 && !isCoolingDown(pluginId)) {
                retryAfterByPluginId.put(pluginId, System.currentTimeMillis() + failureBackoffMs);
            }
            ownedOperation.result.complete(started);
            return started;
        } catch (PluginStartDeferredException e) {
            if (!ownedOperation.cancelled.get() && failureBackoffMs > 0) {
                retryAfterByPluginId.put(pluginId, System.currentTimeMillis() + failureBackoffMs);
            }
            ownedOperation.result.complete(false);
            return false;
        } catch (RuntimeException | Error e) {
            if (!ownedOperation.cancelled.get() && failureBackoffMs > 0) {
                retryAfterByPluginId.put(pluginId, System.currentTimeMillis() + failureBackoffMs);
            }
            ownedOperation.result.completeExceptionally(e);
            throw e;
        } finally {
            inFlightStarts.remove(pluginId, ownedOperation);
        }
    }

    public boolean cancelStart(String pluginId) {
        StartOperation operation = inFlightStarts.get(pluginId);
        return operation != null && operation.cancelled.compareAndSet(false, true);
    }

    public boolean isStartCancellationRequested(String pluginId) {
        if (shutdown.get()) {
            return true;
        }
        StartOperation operation = inFlightStarts.get(pluginId);
        return operation != null && operation.cancelled.get();
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        for (StartOperation operation : inFlightStarts.values()) {
            operation.cancelled.set(true);
            operation.result.complete(false);
        }
        synchronized (capacityMonitor) {
            capacityMonitor.notifyAll();
        }
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public void recordFailure(String pluginId, long failureBackoffMs) {
        if (pluginId == null || pluginId.trim().isEmpty() || failureBackoffMs <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        long retryAfter = now + failureBackoffMs;
        if (retryAfter < now) {
            retryAfter = Long.MAX_VALUE;
        }
        retryAfterByPluginId.merge(pluginId, retryAfter, Math::max);
    }

    public boolean withCapacity(int maxConcurrentStarts,
                                long capacityWaitTimeoutMs,
                                BooleanSupplier startAction) {
        if (shutdown.get()) {
            return false;
        }
        boolean acquired = acquire(Math.max(1, maxConcurrentStarts), Math.max(0L, capacityWaitTimeoutMs));
        if (!acquired) {
            return false;
        }
        try {
            if (shutdown.get()) {
                return false;
            }
            return startAction.getAsBoolean();
        } finally {
            release();
        }
    }

    public boolean withPluginOperation(String pluginId, long waitTimeoutMs, BooleanSupplier action) {
        ReentrantLock lock = operationLock(pluginId);
        boolean acquired;
        try {
            acquired = lock.tryLock(Math.max(1L, waitTimeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!acquired) {
            return false;
        }
        try {
            return action.getAsBoolean();
        } finally {
            lock.unlock();
        }
    }

    public boolean tryPluginOperation(String pluginId, BooleanSupplier action) {
        if (shutdown.get()) {
            return false;
        }
        ReentrantLock lock = operationLock(pluginId);
        if (!lock.tryLock()) {
            return false;
        }
        try {
            if (shutdown.get()) {
                return false;
            }
            return action.getAsBoolean();
        } finally {
            lock.unlock();
        }
    }

    public void runPluginOperation(String pluginId, Runnable action) {
        ReentrantLock lock = operationLock(pluginId);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public void claimDemand(String pluginId, long claimDurationMs) {
        if (shutdown.get() || pluginId == null || pluginId.trim().isEmpty()) {
            return;
        }
        ReentrantLock lock = operationLock(pluginId);
        lock.lock();
        try {
            if (shutdown.get()) {
                return;
            }
            claimDemandWithinOperation(pluginId, claimDurationMs);
        } finally {
            lock.unlock();
        }
    }

    public <T> T claimDemandAndGet(String pluginId,
                                   long claimDurationMs,
                                   long operationWaitTimeoutMs,
                                   Supplier<T> currentValue) {
        if (shutdown.get() || pluginId == null || pluginId.trim().isEmpty() || currentValue == null) {
            return null;
        }
        AtomicReference<T> claimedValue = new AtomicReference<>();
        boolean completed = withPluginOperation(pluginId, operationWaitTimeoutMs, () -> {
            if (shutdown.get()) {
                return false;
            }
            claimDemandWithinOperation(pluginId, claimDurationMs);
            claimedValue.set(currentValue.get());
            return true;
        });
        return completed ? claimedValue.get() : null;
    }

    public boolean runIfUnclaimed(String pluginId, Runnable action) {
        if (shutdown.get()) {
            return false;
        }
        ReentrantLock lock = operationLock(pluginId);
        lock.lock();
        try {
            if (shutdown.get()) {
                return false;
            }
            Long claimUntil = demandClaimUntilByPluginId.get(pluginId);
            if (claimUntil != null && System.currentTimeMillis() < claimUntil) {
                return false;
            }
            if (claimUntil != null) {
                demandClaimUntilByPluginId.remove(pluginId, claimUntil);
            }
            action.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean isCoolingDown(String pluginId) {
        Long retryAfter = retryAfterByPluginId.get(pluginId);
        if (retryAfter == null) {
            return false;
        }
        if (System.currentTimeMillis() < retryAfter) {
            return true;
        }
        retryAfterByPluginId.remove(pluginId, retryAfter);
        return false;
    }

    private ReentrantLock operationLock(String pluginId) {
        int hash = pluginId == null ? 0 : pluginId.hashCode();
        int index = (hash & 0x7fffffff) % operationLocks.length;
        return operationLocks[index];
    }

    boolean hasQueuedPluginOperation(String pluginId, Thread thread) {
        return thread != null && operationLock(pluginId).hasQueuedThread(thread);
    }

    private void claimDemandWithinOperation(String pluginId, long claimDurationMs) {
        long now = System.currentTimeMillis();
        cleanupExpiredEntries(now);
        long durationMs = Math.max(1L, claimDurationMs);
        long claimUntil = now > Long.MAX_VALUE - durationMs ? Long.MAX_VALUE : now + durationMs;
        demandClaimUntilByPluginId.merge(pluginId, claimUntil, Math::max);
    }

    private void cleanupExpiredEntries(long now) {
        cleanupExpired(retryAfterByPluginId, now);
        cleanupExpired(demandClaimUntilByPluginId, now);
    }

    private void cleanupExpired(Map<String, Long> values, long now) {
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            Long expiresAt = entry.getValue();
            if (expiresAt != null && now >= expiresAt) {
                values.remove(entry.getKey(), expiresAt);
            }
        }
    }

    private boolean acquire(int maxConcurrentStarts, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (capacityMonitor) {
            if (shutdown.get()) {
                return false;
            }
            while (activeStarts >= maxConcurrentStarts) {
                if (shutdown.get()) {
                    return false;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    capacityMonitor.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (shutdown.get()) {
                return false;
            }
            activeStarts++;
            return true;
        }
    }

    private void release() {
        synchronized (capacityMonitor) {
            activeStarts = Math.max(0, activeStarts - 1);
            capacityMonitor.notifyAll();
        }
    }

    private boolean await(CompletableFuture<Boolean> future, long timeoutMs) {
        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private static final class StartOperation {

        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
    }
}
