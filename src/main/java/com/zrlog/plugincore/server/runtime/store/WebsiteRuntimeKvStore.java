package com.zrlog.plugincore.server.runtime.store;

import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugincore.server.dao.WebSiteDAO;

import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class WebsiteRuntimeKvStore implements ConditionalKvRepository {

    private final Map<String, WebSiteDAO.WebSiteValueSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        try {
            WebSiteDAO.WebSiteValueSnapshot snapshot = new WebSiteDAO().queryValueSnapshotByName(key);
            snapshots.put(key, snapshot);
            return snapshot.getValue();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            new WebSiteDAO().saveOrUpdateVersioned(key, value);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
        try {
            WebSiteDAO.WebSiteValueSnapshot snapshot = snapshots.get(key);
            String expected = expectedValue.orElse(null);
            if (snapshot != null && Objects.equals(snapshot.getValue().orElse(null), expected)) {
                return new WebSiteDAO().compareAndSet(key, expected, snapshot.getRemark(), value);
            }
            return new WebSiteDAO().compareAndSet(key, expected, value);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
