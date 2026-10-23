package com.zrlog.plugincore.server;

import com.hibegin.common.util.EnvKit;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.common.ConfigKit;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.runtime.PluginCoreRunMode;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.util.LambdaEnv;
import com.zrlog.plugincore.server.util.OutboundProxy;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.FileHandler;

class ApplicationEnvironment {

    private ApplicationEnvironment() {
    }

    static void initStaticEnvironment() {
        System.setProperty("org.slf4j.simpleLogger.log.com.zaxxer.hikari", "off");
        LambdaEnv.initLambdaEnv();
        OutboundProxy.selector();
    }

    static void initLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %4$s %5$s%6$s%n");
        FileHandler fileHandler = com.hibegin.common.util.LoggerUtil.buildFileHandle();
        LoggerUtil.initFileHandle(fileHandler);
        com.hibegin.common.util.LoggerUtil.initFileHandle(fileHandler);
        com.hibegin.common.dao.DaoLoggerUtil.initFileHandle(fileHandler);
    }

    static void configureFaaSRuntimeRoot(String[] args) {
        if (!EnvKit.isFaaSMode()) {
            return;
        }
        configureWritableRuntimeRoot(parseMasterPort(args));
    }

    static void configureWritableRuntimeRoot(int masterPort) {
        String runtimeRoot = PluginConfig.getFaaSRuntimeRoot(masterPort) + "/plugin-core";
        setPathProperty("sws.root.path", runtimeRoot);
        setPathProperty("sws.log.path", runtimeRoot + "/log");
        setPathProperty("sws.cache.path", runtimeRoot + "/cache");
        setPathProperty("sws.temp.path", runtimeRoot + "/temp");
        setPathProperty("java.io.tmpdir", runtimeRoot + "/tmp");
        setPathProperty("user.home", runtimeRoot + "/usr");
        setPathProperty("user.dir", runtimeRoot);
    }

    static void logArgsIfNeeded(String[] args) {
        if (args != null && args.length > 0 && EnvKit.isDevMode()) {
            LoggerUtil.getLogger(ApplicationEnvironment.class).info("args = " + Arrays.toString(args));
        }
    }

    static void configureStandaloneRunMode(String[] args) {
        if ((Objects.isNull(args) || args.length == 0) && !PluginCoreRunMode.isNativeAgent()) {
            RunConstants.runType = RunType.DEV;
            System.getProperties().put("sws.run.mode", "dev");
        }
    }

    static void configureBlogRunModeIfNeeded(boolean externalDbProperties) {
        if (externalDbProperties && !PluginCoreRunMode.isNativeAgent()) {
            RunConstants.runType = EnvKit.isDevMode() ? RunType.DEV : RunType.BLOG;
        }
    }

    private static void setPathProperty(String key, String value) {
        new File(value).mkdirs();
        System.setProperty(key, value);
    }

    private static int parseMasterPort(String[] args) {
        if (args == null || args.length <= 1) {
            return ConfigKit.getServerPort();
        }
        return Integer.parseInt(args[1]);
    }
}
