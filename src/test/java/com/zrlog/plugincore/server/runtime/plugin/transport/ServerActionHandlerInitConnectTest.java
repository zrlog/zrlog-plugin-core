package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.dao.WebSiteDAO;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
