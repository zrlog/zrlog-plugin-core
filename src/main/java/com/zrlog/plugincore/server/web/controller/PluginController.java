package com.zrlog.plugincore.server.web.controller;


import com.google.gson.Gson;
import com.hibegin.http.server.web.Controller;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.plugin.transport.ServiceMsgPacketHandler;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.invocation.ServiceInvocationLogs;
import com.zrlog.plugincore.server.runtime.service.ServiceProviderResolver;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.util.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginController extends Controller {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginController.class);

    /**
     * 得到插件列表
     */
    public void index() throws IOException {
        Document document = Jsoup.parse(Objects.requireNonNull(PluginController.class.getResourceAsStream("/static/index.html")), "UTF-8", "");
        document.title("插件管理");
        document.body().removeAttr("class");
        PluginApiModels.PluginListResponse pluginData = new PluginApiController(request, response).plugins();
        if (Boolean.TRUE.equals(pluginData.getDark())) {
            document.body().addClass("dark");
        } else {
            document.body().addClass("light");
        }
        String jsonStr = new Gson().toJson(pluginData);
        Element pluginInfo = document.getElementById("pluginInfo");
        if (Objects.nonNull(pluginInfo)) {
            pluginInfo.text(jsonStr);
        }
        response.renderHtmlStr(document.html());
    }


    public void download() {
        String downloadUrl = getRequest().getParaToStr("downloadUrl");
        if (Objects.isNull(downloadUrl)) {
            return;
        }
        String fileName = downloadUrl.substring(downloadUrl.lastIndexOf("/") + 1);
        String pluginShortName = PluginFiles.getPluginShortName(new File(fileName));
        try {
            File path = new File(PluginRuntimeBridge.pluginConfig().getPluginBasePath());
            File file = new File(path + "/" + fileName);
            if (file.exists()) {
                if (!pluginBootstrap().startPluginFileForMetadata(file)) {
                    throw new RuntimeException("插件已经存在，但启动插件获取元数据超时");
                }
                response.redirect("/admin/plugins/downloadResult?message=插件已经存在，已启动插件" +
                        "&pluginName=" + pluginShortName);
                return;
            }
            pluginBootstrap().downloadAndStartPlugin(PluginFiles.getPluginFile(pluginShortName).getName());
            response.redirect("/admin/plugins/downloadResult?message=下载插件成功" +
                    "&pluginName=" + pluginShortName);
        } catch (Exception e) {
            response.redirect("/admin/plugins/downloadResult?message=" + e.getMessage() +
                    "&pluginName=" + pluginShortName);
            LOGGER.log(Level.FINER, "download error ", e);
        }
    }


    public void service() throws InterruptedException {
        String name = getRequest().getParaToStr("name");
        if (StringUtils.isEmpty(name)) {
            LOGGER.warning("Missing service name");
            getResponse().renderCode(400);
            return;
        }
        IOSession session = ServiceMsgPacketHandler.getServiceSessionWithRetry(name, 60);
        if (Objects.isNull(session)) {
            getResponse().renderCode(503);
            return;
        }
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            PluginRuntimeStateService stateService = PluginRuntimeStates.newStateService(session);
            String pluginId = session.getPlugin().getId();
            String pluginName = PluginSessions.nameOrShortName(session.getPlugin());
            KvRepository runtimeKvStore = kvStore();
            PluginCapability serviceCapability = serviceCapability(name, pluginId,
                    new CapabilityStore(runtimeKvStore).listByType("service"));
            String capabilityKey = serviceCapabilityKey(name, serviceCapability);
            String requestId = UUID.randomUUID().toString();
            String errorMessage = null;
            long startedAtMs = System.currentTimeMillis();
            stateService.markInvocationStart(pluginId, pluginName);
            try {
                int msgId = session.requestService(name, request.decodeParamMap());
                try (ResponseLease responseLease = session.getResponseLeaseByMsgId(msgId,
                        PluginExecutionTimeouts.executionTimeout(serviceCapability == null ? null : serviceCapability.getTimeoutSeconds()))) {
                    if (responseLease == null) {
                        errorMessage = "service " + name + " not response";
                        getResponse().renderCode(500);
                        return;
                    }
                    MsgPacket responseMsgPacket = responseLease.getPacket();
                    if (responseMsgPacket.getStatus() == MsgPacketStatus.RESPONSE_ERROR) {
                        errorMessage = "service " + name + " response error";
                    }
                    getResponse().addHeader("Content-Type", "application/json");
                    ByteArrayInputStream bin = new ByteArrayInputStream(responseMsgPacket.getData().array());
                    getResponse().write(bin);
                }
            } finally {
                stateService.markInvocationEnd(pluginId, pluginName, errorMessage);
                ServiceInvocationLogs.append(runtimeKvStore, pluginId, capabilityKey, requestId, null,
                        startedAtMs, System.currentTimeMillis(), errorMessage);
            }
        }
    }

    private PluginCapability serviceCapability(String serviceName, String pluginId, List<PluginCapability> serviceCapabilities) {
        return new ServiceProviderResolver()
                .providersFor(serviceName, serviceCapabilities)
                .stream()
                .filter(item -> Objects.equals(pluginId, item.getPluginId()))
                .findFirst()
                .orElse(null);
    }

    private String serviceCapabilityKey(String serviceName, PluginCapability provider) {
        return provider == null || StringUtils.isEmpty(provider.getKey()) ? serviceName : provider.getKey();
    }

    private KvRepository kvStore() {
        return new WebsiteRuntimeKvStore();
    }

    private PluginBootstrapService pluginBootstrap() {
        return PluginRuntimeBridge.pluginBootstrap();
    }

    public void upload() {
        response.renderJson(new PluginApiModels.EmptyResponse());
    }
}
