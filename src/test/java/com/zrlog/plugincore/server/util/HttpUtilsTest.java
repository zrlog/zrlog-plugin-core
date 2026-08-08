package com.zrlog.plugincore.server.util;

import com.sun.net.httpserver.HttpServer;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;
import com.zrlog.plugin.data.codec.HttpResponseInfo;
import com.zrlog.plugin.common.type.HttpMethod;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpUtilsTest {

    @Test
    public void shouldRejectOversizedBufferedResponseBeforeReturningIt() throws Exception {
        byte[] response = "response-too-large".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(response);
        try {
            String url = url(server);

            assertArrayEquals(response,
                    HttpUtils.sendGetRequest(url, Collections.emptyMap(), response.length));

            try {
                HttpUtils.sendGetRequest(url, Collections.emptyMap(), response.length - 1L);
                fail("Expected response size rejection");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("exceeds"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void shouldRejectOversizedChunkedResponse() throws Exception {
        byte[] response = "chunked-response-too-large".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(response, true);
        try {
            try {
                HttpUtils.sendGetRequest(url(server), Collections.emptyMap(), response.length - 1L);
                fail("Expected chunked response size rejection");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("exceeds"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void shouldBoundPluginHttpProxyResponse() throws Exception {
        byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(response);
        try {
            BaseHttpRequestInfo request = new BaseHttpRequestInfo();
            request.setAccessUrl(url(server));
            request.setHttpMethod(HttpMethod.GET);

            HttpResponseInfo result = HttpUtils.doRequest(request);

            assertEquals(200, result.getStatusCode());
            assertArrayEquals(response, result.getResponseBody());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void shouldPreserveExistingArtifactWhenDownloadExceedsLimit() throws Exception {
        byte[] response = "artifact-too-large".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(response);
        Path directory = Files.createTempDirectory("plugin-download-limit");
        Path target = directory.resolve("plugin.bin");
        byte[] existing = "existing".getBytes(StandardCharsets.UTF_8);
        Files.write(target, existing);
        try {
            try {
                HttpUtils.downloadToFile(url(server), Collections.emptyMap(), target.toFile(), response.length - 1L);
                fail("Expected download size rejection");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("exceeds"));
            }

            assertArrayEquals(existing, Files.readAllBytes(target));
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(1L, files.count());
            }
        } finally {
            server.stop(0);
            delete(directory);
        }
    }

    private static HttpServer server(byte[] response) throws IOException {
        return server(response, false);
    }

    private static HttpServer server(byte[] response, boolean chunked) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, chunked ? 0 : response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
