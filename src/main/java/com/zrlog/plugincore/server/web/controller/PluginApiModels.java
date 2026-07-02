package com.zrlog.plugincore.server.web.controller;

import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.vo.PluginCoreSetting;

import java.util.List;
import java.util.Set;

public final class PluginApiModels {

    private PluginApiModels() {
    }

    public static class EmptyResponse {
    }

    public static class ActionResponse {

        private Integer code;
        private String message;

        public ActionResponse() {
        }

        public ActionResponse(Integer code, String message) {
            this.code = code;
            this.message = message;
        }

        public static ActionResponse success(String message) {
            return new ActionResponse(0, message);
        }

        public static ActionResponse error(String message) {
            return new ActionResponse(1, message);
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class PluginListResponse {

        private List<Plugin> plugins;
        private PluginCoreSetting setting;
        private Boolean pluginMetadataReady;
        private Boolean pluginMetadataLoading;
        private Boolean dark;
        private String primaryColor;
        private String pluginVersion;
        private String pluginBuildId;
        private String pluginBuildNumber;
        private Set<String> requiredPlugins;
        private String pluginCenter;

        public List<Plugin> getPlugins() {
            return plugins;
        }

        public void setPlugins(List<Plugin> plugins) {
            this.plugins = plugins;
        }

        public PluginCoreSetting getSetting() {
            return setting;
        }

        public void setSetting(PluginCoreSetting setting) {
            this.setting = setting;
        }

        public Boolean getPluginMetadataReady() {
            return pluginMetadataReady;
        }

        public void setPluginMetadataReady(Boolean pluginMetadataReady) {
            this.pluginMetadataReady = pluginMetadataReady;
        }

        public Boolean getPluginMetadataLoading() {
            return pluginMetadataLoading;
        }

        public void setPluginMetadataLoading(Boolean pluginMetadataLoading) {
            this.pluginMetadataLoading = pluginMetadataLoading;
        }

        public Boolean getDark() {
            return dark;
        }

        public void setDark(Boolean dark) {
            this.dark = dark;
        }

        public String getPrimaryColor() {
            return primaryColor;
        }

        public void setPrimaryColor(String primaryColor) {
            this.primaryColor = primaryColor;
        }

        public String getPluginVersion() {
            return pluginVersion;
        }

        public void setPluginVersion(String pluginVersion) {
            this.pluginVersion = pluginVersion;
        }

        public String getPluginBuildId() {
            return pluginBuildId;
        }

        public void setPluginBuildId(String pluginBuildId) {
            this.pluginBuildId = pluginBuildId;
        }

        public String getPluginBuildNumber() {
            return pluginBuildNumber;
        }

        public void setPluginBuildNumber(String pluginBuildNumber) {
            this.pluginBuildNumber = pluginBuildNumber;
        }

        public Set<String> getRequiredPlugins() {
            return requiredPlugins;
        }

        public void setRequiredPlugins(Set<String> requiredPlugins) {
            this.requiredPlugins = requiredPlugins;
        }

        public String getPluginCenter() {
            return pluginCenter;
        }

        public void setPluginCenter(String pluginCenter) {
            this.pluginCenter = pluginCenter;
        }
    }

    public static class RefreshCacheResponse extends ActionResponse {

        private Integer runtimeEventSuccessCount;
        private Integer runtimeEventFailedCount;
        private Integer runtimeEventHandlerCount;
        private Integer legacySessionCount;
        private Integer successCount;

        public Integer getRuntimeEventSuccessCount() {
            return runtimeEventSuccessCount;
        }

        public void setRuntimeEventSuccessCount(Integer runtimeEventSuccessCount) {
            this.runtimeEventSuccessCount = runtimeEventSuccessCount;
        }

        public Integer getRuntimeEventFailedCount() {
            return runtimeEventFailedCount;
        }

        public void setRuntimeEventFailedCount(Integer runtimeEventFailedCount) {
            this.runtimeEventFailedCount = runtimeEventFailedCount;
        }

        public Integer getRuntimeEventHandlerCount() {
            return runtimeEventHandlerCount;
        }

        public void setRuntimeEventHandlerCount(Integer runtimeEventHandlerCount) {
            this.runtimeEventHandlerCount = runtimeEventHandlerCount;
        }

        public Integer getLegacySessionCount() {
            return legacySessionCount;
        }

        public void setLegacySessionCount(Integer legacySessionCount) {
            this.legacySessionCount = legacySessionCount;
        }

        public Integer getSuccessCount() {
            return successCount;
        }

        public void setSuccessCount(Integer successCount) {
            this.successCount = successCount;
        }
    }

    public static class StatusResponse {

        private Integer code;
        private String status;
        private List<String> runningPlugins;

        public StatusResponse() {
        }

        public StatusResponse(Integer code, String status, List<String> runningPlugins) {
            this.code = code;
            this.status = status;
            this.runningPlugins = runningPlugins;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public List<String> getRunningPlugins() {
            return runningPlugins;
        }

        public void setRunningPlugins(List<String> runningPlugins) {
            this.runningPlugins = runningPlugins;
        }
    }
}
