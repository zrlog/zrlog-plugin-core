package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class PacketDispatchExecutorTest {

    @Test
    public void shouldChargeEmptyTasksAgainstSharedPayloadBudget() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(2L * PacketDispatchExecutor.TASK_BYTES + 7);
        assertTrue(budget.tryReserve(7));
        PacketDispatchExecutor executor = new PacketDispatchExecutor(1, Thread::new, budget);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.execute(completed::countDown);
            try {
                executor.execute(() -> fail("request exceeding the memory budget ran"));
                fail("expected memory exhaustion");
            } catch (RejectedExecutionException expected) {
                assertTrue(expected.getMessage().contains("memory budget"));
            }
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(7L, budget.getReservedBytes());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void shouldReturnOriginalQueuedTasksAndReleaseOnlyOwnedMemoryOnShutdown() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(4096);
        assertTrue(budget.tryReserve(7));
        PacketDispatchExecutor executor = new PacketDispatchExecutor(1, Thread::new, budget);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        Runnable queued = () -> queuedRan.set(true);
        try {
            executor.execute(() -> {
                started.countDown();
                await(new CountDownLatch(1));
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.execute(queued);

            List<Runnable> cancelled = executor.shutdownNow();

            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, cancelled.size());
            assertSame(queued, cancelled.get(0));
            assertFalse(queuedRan.get());
            assertEquals(7L, budget.getReservedBytes());
            try {
                executor.execute(() -> fail("closed executor ran a request"));
                fail("expected shutdown rejection");
            } catch (RejectedExecutionException expected) {
                assertEquals(7L, budget.getReservedBytes());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void shouldProcessControlWorkWhileMessageWorkersAreBlocked() throws Exception {
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(128 * 1024);
        java.util.concurrent.ThreadPoolExecutor messages = PluginCoreSocketServer.newMessageExecutor(budget);
        java.util.concurrent.ThreadPoolExecutor control = PluginCoreSocketServer.newControlExecutor(budget);
        CountDownLatch started = new CountDownLatch(PluginCoreSocketServer.MESSAGE_HANDLER_THREADS);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch controlCompleted = new CountDownLatch(1);
        try {
            for (int i = 0; i < PluginCoreSocketServer.MESSAGE_HANDLER_THREADS; i++) {
                messages.execute(() -> {
                    started.countDown();
                    await(release);
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++) {
                messages.execute(() -> { });
            }
            control.execute(controlCompleted::countDown);
            assertTrue(controlCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(64, messages.getQueue().size());
        } finally {
            release.countDown();
            messages.shutdownNow();
            control.shutdownNow();
            assertTrue(messages.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(control.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, budget.getReservedBytes());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
