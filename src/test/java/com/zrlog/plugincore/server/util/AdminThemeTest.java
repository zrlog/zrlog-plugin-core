package com.zrlog.plugincore.server.util;

import com.zrlog.plugin.common.model.PublicInfo;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdminThemeTest {

    @Test
    public void shouldUseRequestThemeHeadersFirst() {
        PublicInfo publicInfo = publicInfo(false, "#ff4d4f");
        Map<String, String> headers = new HashMap<>();
        headers.put(AdminTheme.DARK_MODE_HEADER, "true");
        headers.put(AdminTheme.ADMIN_COLOR_PRIMARY_HEADER, "#1677ff");
        headers.put(AdminTheme.ADMIN_THEME_HEADER, "desk");
        headers.put(AdminTheme.ADMIN_COMPACT_MODE_HEADER, "true");

        AdminTheme theme = AdminTheme.fromHeaders(headers, publicInfo);

        assertTrue(theme.isDarkMode());
        assertEquals("#1677ff", theme.getAdminColorPrimary());
        assertEquals("desk", theme.getTheme());
        assertTrue(theme.isCompactMode());
    }

    @Test
    public void shouldFallbackToPublicInfoWhenThemeHeadersMissing() {
        PublicInfo publicInfo = publicInfo(true, "#52c41a");

        AdminTheme theme = AdminTheme.fromHeaders(new HashMap<>(), publicInfo);

        assertTrue(theme.isDarkMode());
        assertEquals("#52c41a", theme.getAdminColorPrimary());
        assertEquals("default", theme.getTheme());
        assertFalse(theme.isCompactMode());
    }

    @Test
    public void shouldPutMissingCanonicalHeadersForForwardRequest() {
        PublicInfo publicInfo = publicInfo(true, "#722ed1");
        Map<String, String> headers = new HashMap<>();
        headers.put("Dark_mode", "false");

        AdminTheme theme = AdminTheme.fromHeaders(headers, publicInfo);
        theme.putMissingHeaders(headers);

        assertFalse(theme.isDarkMode());
        assertEquals("false", headers.get(AdminTheme.DARK_MODE_HEADER));
        assertEquals("#722ed1", headers.get(AdminTheme.ADMIN_COLOR_PRIMARY_HEADER));
    }

    @Test
    public void shouldApplyThemeToStandardRequestFields() {
        BaseHttpRequestInfo requestInfo = new BaseHttpRequestInfo();
        AdminTheme theme = new AdminTheme(true, "#13c2c2", "geek", true);

        theme.applyTo(requestInfo);

        assertTrue(requestInfo.isDarkMode());
        assertEquals(Boolean.TRUE, requestInfo.getDarkMode());
        assertEquals("#13c2c2", requestInfo.getAdminColorPrimary());
        assertEquals("true", requestInfo.getHeader().get(BaseHttpRequestInfo.DARK_MODE_HEADER));
        assertEquals("#13c2c2", requestInfo.getHeader().get(BaseHttpRequestInfo.ADMIN_COLOR_PRIMARY_HEADER));
        assertEquals("geek", requestInfo.getHeader().get(AdminTheme.ADMIN_THEME_HEADER));
        assertEquals("true", requestInfo.getHeader().get(AdminTheme.ADMIN_COMPACT_MODE_HEADER));
    }

    @Test
    public void shouldMergeCaseInsensitiveHeadersWithSiteAppearanceAndPreserveFalse() {
        Map<String, String> headers = new HashMap<>();
        headers.put("admin-theme", "antd");
        headers.put("ADMIN-COMPACT-MODE", "false");
        AdminTheme theme = AdminTheme.fromHeadersWithFallback(headers, new AdminTheme(true, "#722ed1", "desk", true));

        theme.putMissingHeaders(headers);

        assertEquals("antd", headers.get(AdminTheme.ADMIN_THEME_HEADER));
        assertEquals("false", headers.get(AdminTheme.ADMIN_COMPACT_MODE_HEADER));
        assertEquals("true", headers.get(AdminTheme.DARK_MODE_HEADER));
        assertEquals("#722ed1", theme.getAdminColorPrimary());
        AdminTheme fallback = AdminTheme.fromHeadersWithFallback(Map.of(AdminTheme.ADMIN_THEME_HEADER, "  "),
                new AdminTheme(false, "#1677ff", "desk", true));
        assertEquals("desk", fallback.getTheme());
        assertTrue(fallback.isCompactMode());
    }

    @Test
    public void shouldUseDefaultColorWhenPublicInfoColorBlank() {
        PublicInfo publicInfo = publicInfo(false, "");

        AdminTheme theme = AdminTheme.fromHeaders(new HashMap<>(), publicInfo);

        assertFalse(theme.isDarkMode());
        assertEquals(PublicInfoLoader.DEFAULT_ADMIN_COLOR_PRIMARY, theme.getAdminColorPrimary());
    }

    private PublicInfo publicInfo(boolean darkMode, String adminColorPrimary) {
        PublicInfo publicInfo = new PublicInfo();
        publicInfo.setDarkMode(darkMode);
        publicInfo.setAdminColorPrimary(adminColorPrimary);
        return publicInfo;
    }

    @Test
    public void shouldReadCompleteSiteAppearanceWhenNoHostHeadersExist() throws Exception {
        try (InMemoryPluginCoreDatabase db = InMemoryPluginCoreDatabase.open()) {
            db.update("insert into website(name, value) values (?, ?)", "admin_theme", "desk");
            db.update("insert into website(name, value) values (?, ?)", "admin_darkMode", "true");
            db.update("insert into website(name, value) values (?, ?)", "admin_color_primary", "#00875a");
            db.update("insert into website(name, value) values (?, ?)", "admin_compactMode", "true");

            AdminTheme appearance = AdminTheme.fromRequest(null);

            assertEquals("desk", appearance.getTheme());
            assertTrue(appearance.isDarkMode());
            assertEquals("#00875a", appearance.getAdminColorPrimary());
            assertTrue(appearance.isCompactMode());
        }
    }
}
