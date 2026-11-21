package com.zrlog.plugincore.server.util;

import com.hibegin.common.dao.DAO;
import com.hibegin.http.server.api.HttpRequest;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.model.PublicInfo;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AdminTheme {

    public static final String DARK_MODE_HEADER = BaseHttpRequestInfo.DARK_MODE_HEADER;
    public static final String ADMIN_COLOR_PRIMARY_HEADER = BaseHttpRequestInfo.ADMIN_COLOR_PRIMARY_HEADER;
    public static final String ADMIN_THEME_HEADER = BaseHttpRequestInfo.ADMIN_THEME_HEADER;
    public static final String ADMIN_COMPACT_MODE_HEADER = BaseHttpRequestInfo.ADMIN_COMPACT_MODE_HEADER;
    private static final String LEGACY_DARK_MODE_HEADER = "dark_mode";
    private static final String LEGACY_ADMIN_COLOR_PRIMARY_HEADER = "admin_color_primary";

    private static final Logger LOGGER = LoggerUtil.getLogger(AdminTheme.class);

    private final boolean darkMode;
    private final String adminColorPrimary;
    private final String theme;
    private final boolean compactMode;

    public AdminTheme(boolean darkMode, String adminColorPrimary) {
        this(darkMode, adminColorPrimary, null, false);
    }

    public AdminTheme(boolean darkMode, String adminColorPrimary, String theme, boolean compactMode) {
        this.darkMode = darkMode;
        this.adminColorPrimary = defaultIfBlank(adminColorPrimary, BaseHttpRequestInfo.DEFAULT_ADMIN_COLOR_PRIMARY);
        this.theme = defaultIfBlank(theme, BaseHttpRequestInfo.DEFAULT_ADMIN_THEME);
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
        return fromHeaders(request == null ? null : request.getHeaderMap());
    }

    public static AdminTheme fromHeaders(Map<String, String> headers) {
        return fromHeadersWithFallback(headers, missingThemeHeader(headers) ? loadThemeSilently() : null);
    }

    public static void applyTo(BaseHttpRequestInfo requestInfo, HttpRequest request) {
        if (requestInfo == null) {
            return;
        }
        AdminTheme theme = fromRequest(request);
        theme.applyTo(requestInfo, ClientIpHeaders.copyHeadersWithRealIp(request));
    }

    public static AdminTheme fromHeaders(Map<String, String> headers, PublicInfo fallback) {
        return fromHeadersWithFallback(headers,
                new AdminTheme(fallback != null && Boolean.TRUE.equals(fallback.getDarkMode()),
                        fallback == null ? null : fallback.getAdminColorPrimary()));
    }

    static AdminTheme fromHeadersWithFallback(Map<String, String> headers, AdminTheme fallback) {
        AdminTheme defaults = fallback == null ? new AdminTheme(false, null) : fallback;
        String darkMode = headerValue(headers, DARK_MODE_HEADER, LEGACY_DARK_MODE_HEADER);
        String adminColorPrimary = headerValue(headers, ADMIN_COLOR_PRIMARY_HEADER, LEGACY_ADMIN_COLOR_PRIMARY_HEADER);
        String theme = headerValue(headers, ADMIN_THEME_HEADER);
        String compactMode = headerValue(headers, ADMIN_COMPACT_MODE_HEADER);
        return new AdminTheme(booleanOrFallback(darkMode, defaults.isDarkMode()),
                defaultIfBlank(adminColorPrimary, defaults.getAdminColorPrimary()),
                defaultIfBlank(theme, defaults.getTheme()),
                booleanOrFallback(compactMode, defaults.isCompactMode()));
    }

    public void applyTo(BaseHttpRequestInfo requestInfo) {
        if (requestInfo == null) {
            return;
        }
        Map<String, String> headers = requestInfo.getHeader();
        applyTo(requestInfo, headers == null ? new HashMap<>() : new HashMap<>(headers));
    }

    private void applyTo(BaseHttpRequestInfo requestInfo, Map<String, String> headers) {
        requestInfo.setDarkMode(darkMode);
        requestInfo.setAdminColorPrimary(adminColorPrimary);
        requestInfo.setAdminTheme(theme);
        requestInfo.setAdminCompactMode(compactMode);
        headers.put(DARK_MODE_HEADER, Boolean.toString(darkMode));
        headers.put(ADMIN_COLOR_PRIMARY_HEADER, adminColorPrimary);
        headers.put(ADMIN_THEME_HEADER, theme);
        headers.put(ADMIN_COMPACT_MODE_HEADER, Boolean.toString(compactMode));
        requestInfo.setHeader(headers);
    }

    private static boolean missingThemeHeader(Map<String, String> headers) {
        return !isBoolean(headerValue(headers, DARK_MODE_HEADER, LEGACY_DARK_MODE_HEADER))
                || headerValue(headers, ADMIN_COLOR_PRIMARY_HEADER, LEGACY_ADMIN_COLOR_PRIMARY_HEADER) == null
                || headerValue(headers, ADMIN_THEME_HEADER) == null
                || !isBoolean(headerValue(headers, ADMIN_COMPACT_MODE_HEADER));
    }

    private static String headerValue(Map<String, String> headers, String key, String... aliases) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String value = findHeader(headers, key);
        if (value != null) {
            return value;
        }
        for (String alias : aliases) {
            value = findHeader(headers, alias);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String findHeader(Map<String, String> headers, String key) {
        String value = defaultIfBlank(headers.get(key), null);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                value = defaultIfBlank(entry.getValue(), null);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static AdminTheme loadThemeSilently() {
        if (DAO.getDefaultDataSource() == null) {
            return null;
        }
        try {
            return PublicInfoLoader.loadAdminTheme();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "load public info for admin theme failed", e);
            return null;
        }
    }

    private static boolean isBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    private static boolean booleanOrFallback(String value, boolean fallback) {
        return isBoolean(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private static String defaultIfBlank(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
