package com.zrlog.plugincore.server.web.handler;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginHandleTest {

    @Test
    public void shouldParseAdminPluginRootPath() {
        PluginRequestUriInfo info = PluginHandle.parseRequestUri("/admin/plugins/reminder");

        assertEquals("reminder", info.getName());
        assertEquals("/", info.getAction());
    }

    @Test
    public void shouldRedirectPluginRootWithoutTrailingSlash() {
        PluginRequestUriInfo info = PluginHandle.parseRequestUri("/admin/plugins/reminder");

        assertTrue(PluginHandle.shouldRedirectPluginRoot("/admin/plugins/reminder", info));
        assertEquals("/admin/plugins/reminder/", PluginHandle.pluginRootRedirectUri("/admin/plugins/reminder"));
    }

    @Test
    public void shouldPreserveQueryWhenRedirectingPluginRoot() {
        assertEquals("/admin/plugins/reminder/?tab=main",
                PluginHandle.pluginRootRedirectUri("/admin/plugins/reminder?tab=main"));
    }

    @Test
    public void shouldNotRedirectNestedPluginPath() {
        PluginRequestUriInfo info = PluginHandle.parseRequestUri("/admin/plugins/reminder/static/app.js");

        assertFalse(PluginHandle.shouldRedirectPluginRoot("/admin/plugins/reminder/static/app.js", info));
    }

    @Test
    public void shouldTreatRuntimeServicesAsInternalUri() {
        assertTrue(PluginHandle.isInternalUri("runtime-services"));
    }

    @Test
    public void shouldTreatRegisteredPluginAsPluginRequest() {
        assertTrue(PluginHandle.shouldTreatAsPluginRequest("reminder", true, false));
    }

    @Test
    public void shouldTreatExistingPluginFileAsPluginRequestBeforeMetadataReady() {
        assertTrue(PluginHandle.shouldTreatAsPluginRequest("reminder", false, true));
    }

    @Test
    public void shouldTreatRequiredPluginAsPluginRequestBeforeDownload() {
        assertTrue(PluginHandle.shouldTreatAsPluginRequest("comment", false, false, true));
    }

    @Test
    public void shouldNotTreatUnknownPathAsPluginRequest() {
        assertFalse(PluginHandle.shouldTreatAsPluginRequest("missing", false, false));
    }

    @Test
    public void shouldNotTreatEmptyPathAsPluginRequest() {
        assertFalse(PluginHandle.shouldTreatAsPluginRequest("", true, true));
    }

    @Test
    public void shouldNotTreatInternalPathAsPluginRequest() {
        assertFalse(PluginHandle.shouldTreatAsPluginRequest("static", true, true));
    }

    @Test
    public void shouldAllowAnonymousPluginPwaResources() {
        assertTrue(PluginHandle.canAccessPublicPluginPath(Collections.emptySet(), "/manifest.webmanifest"));
        assertTrue(PluginHandle.canAccessPublicPluginPath(Collections.emptySet(), "/manifest.json"));
        assertTrue(PluginHandle.canAccessPublicPluginPath(Collections.emptySet(), "/pwa-icon"));
        assertTrue(PluginHandle.canAccessPublicPluginPath(Collections.emptySet(), "/pwa-sw.js"));

        assertFalse(PluginHandle.canAccessPublicPluginPath(Collections.emptySet(), "/"));
        assertFalse(PluginHandle.canAccessPublicPluginPath(Collections.emptySet(), "/static/app.js"));
        assertFalse(PluginHandle.canAccessPublicPluginPath(Collections.emptySet(), "/api/status"));
    }

    @Test
    public void shouldPreserveDeclaredPublicPluginPaths() {
        assertTrue(PluginHandle.canAccessPublicPluginPath(Collections.singleton("/public/"), "/public/index.html"));
        assertFalse(PluginHandle.canAccessPublicPluginPath(Collections.singleton("/public/"), "/private/index.html"));
    }
}
