package com.zrlog.plugincore.server.util;

import com.zrlog.plugin.common.type.HttpMethod;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PluginInternalRequestAuth {
    private static final String COMMENT_ANALYZE_PATH = "/api/admin/internal/ai/comment/analyze";

    private PluginInternalRequestAuth() { }

    public static void authorize(BaseHttpRequestInfo request, String hostApiBaseUrl, String pluginToken) {
        if (!isCommentAnalysisRequest(request, hostApiBaseUrl)) return;
        if (pluginToken == null || pluginToken.isEmpty()) {
            throw new IllegalStateException("Host plugin authentication is unavailable");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (request.getHeader() != null) {
            request.getHeader().forEach((name, value) -> {
                if (!"X-Plugin-Token".equalsIgnoreCase(name) && !"Cookie".equalsIgnoreCase(name)
                        && !"Authorization".equalsIgnoreCase(name) && !"X-ZrLog-Admin-Token".equalsIgnoreCase(name)) {
                    headers.put(name, value);
                }
            });
        }
        headers.put("X-Plugin-Token", pluginToken);
        request.setHeader(headers);
    }

    private static boolean isCommentAnalysisRequest(BaseHttpRequestInfo request, String baseUrl) {
        if (request == null || request.getHttpMethod() != HttpMethod.POST || baseUrl == null || request.getAccessUrl() == null) return false;
        try {
            URI base = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) return false;
            URI expected = URI.create(baseUrl.replaceAll("/+$", "") + COMMENT_ANALYZE_PATH);
            return expected.equals(URI.create(request.getAccessUrl()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
