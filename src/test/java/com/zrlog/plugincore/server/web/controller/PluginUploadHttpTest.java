package com.zrlog.plugincore.server.web.controller;

import com.google.gson.JsonParser;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.web.PluginHttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.junit.Assert.*;

public class PluginUploadHttpTest {
    @Test
    public void decodesMultipartLargerThanFourMiBBeforeCheckingRuntimeAvailability() throws Exception {
        PluginRuntimeServices services = PluginRuntimeServices.unconfigured();
        // A stopped runtime lets the request exercise real parsing/validation without launching the fixture JAR.
        services.pluginBootstrap().shutdown();
        PluginHttpServer server = new PluginHttpServer();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            assertTrue(server.start(port, false, services));
            byte[] archive = largeJar();
            assertTrue(archive.length > 4 * 1024 * 1024);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--upload-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"travel.jar\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(archive);
            body.write("\r\n--upload-test--\r\n".getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/upload?fileName=travel.jar"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept-Language", "en-US")
                    .header("Content-Type", "multipart/form-data; boundary=upload-test")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(response.body(), 200, response.statusCode());
            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(1, json.get("error").getAsInt());
            assertTrue(response.body(), json.get("message").getAsString().contains("starting or shutting down"));
        } finally {
            server.stop("upload test completed");
            services.shutdown();
            PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
        }
    }

    private byte[] largeJar() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Main");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] content = new byte[5 * 1024 * 1024];
        CRC32 crc = new CRC32();
        crc.update(content);
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            JarEntry entry = new JarEntry("example/Main.class");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCrc(crc.getValue());
            jar.putNextEntry(entry);
            jar.write(content);
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
