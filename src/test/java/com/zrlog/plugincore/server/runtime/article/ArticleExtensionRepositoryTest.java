package com.zrlog.plugincore.server.runtime.article;

import com.zrlog.plugin.message.ArticleExtensionFilter;
import com.zrlog.plugin.message.ArticleExtensionQueryRequest;
import com.zrlog.plugin.message.ArticleExtensionQueryResult;
import com.zrlog.plugin.message.ArticleExtensionResult;
import com.zrlog.plugin.message.ArticleExtensionSetRequest;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArticleExtensionRepositoryTest {

    @Test
    public void shouldIsolateNamespacesAndQueryDeclaredIndexesWhenEnabled() throws Exception {
        try (InMemoryPluginCoreDatabase db = InMemoryPluginCoreDatabase.open()) {
            db.update("insert into log(logId,title,alias) values(?,?,?)", 1, "Article", "article");
            db.update("insert into website(name,value) values(?,?)",
                    ArticleExtensionRepository.FILTER_FEATURE_KEY, "true");
            ArticleExtensionRepository repository = new ArticleExtensionRepository();

            ArticleExtensionResult metadataResult = repository.set("metadata",
                    setRequest(1L, mapOf("resourceIds", Arrays.asList("asset-1", "asset-2"),
                            "priority", 3), Arrays.asList("resourceIds", "priority")));
            ArticleExtensionResult catalogResult = repository.set("catalog",
                    setRequest(1L, mapOf("featured", true), Collections.singletonList("featured")));

            assertTrue(metadataResult.isSuccess());
            assertTrue(catalogResult.isSuccess());
            assertEquals(Arrays.asList("asset-1", "asset-2"),
                    repository.get(1, "metadata").getValues().get("resourceIds"));
            assertEquals(Boolean.TRUE, repository.get(1, "catalog").getValues().get("featured"));

            ArticleExtensionQueryResult query = repository.query("metadata",
                    queryRequest("resourceIds", Collections.singletonList("asset-1")));
            assertTrue(query.isSuccess());
            assertEquals(Long.valueOf(1), query.getTotal());
            assertEquals("Article", query.getRows().get(0).getTitle());
            assertEquals(Arrays.asList("asset-1", "asset-2"),
                    query.getRows().get(0).getExtensions().get("resourceIds"));
            assertFalse(query.getRows().get(0).getExtensions().containsKey("catalog"));

            ArticleExtensionQueryResult exists = repository.query("metadata",
                    queryRequest("priority", Collections.emptyList()));
            assertEquals(Long.valueOf(1), exists.getTotal());
        }
    }

    @Test
    public void shouldKeepWritesAvailableWhileExperimentalFilteringIsDisabled() throws Exception {
        try (InMemoryPluginCoreDatabase db = InMemoryPluginCoreDatabase.open()) {
            db.update("insert into log(logId,title,alias) values(?,?,?)", 1, "Article", "article");
            ArticleExtensionRepository repository = new ArticleExtensionRepository();

            ArticleExtensionResult result = repository.set("metadata",
                    setRequest(1L, mapOf("resourceIds", Collections.singletonList("asset-1")),
                            Collections.singletonList("resourceIds")));
            ArticleExtensionQueryResult query = repository.query("metadata",
                    queryRequest("resourceIds", Collections.singletonList("asset-1")));

            assertTrue(result.isSuccess());
            assertFalse(query.isSuccess());
            assertEquals("article extension filtering is disabled", query.getErrorMessage());
            assertEquals(Collections.singletonList("asset-1"),
                    repository.get(1, "metadata").getValues().get("resourceIds"));
        }
    }

    @Test
    public void shouldRollbackJsonWhenIndexRefreshFails() throws Exception {
        try (InMemoryPluginCoreDatabase db = InMemoryPluginCoreDatabase.open()) {
            db.update("insert into log(logId,title,alias) values(?,?,?)", 1, "Article", "article");
            ArticleExtensionRepository repository = new ArticleExtensionRepository();
            assertTrue(repository.set("metadata",
                    setRequest(1L, mapOf("resourceIds", Collections.singletonList("asset-1")),
                            Collections.singletonList("resourceIds"))).isSuccess());
            db.update("drop table log_extension_index");

            ArticleExtensionResult failed = repository.set("metadata",
                    setRequest(1L, mapOf("resourceIds", Collections.singletonList("asset-2")),
                            Collections.singletonList("resourceIds")));

            assertFalse(failed.isSuccess());
            assertEquals(Collections.singletonList("asset-1"),
                    repository.get(1, "metadata").getValues().get("resourceIds"));
        }
    }

    @Test
    public void shouldRejectIndexValuesThatCannotBeQueriedWithoutChangingJson() throws Exception {
        try (InMemoryPluginCoreDatabase db = InMemoryPluginCoreDatabase.open()) {
            db.update("insert into log(logId,title,alias) values(?,?,?)", 1, "Article", "article");
            ArticleExtensionRepository repository = new ArticleExtensionRepository();
            assertTrue(repository.set("metadata",
                    setRequest(1L, mapOf("resourceIds", Collections.singletonList("asset-1")),
                            Collections.singletonList("resourceIds"))).isSuccess());

            ArticleExtensionResult failed = repository.set("metadata",
                    setRequest(1L, mapOf("resourceIds", Collections.singletonList(repeat("x", 513))),
                            Collections.singletonList("resourceIds")));

            assertFalse(failed.isSuccess());
            assertEquals("indexed extension value exceeds 512 characters", failed.getErrorMessage());
            assertEquals(Collections.singletonList("asset-1"),
                    repository.get(1, "metadata").getValues().get("resourceIds"));
        }
    }

    private static ArticleExtensionSetRequest setRequest(Long articleId, Map<String, Object> values,
                                                         List<String> indexedPaths) {
        ArticleExtensionSetRequest request = new ArticleExtensionSetRequest();
        request.setArticleId(articleId);
        request.setValues(values);
        request.setIndexedPaths(indexedPaths);
        return request;
    }

    private static ArticleExtensionQueryRequest queryRequest(String path, List<String> values) {
        ArticleExtensionFilter filter = new ArticleExtensionFilter();
        filter.setPath(path);
        filter.setValues(values);
        ArticleExtensionQueryRequest request = new ArticleExtensionQueryRequest();
        request.setFilters(Collections.singletonList(filter));
        request.setPage(1L);
        request.setSize(20L);
        return request;
    }

    private static Map<String, Object> mapOf(String firstKey, Object firstValue,
                                             String secondKey, Object secondValue) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
