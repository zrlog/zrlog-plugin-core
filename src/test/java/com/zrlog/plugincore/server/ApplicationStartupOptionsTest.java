package com.zrlog.plugincore.server;

import com.zrlog.plugincore.server.runtime.plugin.config.PluginHostConnection;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApplicationStartupOptionsTest {

    @Test
    public void shouldParseStandaloneDefaults() throws Exception {
        ApplicationStartupOptions options = ApplicationStartupOptions.parse(null);

        assertEquals(9089, options.getHttpPort());
        assertFalse(options.hasExternalDbProperties());
        assertTrue(options.getDbProperties().exists());
        assertEquals(PluginHostConnection.DEFAULT_BLOG_API_HOME_URL, options.getBlogApiHomeUrl());
        assertEquals("", options.getBlogPluginToken());
        assertEquals("", options.getNativeInfo());
        assertEquals("", options.getContextPath());
        assertEquals(-1, options.getListenBlogPort());
    }

    @Test
    public void shouldParseBlogRuntimeArgs() throws Exception {
        ApplicationStartupOptions options = ApplicationStartupOptions.parse(new String[]{
                "9089",
                "19080",
                "/tmp/blog-db.properties",
                "/tmp/plugins",
                "19081",
                "/tmp/blog-runtime",
                "4.0.0",
                "8080",
                "token",
                "-",
                "/sub"
        });

        assertEquals(9089, options.getHttpPort());
        assertEquals(19080, options.getMasterPort());
        assertEquals(new File("/tmp/blog-db.properties").getPath(), options.getDbProperties().getPath());
        assertEquals("/tmp/plugins", options.getPluginPath());
        assertEquals("/tmp/blog-runtime", options.getBlogRunTime().getPath());
        assertEquals("4.0.0", options.getBlogRunTime().getVersion());
        assertTrue(options.hasExternalDbProperties());
        assertEquals(19081, options.getListenBlogPort());
        assertEquals("http://127.0.0.1:8080/sub", options.getBlogApiHomeUrl());
        assertEquals("token", options.getBlogPluginToken());
        assertEquals("", options.getNativeInfo());
        assertEquals("/sub", options.getContextPath());
    }

    @Test
    public void shouldTreatHashContextPathPlaceholderAsEmpty() throws Exception {
        ApplicationStartupOptions options = ApplicationStartupOptions.parse(new String[]{
                "9089",
                "19080",
                "/tmp/blog-db.properties",
                "/tmp/plugins",
                "19081",
                "/tmp/blog-runtime",
                "4.0.0",
                "8080",
                "token",
                "-",
                "#"
        });

        assertEquals("http://127.0.0.1:8080", options.getBlogApiHomeUrl());
        assertEquals("", options.getContextPath());
    }
}
