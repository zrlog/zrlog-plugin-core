package com.zrlog.plugincore.server.util;

import com.zrlog.plugin.common.type.HttpMethod;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PluginInternalRequestAuthTest {
    private static final String BASE = "http://127.0.0.1:8080/blog";
    private static final String PATH = "/api/admin/internal/ai/comment/analyze";

    private BaseHttpRequestInfo request(String url) {
        BaseHttpRequestInfo request = new BaseHttpRequestInfo();
        request.setHttpMethod(HttpMethod.POST);
        request.setAccessUrl(url);
        request.setHeader(new HashMap<>());
        request.getHeader().put("Content-Type", "application/json");
        return request;
    }

    @Test public void injectsOnlyHostTokenAndRemovesUserCredentials() {
        BaseHttpRequestInfo request = request(BASE + PATH);
        request.getHeader().put("x-plugin-token", "untrusted");
        request.getHeader().put("COOKIE", "user-session");
        request.getHeader().put("Authorization", "Bearer user-session");
        request.getHeader().put("X-ZrLog-Admin-Token", "user-token");
        PluginInternalRequestAuth.authorize(request, BASE + "/", "internal-token");
        assertEquals(Map.of("Content-Type", "application/json", "X-Plugin-Token", "internal-token"), request.getHeader());
    }

    @Test public void neverAddsTokenToOtherOriginsPathsOrMethods() {
        for (String url : new String[]{"https://external.example" + PATH, "http://127.0.0.1:8081/blog" + PATH,
                "https://127.0.0.1:8080/blog" + PATH, BASE + PATH + "/", BASE + PATH + "?redirect=https://external.example",
                BASE + PATH + "#fragment", "http://user@127.0.0.1:8080/blog" + PATH,
                BASE + "/other/../" + PATH.substring(1), BASE + "/api/admin/refreshCache", BASE + "/api/admin/website/ai"}) {
            BaseHttpRequestInfo request = request(url);
            PluginInternalRequestAuth.authorize(request, BASE, "internal-token");
            assertFalse(url, request.getHeader().containsKey("X-Plugin-Token"));
        }
        BaseHttpRequestInfo request = request(BASE + PATH);
        request.setHttpMethod(HttpMethod.GET);
        PluginInternalRequestAuth.authorize(request, BASE, "internal-token");
        assertFalse(request.getHeader().containsKey("X-Plugin-Token"));
    }

    @Test public void refusesMissingHostTokenInsteadOfForwardingUserAuthentication() {
        BaseHttpRequestInfo request = request(BASE + PATH);
        assertThrows(IllegalStateException.class, () -> PluginInternalRequestAuth.authorize(request, BASE, null));
        assertThrows(IllegalStateException.class, () -> PluginInternalRequestAuth.authorize(request, BASE, ""));
    }

    @Test public void handlesUnconfiguredHostAndPreservesUnrelatedRequestHeaders() {
        BaseHttpRequestInfo request = request("https://storage.example/upload");
        request.getHeader().put("Authorization", "storage-auth");
        PluginInternalRequestAuth.authorize(request, BASE, "internal-token");
        assertEquals("storage-auth", request.getHeader().get("Authorization"));
        PluginInternalRequestAuth.authorize(request, null, "internal-token");
        assertFalse(request.getHeader().containsKey("X-Plugin-Token"));
    }
}
