package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapBatchRunnerTest {

    @Test
    public void shouldCompleteRemainingItemsBeforePropagatingFailure() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(i);
        }
        AtomicInteger attempted = new AtomicInteger();

        try {
            BootstrapBatchRunner.run(items, items.size(), 2, item -> {
                attempted.incrementAndGet();
                if (item == 0) {
                    throw new IllegalStateException("simulated failure");
                }
            });
            throw new AssertionError("Expected batch failure");
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }

        assertEquals(items.size(), attempted.get());
    }

    @Test
    public void shouldKeepOnlyConfiguredItemsInFlightAndCompleteTheBatch() throws Exception {
        int concurrency = 2;
        int itemCount = 1000;
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }
        CountDownLatch firstWave = new CountDownLatch(concurrency);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        Thread runner = new Thread(() -> BootstrapBatchRunner.run(items, items.size(), concurrency, item -> {
            int currentActive = active.incrementAndGet();
            maxActive.updateAndGet(previous -> Math.max(previous, currentActive));
            firstWave.countDown();
            try {
                release.await();
                completed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        }));
        try {
            runner.start();
            assertTrue(firstWave.await(1, TimeUnit.SECONDS));
            assertEquals(concurrency, active.get());
            assertEquals(0, completed.get());
            assertTrue(runner.isAlive());
        } finally {
            release.countDown();
            runner.join(5000L);
        }

        assertFalse(runner.isAlive());
        assertEquals(itemCount, completed.get());
        assertEquals(concurrency, maxActive.get());
    }
}
