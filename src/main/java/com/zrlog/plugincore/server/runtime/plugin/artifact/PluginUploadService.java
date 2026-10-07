package com.zrlog.plugincore.server.runtime.plugin.artifact;

import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.util.PluginI18n;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.web.controller.PluginApiModels;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/** Installs artifacts in the same directory and naming scheme used by runtime startup. */
public class PluginUploadService {
    public static final int MAX_FILE_SIZE = 64 * 1024 * 1024;
    private static final Object UPLOAD_LOCK = new Object();

    private final Path directory;
    private final String nativeInfo;
    private final PluginBootstrapService bootstrap;

    public PluginUploadService() {
        this(Path.of(PluginRuntimeBridge.pluginConfig().getPluginBasePath()),
                PluginRuntimeBridge.hostConnection().getNativeInfo(), PluginRuntimeBridge.pluginBootstrap());
    }

    PluginUploadService(Path directory, String nativeInfo, PluginBootstrapService bootstrap) {
        this.directory = directory.toAbsolutePath().normalize();
        this.nativeInfo = nativeInfo == null ? "" : nativeInfo;
        this.bootstrap = bootstrap;
    }

    public PluginApiModels.UploadResponse upload(String fileName, boolean overwrite, File source) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(jar|bin|exe)")) {
            return error("invalidName");
        }
        String shortName = PluginFiles.getPluginShortName(new File(fileName));
        String expectedName = nativeInfo.isEmpty() ? shortName + ".jar"
                : shortName + "-" + nativeInfo + (nativeInfo.contains("Window") ? ".exe" : ".bin");
        if (shortName.isEmpty() || !fileName.equals(expectedName)) {
            return error("platformMismatch");
        }
        if (source == null || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)
                || source.length() == 0) {
            return error("invalidPackage");
        }
        if (source.length() > MAX_FILE_SIZE) {
            return error("tooLarge");
        }
        if (!validPackage(source, fileName)) {
            return error("invalidPackage");
        }
        synchronized (UPLOAD_LOCK) {
            return install(shortName, fileName, overwrite, source);
        }
    }

    private PluginApiModels.UploadResponse install(String shortName, String fileName, boolean overwrite, File source)
            throws IOException {
        if (bootstrap.isShutdown() || bootstrap.isBootstrapRunning()) {
            return error("busy");
        }
        Files.createDirectories(directory);
        Path target = directory.resolve(fileName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return error("invalidTarget");
        }
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        PluginCoreDAO dao = PluginCoreDAO.getInstance();
        PluginVO previous = dao.getPluginVOByShortName(shortName);
        boolean overwritten = existed || previous != null;
        if (overwritten && !overwrite) {
            return error("alreadyExists");
        }
        Path staged = Files.createTempFile(directory, ".upload-", ".tmp");
        Path backup = null;
        boolean installed = false;
        boolean completed = false;
        try {
            Files.copy(source.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
            if (!bootstrap.stopPlugin(shortName)) {
                return error("stopFailed");
            }
            if (existed) {
                backup = Files.createTempFile(directory, ".backup-", ".tmp");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            installed = true;
            if (!bootstrap.startPluginFileForMetadata(target.toFile())) {
                return error("registrationFailed");
            }
            completed = true;
            return PluginApiModels.UploadResponse.success(shortName, fileName, overwritten,
                    PluginI18n.text("plugin.upload.success"));
        } finally {
            try {
                if (installed && !completed) {
                    // Keep the backup if stopping/restoring fails; never report a partial install as success.
                    if (!bootstrap.stopPlugin(shortName)) {
                        throw new IOException(PluginI18n.text("plugin.upload.error.rollbackFailed"));
                    }
                    if (backup != null) {
                        Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        backup = null;
                    } else {
                        Files.deleteIfExists(target);
                    }
                    dao.update(core -> {
                        if (previous == null) core.getPluginInfoMap().remove(shortName);
                        else core.getPluginInfoMap().put(shortName, previous);
                    });
                }
                if (backup != null) Files.deleteIfExists(backup);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }

    private static boolean validPackage(File source, String fileName) throws IOException {
        if (fileName.endsWith(".jar")) {
            try (JarFile jar = new JarFile(source)) {
                if (jar.getManifest() == null) return false;
                String main = jar.getManifest().getMainAttributes().getValue("Main-Class");
                return main != null && jar.getJarEntry(main.trim().replace('.', '/') + ".class") != null;
            } catch (java.util.zip.ZipException e) {
                return false;
            }
        }
        try (InputStream input = Files.newInputStream(source.toPath())) {
            byte[] magic = input.readNBytes(4);
            if (magic.length < 4) return false;
            if (fileName.endsWith(".exe")) return magic[0] == 'M' && magic[1] == 'Z';
            if (fileName.contains("-Linux-")) {
                return magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
            }
            int signature = ((magic[0] & 255) << 24) | ((magic[1] & 255) << 16)
                    | ((magic[2] & 255) << 8) | (magic[3] & 255);
            return fileName.contains("-Darwin-")
                    && (signature == 0xfeedfacf || signature == 0xcffaedfe || signature == 0xcafebabe
                    || signature == 0xbebafeca || signature == 0xcafebabf || signature == 0xbfbafeca);
        }
    }

    private static PluginApiModels.UploadResponse error(String key) {
        return PluginApiModels.UploadResponse.error(PluginI18n.text("plugin.upload.error." + key));
    }
}
