package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.PipeInfo;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.PackageVersion;
import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;
import com.zrlog.plugin.data.codec.SocketPacketLimits;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginCoreSocketServerTest {

    @Test
    public void shouldReleaseSessionAfterDecodeFailure() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, Selector.open());
        TrackedSession tracked = trackedSession(server, "plugin-a");

        try {
            assertEquals(1, server.activeSessionCount());
            assertTrue(cleanerContains(tracked.session));

            server.dispose(tracked.session, tracked.channel, tracked.key);

            assertEquals(0, server.activeSessionCount());
            assertFalse(cleanerContains(tracked.session));
            assertFalse(tracked.channel.isOpen());
            assertFalse(tracked.key.isValid());
            assertEquals(1, bootstrap.unregisterCount.get());

            server.closeSession(tracked.session, tracked.key);
            assertEquals(1, bootstrap.unregisterCount.get());
        } finally {
            server.destroy("test complete");
        }
        assertTrue(executor.isShutdown());
    }

    @Test
    public void shouldReleaseAllSessionsAndExecutorOnDestroy() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, controlExecutor,
                Selector.open());
        TrackedSession first = trackedSession(server, "plugin-a");
        TrackedSession second = trackedSession(server, "plugin-b");

        try {
            assertEquals(2, server.activeSessionCount());
            assertTrue(cleanerContains(first.session));
            assertTrue(cleanerContains(second.session));

            server.destroy("test destroy");

            assertEquals(0, server.activeSessionCount());
            assertFalse(cleanerContains(first.session));
            assertFalse(cleanerContains(second.session));
            assertFalse(first.channel.isOpen());
            assertFalse(second.channel.isOpen());
            assertFalse(first.key.isValid());
            assertFalse(second.key.isValid());
            assertTrue(executor.isShutdown());
            assertTrue(controlExecutor.isShutdown());
            assertEquals(2, bootstrap.unregisterCount.get());

            server.destroy("second destroy");
            server.closeSession(first.session, first.key);
            assertEquals(2, bootstrap.unregisterCount.get());
        } finally {
            server.destroy("test complete");
        }
    }

    @Test
    public void shouldReleaseQueuedDecodedFrameBudgetOnDestroy() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
        ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(32);
        assertTrue(budget.tryReserve(5));
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, controlExecutor,
                Selector.open(), budget, 2, 1000);
        SocketChannel channel = new MetadataSocketChannel(frame("A", new byte[12]));
        SelectionKey key = new RecordingSelectionKey(channel);
        IOSession session = server.sessionFor(channel, key);
        try {
            server.dispose(session, channel, key);
            assertEquals(1, executor.getQueue().size());
            assertEquals(17L, server.reservedPacketBytes());

            server.destroy("test destroy");

            assertEquals(5L, server.reservedPacketBytes());
            server.destroy("second destroy");
            assertEquals(5L, server.reservedPacketBytes());
        } finally {
            releaseWorker.countDown();
            server.destroy("test complete");
            budget.release(5);
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(controlExecutor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0L, server.reservedPacketBytes());
    }

    @Test
    public void shouldReturnToSelectorWhenFrameIsPartial() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, Selector.open());
        TrackedSession tracked = trackedSession(server, "plugin-partial", new PartialSocketChannel());
        try {
            server.dispose(tracked.session, tracked.channel, tracked.key);

            assertEquals(1, server.activeSessionCount());
            assertTrue(tracked.channel.isOpen());
            assertTrue(tracked.key.isValid());
        } finally {
            server.destroy("test complete");
        }
    }

    @Test
    public void shouldRejectSocketMessagesAtBoundedQueueCapacity() throws Exception {
        ThreadPoolExecutor executor = PluginCoreSocketServer.newMessageExecutor();
        CountDownLatch workersStarted = new CountDownLatch(PluginCoreSocketServer.MESSAGE_HANDLER_THREADS);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blockingTask = () -> {
            workersStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            for (int i = 0; i < PluginCoreSocketServer.MESSAGE_HANDLER_THREADS; i++) {
                executor.execute(blockingTask);
            }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < PluginCoreSocketServer.MESSAGE_QUEUE_CAPACITY; i++) {
                executor.execute(() -> {
                });
            }
            try {
                executor.execute(() -> {
                });
                fail("socket message executor should reject work after reaching its memory boundary");
            } catch (RejectedExecutionException expected) {
                // expected
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void shouldRejectControlMessagesAtBoundedQueueCapacity() throws Exception {
        ThreadPoolExecutor executor = PluginCoreSocketServer.newControlExecutor();
        CountDownLatch workersStarted = new CountDownLatch(PluginCoreSocketServer.CONTROL_HANDLER_THREADS);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blockingTask = () -> {
            workersStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            for (int i = 0; i < PluginCoreSocketServer.CONTROL_HANDLER_THREADS; i++) {
                executor.execute(blockingTask);
            }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < PluginCoreSocketServer.CONTROL_QUEUE_CAPACITY; i++) {
                executor.execute(() -> {
                });
            }
            try {
                executor.execute(() -> {
                });
                fail("socket control executor should reject work after reaching its queue boundary");
            } catch (RejectedExecutionException expected) {
                // expected
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void shouldLimitConnectionsAndExpireSessionsThatNeverHandshake() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, Selector.open(),
                new SocketPacketMemoryBudget(1024), 2, 100);
        SocketChannel firstChannel = new PartialSocketChannel();
        SocketChannel secondChannel = new PartialSocketChannel();
        SocketChannel rejectedChannel = new PartialSocketChannel();
        try {
            assertNotNull(server.sessionFor(firstChannel, new RecordingSelectionKey(firstChannel)));
            assertNotNull(server.sessionFor(secondChannel, new RecordingSelectionKey(secondChannel)));
            assertNull(server.sessionFor(rejectedChannel, new RecordingSelectionKey(rejectedChannel)));
            assertFalse(rejectedChannel.isOpen());
            assertEquals(2, server.activeConnectionCount());
            assertEquals(2, server.activeSessionCount());

            server.closeExpiredHandshakes(System.currentTimeMillis() + 1000);

            assertEquals(0, server.activeConnectionCount());
            assertEquals(0, server.activeSessionCount());
            assertEquals(2, bootstrap.unregisterCount.get());
        } finally {
            server.destroy("test complete");
        }
    }

    @Test
    public void shouldReleasePartialFrameReservationWhenSessionCloses() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(16);
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, Selector.open(),
                budget, 2, 1000);
        SocketChannel channel = new MetadataSocketChannel(frameMetadata(12));
        SelectionKey key = new RecordingSelectionKey(channel);
        IOSession session = server.sessionFor(channel, key);
        try {
            server.dispose(session, channel, key);
            assertEquals(12L, server.reservedPacketBytes());

            server.closeSession(session, key);
            assertEquals(0L, server.reservedPacketBytes());
        } finally {
            server.destroy("test complete");
        }
    }

    @Test
    public void directSessionCloseShouldReleaseBudgetAndServerConnectionState() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(16);
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, Selector.open(),
                budget, 2, 1000);
        TrackedSession tracked = trackedSession(server, "plugin-direct-close",
                new MetadataSocketChannel(frameMetadata(12)));
        try {
            server.dispose(tracked.session, tracked.channel, tracked.key);
            assertEquals(12L, server.reservedPacketBytes());

            tracked.session.close();

            assertEquals(0L, server.reservedPacketBytes());
            assertEquals(0, server.activeSessionCount());
            assertEquals(0, server.activeConnectionCount());
            assertEquals(1, bootstrap.unregisterCount.get());
        } finally {
            server.destroy("test complete");
        }
    }

    @Test
    public void shouldDispatchHeartbeatWhenMessageExecutorIsSaturated() throws Exception {
        RecordingBootstrapService bootstrap = new RecordingBootstrapService();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
        executor.execute(() -> {
        });
        ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
        SocketPacketMemoryBudget budget = new SocketPacketMemoryBudget(1024);
        PluginCoreSocketServer server = new PluginCoreSocketServer(null, bootstrap, executor, controlExecutor,
                Selector.open(), budget, 2, 1000);
        SocketChannel channel = new MetadataSocketChannel(frame(MsgPacketStatus.RESPONSE_SUCCESS,
                ActionType.HTTP_METHOD.name(), new byte[0], ContentType.BYTE));
        SelectionKey key = new RecordingSelectionKey(channel);
        IOSession session = server.sessionFor(channel, key);
        CountDownLatch heartbeatHandled = new CountDownLatch(1);
        long now = System.currentTimeMillis();
        session.getPipeMap().put(1, new PipeInfo(null, null, response -> heartbeatHandled.countDown(),
                now, now + TimeUnit.SECONDS.toMillis(5)));
        try {
            server.dispose(session, channel, key);

            assertTrue(heartbeatHandled.await(5, TimeUnit.SECONDS));
            assertEquals(0L, server.reservedPacketBytes());
            assertTrue(channel.isOpen());
        } finally {
            releaseWorker.countDown();
            server.destroy("test complete");
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(controlExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aggregatePacketBudgetShouldCoverConfiguredFrameLimit() {
        String frameProperty = SocketPacketLimits.MAX_DATA_LENGTH_PROPERTY;
        String budgetProperty = PluginCoreSocketServer.MAX_IN_FLIGHT_PACKET_BYTES_PROPERTY;
        String previousFrame = System.getProperty(frameProperty);
        String previousBudget = System.getProperty(budgetProperty);
        try {
            System.setProperty(frameProperty, String.valueOf(48L * 1024L * 1024L));
            System.clearProperty(budgetProperty);
            assertEquals(48L * 1024L * 1024L, PluginCoreSocketServer.configuredMaxInFlightPacketBytes());

            System.setProperty(budgetProperty, String.valueOf(64L * 1024L * 1024L));
            assertEquals(64L * 1024L * 1024L, PluginCoreSocketServer.configuredMaxInFlightPacketBytes());
        } finally {
            restoreProperty(frameProperty, previousFrame);
            restoreProperty(budgetProperty, previousBudget);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static byte[] frameMetadata(int dataLength) {
        return HexaConversionUtil.mergeBytes(
                new byte[]{PackageVersion.V1.getVersion(), MsgPacketStatus.SEND_REQUEST.getType()},
                HexaConversionUtil.intToByteArray(1),
                new byte[]{1, 'A'},
                HexaConversionUtil.intToByteArray(dataLength),
                new byte[]{ContentType.BYTE.getType()});
    }

    private static byte[] frame(String method, byte[] payload) {
        return frame(MsgPacketStatus.SEND_REQUEST, method, payload, ContentType.JSON);
    }

    private static byte[] frame(MsgPacketStatus status, String method, byte[] payload, ContentType contentType) {
        byte[] methodBytes = method.getBytes();
        return HexaConversionUtil.mergeBytes(
                new byte[]{PackageVersion.V1.getVersion(), status.getType()},
                HexaConversionUtil.intToByteArray(1),
                new byte[]{(byte) methodBytes.length},
                methodBytes,
                HexaConversionUtil.intToByteArray(payload.length),
                new byte[]{contentType.getType()},
                payload);
    }

    private TrackedSession trackedSession(PluginCoreSocketServer server, String pluginId) throws Exception {
        return trackedSession(server, pluginId, new FailingSocketChannel());
    }

    private TrackedSession trackedSession(PluginCoreSocketServer server, String pluginId, SocketChannel channel)
            throws Exception {
        SelectionKey key = new RecordingSelectionKey(channel);
        IOSession session = server.sessionFor(channel, key);
        Plugin plugin = new Plugin();
        plugin.setId(pluginId);
        plugin.setShortName(pluginId);
        session.setPlugin(plugin);
        return new TrackedSession(session, channel, key);
    }

    private boolean cleanerContains(IOSession session) throws Exception {
        Field cleanerField = IOSession.class.getDeclaredField("clearIdlMsgPacketRunnable");
        cleanerField.setAccessible(true);
        Object cleaner = cleanerField.get(null);
        Field pipeMapsField = cleaner.getClass().getDeclaredField("pipeMaps");
        pipeMapsField.setAccessible(true);
        Collection<?> pipeMaps = (Collection<?>) pipeMapsField.get(cleaner);
        for (Object pipeMap : pipeMaps) {
            if (pipeMap == session.getPipeMap()) {
                return true;
            }
        }
        return false;
    }

    private static class TrackedSession {

        private final IOSession session;
        private final SocketChannel channel;
        private final SelectionKey key;

        private TrackedSession(IOSession session, SocketChannel channel, SelectionKey key) {
            this.session = session;
            this.channel = channel;
            this.key = key;
        }
    }

    private static class RecordingSelectionKey extends SelectionKey {

        private final SelectableChannel channel;
        private boolean valid = true;
        private int interestOps;

        private RecordingSelectionKey(SelectableChannel channel) {
            this.channel = channel;
        }

        @Override
        public SelectableChannel channel() {
            return channel;
        }

        @Override
        public Selector selector() {
            return null;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void cancel() {
            valid = false;
        }

        @Override
        public int interestOps() {
            return interestOps;
        }

        @Override
        public SelectionKey interestOps(int ops) {
            interestOps = ops;
            return this;
        }

        @Override
        public int readyOps() {
            return 0;
        }
    }

    private static class FailingSocketChannel extends SocketChannel {

        private final Socket socket = new Socket();

        private FailingSocketChannel() {
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
        public int read(ByteBuffer dst) throws IOException {
            throw new IOException("decode failed");
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            throw new IOException("decode failed");
        }

        @Override
        public int write(ByteBuffer src) {
            return src.remaining();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            long written = 0;
            for (int i = offset; i < offset + length; i++) {
                written += srcs[i].remaining();
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

    private static class PartialSocketChannel extends FailingSocketChannel {

        @Override
        public int read(ByteBuffer dst) {
            return 0;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            return 0;
        }
    }

    private static class MetadataSocketChannel extends FailingSocketChannel {

        private final ByteBuffer source;

        private MetadataSocketChannel(byte[] bytes) {
            source = ByteBuffer.wrap(bytes);
        }

        @Override
        public int read(ByteBuffer dst) {
            if (!source.hasRemaining()) {
                return 0;
            }
            int length = Math.min(source.remaining(), dst.remaining());
            ByteBuffer slice = source.slice();
            slice.limit(length);
            dst.put(slice);
            source.position(source.position() + length);
            return length;
        }
    }

    private static class RecordingBootstrapService extends PluginBootstrapService {

        private final AtomicInteger unregisterCount = new AtomicInteger();

        private RecordingBootstrapService() {
            super(Collections.emptyMap(), null, null, null);
        }

        @Override
        public void unregisterPluginSession(IOSession session) {
            unregisterCount.incrementAndGet();
        }
    }
}
