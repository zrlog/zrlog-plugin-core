package com.zrlog.plugincore.server.runtime.pwa;

import com.google.gson.Gson;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpResponse;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import com.zrlog.plugincore.server.util.AdminTheme;
import com.zrlog.plugincore.server.util.PublicInfoLoader;
import com.zrlog.plugincore.server.web.handler.PluginHandle;
import com.zrlog.plugincore.server.web.handler.PluginRequestUriInfo;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

public class PluginPwaResources {

    static final String MANIFEST_WEBMANIFEST = "manifest.webmanifest";
    static final String MANIFEST_JSON = "manifest.json";
    static final String SERVICE_WORKER = "pwa-sw.js";
    static final String ICON = "pwa-icon";

    private static final String MANIFEST_CONTENT_TYPE = "application/manifest+json; charset=utf-8";
    private static final String JAVASCRIPT_CONTENT_TYPE = "application/javascript; charset=utf-8";
    private static final String DEFAULT_ICON_CONTENT_TYPE = "image/png";
    private static final Gson GSON = new Gson();

    public boolean renderIfMatched(Plugin plugin,
                                   PluginRequestUriInfo requestUriInfo,
                                   HttpRequest request,
                                   HttpResponse response) {
        if (plugin == null || requestUriInfo == null || response == null) {
            return false;
        }
        String action = normalizeAction(requestUriInfo.getAction());
        if (!isPwaResource(action)) {
            return false;
        }
        String basePath = pluginBasePath(requestUriInfo.getName(), PluginRuntimeBridge.hostConnection().getContextPath());
        if (isManifest(action)) {
            write(response, MANIFEST_CONTENT_TYPE, GSON.toJson(manifest(plugin, basePath, AdminTheme.fromRequest(request))));
            return true;
        }
        if (SERVICE_WORKER.equals(action)) {
            write(response, JAVASCRIPT_CONTENT_TYPE, serviceWorker(basePath));
            return true;
        }
        PreviewIcon icon = previewIcon(plugin.getPreviewImageBase64());
        if (icon == null) {
            return false;
        }
        response.addHeader("Content-Type", icon.getContentType());
        response.addHeader("Cache-Control", "max-age=86400");
        response.write(new ByteArrayInputStream(icon.getBytes()), 200);
        return true;
    }

    public PluginPwaManifest manifest(Plugin plugin, String basePath) {
        return manifest(plugin, basePath, new AdminTheme(false, PublicInfoLoader.DEFAULT_ADMIN_COLOR_PRIMARY));
    }

    public PluginPwaManifest manifest(Plugin plugin, String basePath, AdminTheme theme) {
        String normalizedBasePath = ensureTrailingSlash(isBlank(basePath) ? "/" : basePath);
        AdminTheme adminTheme = theme == null ? new AdminTheme(false, PublicInfoLoader.DEFAULT_ADMIN_COLOR_PRIMARY) : theme;
        PluginPwaManifest manifest = new PluginPwaManifest();
        manifest.setId(normalizedBasePath);
        manifest.setName(firstNonBlank(plugin.getName(), plugin.getShortName()));
        manifest.setShortName(firstNonBlank(plugin.getShortName(), plugin.getName()));
        if (!isBlank(plugin.getDesc())) {
            manifest.setDescription(plugin.getDesc());
        }
        manifest.setStartUrl(normalizedBasePath);
        manifest.setScope(normalizedBasePath);
        manifest.setDisplay("standalone");
        manifest.setThemeColor(adminTheme.getAdminColorPrimary());
        manifest.setBackgroundColor(adminTheme.isDarkMode() ? "#000000" : "#FFFFFF");
        PreviewIcon icon = previewIcon(plugin.getPreviewImageBase64());
        if (icon != null) {
            manifest.setIcons(Collections.singletonList(iconManifestEntry(normalizedBasePath, icon)));
        }
        return manifest;
    }

    public String serviceWorker(String basePath) {
        String scope = ensureTrailingSlash(isBlank(basePath) ? "/" : basePath);
        return "'use strict';\n"
                + "const ZRLOG_PLUGIN_PWA_SCOPE = " + GSON.toJson(scope) + ";\n"
                + "self.addEventListener('install', function () {\n"
                + "  self.skipWaiting();\n"
                + "});\n"
                + "self.addEventListener('activate', function (event) {\n"
                + "  event.waitUntil(self.clients.claim());\n"
                + "});\n";
    }

    public static boolean isPwaResource(String action) {
        String normalized = normalizeAction(action);
        return isManifest(normalized) || SERVICE_WORKER.equals(normalized) || ICON.equals(normalized);
    }

    static boolean isManifest(String action) {
        String normalized = normalizeAction(action);
        return MANIFEST_WEBMANIFEST.equals(normalized) || MANIFEST_JSON.equals(normalized);
    }

    static String pluginBasePath(String pluginShortName, String contextPath) {
        return ensureTrailingSlash(PluginHostConnection.normalizeContextPath(contextPath)
                + PluginHandle.OLD_PATH + "/" + trimSlashes(pluginShortName));
    }

    static PreviewIcon previewIcon(String previewImageBase64) {
        if (isBlank(previewImageBase64)) {
            return null;
        }
        String value = previewImageBase64.trim();
        if (value.startsWith("data:")) {
            return dataUrlIcon(value);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            return new PreviewIcon(detectContentType(bytes), bytes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PreviewIcon dataUrlIcon(String value) {
        int commaIndex = value.indexOf(',');
        if (commaIndex < 0) {
            return null;
        }
        String meta = value.substring("data:".length(), commaIndex);
        String payload = value.substring(commaIndex + 1);
        String contentType = contentType(meta);
        try {
            byte[] bytes;
            if (meta.toLowerCase().contains(";base64")) {
                bytes = Base64.getDecoder().decode(payload);
            } else {
                bytes = URLDecoder.decode(payload, StandardCharsets.UTF_8.name()).getBytes(StandardCharsets.UTF_8);
            }
            return new PreviewIcon(contentType, bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static PluginPwaManifest.Icon iconManifestEntry(String basePath, PreviewIcon icon) {
        PluginPwaManifest.Icon item = new PluginPwaManifest.Icon();
        item.setSrc(ensureTrailingSlash(basePath) + ICON);
        item.setSizes("image/svg+xml".equals(icon.getContentType()) ? "any" : "512x512");
        item.setType(icon.getContentType());
        return item;
    }

    private static String contentType(String dataUrlMeta) {
        if (isBlank(dataUrlMeta)) {
            return DEFAULT_ICON_CONTENT_TYPE;
        }
        String[] tokens = dataUrlMeta.split(";");
        if (tokens.length == 0 || isBlank(tokens[0])) {
            return DEFAULT_ICON_CONTENT_TYPE;
        }
        return tokens[0];
    }

    private static String detectContentType(byte[] bytes) {
        String text = new String(bytes, 0, Math.min(bytes.length, 128), StandardCharsets.UTF_8).trim();
        if (text.startsWith("<svg")) {
            return "image/svg+xml";
        }
        return DEFAULT_ICON_CONTENT_TYPE;
    }

    private static void write(HttpResponse response, String contentType, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        response.addHeader("Content-Type", contentType);
        response.addHeader("Cache-Control", "no-cache");
        response.write(new ByteArrayInputStream(bytes), 200);
    }

    private static String normalizeAction(String action) {
        String value = pathPart(action);
        return trimLeadingSlashes(value);
    }

    private static String pathPart(String uri) {
        if (uri == null) {
            return "";
        }
        int queryIndex = uri.indexOf('?');
        if (queryIndex < 0) {
            return uri;
        }
        return uri.substring(0, queryIndex);
    }

    private static String trimLeadingSlashes(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private static String trimSlashes(String value) {
        String result = trimLeadingSlashes(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String ensureTrailingSlash(String value) {
        if (isBlank(value)) {
            return "/";
        }
        return value.endsWith("/") ? value : value + "/";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static class PreviewIcon {
        private final String contentType;
        private final byte[] bytes;

        PreviewIcon(String contentType, byte[] bytes) {
            this.contentType = contentType;
            this.bytes = bytes;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }
}
