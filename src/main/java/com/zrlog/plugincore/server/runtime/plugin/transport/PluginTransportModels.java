package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.hibegin.common.dao.ResultValueConvertUtils;

public final class PluginTransportModels {

    private PluginTransportModels() {
    }

    public static class EmptyResponse {
    }

    public static class ServiceRequest {

        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class WebsiteLoadRequest {

        private String key;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String[] rawKeys() {
            return key.split(",");
        }
    }

    public static class WebsiteSyncOptions {

        private Object syncTemplate;
        private String host;
        private String folder;

        public Object getSyncTemplate() {
            return syncTemplate;
        }

        public void setSyncTemplate(Object syncTemplate) {
            this.syncTemplate = syncTemplate;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getFolder() {
            return folder;
        }

        public void setFolder(String folder) {
            this.folder = folder;
        }

        public boolean hasSyncTemplate() {
            return syncTemplate != null;
        }

        public boolean isSyncTemplateEnabled() {
            return ResultValueConvertUtils.toBoolean(syncTemplate);
        }
    }

    public static class ArticleVisitRequest {

        private String alias;

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }
    }

    public static class ServiceErrorResponse {

        private Integer code;
        private String message;

        public ServiceErrorResponse() {
        }

        public ServiceErrorResponse(Integer code, String message) {
            this.code = code;
            this.message = message;
        }

        public static ServiceErrorResponse error(String message) {
            return new ServiceErrorResponse(1, message);
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

    public static class InitResponse {

        private String runType;

        public InitResponse() {
        }

        public InitResponse(String runType) {
            this.runType = runType;
        }

        public String getRunType() {
            return runType;
        }

        public void setRunType(String runType) {
            this.runType = runType;
        }
    }

    public static class InitErrorResponse {

        private Boolean success;
        private String message;

        public InitErrorResponse() {
        }

        public InitErrorResponse(Boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static InitErrorResponse error(String message) {
            return new InitErrorResponse(false,
                    message == null || message.trim().isEmpty() ? "Plugin init failed" : message);
        }

        public Boolean getSuccess() {
            return success;
        }

        public void setSuccess(Boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class OperationResult {

        private Boolean result;
        private String message;

        public OperationResult() {
        }

        public OperationResult(Boolean result, String message) {
            this.result = result;
            this.message = message;
        }

        public static OperationResult success(boolean result) {
            return new OperationResult(result, null);
        }

        public static OperationResult error(String message) {
            return new OperationResult(false, message);
        }

        public Boolean getResult() {
            return result;
        }

        public void setResult(Boolean result) {
            this.result = result;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

}
