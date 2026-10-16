package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Queues decoded packets within the shared payload and task memory budget. */
final class PacketDispatchExecutor extends ThreadPoolExecutor {

    // Include an allowance for the runnable and queue node, including empty/control packets.
    static final int TASK_BYTES = 1024;
    private final SocketPacketMemoryBudget memoryBudget;

    PacketDispatchExecutor(int workers, ThreadFactory threadFactory, SocketPacketMemoryBudget memoryBudget) {
        super(workers, workers, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                threadFactory, new AbortPolicy());
        this.memoryBudget = memoryBudget;
    }

    @Override
    public void execute(Runnable action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        if (!memoryBudget.tryReserve(TASK_BYTES)) {
            throw new RejectedExecutionException("Plugin socket request memory budget exhausted");
        }
        PacketTask task;
        try {
            task = new PacketTask(action);
        } catch (RuntimeException | Error e) {
            memoryBudget.release(TASK_BYTES);
            throw e;
        }
        try {
            super.execute(task);
        } catch (RuntimeException | Error e) {
            task.cancelIfQueued();
            throw e;
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> queued = super.shutdownNow();
        List<Runnable> originals = new ArrayList<>(queued.size());
        for (Runnable runnable : queued) {
            PacketTask task = (PacketTask) runnable;
            task.cancelIfQueued();
            originals.add(task.action);
        }
        // Return the original codec tasks so the server can cancel their payload/file reservations.
        return originals;
    }

    private final class PacketTask implements Runnable {

        private final Runnable action;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private PacketTask(Runnable action) {
            this.action = action;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                action.run();
            } finally {
                memoryBudget.release(TASK_BYTES);
            }
        }

        private void cancelIfQueued() {
            if (claimed.compareAndSet(false, true)) {
                memoryBudget.release(TASK_BYTES);
            }
        }
    }
}
