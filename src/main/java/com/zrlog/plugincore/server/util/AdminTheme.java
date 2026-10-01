package com.zrlog.plugincore.server.util;

import com.hibegin.http.server.api.HttpRequest;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.model.PublicInfo;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AdminTheme {

    public static final String DARK_MODE_HEADER = BaseHttpRequestInfo.DARK_MODE_HEADER;
    public static final String ADMIN_COLOR_PRIMARY_HEADER = BaseHttpRequestInfo.ADMIN_COLOR_PRIMARY_HEADER;
    public static final String ADMIN_THEME_HEADER = "Admin-Theme";
    public static final String ADMIN_COMPACT_MODE_HEADER = "Admin-Compact-Mode";

    private static final Logger LOGGER = LoggerUtil.getLogger(AdminTheme.class);

    private final boolean darkMode;
    private final String adminColorPrimary;
    private final String theme;
    private final boolean compactMode;

    public AdminTheme(boolean darkMode, String adminColorPrimary) {
        this(darkMode, adminColorPrimary, "default", false);
    }

    public AdminTheme(boolean darkMode, String adminColorPrimary, String theme, boolean compactMode) {
        this.darkMode = darkMode;
        this.adminColorPrimary = normalizeAdminColorPrimary(adminColorPrimary);
        this.theme = isBlank(theme) ? "default" : theme.trim();
        this.compactMode = compactMode;
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public String getAdminColorPrimary() {
        return adminColorPrimary;
    }

    public String getTheme() {
        return theme;
    }

    public boolean isCompactMode() {
        return compactMode;
    }

    public static AdminTheme fromRequest(HttpRequest request) {
        Map<String, String> headers = request == null ? null : request.getHeaderMap();
        AdminTheme fallback = missingThemeHeader(headers) ? loadThemeSilently() : null;
        return fromHeadersWithFallback(headers, fallback);
    }

    public static Map<String, String> copyHeadersWithFallback(HttpRequest request) {
        Map<String, String> headers = ClientIpHeaders.copyHeadersWithRealIp(request);
        AdminTheme theme = fromRequest(request);
        theme.putMissingHeaders(headers);
        return headers;
    }

    public static void applyTo(BaseHttpRequestInfo requestInfo, HttpRequest request) {
        if (requestInfo == null) {
            return;
        }
        AdminTheme theme = fromRequest(request);
        requestInfo.setHeader(copyHeadersWithFallback(request, theme));
        theme.applyTo(requestInfo);
    }

    public static AdminTheme fromHeaders(Map<String, String> headers, PublicInfo fallback) {
        return fromHeadersWithFallback(headers,
                new AdminTheme(publicInfoDarkMode(fallback), publicInfoAdminColorPrimary(fallback)));
    }

    static AdminTheme fromHeadersWithFallback(Map<String, String> headers, AdminTheme fallback) {
        AdminTheme defaults = fallback == null ? new AdminTheme(false, null) : fallback;
        String darkModeHeader = headerValue(headers, DARK_MODE_HEADER, "Dark_mode", "dark_mode");
        String adminColorPrimaryHeader = headerValue(headers, ADMIN_COLOR_PRIMARY_HEADER, "admin_color_Primary", "admin_color_primary");
        boolean darkMode = isBlank(darkModeHeader) ? defaults.isDarkMode() : BooleanUtils.isTrue(darkModeHeader);
        String adminColorPrimary = isBlank(adminColorPrimaryHeader) ? defaults.getAdminColorPrimary() : adminColorPrimaryHeader;
        String theme = headerValue(headers, ADMIN_THEME_HEADER);
        String compactMode = headerValue(headers, ADMIN_COMPACT_MODE_HEADER);
        return new AdminTheme(darkMode, adminColorPrimary, isBlank(theme) ? defaults.getTheme() : theme,
                isBlank(compactMode) ? defaults.isCompactMode() : BooleanUtils.isTrue(compactMode));
    }

    public void putMissingHeaders(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        if (isBlank(headers.get(DARK_MODE_HEADER))) {
            headers.put(DARK_MODE_HEADER, darkMode + "");
        }
        if (isBlank(headers.get(ADMIN_COLOR_PRIMARY_HEADER))) {
            headers.put(ADMIN_COLOR_PRIMARY_HEADER, adminColorPrimary);
        }
        if (isBlank(headers.get(ADMIN_THEME_HEADER))) {
            headers.put(ADMIN_THEME_HEADER, theme);
        }
        if (isBlank(headers.get(ADMIN_COMPACT_MODE_HEADER))) {
            headers.put(ADMIN_COMPACT_MODE_HEADER, Boolean.toString(compactMode));
        }
    }

    public void applyTo(BaseHttpRequestInfo requestInfo) {
        if (requestInfo == null) {
            return;
        }
        requestInfo.setDarkMode(darkMode);
        requestInfo.setAdminColorPrimary(adminColorPrimary);
        Map<String, String> headers = requestInfo.getHeader();
        if (headers == null) {
            headers = new HashMap<>();
            requestInfo.setHeader(headers);
        }
        putMissingHeaders(headers);
    }

    private static Map<String, String> copyHeadersWithFallback(HttpRequest request, AdminTheme theme) {
        Map<String, String> headers = ClientIpHeaders.copyHeadersWithRealIp(request);
        theme.putMissingHeaders(headers);
        return headers;
    }

    private static boolean missingThemeHeader(Map<String, String> headers) {
        return isBlank(headerValue(headers, DARK_MODE_HEADER, "Dark_mode", "dark_mode"))
                || isBlank(headerValue(headers, ADMIN_COLOR_PRIMARY_HEADER, "admin_color_Primary", "admin_color_primary"))
                || isBlank(headerValue(headers, ADMIN_THEME_HEADER))
                || isBlank(headerValue(headers, ADMIN_COMPACT_MODE_HEADER));
    }

    private static String headerValue(Map<String, String> headers, String key, String... aliases) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String value = headers.get(key);
        if (!isBlank(value)) {
            return value;
        }
        for (String alias : aliases) {
            value = headers.get(alias);
            if (!isBlank(value)) {
                return value;
            }
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String entryKey = entry.getKey();
            if (entryKey == null) {
                continue;
            }
            if (Objects.equals(entryKey, key) || entryKey.equalsIgnoreCase(key)) {
                return entry.getValue();
            }
            for (String alias : aliases) {
                if (Objects.equals(entryKey, alias) || entryKey.equalsIgnoreCase(alias)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static AdminTheme loadThemeSilently() {
        try {
            return PublicInfoLoader.loadAdminTheme();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "load public info for admin theme failed", e);
            return null;
        }
    }

    private static boolean publicInfoDarkMode(PublicInfo publicInfo) {
        return publicInfo != null && Boolean.TRUE.equals(publicInfo.getDarkMode());
    }

    private static String publicInfoAdminColorPrimary(PublicInfo publicInfo) {
        if (publicInfo == null) {
            return PublicInfoLoader.DEFAULT_ADMIN_COLOR_PRIMARY;
        }
        return normalizeAdminColorPrimary(publicInfo.getAdminColorPrimary());
    }

    private static String normalizeAdminColorPrimary(String adminColorPrimary) {
        if (isBlank(adminColorPrimary)) {
            return PublicInfoLoader.DEFAULT_ADMIN_COLOR_PRIMARY;
        }
        return adminColorPrimary;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
