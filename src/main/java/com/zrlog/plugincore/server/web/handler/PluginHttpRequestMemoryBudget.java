package com.zrlog.plugincore.server.web.handler;

import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;

import java.util.concurrent.atomic.AtomicBoolean;

final class PluginHttpRequestMemoryBudget {

    static final long DEFAULT_MAX_WEIGHTED_BYTES = 32L * 1024L * 1024L;
    static final int REQUEST_OVERHEAD_BYTES = 256 * 1024;
    static final int JSON_BODY_WEIGHT = 6;

    private final SocketPacketMemoryBudget budget;

    PluginHttpRequestMemoryBudget(long maxWeightedBytes) {
        budget = new SocketPacketMemoryBudget(maxWeightedBytes);
    }

    Lease tryAcquire(int requestBodyBytes) {
        int reservedBytes = estimatedReservationBytes(requestBodyBytes);
        if (!budget.tryReserve(reservedBytes)) {
            return null;
        }
        return new Lease(budget, reservedBytes);
    }

    static int estimatedReservationBytes(int requestBodyBytes) {
        if (requestBodyBytes < 0) {
            throw new IllegalArgumentException("requestBodyBytes must not be negative");
        }
        long estimatedBytes = REQUEST_OVERHEAD_BYTES + (long) requestBodyBytes * JSON_BODY_WEIGHT;
        return estimatedBytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimatedBytes;
    }

    long reservedBytes() {
        return budget.getReservedBytes();
    }

    static final class Lease implements AutoCloseable {

        private final SocketPacketMemoryBudget budget;
        private final int reservedBytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(SocketPacketMemoryBudget budget, int reservedBytes) {
            this.budget = budget;
            this.reservedBytes = reservedBytes;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                budget.release(reservedBytes);
            }
        }
    }
}
