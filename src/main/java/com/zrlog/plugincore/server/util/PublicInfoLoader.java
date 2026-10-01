package com.zrlog.plugincore.server.util;

import com.hibegin.common.dao.ResultValueConvertUtils;
import com.zrlog.plugin.common.model.PublicInfo;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;
import com.zrlog.plugincore.server.dao.WebSiteDAO;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class PublicInfoLoader {

    public static final String DEFAULT_ADMIN_COLOR_PRIMARY = BaseHttpRequestInfo.DEFAULT_ADMIN_COLOR_PRIMARY;
    private static final String[] PUBLIC_INFO_KEYS = "title,second_title,host,admin_darkMode,admin_color_primary".split(",");

    private PublicInfoLoader() {
    }

    public static AdminTheme loadAdminTheme() throws SQLException {
        Map<String, Object> values = new WebSiteDAO().getWebSiteByNameIn(Arrays.asList(
                "admin_darkMode", "admin_color_primary", "admin_theme", "admin_compactMode"));
        return new AdminTheme(Boolean.TRUE.equals(ResultValueConvertUtils.toBoolean(values.get("admin_darkMode"))),
                (String) values.get("admin_color_primary"), (String) values.get("admin_theme"),
                Boolean.TRUE.equals(ResultValueConvertUtils.toBoolean(values.get("admin_compactMode"))));
    }

    public static PublicInfo loadPublicInfo() throws SQLException {
        Map<String, Object> response = new WebSiteDAO().getWebSiteByNameIn(Arrays.asList(PUBLIC_INFO_KEYS));
        PublicInfo publicInfo = new PublicInfo();
        publicInfo.setHomeUrl("http://" + response.get("host"));
        publicInfo.setApiHomeUrl(PluginRuntimeBridge.hostConnection().getBlogApiHomeUrl());
        publicInfo.setTitle((String) response.get("title"));
        publicInfo.setSecondTitle((String) response.get("second_title"));
        publicInfo.setAdminColorPrimary(Objects.requireNonNullElse((String) response.get("admin_color_primary"), DEFAULT_ADMIN_COLOR_PRIMARY));
        publicInfo.setDarkMode(ResultValueConvertUtils.toBoolean(response.get("admin_darkMode")));
        return publicInfo;
    }
}
