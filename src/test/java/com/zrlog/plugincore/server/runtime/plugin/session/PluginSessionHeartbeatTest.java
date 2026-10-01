package com.zrlog.plugincore.server.runtime.plugin.session;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.message.Plugin;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginSessionHeartbeatTest {

    @Test
    public void shouldProbeLiveSessionAfterRuntimeWasSuspended() throws Exception {
        try (HeartbeatSession test = new HeartbeatSession()) {
            test.suspend();

            assertTrue(test.heartbeat.ensureRecentHeartbeat(test.session, System.currentTimeMillis()));
            assertEquals(1, test.pings.get());
            assertTrue(test.channel.isOpen());
            assertTrue(test.heartbeat.hasRecentHeartbeat(test.session, System.currentTimeMillis()));
            assertTrue(test.heartbeat.ensureRecentHeartbeat(test.session, System.currentTimeMillis()));
            assertEquals(1, test.pings.get());
        }
    }

    @Test
    public void shouldProbeExpiredSessionBeforeBackgroundScanClosesIt() throws Exception {
        try (HeartbeatSession test = new HeartbeatSession()) {
            test.suspend();
            test.scan();

            assertEquals(1, test.pings.get());
            assertEquals(0, test.closed.get());
            assertTrue(test.channel.isOpen());
        }
    }

    @Test
    public void shouldCloseUnresponsiveSessionAfterRecoveryProbeTimesOut() throws Exception {
        try (HeartbeatSession test = new HeartbeatSession()) {
            test.respond = false;
            test.suspend();
            test.scan();

            assertEquals(1, test.pings.get());
            assertEquals(1, test.closed.get());
            assertFalse(test.channel.isOpen());
            assertTrue(test.session.getPipeMap().isEmpty());
        }
    }

    @Test
    public void shouldShareRecoveryProbeBetweenRequestAndBackgroundScan() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (HeartbeatSession test = new HeartbeatSession()) {
            test.suspend();
            test.probeStarted = new CountDownLatch(1);
            test.finishProbe = new CountDownLatch(1);
            try {
                Future<Boolean> request = executor.submit(() ->
                        test.heartbeat.ensureRecentHeartbeat(test.session, System.currentTimeMillis()));
                assertTrue(test.probeStarted.await(2, TimeUnit.SECONDS));
                CountDownLatch scanStarted = new CountDownLatch(1);
                Future<?> scan = executor.submit(() -> {
                    scanStarted.countDown();
                    test.scan();
                    return null;
                });
                assertTrue(scanStarted.await(2, TimeUnit.SECONDS));
                test.finishProbe.countDown();

                assertTrue(request.get(5, TimeUnit.SECONDS));
                scan.get(5, TimeUnit.SECONDS);
                assertEquals(1, test.pings.get());
                assertEquals(0, test.closed.get());
                assertTrue(test.channel.isOpen());
            } finally {
                test.finishProbe.countDown();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldDropClosedChannelWithoutSendingProbe() throws Exception {
        try (HeartbeatSession test = new HeartbeatSession()) {
            test.suspend();
            test.channel.close();
            test.scan();

            assertEquals(0, test.pings.get());
            assertEquals(1, test.closed.get());
        }
    }

    @Test
    public void shouldKeepExpiryForLegacyPluginWithoutHeartbeatSupport() throws Exception {
        try (HeartbeatSession test = new HeartbeatSession()) {
            test.session.getPlugin().setVersion("3.0.0");
            test.suspend();
            test.scan();

            assertEquals(0, test.pings.get());
            assertEquals(1, test.closed.get());
        }
    }

    private static final class HeartbeatSession implements AutoCloseable {
        private final SocketChannel channel = SocketChannel.open();
        private final Selector selector = Selector.open();
        private final AtomicInteger pings = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final IOSession session;
        private final PluginSessionHeartbeat heartbeat;
        private boolean respond = true;
        private CountDownLatch probeStarted;
        private CountDownLatch finishProbe;

        private HeartbeatSession() throws Exception {
            SocketEncode encode = new SocketEncode() {
                @Override
                public void doEncode(IOSession ioSession, MsgPacket packet) throws Exception {
                    pings.incrementAndGet();
                    if (probeStarted != null) {
                        probeStarted.countDown();
                        if (!finishProbe.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Recovery probe was not released");
                        }
                    }
                    if (respond) {
                        ioSession.dispose(new MsgPacket(new byte[0], ContentType.BYTE,
                                MsgPacketStatus.RESPONSE_SUCCESS, packet.getMsgId(), packet.getMethodStr()));
                    }
                }
            };
            session = new IOSession(channel, selector,
                    new SocketCodec(encode, new SocketDecode(Runnable::run)), new ClientActionHandler());
            Plugin plugin = new Plugin();
            plugin.setId("heartbeat-test");
            plugin.setShortName("reminder");
            plugin.setVersion("4.0.0");
            session.setPlugin(plugin);
            heartbeat = PluginSessionHeartbeat.active(() -> Collections.singletonList(session), stale -> {
                closed.incrementAndGet();
                stale.close();
            });
            heartbeat.register(session);
        }

        private void suspend() {
            long beforeSuspension = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(3);
            session.getSystemAttr().put(PluginSessionHeartbeat.LAST_HEARTBEAT_AT_ATTR, beforeSuspension);
            session.getSystemAttr().put(PluginSessionHeartbeat.LAST_PING_AT_ATTR, beforeSuspension);
        }

        private void scan() throws Exception {
            Method scan = PluginSessionHeartbeat.class.getDeclaredMethod("pingSessions");
            scan.setAccessible(true);
            scan.invoke(heartbeat);
        }

        @Override
        public void close() throws Exception {
            heartbeat.shutdown();
            session.close();
            selector.close();
        }
    }
}
