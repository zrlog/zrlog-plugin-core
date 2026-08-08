package com.zrlog.plugincore.server.runtime.plugin.bootstrap;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.process.PluginProcessRuntime;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.state.PluginStartCoordinator;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.vo.PluginVO;
import org.junit.Test;
import sun.misc.Unsafe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PluginMetadataBootstrapperTest {

    private final PluginMetadataBootstrapper metadataBootstrapper =
            new PluginMetadataBootstrapper(null, pluginShortName -> {
                return true;
            });

    @Test
    public void shouldSkipMissingPluginFile() {
        assertFalse(metadataBootstrapper.shouldStartPluginFileForMetadata(new File("/tmp/missing-plugin.jar"),
                "plugin-id", new PluginCore()));
    }

    @Test
    public void shouldStartWhenSnapshotHasNoPluginMetadata() throws Exception {
        File pluginFile = Files.createTempFile("metadata-bootstrap", ".jar").toFile();
        try {
            Files.write(pluginFile.toPath(), new byte[]{1});

            assertTrue(metadataBootstrapper.shouldStartPluginFileForMetadata(pluginFile, "plugin-id", new PluginCore()));
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldSkipWhenPluginFileMd5MatchesSnapshot() throws Exception {
        File pluginFile = Files.createTempFile("metadata-bootstrap", ".jar").toFile();
        try {
            Files.write(pluginFile.toPath(), new byte[]{1});
            PluginCore pluginCore = new PluginCore();
            Plugin plugin = new Plugin();
            plugin.setId("plugin-id");
            plugin.setShortName(PluginFiles.getPluginShortName(pluginFile));
            PluginVO pluginVO = new PluginVO();
            pluginVO.setPlugin(plugin);
            pluginVO.setFileMd5(PluginFiles.pluginFileMd5(pluginFile));
            pluginCore.getPluginInfoMap().put(plugin.getShortName(), pluginVO);

            assertFalse(metadataBootstrapper.shouldStartPluginFileForMetadata(pluginFile, "plugin-id", pluginCore));
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldStopProcessStartedForMetadataWhenRegistrationFails() throws Exception {
        File pluginFile = pluginFile("metadata-failure");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            storePlugin(pluginFile, "plugin-id", true, null);
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, false, true, true);
            List<String> stoppedPlugins = new ArrayList<>();
            PluginMetadataBootstrapper bootstrapper =
                    new PluginMetadataBootstrapper(processRuntime, sessionRegistry, pluginShortName -> {
                        stoppedPlugins.add(pluginShortName);
                        return true;
                    });

            try {
                assertFalse(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));

                assertEquals(1, processRuntime.loadCount);
                assertEquals(1, processRuntime.destroyIfCurrentCount);
                assertSame(processRuntime.startedProcess, processRuntime.destroyedExpectedProcess);
                assertTrue(stoppedPlugins.isEmpty());
            } finally {
                Thread.interrupted();
            }
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldAlwaysStopFailedMetadataProcessEvenWhenDemandWasClaimed() throws Exception {
        File pluginFile = pluginFile("metadata-failure-demanded");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            storePlugin(pluginFile, "plugin-id", true, null);
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, false, true, true);
            PluginStartCoordinator startCoordinator = new PluginStartCoordinator();
            startCoordinator.claimDemand("plugin-id", 1000L);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, pluginShortName -> {
                        return true;
                    }, startCoordinator);

            try {
                assertFalse(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));
                assertEquals(1, processRuntime.destroyIfCurrentCount);
                assertSame(processRuntime.startedProcess, processRuntime.destroyedExpectedProcess);
            } finally {
                Thread.interrupted();
            }
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldStopSuccessfulOnDemandMetadataProcessWhenUnclaimed() throws Exception {
        File pluginFile = pluginFile("metadata-success");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            storePlugin(pluginFile, "plugin-id", true, null);
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, true, true);
            List<String> stoppedPlugins = new ArrayList<>();
            PluginMetadataBootstrapper bootstrapper =
                    new PluginMetadataBootstrapper(processRuntime, sessionRegistry, pluginShortName -> {
                        stoppedPlugins.add(pluginShortName);
                        return true;
                    });

            assertTrue(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));

            assertEquals(1, processRuntime.loadCount);
            assertEquals(1, processRuntime.destroyIfCurrentCount);
            assertSame(processRuntime.startedProcess, processRuntime.destroyedExpectedProcess);
            assertTrue(stoppedPlugins.isEmpty());
            assertEquals(PluginFiles.pluginFileMd5(pluginFile),
                    PluginCoreDAO.getInstance().getPluginVOById("plugin-id").getFileMd5());
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldKeepSuccessfulOnDemandMetadataProcessWhenDemandIsClaimed() throws Exception {
        File pluginFile = pluginFile("metadata-demanded");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            storePlugin(pluginFile, "plugin-id", true, null);
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, true, true);
            PluginStartCoordinator startCoordinator = new PluginStartCoordinator();
            startCoordinator.claimDemand("plugin-id", 1000L);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, pluginShortName -> {
                        return true;
                    }, startCoordinator);

            assertTrue(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));

            assertEquals(1, processRuntime.loadCount);
            assertEquals(0, processRuntime.destroyIfCurrentCount);
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldFinalizeMetadataSingleFlightBeforeStoppingUnclaimedProcess() throws Exception {
        File pluginFile = pluginFile("metadata-finalize-before-stop");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            storePlugin(pluginFile, "plugin-id", true, null);
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            PluginStartCoordinator startCoordinator = new PluginStartCoordinator();
            CleanupCoordinatingProcessRuntime processRuntime = new CleanupCoordinatingProcessRuntime(
                    sessionRegistry, startCoordinator);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, pluginShortName -> {
                        return true;
                    }, startCoordinator);

            assertTrue(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));

            assertTrue(processRuntime.nestedStartResult);
            assertEquals(1, processRuntime.nestedStartCount.get());
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldNotStopAlreadyRunningPluginWhenArtifactIsUnchanged() throws Exception {
        File pluginFile = pluginFile("metadata-running");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
            storePlugin(pluginFile, "plugin-id", true, PluginFiles.pluginFileMd5(pluginFile));
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            sessionRegistry.register("plugin-id", pluginShortName);
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, false, false);
            List<String> stoppedPlugins = new ArrayList<>();
            PluginMetadataBootstrapper bootstrapper =
                    new PluginMetadataBootstrapper(processRuntime, sessionRegistry, stoppedPluginShortName -> {
                        stoppedPlugins.add(stoppedPluginShortName);
                        return true;
                    });

            assertTrue(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));

            assertEquals(1, processRuntime.loadCount);
            assertEquals(0, processRuntime.destroyIfCurrentCount);
            assertTrue(stoppedPlugins.isEmpty());
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldAbortChangedArtifactWhenPreviousProcessCannotStop() throws Exception {
        File pluginFile = pluginFile("metadata-stop-failure");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
            storePlugin(pluginFile, "plugin-id", true, "previous-md5");
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            sessionRegistry.register("plugin-id", pluginShortName);
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, false, true);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, ignoredPlugin -> false);

            assertFalse(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));

            assertEquals(0, processRuntime.loadCount);
            assertEquals("previous-md5", PluginCoreDAO.getInstance().getPluginVOById("plugin-id").getFileMd5());
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldNotReuseOldReadySessionWhenChangedArtifactFailsToSpawn() throws Exception {
        File pluginFile = pluginFile("metadata-spawn-failure");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
            storePlugin(pluginFile, "plugin-id", true, "previous-md5");
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            sessionRegistry.register("plugin-id", pluginShortName);
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, false, false);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, ignoredPlugin -> true);

            assertFalse(bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id"));

            assertEquals(1, processRuntime.loadCount);
            assertEquals("previous-md5", PluginCoreDAO.getInstance().getPluginVOById("plugin-id").getFileMd5());
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldCollapseConcurrentMetadataStartsForSamePlugin() throws Exception {
        File pluginFile = pluginFile("metadata-concurrent");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            storePlugin(pluginFile, "plugin-id", true, null);
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            BlockingProcessRuntime processRuntime = new BlockingProcessRuntime(sessionRegistry);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, pluginShortName -> {
                        return true;
                    }, new PluginStartCoordinator());
            AtomicBoolean firstResult = new AtomicBoolean();
            AtomicBoolean secondResult = new AtomicBoolean();

            Thread first = new Thread(() -> firstResult.set(
                    bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id")));
            first.start();
            assertTrue(processRuntime.firstLoadStarted.await(1, TimeUnit.SECONDS));

            Thread second = new Thread(() -> secondResult.set(
                    bootstrapper.startPluginFileForMetadata(pluginFile, "plugin-id")));
            second.start();
            assertFalse(processRuntime.secondLoadStarted.await(100, TimeUnit.MILLISECONDS));

            processRuntime.releaseFirstLoad.countDown();
            first.join(2000L);
            second.join(2000L);

            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(firstResult.get());
            assertTrue(secondResult.get());
            assertEquals(1, processRuntime.loadCount.get());
            assertEquals(1, processRuntime.maxActiveLoads.get());
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldCollapseUnknownMetadataStartsByStableArtifactIdentity() throws Exception {
        File pluginFile = pluginFile("metadata-unknown-concurrent");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            BlockingProcessRuntime processRuntime = new BlockingProcessRuntime(sessionRegistry);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, pluginShortName -> {
                        return true;
                    }, new PluginStartCoordinator());
            AtomicBoolean firstResult = new AtomicBoolean();
            AtomicBoolean secondResult = new AtomicBoolean();

            Thread first = new Thread(() -> firstResult.set(
                    bootstrapper.startPluginFileForMetadata(pluginFile, "unknown-id-1")));
            first.start();
            assertTrue(processRuntime.firstLoadStarted.await(1, TimeUnit.SECONDS));

            Thread second = new Thread(() -> secondResult.set(
                    bootstrapper.startPluginFileForMetadata(pluginFile, "unknown-id-2")));
            second.start();
            assertFalse(processRuntime.secondLoadStarted.await(100, TimeUnit.MILLISECONDS));

            processRuntime.releaseFirstLoad.countDown();
            first.join(2000L);
            second.join(2000L);

            assertTrue(firstResult.get());
            assertTrue(secondResult.get());
            assertEquals(1, processRuntime.loadCount.get());
        } finally {
            pluginFile.delete();
        }
    }

    @Test
    public void shouldBackOffUnknownMetadataRetriesUsingStableArtifactIdentity() throws Exception {
        File pluginFile = pluginFile("metadata-unknown-backoff");
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            StubSessionRegistry sessionRegistry = new StubSessionRegistry();
            RecordingProcessRuntime processRuntime = new RecordingProcessRuntime(sessionRegistry, false, true, true);
            PluginMetadataBootstrapper bootstrapper = new PluginMetadataBootstrapper(
                    processRuntime, sessionRegistry, pluginShortName -> {
                        return true;
                    }, new PluginStartCoordinator());

            try {
                assertFalse(bootstrapper.startPluginFileForMetadata(pluginFile, "unknown-id-1"));
                Thread.interrupted();
                assertFalse(bootstrapper.startPluginFileForMetadata(pluginFile, "unknown-id-2"));

                assertEquals(1, processRuntime.loadCount);
                assertEquals(1, processRuntime.destroyIfCurrentCount);
            } finally {
                Thread.interrupted();
            }
        } finally {
            pluginFile.delete();
        }
    }

    private File pluginFile(String prefix) throws Exception {
        File pluginFile = Files.createTempFile(prefix, ".jar").toFile();
        Files.write(pluginFile.toPath(), new byte[]{1});
        return pluginFile;
    }

    private void storePlugin(File pluginFile, String pluginId, boolean onDemandEnabled, String fileMd5) {
        String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
        PluginCoreDAO.getInstance().update(pluginCore -> {
            pluginCore.getSetting().getRuntime().setOnDemandEnabled(onDemandEnabled);
            Plugin plugin = new Plugin();
            plugin.setId(pluginId);
            plugin.setShortName(pluginShortName);
            PluginVO pluginVO = new PluginVO();
            pluginVO.setPlugin(plugin);
            pluginVO.setFileMd5(fileMd5);
            pluginCore.getPluginInfoMap().put(pluginShortName, pluginVO);
        });
    }

    private static IOSession session(String pluginId, String pluginShortName) {
        try {
            IOSession session = (IOSession) unsafe().allocateInstance(IOSession.class);
            Plugin plugin = new Plugin();
            plugin.setId(pluginId);
            plugin.setShortName(pluginShortName);
            Field pluginField = IOSession.class.getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(session, plugin);
            return session;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static class StubSessionRegistry extends PluginSessionRegistry {

        private volatile IOSession session;

        void register(String pluginId, String pluginShortName) {
            session = PluginMetadataBootstrapperTest.session(pluginId, pluginShortName);
        }

        @Override
        public boolean isRunningByPluginShortName(String pluginShortName) {
            return matches(null, pluginShortName);
        }

        @Override
        public IOSession getLocalSessionByPluginId(String pluginId) {
            return matches(pluginId, null) ? session : null;
        }

        @Override
        public IOSession getLocalSessionByPluginShortName(String pluginShortName) {
            return matches(null, pluginShortName) ? session : null;
        }

        @Override
        public boolean isReady(IOSession candidate) {
            return candidate != null && candidate == session;
        }

        private boolean matches(String pluginId, String pluginShortName) {
            if (session == null || session.getPlugin() == null) {
                return false;
            }
            boolean idMatches = pluginId == null || pluginId.equals(session.getPlugin().getId());
            boolean shortNameMatches = pluginShortName == null
                    || pluginShortName.equals(session.getPlugin().getShortName());
            return idMatches && shortNameMatches;
        }
    }

    private static class RecordingProcessRuntime extends PluginProcessRuntime {

        private final StubSessionRegistry sessionRegistry;
        private final boolean registerOnLoad;
        private final boolean interruptAfterLoad;
        private final Process startedProcess;
        private int loadCount;
        private int destroyIfCurrentCount;
        private Process destroyedExpectedProcess;

        RecordingProcessRuntime(StubSessionRegistry sessionRegistry, boolean registerOnLoad, boolean processStarted) {
            this(sessionRegistry, registerOnLoad, processStarted, false);
        }

        RecordingProcessRuntime(StubSessionRegistry sessionRegistry, boolean registerOnLoad, boolean processStarted,
                                boolean interruptAfterLoad) {
            super(sessionRegistry, PluginConfig.unconfigured());
            this.sessionRegistry = sessionRegistry;
            this.registerOnLoad = registerOnLoad;
            this.interruptAfterLoad = interruptAfterLoad;
            this.startedProcess = processStarted ? new FakeProcess() : null;
        }

        @Override
        public Process loadPlugin(File pluginFile, String pluginId) {
            loadCount++;
            if (registerOnLoad) {
                sessionRegistry.register(pluginId, PluginFiles.getPluginShortName(pluginFile));
            }
            if (interruptAfterLoad) {
                Thread.currentThread().interrupt();
            }
            return startedProcess;
        }

        @Override
        public boolean destroyByPluginIdIfCurrent(String pluginId, String pluginShortName, Process expectedProcess) {
            destroyIfCurrentCount++;
            destroyedExpectedProcess = expectedProcess;
            return expectedProcess == startedProcess;
        }
    }

    private static class BlockingProcessRuntime extends PluginProcessRuntime {

        private final StubSessionRegistry sessionRegistry;
        private final CountDownLatch firstLoadStarted = new CountDownLatch(1);
        private final CountDownLatch secondLoadStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstLoad = new CountDownLatch(1);
        private final AtomicInteger loadCount = new AtomicInteger();
        private final AtomicInteger activeLoads = new AtomicInteger();
        private final AtomicInteger maxActiveLoads = new AtomicInteger();

        BlockingProcessRuntime(StubSessionRegistry sessionRegistry) {
            super(sessionRegistry, PluginConfig.unconfigured());
            this.sessionRegistry = sessionRegistry;
        }

        @Override
        public Process loadPlugin(File pluginFile, String pluginId) {
            int currentLoad = loadCount.incrementAndGet();
            int active = activeLoads.incrementAndGet();
            maxActiveLoads.accumulateAndGet(active, Math::max);
            try {
                if (currentLoad == 1) {
                    firstLoadStarted.countDown();
                    await(releaseFirstLoad);
                } else {
                    secondLoadStarted.countDown();
                }
                sessionRegistry.register(pluginId, PluginFiles.getPluginShortName(pluginFile));
                return null;
            } finally {
                activeLoads.decrementAndGet();
            }
        }

        private void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static class CleanupCoordinatingProcessRuntime extends PluginProcessRuntime {

        private final StubSessionRegistry sessionRegistry;
        private final PluginStartCoordinator startCoordinator;
        private final Process startedProcess = new FakeProcess();
        private final AtomicInteger nestedStartCount = new AtomicInteger();
        private boolean nestedStartResult;

        CleanupCoordinatingProcessRuntime(StubSessionRegistry sessionRegistry,
                                          PluginStartCoordinator startCoordinator) {
            super(sessionRegistry, PluginConfig.unconfigured());
            this.sessionRegistry = sessionRegistry;
            this.startCoordinator = startCoordinator;
        }

        @Override
        public Process loadPlugin(File pluginFile, String pluginId) {
            sessionRegistry.register(pluginId, PluginFiles.getPluginShortName(pluginFile));
            return startedProcess;
        }

        @Override
        public boolean destroyByPluginIdIfCurrent(String pluginId, String pluginShortName, Process expectedProcess) {
            nestedStartResult = startCoordinator.start(pluginId, 1, 100, 100, 0, () -> {
                nestedStartCount.incrementAndGet();
                return true;
            });
            return expectedProcess == startedProcess;
        }
    }

    private static class FakeProcess extends Process {

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }
}
