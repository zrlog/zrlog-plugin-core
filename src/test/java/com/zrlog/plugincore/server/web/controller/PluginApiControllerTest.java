package com.zrlog.plugincore.server.web.controller;

import com.zrlog.plugin.RuntimeEvents;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventPublishResult;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventRequest;
import com.zrlog.plugincore.server.runtime.event.RuntimeEventRuntime;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PluginApiControllerTest {

    @Test
    public void shouldReportStartedWhenRuntimeIsOnDemand() {
        PluginApiModels.StatusResponse response = PluginApiController.statusResponse(true, false, Arrays.asList("comment"));

        assertEquals(Integer.valueOf(0), response.getCode());
        assertEquals("STARTED", response.getStatus());
        assertEquals(Arrays.asList("comment"), response.getRunningPlugins());
    }

    @Test
    public void shouldReportStartedWhenAllPluginsAreRunningWithoutOnDemand() {
        PluginApiModels.StatusResponse response = PluginApiController.statusResponse(false, true, Arrays.asList("comment", "oss"));

        assertEquals("STARTED", response.getStatus());
    }

    @Test
    public void shouldReportStartingWhenWaitingForPluginsWithoutOnDemand() {
        PluginApiModels.StatusResponse response = PluginApiController.statusResponse(false, false, Arrays.asList("comment"));

        assertEquals("STARTING", response.getStatus());
    }

    @Test
    public void shouldNotLoadPluginListsInNativeAgentMode() {
        RunType previous = RunConstants.runType;
        try {
            RunConstants.runType = RunType.AGENT;

            assertTrue(PluginApiController.pluginsForCurrentMode().isEmpty());
        } finally {
            RunConstants.runType = previous;
        }
    }

    @Test
    public void shouldBuildPluginListFromProvidedSnapshot() {
        RunType previous = RunConstants.runType;
        try {
            RunConstants.runType = RunType.BLOG;
            PluginCore pluginCore = new PluginCore();
            Plugin plugin = new Plugin();
            plugin.setId("plugin-id");
            plugin.setShortName("comment");
            PluginVO pluginVO = new PluginVO();
            pluginVO.setPlugin(plugin);
            pluginCore.getPluginInfoMap().put("comment", pluginVO);

            List<Plugin> plugins = PluginApiController.pluginsForCurrentMode(pluginCore);

            assertEquals(1, plugins.size());
            assertEquals("comment", plugins.get(0).getShortName());
            assertEquals("", plugins.get(0).getPreviewImageBase64());
        } finally {
            RunConstants.runType = previous;
        }
    }

    @Test
    public void shouldBuildRefreshCacheSystemInvocationLog() {
        RuntimeEventRequest request = new RuntimeEventRequest();
        request.setEventType(RuntimeEvents.REFRESH_CACHE);
        request.setRequestId("refresh-cache-1");
        RuntimeEventPublishResult result = new RuntimeEventPublishResult();

        CapabilityInvocationLog log = PluginApiController.refreshCacheInvocationLog(request, result, 100L, 145L);

        assertEquals("__system__", log.getPluginId());
        assertEquals(RuntimeEvents.REFRESH_CACHE, log.getCapabilityKey());
        assertEquals(RuntimeEventRuntime.SOURCE, log.getSource());
        assertEquals("refresh-cache-1", log.getRequestId());
        assertEquals("success", log.getStatus());
        assertEquals(Long.valueOf(45L), log.getDurationMs());
    }

}
