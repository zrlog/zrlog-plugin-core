package com.zrlog.plugincore.server.web.controller;

import com.google.gson.JsonParser;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import com.zrlog.plugincore.server.runtime.scheduler.AutomationStore;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.web.PluginHttpServer;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.Assert.*;

public class HostSchedulerHttpTest {
    @Test
    public void machineTickRejectsBrowserIdentityAndReturnsOnlyNestedCurrentResults() throws Exception {
        PluginRuntimeServices services = PluginRuntimeServices.create(PluginConfig.unconfigured(),
                new PluginHostConnection("http://127.0.0.1:1", "fixture-host-secret", "", ""));
        services.pluginBootstrap().shutdown();
        PluginHttpServer server = new PluginHttpServer();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            PluginAutomation task = new PluginAutomation();
            task.setId("plugin-child"); task.setPluginId("cold-plugin"); task.setName("Check feeds");
            task.setCapabilityKey("feed.check"); task.setCron("*/5 * * * *"); task.setEnabled(true);
            task.setTimezone("UTC"); task.setNextRunAt(System.currentTimeMillis() - 1000);
            new AutomationStore(new WebsiteRuntimeKvStore()).saveAll(List.of(task));
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            assertTrue(server.start(port, false, services));
            String url = "http://127.0.0.1:" + port + "/internal/runtime-scheduler/tick";
            HttpClient client = HttpClient.newHttpClient();
            var browser = client.send(request(url).header("IsLogin", "true").header("LoginUserId", "1")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, browser.statusCode());
            var get = client.send(authenticated(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
            var invalid = client.send(authenticated(url).header("X-ZrLog-Budget-Millis", "999999")
                    .header("X-ZrLog-Trigger-Id", "0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
            var result = client.send(authenticated(url).header("X-ZrLog-Budget-Millis", "645000")
                    .header("X-ZrLog-Trigger-Id", "0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(result.body(), 200, result.statusCode());
            assertEquals("1", result.headers().firstValue("X-ZrLog-Scheduler-Protocol").orElse(null));
            assertEquals("no-store", result.headers().firstValue("Cache-Control").orElse(null));
            var json = JsonParser.parseString(result.body()).getAsJsonObject();
            assertEquals("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b", json.get("triggerId").getAsString());
            assertEquals(1, json.getAsJsonArray("children").size());
            var child = json.getAsJsonArray("children").get(0).getAsJsonObject();
            assertEquals("plugin-child", child.get("automationId").getAsString());
            assertEquals("error", child.get("status").getAsString());
            assertTrue(child.has("runId"));
            assertEquals("Capability not found", child.get("errorMessage").getAsString());
            assertFalse(result.body().contains("fixture-host-secret"));
            assertFalse(child.has("payload"));
            var oldAggregation = client.send(authenticated(url.replace("/tick", "/automations"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, oldAggregation.statusCode());

            String refreshUrl = url.replace("/tick", "/refresh-cache");
            var unauthenticatedRefresh = client.send(request(refreshUrl).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthenticatedRefresh.statusCode());
            var readRefresh = client.send(authenticated(refreshUrl).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, readRefresh.statusCode());
            var missingVersion = client.send(authenticated(refreshUrl).header("X-ZrLog-Trigger-Id", "publication-run")
                    .header("X-ZrLog-Budget-Millis", "10000").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, missingVersion.statusCode());
            var refreshed = client.send(authenticated(refreshUrl).header("X-ZrLog-Trigger-Id", "publication-run")
                    .header("X-ZrLog-Cache-Version", "cache-1").header("X-ZrLog-Budget-Millis", "10000")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(refreshed.body(), 200, refreshed.statusCode());
            assertEquals("1", refreshed.headers().firstValue("X-ZrLog-Scheduler-Protocol").orElse(null));
            var receipt = JsonParser.parseString(refreshed.body()).getAsJsonObject();
            assertEquals("publication-run", receipt.get("triggerId").getAsString());
            assertEquals("cache-1", receipt.get("cacheVersion").getAsString());
            assertTrue(receipt.get("completed").getAsBoolean());
            assertEquals(0, receipt.get("successCount").getAsInt());

            String viewUrl = url.replace("/tick", "/render-view");
            assertEquals(401, client.send(request(viewUrl).header("IsLogin", "true")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, client.send(authenticated(viewUrl).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            for (String path : List.of("/../admin", "/%2e%2e/admin", "//other/index", "https://other/index", "/index?private=1", "/index%00")) {
                var invalidView = client.send(authenticated(viewUrl).header("X-ZrLog-Trigger-Id", "publication-run")
                        .header("X-ZrLog-Request-Id", "view-run").header("X-ZrLog-Plugin-Name", "fixture")
                        .header("X-ZrLog-View-Path", path).header("X-ZrLog-Budget-Millis", "10000")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(path, 400, invalidView.statusCode());
            }
        } finally {
            server.stop("host scheduler test completed"); services.shutdown();
            PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
        }
    }

    private HttpRequest.Builder request(String url) { return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)); }
    private HttpRequest.Builder authenticated(String url) {
        return request(url).header("X-Plugin-Token", "fixture-host-secret").header("X-ZrLog-Actor-Id", "1");
    }
}
