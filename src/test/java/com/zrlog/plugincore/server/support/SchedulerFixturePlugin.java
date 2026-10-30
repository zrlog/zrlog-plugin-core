package com.zrlog.plugincore.server.support;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.api.IPluginAction;
import com.zrlog.plugin.api.IPluginService;
import com.zrlog.plugin.api.ScheduledCapability;
import com.zrlog.plugin.api.Service;
import com.zrlog.plugin.client.NioClient;
import com.zrlog.plugin.data.codec.HttpRequestInfo;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Real SDK client in a child JVM; test payloads restrict all effects to their temporary directory. */
public final class SchedulerFixturePlugin implements IPluginAction {
    public static final String ID = "scheduler-fixture";
    public static final String CAPABILITY = "fixture.write";

    public static void main(String[] args) throws Exception {
        new NioClient().connectServer(args, Collections.singletonList(FixtureView.class), SchedulerFixturePlugin.class,
                java.util.List.of(FixtureService.class, OtherFixtureService.class, CacheFixtureService.class));
    }

    public static final class FixtureView {
        private final IOSession session;
        private final MsgPacket packet;
        private final HttpRequestInfo request;
        public FixtureView(IOSession session, MsgPacket packet, HttpRequestInfo request) {
            this.session = session; this.packet = packet; this.request = request;
        }
        public void index() throws Exception {
            java.util.Properties fixture = new java.util.Properties();
            try (var input = SchedulerFixturePlugin.class.getResourceAsStream("/fixture.properties")) { fixture.load(input); }
            Path directory = Path.of(fixture.getProperty("directory"));
            String mode = request.getParam().get("mode")[0];
            Files.writeString(directory.resolve("view-started.tmp"), new Gson().toJson(request));
            Files.move(directory.resolve("view-started.tmp"), directory.resolve("view-started.json"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            session.sendMsg(com.zrlog.plugin.data.codec.ContentType.HTML, "<aside>公开插件内容</aside>",
                    packet.getMethodStr(), packet.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS, null);
            Files.writeString(directory.resolve("early-response"), "content sent before handler cleanup");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int writes = 0;
            while (!Files.exists(directory.resolve("release"))) {
                if (System.nanoTime() >= end) throw new IllegalStateException("Fixture was not released");
                Files.writeString(directory.resolve("progress"), String.valueOf(++writes));
                try { Thread.sleep(20); }
                catch (InterruptedException interrupted) {
                    Files.writeString(directory.resolve("cancelled"), "cancellation received");
                    if (!"uncooperative".equals(mode)) {
                        Files.writeString(directory.resolve("cleanup-completed"), "view cleanup finished");
                        throw interrupted;
                    }
                }
            }
            Files.writeString(directory.resolve("completed"), "view returned");
        }
    }

    @Service(CAPABILITY)
    @ScheduledCapability(key = CAPABILITY, label = "Write a local fixture result", timeoutSeconds = 5)
    public static final class FixtureService implements IPluginService {
        @Override public void handle(IOSession session, MsgPacket packet) { execute(session, packet); }
    }

    @Service(CAPABILITY + ".other")
    @ScheduledCapability(key = CAPABILITY + ".other", label = "Concurrent local fixture", timeoutSeconds = 30)
    public static final class OtherFixtureService implements IPluginService {
        @Override public void handle(IOSession session, MsgPacket packet) { execute(session, packet); }
    }

    @Service("fixture.cache")
    @com.zrlog.plugin.api.Capability(key = "fixture.cache", type = "event_handler", exposure = {"runtime_event"},
            channel = "system.refreshCache", timeoutSeconds = 3)
    public static final class CacheFixtureService implements IPluginService {
        @Override public void handle(IOSession session, MsgPacket packet) { execute(session, packet); }
    }

    private static void execute(IOSession session, MsgPacket packet) {
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        boolean replied = false;
        try {
            CapabilityInvokeRequest request = new Gson().fromJson(packet.getDataStr(), CapabilityInvokeRequest.class);
            Path directory;
            String mode;
            if ("fixture.cache".equals(request.getCapabilityKey())) {
                java.util.Properties fixture = new java.util.Properties();
                try (var input = SchedulerFixturePlugin.class.getResourceAsStream("/fixture.properties")) { fixture.load(input); }
                directory = Path.of(fixture.getProperty("directory"));
                mode = String.valueOf(((java.util.Map<?, ?>) request.getPayload().get("payload")).get("cacheVersion"));
            } else {
                directory = Path.of((String) request.getPayload().get("directory"));
                mode = String.valueOf(request.getPayload().get("mode"));
            }
            Files.writeString(directory.resolve("invocations"), request.getRequestId() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(directory.resolve("started.tmp"), new Gson().toJson(request));
            Files.move(directory.resolve("started.tmp"), directory.resolve("started.json"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            if ("early".equals(mode)) {
                result.setSuccess(true);
                session.sendJsonMsg(result, packet.getMethodStr(), packet.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
                replied = true;
                Files.writeString(directory.resolve("early-response"), "sent before handler returns");
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            int writes = 0;
            while (!Files.exists(directory.resolve("release"))) {
                if (System.nanoTime() >= end) throw new IllegalStateException("Fixture was not released");
                Files.writeString(directory.resolve("progress"), String.valueOf(++writes));
                try { Thread.sleep(20); }
                catch (InterruptedException interrupted) {
                    Files.writeString(directory.resolve("cancelled"), "cancellation received");
                    if (!"uncooperative".equals(mode)) {
                        Files.writeString(directory.resolve("cleanup-completed"), "handler cleanup finished");
                        throw interrupted;
                    }
                }
            }
            Files.writeString(directory.resolve("completed"), request.getRequestId());
            result.setSuccess(true);
        } catch (Exception error) {
            result.setSuccess(false);
            result.setErrorMessage(error.getMessage());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        if (!replied) session.sendJsonMsg(result, packet.getMethodStr(), packet.getMsgId(),
                result.isSuccess() ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
    }

    public void start(IOSession session, MsgPacket packet) { }
    public void stop(IOSession session, MsgPacket packet) { }
    public void install(IOSession session, MsgPacket packet, HttpRequestInfo request) { }
    public void uninstall(IOSession session, MsgPacket packet) { }
}
