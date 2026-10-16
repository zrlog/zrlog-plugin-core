package com.zrlog.plugincore.server.runtime.plugin.transport;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HostCacheRefreshDispatcherTest {

    @Test
    public void shouldLeaveCallerAvailableAndCoalesceChangesDuringBlockedHostCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (HostCacheRefreshDispatcher dispatcher = new HostCacheRefreshDispatcher(() -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                second.countDown();
            }
        })) {
            dispatcher.request();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 200; i++) {
                dispatcher.request();
            }
            assertEquals(1, calls.get());
            release.countDown();
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void shouldDiscardQueuedRefreshAndInterruptCallbackWhenServerCloses() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HostCacheRefreshDispatcher dispatcher = new HostCacheRefreshDispatcher(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        try {
            dispatcher.request();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            dispatcher.request();
            dispatcher.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            dispatcher.request();
            assertEquals(1, calls.get());
        } finally {
            dispatcher.close();
        }
    }
}
