package com.zrlog.plugincore.server.web.controller;

import com.zrlog.plugincore.server.util.PluginI18n;

import com.hibegin.http.annotation.ResponseBody;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.dao.WebSiteDAO;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.service.ServiceProviderResolver;
import com.zrlog.plugincore.server.runtime.service.ServiceProviderSetting;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ItemsResponse;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.Response;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiModels.ServiceProviderRow;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.error;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.pluginsById;
import static com.zrlog.plugincore.server.web.controller.RuntimeApiResponses.success;
import static com.zrlog.plugincore.server.web.controller.RuntimeProviderResponses.*;

public class RuntimeServiceApiController extends RuntimeBaseApiController {

    private static final String COMMENT_PLUGIN_NAME_KEY = "comment_plugin_name";

    @ResponseBody
    public ItemsResponse<ServiceProviderRow> serviceProviders() {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        return new ItemsResponse<ServiceProviderRow>(serviceProviderRows(
                capabilityStore().listByType("service"),
                pluginCore.getSetting().getService(),
                pluginsById(pluginCore)));
    }

    @ResponseBody
    public Response serviceProviderUpdate() {
        String serviceName = getRequest().getParaToStr("serviceName");
        String pluginId = getRequest().getParaToStr("pluginId");
        String capabilityKey = getRequest().getParaToStr("capabilityKey");
        List<PluginCapability> serviceCapabilities = capabilityStore().listByType("service");
        PluginCapability provider = new ServiceProviderResolver().providersFor(serviceName, serviceCapabilities).stream()
                .filter(item -> Objects.equals(pluginId, item.getPluginId()))
                .filter(item -> Objects.equals(capabilityKey, item.getKey()))
                .findFirst()
                .orElse(null);
        if (provider == null) {
            return error(PluginI18n.text("plugin.service.notFound"));
        }
        ServiceProviderSetting providerSetting = new ServiceProviderSetting();
        providerSetting.setServiceName(serviceName);
        providerSetting.setPluginId(pluginId);
        providerSetting.setCapabilityKey(capabilityKey);
        PluginCoreDAO.getInstance().update(pluginCore ->
                pluginCore.getSetting().getService().getDefaultProviders().put(serviceName, providerSetting));
        return success();
    }

    @ResponseBody
    public Response serviceProviderAuto() {
        String serviceName = getRequest().getParaToStr("serviceName");
        PluginCoreDAO.getInstance().update(pluginCore ->
                pluginCore.getSetting().getService().getDefaultProviders().remove(serviceName));
        return success();
    }

    @ResponseBody
    public Response commentProviders() {
        try {
            List<Plugin> providers = commentProviderPlugins();
            String configured = commentPluginName();
            return commentProviderResponse(providers, configured);
        } catch (SQLException e) {
            return error(e.getMessage());
        }
    }

    @ResponseBody
    public Response commentProviderUpdate() {
        String shortName = getRequest().getParaToStr("shortName");
        if (findPluginByShortName(commentProviderPlugins(), shortName) == null) {
            return error(PluginI18n.text("plugin.service.commentNotFound"));
        }
        try {
            new WebSiteDAO().saveOrUpdateChanged(COMMENT_PLUGIN_NAME_KEY, shortName);
            return success();
        } catch (SQLException e) {
            return error(e.getMessage());
        }
    }

    @ResponseBody
    public Response commentProviderDefault() {
        List<Plugin> providers = commentProviderPlugins();
        String shortName = findPluginByShortName(providers, DEFAULT_COMMENT_PLUGIN) == null && !providers.isEmpty()
                ? providers.get(0).getShortName()
                : DEFAULT_COMMENT_PLUGIN;
        try {
            new WebSiteDAO().saveOrUpdateChanged(COMMENT_PLUGIN_NAME_KEY, shortName);
            return success();
        } catch (SQLException e) {
            return error(e.getMessage());
        }
    }

    private String commentPluginName() throws SQLException {
        Object value = new WebSiteDAO().getWebSiteByNameIn(Collections.singletonList(COMMENT_PLUGIN_NAME_KEY)).get(COMMENT_PLUGIN_NAME_KEY);
        return value == null ? "" : value.toString();
    }
}
