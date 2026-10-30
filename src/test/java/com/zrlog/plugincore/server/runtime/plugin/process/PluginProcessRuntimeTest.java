package com.zrlog.plugincore.server.runtime.plugin.process;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.common.model.BlogRunTime;
import com.zrlog.plugincore.server.runtime.plugin.lifecycle.PluginLifecycleService;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.state.PluginStartCoordinator;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase.DatabaseType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class PluginProcessRuntimeTest {

    private final DatabaseType databaseType;

    public PluginProcessRuntimeTest(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    @Parameterized.Parameters(name = "{0}")
    public static DatabaseType[] databases() {
        return DatabaseType.values();
    }

    @Test(timeout = 15000)
    public void registrationWaitsForManagedProcessIdentityBeforePublishingSession() throws Exception {
        assertRegistrationDuringSpawn(false);
    }

    @Test(timeout = 15000)
    public void registrationRejectsManagedProcessWhoseStartupFailed() throws Exception {
        assertRegistrationDuringSpawn(true);
    }

    private void assertRegistrationDuringSpawn(boolean failStartup) throws Exception {
        Path root = Files.createTempDirectory("plugin-registration-during-spawn");
        CountDownLatch spawning = new CountDownLatch(1);
        CountDownLatch finishSpawn = new CountDownLatch(1);
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        ControlledProcess process = failStartup ? new ControlledProcess(true) : new ManagedControlledProcess(true);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType);
             SocketChannel channel = SocketChannel.open(); Selector selector = Selector.open()) {
            IOSession session = new IOSession(channel, selector,
                    new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)),
                    (IActionHandler) Proxy.newProxyInstance(IActionHandler.class.getClassLoader(),
                            new Class<?>[]{IActionHandler.class}, (proxy, method, args) -> null));
            Plugin plugin = new Plugin();
            plugin.setId("plugin-id"); plugin.setShortName("fixture"); session.setPlugin(plugin);
            TestableProcessRuntime runtime = new TestableProcessRuntime(root, process, false) {
                @Override Process startPluginProcess(LaunchCommand command) {
                    spawning.countDown();
                    try {
                        if (!finishSpawn.await(5, TimeUnit.SECONDS)) throw new AssertionError("Spawn was not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return process;
                }
            };
            File file = pluginFile(root, "fixture.jar");
            Thread starter = new Thread(() -> {
                try { runtime.loadPlugin(file, "plugin-id"); }
                catch (Throwable error) { startFailure.set(error); }
            }, "fixture-start");
            Thread registration = new Thread(() -> {
                registering.countDown();
                try { new PluginLifecycleService(runtime).registerPlugin(session); }
                catch (Throwable error) { registrationFailure.set(error); }
                finally { registered.countDown(); }
            }, "fixture-register");
            try {
                starter.start(); assertTrue(spawning.await(5, TimeUnit.SECONDS));
                registration.start(); assertTrue(registering.await(5, TimeUnit.SECONDS));
                assertFalse("Registration must not publish a session before its managed identity exists",
                        registered.await(300, TimeUnit.MILLISECONDS));
                assertTrue(runtime.sessionRegistry().getAllLocalSessions().isEmpty());
                finishSpawn.countDown(); starter.join(5000); registration.join(5000);
                assertFalse(starter.isAlive()); assertFalse(registration.isAlive());
                if (failStartup) {
                    assertTrue(startFailure.get() instanceof IllegalStateException);
                    assertTrue(registrationFailure.get() instanceof IllegalStateException);
                    assertTrue(runtime.sessionRegistry().getAllLocalSessions().isEmpty());
                } else {
                    org.junit.Assert.assertNull(startFailure.get());
                    org.junit.Assert.assertNull(registrationFailure.get());
                    assertEquals(Long.valueOf(process.pid()), runtime.sessionRegistry().processId(session));
                    assertEquals(runtime.runtimeInstanceIdByPluginId("plugin-id").orElseThrow(),
                            runtime.sessionRegistry().runtimeInstanceId(session));
                    assertTrue(runtime.sessionRegistry().getAllLocalSessions().contains(session));
                }
            } finally {
                finishSpawn.countDown(); starter.join(5000); registration.join(5000);
                runtime.shutdownProcesses(1000, 100); runtime.sessionRegistry().shutdown();
            }
        } finally { delete(root); }
    }

    @Test
    public void shouldLaunchExecutablePluginWithoutChangingPermissions() throws Exception {
        Path root = Files.createTempDirectory("plugin-executable-permissions");
        try {
            org.junit.Assume.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
            File plugin = pluginFile(root, "fixture-Linux-amd64.bin");
            var permissions = PosixFilePermissions.fromString("r-x------");
            Files.setPosixFilePermissions(plugin.toPath(), permissions);
            ManagedControlledProcess process = new ManagedControlledProcess(true);
            try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
                TestableProcessRuntime runtime = new TestableProcessRuntime(root, process, false);
                try {
                    org.junit.Assert.assertSame(process, runtime.loadPlugin(plugin, "plugin-id"));
                    assertEquals(permissions, Files.getPosixFilePermissions(plugin.toPath()));
                } finally {
                    runtime.shutdownProcesses(1000, 100);
                }
            }
        } finally {
            delete(root);
        }
    }

    @Test
    public void invocationStopChecksBothProcessAndRuntimeIdentity() throws Exception {
        Path root = Files.createTempDirectory("plugin-invocation-stop");
        ManagedControlledProcess process = new ManagedControlledProcess(true);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(root, process, false);
            try {
                runtime.loadPlugin(pluginFile(root, "fixture.jar"), "plugin-id");
                String identity = runtime.runtimeInstanceIdByPluginId("plugin-id").orElseThrow();
                assertFalse(runtime.destroyByRuntimeIdentity("plugin-id", "fixture", "old-runtime", process.pid(), RuntimeDeadline.afterMillis(100)));
                assertFalse(runtime.destroyByRuntimeIdentity("plugin-id", "fixture", identity, process.pid() + 1, RuntimeDeadline.afterMillis(100)));
                assertFalse(runtime.destroyByRuntimeIdentity("plugin-id", "other-plugin", identity, process.pid(), RuntimeDeadline.afterMillis(100)));
                assertTrue(process.isAlive()); assertEquals(0, process.destroyCallCount());
                assertTrue(runtime.destroyByRuntimeIdentity("plugin-id", "fixture", identity, process.pid(), RuntimeDeadline.afterMillis(1000)));
                assertFalse(process.isAlive()); assertFalse(runtime.hasManagedProcessSlot("plugin-id", "fixture"));
            } finally { runtime.shutdownProcesses(1000, 100); }
        } finally { delete(root); }
    }

    @Test
    public void invocationStopKeepsUnterminatedProcessReserved() throws Exception {
        Path root = Files.createTempDirectory("plugin-invocation-survivor");
        ManagedControlledProcess process = new ManagedControlledProcess(false);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(root, process, false);
            try {
                runtime.loadPlugin(pluginFile(root, "fixture.jar"), "plugin-id");
                String identity = runtime.runtimeInstanceIdByPluginId("plugin-id").orElseThrow();
                assertFalse(runtime.destroyByRuntimeIdentity("plugin-id", "fixture", identity, process.pid(), RuntimeDeadline.afterMillis(100)));
                assertTrue(process.isAlive()); assertEquals(2, process.destroyCallCount());
                assertTrue(runtime.hasManagedProcessSlot("plugin-id", "fixture"));
                assertEquals(identity, runtime.runtimeInstanceIdByPluginId("plugin-id").orElseThrow());
            } finally { process.markExited(); runtime.shutdownProcesses(1000, 100); }
        } finally { delete(root); }
    }

    @Test(timeout = 5000)
    public void deadlineStopsWaitingForProcessLifecycleWithoutSpawning() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-budget-lock");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger spawned = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            ManagedControlledProcess process = new ManagedControlledProcess(true);
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false) {
                @Override
                Process startPluginProcess(LaunchCommand command) {
                    spawned.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeDeadline.Expired();
                    }
                    return process;
                }
            };
            File first = pluginFile(pluginRoot, "first.jar");
            File second = pluginFile(pluginRoot, "second.jar");
            Thread owner = new Thread(() -> {
                try {
                    runtime.loadPlugin(first, "first");
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            try {
                owner.start();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                long startedAt = System.nanoTime();
                try {
                    runtime.loadPlugin(second, "second", RuntimeDeadline.afterMillis(150));
                    org.junit.Assert.fail("busy lifecycle must exhaust the caller's budget");
                } catch (RuntimeDeadline.Expired expected) {
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1000);
                }
                assertEquals(1, spawned.get());
                assertFalse(runtime.hasManagedProcessSlot("second", "second"));
            } finally {
                release.countDown();
                owner.join(1000);
                runtime.shutdownProcesses(1000, 100);
            }
            assertFalse(owner.isAlive());
            org.junit.Assert.assertNull(failure.get());
        } finally {
            delete(pluginRoot);
        }
    }

    @Test(timeout = 3000)
    public void failedProcessInitializationUsesRemainingCleanupBudget() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-budget-cleanup");
        SlowWaitProcess process = new SlowWaitProcess();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false);
            try {
                long startedAt = System.nanoTime();
                try {
                    runtime.loadPlugin(pluginFile(pluginRoot, "broken.jar"), "broken", RuntimeDeadline.afterMillis(150));
                    org.junit.Assert.fail("Expected pid failure after spawn");
                } catch (IllegalStateException expected) {
                    assertEquals("simulated pid failure", expected.getMessage());
                }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1000);
                assertEquals(2, process.destroyCallCount());
                assertTrue("retain ownership while the process is still alive", runtime.hasManagedProcessSlot("broken", "broken"));
            } finally {
                process.markExited();
                runtime.shutdownProcesses(1000, 100);
            }
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldUsePluginHomeAsWorkingDirectoryForNativePlugin() {
        String userDir = "/tmp/9080/reminder/usr/";
        String tmpDir = "/tmp/9080/reminder/tmp/";
        File pluginFile = new File("/var/task/conf/plugins/installed-plugins/reminder-Linux-amd64.bin");

        PluginProcessRuntime.LaunchCommand command = PluginProcessRuntime.buildLaunchCommand(
                pluginFile, 9080, "plugin-id", userDir, tmpDir, "", "/opt/java"
        );

        assertEquals(pluginFile.getAbsolutePath(), command.program);
        assertEquals(Arrays.asList("9080", "plugin-id"), command.args);
        assertEquals(new File(userDir), command.workingDirectory);
        assertEquals(userDir, command.environment.get("HOME"));
        assertEquals(tmpDir, command.environment.get("TMPDIR"));
        assertEquals("native", PluginProcessRuntime.runtimeMode(pluginFile));
    }

    @Test
    public void shouldKeepJarSystemPropertiesAndUseSameWorkingDirectory() {
        String userDir = "/tmp/9080/reminder/usr/";
        String tmpDir = "/tmp/9080/reminder/tmp/";
        File pluginFile = new File("/var/task/conf/plugins/installed-plugins/reminder.jar");

        PluginProcessRuntime.LaunchCommand command = PluginProcessRuntime.buildLaunchCommand(
                pluginFile, 9080, "plugin-id", userDir, tmpDir, "-Dfile.encoding=UTF-8 -Xmx32m", "/opt/java"
        );

        assertEquals("/opt/java/bin/java", command.program);
        assertEquals(Arrays.asList(
                "-Djava.io.tmpdir=" + tmpDir,
                "-Duser.dir=" + userDir,
                "-Duser.home=" + userDir,
                "-Dfile.encoding=UTF-8",
                "-Xmx32m",
                "-jar",
                pluginFile.getAbsolutePath(),
                "9080",
                "plugin-id"
        ), command.args);
        assertEquals(new File(userDir), command.workingDirectory);
        assertEquals(userDir, command.environment.get("HOME"));
        assertEquals(tmpDir, command.environment.get("TMPDIR"));
        assertEquals("process", PluginProcessRuntime.runtimeMode(pluginFile));
    }

    @Test
    public void shouldResolvePluginExecutableBeforeChangingWorkingDirectory() {
        File pluginFile = new File("conf/plugins/installed-plugins/reminder-Linux-amd64.bin");

        PluginProcessRuntime.LaunchCommand command = PluginProcessRuntime.buildLaunchCommand(
                pluginFile, 9080, "plugin-id", "/tmp/9080/reminder/usr/", "/tmp/9080/reminder/tmp/", "", "/opt/java"
        );

        assertEquals(pluginFile.getAbsolutePath(), command.program);
    }

    @Test
    public void shouldNotDestroyProcessWhenOutputStreamEnds() throws Exception {
        PluginProcessRuntime processRuntime = new PluginProcessRuntime();
        FakeProcess process = new FakeProcess();

        processRuntime.drainProcessOutput(process, new ByteArrayInputStream(new byte[0]), "reminder", "PINFO",
                "plugin-id", new AtomicBoolean(false));

        assertFalse(process.destroyed);
    }

    @Test
    public void shouldStartMoreThanFourDistinctPluginsOnDemand() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-many");
        AtomicInteger spawned = new AtomicInteger();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, null, false) {
                @Override
                Process startPluginProcess(LaunchCommand launchCommand) {
                    final long pid = 4242L + spawned.incrementAndGet();
                    return new ManagedControlledProcess(true) {
                        @Override
                        public long pid() {
                            return pid;
                        }
                    };
                }
            };
            try {
                for (int i = 0; i < 6; i++) {
                    assertTrue(runtime.loadPlugin(pluginFile(pluginRoot, "plugin-" + i + ".jar"),
                            "plugin-" + i) != null);
                }
                assertEquals(6, spawned.get());
                assertEquals(6, trackedProcessCount(runtime));
            } finally {
                runtime.shutdownProcesses(1000L, 1000L);
            }
            assertEquals(0, trackedProcessCount(runtime));
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldOnlyAcceptConnectionsMatchingReservedProcessIdentity() throws Exception {
        PluginProcessRuntime runtime = new PluginProcessRuntime();
        PluginProcessRuntime.ProcessSlot slot = new PluginProcessRuntime.ProcessSlot("reminder");
        ControlledProcess process = new ControlledProcess(true);
        slot.finishSpawn(process);
        slot.initializationFinished.countDown();
        runtime.registerProcessSlot("plugin-id", slot);

        assertTrue(runtime.hasManagedProcessSlot("plugin-id", "reminder"));
        assertFalse(runtime.hasManagedProcessSlot("other-id", "reminder"));
        assertFalse(runtime.hasManagedProcessSlot("plugin-id", "other-plugin"));
        assertFalse(runtime.hasManagedProcessSlot(null, "reminder"));

        runtime.shutdownProcesses(20L, 0L);
        assertEquals(0, trackedProcessCount(runtime));
    }

    @Test
    public void shouldNotSpawnProcessWhenSlotReservationFails() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-registration");
        File pluginFile = pluginFile(pluginRoot, "registration-failure.jar");
        ControlledProcess process = new ControlledProcess(true);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, true);

            try {
                runtime.loadPlugin(pluginFile, "plugin-id");
                org.junit.Assert.fail("Expected process registration failure");
            } catch (OutOfMemoryError expected) {
                assertEquals("simulated process registration failure", expected.getMessage());
            }

            assertEquals(0, runtime.startCount.get());
            assertEquals(0, process.destroyCount.get());
            assertTrue(process.isAlive());
            assertEquals(0, trackedProcessCount(runtime));
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldNotSpawnProcessAfterShutdownBegins() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-shutdown");
        File pluginFile = pluginFile(pluginRoot, "shutdown.jar");
        ControlledProcess process = new ControlledProcess(true);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false);

            runtime.shutdownProcesses();

            org.junit.Assert.assertNull(runtime.loadPlugin(pluginFile, "plugin-id"));
            assertEquals(0, runtime.startCount.get());
            assertEquals(0, trackedProcessCount(runtime));
        } finally {
            delete(pluginRoot);
        }
    }

    @Test(timeout = 3000L)
    public void shouldKillProcessPublishedAfterShutdownDeadline() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-late-publication");
        File pluginFile = pluginFile(pluginRoot, "late-publication.jar");
        ControlledProcess process = new ControlledProcess(false, true);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicReference<Process> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = null;
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false) {
                @Override
                Process startPluginProcess(LaunchCommand launchCommand) {
                    startEntered.countDown();
                    try {
                        releaseStart.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    return process;
                }
            };
            starter = new Thread(() -> {
                try {
                    result.set(runtime.loadPlugin(pluginFile, "plugin-id"));
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            starter.start();
            assertTrue(startEntered.await(1L, TimeUnit.SECONDS));

            runtime.shutdownProcesses(80L, 20L);
            assertTrue(starter.isAlive());
            releaseStart.countDown();
            starter.join(2000L);

            org.junit.Assert.assertNull(failure.get());
            org.junit.Assert.assertNull(result.get());
            assertFalse(process.isAlive());
            assertEquals(2, process.destroyCallCount());
            assertTrue(awaitTrackedProcessCount(runtime, 0));
        } finally {
            releaseStart.countDown();
            if (starter != null) {
                starter.join(2000L);
            }
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldForceAndUntrackRunningProcessesDuringShutdown() throws Exception {
        ControlledProcess process = new ControlledProcess(false, true);
        PluginProcessRuntime runtime = new PluginProcessRuntime();
        PluginProcessRuntime.ProcessSlot slot = new PluginProcessRuntime.ProcessSlot("reminder");
        slot.finishSpawn(process);
        slot.initializationFinished.countDown();
        runtime.registerProcessSlot("plugin-id", slot);

        runtime.shutdownProcesses(250L, 25L);

        assertFalse(process.isAlive());
        assertEquals(2, process.destroyCount.get());
        assertEquals(0, trackedProcessCount(runtime));
    }

    @Test
    public void shouldOnlyRunExplicitProcessShutdownOnce() throws Exception {
        ControlledProcess process = new ControlledProcess(true);
        PluginProcessRuntime runtime = new PluginProcessRuntime();
        PluginProcessRuntime.ProcessSlot slot = new PluginProcessRuntime.ProcessSlot("reminder");
        slot.finishSpawn(process);
        slot.initializationFinished.countDown();
        runtime.registerProcessSlot("plugin-id", slot);

        runtime.shutdown();
        runtime.shutdown();

        assertFalse(process.isAlive());
        assertEquals(1, process.destroyCount.get());
        assertEquals(0, trackedProcessCount(runtime));
    }

    @Test(timeout = 2000L)
    public void shouldApplyOneShutdownDeadlineAcrossAllProcesses() throws Exception {
        PluginProcessRuntime runtime = new PluginProcessRuntime();
        List<SlowWaitProcess> processes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            SlowWaitProcess process = new SlowWaitProcess();
            processes.add(process);
            PluginProcessRuntime.ProcessSlot slot = new PluginProcessRuntime.ProcessSlot("stubborn-" + i);
            slot.finishSpawn(process);
            slot.initializationFinished.countDown();
            runtime.registerProcessSlot("plugin-" + i, slot);
        }

        long startedAtNanos = System.nanoTime();
        runtime.shutdownProcesses(180L, 40L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

        assertTrue("shutdown exceeded its shared deadline: " + elapsedMs + "ms", elapsedMs < 1000L);
        assertEquals(4, trackedProcessCount(runtime));
        for (SlowWaitProcess process : processes) {
            assertEquals(2, process.destroyCallCount());
            assertEquals(0, process.timedWaitCount.get());
            process.markExited();
        }
        runtime.shutdownProcesses(20L, 0L);
        assertEquals(0, trackedProcessCount(runtime));
    }

    @Test
    public void shouldReportFailedStopAndKeepSlotWhenProcessRemainsAlive() throws Exception {
        ControlledProcess process = new ControlledProcess(false);
        PluginProcessRuntime runtime = new PluginProcessRuntime();
        PluginProcessRuntime.ProcessSlot slot = new PluginProcessRuntime.ProcessSlot("reminder");
        slot.finishSpawn(process);
        slot.initializationFinished.countDown();
        runtime.registerProcessSlot("plugin-id", slot);

        assertFalse(runtime.destroyByPluginId("plugin-id", "reminder"));

        assertTrue(process.isAlive());
        assertEquals(2, process.destroyCount.get());
        assertEquals(1, trackedProcessCount(runtime));
        process.markExited();
    }

    @Test
    public void shouldContinueShutdownWhenOneProcessCannotBeInspected() throws Exception {
        PluginProcessRuntime runtime = new PluginProcessRuntime();
        PluginProcessRuntime.ProcessSlot brokenSlot = new PluginProcessRuntime.ProcessSlot("broken");
        brokenSlot.finishSpawn(new UninspectableProcess());
        brokenSlot.initializationFinished.countDown();
        runtime.registerProcessSlot("broken-id", brokenSlot);
        ControlledProcess healthyProcess = new ControlledProcess(false, true);
        PluginProcessRuntime.ProcessSlot healthySlot = new PluginProcessRuntime.ProcessSlot("healthy");
        healthySlot.finishSpawn(healthyProcess);
        healthySlot.initializationFinished.countDown();
        runtime.registerProcessSlot("healthy-id", healthySlot);

        runtime.shutdownProcesses(250L, 25L);

        assertFalse(healthyProcess.isAlive());
        assertEquals(2, healthyProcess.destroyCount.get());
    }

    @Test
    public void shouldStopSpawnedProcessWhenPublicationFails() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-publication");
        File pluginFile = pluginFile(pluginRoot, "publication-failure.jar");
        ControlledProcess process = new ControlledProcess(false, true);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false, true);

            try {
                runtime.loadPlugin(pluginFile, "plugin-id");
                org.junit.Assert.fail("Expected process publication failure");
            } catch (OutOfMemoryError expected) {
                assertEquals("simulated process publication failure", expected.getMessage());
            }

            assertFalse(process.isAlive());
            assertEquals(2, process.destroyCount.get());
            assertEquals(0, trackedProcessCount(runtime));
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldEventuallyReleaseSlotWhenInitializationRollbackCannotStopProcess() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-rollback");
        File pluginFile = pluginFile(pluginRoot, "initialization-failure.jar");
        ControlledProcess process = new ControlledProcess(false);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false);

            try {
                runtime.loadPlugin(pluginFile, "plugin-id");
                org.junit.Assert.fail("Expected process initialization failure");
            } catch (IllegalStateException expected) {
                assertEquals("simulated pid failure", expected.getMessage());
            }

            assertTrue(process.isAlive());
            assertEquals(1, trackedProcessCount(runtime));
            process.markExited();
            assertTrue(awaitTrackedProcessCount(runtime, 0));
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldBackOffAfterProcessExitsSoonAfterReady() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-early-exit");
        File pluginFile = pluginFile(pluginRoot, "early-exit.jar");
        ManagedControlledProcess process = new ManagedControlledProcess(false);
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false, false, coordinator);

            org.junit.Assert.assertSame(process, runtime.loadPlugin(pluginFile, "plugin-id"));
            String runtimeInstanceId = runtime.runtimeInstanceIdByPluginId("plugin-id").orElseThrow();
            runtime.markReadyIfCurrent("plugin-id", "stale-instance", process.pid());
            assertEquals(0L, trackedProcessSlot(runtime, "plugin-id").readyAt);
            runtime.markReadyIfCurrent("plugin-id", runtimeInstanceId, process.pid());
            assertTrue(trackedProcessSlot(runtime, "plugin-id").readyAt > 0L);
            process.markExited();
            assertTrue(awaitTrackedProcessCount(runtime, 0));

            AtomicInteger restartCount = new AtomicInteger();
            assertFalse(coordinator.start("plugin-id", 1, 10, 10, 1000L,
                    () -> restartCount.incrementAndGet() > 0));
            assertEquals(0, restartCount.get());
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldAllowImmediateRestartAfterStableProcessExits() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-stable-exit");
        File pluginFile = pluginFile(pluginRoot, "stable-exit.jar");
        ManagedControlledProcess process = new ManagedControlledProcess(false);
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false, false, coordinator);

            org.junit.Assert.assertSame(process, runtime.loadPlugin(pluginFile, "plugin-id"));
            runtime.markReadyIfCurrent("plugin-id",
                    runtime.runtimeInstanceIdByPluginId("plugin-id").orElseThrow(), process.pid());
            trackedProcessSlot(runtime, "plugin-id").readyAt = System.currentTimeMillis() - 31000L;
            process.markExited();
            assertTrue(awaitTrackedProcessCount(runtime, 0));

            AtomicInteger restartCount = new AtomicInteger();
            assertTrue(coordinator.start("plugin-id", 1, 10, 10, 1000L,
                    () -> restartCount.incrementAndGet() > 0));
            assertEquals(1, restartCount.get());
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldNotBackOffAfterIntentionalProcessStop() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-intentional-stop");
        File pluginFile = pluginFile(pluginRoot, "intentional-stop.jar");
        ManagedControlledProcess process = new ManagedControlledProcess(true);
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, process, false, false, coordinator);

            org.junit.Assert.assertSame(process, runtime.loadPlugin(pluginFile, "plugin-id"));
            runtime.markReadyIfCurrent("plugin-id",
                    runtime.runtimeInstanceIdByPluginId("plugin-id").orElseThrow(), process.pid());
            assertTrue(runtime.destroyByPluginId("plugin-id", "intentional-stop"));
            assertTrue(awaitTrackedProcessCount(runtime, 0));

            AtomicInteger restartCount = new AtomicInteger();
            assertTrue(coordinator.start("plugin-id", 1, 10, 10, 1000L,
                    () -> restartCount.incrementAndGet() > 0));
            assertEquals(1, restartCount.get());
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldPublishExitBeforeWatcherCanAcquireOperationLock() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-exit-signal");
        File pluginFile = pluginFile(pluginRoot, "exit-signal.jar");
        ManagedControlledProcess process = new ManagedControlledProcess(false);
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        CountDownLatch operationLocked = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(
                    pluginRoot, process, false, false, coordinator);
            org.junit.Assert.assertSame(process, runtime.loadPlugin(pluginFile, "plugin-id"));

            Thread operation = new Thread(() -> coordinator.withPluginOperation("plugin-id", 1000L, () -> {
                operationLocked.countDown();
                try {
                    releaseOperation.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;
            }));
            operation.start();
            assertTrue(operationLocked.await(1, TimeUnit.SECONDS));

            process.markExited();
            PluginProcessRuntime.ProcessSlot slot = trackedProcessSlot(runtime, "plugin-id");
            assertTrue(slot.exitCode.get(1, TimeUnit.SECONDS) == 1);
            assertEquals(1, trackedProcessCount(runtime));
            assertFalse(runtime.isManagedProcessStartViable("plugin-id", "exit-signal"));

            releaseOperation.countDown();
            operation.join(2000L);
            assertTrue(awaitTrackedProcessCount(runtime, 0));
        } finally {
            releaseOperation.countDown();
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldNotSpawnReplacementWhenOldShortNameProcessCannotStop() throws Exception {
        Path pluginRoot = Files.createTempDirectory("plugin-process-duplicate-short-name");
        File pluginFile = pluginFile(pluginRoot, "duplicate.jar");
        ManagedControlledProcess replacement = new ManagedControlledProcess(true);
        ControlledProcess oldProcess = new ControlledProcess(false);
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            TestableProcessRuntime runtime = new TestableProcessRuntime(pluginRoot, replacement, false);
            PluginProcessRuntime.ProcessSlot oldSlot = new PluginProcessRuntime.ProcessSlot("duplicate");
            oldSlot.finishSpawn(oldProcess);
            oldSlot.initializationFinished.countDown();
            runtime.registerProcessSlot("old-plugin-id", oldSlot);

            try {
                runtime.loadPlugin(pluginFile, "new-plugin-id");
                org.junit.Assert.fail("Expected replacement start to be deferred");
            } catch (com.zrlog.plugincore.server.runtime.state.PluginStartDeferredException expected) {
                assertTrue(expected.getMessage().contains("duplicate"));
            }

            assertEquals(0, runtime.startCount.get());
            assertEquals(1, trackedProcessCount(runtime));
            assertTrue(oldProcess.isAlive());
            oldProcess.markExited();
            runtime.shutdownProcesses();
        } finally {
            delete(pluginRoot);
        }
    }

    @Test
    public void shouldSplitLongProcessOutputAndContinueDrainingFollowingLines() throws Exception {
        PluginProcessRuntime processRuntime = new PluginProcessRuntime();
        FakeProcess process = new FakeProcess();
        String longLine = "x".repeat(PluginProcessRuntime.PROCESS_LOG_SEGMENT_CHARS * 2 + 17);
        String input = longLine + "\nnext\r\nlast";
        List<String> output = new ArrayList<>();

        processRuntime.drainProcessOutput(process,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                "reminder", "PINFO", "plugin-id", new AtomicBoolean(false), output::add);

        String prefix = "[PINFO]: reminder - ";
        assertEquals(5, output.size());
        StringBuilder reconstructedLongLine = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String segment = output.get(i).substring(prefix.length());
            assertTrue(segment.length() <= PluginProcessRuntime.PROCESS_LOG_SEGMENT_CHARS);
            reconstructedLongLine.append(segment);
        }
        assertEquals(longLine, reconstructedLongLine.toString());
        assertEquals(prefix + "next", output.get(3));
        assertEquals(prefix + "last", output.get(4));
        assertFalse(process.destroyed);
    }

    @Test
    public void shouldStillDestroyProcessForCorruptJarError() throws Exception {
        PluginProcessRuntime processRuntime = new PluginProcessRuntime();
        FakeProcess process = new FakeProcess();
        List<String> output = new ArrayList<>();

        processRuntime.drainProcessOutput(process,
                new ByteArrayInputStream("Error: Invalid or corrupt jarfile plugin.jar\nignored"
                        .getBytes(StandardCharsets.UTF_8)),
                "reminder", "PERROR", "plugin-id", new AtomicBoolean(false), output::add);

        assertTrue(process.destroyed);
        assertTrue(output.isEmpty());
    }

    @Test
    public void shouldStopProcessWhenOutputCanNoLongerBeDrained() throws Exception {
        PluginProcessRuntime processRuntime = new PluginProcessRuntime();
        ControlledProcess process = new ControlledProcess(false, true);

        try {
            processRuntime.drainProcessOutput(process,
                    new ByteArrayInputStream("output\n".getBytes(StandardCharsets.UTF_8)),
                    "reminder", "PINFO", "plugin-id", new AtomicBoolean(false), value -> {
                        throw new OutOfMemoryError("simulated output failure");
                    });
            org.junit.Assert.fail("Expected output failure");
        } catch (OutOfMemoryError expected) {
            assertEquals("simulated output failure", expected.getMessage());
        }

        assertFalse(process.isAlive());
        assertEquals(2, process.destroyCount.get());
    }

    private static File pluginFile(Path pluginRoot, String fileName) throws Exception {
        Path pluginFile = pluginRoot.resolve(fileName);
        Files.write(pluginFile, new byte[]{1});
        return pluginFile.toFile();
    }

    private static boolean awaitTrackedProcessCount(PluginProcessRuntime runtime, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (trackedProcessCount(runtime) == expected) {
                Thread.sleep(20L);
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static int trackedProcessCount(PluginProcessRuntime runtime) throws Exception {
        Field field = PluginProcessRuntime.class.getDeclaredField("processMap");
        field.setAccessible(true);
        return ((Map<String, ?>) field.get(runtime)).size();
    }

    @SuppressWarnings("unchecked")
    private static PluginProcessRuntime.ProcessSlot trackedProcessSlot(PluginProcessRuntime runtime,
                                                                       String pluginId) throws Exception {
        Field field = PluginProcessRuntime.class.getDeclaredField("processMap");
        field.setAccessible(true);
        return ((Map<String, PluginProcessRuntime.ProcessSlot>) field.get(runtime)).get(pluginId);
    }

    private static void delete(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private static class TestableProcessRuntime extends PluginProcessRuntime {

        private final Process process;
        private final boolean failReservation;
        private final boolean failPublication;
        private final AtomicInteger startCount = new AtomicInteger();

        TestableProcessRuntime(Path pluginRoot, Process process, boolean failReservation) {
            this(pluginRoot, process, failReservation, false);
        }

        TestableProcessRuntime(Path pluginRoot, Process process, boolean failReservation, boolean failPublication) {
            this(pluginRoot, process, failReservation, failPublication, new PluginStartCoordinator());
        }

        TestableProcessRuntime(Path pluginRoot,
                               Process process,
                               boolean failReservation,
                               boolean failPublication,
                               PluginStartCoordinator startCoordinator) {
            super(new PluginSessionRegistry(), new PluginConfig(null, null, 0,
                    pluginRoot.toString(), new BlogRunTime()), startCoordinator);
            this.process = process;
            this.failReservation = failReservation;
            this.failPublication = failPublication;
        }

        @Override
        Process startPluginProcess(LaunchCommand launchCommand) {
            startCount.incrementAndGet();
            return process;
        }

        @Override
        void registerProcessSlot(String pluginId, ProcessSlot slot) {
            if (failReservation) {
                throw new OutOfMemoryError("simulated process registration failure");
            }
            super.registerProcessSlot(pluginId, slot);
        }

        @Override
        void publishSpawnedProcess(ProcessSlot slot, Process process) {
            if (failPublication) {
                throw new OutOfMemoryError("simulated process publication failure");
            }
            super.publishSpawnedProcess(slot, process);
        }
    }

    private static class ManagedControlledProcess extends ControlledProcess {

        ManagedControlledProcess(boolean exitOnDestroy) {
            super(exitOnDestroy);
        }

        @Override
        public long pid() {
            return 4242L;
        }
    }

    private static class ControlledProcess extends Process {

        private final boolean exitOnDestroy;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger destroyCount = new AtomicInteger();
        private final CountDownLatch exited = new CountDownLatch(1);

        private final boolean exitOnForce;

        ControlledProcess(boolean exitOnDestroy) {
            this(exitOnDestroy, exitOnDestroy);
        }

        ControlledProcess(boolean exitOnDestroy, boolean exitOnForce) {
            this.exitOnDestroy = exitOnDestroy;
            this.exitOnForce = exitOnForce;
        }

        @Override
        public long pid() {
            throw new IllegalStateException("simulated pid failure");
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
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
        public int waitFor() throws InterruptedException {
            exited.await();
            return 1;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return 1;
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
            if (exitOnDestroy) {
                markExited();
            }
        }

        @Override
        public Process destroyForcibly() {
            destroyCount.incrementAndGet();
            if (exitOnForce) {
                markExited();
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        void markExited() {
            alive.set(false);
            exited.countDown();
        }

        int destroyCallCount() {
            return destroyCount.get();
        }
    }

    private static class SlowWaitProcess extends ControlledProcess {

        private final AtomicInteger timedWaitCount = new AtomicInteger();

        SlowWaitProcess() {
            super(false);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitCount.incrementAndGet();
            try {
                unit.sleep(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return !isAlive();
        }
    }

    private static class FakeProcess extends Process {

        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
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
            destroyed = true;
        }
    }

    private static class UninspectableProcess extends FakeProcess {

        private final AtomicBoolean inspectionFailed = new AtomicBoolean();

        @Override
        public boolean isAlive() {
            if (inspectionFailed.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated process inspection failure");
            }
            return false;
        }
    }
}
