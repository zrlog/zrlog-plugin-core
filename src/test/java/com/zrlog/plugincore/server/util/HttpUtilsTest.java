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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;

public class HttpUtilsTest {

    @Test
    public void shouldDownloadBeyondBufferedLimitAndPreserveOrdinaryResponseLimit() throws Exception {
        byte[] content = new byte[5 * 1024 * 1024];
        content[0] = 0x50;
        content[content.length - 1] = (byte) 0xff;
        HttpServer server = server(content);
        Path directory = Files.createTempDirectory("shared-http-file-");
        try {
            BaseHttpRequestInfo request = fileRequest(url(server), (long) content.length);
            Path target = directory.resolve("archive.zip");
            HttpUtils.downloadHttpFile(request, target);
            assertArrayEquals(content, Files.readAllBytes(target));
            assertThrows(IOException.class, () -> HttpUtils.doRequest(request));
        } finally {
            server.stop(0);
            delete(directory);
        }
    }

    @Test
    public void shouldEnforceFileLimitWithAndWithoutContentLengthAndCleanPartialFiles() throws Exception {
        for (boolean chunked : new boolean[]{false, true}) {
            HttpServer server = server(new byte[]{1, 2, 3}, chunked);
            Path directory = Files.createTempDirectory("shared-http-limit-");
            Path target = directory.resolve("archive.zip");
            Files.write(target, new byte[]{9});
            try {
                assertThrows(IOException.class, () -> HttpUtils.downloadHttpFile(fileRequest(url(server), 2L), target));
                assertArrayEquals(new byte[]{9}, Files.readAllBytes(target));
                try (Stream<Path> files = Files.list(directory)) {
                    assertEquals(1, files.count());
                }
            } finally {
                server.stop(0);
                delete(directory);
            }
        }
    }

    @Test
    public void shouldRejectRedirectsWithoutForwardingCredentialsOrReturningErrorFiles() throws Exception {
        java.util.concurrent.atomic.AtomicInteger redirected = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        for (int status : new int[]{206, 302, 307, 401, 500}) {
            server.createContext("/status/" + status, exchange -> {
                exchange.getResponseHeaders().add("Location", url(server) + "external");
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
        }
        server.start();
        Path directory = Files.createTempDirectory("shared-http-status-");
        try {
            for (int status : new int[]{206, 302, 307, 401, 500}) {
                BaseHttpRequestInfo request = fileRequest(url(server) + "status/" + status, 1024L);
                request.setHeader(Collections.singletonMap("Cookie", "private-session"));
                Path target = directory.resolve("archive.zip");
                HttpUtils.HttpDownloadException error = assertThrows(HttpUtils.HttpDownloadException.class,
                        () -> HttpUtils.downloadHttpFile(request, target));
                assertEquals(status, error.getStatusCode());
                assertFalse(Files.exists(target));
                try (Stream<Path> files = Files.list(directory)) {
                    assertEquals(0, files.count());
                }
            }
            assertEquals(0, redirected.get());
        } finally {
            server.stop(0);
            delete(directory);
        }
    }

    @Test
    public void shouldRejectUnboundedDownloadsAndNonGetRequestsBeforeCreatingFiles() throws Exception {
        Path directory = Files.createTempDirectory("shared-http-invalid-");
        Path target = directory.resolve("archive.zip");
        try {
            for (Long limit : new Long[]{null, 0L, -1L, 256L * 1024 * 1024 + 1}) {
                BaseHttpRequestInfo request = fileRequest("http://invalid.invalid/", limit);
                assertThrows(IllegalArgumentException.class, () -> HttpUtils.downloadHttpFile(request, target));
            }
            BaseHttpRequestInfo request = fileRequest("http://invalid.invalid/", 1024L);
            request.setHttpMethod(HttpMethod.POST);
            assertThrows(IllegalArgumentException.class, () -> HttpUtils.downloadHttpFile(request, target));
            request.setHttpMethod(HttpMethod.GET);
            request.setRequestBody(new byte[]{1});
            assertThrows(IllegalArgumentException.class, () -> HttpUtils.downloadHttpFile(request, target));
            assertFalse(Files.exists(target));
        } finally {
            delete(directory);
        }
    }

    private static BaseHttpRequestInfo fileRequest(String url, Long limit) {
        BaseHttpRequestInfo request = new BaseHttpRequestInfo();
        request.setAccessUrl(url);
        request.setHttpMethod(HttpMethod.GET);
        request.setResponseBodyAsFile(true);
        request.setMaxResponseBodyBytes(limit);
        return request;
    }

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
