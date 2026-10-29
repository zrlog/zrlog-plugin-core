package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.sun.net.httpserver.HttpServer;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.client.HttpClientUtils;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual HTTP -> core handler -> socket FILE -> shared client, with independent spool files. */
public class HttpFileDownloadIntegrationTest {
    private static final long HEXO_LIMIT = 64L * 1024 * 1024;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 60000)
    public void downloadsFull64MiBThroughFileProtocolAndReleasesBothTemporaryFiles() throws Exception {
        byte[] block = new byte[64 * 1024];
        for (int i = 0; i < block.length; i++) block[i] = (byte) i;
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        for (long size = 0; size < HEXO_LIMIT; size += block.length) expected.update(block);
        HttpServer server = server(block, HEXO_LIMIT, false);
        try (Transport transport = new Transport(false)) {
            Path target = temporary.newFile("archive.zip").toPath();
            HttpClientUtils.downloadToFile(url(server), target, HEXO_LIMIT, Collections.emptyMap(),
                    transport.client, Duration.ofSeconds(45));
            transport.finish();
            assertEquals(HEXO_LIMIT, Files.size(target));
            assertArrayEquals(expected.digest(), digest(target));
            Path coreFile = transport.sentFile.get();
            Path pluginFile = transport.receivedFile.get();
            assertNotEquals(coreFile, pluginFile);
            assertCleaned(coreFile);
            assertCleaned(pluginFile);
            assertTrue(transport.client.getPipeMap().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 60000)
    public void rejectsOneByteBeyond64MiBEvenWithoutContentLength() throws Exception {
        HttpServer server = server(new byte[64 * 1024], HEXO_LIMIT + 1, true);
        try (Transport transport = new Transport(false)) {
            Path target = temporary.newFile("existing.zip").toPath();
            Files.write(target, new byte[]{9});
            assertThrows(IOException.class, () -> HttpClientUtils.downloadToFile(url(server), target,
                    HEXO_LIMIT, null, transport.client, Duration.ofSeconds(45)));
            transport.finish();
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(target));
            assertNull(transport.sentFile.get());
            assertNull(transport.receivedFile.get());
            assertTrue(transport.client.getPipeMap().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 10000)
    public void releasesDownloadedSourceWhenSocketSendFails() throws Exception {
        HttpServer server = server(new byte[]{1, 2, 3}, 3, false);
        try (Transport transport = new Transport(true)) {
            Path target = temporary.newFile("existing.zip").toPath();
            Files.write(target, new byte[]{9});
            assertThrows(IOException.class, () -> HttpClientUtils.downloadToFile(url(server), target,
                    HEXO_LIMIT, null, transport.client, Duration.ofSeconds(3)));
            transport.finish();
            assertCleaned(transport.sentFile.get());
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(target));
            assertTrue(transport.client.getPipeMap().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private static void assertCleaned(Path path) {
        assertNotNull("FILE was not transferred", path);
        assertFalse(Files.exists(path));
        assertFalse(Files.exists(path.getParent()));
    }

    private static byte[] digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] block = new byte[64 * 1024];
            int count;
            while ((count = input.read(block)) >= 0) digest.update(block, 0, count);
        }
        return digest.digest();
    }

    private static HttpServer server(byte[] block, long size, boolean chunked) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/archive.zip", exchange -> {
            try {
                exchange.sendResponseHeaders(200, chunked ? 0 : size);
                for (long sent = 0; sent < size; sent += block.length) {
                    exchange.getResponseBody().write(block, 0, (int) Math.min(block.length, size - sent));
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/archive.zip";
    }

    private static final class Transport implements AutoCloseable {
        private final Selector selector = Selector.open();
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final AtomicReference<Path> sentFile = new AtomicReference<>();
        private final AtomicReference<Path> receivedFile = new AtomicReference<>();
        private final IOSession client;
        private final IOSession core;
        private final Future<?> request;
        private final Future<?> response;

        private Transport(boolean failSend) throws IOException {
            SocketChannel pluginChannel;
            SocketChannel coreChannel;
            try (ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                pluginChannel = SocketChannel.open(listener.getLocalAddress());
                coreChannel = listener.accept();
            }
            pluginChannel.configureBlocking(false);
            coreChannel.configureBlocking(false);
            SocketDecode coreDecode = new SocketDecode(Runnable::run);
            core = new IOSession(coreChannel, selector, new SocketCodec(new SocketEncode() {
                @Override public void doEncode(IOSession session, MsgPacket packet) throws Exception {
                    if (packet.getContentType() == ContentType.FILE) {
                        sentFile.set(packet.getFilePayload().getFile().toPath());
                        if (failSend) throw new IOException("injected socket write failure");
                    }
                    super.doEncode(session, packet);
                }
            }, coreDecode), new ServerActionHandler());
            SocketDecode clientDecode = new SocketDecode(Runnable::run);
            client = new IOSession(pluginChannel, selector,
                    new SocketCodec(new SocketEncode(), clientDecode), new ClientActionHandler()) {
                @Override public void dispose(MsgPacket packet, Runnable release) {
                    if (packet.getContentType() == ContentType.FILE) {
                        receivedFile.set(packet.getFilePayload().getFile().toPath());
                    }
                    super.dispose(packet, release);
                }
            };
            request = workers.submit(() -> decodeOne(coreDecode, core, false));
            response = workers.submit(() -> decodeOne(clientDecode, client, failSend));
        }

        private static void decodeOne(SocketDecode decoder, IOSession session, boolean expectDisconnect) {
            try {
                while (!decoder.doDecode(session)) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("interrupted");
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            } catch (Exception e) {
                session.close();
                if (!expectDisconnect) throw new RuntimeException(e);
            }
        }

        private void finish() throws Exception {
            request.get(5, TimeUnit.SECONDS);
            response.get(5, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            client.close();
            core.close();
            selector.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
