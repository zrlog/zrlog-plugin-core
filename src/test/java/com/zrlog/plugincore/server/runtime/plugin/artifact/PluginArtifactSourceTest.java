package com.zrlog.plugincore.server.runtime.plugin.artifact;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class PluginArtifactSourceTest {
    private final Map<String, String> headers = Map.of("Cookie", "admin-token=private", "authorization", "Bearer private",
            "Host", "other.invalid", "X-Extra", "not-forwarded");

    @Test public void allowlistedStoreNeverReceivesCallerCredentials() {
        var source = PluginArtifactSource.resolve("https://dl.zrlog.com/plugin/travel.jar?v=123", "http://localhost:7080/blog");
        assertTrue(source.headers(headers).isEmpty());
    }

    @Test public void privateRelativeResourceUsesTrustedHostAndOnlyCallerCredentials() {
        for (String path : new String[]{"/admin/attached/tmp/plugins/hash.jar", "/blog/admin/attached/tmp/plugins/hash.jar"}) {
            var source = PluginArtifactSource.resolve(path, "http://localhost:7080/blog/");
            assertEquals("http://localhost:7080/blog/api/admin/upload/resource?path=%2Fadmin%2Fattached%2Ftmp%2Fplugins%2Fhash.jar", source.uri().toString());
            assertEquals(Map.of("Cookie", "admin-token=private", "authorization", "Bearer private"), source.headers(headers));
        }
    }

    @Test public void rejectsOtherOriginsAndAmbiguousPaths() {
        for (String path : new String[]{"http://dl.zrlog.com/plugin/a.jar", "https://dl.zrlog.com.evil/plugin/a.jar",
                "https://dl.zrlog.com@evil/plugin/a.jar", "https://evil@dl.zrlog.com/plugin/a.jar",
                "https://dl.zrlog.com:444/plugin/a.jar", "https://dl.zrlog.com/plugin/sub/a.jar",
                "https://dl.zrlog.com/plugin/../a.jar", "https://dl.zrlog.com/plugin/%2e%2e/a.jar",
                "//evil/admin/attached/tmp/a.jar", "/admin/attached/tmp/../secret.jar", "/admin/attached/tmp/%252e/a.jar",
                "/admin/attached/tmp/a.jar?redirect=evil", "/admin/attached/tmp/a.jar#frag", "/admin/attached/tmp//a.jar",
                "/admin/attached/tmp/", "/attached/a.jar", "file:///tmp/a.jar", "http://localhost:7080/admin/attached/tmp/a.jar"}) {
            assertThrows(path, IllegalArgumentException.class, () -> PluginArtifactSource.resolve(path, "http://localhost:7080"));
        }
    }
}
