package com.zrlog.plugincore.server.runtime.event;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.support.MemorySocketChannels.MemoryChannel;
import com.zrlog.plugincore.server.support.MemorySocketChannels.MemorySelector;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class HostCacheRefreshRuntimeTest {
    @Test
    public void legacyCallbackIsRejectedWithoutAnyFireAndForgetSend() {
        var store = new InMemoryRuntimeKvStore();
        var logs = new InvocationLogStore(store);
        var event = new RuntimeEventRuntime(new CapabilityStore(store), new RuntimeEventHandlerResolver(),
                (id, key, payload, context) -> { throw new AssertionError("Legacy work must not dispatch"); });
        IOSession session = new IOSession(new MemoryChannel(), new MemorySelector(),
                new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)), new ClientActionHandler());
        Plugin plugin = new Plugin(); plugin.setId("legacy"); session.setPlugin(plugin);
        try {
            var result = new HostCacheRefreshRuntime(event, () -> List.of(session), logs).refresh("run-1", 7, "cache-1", 10000);
            assertFalse(result.completed); assertFalse(result.outcomeUncertain); assertEquals(1, result.unsupportedCount);
            assertEquals(0, result.successCount);
            assertEquals(1, logs.list().size()); assertEquals("error", logs.list().get(0).getStatus());
            assertEquals("run-1", logs.list().get(0).getTraceId());
        } finally { session.close(); }
    }

    @Test
    public void invalidIdentityStopsBeforeInspectingOrInvokingPlugins() {
        var store = new InMemoryRuntimeKvStore();
        var runtime = new HostCacheRefreshRuntime(null, () -> { throw new AssertionError(); }, new InvocationLogStore(store));
        assertThrows(IllegalArgumentException.class, () -> runtime.refresh("bad id", 7, "cache-1", 10000));
        assertThrows(IllegalArgumentException.class, () -> runtime.refresh("run-1", 0, "cache-1", 10000));
        assertThrows(IllegalArgumentException.class, () -> runtime.refresh("run-1", 7, "bad/version", 10000));
        assertTrue(new InvocationLogStore(store).list().isEmpty());
    }
}
