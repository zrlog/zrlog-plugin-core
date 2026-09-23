package com.zrlog.plugincore.server.util;

import com.hibegin.http.server.api.HttpRequest;
import com.zrlog.plugincore.server.dao.WebSiteDAO;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import org.junit.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.Assert.*;

public class PluginI18nTest {
    @Test
    public void shouldFollowAdminWebsiteLanguageAndPickUpChanges() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            WebSiteDAO website = new WebSiteDAO();
            website.saveOrUpdate("language", "en_US");
            assertEquals("en_US", PluginI18n.resolveLanguage(request("zh-CN")));
            website.saveOrUpdate("language", "zh_CN");
            assertEquals("zh_CN", PluginI18n.resolveLanguage(request("en-US")));
            website.saveOrUpdate("language", "fr_FR");
            assertEquals("zh_CN", PluginI18n.resolveLanguage(request("en-US")));
        }
    }

    @Test
    public void shouldUseBrowserLanguageOnlyWhenWebsiteLanguageIsMissing() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            assertEquals("en_US", PluginI18n.resolveLanguage(request("en-GB,en;q=0.9")));
            assertEquals("zh_CN", PluginI18n.resolveLanguage(request("zh-CN,en;q=0.8")));
            assertEquals("zh_CN", PluginI18n.resolveLanguage(request("fr-FR")));
            assertEquals("zh_CN", PluginI18n.resolveLanguage(null));
        }
    }

    @Test
    public void shouldRestoreLanguageAfterNestedScopeAndException() {
        try (PluginI18n.Scope ignored = PluginI18n.open("en-US")) {
            assertEquals("Plugin stopped", PluginI18n.text("plugin.stop.success"));
            assertEquals("limit must be a number", PluginI18n.text("plugin.validation.numberRequired", "limit"));
            assertThrows(IllegalStateException.class, () -> {
                try (PluginI18n.Scope nested = PluginI18n.open("zh_CN")) {
                    assertEquals("停止成功", PluginI18n.text("plugin.stop.success"));
                    throw new IllegalStateException("test");
                }
            });
            assertEquals("en_US", PluginI18n.getLanguage());
        }
        assertEquals("zh_CN", PluginI18n.getLanguage());
    }

    @Test
    public void shouldPackageBothLanguagesWithMatchingKeysAndParameters() throws Exception {
        Properties chinese = resources("zh_CN");
        Properties english = resources("en_US");
        assertFalse(chinese.isEmpty());
        assertEquals(chinese.keySet(), english.keySet());
        for (String key : chinese.stringPropertyNames()) {
            assertFalse(key, chinese.getProperty(key).trim().isEmpty());
            assertFalse(key, english.getProperty(key).trim().isEmpty());
            assertEquals(key, parameters(chinese.getProperty(key)), parameters(english.getProperty(key)));
        }
    }

    private String parameters(String value) {
        return value.replaceAll("[^{}0-9]", "");
    }

    private Properties resources(String language) throws Exception {
        Properties result = new Properties();
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream(
                "/i18n/plugin_backend_" + language + ".properties"), StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }

    private HttpRequest request(String acceptLanguage) {
        return (HttpRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpRequest.class},
                (proxy, method, args) -> "getHeader".equals(method.getName()) ? acceptLanguage : null);
    }
}
