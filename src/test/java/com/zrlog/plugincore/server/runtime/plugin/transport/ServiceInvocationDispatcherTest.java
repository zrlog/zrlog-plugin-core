package com.zrlog.plugincore.server.runtime.plugin.transport;

import org.junit.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceInvocationDispatcherTest {

    @Test
    public void shouldQueueColdServiceBurstWithoutRunningOnCaller() throws Exception {
        ThreadPoolExecutor executor = ServiceInvocationDispatcher.newExecutor();
        ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(executor, 128L * 1024L);
        CountDownLatch workersStarted = new CountDownLatch(ServiceInvocationDispatcher.WORKER_THREADS);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(64);
        AtomicBoolean ranOnCaller = new AtomicBoolean(false);
        Thread caller = Thread.currentThread();
        try {
            for (int i = 0; i < ServiceInvocationDispatcher.WORKER_THREADS; i++) {
                assertTrue(dispatcher.dispatch(10, () -> {
                    workersStarted.countDown();
                    await(releaseWorkers);
                }));
            }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++) {
                assertTrue(dispatcher.dispatch(10, () -> {
                    if (Thread.currentThread() == caller) {
                        ranOnCaller.set(true);
                    }
                    completed.countDown();
                }));
            }
            assertEquals(64, executor.getQueue().size());
            releaseWorkers.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            awaitReservedBytes(dispatcher, 0L);
            assertFalse(ranOnCaller.get());
        } finally {
            releaseWorkers.countDown();
            dispatcher.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void shouldRejectWhenPendingByteBudgetIsExhausted() throws Exception {
        ThreadPoolExecutor executor = executor();
        ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(executor, 10L + ServiceInvocationDispatcher.TASK_BYTES);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        assertTrue(dispatcher.dispatch(8, () -> {
            started.countDown();
            await(release);
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(8L + ServiceInvocationDispatcher.TASK_BYTES, dispatcher.reservedBytes());
        assertFalse(dispatcher.dispatch(3, () -> {
        }));
        assertEquals(8L + ServiceInvocationDispatcher.TASK_BYTES, dispatcher.reservedBytes());

        release.countDown();
        awaitReservedBytes(dispatcher, 0L);
        dispatcher.shutdown();
    }

    @Test
    public void shouldReleaseQueuedAndRunningReservationsOnShutdown() throws Exception {
        ThreadPoolExecutor executor = executor();
        ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(executor, 20L + 2L * ServiceInvocationDispatcher.TASK_BYTES);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstFinished = new CountDownLatch(1);
        AtomicBoolean queuedActionRan = new AtomicBoolean(false);

        assertTrue(dispatcher.dispatch(7, () -> {
            firstStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                firstFinished.countDown();
            }
        }));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        assertTrue(dispatcher.dispatch(9, () -> queuedActionRan.set(true)));
        assertEquals(16L + 2L * ServiceInvocationDispatcher.TASK_BYTES, dispatcher.reservedBytes());

        dispatcher.shutdown();

        assertTrue(firstFinished.await(5, TimeUnit.SECONDS));
        awaitReservedBytes(dispatcher, 0L);
        assertTrue(dispatcher.isClosed());
        assertFalse(queuedActionRan.get());
        assertFalse(dispatcher.dispatch(1, () -> {
        }));
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> new Thread(runnable, "service-dispatch-test"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitReservedBytes(ServiceInvocationDispatcher dispatcher, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline && dispatcher.reservedBytes() != expected) {
            Thread.sleep(10L);
        }
        assertEquals(expected, dispatcher.reservedBytes());
    }
}
