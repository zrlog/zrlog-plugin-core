package com.zrlog.plugincore.server.web.handler;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PluginHttpRequestMemoryBudgetTest {

    @Test
    public void shouldAllowOnlyOneMaximumSizedHttpBodyAtATime() {
        PluginHttpRequestMemoryBudget budget =
                new PluginHttpRequestMemoryBudget(PluginHttpRequestMemoryBudget.DEFAULT_MAX_WEIGHTED_BYTES);

        PluginHttpRequestMemoryBudget.Lease first = budget.tryAcquire(4 * 1024 * 1024);
        assertNotNull(first);
        assertNull(budget.tryAcquire(4 * 1024 * 1024));

        first.close();
        assertEquals(0L, budget.reservedBytes());
        PluginHttpRequestMemoryBudget.Lease next = budget.tryAcquire(4 * 1024 * 1024);
        assertNotNull(next);
        next.close();
    }

    @Test
    public void shouldReleaseReservationOnlyOnce() {
        PluginHttpRequestMemoryBudget budget = new PluginHttpRequestMemoryBudget(1024 * 1024);
        PluginHttpRequestMemoryBudget.Lease lease = budget.tryAcquire(1024);
        assertNotNull(lease);

        lease.close();
        lease.close();

        assertEquals(0L, budget.reservedBytes());
    }
}
