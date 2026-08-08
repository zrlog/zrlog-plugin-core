package com.zrlog.plugincore.server.web.handler;

import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.ActionType;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginHttpStreamTest {

    @Test
    public void shouldCachePluginReportedStaticAssets() {
        Plugin plugin = pluginWithCacheableStaticPaths("/static/assets/", "/static/js/main.js");

        assertTrue(PluginHttpStream.shouldCachePluginStaticResource(plugin, "/static/assets/main.a1b2c3d4.js", ActionType.HTTP_FILE));
        assertTrue(PluginHttpStream.shouldCachePluginStaticResource(plugin, "/static/assets/main.35820637.css", ActionType.HTTP_FILE));
        assertTrue(PluginHttpStream.shouldCachePluginStaticResource(plugin, "static/js/main.js?v=1", ActionType.HTTP_FILE));
    }

    @Test
    public void shouldNotCacheUnreportedOrDynamicPluginResponses() {
        Plugin plugin = pluginWithCacheableStaticPaths("/static/js/main.js", "/api/");

        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(plugin, "/static/app.js", ActionType.HTTP_FILE));
        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(plugin, "/static/index.html", ActionType.HTTP_FILE));
        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(plugin, "/api/status.json", ActionType.HTTP_FILE));
        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(plugin, "/static/js/main.js", ActionType.HTTP_METHOD));
        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(plugin, "/pwa-sw.js", ActionType.HTTP_FILE));
        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(plugin, null, ActionType.HTTP_FILE));
        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(new Plugin(), "/static/js/main.js", ActionType.HTTP_FILE));
        assertFalse(PluginHttpStream.shouldCachePluginStaticResource(null, "/static/js/main.js", ActionType.HTTP_FILE));
    }

    @Test
    public void shouldRenderStreamedAttachmentWithOriginalFileName() throws Exception {
        Path directory = Files.createTempDirectory("plugin-http-attachment");
        File file = directory.resolve("report.bin").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        try {
            MsgPacket packet = new MsgPacket(file, ContentType.FILE, MsgPacketStatus.RESPONSE_SUCCESS, 1,
                    ActionType.HTTP_ATTACHMENT_FILE.name());

            assertEquals(file, PluginHttpStream.attachmentFile(packet));
            assertEquals("report.bin", PluginHttpStream.attachmentFile(packet).getName());
            assertThrows(IllegalStateException.class, () -> PluginHttpStream.attachmentFile(new MsgPacket()));
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static Plugin pluginWithCacheableStaticPaths(String... paths) {
        Plugin plugin = new Plugin();
        plugin.setCacheableStaticPaths(new LinkedHashSet<>(Arrays.asList(paths)));
        return plugin;
    }
}
