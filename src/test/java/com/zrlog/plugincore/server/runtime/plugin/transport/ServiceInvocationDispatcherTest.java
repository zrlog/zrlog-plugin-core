package com.zrlog.plugincore.server.runtime.plugin.transport;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceInvocationDispatcherTest {

    @Test
    public void shouldRejectWhenQueueIsFullWithoutRunningOnCaller() throws Exception {
        ThreadPoolExecutor executor = executor(1);
        ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(executor, 1024L);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicBoolean rejectedActionRan = new AtomicBoolean(false);

        assertTrue(dispatcher.dispatch(10, () -> {
            firstStarted.countDown();
            await(releaseFirst);
        }));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        assertTrue(dispatcher.dispatch(10, secondFinished::countDown));
        assertFalse(dispatcher.dispatch(10, () -> rejectedActionRan.set(true)));
        assertFalse(rejectedActionRan.get());

        releaseFirst.countDown();
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
        awaitReservedBytes(dispatcher, 0L);
        dispatcher.shutdown();
    }

    @Test
    public void shouldRejectWhenPendingByteBudgetIsExhausted() throws Exception {
        ThreadPoolExecutor executor = executor(2);
        ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(executor, 10L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        assertTrue(dispatcher.dispatch(8, () -> {
            started.countDown();
            await(release);
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(8L, dispatcher.reservedBytes());
        assertFalse(dispatcher.dispatch(3, () -> {
        }));
        assertEquals(8L, dispatcher.reservedBytes());

        release.countDown();
        awaitReservedBytes(dispatcher, 0L);
        dispatcher.shutdown();
    }

    @Test
    public void shouldReleaseQueuedAndRunningReservationsOnShutdown() throws Exception {
        ThreadPoolExecutor executor = executor(1);
        ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(executor, 20L);
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
        assertEquals(16L, dispatcher.reservedBytes());

        dispatcher.shutdown();

        assertTrue(firstFinished.await(5, TimeUnit.SECONDS));
        awaitReservedBytes(dispatcher, 0L);
        assertTrue(dispatcher.isClosed());
        assertFalse(queuedActionRan.get());
        assertFalse(dispatcher.dispatch(1, () -> {
        }));
    }

    private static ThreadPoolExecutor executor(int queueCapacity) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
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
