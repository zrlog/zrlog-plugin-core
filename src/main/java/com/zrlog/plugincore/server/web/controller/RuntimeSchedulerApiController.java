package com.zrlog.plugincore.server.web.controller;

import com.google.gson.Gson;
import com.hibegin.common.dao.dto.PageData;
import com.hibegin.http.HttpMethod;
import com.hibegin.http.annotation.ResponseBody;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.capability.RuntimeSources;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.RuntimeCapabilityInvokerFactory;
import com.zrlog.plugincore.server.runtime.event.HostCacheRefreshResult;
import com.zrlog.plugincore.server.runtime.event.HostCacheRefreshRuntime;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventHandlerResolver;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventRuntime;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomation;
import com.zrlog.plugincore.server.runtime.scheduler.PluginAutomationRun;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerExternalEndpoint;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerProviderSetting;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerSetting;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerTickResult;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ActionResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.AutomationResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.AutomationRunResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.AutomationsResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ItemResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.PageResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.Response;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ResultResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.SchedulerSettingsResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.*;

public class RuntimeSchedulerApiController extends RuntimeBaseApiController {

    private final Gson gson = new Gson();

    @ResponseBody
    public SchedulerSettingsResponse schedulerSettings() {
        PluginCoreDAO pluginCoreDAO = PluginCoreDAO.getInstance();
        SchedulerSetting setting = pluginCoreDAO.loadSnapshot().getSetting().getScheduler();
        if (getRequest().getMethod() == HttpMethod.POST) {
            String externalTickEnabled = getRequest().getParaToStr("externalTickEnabled");
            setting = pluginCoreDAO.update(pluginCore -> {
                SchedulerSetting scheduler = pluginCore.getSetting().getScheduler();
                scheduler.setExternalHost(normalizeNullable(getRequest().getParaToStr("externalHost")));
                SchedulerProviderSetting provider = scheduler.ensureDefaultProvider();
                if (!isBlank(externalTickEnabled)) {
                    provider.setEnabled(getRequest().getParaToBool("externalTickEnabled"));
                }
            }).getSetting().getScheduler();
        }
        String fallbackHomeUrl = schedulerFallbackHomeUrl(request);
        SchedulerSettingsResponse response = new SchedulerSettingsResponse();
        response.setEnabled(setting.getEnabled());
        response.setHostDriven(setting.getHostDriven()
                || com.zrlog.plugincore.server.runtime.PluginRuntimeBridge.hostConnection().isHostSchedulerDriven());
        response.setExternalHost(setting.getExternalHost());
        response.setEffectiveExternalHost(SchedulerExternalEndpoint.effectiveHost(setting.getExternalHost(), fallbackHomeUrl));
        response.setExternalTickPath(SchedulerExternalEndpoint.EXTERNAL_TICK_EXPOSE_PATH);
        response.setExternalTickUrl(SchedulerExternalEndpoint.tickUrl(setting.getExternalHost(), fallbackHomeUrl));
        response.setProviders(setting.getProviders());
        response.setSystemTimezone(ZoneId.systemDefault().getId());
        return response;
    }

    @ResponseBody
    public Response automations() {
        if (getRequest().getMethod() == HttpMethod.POST) {
            return saveAutomation();
        }
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        List<PluginCapability> capabilities = capabilityStore().listAll();
        return new AutomationsResponse(automationResponses(
                automationService(pluginCore.getSetting().getRuntime()).listWithSystemAutomations(),
                pluginsById(pluginCore),
                capabilitiesByKey(capabilities)), ZoneId.systemDefault().getId());
    }

    @ResponseBody
    public Response automationUpdate() {
        return saveAutomation();
    }

    @ResponseBody
    public ActionResponse automationDelete() {
        boolean removed = automationService().delete(getRequest().getParaToStr("id"));
        return ActionResponse.removed(removed);
    }

    @ResponseBody
    public Response automationRunNow() {
        try {
            PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
            PluginAutomationRun run = schedulerTickService(pluginCore).runNow(getRequest().getParaToStr("id"), ZonedDateTime.now());
            List<PluginCapability> capabilities = capabilityStore().listAll();
            return new ItemResponse<AutomationRunResponse>(
                    automationRunResponse(run, pluginsById(pluginCore), capabilitiesByKey(capabilities)));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @ResponseBody
    public SchedulerTickResult hostSchedulerTick() {
        long budgetMillis;
        int actorId;
        String triggerId = getRequest().getHeader("X-ZrLog-Trigger-Id");
        try {
            budgetMillis = Long.parseLong(getRequest().getHeader("X-ZrLog-Budget-Millis"));
            actorId = Integer.parseInt(getRequest().getHeader("X-ZrLog-Actor-Id"));
            if (budgetMillis < 1000 || budgetMillis > 645000 || actorId < 1
                    || triggerId == null || !triggerId.matches("[a-zA-Z0-9_.-]{1,128}")) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            getResponse().renderCode(400);
            return null;
        }
        return schedulerTickService(PluginCoreDAO.getInstance().loadSnapshot())
                .tickFromHost(ZonedDateTime.now(), triggerId, actorId, budgetMillis);
    }

    @ResponseBody
    public HostCacheRefreshResult hostRefreshCache() {
        long budget;
        int actor;
        String trigger = getRequest().getHeader("X-ZrLog-Trigger-Id");
        String version = getRequest().getHeader("X-ZrLog-Cache-Version");
        try {
            budget = Long.parseLong(getRequest().getHeader("X-ZrLog-Budget-Millis"));
            actor = Integer.parseInt(getRequest().getHeader("X-ZrLog-Actor-Id"));
            if (budget < 1000 || budget > 645000 || actor < 1 || trigger == null
                    || !trigger.matches("[a-zA-Z0-9_.-]{1,128}") || version == null
                    || !version.matches("[a-zA-Z0-9_.-]{1,128}")) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            getResponse().renderCode(400);
            return null;
        }
        var store = new WebsiteRuntimeKvStore();
        var events = new RuntimeEventRuntime(new CapabilityStore(store), new RuntimeEventHandlerResolver(),
                RuntimeCapabilityInvokerFactory.socket(store));
        return new HostCacheRefreshRuntime(events, PluginSessions::getAllLocalSessions, new InvocationLogStore(store))
                .refresh(trigger, actor, version, budget);
    }

    @ResponseBody
    public com.zrlog.plugincore.server.runtime.event.HostViewResult hostRenderView() {
        String trigger = request.getHeader("X-ZrLog-Trigger-Id"), invocation = request.getHeader("X-ZrLog-Request-Id");
        String name = request.getHeader("X-ZrLog-Plugin-Name"), path;
        int actor;
        long millis;
        try {
            actor = Integer.parseInt(request.getHeader("X-ZrLog-Actor-Id"));
            millis = Long.parseLong(request.getHeader("X-ZrLog-Budget-Millis"));
            java.net.URI uri = java.net.URI.create(request.getHeader("X-ZrLog-View-Path"));
            path = uri.getPath();
            if (actor < 1 || millis < 1000 || millis > 645000 || trigger == null || invocation == null
                    || !trigger.matches("[a-zA-Z0-9_.-]{1,128}") || !invocation.matches("[a-zA-Z0-9_.-]{1,128}")
                    || name == null || !name.matches("[a-zA-Z0-9_-]{1,128}") || uri.isAbsolute() || uri.getRawAuthority() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !com.zrlog.plugincore.server.runtime.event.HostViewRuntime.validPath(path)
                    || request.getQueryStr() != null && request.getQueryStr().length() > 8192)
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { response.renderCode(400); return null; }
        var deadline = com.zrlog.plugincore.server.runtime.util.RuntimeDeadline.afterMillis(millis);
        var view = new com.zrlog.plugin.data.codec.HttpRequestInfo();
        view.setUri(path); view.setParam(request.decodeParamMap());
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (String key : List.of("User-Agent", "Blog-Version", "Current-Locale", "AccessUrl", "Full-Url",
                "Dark-Mode", "Admin-Theme", "Admin-Compact-Mode", "Admin-Color-Primary")) {
            String value = request.getHeader("User-Agent".equals(key) ? "X-ZrLog-View-User-Agent" : key);
            if (value != null) headers.put(key, value);
        }
        headers.put("IsLogin", "false"); view.setHeader(headers);
        view.setFullUrl(headers.get("Full-Url")); view.setAccessUrl(headers.get("AccessUrl")); view.setVersion(headers.get("Blog-Version"));
        com.zrlog.plugincore.server.util.AdminTheme.fromHeaders(headers, null).applyTo(view);
        var store = new WebsiteRuntimeKvStore();
        var states = new com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService(
                new com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore(store),
                new com.zrlog.plugincore.server.runtime.state.DefaultPluginRuntimeStarter());
        return new com.zrlog.plugincore.server.runtime.event.HostViewRuntime(pluginName -> {
            var plugin = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginName);
            return plugin == null ? null : plugin.getPlugin();
        }, states, new com.zrlog.plugincore.server.runtime.capability.SocketCapabilityInvoker(), new InvocationLogStore(store))
                .render(name, view, trigger, invocation, actor, deadline);
    }

    @ResponseBody
    public Response schedulerTick() {
        try {
            SchedulerTickResult result = schedulerTickService(PluginCoreDAO.getInstance().loadSnapshot())
                    .tick(ZonedDateTime.now(), RuntimeSources.TICK);
            return new ResultResponse(result);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @ResponseBody
    public PageResponse<AutomationRunResponse> automationRuns() {
        PageData<PluginAutomationRun> page = newestPage(automationRunStore().list(), 8);
        List<PluginCapability> capabilities = capabilityStore().listAll();
        return pageResponse(automationRunResponses(page.getRows(), pluginsById(), capabilitiesByKey(capabilities)), page);
    }

    private Response saveAutomation() {
        try {
            PluginAutomation automation = readAutomation();
            PluginAutomation saved = automationService().save(automation, null,
                    Boolean.TRUE.equals(getRequest().getParaToBool("reviewConfirmed"))
                            ? getRequest().getParaToStr("reviewRunId") : null);
            List<PluginCapability> capabilities = capabilityStore().listAll();
            return new ItemResponse<AutomationResponse>(automationResponse(saved, pluginsById(), capabilitiesByKey(capabilities)));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private PluginAutomation readAutomation() {
        PluginAutomation automation = new PluginAutomation();
        automation.setId(getRequest().getParaToStr("id"));
        automation.setName(getRequest().getParaToStr("name"));
        automation.setPluginId(getRequest().getParaToStr("pluginId"));
        automation.setCapabilityKey(getRequest().getParaToStr("capabilityKey"));
        automation.setCron(getRequest().getParaToStr("cron"));
        automation.setEnabled(getRequest().getParaToBool("enabled"));
        String payload = getRequest().getParaToStr("payload");
        if (!isBlank(payload)) {
            automation.setPayload(gson.fromJson(payload, Map.class));
        }
        return automation;
    }
}
