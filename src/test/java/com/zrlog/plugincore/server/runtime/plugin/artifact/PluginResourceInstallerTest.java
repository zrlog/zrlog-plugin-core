package com.zrlog.plugincore.server.runtime.plugin.artifact;

import com.sun.net.httpserver.HttpServer;
import com.zrlog.plugincore.server.web.controller.PluginApiModels;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class PluginResourceInstallerTest {
    @Test public void streamsLargeResourceIntoInstallerAndRemovesDownloadAfterwards() throws Exception {
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = new byte[5 * 1024 * 1024];
        bytes[bytes.length - 1] = 42;
        AtomicReference<String> cookie = new AtomicReference<>();
        AtomicReference<Path> downloaded = new AtomicReference<>();
        host.createContext("/blog/api/admin/upload/resource", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        host.start();
        try {
            PluginInstallService installer = new PluginInstallService(Path.of("/tmp"), "", null) {
                @Override public PluginApiModels.InstallResponse install(String name, boolean overwrite, File file) throws IOException {
                    downloaded.set(file.toPath());
                    assertEquals("travel.jar", name);
                    assertTrue(overwrite);
                    assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
                    return PluginApiModels.InstallResponse.success("travel", name, true, "installed");
                }
            };
            var response = new PluginResourceInstaller(installer, "http://127.0.0.1:" + host.getAddress().getPort() + "/blog")
                    .install("/admin/attached/tmp/package.jar", "travel.jar", true, Map.of("Cookie", "admin-token=test"));
            assertEquals(0, response.getError());
            assertEquals("admin-token=test", cookie.get());
            assertFalse(Files.exists(downloaded.get()));
        } finally { host.stop(0); }
    }

    @Test public void refusesRedirectWithoutContactingItsTarget() throws Exception {
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger leaked = new AtomicInteger();
        host.createContext("/api/admin/upload/resource", exchange -> {
            exchange.getResponseHeaders().add("Location", "/external");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        host.createContext("/external", exchange -> {leaked.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();});
        host.start();
        try {
            var service = new PluginResourceInstaller(new PluginInstallService(Path.of("/tmp"), "", null),
                    "http://127.0.0.1:" + host.getAddress().getPort());
            assertThrows(IOException.class, () -> service.install("/admin/attached/tmp/package.jar", "travel.jar", false,
                    Map.of("Cookie", "private", "Authorization", "Bearer private")));
            assertEquals(0, leaked.get());
        } finally { host.stop(0); }
    }
}
