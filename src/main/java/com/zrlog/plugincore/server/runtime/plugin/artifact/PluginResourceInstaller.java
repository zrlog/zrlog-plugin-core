package com.zrlog.plugincore.server.runtime.plugin.artifact;

import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.util.HttpUtils;
import com.zrlog.plugincore.server.util.PluginI18n;
import com.zrlog.plugincore.server.web.controller.PluginApiModels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Both uploaded resources and store downloads enter the same bounded download/install transaction. */
public class PluginResourceInstaller {
    private final PluginInstallService installer;
    private final String apiHomeUrl;

    public PluginResourceInstaller() {
        this(new PluginInstallService(), PluginRuntimeBridge.hostConnection().getBlogApiHomeUrl());
    }

    PluginResourceInstaller(PluginInstallService installer, String apiHomeUrl) {
        this.installer = installer;
        this.apiHomeUrl = apiHomeUrl;
    }

    public PluginApiModels.InstallResponse install(String source, String fileName, boolean overwrite,
                                                   Map<String, String> credentials) throws Exception {
        if (!PluginInstallService.validName(fileName)) {
            return PluginApiModels.InstallResponse.error(PluginI18n.text("plugin.upload.error.invalidName"));
        }
        PluginArtifactSource resource;
        try {
            resource = PluginArtifactSource.resolve(source, apiHomeUrl);
        } catch (IllegalArgumentException e) {
            return PluginApiModels.InstallResponse.error(PluginI18n.text("plugin.upload.error.invalidSource"));
        }
        Path download = Files.createTempFile("zrlog-plugin-install-", ".tmp");
        try {
            // HttpUtils does not follow redirects; credentials never escape the checked target.
            HttpUtils.downloadToFile(resource.uri().toString(), resource.headers(credentials), download.toFile(),
                    PluginInstallService.MAX_FILE_SIZE);
            return installer.install(fileName, overwrite, download.toFile());
        } finally {
            Files.deleteIfExists(download);
        }
    }
}
