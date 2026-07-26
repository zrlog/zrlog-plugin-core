package com.zrlog.plugincore.server.runtime.article;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.hibegin.common.dao.DAO;
import com.zrlog.plugin.message.ArticleExtensionArticle;
import com.zrlog.plugin.message.ArticleExtensionFilter;
import com.zrlog.plugin.message.ArticleExtensionQueryRequest;
import com.zrlog.plugin.message.ArticleExtensionQueryResult;
import com.zrlog.plugin.message.ArticleExtensionResult;
import com.zrlog.plugin.message.ArticleExtensionSetRequest;
import com.zrlog.plugincore.server.dao.ArticleDAO;
import com.zrlog.plugincore.server.dao.WebSiteDAO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class ArticleExtensionRepository {

    public static final String FILTER_FEATURE_KEY = "feature_article_extension_filter_enabled";

    private static final Object WRITE_LOCK = new Object();
    private static final Gson GSON = new Gson();
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern PATH_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");
    private static final int MAX_JSON_LENGTH = 256 * 1024;
    private static final int MAX_INDEXED_PATHS = 32;
    private static final int MAX_FILTERS = 8;
    private static final int MAX_FILTER_VALUES = 64;
    private static final int MAX_INDEX_VALUES_PER_PATH = 128;
    private static final int MAX_INDEX_VALUE_LENGTH = 512;
    private static final long MAX_PAGE_SIZE = 200;

    public ArticleExtensionResult get(long articleId, String namespace) {
        try {
            validateArticleId(articleId);
            validateNamespace(namespace);
            Map<String, Object> values = namespaceValues(loadExtensions(articleId), namespace);
            return ArticleExtensionResult.success(articleId, namespace, values);
        } catch (IllegalArgumentException | SQLException e) {
            return ArticleExtensionResult.error(e.getMessage());
        }
    }

    public ArticleExtensionResult set(String namespace, ArticleExtensionSetRequest request) {
        if (request == null || request.getArticleId() == null) {
            return ArticleExtensionResult.error("articleId is required");
        }
        try {
            long articleId = request.getArticleId();
            validateArticleId(articleId);
            validateNamespace(namespace);
            List<String> indexedPaths = normalizeIndexedPaths(request.getIndexedPaths());
            Map<String, Object> values = request.getValues() == null
                    ? Collections.emptyMap()
                    : request.getValues();
            JsonElement namespaceElement = GSON.toJsonTree(values);
            if (!namespaceElement.isJsonObject()) {
                throw new IllegalArgumentException("values must be a JSON object");
            }
            String namespaceJson = GSON.toJson(namespaceElement);
            if (namespaceJson.length() > MAX_JSON_LENGTH) {
                throw new IllegalArgumentException("extension data exceeds 256 KiB");
            }
            synchronized (WRITE_LOCK) {
                try (Connection connection = DAO.getDefaultDataSource().getConnection()) {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    connection.setAutoCommit(false);
                    try {
                        JsonObject root = loadExtensions(connection, articleId);
                        if (values.isEmpty()) {
                            root.remove(namespace);
                        } else {
                            root.add(namespace, namespaceElement);
                        }
                        String storedJson = root.size() == 0 ? null : GSON.toJson(root);
                        updateExtensions(connection, articleId, storedJson);
                        replaceIndex(connection, articleId, namespace,
                                namespaceElement.getAsJsonObject(), indexedPaths);
                        connection.commit();
                    } catch (RuntimeException | SQLException e) {
                        connection.rollback();
                        throw e;
                    }
                }
            }
            return ArticleExtensionResult.success(articleId, namespace, values);
        } catch (IllegalArgumentException | SQLException e) {
            return ArticleExtensionResult.error(e.getMessage());
        }
    }

    public ArticleExtensionQueryResult query(String namespace, ArticleExtensionQueryRequest request) {
        try {
            validateNamespace(namespace);
            if (!isFilterEnabled()) {
                return ArticleExtensionQueryResult.error("article extension filtering is disabled");
            }
            List<ArticleExtensionFilter> filters = normalizeFilters(request == null ? null : request.getFilters());
            if (filters.isEmpty()) {
                throw new IllegalArgumentException("at least one extension filter is required");
            }
            long page = normalizePage(request == null ? null : request.getPage());
            long size = normalizeSize(request == null ? null : request.getSize());
            QueryParts queryParts = buildQuery(namespace, filters);
            ArticleDAO dao = new ArticleDAO();
            Number totalValue = (Number) dao.queryFirstObj(
                    "select count(distinct l.logId) " + queryParts.fromAndWhere,
                    queryParts.params.toArray());
            long total = totalValue == null ? 0 : totalValue.longValue();
            List<Object> pageParams = new ArrayList<>(queryParts.params);
            pageParams.add(size);
            pageParams.add((page - 1) * size);
            List<Map<String, Object>> rows = dao.queryListWithParams(
                    "select distinct l.logId as id,l.title,l.alias,l.extensions "
                            + queryParts.fromAndWhere
                            + " order by l.logId desc limit ? offset ?",
                    pageParams.toArray());
            List<ArticleExtensionArticle> articles = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                ArticleExtensionArticle article = new ArticleExtensionArticle();
                Object id = valueIgnoreCase(row, "id");
                article.setId(id instanceof Number ? ((Number) id).longValue() : Long.valueOf(String.valueOf(id)));
                article.setTitle(stringValue(valueIgnoreCase(row, "title")));
                article.setAlias(stringValue(valueIgnoreCase(row, "alias")));
                article.setExtensions(namespaceValues(
                        loadJsonObject(valueIgnoreCase(row, "extensions")), namespace));
                articles.add(article);
            }
            return ArticleExtensionQueryResult.success(page, size, total, articles);
        } catch (IllegalArgumentException | SQLException e) {
            return ArticleExtensionQueryResult.error(e.getMessage());
        }
    }

    public boolean isFilterEnabled() throws SQLException {
        Object value = new WebSiteDAO().queryValueByName(FILTER_FEATURE_KEY);
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private JsonObject loadExtensions(long articleId) throws SQLException {
        Map<String, Object> row = new ArticleDAO().queryFirstWithParams(
                "select extensions from log where logId=?", articleId);
        if (row == null) {
            throw new IllegalArgumentException("article not found: " + articleId);
        }
        return loadJsonObject(valueIgnoreCase(row, "extensions"));
    }

    private JsonObject loadExtensions(Connection connection, long articleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select extensions from log where logId=?")) {
            statement.setLong(1, articleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("article not found: " + articleId);
                }
                return loadJsonObject(resultSet.getObject(1));
            }
        }
    }

    private void updateExtensions(Connection connection, long articleId, String storedJson)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update log set extensions=? where logId=?")) {
            statement.setString(1, storedJson);
            statement.setLong(2, articleId);
            statement.executeUpdate();
        }
    }

    private JsonObject loadJsonObject(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(String.valueOf(value));
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private void replaceIndex(Connection connection, long articleId, String namespace,
                              JsonObject namespaceValues, List<String> indexedPaths)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "delete from log_extension_index where log_id=? and namespace=?")) {
            delete.setLong(1, articleId);
            delete.setString(2, namespace);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into log_extension_index"
                        + "(log_id,namespace,extension_path,extension_value) values(?,?,?,?)")) {
            for (String path : indexedPaths) {
                Set<String> values = indexValues(resolvePath(namespaceValues, path));
                for (String value : values) {
                    insert.setLong(1, articleId);
                    insert.setString(2, namespace);
                    insert.setString(3, path);
                    insert.setString(4, value);
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private JsonElement resolvePath(JsonObject values, String path) {
        JsonElement current = values;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(segment);
        }
        return current;
    }

    private Set<String> indexValues(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<>();
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                addIndexValue(values, item);
                if (values.size() > MAX_INDEX_VALUES_PER_PATH) {
                    throw new IllegalArgumentException(
                            "too many values for indexed extension path");
                }
            }
        } else {
            addIndexValue(values, element);
        }
        return values;
    }

    private void addIndexValue(Set<String> values, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException("indexed extension values must be scalar");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        String value;
        if (primitive.isNumber()) {
            value = new BigDecimal(primitive.getAsString()).stripTrailingZeros().toPlainString();
        } else {
            value = primitive.getAsString();
        }
        if (value.length() > MAX_INDEX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "indexed extension value exceeds 512 characters");
        }
        values.add(value);
    }

    private QueryParts buildQuery(String namespace, List<ArticleExtensionFilter> filters) {
        StringBuilder sql = new StringBuilder("from log l ");
        List<Object> params = new ArrayList<>();
        for (int index = 0; index < filters.size(); index++) {
            ArticleExtensionFilter filter = filters.get(index);
            String alias = "e" + index;
            sql.append("inner join log_extension_index ").append(alias)
                    .append(" on ").append(alias).append(".log_id=l.logId")
                    .append(" and ").append(alias).append(".namespace=?")
                    .append(" and ").append(alias).append(".extension_path=?");
            params.add(namespace);
            params.add(filter.getPath());
            if (filter.getValues() != null && !filter.getValues().isEmpty()) {
                sql.append(" and ").append(alias).append(".extension_value in (");
                for (int valueIndex = 0; valueIndex < filter.getValues().size(); valueIndex++) {
                    if (valueIndex > 0) {
                        sql.append(",");
                    }
                    sql.append("?");
                    params.add(normalizeFilterValue(filter.getValues().get(valueIndex)));
                }
                sql.append(")");
            }
            sql.append(" ");
        }
        return new QueryParts(sql.toString(), params);
    }

    private List<ArticleExtensionFilter> normalizeFilters(List<ArticleExtensionFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return Collections.emptyList();
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("too many extension filters");
        }
        List<ArticleExtensionFilter> normalized = new ArrayList<>();
        for (ArticleExtensionFilter filter : filters) {
            if (filter == null) {
                throw new IllegalArgumentException("extension filter is required");
            }
            validatePath(filter.getPath());
            List<String> values = filter.getValues() == null
                    ? Collections.emptyList()
                    : filter.getValues();
            if (values.size() > MAX_FILTER_VALUES) {
                throw new IllegalArgumentException("too many values for extension filter " + filter.getPath());
            }
            filter.setValues(new ArrayList<>(values));
            normalized.add(filter);
        }
        return normalized;
    }

    private List<String> normalizeIndexedPaths(List<String> indexedPaths) {
        if (indexedPaths == null || indexedPaths.isEmpty()) {
            return Collections.emptyList();
        }
        if (indexedPaths.size() > MAX_INDEXED_PATHS) {
            throw new IllegalArgumentException("too many indexed extension paths");
        }
        LinkedHashSet<String> uniquePaths = new LinkedHashSet<>();
        for (String path : indexedPaths) {
            validatePath(path);
            uniquePaths.add(path);
        }
        return new ArrayList<>(uniquePaths);
    }

    private void validateArticleId(long articleId) {
        if (articleId <= 0) {
            throw new IllegalArgumentException("articleId must be positive");
        }
    }

    private void validateNamespace(String namespace) {
        if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid article extension namespace");
        }
    }

    private void validatePath(String path) {
        if (path == null || path.length() > 191 || !PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("invalid article extension path: " + path);
        }
    }

    private String normalizeFilterValue(String value) {
        if (value == null || value.length() > MAX_INDEX_VALUE_LENGTH) {
            throw new IllegalArgumentException("invalid article extension filter value");
        }
        return value;
    }

    private long normalizePage(Long page) {
        return page == null || page < 1 ? 1 : page;
    }

    private long normalizeSize(Long size) {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> namespaceValues(JsonObject root, String namespace) {
        JsonElement value = root.get(namespace);
        if (value == null || !value.isJsonObject()) {
            return Collections.emptyMap();
        }
        return GSON.fromJson(value, LinkedHashMap.class);
    }

    private Object valueIgnoreCase(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT)
                    .equals(key.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return Objects.toString(value, "");
    }

    private static final class QueryParts {
        private final String fromAndWhere;
        private final List<Object> params;

        private QueryParts(String fromAndWhere, List<Object> params) {
            this.fromAndWhere = fromAndWhere;
            this.params = params;
        }
    }
}
