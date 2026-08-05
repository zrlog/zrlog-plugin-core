package com.zrlog.plugincore.server.runtime.plugin.session;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.util.StringUtils;
import com.zrlog.plugincore.server.vo.PluginVO;

import java.nio.channels.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

public class PluginSessionRegistry {

    public static final String SESSION_ID_ATTR = "_zrlog_session_id";
    public static final String PROCESS_ID_ATTR = "_zrlog_process_id";

    private final List<IOSession> localSessions = new CopyOnWriteArrayList<>();
    private final SessionStopMarker sessionStopMarker;
    private final PluginSessionHeartbeat heartbeat;
    private final Map<String, String> requiredPlugins;
    private final Function<Plugin, Boolean> pluginStarter;

    public PluginSessionRegistry() {
        this(Collections.emptyMap());
    }

    public PluginSessionRegistry(Map<String, String> requiredPlugins) {
        this(PluginRuntimeStates::markStoppedIfCurrent, null, requiredPlugins, PluginRuntimeStates::ensureStarted);
    }

    PluginSessionRegistry(SessionStopMarker sessionStopMarker) {
        this(sessionStopMarker, PluginSessionHeartbeat.disabled());
    }

    PluginSessionRegistry(SessionStopMarker sessionStopMarker, PluginSessionHeartbeat heartbeat) {
        this(sessionStopMarker, heartbeat, Collections.emptyMap(), PluginRuntimeStates::ensureStarted);
    }

    PluginSessionRegistry(SessionStopMarker sessionStopMarker, PluginSessionHeartbeat heartbeat,
                          Map<String, String> requiredPlugins, Function<Plugin, Boolean> pluginStarter) {
        this.sessionStopMarker = sessionStopMarker == null ? PluginRuntimeStates::markStoppedIfCurrent : sessionStopMarker;
        this.requiredPlugins = requiredPlugins == null ? Collections.emptyMap() : new HashMap<>(requiredPlugins);
        this.pluginStarter = pluginStarter == null ? PluginRuntimeStates::ensureStarted : pluginStarter;
        this.heartbeat = heartbeat == null
                ? PluginSessionHeartbeat.active(this::getAllLocalSessions, this::closeLocalSession)
                : heartbeat;
    }

    public boolean isCurrentPluginIdentity(Plugin plugin) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(plugin)) {
            PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(plugin.getShortName());
            return pluginVO != null && pluginVO.getPlugin() != null
                    && Objects.equals(plugin.getId(), pluginVO.getPlugin().getId());
        }
    }

    public String nameOrShortName(Plugin plugin) {
        if (plugin == null) {
            return "";
        }
        if (!StringUtils.isEmpty(plugin.getName())) {
            return plugin.getName();
        }
        return plugin.getShortName();
    }

    public List<Plugin> allPlugins() {
        List<Plugin> allPlugins = new ArrayList<>();
        for (PluginVO pluginEntry : PluginCoreDAO.getInstance().getPluginVOs()) {
            if (pluginEntry.getPlugin() == null) {
                continue;
            }
            if (StringUtils.isEmpty(pluginEntry.getPlugin().getPreviewImageBase64())) {
                pluginEntry.getPlugin().setPreviewImageBase64("");
            }
            allPlugins.add(pluginEntry.getPlugin());
        }
        return allPlugins;
    }

    public boolean isRunningByPluginId(String pluginId) {
        return getLocalSessionByPluginId(pluginId) != null;
    }

    public boolean isRunningByPluginShortName(String pluginShortName) {
        return getLocalSessionByPluginShortName(pluginShortName) != null;
    }

    public IOSession getLocalSessionByPluginShortName(String pluginShortName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(null, pluginShortName, pluginShortName)) {
            return firstOpenLocalSession(session -> matchesPluginShortName(session, pluginShortName));
        }
    }

    public IOSession getOrStartLocalSessionByPluginShortName(String pluginShortName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(null, pluginShortName, pluginShortName)) {
            IOSession session = getLocalSessionByPluginShortName(pluginShortName);
            if (session != null) {
                return session;
            }
            Plugin plugin = pluginForStart(pluginShortName);
            if (plugin == null) {
                return null;
            }
            try (PluginLogContext.Scope pluginScope = PluginLogContext.open(plugin)) {
                if (!pluginStarter.apply(plugin)) {
                    return null;
                }
                IOSession startedSession = getLocalSessionByPluginId(plugin.getId());
                return startedSession == null ? getLocalSessionByPluginShortName(pluginShortName) : startedSession;
            }
        }
    }

    public boolean isRequiredPlugin(String pluginShortName) {
        return !StringUtils.isEmpty(pluginShortName) && requiredPlugins.containsKey(pluginShortName);
    }

    private Plugin pluginForStart(String pluginShortName) {
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginShortName);
        if (pluginVO != null && pluginVO.getPlugin() != null) {
            return pluginVO.getPlugin();
        }
        String pluginId = requiredPlugins.get(pluginShortName);
        if (StringUtils.isEmpty(pluginId)) {
            return null;
        }
        Plugin plugin = new Plugin();
        plugin.setId(pluginId);
        plugin.setShortName(pluginShortName);
        plugin.setName(pluginShortName);
        return plugin;
    }

    public IOSession getLocalSessionByPluginId(String pluginId) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            return firstOpenLocalSession(session -> matchesPluginId(session, pluginId));
        }
    }

    public List<IOSession> getLocalSessionsByPluginId(String pluginId) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            return openLocalSessions(session -> matchesPluginId(session, pluginId));
        }
    }

    public List<IOSession> closeLocalSessionsByPluginId(String pluginId) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            return closeLocalSessions(session -> matchesPluginId(session, pluginId));
        }
    }

    public List<IOSession> closeLocalSessionsByPluginShortName(String pluginShortName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(null, pluginShortName, pluginShortName)) {
            return closeLocalSessions(session -> matchesPluginShortName(session, pluginShortName));
        }
    }

    public boolean hasOpenSessionForRuntimeInstance(String pluginId, String runtimeInstanceId) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, null, null)) {
            for (IOSession session : getLocalSessionsByPluginId(pluginId)) {
                if (session.getPlugin() != null && Objects.equals(runtimeInstanceId, runtimeInstanceId(session))) {
                    return true;
                }
            }
            return false;
        }
    }

    public void addLocalSession(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session == null || session.getPlugin() == null || StringUtils.isEmpty(session.getPlugin().getId())) {
                return;
            }
            sessionId(session);
            heartbeat.start();
            heartbeat.register(session);
            if (!localSessions.contains(session)) {
                localSessions.add(session);
            }
        }
    }

    public void removeLocalSession(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session != null) {
                localSessions.remove(session);
            }
        }
    }

    public List<IOSession> getAllLocalSessions() {
        return new ArrayList<>(localSessions);
    }

    private IOSession firstOpenLocalSession(Predicate<IOSession> matcher) {
        for (IOSession session : localSessions(matcher)) {
            try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
                if (isSessionUsable(session)) {
                    return session;
                }
            }
        }
        return null;
    }

    private List<IOSession> openLocalSessions(Predicate<IOSession> matcher) {
        List<IOSession> sessions = new ArrayList<>();
        for (IOSession session : localSessions(matcher)) {
            try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
                if (isSessionUsable(session)) {
                    sessions.add(session);
                }
            }
        }
        return sessions;
    }

    private List<IOSession> closeLocalSessions(Predicate<IOSession> matcher) {
        List<IOSession> sessions = localSessions(matcher);
        for (IOSession session : sessions) {
            try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
                closeLocalSession(session);
            }
        }
        return sessions;
    }

    private List<IOSession> localSessions(Predicate<IOSession> matcher) {
        List<IOSession> sessions = new ArrayList<>();
        for (IOSession session : localSessions) {
            if (matcher.test(session)) {
                sessions.add(session);
            }
        }
        return sessions;
    }

    private void closeLocalSession(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session == null) {
                return;
            }
            try {
                if (session.getSystemAttr().get("_channel") instanceof Channel) {
                    session.close();
                }
            } finally {
                removeClosedLocalSession(session);
            }
        }
    }

    private void removeClosedLocalSession(IOSession session) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (session == null || session.getPlugin() == null) {
                removeLocalSession(session);
                return;
            }
            String pluginId = session.getPlugin().getId();
            String runtimeInstanceId = runtimeInstanceId(session);
            removeLocalSession(session);
            if (!hasOpenSessionForRuntimeInstance(pluginId, runtimeInstanceId)) {
                sessionStopMarker.markStoppedIfCurrent(session);
            }
        }
    }

    private boolean matchesPluginId(IOSession session, String pluginId) {
        return session != null && session.getPlugin() != null && Objects.equals(pluginId, session.getPlugin().getId());
    }

    private boolean matchesPluginShortName(IOSession session, String pluginShortName) {
        return session != null && session.getPlugin() != null
                && Objects.equals(pluginShortName, session.getPlugin().getShortName());
    }

    public String sessionId(IOSession session) {
        Object existing = session.getSystemAttr().get(SESSION_ID_ATTR);
        if (existing != null && !existing.toString().trim().isEmpty()) {
            return existing.toString();
        }
        String sessionId = PluginRuntimeStates.newRuntimeInstanceId();
        session.getSystemAttr().put(SESSION_ID_ATTR, sessionId);
        return sessionId;
    }

    public Long processId(IOSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getSystemAttr().get(PROCESS_ID_ATTR);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public String runtimeInstanceId(IOSession session) {
        return sessionId(session);
    }

    private boolean isSessionOpen(IOSession session) {
        Object channel = session.getSystemAttr().get("_channel");
        return channel instanceof Channel && ((Channel) channel).isOpen();
    }

    private boolean isSessionUsable(IOSession session) {
        if (!isSessionOpen(session)) {
            removeClosedLocalSession(session);
            return false;
        }
        if (!heartbeat.ensureRecentHeartbeat(session, System.currentTimeMillis())) {
            closeLocalSession(session);
            return false;
        }
        return true;
    }

    interface SessionStopMarker {
        void markStoppedIfCurrent(IOSession session);
    }
}
