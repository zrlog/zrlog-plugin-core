package com.zrlog.plugincore.server.runtime.plugin.artifact;

import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.vo.PluginVO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.*;

public class PluginUploadServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsAndRegistersThenRequiresExplicitOverwrite() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            Path root = temporary.newFolder().toPath();
            FakeBootstrap bootstrap = new FakeBootstrap();
            PluginUploadService service = new PluginUploadService(root, "", bootstrap);
            File first = jar(1);
            var response = service.upload("travel.jar", false, first);
            assertEquals(0, response.getError());
            assertEquals("travel", response.getData().getShortName());
            assertFalse(response.getData().isOverwritten());
            assertEquals(root.resolve("travel.jar").toFile(), bootstrap.registered);
            assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(bootstrap.registered.toPath()));

            File replacement = jar(2);
            assertEquals(1, service.upload("travel.jar", false, replacement).getError());
            assertEquals(1, bootstrap.starts);
            assertEquals(0, service.upload("travel.jar", true, replacement).getError());
            assertEquals(2, bootstrap.starts);
            assertArrayEquals(Files.readAllBytes(replacement.toPath()), Files.readAllBytes(root.resolve("travel.jar")));
            assertOnlyPluginRemains(root);
        }
    }

    @Test
    public void restoresPreviousFileAndMetadataWhenRegistrationFails() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            Path root = temporary.newFolder().toPath();
            File original = jar(1);
            Files.copy(original.toPath(), root.resolve("travel.jar"));
            storePlugin("old-id");
            FakeBootstrap bootstrap = new FakeBootstrap();
            bootstrap.register = false;
            bootstrap.changeMetadata = true;
            var response = new PluginUploadService(root, "", bootstrap).upload("travel.jar", true, jar(2));
            assertEquals(1, response.getError());
            assertArrayEquals(Files.readAllBytes(original.toPath()), Files.readAllBytes(root.resolve("travel.jar")));
            assertEquals("old-id", PluginCoreDAO.getInstance().getPluginVOByShortName("travel").getPlugin().getId());
            assertOnlyPluginRemains(root);
        }
    }

    @Test
    public void removesNewArtifactAndMetadataWhenRegistrationFails() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            Path root = temporary.newFolder().toPath();
            FakeBootstrap bootstrap = new FakeBootstrap();
            bootstrap.register = false;
            bootstrap.changeMetadata = true;
            assertEquals(1, new PluginUploadService(root, "", bootstrap).upload("travel.jar", false, jar(1)).getError());
            assertNull(PluginCoreDAO.getInstance().getPluginVOByShortName("travel"));
            try (var files = Files.list(root)) { assertEquals(0, files.count()); }
        }
    }

    @Test
    public void rejectsUnsafeNamesInvalidFilesAndPlatformMismatchBeforeTouchingRuntime() throws Exception {
        FakeBootstrap bootstrap = new FakeBootstrap();
        PluginUploadService service = new PluginUploadService(temporary.newFolder().toPath(), "", bootstrap);
        File valid = jar(1);
        for (String name : new String[]{"../travel.jar", "/travel.jar", "..\\travel.jar", "travel.zip",
                "travel jar.jar", "travel-Linux-amd64.bin", "travel.bin.jar"}) {
            assertEquals(name, 1, service.upload(name, true, valid).getError());
        }
        assertEquals(1, service.upload("travel.jar", false, null).getError());
        assertEquals(1, service.upload("travel.jar", false, temporary.newFile()).getError());
        File text = temporary.newFile();
        Files.writeString(text.toPath(), "not a jar");
        assertEquals(1, service.upload("travel.jar", false, text).getError());
        File large = temporary.newFile();
        try (RandomAccessFile file = new RandomAccessFile(large, "rw")) { file.setLength(PluginUploadService.MAX_FILE_SIZE + 1L); }
        assertEquals(1, service.upload("travel.jar", false, large).getError());
        assertEquals(0, bootstrap.stops);
    }

    @Test
    public void rejectsSymlinkDestinationWithoutChangingItsReferent() throws Exception {
        Path root = temporary.newFolder().toPath();
        File original = jar(1);
        byte[] before = Files.readAllBytes(original.toPath());
        Files.createSymbolicLink(root.resolve("travel.jar"), original.toPath());
        FakeBootstrap bootstrap = new FakeBootstrap();
        assertEquals(1, new PluginUploadService(root, "", bootstrap).upload("travel.jar", true, jar(2)).getError());
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
        assertEquals(0, bootstrap.stops);
    }

    @Test
    public void doesNotReplaceFileIfOldProcessCannotStop() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            Path root = temporary.newFolder().toPath();
            File original = jar(1);
            Files.copy(original.toPath(), root.resolve("travel.jar"));
            FakeBootstrap bootstrap = new FakeBootstrap();
            bootstrap.stop = false;
            assertEquals(1, new PluginUploadService(root, "", bootstrap).upload("travel.jar", true, jar(2)).getError());
            assertEquals(0, bootstrap.starts);
            assertArrayEquals(Files.readAllBytes(original.toPath()), Files.readAllBytes(root.resolve("travel.jar")));
            assertOnlyPluginRemains(root);
        }
    }

    @Test
    public void acceptsNativeArtifactOnlyForConfiguredPlatform() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            Path root = temporary.newFolder().toPath();
            File binary = temporary.newFile();
            Files.write(binary.toPath(), new byte[]{0x7f, 'E', 'L', 'F', 2, 1});
            PluginUploadService service = new PluginUploadService(root, "Linux-amd64", new FakeBootstrap());
            assertEquals(1, service.upload("travel-Linux-arm64.bin", false, binary).getError());
            assertEquals(1, service.upload("travel.jar", false, jar(1)).getError());
            var response = service.upload("travel-Linux-amd64.bin", false, binary);
            assertEquals(0, response.getError());
            assertEquals("travel", response.getData().getShortName());
            assertEquals("travel-Linux-amd64.bin", response.getData().getFileName());
        }
    }

    private File jar(int content) throws Exception {
        File file = temporary.newFile();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Main");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file.toPath()), manifest)) {
            output.putNextEntry(new JarEntry("example/Main.class"));
            output.write(content);
            output.closeEntry();
        }
        return file;
    }

    private void assertOnlyPluginRemains(Path root) throws Exception {
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    private static void storePlugin(String id) {
        Plugin plugin = new Plugin();
        plugin.setShortName("travel");
        plugin.setId(id);
        PluginVO vo = new PluginVO();
        vo.setPlugin(plugin);
        PluginCoreDAO.getInstance().update(core -> core.getPluginInfoMap().put("travel", vo));
    }

    private static class FakeBootstrap extends PluginBootstrapService {
        boolean register = true;
        boolean stop = true;
        boolean changeMetadata;
        int starts;
        int stops;
        File registered;

        FakeBootstrap() { super(Collections.emptyMap(), null, null, null); }

        @Override public boolean stopPlugin(String shortName) { stops++; return stop; }
        @Override public boolean startPluginFileForMetadata(File file) {
            starts++;
            registered = file;
            if (changeMetadata) storePlugin("new-id");
            return register;
        }
    }
}
