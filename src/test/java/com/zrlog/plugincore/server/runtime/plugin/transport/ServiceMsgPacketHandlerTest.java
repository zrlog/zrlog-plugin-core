package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.state.PluginIdentity;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStarter;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeState;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceMsgPacketHandlerTest {

    @Test
    public void shouldRejectOversizedServiceRequestBeforeReadingPayload() throws Exception {
        MsgPacket packet = new MsgPacket();
        packet.setDataLength(ServiceMsgPacketHandler.MAX_SERVICE_REQUEST_BYTES + 1);
        packet.setMethodStr("SERVICE");
        packet.setMsgId(7);

        try (RecordingSession session = new RecordingSession()) {
            new ServiceMsgPacketHandler(session).doHandle(packet);

            assertTrue(session.response instanceof PluginTransportModels.ServiceErrorResponse);
            assertEquals(ServiceMsgPacketHandler.SERVICE_REQUEST_TOO_LARGE_MESSAGE,
                    ((PluginTransportModels.ServiceErrorResponse) session.response).getMessage());
            assertEquals("SERVICE", session.method);
            assertEquals(7, session.msgId);
            assertEquals(MsgPacketStatus.RESPONSE_ERROR, session.status);
        }
    }

    @Test
    public void shouldParseServiceRequestAtByteLimit() {
        byte[] payload = new byte[ServiceMsgPacketHandler.MAX_SERVICE_REQUEST_BYTES];
        Arrays.fill(payload, (byte) ' ');
        byte[] json = "{\"name\":\"email\"}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, payload, 0, json.length);

        Map<String, Object> parsed = ServiceMsgPacketHandler.parseServicePayload(packet(payload));

        assertEquals("email", parsed.get("name"));
    }

    @Test
    public void shouldPreserveFlatPayloadWhenParsingServiceRequestOnce() {
        MsgPacket packet = packet(("{\"name\":\"email\",\"template\":\"weekly\",\"attempt\":3}")
                .getBytes(StandardCharsets.UTF_8));

        Map<String, Object> parsed = ServiceMsgPacketHandler.parseServicePayload(packet);

        assertEquals("email", parsed.get("name"));
        assertEquals("weekly", parsed.get("template"));
        assertEquals(3.0d, parsed.get("attempt"));
    }

    @Test
    public void shouldFinishInvocationExactlyOnceAcrossResponseAndAbort() throws Exception {
        AtomicInteger completions = new AtomicInteger();
        ServiceMsgPacketHandler.InvocationCompletion completion =
                new ServiceMsgPacketHandler.InvocationCompletion(error -> completions.incrementAndGet());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Thread response = new Thread(() -> {
            ready.countDown();
            await(start);
            completion.complete(null);
        });
        Thread abort = new Thread(() -> {
            ready.countDown();
            await(start);
            completion.complete("timeout");
        });
        response.start();
        abort.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        response.join(5000L);
        abort.join(5000L);

        assertFalse(response.isAlive());
        assertFalse(abort.isAlive());
        assertEquals(1, completions.get());
    }

    @Test
    public void shouldRemainTerminalWhenCompletionActionFails() {
        AtomicInteger attempts = new AtomicInteger();
        ServiceMsgPacketHandler.InvocationCompletion completion =
                new ServiceMsgPacketHandler.InvocationCompletion(error -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("store unavailable");
                });

        assertTrue(completion.complete("timeout"));
        assertFalse(completion.complete("late response"));
        assertEquals(1, attempts.get());
    }

    @Test
    public void shouldRetryAmbiguousStateFinishWithoutDoubleDecrementOrDuplicateLog() {
        FailingInvocationKvStore kvStore = new FailingInvocationKvStore();
        PluginRuntimeStateService stateService = new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore), new NoopStarter(), "service-instance");
        stateService.markInvocationStart("plugin-a", "reminder", "invocation-a");
        stateService.markInvocationStart("plugin-a", "reminder", "invocation-b");
        kvStore.failAfterNextStateWrite();
        ServiceMsgPacketHandler handler = new ServiceMsgPacketHandler(null);
        ServiceMsgPacketHandler.InvocationCompletion completion =
                new ServiceMsgPacketHandler.InvocationCompletion(error -> handler.finishInvocation(
                        null,
                        stateService,
                        kvStore,
                        "plugin-a",
                        "reminder",
                        "invocation-a",
                        "reminder.service",
                        "request-a",
                        System.currentTimeMillis(),
                        error));

        assertTrue(completion.complete(null));
        assertFalse(completion.complete("late timeout"));

        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals(Integer.valueOf(1), state.getActiveInvocationCount());
        assertEquals(Collections.singleton("invocation-b"),
                state.getInstances().get(0).getActiveInvocationIds());
        assertEquals(1, new InvocationLogStore(kvStore).list().size());
        assertEquals("success", new InvocationLogStore(kvStore).list().get(0).getStatus());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MsgPacket packet(byte[] payload) {
        MsgPacket packet = new MsgPacket();
        packet.setData(ByteBuffer.wrap(payload));
        packet.setDataLength(payload.length);
        return packet;
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
            this(SocketChannel.open(), Selector.open());
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

    private static class NoopStarter implements PluginRuntimeStarter {

        @Override
        public boolean isStarted(String pluginId) {
            return true;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, pluginId));
        }

        @Override
        public void start(PluginIdentity identity) {
        }
    }

    private static class FailingInvocationKvStore extends InMemoryRuntimeKvStore {

        private boolean failAfterNextStateWrite;

        private void failAfterNextStateWrite() {
            failAfterNextStateWrite = true;
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            boolean updated = super.compareAndSet(key, expectedValue, value);
            if (PluginRuntimeStateStore.KEY.equals(key) && updated && failAfterNextStateWrite) {
                failAfterNextStateWrite = false;
                throw new IllegalStateException("state store failed after write");
            }
            return updated;
        }
    }
}
