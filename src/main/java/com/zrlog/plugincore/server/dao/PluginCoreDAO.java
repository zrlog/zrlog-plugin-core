package com.zrlog.plugincore.server.dao;

import com.google.gson.Gson;
import com.hibegin.common.dao.DaoTrace;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;
import com.zrlog.plugincore.server.vo.PluginVO;

import java.util.*;
import java.sql.SQLException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class PluginCoreDAO {

    private static final String PLUGIN_DB_KEY = "plugin_core_db_key";
    private static final int UPDATE_RETRIES = 3;
    private static final Logger LOGGER = LoggerUtil.getLogger(PluginCoreDAO.class);

    private final Gson gson = new Gson();

    private static PluginCoreDAO instance;

    private static final Lock initLock = new ReentrantLock();

    public static PluginCoreDAO getInstance() {
        if (Objects.nonNull(instance)) {
            return instance;
        }
        initLock.lock();
        try {
            if (Objects.isNull(instance)) {
                instance = new PluginCoreDAO();
            }
            return instance;
        } finally {
            initLock.unlock();
        }
    }

    protected WebSiteDAO.WebSiteValueSnapshot getPluginCoreRawByDb() {
        try {
            DaoTrace.info(LOGGER, "pluginCore.queryRaw", "key=" + PLUGIN_DB_KEY);
            return new WebSiteDAO().queryValueSnapshotByName(PLUGIN_DB_KEY);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PluginCore parsePluginCore(Optional<String> text) {
        if (text.isPresent() && !text.get().isEmpty()) {
            PersistentJsonLimits.requireUtf8Length("Plugin core document", text.get(),
                    PersistentJsonLimits.MAX_PLUGIN_CORE_DOCUMENT_BYTES);
            PluginCore pluginCore = normalize(gson.fromJson(text.get(), PluginCore.class));
            validatePluginEntryCount(pluginCore);
            return pluginCore;
        }
        return new PluginCore();
    }

    public synchronized PluginCore loadSnapshot() {
        DaoTrace.info(LOGGER, "pluginCore.loadSnapshot", null);
        return parsePluginCore(getPluginCoreRawByDb().getValue());
    }

    public synchronized PluginCore update(Consumer<PluginCore> consumer) {
        DaoTrace.info(LOGGER, "pluginCore.update", "maxRetries=" + UPDATE_RETRIES);
        for (int i = 0; i < UPDATE_RETRIES; i++) {
            DaoTrace.info(LOGGER, "pluginCore.updateAttempt", "attempt=" + (i + 1));
            WebSiteDAO.WebSiteValueSnapshot snapshot = getPluginCoreRawByDb();
            Optional<String> raw = snapshot.getValue();
            PluginCore nextPluginCore = parsePluginCore(raw);
            String normalizedCurrentJson = pluginCoreJson(nextPluginCore);
            consumer.accept(nextPluginCore);
            String json = pluginCoreJson(nextPluginCore);
            if (Objects.equals(normalizedCurrentJson, json)) {
                return nextPluginCore;
            }
            try {
                if (compareAndSetPluginCore(raw.orElse(null), snapshot.getRemark(), json)) {
                    return nextPluginCore;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("Failed to update plugin core due to concurrent modification");
    }

    public synchronized void validatePluginRegistration(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin metadata is empty");
        }
        PluginCore projected = loadSnapshot();
        PluginVO pluginVO = new PluginVO();
        pluginVO.setPlugin(plugin);
        pluginVO.setFileMd5("");
        projected.getPluginInfoMap().put(plugin.getShortName(), pluginVO);
        String json = pluginCoreJson(projected);
        PersistentJsonLimits.requireUtf8Length("Projected plugin core document", json,
                PersistentJsonLimits.MAX_PLUGIN_CORE_DOCUMENT_BYTES - 1024);
    }

    protected boolean compareAndSetPluginCore(String expectedValue, String expectedRemark, String value) throws SQLException {
        return new WebSiteDAO().compareAndSet(PLUGIN_DB_KEY, expectedValue, expectedRemark, value);
    }

    public Map<String, PluginVO> getPluginInfoMap() {
        DaoTrace.info(LOGGER, "pluginCore.getPluginInfoMap", null);
        return loadSnapshot().getPluginInfoMap();
    }

    public Collection<PluginVO> getPluginVOs() {
        DaoTrace.info(LOGGER, "pluginCore.getPluginVOs", null);
        return getPluginVOs(loadSnapshot());
    }

    public Collection<PluginVO> getPluginVOs(PluginCore pluginCore) {
        if (pluginCore == null || pluginCore.getPluginInfoMap() == null) {
            return Collections.emptyList();
        }
        return pluginCore.getPluginInfoMap().values();
    }

    public PluginVO getPluginVOByShortName(String pluginShortName) {
        DaoTrace.info(LOGGER, "pluginCore.getPluginVOByShortName", "pluginShortName=" + pluginShortName);
        return getPluginVOByShortName(loadSnapshot(), pluginShortName);
    }

    public PluginVO getPluginVOByShortName(PluginCore pluginCore, String pluginShortName) {
        if (isBlank(pluginShortName)) {
            return null;
        }
        if (pluginCore == null || pluginCore.getPluginInfoMap() == null) {
            return null;
        }
        PluginVO pluginVO = pluginCore.getPluginInfoMap().get(pluginShortName);
        if (pluginVO != null) {
            return pluginVO;
        }
        for (PluginVO item : pluginCore.getPluginInfoMap().values()) {
            if (item != null && item.getPlugin() != null
                    && Objects.equals(pluginShortName, item.getPlugin().getShortName())) {
                return item;
            }
        }
        return null;
    }

    public PluginVO getPluginVOById(String pluginId) {
        DaoTrace.info(LOGGER, "pluginCore.getPluginVOById", "pluginId=" + pluginId);
        return getPluginVOById(loadSnapshot(), pluginId);
    }

    public PluginVO getPluginVOById(PluginCore pluginCore, String pluginId) {
        if (isBlank(pluginId)) {
            return null;
        }
        for (PluginVO pluginVO : getPluginVOs(pluginCore)) {
            if (pluginVO.getPlugin() != null && Objects.equals(pluginId, pluginVO.getPlugin().getId())) {
                return pluginVO;
            }
        }
        return null;
    }

    public void updatePluginFileMd5(String pluginShortName, String pluginId, String fileMd5) {
        DaoTrace.info(LOGGER, "pluginCore.updatePluginFileMd5",
                "pluginShortName=" + pluginShortName + " pluginId=" + pluginId + " fileMd5=" + DaoTrace.valueSummary(fileMd5));
        if (isBlank(fileMd5)) {
            return;
        }
        PluginVO pluginVO = getPluginVOById(pluginId);
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            pluginVO = getPluginVOByShortName(pluginShortName);
        }
        if (pluginVO == null || pluginVO.getPlugin() == null) {
            return;
        }
        PluginVO finalPluginVO = pluginVO;
        update(pluginCore -> {
            removeAliasEntriesByShortName(pluginCore, pluginShortName);
            PluginVO current = pluginCore.getPluginInfoMap().get(pluginShortName);
            if (current == null || current.getPlugin() == null
                    || !Objects.equals(pluginId, current.getPlugin().getId())) {
                current = finalPluginVO;
            }
            current.setFileMd5(fileMd5);
            pluginCore.getPluginInfoMap().put(pluginShortName, current);
        });
    }

    public void removePluginByShortName(String pluginShortName) {
        DaoTrace.info(LOGGER, "pluginCore.removePluginByShortName", "pluginShortName=" + pluginShortName);
        if (isBlank(pluginShortName)) {
            return;
        }
        update(pluginCore -> {
            removeAliasEntriesByShortName(pluginCore, pluginShortName);
            pluginCore.getPluginInfoMap().remove(pluginShortName);
        });
    }

    private void removeAliasEntriesByShortName(PluginCore pluginCore, String pluginShortName) {
        pluginCore.getPluginInfoMap().entrySet().removeIf(entry -> !Objects.equals(entry.getKey(), pluginShortName)
                && entry.getValue() != null
                && entry.getValue().getPlugin() != null
                && Objects.equals(pluginShortName, entry.getValue().getPlugin().getShortName()));
    }

    private PluginCore normalize(PluginCore pluginCore) {
        PluginCore normalized = pluginCore == null ? new PluginCore() : pluginCore;
        normalized.getSetting().getRuntime().normalize();
        return normalized;
    }

    private String pluginCoreJson(PluginCore pluginCore) {
        PluginCore normalized = normalize(pluginCore);
        validatePluginEntryCount(normalized);
        String json = gson.toJson(normalized);
        PersistentJsonLimits.requireUtf8Length("Plugin core document", json,
                PersistentJsonLimits.MAX_PLUGIN_CORE_DOCUMENT_BYTES);
        return json;
    }

    private void validatePluginEntryCount(PluginCore pluginCore) {
        if (pluginCore.getPluginInfoMap() != null
                && pluginCore.getPluginInfoMap().size() > PersistentJsonLimits.MAX_PLUGIN_ENTRIES) {
            throw new IllegalArgumentException("Plugin core entry count exceeds "
                    + PersistentJsonLimits.MAX_PLUGIN_ENTRIES);
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
