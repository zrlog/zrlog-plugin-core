package com.zrlog.plugincore.server.runtime.plugin.config;

public class PluginHostConnection {

    public static final String DEFAULT_BLOG_API_HOME_URL = "http://localhost:7080";

    private final String blogPluginToken;
    private final String blogApiHomeUrl;
    private final String nativeInfo;
    private final String contextPath;
    private final boolean hostSchedulerDriven;

    public PluginHostConnection(String apiHomeUrl, String pluginToken, String nativeInfo, String contextPath) {
        this(apiHomeUrl, pluginToken, nativeInfo, contextPath, false);
    }

    public PluginHostConnection(String apiHomeUrl, String pluginToken, String nativeInfo, String contextPath,
                                boolean hostSchedulerDriven) {
        this.hostSchedulerDriven = hostSchedulerDriven;
        this.blogApiHomeUrl = apiHomeUrl;
        this.blogPluginToken = pluginToken;
        this.nativeInfo = nativeInfo;
        this.contextPath = normalizeContextPath(contextPath);
    }

    public static PluginHostConnection defaults() {
        return new PluginHostConnection(DEFAULT_BLOG_API_HOME_URL, "", "", "");
    }

    public static String normalizeContextPath(String contextPath) {
        if (contextPath == null) {
            return "";
        }
        String value = contextPath.trim().replace("#", "");
        if (value.isEmpty() || "/".equals(value)) {
            return "";
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public String getBlogPluginToken() {
        return blogPluginToken;
    }

    public String getBlogApiHomeUrl() {
        return blogApiHomeUrl;
    }

    public String getNativeInfo() {
        return nativeInfo;
    }

    public boolean isHostSchedulerDriven() { return hostSchedulerDriven; }

    public String getContextPath() {
        return contextPath;
    }
}
