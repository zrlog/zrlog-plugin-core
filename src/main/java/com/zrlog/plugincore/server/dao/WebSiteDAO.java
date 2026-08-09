package com.zrlog.plugincore.server.dao;


import com.hibegin.common.dao.DAO;
import com.hibegin.common.dao.DaoTrace;
import com.zrlog.plugin.common.LoggerUtil;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Created by xiaochun on 2016/2/14.
 */
public class WebSiteDAO extends DAO {

    private static final Logger LOGGER = LoggerUtil.getLogger(WebSiteDAO.class);
    private static final String CAS_VERSION_PREFIX = "cas:";

    public WebSiteDAO() {
        this.tableName = "website";
    }

    public Object queryValueByName(String key) throws SQLException {
        DaoTrace.info(LOGGER, "website.queryValueByName", "key=" + key);
        return new WebSiteDAO().set("name", key).queryFirst("value");
    }

    public boolean saveOrUpdate(String key, Object value) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        return Boolean.TRUE.equals(saveOrUpdate(values).get(key));
    }

    public boolean saveOrUpdateVersioned(String key, Object value) throws SQLException {
        return saveOrUpdateOne(key, value, nextVersionRemark());
    }

    public boolean saveOrUpdateChanged(String key, Object value) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        return Boolean.TRUE.equals(saveOrUpdateChanged(values).get(key));
    }

    public boolean compareAndSet(String key, String expectedValue, Object value) throws SQLException {
        WebSiteValueSnapshot snapshot = queryValueSnapshotByName(key);
        if (!Objects.equals(snapshot.getValue().orElse(null), expectedValue)) {
            return false;
        }
        return compareAndSet(key, expectedValue, snapshot.getRemark(), value);
    }

    public boolean compareAndSet(String key, String expectedValue, String expectedRemark, Object value) throws SQLException {
        DaoTrace.info(LOGGER, "website.compareAndSet",
                "key=" + key + " expected=" + DaoTrace.valueSummary(expectedValue)
                        + " value=" + DaoTrace.valueSummary(value));
        String nextRemark = nextVersionRemark();
        if (expectedValue == null) {
            if (exists(key)) {
                return false;
            }
            try {
                return new WebSiteDAO().set("name", key).set("value", storedValue(value))
                        .set("remark", nextRemark).save();
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
                return false;
            }
        }
        if (!isBlank(expectedRemark)) {
            return new WebSiteDAO().execute("update website set value=?, remark=? where name=? and remark=?",
                    storedValue(value), nextRemark, key, expectedRemark);
        }
        return new WebSiteDAO().execute("update website set value=?, remark=? where name=? and value=? and (remark is null or remark='')",
                storedValue(value), nextRemark, key, expectedValue);
    }

    public Map<String, Boolean> saveOrUpdate(Map<String, Object> values) throws SQLException {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            return results;
        }
        DaoTrace.info(LOGGER, "website.saveOrUpdate", DaoTrace.keysSummary(values.keySet()));
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            results.put(entry.getKey(), saveOrUpdateOne(entry.getKey(), entry.getValue(), null));
        }
        return results;
    }

    public Map<String, Boolean> saveOrUpdateChanged(Map<String, Object> values) throws SQLException {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            return results;
        }
        Map<String, Object> existingValues = existingValuesByName(new ArrayList<>(values.keySet()));
        Map<String, Object> changedValues = changedValues(existingValues, values);
        for (String key : values.keySet()) {
            if (!changedValues.containsKey(key)) {
                results.put(key, true);
            }
        }
        if (changedValues.isEmpty()) {
            return results;
        }
        results.putAll(doSaveOrUpdate(changedValues));
        return results;
    }

    protected Map<String, Object> existingValuesByName(List<String> names) throws SQLException {
        return getWebSiteByNameIn(names);
    }

    protected Map<String, Boolean> doSaveOrUpdate(Map<String, Object> values) throws SQLException {
        return saveOrUpdate(values);
    }

    static boolean sameStoredValue(Object storedValue, Object nextValue) {
        String storedText = storedValue == null ? null : storedValue.toString();
        String nextText = nextValue == null ? null : nextValue.toString();
        if (isBooleanText(storedText) && isBooleanText(nextText)) {
            return Boolean.parseBoolean(storedText) == Boolean.parseBoolean(nextText);
        }
        return Objects.equals(storedText, nextText);
    }

    private static boolean isBooleanText(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    static Map<String, Object> changedValues(Map<String, Object> existingValues, Map<String, Object> values) {
        Map<String, Object> changedValues = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            return changedValues;
        }
        Map<String, Object> currentValues = existingValues == null ? new HashMap<>() : existingValues;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (currentValues.containsKey(key) && sameStoredValue(currentValues.get(key), entry.getValue())) {
                continue;
            }
            changedValues.put(key, entry.getValue());
        }
        return changedValues;
    }

    private boolean saveOrUpdateOne(String key, Object value, String remark) throws SQLException {
        Map<String, Object> cond = new HashMap<>();
        cond.put("name", key);
        Object persistedValue = storedValue(value);
        DAO updateDAO = new WebSiteDAO().set("value", persistedValue);
        if (remark != null) {
            updateDAO.set("remark", remark);
        }
        if (updateDAO.update(cond)) {
            return true;
        }
        try {
            DAO insertDAO = new WebSiteDAO().set("name", key).set("value", persistedValue);
            if (remark != null) {
                insertDAO.set("remark", remark);
            }
            return insertDAO.save();
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            DAO retryDAO = new WebSiteDAO().set("value", persistedValue);
            if (remark != null) {
                retryDAO.set("remark", remark);
            }
            if (retryDAO.update(cond)) {
                return true;
            }
            return exists(key);
        }
    }

    private static Object storedValue(Object value) {
        return value instanceof Boolean ? value.toString() : value;
    }

    private boolean exists(String key) throws SQLException {
        DaoTrace.info(LOGGER, "website.exists", "key=" + key);
        return new WebSiteDAO().queryFirstObj("select name from " + tableName + " where name=?", key) != null;
    }

    public WebSiteValueSnapshot queryValueSnapshotByName(String key) throws SQLException {
        DaoTrace.info(LOGGER, "website.queryValueSnapshotByName", "key=" + key);
        List<Map<String, Object>> rows = new WebSiteDAO()
                .queryListWithParams("select value,remark from website where name=?", key);
        if (rows.isEmpty()) {
            return new WebSiteValueSnapshot(Optional.empty(), null);
        }
        Map<String, Object> row = rows.get(0);
        Object value = row.get("value");
        Object remark = row.get("remark");
        return new WebSiteValueSnapshot(value == null ? Optional.empty() : Optional.of(value.toString()),
                remark == null ? null : remark.toString());
    }

    private boolean isDuplicateKey(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("duplicate")
                || lowerMessage.contains("unique")
                || lowerMessage.contains("constraint");
    }

    public Map<String, Object> getWebSiteByNameIn(List<String> names) throws SQLException {
        Map<String, Object> webSites = new HashMap<>();
        if (names == null || names.isEmpty()) {
            return webSites;
        }
        DaoTrace.info(LOGGER, "website.getWebSiteByNameIn", DaoTrace.keysSummary(names));

        StringJoiner sj = new StringJoiner(",");
        Object[] params = new String[names.size()];
        for (int i = 0; i < params.length; i++) {
            sj.add("?");
            params[i] = names.get(i);
        }
        List<Map<String, Object>> lw = queryListWithParams("select name,value from " + tableName + " where name in " + "(" + sj + ")", params);
        for (Map<String, Object> webSite : lw) {
            webSites.put((String) webSite.get("name"), webSite.get("value"));
        }
        return webSites;
    }

    static String nextVersionRemark() {
        return CAS_VERSION_PREFIX + Long.toString(System.currentTimeMillis(), 36) + ":" + UUID.randomUUID();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class WebSiteValueSnapshot {
        private final Optional<String> value;
        private final String remark;

        public WebSiteValueSnapshot(Optional<String> value, String remark) {
            this.value = value == null ? Optional.empty() : value;
            this.remark = remark;
        }

        public Optional<String> getValue() {
            return value;
        }

        public String getRemark() {
            return remark;
        }
    }
}
