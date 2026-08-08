package com.zrlog.plugincore.server.web.controller;

import com.hibegin.http.annotation.ResponseBody;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpResponse;
import com.hibegin.http.server.web.Controller;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RuntimeEvents;
import com.zrlog.plugin.common.ConfigKit;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.HttpRequestInfo;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.vo.PluginCoreSetting;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.runtime.PluginCoreRunMode;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.RuntimeCapabilityInvokerFactory;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventHandlerResolver;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventPublishResult;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventRequest;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventRuntime;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.state.DefaultPluginRuntimeStarter;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.util.AdminTheme;
import com.zrlog.plugincore.server.util.HttpMsgUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class PluginApiController extends Controller {

    public PluginApiController() {
    }

    public PluginApiController(HttpRequest request, HttpResponse response) {
        super(request, response);
    }

    private IOSession getSession() {
        return PluginSessions.getLocalSessionByPluginShortName(getRequest().getParaToStr("name"));
    }

    @ResponseBody
    public PluginApiModels.PluginListResponse plugins() {
        AdminTheme adminTheme = AdminTheme.fromRequest(getRequest());
        boolean pluginMetadataReady = PluginCoreRunMode.isNativeAgent()
                || pluginBootstrap().isCurrentBootstrapReady();
        PluginCore pluginCore = PluginCoreRunMode.isNativeAgent() ? null : PluginCoreDAO.getInstance().loadSnapshot();
        PluginApiModels.PluginListResponse response = new PluginApiModels.PluginListResponse();
        response.setPlugins(pluginsForCurrentMode(pluginCore));
        response.setSetting(pluginCore == null ? new PluginCoreSetting() : pluginCore.getSetting());
        response.setPluginMetadataReady(pluginMetadataReady);
        response.setPluginMetadataLoading(pluginBootstrap().isBootstrapRunning());
        response.setDark(adminTheme.isDarkMode());
        response.setPrimaryColor(adminTheme.getAdminColorPrimary());
        response.setPluginVersion(String.valueOf(ConfigKit.get("version", "")));
        response.setPluginBuildId(String.valueOf(ConfigKit.get("buildId", "")));
        response.setPluginBuildNumber(String.valueOf(ConfigKit.get("buildNumber", "")));
        response.setRequiredPlugins(pluginBootstrap().getRequiredPlugins().keySet());
        response.setPluginCenter("https://store.zrlog.com/plugin/index.html?upgrade-v3=true&from=#locationHref");
        return response;
    }

    static List<Plugin> pluginsForCurrentMode() {
        return PluginCoreRunMode.isNativeAgent() ? Collections.emptyList() : pluginsForCurrentMode(PluginCoreDAO.getInstance().loadSnapshot());
    }

    static List<Plugin> pluginsForCurrentMode(PluginCore pluginCore) {
        if (PluginCoreRunMode.isNativeAgent() || pluginCore == null || pluginCore.getPluginInfoMap() == null) {
            return Collections.emptyList();
        }
        return pluginCore.getPluginInfoMap().values().stream()
                .filter(pluginEntry -> pluginEntry.getPlugin() != null)
                .map(pluginEntry -> {
                    if (pluginEntry.getPlugin().getPreviewImageBase64() == null
                            || pluginEntry.getPlugin().getPreviewImageBase64().isEmpty()) {
                        pluginEntry.getPlugin().setPreviewImageBase64("");
                    }
                    return pluginEntry.getPlugin();
                })
                .collect(Collectors.toList());
    }

    private HttpRequestInfo genInfo() {
        return HttpMsgUtil.genInfo(getRequest());
    }

    @ResponseBody
    public PluginApiModels.ActionResponse stop() {
        if (getSession() != null) {
            Plugin plugin = getSession().getPlugin();
            if (pluginBootstrap().stopPlugin(plugin.getId(), plugin.getShortName())) {
                return PluginApiModels.ActionResponse.success("停止成功");
            }
            return PluginApiModels.ActionResponse.error("插件停止失败");
        }
        return PluginApiModels.ActionResponse.error("插件没有启动");

    }

    @ResponseBody
    public PluginApiModels.ActionResponse start() throws IOException {
        String pluginShortName = getRequest().getParaToStr("name");
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginCore, pluginShortName);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return PluginApiModels.ActionResponse.error("插件不存在");
        }
        String pluginId = pluginVO.getPlugin().getId();
        if (PluginSessions.isRunningByPluginId(pluginId)) {
            return PluginApiModels.ActionResponse.error("插件已经启动了");
        }
        boolean started = runtimeStateService(pluginCore).ensureStarted(pluginId);
        return new PluginApiModels.ActionResponse(started ? 0 : 1, started ? "插件启动成功" : "插件启动失败");
    }

    @ResponseBody
    public PluginApiModels.ActionResponse uninstall() {
        String pluginShortName = getRequest().getParaToStr("name");
        if (pluginBootstrap().getRequiredPlugins().containsKey(pluginShortName)) {
            return PluginApiModels.ActionResponse.error("必要插件，无法移除");
        }
        IOSession session = getSession();
        if (session != null) {
            session.sendMsg(new MsgPacket(genInfo(), ContentType.JSON, MsgPacketStatus.SEND_REQUEST, IdUtil.getInt(), ActionType.PLUGIN_UNINSTALL.name()));
        }
        if (pluginBootstrap().deletePlugin(pluginShortName)) {
            return PluginApiModels.ActionResponse.success("移除成功");
        }
        return PluginApiModels.ActionResponse.error("插件仍在运行，移除失败");
    }

    @ResponseBody
    public PluginApiModels.RefreshCacheResponse refreshCache() {
        WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
        RuntimeEventRequest eventRequest = refreshCacheRequest();
        long startedAtMs = System.currentTimeMillis();
        RuntimeEventPublishResult eventResult = runtimeEventRuntime(kvStore).publish(eventRequest);
        int legacySessionCount = broadcastLegacyRefreshCache(eventResult.getHandlerPluginIds());
        int failedCount = eventResult.getFailedCount();
        int successCount = eventResult.getSuccessCount() + legacySessionCount;
        new InvocationLogStore(kvStore).append(refreshCacheInvocationLog(eventRequest, eventResult, startedAtMs, System.currentTimeMillis()));

        PluginApiModels.RefreshCacheResponse response = new PluginApiModels.RefreshCacheResponse();
        response.setCode(failedCount == 0 ? 0 : 1);
        response.setMessage(failedCount == 0 ? "更新缓存成功" : "部分插件更新缓存失败");
        response.setRuntimeEventSuccessCount(eventResult.getSuccessCount());
        response.setRuntimeEventFailedCount(failedCount);
        response.setRuntimeEventHandlerCount(eventResult.getHandlerCount());
        response.setLegacySessionCount(legacySessionCount);
        response.setSuccessCount(successCount);
        return response;
    }

    private int broadcastLegacyRefreshCache(Set<String> runtimeEventHandlerPluginIds) {
        int[] count = new int[]{0};
        PluginSessions.getAllLocalSessions().forEach(e -> {
            if (e.getPlugin() != null && runtimeEventHandlerPluginIds != null
                    && runtimeEventHandlerPluginIds.contains(e.getPlugin().getId())) {
                return;
            }
            e.sendMsg(ContentType.JSON, new HashMap<>(), ActionType.REFRESH_CACHE.name(), IdUtil.getInt(), MsgPacketStatus.SEND_REQUEST);
            count[0]++;
        });
        return count[0];
    }

    private RuntimeEventRequest refreshCacheRequest() {
        RuntimeEventRequest request = new RuntimeEventRequest();
        request.setEventType(RuntimeEvents.REFRESH_CACHE);
        request.setAliases(Arrays.asList(RuntimeEvents.LEGACY_REFRESH_CACHE, ActionType.REFRESH_CACHE.name()));
        request.setSource("plugin-core");
        Map<String, Object> payload = new HashMap<>();
        payload.put("actionType", ActionType.REFRESH_CACHE.name());
        request.setPayload(payload);
        return request;
    }

    static CapabilityInvocationLog refreshCacheInvocationLog(RuntimeEventRequest request,
                                                             RuntimeEventPublishResult result,
                                                             long startedAtMs,
                                                             long finishedAtMs) {
        CapabilityInvocationLog log = new CapabilityInvocationLog();
        log.setId(UUID.randomUUID().toString());
        log.setPluginId("__system__");
        log.setCapabilityKey(RuntimeEvents.REFRESH_CACHE);
        log.setSource(RuntimeEventRuntime.SOURCE);
        log.setRequestId(request == null ? null : request.getRequestId());
        log.setTraceId(request == null ? null : request.getTraceId());
        log.setStartedAt(startedAtMs);
        log.setFinishedAt(finishedAtMs);
        log.setDurationMs(finishedAtMs - startedAtMs);
        int failedCount = result == null ? 0 : result.getFailedCount();
        log.setStatus(failedCount == 0 ? "success" : "error");
        if (failedCount > 0) {
            log.setErrorMessage("Runtime event handlers failed: " + failedCount);
        }
        return log;
    }

    private RuntimeEventRuntime runtimeEventRuntime(KvRepository kvStore) {
        return new RuntimeEventRuntime(
                new CapabilityStore(kvStore),
                new RuntimeEventHandlerResolver(),
                RuntimeCapabilityInvokerFactory.socket(kvStore)
        );
    }

    @ResponseBody
    public PluginApiModels.StatusResponse status() {
        List<String> plugins = PluginSessions.getAllLocalSessions().stream().map(e -> e.getPlugin().getShortName()).collect(Collectors.toList());
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        boolean onDemandEnabled = Boolean.TRUE.equals(pluginCore.getSetting().getRuntime().getOnDemandEnabled());
        boolean allRunning = !onDemandEnabled && pluginBootstrap().allRunning();
        return statusResponse(onDemandEnabled, allRunning, plugins);
    }

    static PluginApiModels.StatusResponse statusResponse(boolean onDemandEnabled, boolean allRunning, List<String> runningPlugins) {
        String status = onDemandEnabled || allRunning ? "STARTED" : "STARTING";
        return new PluginApiModels.StatusResponse(0, status, runningPlugins);
    }

    private PluginRuntimeStateService runtimeStateService(PluginCore pluginCore) {
        return new PluginRuntimeStateService(new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()),
                new DefaultPluginRuntimeStarter(pluginCore));
    }

    private PluginBootstrapService pluginBootstrap() {
        return PluginRuntimeBridge.pluginBootstrap();
    }
}
