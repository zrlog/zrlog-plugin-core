package com.zrlog.plugincore.server.web.controller;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.message.CapabilityCancelRequest;
import com.zrlog.plugin.message.CapabilityCancelResult;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugin.common.model.BlogRunTime;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.PluginRuntimeServices;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import com.zrlog.plugincore.server.runtime.plugin.transport.PluginCoreSocketServer;
import com.zrlog.plugincore.server.runtime.scheduler.AutomationRunStore;
import com.zrlog.plugincore.server.runtime.scheduler.AutomationStore;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.support.SchedulerFixturePlugin;
import com.zrlog.plugincore.server.web.PluginHttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Real HTTP route, production runtime assembly, managed child JVM and plugin SDK Socket transport. */
public class HostSchedulerPluginIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void publicViewWaitsForActualCompletionAndDoesNotBorrowHostCredentials() throws Exception {
        assertViewScenario("early");
    }

    @Test public void cancelledViewStopsEffectsAndDiscardsEarlyContent() throws Exception {
        assertViewScenario("cooperative");
    }

    @Test public void ignoredViewCancellationStopsItsOriginalProcessBeforeReturning() throws Exception {
        assertViewScenario("uncooperative");
    }

    private void assertViewScenario(String mode) throws Exception {
        Path root = temporary.newFolder().toPath();
        int[] ports = freePorts();
        int socketPort = ports[0], httpPort = ports[1];
        var config = new PluginConfig(RunType.BLOG, null, socketPort, root.toString(), new BlogRunTime());
        var services = PluginRuntimeServices.create(config,
                new PluginHostConnection("http://127.0.0.1:1", "integration-host-secret", "", "", true));
        var httpServer = new PluginHttpServer();
        var socketServer = new PluginCoreSocketServer(config, services.pluginBootstrap());
        Thread socketThread = new Thread(socketServer::listen, "integration-plugin-view");
        try (var database = InMemoryPluginCoreDatabase.open()) {
            try {
                assertTrue(httpServer.start(httpPort, false, services)); assertTrue(socketServer.create()); socketThread.start();
                services.pluginBootstrap().loadPlugin(pluginJar(root).toFile(), SchedulerFixturePlugin.ID, RuntimeDeadline.afterMillis(10000));
                await(() -> services.pluginSessions().isReadyByPluginId(SchedulerFixturePlugin.ID));
                var session = services.pluginSessions().getReadyLocalSessionByPluginId(SchedulerFixturePlugin.ID);
                long pid = services.pluginSessions().processId(session);
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort
                                + "/internal/runtime-scheduler/render-view?mode=" + mode + "&tag=a&tag=b&text=%E4%BD%A0%E5%A5%BD"))
                        .timeout(Duration.ofSeconds(20)).header("X-Plugin-Token", "integration-host-secret")
                        .header("X-ZrLog-Actor-Id", "42").header("X-ZrLog-Trigger-Id", "0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b")
                        .header("X-ZrLog-Request-Id", "view-run").header("X-ZrLog-Plugin-Name", SchedulerFixturePlugin.ID)
                        .header("X-ZrLog-View-Path", "/index").header("X-ZrLog-Budget-Millis", "10000")
                        .header("IsLogin", "true").header("LoginUserId", "42").header("Authorization", "Bearer browser-secret")
                        .header("Cookie", "private-session").header("Current-Locale", "zh_CN")
                        .header("X-ZrLog-View-User-Agent", "Static fixture")
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                var response = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
                await(() -> Files.exists(root.resolve("early-response")) || response.isDone());
                assertFalse("View returned before its handler completed: " + (response.isDone() ? response.get().body() : "pending"), response.isDone());
                var view = JsonParser.parseString(Files.readString(root.resolve("view-started.json"))).getAsJsonObject();
                assertEquals(-1, view.get("userId").getAsInt()); assertEquals("GET", view.get("httpMethod").getAsString());
                assertEquals("false", view.getAsJsonObject("header").get("IsLogin").getAsString());
                assertEquals("Static fixture", view.getAsJsonObject("header").get("User-Agent").getAsString());
                assertEquals(2, view.getAsJsonObject("param").getAsJsonArray("tag").size());
                assertEquals("你好", view.getAsJsonObject("param").getAsJsonArray("text").get(0).getAsString());
                for (String secret : List.of("integration-host-secret", "browser-secret", "private-session", "LoginUserId"))
                    assertFalse(view.toString().contains(secret));
                if ("early".equals(mode)) Files.createFile(root.resolve("release"));
                var completed = response.get(15, TimeUnit.SECONDS);
                assertEquals(completed.body(), 200, completed.statusCode());
                assertEquals("1", completed.headers().firstValue("X-ZrLog-Scheduler-Protocol").orElse(null));
                var receipt = JsonParser.parseString(completed.body()).getAsJsonObject();
                assertEquals("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b", receipt.get("triggerId").getAsString());
                assertEquals("view-run", receipt.get("requestId").getAsString());
                assertEquals("early".equals(mode), receipt.get("completed").getAsBoolean());
                assertEquals(!"early".equals(mode), receipt.get("outcomeUncertain").getAsBoolean());
                if ("early".equals(mode)) {
                    assertEquals("<aside>公开插件内容</aside>", receipt.get("content").getAsString());
                    assertTrue(Files.exists(root.resolve("completed")));
                } else { assertFalse(receipt.has("content")); }
                if ("early".equals(mode)) assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                else assertCancellationSettled(root, pid, "uncooperative".equals(mode), completed.body());
                String progress = Files.readString(root.resolve("progress"));
                Thread.sleep(150); assertEquals(progress, Files.readString(root.resolve("progress")));
                var logs = new com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore(new WebsiteRuntimeKvStore()).list();
                assertTrue(logs.stream().anyMatch(log -> "host_view".equals(log.getSource())
                        && "0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b".equals(log.getTraceId()) && "view-run".equals(log.getRequestId())
                        && ("early".equals(mode) ? "success" : "uncertain").equals(log.getStatus())));
            } finally {
                Files.writeString(root.resolve("release"), "cleanup");
                services.shutdown(); socketServer.destroy("view integration complete"); socketThread.join(5000);
                httpServer.stop("view integration complete"); PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
            }
        }
        assertFalse(socketThread.isAlive());
    }

    @Test
    public void httpTickWaitsForPluginBusinessAndPersistsOnlyThisRunWithoutReplay() throws Exception {
        Path root = temporary.newFolder().toPath();
        int[] ports = freePorts();
        int socketPort = ports[0], httpPort = ports[1];
        var config = new PluginConfig(RunType.BLOG, null, socketPort, root.toString(), new BlogRunTime());
        var services = PluginRuntimeServices.create(config,
                new PluginHostConnection("http://127.0.0.1:1", "integration-host-secret", "", "", true));
        var httpServer = new PluginHttpServer();
        var socketServer = new PluginCoreSocketServer(config, services.pluginBootstrap());
        Thread socketThread = new Thread(socketServer::listen, "integration-plugin-socket");
        try (var database = InMemoryPluginCoreDatabase.open()) {
            try {
                assertTrue(httpServer.start(httpPort, false, services));
                assertTrue(socketServer.create());
                socketThread.start();
                services.pluginBootstrap().loadPlugin(pluginJar(root).toFile(), SchedulerFixturePlugin.ID,
                        RuntimeDeadline.afterMillis(10000));
                await(() -> services.pluginSessions().isReadyByPluginId(SchedulerFixturePlugin.ID));

                var task = new PluginAutomation();
                task.setId("fixture-schedule"); task.setPluginId(SchedulerFixturePlugin.ID);
                task.setCapabilityKey(SchedulerFixturePlugin.CAPABILITY); task.setName("Local fixture task");
                task.setCron("*/5 * * * *"); task.setTimezone("UTC"); task.setEnabled(true);
                task.setNextRunAt(System.currentTimeMillis() - 1000);
                task.setPayload(Map.of("directory", root.toString(), "mode", "early"));
                var tasks = new AutomationStore(new WebsiteRuntimeKvStore());
                var runs = new AutomationRunStore(new WebsiteRuntimeKvStore());
                tasks.saveAll(List.of(task));

                var client = HttpClient.newHttpClient();
                var request = tickRequest(httpPort, "assistant-parent-run");
                var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                await(() -> Files.exists(root.resolve("early-response")));
                assertFalse("HTTP must remain pending while plugin business is running", response.isDone());
                var running = runs.list().get(0);
                assertEquals("running", running.getStatus());
                assertEquals("42", running.getActorId());
                assertEquals("assistant-parent-run", running.getTriggerId());
                assertEquals(running.getId(), tasks.list().get(0).getLeaseOwner());
                var sent = JsonParser.parseString(Files.readString(root.resolve("started.json"))).getAsJsonObject();
                assertEquals(running.getId(), sent.get("requestId").getAsString());
                assertTrue(sent.get("requireCompletion").getAsBoolean());
                assertEquals("assistant-parent-run", sent.get("traceId").getAsString());
                assertFalse(Files.exists(root.resolve("completed")));

                Files.createFile(root.resolve("release"));
                var completed = response.get(10, TimeUnit.SECONDS);
                assertEquals(completed.body(), 200, completed.statusCode());
                var result = JsonParser.parseString(completed.body()).getAsJsonObject();
                assertEquals(1, result.get("executedCount").getAsInt());
                assertEquals(1, result.getAsJsonArray("children").size());
                var child = result.getAsJsonArray("children").get(0).getAsJsonObject();
                assertEquals("success", child.get("status").getAsString());
                assertEquals(running.getId(), child.get("runId").getAsString());
                assertEquals(running.getId(), Files.readString(root.resolve("completed")));
                assertEquals("success", runs.find(running.getId()).orElseThrow().getStatus());
                assertNotNull(runs.find(running.getId()).orElseThrow().getFinishedAt());
                assertNull(tasks.list().get(0).getLeaseOwner());
                assertFalse(completed.body().contains(root.toString()));

                var repeat = client.send(tickRequest(httpPort, "assistant-next-run"), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, repeat.statusCode());
                assertEquals(0, JsonParser.parseString(repeat.body()).getAsJsonObject().get("executedCount").getAsInt());
                assertEquals(List.of(running.getId()), Files.readAllLines(root.resolve("invocations")));
                assertEquals(1, runs.list().size());
            } finally {
                Files.writeString(root.resolve("release"), "release for cleanup");
                services.shutdown();
                socketServer.destroy("integration complete");
                socketThread.join(5000);
                httpServer.stop("integration complete");
                PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
            }
        }
        assertFalse(socketThread.isAlive());
    }

    @Test
    public void cooperativeHandlerStopsEffectsBeforeHttpReturns() throws Exception {
        assertTimeoutScenario(false);
    }

    @Test
    public void realSocketCancellationAcknowledgesHandlerCleanupWithoutStoppingProcess() throws Exception {
        Path root = temporary.newFolder().toPath();
        var config = new PluginConfig(RunType.BLOG, null, freePorts()[0], root.toString(), new BlogRunTime());
        var services = PluginRuntimeServices.create(config,
                new PluginHostConnection("http://127.0.0.1:1", "integration-host-secret", "", "", true));
        var socketServer = new PluginCoreSocketServer(config, services.pluginBootstrap());
        Thread socketThread = new Thread(socketServer::listen, "integration-sdk-cancellation");
        try (var database = InMemoryPluginCoreDatabase.open()) {
            try {
                PluginRuntimeBridge.install(services);
                assertTrue(socketServer.create()); socketThread.start();
                services.pluginBootstrap().loadPlugin(pluginJar(root).toFile(), SchedulerFixturePlugin.ID, RuntimeDeadline.afterMillis(10000));
                await(() -> services.pluginSessions().isReadyByPluginId(SchedulerFixturePlugin.ID));
                var session = services.pluginSessions().getReadyLocalSessionByPluginId(SchedulerFixturePlugin.ID);
                long pid = services.pluginSessions().processId(session);
                var request = new CapabilityInvokeRequest();
                request.setPluginId(SchedulerFixturePlugin.ID); request.setCapabilityKey(SchedulerFixturePlugin.CAPABILITY);
                request.setRequestId("direct-cancellation"); request.setRequireCompletion(true);
                request.setDeadlineEpochMillis(System.currentTimeMillis() + 30000);
                request.setPayload(Map.of("directory", root.toString(), "mode", "cooperative"));
                int invocationId = IdUtil.getInt();
                session.sendJsonRequest(request, ActionType.CAPABILITY_INVOKE.name(), invocationId, Duration.ofSeconds(10));
                await(() -> Files.exists(root.resolve("started.json")));
                var cancel = new CapabilityCancelRequest();
                cancel.setInvocationMessageId(invocationId); cancel.setRequestId(request.getRequestId());
                int cancellationId = IdUtil.getInt();
                session.sendJsonRequest(cancel, ActionType.CAPABILITY_CANCEL.name(), cancellationId, Duration.ofSeconds(10));
                try (var lease = session.getResponseLeaseByMsgId(cancellationId, Duration.ofSeconds(10))) {
                    assertNotNull("Plugin did not acknowledge cancellation", lease);
                    var receipt = new Gson().fromJson(lease.getPacket().getDataStr(), CapabilityCancelResult.class);
                    assertEquals(request.getRequestId(), receipt.getRequestId());
                    assertTrue(receipt.isExecutionCompleted());
                    assertTrue(Files.exists(root.resolve("cancelled")));
                    assertTrue(Files.exists(root.resolve("cleanup-completed")));
                }
                try (var lease = session.getResponseLeaseByMsgId(invocationId, Duration.ofSeconds(10))) {
                    assertNotNull(lease);
                    var result = new Gson().fromJson(lease.getPacket().getDataStr(), CapabilityInvokeResult.class);
                    assertTrue(result.isExecutionCompleted()); assertTrue(result.isOutcomeUncertain()); assertFalse(result.isSuccess());
                }
                assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                String progress = Files.readString(root.resolve("progress"));
                Thread.sleep(150); assertEquals(progress, Files.readString(root.resolve("progress")));
            } finally {
                Files.writeString(root.resolve("release"), "cleanup");
                services.shutdown(); socketServer.destroy("SDK cancellation complete"); socketThread.join(5000);
                PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
            }
        }
        assertFalse(socketThread.isAlive());
    }

    @Test
    public void ignoredCancellationStopsProcessAndMarksOtherActiveRunUncertain() throws Exception {
        assertTimeoutScenario(true);
    }

    @Test
    public void hostRefreshWaitsForEventHandlerCompletion() throws Exception { assertCacheRefresh("early"); }

    @Test
    public void hostRefreshCancellationStopsEffectsBeforeReturning() throws Exception { assertCacheRefresh("cooperative"); }

    @Test
    public void hostRefreshStopsAnUncooperativeHandlerBeforeReturning() throws Exception { assertCacheRefresh("uncooperative"); }

    @Test
    public void hostRefreshStopsPluginThatNeverDispatchesCancellation() throws Exception {
        assertCacheRefresh("uncooperative", true);
    }

    private void assertCacheRefresh(String mode) throws Exception {
        assertCacheRefresh(mode, false);
    }

    private void assertCacheRefresh(String mode, boolean ignoreCancellation) throws Exception {
        Path root = temporary.newFolder().toPath();
        if (ignoreCancellation) Files.createFile(root.resolve("ignore-cancellation"));
        int[] ports = freePorts();
        int socketPort = ports[0], httpPort = ports[1];
        var config = new PluginConfig(RunType.BLOG, null, socketPort, root.toString(), new BlogRunTime());
        var services = PluginRuntimeServices.create(config,
                new PluginHostConnection("http://127.0.0.1:1", "integration-host-secret", "", "", true));
        var httpServer = new PluginHttpServer();
        var socketServer = new PluginCoreSocketServer(config, services.pluginBootstrap());
        Thread socketThread = new Thread(socketServer::listen, "integration-cache-refresh");
        try (var database = InMemoryPluginCoreDatabase.open()) {
            try {
                assertTrue(httpServer.start(httpPort, false, services)); assertTrue(socketServer.create()); socketThread.start();
                services.pluginBootstrap().loadPlugin(pluginJar(root).toFile(), SchedulerFixturePlugin.ID, RuntimeDeadline.afterMillis(10000));
                await(() -> services.pluginSessions().isReadyByPluginId(SchedulerFixturePlugin.ID));
                var session = services.pluginSessions().getReadyLocalSessionByPluginId(SchedulerFixturePlugin.ID);
                long pid = services.pluginSessions().processId(session);
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/internal/runtime-scheduler/refresh-cache"))
                        .timeout(Duration.ofSeconds(25)).header("X-Plugin-Token", "integration-host-secret")
                        .header("X-ZrLog-Actor-Id", "42").header("X-ZrLog-Trigger-Id", "0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b")
                        .header("X-ZrLog-Cache-Version", mode).header("X-ZrLog-Budget-Millis", "20000")
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                var response = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString());
                await(() -> Files.exists(root.resolve("started.json")));
                var invocation = JsonParser.parseString(Files.readString(root.resolve("started.json"))).getAsJsonObject();
                assertTrue(invocation.get("requireCompletion").getAsBoolean());
                assertEquals("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b", invocation.get("requestId").getAsString());
                assertEquals("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b", invocation.get("traceId").getAsString());
                assertFalse(response.isDone());
                if ("early".equals(mode)) {
                    await(() -> Files.exists(root.resolve("early-response")));
                    assertFalse(response.isDone());
                    Files.createFile(root.resolve("release"));
                }
                var completed = response.get(20, TimeUnit.SECONDS);
                assertEquals(completed.body(), 200, completed.statusCode());
                var receipt = JsonParser.parseString(completed.body()).getAsJsonObject();
                assertEquals("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b", receipt.get("triggerId").getAsString());
                assertEquals(mode, receipt.get("cacheVersion").getAsString());
                assertEquals("early".equals(mode), receipt.get("completed").getAsBoolean());
                assertEquals(!"early".equals(mode), receipt.get("outcomeUncertain").getAsBoolean());
                if ("early".equals(mode)) assertEquals("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b", Files.readString(root.resolve("completed")));
                else assertCancellationSettled(root, pid, "uncooperative".equals(mode), completed.body());
                if (ignoreCancellation) assertFalse(Files.exists(root.resolve("cancelled")));
                if ("early".equals(mode)) assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                String progress = Files.readString(root.resolve("progress"));
                Thread.sleep(150);
                assertEquals(progress, Files.readString(root.resolve("progress")));
                var logs = new com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore(new WebsiteRuntimeKvStore()).list();
                assertTrue(logs.stream().anyMatch(log -> "__system__".equals(log.getPluginId())
                        && "0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b".equals(log.getTraceId())
                        && ("early".equals(mode) ? "success" : "uncertain").equals(log.getStatus())));
            } finally {
                Files.writeString(root.resolve("release"), "cleanup");
                services.shutdown(); socketServer.destroy("cache integration complete"); socketThread.join(5000);
                httpServer.stop("cache integration complete"); PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
            }
        }
        assertFalse(socketThread.isAlive());
    }

    private void assertTimeoutScenario(boolean uncooperative) throws Exception {
        Path root = temporary.newFolder().toPath();
        Path first = Files.createDirectory(root.resolve("first"));
        Path other = Files.createDirectory(root.resolve("other"));
        int[] ports = freePorts();
        int socketPort = ports[0], httpPort = ports[1];
        var config = new PluginConfig(RunType.BLOG, null, socketPort, root.toString(), new BlogRunTime());
        var services = PluginRuntimeServices.create(config,
                new PluginHostConnection("http://127.0.0.1:1", "integration-host-secret", "", "", true));
        var httpServer = new PluginHttpServer();
        var socketServer = new PluginCoreSocketServer(config, services.pluginBootstrap());
        Thread socketThread = new Thread(socketServer::listen, "integration-plugin-cancellation");
        try (var database = InMemoryPluginCoreDatabase.open()) {
            try {
                assertTrue(httpServer.start(httpPort, false, services)); assertTrue(socketServer.create()); socketThread.start();
                services.pluginBootstrap().loadPlugin(pluginJar(root).toFile(), SchedulerFixturePlugin.ID, RuntimeDeadline.afterMillis(10000));
                await(() -> services.pluginSessions().isReadyByPluginId(SchedulerFixturePlugin.ID));
                var original = services.pluginSessions().getReadyLocalSessionByPluginId(SchedulerFixturePlugin.ID);
                long pid = services.pluginSessions().processId(original);
                var tasks = new AutomationStore(new WebsiteRuntimeKvStore());
                var runs = new AutomationRunStore(new WebsiteRuntimeKvStore());
                PluginAutomation task = fixtureTask("first", SchedulerFixturePlugin.CAPABILITY, first,
                        uncooperative ? "uncooperative" : "cooperative");
                tasks.saveAll(uncooperative ? List.of(task, fixtureTask("other", SchedulerFixturePlugin.CAPABILITY + ".other", other, "uncooperative")) : List.of(task));
                var client = HttpClient.newHttpClient();
                var response = client.sendAsync(tickRequest(httpPort, "timeout-parent", uncooperative ? 50000 : 20000), HttpResponse.BodyHandlers.ofString());
                await(() -> Files.exists(first.resolve("started.json")));
                if (uncooperative) await(() -> Files.exists(other.resolve("started.json")));
                assertFalse(response.isDone());
                var completed = response.get(20, TimeUnit.SECONDS);
                assertEquals(completed.body(), 200, completed.statusCode());
                var result = JsonParser.parseString(completed.body()).getAsJsonObject();
                assertEquals(0, result.get("executedCount").getAsInt());
                assertEquals(uncooperative ? 2 : 1, result.getAsJsonArray("children").size());
                for (var child : result.getAsJsonArray("children")) assertEquals("uncertain", child.getAsJsonObject().get("status").getAsString());
                for (var run : runs.list()) assertEquals("uncertain", run.getStatus());
                for (var saved : tasks.list()) assertEquals(Boolean.FALSE, saved.getEnabled());
                assertCancellationSettled(first, pid, uncooperative, completed.body());
                String firstProgress = Files.readString(first.resolve("progress"));
                String otherProgress = uncooperative ? Files.readString(other.resolve("progress")) : null;
                Thread.sleep(200);
                assertEquals(firstProgress, Files.readString(first.resolve("progress")));
                if (uncooperative) assertEquals(otherProgress, Files.readString(other.resolve("progress")));
                var repeat = client.send(tickRequest(httpPort, "after-timeout", 50000), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, repeat.statusCode());
                assertEquals(0, JsonParser.parseString(repeat.body()).getAsJsonObject().getAsJsonArray("children").size());
                assertEquals(1, Files.readAllLines(first.resolve("invocations")).size());
                if (uncooperative) assertEquals(1, Files.readAllLines(other.resolve("invocations")).size());
            } finally {
                Files.writeString(first.resolve("release"), "cleanup"); Files.writeString(other.resolve("release"), "cleanup");
                services.shutdown(); socketServer.destroy("integration complete"); socketThread.join(5000);
                httpServer.stop("integration complete"); PluginRuntimeBridge.install(PluginRuntimeServices.unconfigured());
            }
        }
        assertFalse(socketThread.isAlive());
    }

    private static PluginAutomation fixtureTask(String id, String capability, Path directory, String mode) {
        var task = new PluginAutomation(); task.setId(id); task.setPluginId(SchedulerFixturePlugin.ID);
        task.setCapabilityKey(capability); task.setName(id); task.setCron("*/5 * * * *"); task.setTimezone("UTC");
        task.setEnabled(true); task.setNextRunAt(System.currentTimeMillis() - 1000);
        task.setPayload(Map.of("directory", directory.toString(), "mode", mode)); return task;
    }

    private Path pluginJar(Path root) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, SchedulerFixturePlugin.class.getName());
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, Arrays.stream(classPath.split(File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toUri().toString()).collect(Collectors.joining(" ")));
        Path jar = root.resolve(SchedulerFixturePlugin.ID + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("plugin.properties"));
            out.write(("version=1\nname=Scheduler fixture\nshortName=" + SchedulerFixturePlugin.ID + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("fixture.properties"));
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("directory", root.toString());
            properties.store(out, "Local integration fixture");
            out.closeEntry();
        }
        return jar;
    }

    private static HttpRequest tickRequest(int port, String trigger) { return tickRequest(port, trigger, 20000); }

    private static HttpRequest tickRequest(int port, String trigger, long budget) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/runtime-scheduler/tick"))
                .timeout(Duration.ofSeconds(30)).header("X-Plugin-Token", "integration-host-secret")
                .header("X-ZrLog-Actor-Id", "42").header("X-ZrLog-Trigger-Id", trigger)
                .header("X-ZrLog-Budget-Millis", String.valueOf(budget)).POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private static int[] freePorts() throws Exception {
        // Hold both reservations together so the OS cannot return the same released port twice.
        try (ServerSocket socket = new ServerSocket(0); ServerSocket http = new ServerSocket(0)) {
            return new int[]{socket.getLocalPort(), http.getLocalPort()};
        }
    }

    private static void assertCancellationSettled(Path directory, long pid, boolean requireProcessStop, String receipt) throws Exception {
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        String diagnostic = "receipt=" + receipt + ", processAlive=" + alive + ", fixtureFailure="
                + (Files.exists(directory.resolve("fixture-failure")) ? Files.readString(directory.resolve("fixture-failure")) : "none");
        if (requireProcessStop) assertFalse(diagnostic, alive);
        // Even a cooperative child may miss the production grace period under instrumentation.
        // The HTTP contract permits returning only after cleanup or confirmed process death.
        if (alive) {
            assertTrue(diagnostic, Files.exists(directory.resolve("cancelled")));
            assertTrue(diagnostic, Files.exists(directory.resolve("cleanup-completed")));
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for the real plugin");
            Thread.sleep(10);
        }
    }
}
