package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugin.common.model.BlogRunTime;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.vo.PluginVO;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginArtifactBootstrapperTest {

    @Test
    public void shouldResolveInstalledArtifactPluginIdByMapKeyOrShortName() {
        PluginCore pluginCore = new PluginCore();
        PluginVO pluginVO = new PluginVO();
        Plugin plugin = new Plugin();
        plugin.setId("plugin-id");
        plugin.setShortName("reminder");
        pluginVO.setPlugin(plugin);
        pluginCore.getPluginInfoMap().put("legacy-key", pluginVO);

        assertEquals("plugin-id", PluginArtifactBootstrapper.pluginIdForInstalledArtifact(pluginCore, "reminder"));
        assertFalse(PluginArtifactBootstrapper.pluginIdForInstalledArtifact(pluginCore, "email").trim().isEmpty());
    }

    @Test
    public void shouldSkipMissingPluginDownloadDuringOnDemandBootstrap() {
        PluginCore pluginCore = new PluginCore();
        pluginCore.getSetting().getRuntime().setOnDemandEnabled(true);

        assertFalse(PluginArtifactBootstrapper.shouldDownloadMissingPluginFilesDuringBootstrap(pluginCore));
    }

    @Test
    public void shouldTreatMissingSettingAsOnDemandBootstrap() {
        assertFalse(PluginArtifactBootstrapper.shouldDownloadMissingPluginFilesDuringBootstrap(null));
    }

    @Test
    public void shouldDownloadMissingPluginsDuringStartupBootstrap() {
        PluginCore pluginCore = new PluginCore();
        pluginCore.getSetting().getRuntime().setOnDemandEnabled(false);

        assertTrue(PluginArtifactBootstrapper.shouldDownloadMissingPluginFilesDuringBootstrap(pluginCore));
    }

    @Test
    public void shouldSkipStartupMissingPluginDownloadWhenAutoDownloadDisabled() {
        PluginCore pluginCore = new PluginCore();
        pluginCore.getSetting().getRuntime().setOnDemandEnabled(false);
        pluginCore.getSetting().setDisableAutoDownloadLostFile(true);

        assertFalse(PluginArtifactBootstrapper.shouldDownloadMissingPluginFilesDuringBootstrap(pluginCore));
    }

    @Test
    public void shouldPartitionRequiredPluginsFromOptionalRunnablePlugins() {
        Map<String, String> requiredPlugins = new LinkedHashMap<>();
        requiredPlugins.put("comment", "comment-fallback");
        PluginMetadataBootstrapper metadataBootstrapper = new PluginMetadataBootstrapper(null, ignored -> {
            return true;
        });
        PluginArtifactBootstrapper artifactBootstrapper =
                new PluginArtifactBootstrapper(requiredPlugins, metadataBootstrapper);
        PluginCore pluginCore = new PluginCore();
        pluginCore.getPluginInfoMap().put("comment", plugin("comment-id", "comment"));
        pluginCore.getPluginInfoMap().put("reminder", plugin("reminder-id", "reminder"));

        Map<String, String> required = artifactBootstrapper.getRequiredRunnablePlugins(pluginCore);
        Map<String, String> optional = artifactBootstrapper.getOptionalRunnablePlugins(pluginCore);

        assertEquals(Collections.singletonMap("comment", "comment-id"), required);
        assertEquals(Collections.singletonMap("reminder", "reminder-id"), optional);
    }

    @Test
    public void shouldFinishRequiredArtifactReconcileBeforeStartingOptionalMetadata() throws Exception {
        File pluginDirectory = Files.createTempDirectory("plugin-bootstrap-required-first").toFile();
        File requiredFile = new File(pluginDirectory, "required-comment.jar");
        File optionalFile = new File(pluginDirectory, "optional-reminder.jar");
        Files.write(requiredFile.toPath(), new byte[]{1});
        Files.write(optionalFile.toPath(), new byte[]{1});
        PhasedMetadataBootstrapper metadataBootstrapper = new PhasedMetadataBootstrapper(requiredFile);
        Map<String, String> requiredPlugins = Collections.singletonMap("required-comment", "comment-id");
        PluginConfig pluginConfig = new PluginConfig(null, null, 0,
                pluginDirectory.getAbsolutePath(), new BlogRunTime());
        PluginArtifactBootstrapper artifactBootstrapper = new PluginArtifactBootstrapper(
                requiredPlugins, metadataBootstrapper, metadataBootstrapper.sessionRegistry(), pluginConfig);
        PluginCore pluginCore = new PluginCore();
        pluginCore.getSetting().getRuntime().setOnDemandEnabled(true);
        pluginCore.getSetting().getRuntime().setMaxConcurrentStarts(2L);
        Thread reconcileThread = new Thread(() -> artifactBootstrapper.reconcilePluginArtifacts(pluginCore));
        try {
            reconcileThread.start();
            assertTrue(metadataBootstrapper.requiredStartEntered.await(1, TimeUnit.SECONDS));

            assertFalse(metadataBootstrapper.optionalStartEntered.await(100, TimeUnit.MILLISECONDS));
            assertFalse(metadataBootstrapper.optionalStartedBeforeRequiredCompleted.get());

            metadataBootstrapper.releaseRequiredStart.countDown();
            reconcileThread.join(5000L);

            assertFalse(reconcileThread.isAlive());
            assertEquals(0L, metadataBootstrapper.optionalStartEntered.getCount());
            assertFalse(metadataBootstrapper.optionalStartedBeforeRequiredCompleted.get());
        } finally {
            metadataBootstrapper.releaseRequiredStart.countDown();
            reconcileThread.join(5000L);
            requiredFile.delete();
            optionalFile.delete();
            pluginDirectory.delete();
        }
    }

    @Test
    public void shouldLimitMetadataBootstrapExecutorToConfiguredConcurrentStarts() throws Exception {
        File pluginDirectory = Files.createTempDirectory("plugin-bootstrap-concurrency").toFile();
        List<File> pluginFiles = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                File pluginFile = new File(pluginDirectory, "plugin-" + i + ".jar");
                Files.write(pluginFile.toPath(), new byte[]{1});
                pluginFiles.add(pluginFile);
            }
            BlockingMetadataBootstrapper metadataBootstrapper = new BlockingMetadataBootstrapper(2);
            PluginConfig pluginConfig = new PluginConfig(null, null, 0,
                    pluginDirectory.getAbsolutePath(), new BlogRunTime());
            PluginArtifactBootstrapper artifactBootstrapper = new PluginArtifactBootstrapper(
                    Collections.emptyMap(), metadataBootstrapper, metadataBootstrapper.sessionRegistry(), pluginConfig);
            PluginCore pluginCore = new PluginCore();
            pluginCore.getSetting().getRuntime().setOnDemandEnabled(true);
            pluginCore.getSetting().getRuntime().setMaxConcurrentStarts(2L);

            artifactBootstrapper.reconcilePluginArtifacts(pluginCore);

            assertEquals(2, artifactBootstrapper.pluginStartThreads(pluginFiles.size(), pluginCore));
            assertEquals(pluginFiles.size(), metadataBootstrapper.callCount.get());
            assertEquals(2, metadataBootstrapper.maxActive.get());
        } finally {
            for (File pluginFile : pluginFiles) {
                pluginFile.delete();
            }
            pluginDirectory.delete();
        }
    }

    private static class BlockingMetadataBootstrapper extends PluginMetadataBootstrapper {

        private final CountDownLatch firstWave;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger callCount = new AtomicInteger();

        BlockingMetadataBootstrapper(int expectedConcurrency) {
            super(null, pluginShortName -> {
                return true;
            });
            this.firstWave = new CountDownLatch(expectedConcurrency);
        }

        @Override
        public boolean shouldStartPluginFileForMetadata(File pluginFile, String pluginId, PluginCore pluginCore) {
            return true;
        }

        @Override
        public boolean startPluginFileForMetadata(File pluginFile, String pluginId) {
            callCount.incrementAndGet();
            int currentActive = active.incrementAndGet();
            maxActive.updateAndGet(previous -> Math.max(previous, currentActive));
            firstWave.countDown();
            try {
                if (!firstWave.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("metadata executor did not reach configured concurrency");
                }
                Thread.sleep(20L);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static class PhasedMetadataBootstrapper extends PluginMetadataBootstrapper {

        private final File requiredFile;
        private final CountDownLatch requiredStartEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRequiredStart = new CountDownLatch(1);
        private final CountDownLatch optionalStartEntered = new CountDownLatch(1);
        private final AtomicBoolean requiredCompleted = new AtomicBoolean(false);
        private final AtomicBoolean optionalStartedBeforeRequiredCompleted = new AtomicBoolean(false);

        PhasedMetadataBootstrapper(File requiredFile) {
            super(null, ignored -> {
                return true;
            });
            this.requiredFile = requiredFile;
        }

        @Override
        public boolean shouldStartPluginFileForMetadata(File pluginFile, String pluginId, PluginCore pluginCore) {
            return true;
        }

        @Override
        public boolean startPluginFileForMetadata(File pluginFile, String pluginId) {
            if (requiredFile.equals(pluginFile)) {
                requiredStartEntered.countDown();
                try {
                    if (!releaseRequiredStart.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("required metadata start was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                requiredCompleted.set(true);
                return true;
            }
            if (!requiredCompleted.get()) {
                optionalStartedBeforeRequiredCompleted.set(true);
            }
            optionalStartEntered.countDown();
            return true;
        }
    }

    private static PluginVO plugin(String pluginId, String pluginShortName) {
        Plugin plugin = new Plugin();
        plugin.setId(pluginId);
        plugin.setShortName(pluginShortName);
        PluginVO pluginVO = new PluginVO();
        pluginVO.setPlugin(plugin);
        return pluginVO;
    }
}
