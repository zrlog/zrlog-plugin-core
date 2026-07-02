package com.zrlog.plugincore.server.runtime.pwa;

import com.google.gson.Gson;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.util.AdminTheme;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PluginPwaResourcesTest {

    private static final Gson GSON = new Gson();

    @Test
    public void shouldBuildScopedManifestFromExistingPluginMetadata() {
        Plugin plugin = plugin();

        PluginPwaManifest manifest = new PluginPwaResources().manifest(plugin, "/admin/plugins/reminder/");

        assertEquals("/admin/plugins/reminder/", manifest.getId());
        assertEquals("Reminder", manifest.getName());
        assertEquals("reminder", manifest.getShortName());
        assertEquals("Create and manage reminders", manifest.getDescription());
        assertEquals("/admin/plugins/reminder/", manifest.getStartUrl());
        assertEquals("/admin/plugins/reminder/", manifest.getScope());
        assertEquals("standalone", manifest.getDisplay());
        assertEquals("#1677ff", manifest.getThemeColor());
        assertEquals("#FFFFFF", manifest.getBackgroundColor());
        assertEquals("/admin/plugins/reminder/pwa-icon", manifest.getIcons().get(0).getSrc());
        assertEquals("any", manifest.getIcons().get(0).getSizes());
        assertEquals("image/svg+xml", manifest.getIcons().get(0).getType());
    }

    @Test
    public void shouldBuildManifestWithAdminTheme() {
        PluginPwaManifest manifest = new PluginPwaResources().manifest(plugin(), "/admin/plugins/reminder/",
                new AdminTheme(true, "#13c2c2"));

        assertEquals("#13c2c2", manifest.getThemeColor());
        assertEquals("#000000", manifest.getBackgroundColor());
    }

    @Test
    public void shouldSerializeManifestUsingPwaFieldNames() {
        String json = GSON.toJson(new PluginPwaResources().manifest(plugin(), "/admin/plugins/reminder/"));

        assertTrue(json.contains("\"short_name\":\"reminder\""));
        assertTrue(json.contains("\"start_url\":\"/admin/plugins/reminder/\""));
        assertTrue(json.contains("\"theme_color\":\"#1677ff\""));
        assertTrue(json.contains("\"background_color\":\"#FFFFFF\""));
    }

    @Test
    public void shouldDecodePreviewImageDataUrlForIconEndpoint() {
        byte[] svg = "<svg/>".getBytes(StandardCharsets.UTF_8);
        PluginPwaResources.PreviewIcon icon = PluginPwaResources.previewIcon(
                "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg));

        assertNotNull(icon);
        assertEquals("image/svg+xml", icon.getContentType());
        assertArrayEquals(svg, icon.getBytes());
    }

    @Test
    public void shouldDetectStandardPwaResourceActions() {
        assertTrue(PluginPwaResources.isPwaResource("/manifest.webmanifest"));
        assertTrue(PluginPwaResources.isPwaResource("/manifest.json"));
        assertTrue(PluginPwaResources.isPwaResource("/pwa-sw.js"));
        assertTrue(PluginPwaResources.isPwaResource("/pwa-icon"));
        assertFalse(PluginPwaResources.isPwaResource("/static/app.js"));
    }

    @Test
    public void shouldResolveCanonicalPluginBasePath() {
        assertEquals("/admin/plugins/reminder/",
                PluginPwaResources.pluginBasePath("reminder", ""));
        assertEquals("/admin/plugins/reminder/",
                PluginPwaResources.pluginBasePath("reminder", "#"));
        assertEquals("/sub/admin/plugins/reminder/",
                PluginPwaResources.pluginBasePath("reminder", "/sub/"));
    }

    @Test
    public void shouldBuildScopedManifestWithContextPath() {
        PluginPwaManifest manifest = new PluginPwaResources().manifest(plugin(), "/sub/admin/plugins/reminder/");

        assertEquals("/sub/admin/plugins/reminder/", manifest.getId());
        assertEquals("/sub/admin/plugins/reminder/", manifest.getStartUrl());
        assertEquals("/sub/admin/plugins/reminder/", manifest.getScope());
        assertEquals("/sub/admin/plugins/reminder/pwa-icon", manifest.getIcons().get(0).getSrc());
    }

    private Plugin plugin() {
        Plugin plugin = new Plugin();
        plugin.setShortName("reminder");
        plugin.setName("Reminder");
        plugin.setDesc("Create and manage reminders");
        plugin.setPreviewImageBase64("data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg/>".getBytes(StandardCharsets.UTF_8)));
        return plugin;
    }
}
