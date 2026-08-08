package com.zrlog.plugincore.server.runtime.plugin.session;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.runtime.state.PluginStartCoordinator;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginSessionRegistryTest {

    @Test
    public void shouldReturnHeartbeatConfirmedSessionAsRunning() throws Exception {
        PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
        });
        TestSession testSession = testSession("plugin-a", "reminder");
        try {
            registry.addLocalSession(testSession.session);

            assertTrue(registry.isRunningByPluginId("plugin-a"));
            assertNotNull(testSession.session.getSystemAttr().get(PluginSessionHeartbeat.LAST_HEARTBEAT_AT_ATTR));
        } finally {
            testSession.close();
        }
    }

    @Test
    public void shouldReportSessionReadyOnlyAfterLifecycleMarker() throws Exception {
        PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
        });
        TestSession testSession = testSession("plugin-a", "reminder");
        try {
            registry.addLocalSession(testSession.session);

            assertFalse(registry.isReadyByPluginId("plugin-a"));
            registry.markReady(testSession.session);
            assertTrue(registry.isReadyByPluginId("plugin-a"));
        } finally {
            testSession.close();
        }
    }

    @Test
    public void shouldDropHeartbeatExpiredSessionBeforeReportingRunning() throws Exception {
        PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
        });
        TestSession testSession = testSession("plugin-a", "reminder");
        try {
            registry.addLocalSession(testSession.session);
            testSession.session.getSystemAttr().put(PluginSessionHeartbeat.LAST_HEARTBEAT_AT_ATTR,
                    System.currentTimeMillis() - PluginSessionHeartbeat.HEARTBEAT_EXPIRE_MS - 1);

            assertFalse(registry.isRunningByPluginId("plugin-a"));

            assertNull(registry.getLocalSessionByPluginId("plugin-a"));
            assertFalse(testSession.channel.isOpen());
        } finally {
            testSession.close();
        }
    }

    @Test
    public void shouldStartRequiredPluginOnFirstSessionRequestWithoutRegisteredMetadata() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            AtomicReference<Plugin> startedPlugin = new AtomicReference<>();
            PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
            }, PluginSessionHeartbeat.disabled(), Collections.singletonMap("comment", "comment"), plugin -> {
                startedPlugin.set(plugin);
                return false;
            });

            assertTrue(registry.isRequiredPlugin("comment"));
            assertNull(registry.getOrStartLocalSessionByPluginShortName("comment"));

            assertNotNull(startedPlugin.get());
            assertEquals("comment", startedPlugin.get().getId());
            assertEquals("comment", startedPlugin.get().getShortName());
        }
    }

    @Test
    public void shouldJoinInitializingSessionBeforeReturningItToRouting() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            TestSession testSession = testSession("plugin-a", "comment");
            AtomicBoolean starterCalled = new AtomicBoolean();
            PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
            }, PluginSessionHeartbeat.disabled(), Collections.singletonMap("comment", "plugin-a"), plugin -> {
                starterCalled.set(true);
                testSession.session.getSystemAttr().put(PluginSessionRegistry.READY_ATTR, Boolean.TRUE);
                return true;
            });
            try {
                registry.addLocalSession(testSession.session);

                assertNull(registry.getReadyLocalSessionByPluginShortName("comment"));
                assertEquals(testSession.session, registry.getOrStartLocalSessionByPluginShortName("comment"));
                assertTrue(starterCalled.get());
            } finally {
                testSession.close();
            }
        }
    }

    @Test
    public void shouldNotReturnInitializingSessionWhenStarterReportsSuccessWithoutReadyMarker() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            TestSession testSession = testSession("plugin-a", "comment");
            PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
            }, PluginSessionHeartbeat.disabled(), Collections.singletonMap("comment", "plugin-a"), plugin -> true);
            try {
                registry.addLocalSession(testSession.session);

                assertNull(registry.getOrStartLocalSessionByPluginShortName("comment"));
                assertNull(registry.getReadyLocalSessionByPluginId("plugin-a"));
            } finally {
                testSession.close();
            }
        }
    }

    @Test
    public void shouldReturnReadySessionWithoutStartingAgain() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            TestSession testSession = testSession("plugin-a", "comment");
            AtomicBoolean starterCalled = new AtomicBoolean();
            PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
            }, PluginSessionHeartbeat.disabled(), Collections.singletonMap("comment", "plugin-a"), plugin -> {
                starterCalled.set(true);
                return true;
            });
            try {
                registry.addLocalSession(testSession.session);
                registry.markReady(testSession.session);

                assertEquals(testSession.session, registry.getOrStartLocalSessionByPluginShortName("comment"));
                assertFalse(starterCalled.get());
                assertEquals(testSession.session, registry.getReadyLocalSessionByPluginId("plugin-a"));
            } finally {
                testSession.close();
            }
        }
    }

    @Test
    public void shouldClaimDemandAndRecheckReadySessionBeforeReturning() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        TestSession testSession = testSession("plugin-a", "comment");
        AtomicInteger readyChecks = new AtomicInteger();
        PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
        }, PluginSessionHeartbeat.disabled(), Collections.emptyMap(), plugin -> true, coordinator) {
            @Override
            public IOSession getReadyLocalSessionByPluginId(String pluginId) {
                return readyChecks.incrementAndGet() == 1 ? testSession.session : null;
            }
        };
        try {
            assertNull(registry.claimReadyLocalSessionByPluginId("plugin-a"));
            assertEquals(2, readyChecks.get());
            assertFalse(coordinator.runIfUnclaimed("plugin-a", () -> {
                throw new AssertionError("claimed demand must be visible before the READY recheck");
            }));
        } finally {
            testSession.close();
        }
    }

    @Test
    public void shouldReturnClaimedReadySessionWithoutAllowingIdleCleanup() throws Exception {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
        }, PluginSessionHeartbeat.disabled(), Collections.emptyMap(), plugin -> true, coordinator);
        TestSession testSession = testSession("plugin-a", "comment");
        try {
            registry.addLocalSession(testSession.session);
            registry.markReady(testSession.session);

            assertEquals(testSession.session, registry.claimReadyLocalSessionByPluginId("plugin-a"));
            assertFalse(coordinator.runIfUnclaimed("plugin-a", () -> {
                throw new AssertionError("idle cleanup must not pass a claimed READY session");
            }));
        } finally {
            testSession.close();
        }
    }

    @Test
    public void shouldCloseCurrentAndRejectFutureSessionsAfterShutdown() throws Exception {
        PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
        }, PluginSessionHeartbeat.disabled());
        TestSession current = testSession("plugin-a", "comment");
        TestSession late = testSession("plugin-b", "reminder");
        try {
            registry.addLocalSession(current.session);

            registry.shutdown();
            registry.shutdown();
            registry.addLocalSession(late.session);

            assertTrue(registry.isShutdown());
            assertTrue(registry.getAllLocalSessions().isEmpty());
            assertFalse(current.channel.isOpen());
            assertFalse(late.channel.isOpen());
        } finally {
            current.close();
            late.close();
        }
    }

    private TestSession testSession(String pluginId, String shortName) throws Exception {
        SocketChannel channel = SocketChannel.open();
        Selector selector = Selector.open();
        IActionHandler actionHandler = (IActionHandler) Proxy.newProxyInstance(
                IActionHandler.class.getClassLoader(), new Class<?>[]{IActionHandler.class},
                (proxy, method, args) -> null);
        IOSession session = new IOSession(channel, selector,
                new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)), actionHandler);
        Plugin plugin = new Plugin();
        plugin.setId(pluginId);
        plugin.setShortName(shortName);
        session.setPlugin(plugin);
        return new TestSession(session, channel, selector);
    }

    private static class TestSession implements AutoCloseable {
        private final IOSession session;
        private final SocketChannel channel;
        private final Selector selector;

        private TestSession(IOSession session, SocketChannel channel, Selector selector) {
            this.session = session;
            this.channel = channel;
            this.selector = selector;
        }

        @Override
        public void close() throws Exception {
            session.close();
            selector.close();
        }
    }

}
