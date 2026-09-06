package com.zrlog.plugincore.server.runtime.plugin.artifact;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PluginFilesTest {

    @Test
    public void shouldCalculatePluginFileMd5() throws Exception {
        File file = File.createTempFile("zrlog-plugin-md5", ".tmp");
        try {
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                outputStream.write("plugin".getBytes(StandardCharsets.UTF_8));
            }

            assertEquals("a24bcf2198b1b13ad985304483f7f324", PluginFiles.pluginFileMd5(file));
        } finally {
            file.delete();
        }
    }

    @Test
    public void shouldReturnEmptyMd5ForMissingFile() {
        assertEquals("", PluginFiles.pluginFileMd5(new File("/tmp/not-exists-zrlog-plugin")));
    }

    @Test
    public void shouldListOnlyRunnablePluginFilesFromPluginDirectory() throws Exception {
        File directory = File.createTempFile("zrlog-plugin-files", "");
        assertTrue(directory.delete());
        assertTrue(directory.mkdirs());
        try {
            writeFile(directory, "reminder-Linux-amd64.bin");
            writeFile(directory, "email.jar");
            writeFile(directory, "backup-Windows-amd64.exe");
            writeFile(directory, "README.txt");
            assertTrue(new File(directory, "empty.jar").createNewFile());
            assertTrue(new File(directory, "comment").mkdirs());

            List<String> fileNames = new ArrayList<>();
            for (File file : PluginFiles.pluginFilesIn(directory)) {
                fileNames.add(file.getName());
            }

            assertEquals(Arrays.asList("backup-Windows-amd64.exe", "email.jar", "reminder-Linux-amd64.bin"), fileNames);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void shouldExplainWhenAutoDownloadIsDisabled() {
        assertEquals("Plugin file not found: reminder. Automatic plugin download is disabled by runtime.autoDownloadMissingPluginFileEnabled.",
                PluginFiles.missingPluginFileMessage("reminder", true));
        assertEquals("Plugin file not found: reminder", PluginFiles.missingPluginFileMessage("reminder", false));
    }

    @Test
    public void shouldUseConfiguredPathForDownloadedPluginsOutsideFaaS() {
        File file = PluginFiles.downloadPluginFile("changyan-Linux-amd64.bin", false, 9080,
                "/var/task/conf/plugins/installed-plugins");

        assertEquals(new File("/var/task/conf/plugins/installed-plugins", "changyan-Linux-amd64.bin").getPath(),
                file.getPath());
    }

    @Test
    public void shouldUseWritablePathForDownloadedPluginsInFaaS() {
        File file = PluginFiles.downloadPluginFile("changyan-Linux-amd64.bin", true, 9080,
                "/var/task/conf/plugins/installed-plugins");

        assertEquals(new File("/tmp/9080/plugins/installed-plugins", "changyan-Linux-amd64.bin").getPath(),
                file.getPath());
    }

    private static void writeFile(File directory, String name) throws Exception {
        try (FileOutputStream outputStream = new FileOutputStream(new File(directory, name))) {
            outputStream.write("plugin".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
