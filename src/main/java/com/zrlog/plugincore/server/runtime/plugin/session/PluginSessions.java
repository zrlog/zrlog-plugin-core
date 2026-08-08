package com.zrlog.plugincore.server.runtime.plugin.session;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;

import java.util.List;

public final class PluginSessions {

    private PluginSessions() {
    }

    public static boolean isCurrentPluginIdentity(Plugin plugin) {
        return registry().isCurrentPluginIdentity(plugin);
    }

    public static String nameOrShortName(Plugin plugin) {
        return registry().nameOrShortName(plugin);
    }

    public static List<Plugin> allPlugins() {
        return registry().allPlugins();
    }

    public static boolean isRunningByPluginId(String pluginId) {
        return registry().isRunningByPluginId(pluginId);
    }

    public static boolean isReadyByPluginId(String pluginId) {
        return registry().isReadyByPluginId(pluginId);
    }

    public static void markReady(IOSession session) {
        registry().markReady(session);
    }

    public static boolean isRunningByPluginShortName(String pluginShortName) {
        return registry().isRunningByPluginShortName(pluginShortName);
    }

    public static IOSession getLocalSessionByPluginShortName(String pluginShortName) {
        return registry().getLocalSessionByPluginShortName(pluginShortName);
    }

    public static IOSession getReadyLocalSessionByPluginShortName(String pluginShortName) {
        return registry().getReadyLocalSessionByPluginShortName(pluginShortName);
    }

    public static IOSession getOrStartLocalSessionByPluginShortName(String pluginShortName) {
        return registry().getOrStartLocalSessionByPluginShortName(pluginShortName);
    }

    public static boolean isRequiredPlugin(String pluginShortName) {
        return registry().isRequiredPlugin(pluginShortName);
    }

    public static IOSession getLocalSessionByPluginId(String pluginId) {
        return registry().getLocalSessionByPluginId(pluginId);
    }

    public static IOSession getReadyLocalSessionByPluginId(String pluginId) {
        return registry().getReadyLocalSessionByPluginId(pluginId);
    }

    public static IOSession claimReadyLocalSessionByPluginId(String pluginId) {
        return registry().claimReadyLocalSessionByPluginId(pluginId);
    }

    public static IOSession claimReadyLocalSessionByPluginId(String pluginId, long operationWaitTimeoutMs) {
        return registry().claimReadyLocalSessionByPluginId(pluginId, operationWaitTimeoutMs);
    }

    public static List<IOSession> getLocalSessionsByPluginId(String pluginId) {
        return registry().getLocalSessionsByPluginId(pluginId);
    }

    public static List<IOSession> closeLocalSessionsByPluginId(String pluginId) {
        return registry().closeLocalSessionsByPluginId(pluginId);
    }

    public static List<IOSession> closeLocalSessionsByPluginShortName(String pluginShortName) {
        return registry().closeLocalSessionsByPluginShortName(pluginShortName);
    }

    static boolean hasOpenSessionForRuntimeInstance(String pluginId, String runtimeInstanceId) {
        return registry().hasOpenSessionForRuntimeInstance(pluginId, runtimeInstanceId);
    }

    static void addLocalSession(IOSession session) {
        registry().addLocalSession(session);
    }

    static void removeLocalSession(IOSession session) {
        registry().removeLocalSession(session);
    }

    public static List<IOSession> getAllLocalSessions() {
        return registry().getAllLocalSessions();
    }

    public static String sessionId(IOSession session) {
        return registry().sessionId(session);
    }

    public static Long processId(IOSession session) {
        return registry().processId(session);
    }

    public static String runtimeInstanceId(IOSession session) {
        return registry().runtimeInstanceId(session);
    }

    private static PluginSessionRegistry registry() {
        return PluginRuntimeBridge.pluginSessions();
    }
}
