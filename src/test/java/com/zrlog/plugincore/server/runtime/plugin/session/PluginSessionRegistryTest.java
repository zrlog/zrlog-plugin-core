package com.zrlog.plugincore.server.runtime.plugin.session;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.channels.Channel;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
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

        registry.addLocalSession(testSession.session);

        assertTrue(registry.isRunningByPluginId("plugin-a"));
        assertNotNull(testSession.session.getSystemAttr().get(PluginSessionHeartbeat.LAST_HEARTBEAT_AT_ATTR));
        testSession.channel.close();
    }

    @Test
    public void shouldDropHeartbeatExpiredSessionBeforeReportingRunning() throws Exception {
        PluginSessionRegistry registry = new PluginSessionRegistry(session -> {
        });
        TestSession testSession = testSession("plugin-a", "reminder");
        registry.addLocalSession(testSession.session);
        testSession.session.getSystemAttr().put(PluginSessionHeartbeat.LAST_HEARTBEAT_AT_ATTR,
                System.currentTimeMillis() - PluginSessionHeartbeat.HEARTBEAT_EXPIRE_MS - 1);

        assertFalse(registry.isRunningByPluginId("plugin-a"));

        assertNull(registry.getLocalSessionByPluginId("plugin-a"));
        assertFalse(testSession.channel.isOpen());
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

    private TestSession testSession(String pluginId, String shortName) throws Exception {
        IOSession session = (IOSession) unsafe().allocateInstance(IOSession.class);
        setField(session, "systemAttr", new ConcurrentHashMap<String, Object>());
        setField(session, "attr", new ConcurrentHashMap<String, Object>());
        setField(session, "pipeMap", new ConcurrentHashMap<Integer, Object>());
        ensureIdlePacketCleaner();
        Plugin plugin = new Plugin();
        plugin.setId(pluginId);
        plugin.setShortName(shortName);
        setField(session, "plugin", plugin);
        FakeChannel channel = new FakeChannel();
        session.getSystemAttr().put("_channel", channel);
        return new TestSession(session, channel);
    }

    private static class TestSession {
        private final IOSession session;
        private final FakeChannel channel;

        private TestSession(IOSession session, FakeChannel channel) {
            this.session = session;
            this.channel = channel;
        }
    }

    private static class FakeChannel implements Channel {
        private boolean open = true;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = IOSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void ensureIdlePacketCleaner() throws Exception {
        Field field = IOSession.class.getDeclaredField("clearIdlMsgPacketRunnable");
        field.setAccessible(true);
        if (field.get(null) != null) {
            return;
        }
        Class<?> cleanerClass = Class.forName("com.zrlog.plugin.ClearIdlMsgPacketRunnable");
        java.lang.reflect.Constructor<?> constructor = cleanerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        field.set(null, constructor.newInstance());
    }

}
