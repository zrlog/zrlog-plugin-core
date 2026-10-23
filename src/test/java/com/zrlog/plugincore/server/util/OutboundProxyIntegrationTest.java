package com.zrlog.plugincore.server.util;

import com.hibegin.common.dao.DAO;
import com.sun.net.httpserver.HttpServer;
import com.zrlog.plugin.common.model.BlogRunTime;
import com.zrlog.plugin.common.type.HttpMethod;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;
import com.zrlog.plugin.data.codec.HttpResponseInfo;
import com.zrlog.plugincore.server.Application;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginDataSourceInitializer;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class OutboundProxyIntegrationTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 45000)
    public void databaseForwardingAndArtifactRequestsUseTheSameProxy() throws Exception {
        for (String mode : List.of("jvm", "environment")) {
            Path directory = temporary.newFolder(mode).toPath();
            try (ServerSocket proxy = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
                proxy.setSoTimeout(15000);
                CompletableFuture<List<String>> requests = new CompletableFuture<>();
                Thread worker = new Thread(() -> receiveRequests(proxy, requests));
                worker.setDaemon(true);
                worker.start();
                Path output = directory.resolve("probe.log");
                Process process = probe(mode, proxy.getLocalPort(), directory).redirectErrorStream(true)
                        .redirectOutput(output.toFile()).start();
                try {
                    boolean finished = process.waitFor(15, TimeUnit.SECONDS);
                    assertTrue(mode + ": probe timed out\n" + Files.readString(output), finished);
                    assertEquals(mode + ": " + Files.readString(output), 0, process.exitValue());
                    assertEquals(List.of(
                            "GET http://artifact.invalid/plugin.jar.md5 HTTP/1.1",
                            "GET http://artifact.invalid/plugin.jar HTTP/1.1",
                            "POST http://api.invalid/forward HTTP/1.1",
                            "CONNECT api.invalid:443 HTTP/1.1",
                            "CONNECT dl.zrlog.com:443 HTTP/1.1",
                            "CONNECT dl.zrlog.com:443 HTTP/1.1",
                            "CONNECT database.invalid:443 HTTP/1.1",
                            "CONNECT database.invalid:443 HTTP/1.1"), requests.get(2, TimeUnit.SECONDS));
                } finally {
                    process.destroyForcibly();
                    proxy.close();
                    worker.join(2000);
                }
            }
        }
    }

    private static ProcessBuilder probe(String mode, int port, Path directory) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if ("jvm".equals(mode)) {
            command.addAll(List.of("-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=" + port,
                    "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=" + port,
                    "-Dhttp.nonProxyHosts=localhost|127.*|[::1]|[::ffff:127.*|*.internal"));
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), directory.toString()));
        ProcessBuilder builder = new ProcessBuilder(command);
        for (String key : List.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy")) {
            builder.environment().remove(key);
        }
        if ("environment".equals(mode)) {
            builder.environment().put("HTTP_PROXY", "http://ignored.invalid:3128");
            builder.environment().put("http_proxy", "http://127.0.0.1:" + port);
            builder.environment().put("HTTPS_PROXY", "http://127.0.0.1:" + port);
            builder.environment().put("NO_PROXY", ".internal");
        } else {
            // Host launch arguments must win over the independently inherited environment.
            builder.environment().put("HTTP_PROXY", "http://ignored.invalid:3128");
            builder.environment().put("HTTPS_PROXY", "http://ignored.invalid:3128");
            builder.environment().put("NO_PROXY", "*");
        }
        return builder;
    }

    private static void receiveRequests(ServerSocket proxy, CompletableFuture<List<String>> result) {
        try {
            List<String> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                try (var connection = proxy.accept()) {
                    connection.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            connection.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    requests.add(request);
                    String line;
                    int length = 0;
                    boolean forwardedHeader = false;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                        if (line.equalsIgnoreCase("X-Probe: forwarded")) {
                            forwardedHeader = true;
                        }
                    }
                    char[] body = new char[length];
                    for (int offset = 0; offset < length;) {
                        int count = reader.read(body, offset, length - offset);
                        if (count < 0) throw new IOException("Incomplete request body");
                        offset += count;
                    }
                    if (request.startsWith("POST ")) {
                        assertTrue("Missing forwarded header", forwardedHeader);
                        assertEquals("payload", new String(body));
                    }
                    boolean tunnel = request.startsWith("CONNECT ");
                    connection.getOutputStream().write(((tunnel ? "HTTP/1.1 502 Bad Gateway" : "HTTP/1.1 200 OK")
                            + "\r\nContent-Length: " + (tunnel ? 0 : 7)
                            + "\r\nX-Probe-Response: forwarded\r\nConnection: close\r\n\r\n"
                            + (tunnel ? "" : "proxied")).getBytes(StandardCharsets.US_ASCII));
                }
            }
            result.complete(requests);
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            // Exercise the actual startup initialization before loading either HTTP client.
            Class.forName(Application.class.getName());
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    throw new AssertionError("Client used the global selector instead of the startup proxy policy");
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException error) {
                }
            });
            Path directory = Path.of(args[0]);
            byte[] expected = "proxied".getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(expected, HttpUtils.sendGetRequest("http://artifact.invalid/plugin.jar.md5", Map.of()));
            Path target = directory.resolve("plugin.jar");
            HttpUtils.downloadToFile("http://artifact.invalid/plugin.jar", Map.of(), target.toFile());
            assertArrayEquals(expected, Files.readAllBytes(target));

            BaseHttpRequestInfo request = new BaseHttpRequestInfo();
            request.setAccessUrl("http://api.invalid/forward");
            request.setHttpMethod(HttpMethod.POST);
            request.setHeader(Map.of("X-Probe", "forwarded"));
            request.setRequestBody("payload".getBytes(StandardCharsets.US_ASCII));
            HttpResponseInfo response = HttpUtils.doRequest(request);
            assertEquals(200, response.getStatusCode());
            assertEquals("forwarded", response.getHeader().get("x-probe-response"));
            assertArrayEquals(expected, response.getResponseBody());

            request.setAccessUrl("https://api.invalid/forward");
            assertThrows(IOException.class, () -> HttpUtils.doRequest(request));
            PluginRuntimeServices services = PluginRuntimeServices.create(
                    new PluginConfig(null, null, 0, directory.toString(), new BlogRunTime()), PluginHostConnection.defaults());
            PluginRuntimeBridge.install(services);
            try {
                assertThrows(IOException.class, () -> PluginFiles.downloadPlugin("comment.jar"));
                assertFalse(Files.exists(directory.resolve("comment.jar")));
                assertEquals("", PluginFiles.pluginFileRemoteMd5("comment.jar"));
            } finally {
                services.shutdown();
            }

            Properties properties = new Properties();
            properties.setProperty("jdbcUrl", "jdbc:webapi://database.invalid:443/query");
            properties.setProperty("user", "test-user");
            properties.setProperty("password", "test-token");
            properties.setProperty("driverClass", "unused.for.webapi");
            Path dbProperties = directory.resolve("db.properties");
            try (OutputStream out = Files.newOutputStream(dbProperties)) {
                properties.store(out, null);
            }
            new PluginDataSourceInitializer().initialize(dbProperties.toFile());
            try (var dataSource = DAO.getDefaultDataSource()) {
                assertThrows(SQLException.class, dataSource::testConnection);
                assertThrows(SQLException.class, () -> dataSource.getQueryRunner().update("update example set value = ?", "test"));
            }

            // The same configured policy must not affect ordinary JDBC databases.
            for (String jdbcUrl : List.of("jdbc:h2:mem:proxy_probe", "jdbc:sqlite:" + directory.resolve("probe.db"))) {
                properties.setProperty("jdbcUrl", jdbcUrl);
                properties.setProperty("driverClass", jdbcUrl.startsWith("jdbc:h2:") ? "org.h2.Driver" : "org.sqlite.JDBC");
                try (OutputStream out = Files.newOutputStream(dbProperties)) {
                    properties.store(out, null);
                }
                new PluginDataSourceInitializer().initialize(dbProperties.toFile());
                try (var dataSource = DAO.getDefaultDataSource(); var connection = dataSource.getConnection();
                     var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 1")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }

            HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            local.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, 5);
                exchange.getResponseBody().write("local".getBytes(StandardCharsets.US_ASCII));
                exchange.close();
            });
            local.start();
            try {
                assertArrayEquals("local".getBytes(StandardCharsets.US_ASCII), HttpUtils.sendGetRequest(
                        "http://127.0.0.1:" + local.getAddress().getPort() + "/", Map.of()));
            } finally {
                local.stop(0);
            }
            // common-dao's HTTP executor has process lifetime.
            System.exit(0);
        }
    }
}
