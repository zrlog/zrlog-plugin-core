package com.zrlog.plugincore.server;

import com.hibegin.common.util.IOUtil;
import com.zrlog.plugin.common.ConfigKit;
import com.zrlog.plugin.common.model.BlogRunTime;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import com.zrlog.plugincore.server.util.DevUtil;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

class ApplicationStartupOptions {

    private final int httpPort;
    private final int masterPort;
    private final File dbProperties;
    private final String pluginPath;
    private final BlogRunTime blogRunTime;
    private final boolean externalDbProperties;
    private final int listenBlogPort;
    private final String blogApiHomeUrl;
    private final String blogPluginToken;
    private final String nativeInfo;
    private final String contextPath;
    private final boolean hostSchedulerDriven;

    private ApplicationStartupOptions(int httpPort,
                                      int masterPort,
                                      File dbProperties,
                                      String pluginPath,
                                      BlogRunTime blogRunTime,
                                      boolean externalDbProperties,
                                      int listenBlogPort,
                                      String blogApiHomeUrl,
                                      String blogPluginToken,
                                      String nativeInfo,
                                      String contextPath, boolean hostSchedulerDriven) {
        this.httpPort = httpPort;
        this.masterPort = masterPort;
        this.dbProperties = dbProperties;
        this.pluginPath = pluginPath;
        this.blogRunTime = blogRunTime;
        this.externalDbProperties = externalDbProperties;
        this.listenBlogPort = listenBlogPort;
        this.blogApiHomeUrl = blogApiHomeUrl;
        this.blogPluginToken = blogPluginToken;
        this.nativeInfo = nativeInfo;
        this.contextPath = contextPath;
        this.hostSchedulerDriven = hostSchedulerDriven;
    }

    static ApplicationStartupOptions parse(String[] args) throws IOException {
        int httpPort = intArg(args, 0, 9089);
        int masterPort = intArg(args, 1, ConfigKit.getServerPort());
        String dbPropertiesPath = stringArg(args, 2, null);
        String pluginPath = stringArg(args, 3, DevUtil.pluginHome());
        BlogRunTime blogRunTime = new BlogRunTime();
        if (dbPropertiesPath == null) {
            File dbProperties = createDevDbProperties();
            blogRunTime.setPath(DevUtil.blogRuntimePath());
            blogRunTime.setVersion("1.5");
            return new ApplicationStartupOptions(httpPort, masterPort, dbProperties, pluginPath, blogRunTime,
                    false, -1, PluginHostConnection.DEFAULT_BLOG_API_HOME_URL, "", "", "", false);
        }
        blogRunTime.setPath(stringArg(args, 5, DevUtil.blogRuntimePath()));
        blogRunTime.setVersion(stringArg(args, 6, DevUtil.blogVersion()));
        String contextPath = contextPath(args);
        String blogApiHomeUrl = blogApiHomeUrl(args, contextPath);
        return new ApplicationStartupOptions(httpPort, masterPort, new File(dbPropertiesPath), pluginPath, blogRunTime,
                true, intArg(args, 4, -1), blogApiHomeUrl, stringArg(args, 8, "_NOT_FOUND"),
                normalizeNativeInfo(stringArg(args, 9, "")), contextPath,
                args != null && java.util.Arrays.asList(args).contains("--host-scheduler"));
    }

    private static File createDevDbProperties() throws IOException {
        File tmpFile = File.createTempFile("blog-db", ".properties");
        IOUtil.writeBytesToFile(IOUtil.getByteByInputStream(ApplicationStartupOptions.class.getResourceAsStream("/db.properties")), tmpFile);
        return tmpFile;
    }

    private static String blogApiHomeUrl(String[] args, String contextPath) {
        String blogApiHomeUrl = hasArg(args, 7) ? "http://127.0.0.1:" + Integer.parseInt(args[7])
                : PluginHostConnection.DEFAULT_BLOG_API_HOME_URL;
        return blogApiHomeUrl + contextPath;
    }

    private static String contextPath(String[] args) {
        return PluginHostConnection.normalizeContextPath(stringArg(args, 10, ""));
    }

    private static String normalizeNativeInfo(String nativeInfo) {
        if (Objects.equals(nativeInfo, "-")) {
            return "";
        }
        return nativeInfo;
    }

    private static int intArg(String[] args, int index, int fallback) {
        if (!hasArg(args, index)) {
            return fallback;
        }
        return Integer.parseInt(args[index]);
    }

    private static String stringArg(String[] args, int index, String fallback) {
        if (!hasArg(args, index)) {
            return fallback;
        }
        return args[index];
    }

    private static boolean hasArg(String[] args, int index) {
        return args != null && args.length > index;
    }

    int getHttpPort() {
        return httpPort;
    }

    int getMasterPort() {
        return masterPort;
    }

    File getDbProperties() {
        return dbProperties;
    }

    String getPluginPath() {
        return pluginPath;
    }

    BlogRunTime getBlogRunTime() {
        return blogRunTime;
    }

    boolean hasExternalDbProperties() {
        return externalDbProperties;
    }

    int getListenBlogPort() {
        return listenBlogPort;
    }

    String getBlogApiHomeUrl() {
        return blogApiHomeUrl;
    }

    String getBlogPluginToken() {
        return blogPluginToken;
    }

    String getNativeInfo() {
        return nativeInfo;
    }

    boolean isHostSchedulerDriven() { return hostSchedulerDriven; }

    String getContextPath() {
        return contextPath;
    }
}
