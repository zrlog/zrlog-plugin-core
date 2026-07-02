package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.google.gson.Gson;
import com.zrlog.plugin.message.DbPropertiesResponse;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PluginTransportModelsTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldKeepLegacyTransportJsonFields() {
        assertEquals("email",
                gson.fromJson("{\"name\":\"email\"}", PluginTransportModels.ServiceRequest.class).getName());
        assertEquals("host",
                gson.fromJson("{\"key\":\"title,host\"}", PluginTransportModels.WebsiteLoadRequest.class).rawKeys()[1]);
        PluginTransportModels.WebsiteSyncOptions syncOptions = gson.fromJson(
                "{\"syncTemplate\":\"true\",\"host\":\"https://static.example\",\"folder\":\"assets\"}",
                PluginTransportModels.WebsiteSyncOptions.class);
        assertEquals(true, syncOptions.hasSyncTemplate());
        assertEquals(true, syncOptions.isSyncTemplateEnabled());
        assertEquals("assets", syncOptions.getFolder());
        assertEquals("hello-world",
                gson.fromJson("{\"alias\":\"hello-world\"}", PluginTransportModels.ArticleVisitRequest.class).getAlias());
        assertEquals("{\"code\":1,\"message\":\"failed\"}",
                gson.toJson(PluginTransportModels.ServiceErrorResponse.error("failed")));
        assertEquals("{\"runType\":\"BLOG\"}",
                gson.toJson(new PluginTransportModels.InitResponse("BLOG")));
        assertEquals("{\"success\":false,\"message\":\"Plugin init failed\"}",
                gson.toJson(PluginTransportModels.InitErrorResponse.error(null)));
        assertEquals("{\"result\":true}",
                gson.toJson(PluginTransportModels.OperationResult.success(true)));
        assertEquals("{\"result\":false,\"message\":\"failed\"}",
                gson.toJson(PluginTransportModels.OperationResult.error("failed")));
        assertEquals("{\"dbProperties\":\"/tmp/db.properties\"}",
                gson.toJson(new DbPropertiesResponse("/tmp/db.properties")));
    }
}
