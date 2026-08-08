package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.hibegin.common.util.EnvKit;
import com.hibegin.http.server.api.ISocketServer;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;
import com.zrlog.plugin.data.codec.SocketPacketLimits;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Channel;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginCoreSocketServer implements ISocketServer {


    private static final Logger LOGGER = LoggerUtil.getLogger(PluginCoreSocketServer.class);
    private static final String SELECTION_KEY_ATTR = "_selectionKey";
    static final int MESSAGE_HANDLER_THREADS = 4;
    static final int MESSAGE_QUEUE_CAPACITY = 8;
    static final int CONTROL_HANDLER_THREADS = 2;
    static final int CONTROL_QUEUE_CAPACITY = 16;
    static final int MAX_SOCKET_CONNECTIONS = 40;
    static final long HANDSHAKE_TIMEOUT_MS = 30000L;
    static final long DEFAULT_MAX_IN_FLIGHT_PACKET_BYTES = 32L * 1024L * 1024L;
    static final String MAX_IN_FLIGHT_PACKET_BYTES_PROPERTY = "zrlog.plugin.socket.maxInFlightBytes";
    static final String MAX_IN_FLIGHT_PACKET_BYTES_ENV = "PLUGIN_SOCKET_MAX_IN_FLIGHT_BYTES";

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private int port;
    private final Map<Socket, IOSession> decoderMap = new ConcurrentHashMap<>();
    private final Map<SocketChannel, Long> connectionAcceptedAtMap = new ConcurrentHashMap<>();
    private final Object sessionLifecycleLock = new Object();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final ExecutorService executor;
    private final ExecutorService controlExecutor;
    private final ServiceInvocationDispatcher serviceInvocationDispatcher = new ServiceInvocationDispatcher();
    private final PluginConfig pluginConfig;
    private final PluginBootstrapService pluginBootstrap;
    private final SocketPacketMemoryBudget packetMemoryBudget;
    private final int maxSocketConnections;
    private final long handshakeTimeoutMs;

    public PluginCoreSocketServer() {
        this(PluginRuntimeBridge.pluginConfig(), PluginRuntimeBridge.pluginBootstrap());
    }

    public PluginCoreSocketServer(PluginConfig pluginConfig, PluginBootstrapService pluginBootstrap) {
        this(pluginConfig, pluginBootstrap, newMessageExecutor(), newControlExecutor(), null);
    }

    PluginCoreSocketServer(PluginConfig pluginConfig, PluginBootstrapService pluginBootstrap,
                           ExecutorService executor) {
        this(pluginConfig, pluginBootstrap, executor, newControlExecutor(), null);
    }

    PluginCoreSocketServer(PluginConfig pluginConfig, PluginBootstrapService pluginBootstrap,
                           ExecutorService executor, Selector selector) {
        this(pluginConfig, pluginBootstrap, executor, newControlExecutor(), selector,
                new SocketPacketMemoryBudget(configuredMaxInFlightPacketBytes()),
                MAX_SOCKET_CONNECTIONS, HANDSHAKE_TIMEOUT_MS);
    }

    PluginCoreSocketServer(PluginConfig pluginConfig, PluginBootstrapService pluginBootstrap,
                           ExecutorService executor, ExecutorService controlExecutor, Selector selector) {
        this(pluginConfig, pluginBootstrap, executor, controlExecutor, selector,
                new SocketPacketMemoryBudget(configuredMaxInFlightPacketBytes()),
                MAX_SOCKET_CONNECTIONS, HANDSHAKE_TIMEOUT_MS);
    }

    PluginCoreSocketServer(PluginConfig pluginConfig, PluginBootstrapService pluginBootstrap,
                           ExecutorService executor, Selector selector,
                           SocketPacketMemoryBudget packetMemoryBudget,
                           int maxSocketConnections, long handshakeTimeoutMs) {
        this(pluginConfig, pluginBootstrap, executor, newControlExecutor(), selector, packetMemoryBudget,
                maxSocketConnections, handshakeTimeoutMs);
    }

    PluginCoreSocketServer(PluginConfig pluginConfig, PluginBootstrapService pluginBootstrap,
                           ExecutorService executor, ExecutorService controlExecutor, Selector selector,
                           SocketPacketMemoryBudget packetMemoryBudget,
                           int maxSocketConnections, long handshakeTimeoutMs) {
        this.pluginConfig = pluginConfig;
        this.pluginBootstrap = pluginBootstrap;
        this.executor = executor;
        this.controlExecutor = controlExecutor;
        this.selector = selector;
        this.packetMemoryBudget = packetMemoryBudget;
        this.maxSocketConnections = Math.max(1, maxSocketConnections);
        this.handshakeTimeoutMs = Math.max(1L, handshakeTimeoutMs);
    }

    @Override
    public void listen() {
        if (selector == null) {
            return;
        }
        while (selector.isOpen()) {
            try {
                if (selector.select(200) <= 0) {
                    closeExpiredHandshakes(System.currentTimeMillis());
                    continue;
                }
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    SocketChannel channel;
                    if (key.isAcceptable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        channel = server.accept();
                        if (channel != null) {
                            registerAcceptedChannel(channel);
                        }
                    } else if (key.isReadable()) {
                        channel = (SocketChannel) key.channel();
                        if (channel != null) {
                            IOSession session = sessionFor(channel, key);
                            if (session != null) {
                                dispose(session, channel, key);
                            }
                        }
                    }
                    iter.remove();
                }
                closeExpiredHandshakes(System.currentTimeMillis());
            } catch (Exception e) {
                if (e instanceof ClosedSelectorException) {
                    return;
                }
                LOGGER.log(Level.SEVERE, "", e);
            }
        }
    }

    @Override
    public void destroy(String s) {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        try {
            closeQuietly(serverChannel);
            List<IOSession> sessions;
            synchronized (sessionLifecycleLock) {
                sessions = new ArrayList<>(decoderMap.values());
                decoderMap.clear();
            }
            for (IOSession session : sessions) {
                closeClaimedSession(session, selectionKey(session));
            }
        } finally {
            try {
                closeRegisteredChannels();
            } finally {
                closeQuietly(serverChannel);
                closeQuietly(selector);
                shutdownExecutors();
            }
        }
    }

    @Override
    public boolean create() {
        return create(pluginConfig.getMasterPort());
    }

    @Override
    public boolean create(int port) {
        return create("127.0.0.1", port);
    }

    @Override
    public boolean create(String s, int port) {
        synchronized (sessionLifecycleLock) {
            if (destroyed.get()) {
                return false;
            }
            try {
                serverChannel = ServerSocketChannel.open();
                serverChannel.socket().bind(new InetSocketAddress(s, port));
                serverChannel.configureBlocking(false);
                selector = Selector.open();
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                this.port = port;

                LOGGER.info("zrlog-plugin-core-server listening on port -> " + port);
                return true;
            } catch (Exception e) {
                closeQuietly(selector);
                closeQuietly(serverChannel);
                selector = null;
                serverChannel = null;
                LOGGER.log(Level.SEVERE, "", e);
            }
        }
        return false;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "close plugin socket server error", e);
        }
    }

    @Override
    public int getPort() {
        return port;
    }

    private void registerAcceptedChannel(SocketChannel channel) {
        synchronized (sessionLifecycleLock) {
            if (destroyed.get()) {
                closeQuietly(channel);
                return;
            }
            try {
                if (!trackConnection(channel, System.currentTimeMillis())) {
                    closeQuietly(channel);
                    LOGGER.warning("reject plugin socket connection because the connection limit was reached");
                    return;
                }
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ);
            } catch (Exception e) {
                connectionAcceptedAtMap.remove(channel);
                closeQuietly(channel);
                LOGGER.log(Level.WARNING, "register plugin socket channel error", e);
            }
        }
    }

    IOSession sessionFor(SocketChannel channel, SelectionKey key) {
        synchronized (sessionLifecycleLock) {
            if (destroyed.get()) {
                closeQuietly(channel);
                return null;
            }
            Socket socket = channel.socket();
            IOSession session = decoderMap.get(socket);
            if (session == null) {
                if (!trackConnection(channel, System.currentTimeMillis())) {
                    closeQuietly(channel);
                    return null;
                }
                IOSession createdSession = new IOSession(channel, selector,
                        new SocketCodec(new SocketEncode(),
                                new SocketDecode(executor, controlExecutor, packetMemoryBudget)),
                        new ServerActionHandler(serviceInvocationDispatcher));
                createdSession.addCloseListener(() -> onSessionClosed(createdSession));
                session = createdSession;
                decoderMap.put(socket, session);
            }
            if (key != null) {
                session.getSystemAttr().put(SELECTION_KEY_ATTR, key);
            }
            return session;
        }
    }

    int activeSessionCount() {
        return decoderMap.size();
    }

    int activeConnectionCount() {
        return connectionAcceptedAtMap.size();
    }

    long reservedPacketBytes() {
        return packetMemoryBudget.getReservedBytes();
    }

    void dispose(IOSession session, SocketChannel channel, SelectionKey key) {
        long start = System.currentTimeMillis();
        SocketDecode decode = (SocketDecode) session.getSystemAttr().get("_decode");
        try {
            decode.doDecode(session);
        } catch (Exception e) {
            closeSession(session, key);
            LOGGER.log(Level.SEVERE, "dispose error " + e.getMessage());
        } finally {
            if (EnvKit.isDevMode()) {
                try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
                    LOGGER.info(PluginLogContext.prefix("doDecode used time " + (System.currentTimeMillis() - start) + " ms"));
                }
            }
        }
    }

    static ThreadPoolExecutor newMessageExecutor() {
        AtomicInteger threadIndex = new AtomicInteger();
        return new ThreadPoolExecutor(
                MESSAGE_HANDLER_THREADS,
                MESSAGE_HANDLER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MESSAGE_QUEUE_CAPACITY),
                runnable -> new Thread(runnable, "zrlog-plugin-message-" + threadIndex.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    static ThreadPoolExecutor newControlExecutor() {
        AtomicInteger threadIndex = new AtomicInteger();
        return new ThreadPoolExecutor(
                CONTROL_HANDLER_THREADS,
                CONTROL_HANDLER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONTROL_QUEUE_CAPACITY),
                runnable -> new Thread(runnable, "zrlog-plugin-control-" + threadIndex.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private void shutdownExecutors() {
        try {
            shutdownExecutor(executor);
        } finally {
            try {
                if (controlExecutor != executor) {
                    shutdownExecutor(controlExecutor);
                }
            } finally {
                serviceInvocationDispatcher.shutdown();
            }
        }
    }

    private void shutdownExecutor(ExecutorService executorService) {
        List<Runnable> queuedTasks = executorService.shutdownNow();
        for (Runnable queuedTask : queuedTasks) {
            SocketDecode.cancelQueuedDispatch(queuedTask);
        }
    }

    void closeSession(IOSession session, SelectionKey key) {
        if (session == null) {
            return;
        }
        if (key != null) {
            session.getSystemAttr().put(SELECTION_KEY_ATTR, key);
        }
        session.close();
    }

    private void closeClaimedSession(IOSession session, SelectionKey key) {
        if (key != null) {
            session.getSystemAttr().put(SELECTION_KEY_ATTR, key);
        }
        session.close();
    }

    private void onSessionClosed(IOSession session) {
        SelectionKey key = selectionKey(session);
        cancelQuietly(key);
        session.getSystemAttr().remove(SELECTION_KEY_ATTR);
        SocketChannel channel = channel(session);
        if (channel != null) {
            decoderMap.remove(channel.socket(), session);
            connectionAcceptedAtMap.remove(channel);
        }
        try {
            pluginBootstrap.unregisterPluginSession(session);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "unregister plugin session error", e);
        }
    }

    private SocketChannel channel(IOSession session) {
        Object channel = session.getSystemAttr().get("_channel");
        return channel instanceof SocketChannel ? (SocketChannel) channel : null;
    }

    private SelectionKey selectionKey(IOSession session) {
        Object sessionKey = session.getSystemAttr().get(SELECTION_KEY_ATTR);
        if (sessionKey instanceof SelectionKey) {
            return (SelectionKey) sessionKey;
        }
        SocketChannel channel = channel(session);
        Selector currentSelector = selector;
        if (channel == null || currentSelector == null) {
            return null;
        }
        try {
            return channel.keyFor(currentSelector);
        } catch (Exception e) {
            return null;
        }
    }

    private void closeRegisteredChannels() {
        Selector currentSelector = selector;
        if (currentSelector == null) {
            return;
        }
        try {
            for (SelectionKey key : new ArrayList<>(currentSelector.keys())) {
                Channel channel = key.channel();
                cancelQuietly(key);
                closeQuietly(channel);
            }
        } catch (ClosedSelectorException ignored) {
        } finally {
            connectionAcceptedAtMap.clear();
        }
    }

    void closeExpiredHandshakes(long now) {
        for (Map.Entry<SocketChannel, Long> entry : new ArrayList<>(connectionAcceptedAtMap.entrySet())) {
            SocketChannel channel = entry.getKey();
            IOSession session = decoderMap.get(channel.socket());
            if (!channel.isOpen()) {
                if (session != null) {
                    session.close();
                } else {
                    connectionAcceptedAtMap.remove(channel, entry.getValue());
                }
                continue;
            }
            if (now - entry.getValue() < handshakeTimeoutMs) {
                continue;
            }
            if (session != null && session.getPlugin() != null) {
                continue;
            }
            if (session != null) {
                closeSession(session, selectionKey(session));
            } else if (connectionAcceptedAtMap.remove(channel, entry.getValue())) {
                cancelQuietly(selector == null ? null : channel.keyFor(selector));
                closeQuietly(channel);
            }
        }
    }

    private boolean trackConnection(SocketChannel channel, long acceptedAt) {
        if (connectionAcceptedAtMap.containsKey(channel)) {
            return true;
        }
        if (!hasConnectionCapacity(connectionAcceptedAtMap.size(), maxSocketConnections)) {
            return false;
        }
        connectionAcceptedAtMap.put(channel, acceptedAt);
        return true;
    }

    static boolean hasConnectionCapacity(int activeConnections, int maxConnections) {
        return activeConnections < Math.max(1, maxConnections);
    }

    static long configuredMaxInFlightPacketBytes() {
        int maxFrameBytes = SocketPacketLimits.configuredMaxDataLengthBytes();
        String value = System.getProperty(MAX_IN_FLIGHT_PACKET_BYTES_PROPERTY);
        String source = MAX_IN_FLIGHT_PACKET_BYTES_PROPERTY;
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(MAX_IN_FLIGHT_PACKET_BYTES_ENV);
            source = MAX_IN_FLIGHT_PACKET_BYTES_ENV;
        }
        if (value == null || value.trim().isEmpty()) {
            return Math.max(DEFAULT_MAX_IN_FLIGHT_PACKET_BYTES, (long) maxFrameBytes);
        }
        try {
            long configured = Long.parseLong(value.trim());
            if (configured < maxFrameBytes) {
                throw new IllegalArgumentException(source + " must be at least the configured socket frame limit "
                        + maxFrameBytes);
            }
            return configured;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(source + " must be a valid long", e);
        }
    }

    private void cancelQuietly(SelectionKey key) {
        if (key == null) {
            return;
        }
        try {
            key.cancel();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "cancel plugin socket key error", e);
        }
    }
}
