package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.sun.net.httpserver.HttpServer;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.dao.WebSiteDAO;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServerActionHandlerInitConnectTest {

    @Test
    public void shouldPersistPrivateSyncHistoryWithoutCallingHostRefreshAgain() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open();
             RecordingSession session = new RecordingSession()) {
            Plugin plugin = new Plugin();
            plugin.setId("static-id");
            plugin.setShortName("static-plus");
            session.setPlugin(plugin);
            AtomicInteger refreshes = new AtomicInteger();
            MsgPacket packet = new MsgPacket(Collections.singletonMap("syncHistory", "[{\"success\":true}]"),
                    ContentType.JSON, MsgPacketStatus.SEND_REQUEST, 81, "SET_WEBSITE");

            new ServerActionHandler(null, refreshes::incrementAndGet).setWebSite(session, packet);

            assertEquals("[{\"success\":true}]", new WebSiteDAO().queryValueByName("static-plus_syncHistory"));
            assertEquals(MsgPacketStatus.RESPONSE_SUCCESS, session.status);
            assertEquals(81, session.msgId);
            assertEquals(0, refreshes.get());
        }
    }

    @Test
    public void shouldRequestHostRefreshForExplicitStaticTemplateSettings() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open();
             RecordingSession session = new RecordingSession()) {
            Plugin plugin = new Plugin();
            plugin.setId("static-id");
            plugin.setShortName("static-plus");
            session.setPlugin(plugin);
            new WebSiteDAO().saveOrUpdateChanged("staticResourceHost", "https://old.example");
            AtomicInteger refreshes = new AtomicInteger();
            MsgPacket packet = new MsgPacket(Collections.singletonMap("syncTemplate", false),
                    ContentType.JSON, MsgPacketStatus.SEND_REQUEST, 82, "SET_WEBSITE");

            new ServerActionHandler(null, () -> {
                assertEquals(MsgPacketStatus.RESPONSE_SUCCESS, session.status);
                refreshes.incrementAndGet();
            }).setWebSite(session, packet);

            assertEquals("", new WebSiteDAO().queryValueByName("staticResourceHost"));
            assertEquals(1, refreshes.get());
        }
    }

    @Test
    public void shouldWaitForHostRefreshBeforeReturningFromDefaultHandler() throws Exception {
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        host.createContext("/api/admin/refreshCache", exchange -> {
            refreshEntered.countDown();
            try {
                if (!releaseRefresh.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test did not release host refresh");
                }
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                exchange.close();
            }
        });
        ExecutorService requests = Executors.newSingleThreadExecutor();
        RunType originalRunType = RunConstants.runType;
        PluginRuntimeServices services = PluginRuntimeServices.create(PluginConfig.unconfigured(),
                new PluginHostConnection("http://127.0.0.1:" + host.getAddress().getPort(), "", "", ""));
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open();
             RecordingSession session = new RecordingSession()) {
            host.start();
            PluginRuntimeBridge.install(services);
            RunConstants.runType = RunType.BLOG;
            Plugin plugin = new Plugin();
            plugin.setId("static-id");
            plugin.setShortName("static-plus");
            session.setPlugin(plugin);
            MsgPacket packet = new MsgPacket(Collections.singletonMap("syncTemplate", false),
                    ContentType.JSON, MsgPacketStatus.SEND_REQUEST, 83, "SET_WEBSITE");

            Future<?> request = requests.submit(() -> new ServerActionHandler(null).setWebSite(session, packet));

            assertTrue("host refresh was not called", refreshEntered.await(5, TimeUnit.SECONDS));
            assertEquals(MsgPacketStatus.RESPONSE_SUCCESS, session.status);
            assertEquals(83, session.msgId);
            try {
                request.get(200, TimeUnit.MILLISECONDS);
                fail("SET_WEBSITE returned before its host refresh completed");
            } catch (TimeoutException expected) {
                // The plugin reply precedes the callback, but the callback stays on the request thread.
            }
            releaseRefresh.countDown();
            request.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRefresh.countDown();
            requests.shutdownNow();
            assertTrue(requests.awaitTermination(5, TimeUnit.SECONDS));
            host.stop(0);
            services.shutdown();
            PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
            RunConstants.runType = originalRunType;
        }
    }

    @Test
    public void shouldRejectOversizedInitBeforeBindingSessionOrPersistingPlugin() throws Exception {
        MsgPacket packet = new MsgPacket();
        packet.setDataLength(PluginInitPayloadValidator.MAX_INIT_PAYLOAD_BYTES + 1);
        packet.setMethodStr("INIT_CONNECT");
        packet.setMsgId(73);

        try (RecordingSession session = new RecordingSession()) {
            new ServerActionHandler().initConnect(session, packet);

            assertNull(session.getPlugin());
            assertTrue(session.isClosed());
            assertTrue(session.response instanceof PluginTransportModels.InitErrorResponse);
            PluginTransportModels.InitErrorResponse response =
                    (PluginTransportModels.InitErrorResponse) session.response;
            assertFalse(response.getSuccess());
            assertTrue(response.getMessage().contains("exceeds"));
            assertEquals("INIT_CONNECT", session.method);
            assertEquals(73, session.msgId);
            assertEquals(MsgPacketStatus.RESPONSE_ERROR, session.status);
        }
    }

    private static IActionHandler noopActionHandler() {
        return (IActionHandler) Proxy.newProxyInstance(
                IActionHandler.class.getClassLoader(),
                new Class<?>[]{IActionHandler.class},
                (proxy, method, args) -> null);
    }

    private static class RecordingSession extends IOSession implements AutoCloseable {

        private final Selector selector;
        private Object response;
        private String method;
        private int msgId;
        private MsgPacketStatus status;

        private RecordingSession() throws Exception {
            this(new NoopSocketChannel(), Selector.open());
        }

        private RecordingSession(SocketChannel channel, Selector selector) {
            super(channel, selector,
                    new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)), noopActionHandler());
            this.selector = selector;
        }

        @Override
        public void sendJsonMsg(Object data, String method, int id, MsgPacketStatus status) {
            this.response = data;
            this.method = method;
            this.msgId = id;
            this.status = status;
        }

        @Override
        public void close() {
            super.close();
            try {
                selector.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static class NoopSocketChannel extends SocketChannel {

        private final Socket socket = new Socket();

        private NoopSocketChannel() {
            super(SelectorProvider.provider());
        }

        @Override
        public SocketChannel bind(SocketAddress local) {
            return this;
        }

        @Override
        public <T> SocketChannel setOption(SocketOption<T> name, T value) {
            return this;
        }

        @Override
        public SocketChannel shutdownInput() {
            return this;
        }

        @Override
        public SocketChannel shutdownOutput() {
            return this;
        }

        @Override
        public Socket socket() {
            return socket;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isConnectionPending() {
            return false;
        }

        @Override
        public boolean connect(SocketAddress remote) {
            return true;
        }

        @Override
        public boolean finishConnect() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            return -1;
        }

        @Override
        public int write(ByteBuffer src) {
            int remaining = src.remaining();
            src.position(src.limit());
            return remaining;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            long written = 0;
            for (int i = offset; i < offset + length; i++) {
                written += write(srcs[i]);
            }
            return written;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public <T> T getOption(SocketOption<T> name) {
            return null;
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return Collections.emptySet();
        }

        @Override
        protected void implCloseSelectableChannel() throws IOException {
            socket.close();
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
        }
    }
}
