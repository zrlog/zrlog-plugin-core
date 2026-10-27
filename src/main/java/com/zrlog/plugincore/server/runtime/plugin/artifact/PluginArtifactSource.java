package com.zrlog.plugincore.server.runtime.plugin.artifact;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source allowlisting and credential selection are deliberately separate decisions. */
public final class PluginArtifactSource {
    private final URI uri;
    private final boolean privateResource;

    private PluginArtifactSource(URI uri, boolean privateResource) {
        this.uri = uri;
        this.privateResource = privateResource;
    }

    public static PluginArtifactSource resolve(String source, String apiHomeUrl) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source");
        URI value = URI.create(source);
        if (value.isAbsolute()) {
            storeFileName(source);
            return new PluginArtifactSource(value, false);
        }
        safePath(value);
        URI home = URI.create(apiHomeUrl.replaceAll("/+$", ""));
        if (home.getHost() == null || home.getUserInfo() != null || home.getQuery() != null || home.getFragment() != null
                || !("http".equals(home.getScheme()) || "https".equals(home.getScheme()))) {
            throw new IllegalArgumentException("apiHomeUrl");
        }
        String path = value.getPath();
        String context = home.getPath();
        if (!context.isEmpty() && path.startsWith(context + "/")) path = path.substring(context.length());
        if (!path.startsWith("/admin/attached/tmp/") || path.endsWith("/") || value.getQuery() != null) {
            throw new IllegalArgumentException("source");
        }
        return new PluginArtifactSource(URI.create(home + "/api/admin/upload/resource?path="
                + URLEncoder.encode(path, StandardCharsets.UTF_8)), true);
    }

    public static String storeFileName(String source) {
        URI value = URI.create(source);
        safePath(value);
        if (!"https".equals(value.getScheme()) || !"dl.zrlog.com".equalsIgnoreCase(value.getHost())
                || (value.getPort() != -1 && value.getPort() != 443) || !value.getPath().startsWith("/plugin/")) {
            throw new IllegalArgumentException("source");
        }
        String name = value.getPath().substring("/plugin/".length());
        if (!PluginInstallService.validName(name)) throw new IllegalArgumentException("fileName");
        return name;
    }

    private static void safePath(URI value) {
        if (value.getUserInfo() != null || value.getFragment() != null
                || (!value.isAbsolute() && value.getRawAuthority() != null)
                || value.getRawPath() == null || value.getRawPath().contains("%")
                || value.getPath().contains("//") || !value.normalize().equals(value)) {
            throw new IllegalArgumentException("source");
        }
    }

    public URI uri() { return uri; }

    public Map<String, String> headers(Map<String, String> incoming) {
        if (!privateResource || incoming == null) return Collections.emptyMap();
        Map<String, String> headers = new LinkedHashMap<>();
        incoming.forEach((key, value) -> {
            if (value != null && ("Cookie".equalsIgnoreCase(key) || "Authorization".equalsIgnoreCase(key))) {
                headers.put(key, value);
            }
        });
        return headers;
    }
}
