package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugincore.server.model.PluginCore;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginStartupCoordinatorTest {

    @Test
    public void shouldFinishRequiredPhaseBeforeReconcilingOrStartingOptionalPlugins() throws Exception {
        File pluginFile = Files.createTempFile("plugin-startup-priority", ".jar").toFile();
        Files.write(pluginFile.toPath(), new byte[]{1});
        RecordingArtifactBootstrapper artifactBootstrapper = new RecordingArtifactBootstrapper(pluginFile);
        PluginStartupCoordinator coordinator = new PluginStartupCoordinator(null, artifactBootstrapper);
        PluginCore pluginCore = new PluginCore();
        pluginCore.getSetting().getRuntime().setMaxRunningPlugins(1L);
        pluginCore.getSetting().getRuntime().setMaxConcurrentStarts(2L);
        Thread bootstrapThread = new Thread(() -> coordinator.startRunnablePlugins(pluginCore));
        try {
            bootstrapThread.start();
            assertTrue(artifactBootstrapper.requiredStartEntered.await(1, TimeUnit.SECONDS));

            assertFalse(artifactBootstrapper.optionalReconcileEntered.await(100, TimeUnit.MILLISECONDS));
            assertFalse(artifactBootstrapper.optionalStartedBeforeRequiredCompleted.get());

            artifactBootstrapper.releaseRequiredStart.countDown();
            bootstrapThread.join(5000L);

            assertFalse(bootstrapThread.isAlive());
            assertEquals(0L, artifactBootstrapper.optionalReconcileEntered.getCount());
            assertFalse(artifactBootstrapper.optionalStartedBeforeRequiredCompleted.get());
            assertEquals("reconcile:required", artifactBootstrapper.events.get(0));
            assertEquals("start:comment", artifactBootstrapper.events.get(1));
            assertEquals("reconcile:optional", artifactBootstrapper.events.get(2));
        } finally {
            artifactBootstrapper.releaseRequiredStart.countDown();
            bootstrapThread.join(5000L);
            pluginFile.delete();
        }
    }

    private static class RecordingArtifactBootstrapper extends PluginArtifactBootstrapper {

        private final File pluginFile;
        private final CountDownLatch requiredStartEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRequiredStart = new CountDownLatch(1);
        private final CountDownLatch optionalReconcileEntered = new CountDownLatch(1);
        private final AtomicBoolean requiredCompleted = new AtomicBoolean(false);
        private final AtomicBoolean optionalStartedBeforeRequiredCompleted = new AtomicBoolean(false);
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        RecordingArtifactBootstrapper(File pluginFile) {
            super(Collections.emptyMap(), new PluginMetadataBootstrapper(null, ignored -> {
                return true;
            }));
            this.pluginFile = pluginFile;
        }

        @Override
        void reconcileRequiredPluginArtifacts(PluginCore currentPluginCore) {
            events.add("reconcile:required");
        }

        @Override
        void reconcileOptionalPluginArtifacts(PluginCore currentPluginCore) {
            events.add("reconcile:optional");
            optionalReconcileEntered.countDown();
        }

        @Override
        Map<String, String> getRequiredRunnablePlugins(PluginCore currentPluginCore) {
            Map<String, String> plugins = new LinkedHashMap<>();
            plugins.put("comment", "comment");
            return plugins;
        }

        @Override
        Map<String, String> getOptionalRunnablePlugins(PluginCore currentPluginCore) {
            Map<String, String> plugins = new LinkedHashMap<>();
            plugins.put("reminder", "reminder");
            plugins.put("statistics", "statistics");
            return plugins;
        }

        @Override
        File availablePluginFile(String pluginShortName) {
            return pluginFile;
        }

        @Override
        boolean startPluginAndAwait(File pluginFile, String pluginId) {
            events.add("start:" + pluginId);
            if ("comment".equals(pluginId)) {
                requiredStartEntered.countDown();
                try {
                    if (!releaseRequiredStart.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("required plugin start was not released");
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
            return true;
        }
    }
}
