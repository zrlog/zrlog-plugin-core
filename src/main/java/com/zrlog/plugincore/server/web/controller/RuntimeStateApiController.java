package com.zrlog.plugincore.server.web.controller;

import com.hibegin.common.dao.dto.PageData;
import com.hibegin.http.HttpMethod;
import com.hibegin.http.annotation.ResponseBody;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.PluginCoreRunMode;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessQueryService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import com.zrlog.plugincore.server.vo.PluginVO;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.CapabilityResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ActionResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ItemsResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.InvocationLogResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.PageResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.Response;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.RuntimeSettingsResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.*;

public class RuntimeStateApiController extends RuntimeBaseApiController {

    @ResponseBody
    public ItemsResponse<?> runtimeStates() {
        return new ItemsResponse<Object>(runtimeStatesForCurrentMode());
    }

    @ResponseBody
    public Response runtimeStart() {
        String pluginId = getRequest().getParaToStr("pluginId");
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOById(pluginCore, pluginId);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return error("插件不存在");
        }
        boolean started = runtimeStateService(pluginCore).ensureStarted(pluginVO.getPlugin().getId());
        if (!started) {
            return error("插件启动失败");
        }
        return ActionResponse.started();
    }

    @ResponseBody
    public Response runtimeStop() {
        String pluginId = getRequest().getParaToStr("pluginId");
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOById(pluginCore, pluginId);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return error("插件不存在");
        }
        if (activeInvocationCount(pluginId) > 0) {
            return error("插件正在执行任务");
        }
        String pluginName = pluginDisplayName(pluginVO.getPlugin());
        try {
            runtimeStateService(pluginCore).markStopping(pluginId, pluginName);
            if (!pluginBootstrap().stopPlugin(pluginId, pluginVO.getPlugin().getShortName())) {
                throw new IllegalStateException("插件停止失败");
            }
            runtimeStateService(pluginCore).markStopped(pluginId, pluginName);
            return success();
        } catch (RuntimeException e) {
            runtimeStateService(pluginCore).markFailed(pluginId, pluginName, e.getMessage());
            return error(e.getMessage());
        }
    }

    @ResponseBody
    public Response runtimeSettings() {
        PluginCoreDAO pluginCoreDAO = PluginCoreDAO.getInstance();
        PluginCore currentPluginCore = pluginCoreDAO.loadSnapshot();
        PluginRuntimeSetting setting = currentPluginCore.getSetting().getRuntime();
        if (getRequest().getMethod() == HttpMethod.POST) {
            try {
                Boolean onDemandEnabled = runtimeBooleanParam("onDemandEnabled", setting.getOnDemandEnabled());
                Boolean autoDownloadMissingPluginFileEnabled = runtimeBooleanParam("autoDownloadMissingPluginFileEnabled",
                        currentPluginCore.getSetting().isAutoDownloadMissingPluginFileEnabled());
                Boolean idleStopEnabled = runtimeBooleanParam("idleStopEnabled", setting.getIdleStopEnabled());
                Long idleTimeoutSeconds = runtimeLongParam("idleTimeoutSeconds", setting.getIdleTimeoutSeconds(),
                        PluginRuntimeSetting.MIN_IDLE_TIMEOUT_SECONDS, PluginRuntimeSetting.MAX_IDLE_TIMEOUT_SECONDS);
                Long idleScanIntervalSeconds = runtimeLongParam("idleScanIntervalSeconds", setting.getIdleScanIntervalSeconds(),
                        PluginRuntimeSetting.MIN_IDLE_SCAN_INTERVAL_SECONDS, PluginRuntimeSetting.MAX_IDLE_SCAN_INTERVAL_SECONDS);
                Long maxRunningPlugins = runtimeLongParam("maxRunningPlugins", setting.getMaxRunningPlugins(),
                        PluginRuntimeSetting.MIN_MAX_RUNNING_PLUGINS, PluginRuntimeSetting.MAX_MAX_RUNNING_PLUGINS);
                Long maxConcurrentStarts = runtimeLongParam("maxConcurrentStarts", setting.getMaxConcurrentStarts(),
                        PluginRuntimeSetting.MIN_MAX_CONCURRENT_STARTS, PluginRuntimeSetting.MAX_MAX_CONCURRENT_STARTS);
                Long startFailureBackoffSeconds = runtimeLongParam("startFailureBackoffSeconds",
                        setting.getStartFailureBackoffSeconds(), PluginRuntimeSetting.MIN_START_FAILURE_BACKOFF_SECONDS,
                        PluginRuntimeSetting.MAX_START_FAILURE_BACKOFF_SECONDS);
                PluginRuntimeSetting requested = new PluginRuntimeSetting();
                requested.setOnDemandEnabled(onDemandEnabled);
                requested.setAutoDownloadMissingPluginFileEnabled(autoDownloadMissingPluginFileEnabled);
                requested.setIdleStopEnabled(idleStopEnabled);
                requested.setIdleTimeoutSeconds(idleTimeoutSeconds);
                requested.setIdleScanIntervalSeconds(idleScanIntervalSeconds);
                requested.setMaxRunningPlugins(maxRunningPlugins);
                requested.setMaxConcurrentStarts(maxConcurrentStarts);
                requested.setStartFailureBackoffSeconds(startFailureBackoffSeconds);
                automationService().saveRuntimeMaintenance(requested, null);
                setting = pluginCoreDAO.loadSnapshot().getSetting().getRuntime();
                pluginBootstrap().loadPluginsAsync();
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
        }
        return new RuntimeSettingsResponse(setting);
    }

    @ResponseBody
    public PageResponse<InvocationLogResponse> invocationLogs() {
        PageData<CapabilityInvocationLog> page = newestPage(invocationLogStore().list(), 10);
        return pageResponse(invocationLogResponses(page.getRows(), pluginsById()), page);
    }

    @ResponseBody
    public ItemsResponse<CapabilityResponse> capabilities() {
        String pluginId = getRequest().getParaToStr("pluginId");
        String type = getRequest().getParaToStr("type");
        String exposure = getRequest().getParaToStr("exposure");
        List<PluginCapability> items = capabilityStore().listAll();
        if (!isBlank(pluginId)) {
            items = items.stream().filter(item -> Objects.equals(pluginId, item.getPluginId())).collect(Collectors.toList());
        }
        if (!isBlank(type)) {
            items = items.stream().filter(item -> Objects.equals(type, item.getType())).collect(Collectors.toList());
        }
        if (!isBlank(exposure)) {
            items = items.stream()
                    .filter(item -> item.getExposure() != null && item.getExposure().contains(exposure))
                    .collect(Collectors.toList());
        }
        return new ItemsResponse<CapabilityResponse>(capabilityResponses(items, pluginsById()));
    }

    static List<?> runtimeStatesForCurrentMode() {
        return PluginCoreRunMode.isNativeAgent() ? Collections.emptyList() : new PluginProcessQueryService().query();
    }

    private Boolean runtimeBooleanParam(String name, Boolean fallback) {
        String value = getRequest().getParaToStr(name);
        if (isBlank(value)) {
            return fallback;
        }
        return getRequest().getParaToBool(name);
    }

    private Long runtimeLongParam(String name, Long fallback, long min, long max) {
        String value = getRequest().getParaToStr(name);
        Long number = fallback;
        if (!isBlank(value)) {
            try {
                number = Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " 必须是数字");
            }
        }
        return number == null ? min : Math.max(min, Math.min(max, number));
    }
}
