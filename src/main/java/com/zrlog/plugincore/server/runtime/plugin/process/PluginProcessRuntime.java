package com.zrlog.plugincore.server.runtime.plugin.process;

import com.hibegin.common.util.EnvKit;
import com.zrlog.plugin.common.ConfigKit;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessionRegistry;
import com.zrlog.plugincore.server.runtime.state.DefaultPluginRuntimeStarter;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import com.zrlog.plugincore.server.runtime.state.PluginStartCoordinator;
import com.zrlog.plugincore.server.runtime.state.PluginStartDeferredException;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.util.CmdUtil;
import com.zrlog.plugincore.server.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginProcessRuntime {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginProcessRuntime.class);
    private static final long PROCESS_START_GRACE_MS = 30000L;
    private static final long PROCESS_STABLE_AFTER_READY_MS = 30000L;
    private static final long PROCESS_STOP_GRACE_MS = 2000L;
    private static final long PROCESS_OPERATION_WAIT_MS = 10000L;
    private static final long PROCESS_SHUTDOWN_TIMEOUT_MS = 1500L;
    private static final long PROCESS_SHUTDOWN_GRACE_MS = 1000L;
    private static final long PROCESS_SHUTDOWN_POLL_MS = 10L;
    static final int PROCESS_LOG_SEGMENT_CHARS = 8 * 1024;
    private static final int PROCESS_LOG_READ_BUFFER_CHARS = 2 * 1024;

    private final PluginSessionRegistry sessionRegistry;
    private final Map<String, ProcessSlot> processMap = new ConcurrentHashMap<>();
    private final Map<String, Long> processIdMap = new ConcurrentHashMap<>();
    private final Map<String, String> processRuntimeInstanceIdMap = new ConcurrentHashMap<>();
    private final Object processLifecycleLock = new Object();
    private final Object processCapacityLock = new Object();
    private final PluginConfig pluginConfig;
    private final PluginStartCoordinator startCoordinator;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private final CountDownLatch shutdownFinished = new CountDownLatch(1);
    private volatile boolean shuttingDown;

    public PluginProcessRuntime() {
        this(new PluginSessionRegistry(), PluginRuntimeBridge.pluginConfig());
    }

    public PluginProcessRuntime(PluginSessionRegistry sessionRegistry) {
        this(sessionRegistry, PluginRuntimeBridge.pluginConfig());
    }

    public PluginProcessRuntime(PluginSessionRegistry sessionRegistry, PluginConfig pluginConfig) {
        this(sessionRegistry, pluginConfig, new PluginStartCoordinator());
    }

    public PluginProcessRuntime(PluginSessionRegistry sessionRegistry,
                                PluginConfig pluginConfig,
                                PluginStartCoordinator startCoordinator) {
        this.sessionRegistry = sessionRegistry;
        this.pluginConfig = pluginConfig;
        this.startCoordinator = startCoordinator;
    }

    public void shutdown() {
        beginShutdown();
        if (shutdownStarted.compareAndSet(false, true)) {
            try {
                shutdownProcesses();
            } finally {
                shutdownFinished.countDown();
            }
            return;
        }
        try {
            shutdownFinished.await(PROCESS_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void beginShutdown() {
        shuttingDown = true;
    }

    void shutdownProcesses() {
        shutdownProcesses(PROCESS_SHUTDOWN_TIMEOUT_MS, PROCESS_SHUTDOWN_GRACE_MS);
    }

    void shutdownProcesses(long timeoutMs, long gracefulTimeoutMs) {
        beginShutdown();
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        long gracefulNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, gracefulTimeoutMs));
        long deadlineNanos = startedAtNanos + timeoutNanos;
        long gracefulDeadlineNanos = startedAtNanos + Math.min(timeoutNanos, gracefulNanos);
        Set<ProcessSlot> gracefulRequested = new HashSet<>();
        Set<ProcessSlot> forciblyRequested = new HashSet<>();

        awaitShutdownPhase(gracefulRequested, false, gracefulDeadlineNanos);
        awaitShutdownPhase(forciblyRequested, true, deadlineNanos);

        // Catch a slot published by an in-flight start at the edge of the deadline.
        requestShutdownForKnownProcesses(forciblyRequested, true);
        removeExitedProcessSlots();
        logRemainingShutdownProcesses();
    }

    private void awaitShutdownPhase(Set<ProcessSlot> requestedSlots, boolean forcibly, long deadlineNanos) {
        while (true) {
            requestShutdownForKnownProcesses(requestedSlots, forcibly);
            removeExitedProcessSlots();
            if (processMap.isEmpty() || !pauseUntil(deadlineNanos)) {
                return;
            }
        }
    }

    private void requestShutdownForKnownProcesses(Set<ProcessSlot> requestedSlots, boolean forcibly) {
        for (Map.Entry<String, ProcessSlot> entry : processMap.entrySet()) {
            ProcessSlot slot = entry.getValue();
            slot.shutdownRequested = true;
            Process process = slot.process;
            if (process == null || !requestedSlots.add(slot)) {
                continue;
            }
            requestProcessShutdown(process, forcibly);
        }
    }

    private void requestProcessShutdown(Process process, boolean forcibly) {
        try {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        } catch (RuntimeException | Error e) {
            logBestEffort(Level.WARNING, forcibly
                    ? "unable to force plugin shutdown"
                    : "unable to request graceful plugin shutdown", e);
        }
    }

    private void removeExitedProcessSlots() {
        for (Map.Entry<String, ProcessSlot> entry : processMap.entrySet()) {
            ProcessSlot slot = entry.getValue();
            Process process = slot.process;
            if (process == null || processAliveOrUnknown(process) || !processMap.remove(entry.getKey(), slot)) {
                continue;
            }
            String pluginShortName = slot.pluginShortName;
            try (PluginLogContext.Scope ignored = PluginLogContext.open(entry.getKey(), pluginShortName,
                    pluginShortName)) {
                LOGGER.info(PluginLogContext.prefix("close plugin " + pluginShortName));
            } catch (RuntimeException | Error e) {
                logBestEffort(Level.WARNING, "unable to log stopped plugin during shutdown", e);
            }
        }
    }

    private boolean processAliveOrUnknown(Process process) {
        try {
            return process.isAlive();
        } catch (RuntimeException | Error e) {
            return true;
        }
    }

    private boolean pauseUntil(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return false;
        }
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(PROCESS_SHUTDOWN_POLL_MS);
        try {
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, pollNanos));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void logRemainingShutdownProcesses() {
        for (Map.Entry<String, ProcessSlot> entry : processMap.entrySet()) {
            ProcessSlot slot = entry.getValue();
            String pluginShortName = slot.pluginShortName;
            try (PluginLogContext.Scope ignored = PluginLogContext.open(entry.getKey(), pluginShortName,
                    pluginShortName)) {
                LOGGER.warning(PluginLogContext.prefix("plugin " + pluginShortName
                        + " is still alive after the shutdown deadline"));
            } catch (RuntimeException | Error e) {
                logBestEffort(Level.WARNING, "unable to report plugin that survived shutdown", e);
            }
        }
    }

    public boolean destroy(String pluginShortName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(null, pluginShortName, pluginShortName)) {
            boolean found = false;
            boolean destroyed = true;
            for (Map.Entry<String, ProcessSlot> entry : new ArrayList<Map.Entry<String, ProcessSlot>>(processMap.entrySet())) {
                if (!Objects.equals(pluginShortName, entry.getValue().pluginShortName)) {
                    continue;
                }
                found = true;
                destroyed = destroyByPluginId(entry.getKey(), pluginShortName) && destroyed;
            }
            if (!found) {
                PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginShortName);
                if (pluginVO != null && pluginVO.getPlugin() != null) {
                    found = true;
                    destroyed = destroyByPluginId(pluginVO.getPlugin().getId(), pluginShortName);
                }
            }
            if (destroyed) {
                sessionRegistry.closeLocalSessionsByPluginShortName(pluginShortName);
            }
            return destroyed;
        }
    }

    public boolean destroyByPluginId(String pluginId, String pluginShortName) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginShortName, pluginShortName)) {
            startCoordinator.cancelStart(pluginId);
            return startCoordinator.withPluginOperation(pluginId, PROCESS_OPERATION_WAIT_MS,
                    () -> destroyByPluginIdWithinOperation(pluginId, pluginShortName, null));
        }
    }

    public boolean destroyByPluginIdIfCurrent(String pluginId, String pluginShortName, Process expectedProcess) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginShortName, pluginShortName)) {
            return startCoordinator.withPluginOperation(pluginId, PROCESS_OPERATION_WAIT_MS,
                    () -> destroyByPluginIdWithinOperation(pluginId, pluginShortName, expectedProcess));
        }
    }

    private boolean destroyByPluginIdWithinOperation(String pluginId, String pluginShortName, Process expectedProcess) {
        synchronized (processLifecycleLock) {
            return destroyCurrentProcess(pluginId, pluginShortName, expectedProcess);
        }
    }

    public boolean hasManagedProcessSlot(String pluginId, String pluginShortName) {
        if (StringUtils.isEmpty(pluginId) || StringUtils.isEmpty(pluginShortName)) {
            return false;
        }
        ProcessSlot slot = processMap.get(pluginId);
        return slot != null && Objects.equals(pluginShortName, slot.pluginShortName);
    }

    public boolean isManagedProcessStartViable(String pluginId, String pluginShortName) {
        ProcessSlot slot = processMap.get(pluginId);
        if (slot == null || !Objects.equals(pluginShortName, slot.pluginShortName) || slot.exitCode.isDone()) {
            return false;
        }
        Process process = slot.process;
        if (process == null) {
            return false;
        }
        try {
            return process.isAlive();
        } catch (RuntimeException | Error ignored) {
            return true;
        }
    }

    public Process loadPlugin(final File pluginFile, String pluginId) {
        if (pluginFile == null || !pluginFile.exists()) {
            return null;
        }
        String pluginShortName = PluginFiles.getPluginShortName(pluginFile);
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginShortName, pluginShortName)) {
            synchronized (processLifecycleLock) {
                if (shuttingDown) {
                    return null;
                }
                destroyOtherProcesses(pluginShortName, pluginId);
                if (!prepareProcessSlot(pluginId, pluginShortName)) {
                    return null;
                }
                PluginRuntimeStates.removeLocalRuntimeInstances(pluginId);
                LOGGER.info(PluginLogContext.prefix("run plugin " + pluginShortName));
                String userDir = pluginConfig.getPluginHomeFolder(pluginShortName);
                String tmpDir = pluginConfig.getPluginTempFolder(pluginShortName);
                new File(userDir).mkdirs();
                new File(tmpDir).mkdirs();
                LaunchCommand launchCommand = buildLaunchCommand(
                        pluginFile,
                        pluginConfig.getMasterPort(),
                        pluginId,
                        userDir,
                        tmpDir,
                        ConfigKit.get("pluginJvmArgs", "") + "",
                        System.getProperty("java.home")
                );
                if (!pluginFile.getName().endsWith(".jar")) {
                    if (File.separatorChar == '/') {
                        CmdUtil.sendCmd("chmod", "a+x", pluginFile.toString());
                    }
                }
                ProcessSlot slot = startProcessWithinCapacity(pluginId, pluginShortName, launchCommand);
                if (slot != null) {
                    Process pr = slot.process;
                    try {
                        long processId = pr.pid();
                        String runtimeInstanceId = PluginRuntimeStates.newRuntimeInstanceId(processId);
                        processIdMap.put(pluginId, processId);
                        processRuntimeInstanceIdMap.put(pluginId, runtimeInstanceId);
                        runtimeStateService(runtimeInstanceId).markStarting(pluginId,
                                pluginNameOrShortName(pluginId, pluginShortName), runtimeMode(pluginFile), processId);
                        printInputStreamWithThread(pr, pr.getInputStream(), pluginShortName, "PINFO", pluginId,
                                slot.cleaned);
                        printInputStreamWithThread(pr, pr.getErrorStream(), pluginShortName, "PERROR", pluginId,
                                slot.cleaned);
                        return pr;
                    } catch (RuntimeException | Error e) {
                        rollbackSpawnedProcess(pluginId, slot, e);
                        throw e;
                    } finally {
                        slot.initializationFinished.countDown();
                    }
                }
                if (!shuttingDown) {
                    runtimeStateService().markFailed(pluginId, pluginNameOrShortName(pluginId, pluginShortName),
                            "Plugin process start failed");
                }
                return null;
            }
        }
    }

    private ProcessSlot startProcessWithinCapacity(String pluginId, String pluginShortName,
                                                   LaunchCommand launchCommand) {
        if (StringUtils.isEmpty(pluginId)) {
            throw new IllegalArgumentException("pluginId is required");
        }
        synchronized (processCapacityLock) {
            reapExitedProcesses();
            PluginRuntimeSetting runtimeSetting = runtimeSetting();
            int maxRunningPlugins = runtimeSetting.getMaxRunningPlugins().intValue();
            if (!hasProcessCapacity(processMap.size(), runtimeSetting)) {
                throw new PluginStartDeferredException("Plugin runtime capacity reached (max " + maxRunningPlugins + ")");
            }
            ProcessSlot slot = new ProcessSlot(pluginShortName);
            Thread watcher = newProcessWatcher(slot, pluginId);
            try {
                registerProcessSlot(pluginId, slot);
                watcher.start();
            } catch (RuntimeException | Error reservationFailure) {
                processMap.remove(pluginId, slot);
                slot.finishSpawn(null);
                slot.initializationFinished.countDown();
                throw reservationFailure;
            }
            Process process = null;
            try {
                process = startPluginProcess(launchCommand);
                publishSpawnedProcess(slot, process);
                if (stopPublishedProcessAfterShutdown(slot, process)) {
                    slot.initializationFinished.countDown();
                    return null;
                }
            } catch (RuntimeException | Error spawnFailure) {
                preserveOrReleaseFailedSpawn(pluginId, slot, process);
                slot.initializationFinished.countDown();
                throw spawnFailure;
            }
            if (process == null) {
                processMap.remove(pluginId, slot);
                slot.initializationFinished.countDown();
                return null;
            }
            return slot;
        }
    }

    Process startPluginProcess(LaunchCommand launchCommand) {
        return CmdUtil.getProcess(
                launchCommand.workingDirectory,
                launchCommand.environment,
                launchCommand.program,
                launchCommand.args.toArray(new Object[0])
        );
    }

    void publishSpawnedProcess(ProcessSlot slot, Process process) {
        slot.finishSpawn(process);
    }

    private boolean stopPublishedProcessAfterShutdown(ProcessSlot slot, Process process) {
        if (process == null || (!shuttingDown && !slot.shutdownRequested)) {
            return false;
        }
        slot.shutdownRequested = true;
        requestProcessShutdown(process, false);
        requestProcessShutdown(process, true);
        return true;
    }

    void registerProcessSlot(String pluginId, ProcessSlot slot) {
        ProcessSlot existing = processMap.putIfAbsent(pluginId, slot);
        if (existing != null) {
            throw new IllegalStateException("Plugin process slot already exists for " + pluginId);
        }
    }

    private void rollbackSpawnedProcess(String pluginId, ProcessSlot slot, Throwable failure) {
        String pluginShortName = slot.pluginShortName;
        try {
            if (processMap.get(pluginId) == slot && terminateAndRemove(pluginId, slot)) {
                if (slot.cleaned.compareAndSet(false, true)) {
                    cleanupProcessState(pluginId, pluginShortName);
                }
            }
        } catch (RuntimeException | Error cleanupFailure) {
            logBestEffort(Level.WARNING, "unable to clean up failed plugin process start", cleanupFailure);
        }
        try {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            runtimeStateService().markFailed(pluginId, pluginNameOrShortName(pluginId, pluginShortName), message);
        } catch (RuntimeException | Error stateFailure) {
            logBestEffort(Level.WARNING, "unable to mark failed plugin process start", stateFailure);
        }
    }

    private void preserveOrReleaseFailedSpawn(String pluginId, ProcessSlot slot, Process process) {
        if (process == null) {
            processMap.remove(pluginId, slot);
            slot.finishSpawn(null);
            return;
        }
        slot.finishSpawn(process);
        boolean stopped;
        try {
            stopped = terminateProcess(process);
        } catch (RuntimeException | Error cleanupFailure) {
            stopped = false;
            logBestEffort(Level.WARNING, "unable to stop plugin after process publication failed", cleanupFailure);
        }
        if (stopped) {
            processMap.remove(pluginId, slot);
        }
    }

    private void reapExitedProcesses() {
        for (Map.Entry<String, ProcessSlot> entry : new ArrayList<Map.Entry<String, ProcessSlot>>(processMap.entrySet())) {
            ProcessSlot slot = entry.getValue();
            Process process = slot.process;
            if (process == null || process.isAlive() || !processMap.remove(entry.getKey(), slot)) {
                continue;
            }
            cleanupUnexpectedExit(entry.getKey(), slot.pluginShortName, slot, processExitCode(process));
        }
    }

    static boolean hasProcessCapacity(int runningProcesses, PluginRuntimeSetting runtimeSetting) {
        return !runtimeSetting.getOnDemandEnabled()
                || runningProcesses < Math.max(1, runtimeSetting.getMaxRunningPlugins().intValue());
    }

    private PluginRuntimeSetting runtimeSetting() {
        return PluginCoreDAO.getInstance().loadSnapshot().getSetting().getRuntime();
    }

    private void printInputStreamWithThread(final Process pr, final InputStream in, final String pluginShortName,
                                            final String printLevel, final String uuid,
                                            AtomicBoolean cleaned) {
        new Thread(() -> {
            try (PluginLogContext.Scope ignored = PluginLogContext.open(uuid, pluginShortName, pluginShortName)) {
                try {
                    drainProcessOutput(pr, in, pluginShortName, printLevel, uuid, cleaned);
                } catch (IOException | RuntimeException | Error e) {
                    if (EnvKit.isDevMode()) {
                        logBestEffort(Level.SEVERE, "plugin output error", e);
                    }
                }
            }
        }, "zrlog-plugin-output-" + printLevel + "-" + pluginShortName).start();
    }

    void drainProcessOutput(Process pr, InputStream in, String pluginShortName, String printLevel, String uuid,
                            AtomicBoolean cleaned) throws IOException {
        drainProcessOutput(pr, in, pluginShortName, printLevel, uuid, cleaned, System.out::println);
    }

    void drainProcessOutput(Process pr, InputStream in, String pluginShortName, String printLevel, String uuid,
                            AtomicBoolean cleaned, Consumer<String> output) throws IOException {
        try (Reader reader = new InputStreamReader(in)) {
            readBoundedLines(reader, segment -> {
                if ("PERROR".equals(printLevel) && segment.startsWith("Error: Invalid or corrupt jarfile")) {
                    pr.destroy();
                    return false;
                }
                output.accept("[" + printLevel + "]" + ": " + pluginShortName + " - " + segment);
                return true;
            });
        } catch (IOException e) {
            terminateAfterOutputFailure(pr, e);
            throw e;
        } catch (RuntimeException | Error e) {
            terminateAfterOutputFailure(pr, e);
            throw e;
        }
    }

    private void terminateAfterOutputFailure(Process process, Throwable failure) {
        try {
            if (!terminateProcess(process)) {
                logBestEffort(Level.WARNING, "plugin output cannot be drained and process is still alive", failure);
            }
        } catch (RuntimeException | Error cleanupFailure) {
            logBestEffort(Level.WARNING, "unable to stop plugin after output drain failure", cleanupFailure);
        }
    }

    private static void readBoundedLines(Reader reader, ProcessOutputSegmentHandler handler) throws IOException {
        char[] readBuffer = new char[PROCESS_LOG_READ_BUFFER_CHARS];
        StringBuilder segment = new StringBuilder(PROCESS_LOG_SEGMENT_CHARS);
        boolean segmentEmitted = false;
        boolean pendingCarriageReturn = false;
        int read;
        while ((read = reader.read(readBuffer)) != -1) {
            for (int i = 0; i < read; i++) {
                char value = readBuffer[i];
                if (pendingCarriageReturn) {
                    if (!finishProcessLogLine(segment, segmentEmitted, handler)) {
                        return;
                    }
                    segmentEmitted = false;
                    pendingCarriageReturn = false;
                    if (value == '\n') {
                        continue;
                    }
                }
                if (value == '\r') {
                    pendingCarriageReturn = true;
                    continue;
                }
                if (value == '\n') {
                    if (!finishProcessLogLine(segment, segmentEmitted, handler)) {
                        return;
                    }
                    segmentEmitted = false;
                    continue;
                }
                segment.append(value);
                if (segment.length() == PROCESS_LOG_SEGMENT_CHARS) {
                    if (!handler.handle(segment.toString())) {
                        return;
                    }
                    segment.setLength(0);
                    segmentEmitted = true;
                }
            }
        }
        if (pendingCarriageReturn) {
            finishProcessLogLine(segment, segmentEmitted, handler);
        } else if (segment.length() > 0) {
            handler.handle(segment.toString());
        }
    }

    private static boolean finishProcessLogLine(StringBuilder segment, boolean segmentEmitted,
                                                ProcessOutputSegmentHandler handler) {
        if (segment.length() == 0 && segmentEmitted) {
            return true;
        }
        String value = segment.toString();
        segment.setLength(0);
        return handler.handle(value);
    }

    @FunctionalInterface
    private interface ProcessOutputSegmentHandler {

        boolean handle(String segment);
    }

    private Thread newProcessWatcher(ProcessSlot slot, String pluginId) {
        return new Thread(() -> {
            boolean interrupted = false;
            String pluginShortName = slot.pluginShortName;
            try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginShortName, pluginShortName)) {
                while (true) {
                    try {
                        slot.spawnFinished.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                Process process = slot.process;
                if (process == null) {
                    return;
                }
                while (true) {
                    try {
                        slot.initializationFinished.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                int exitCode;
                while (true) {
                    try {
                        exitCode = process.waitFor();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (EnvKit.isDevMode()) {
                    LOGGER.info(PluginLogContext.prefix("plugin " + pluginShortName + " exited with code " + exitCode));
                }
                final int completedExitCode = exitCode;
                slot.exitCode.complete(completedExitCode);
                startCoordinator.runPluginOperation(pluginId,
                        () -> cleanupExitedProcess(slot, pluginId, completedExitCode));
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "zrlog-plugin-watch-" + slot.pluginShortName);
    }

    private void cleanupExitedProcess(ProcessSlot slot, String pluginId, int exitCode) {
        String pluginShortName = slot.pluginShortName;
        try (PluginLogContext.Scope ignored = PluginLogContext.open(pluginId, pluginShortName, pluginShortName)) {
            synchronized (processLifecycleLock) {
                if (!processMap.remove(pluginId, slot)) {
                    return;
                }
                if (slot.shutdownRequested) {
                    slot.cleaned.set(true);
                    return;
                }
                cleanupUnexpectedExit(pluginId, pluginShortName, slot, exitCode);
            }
        }
    }

    private void destroyOtherProcesses(String pluginShortName, String currentPluginId) {
        for (Map.Entry<String, ProcessSlot> entry : new ArrayList<Map.Entry<String, ProcessSlot>>(processMap.entrySet())) {
            if (Objects.equals(currentPluginId, entry.getKey())
                    || !Objects.equals(pluginShortName, entry.getValue().pluginShortName)) {
                continue;
            }
            if (!destroyCurrentProcess(entry.getKey(), pluginShortName, null)) {
                throw new PluginStartDeferredException("Existing plugin process is still alive for " + pluginShortName);
            }
        }
    }

    private boolean prepareProcessSlot(String pluginId, String pluginShortName) {
        ProcessSlot existingSlot = processMap.get(pluginId);
        if (existingSlot == null) {
            return true;
        }
        Process existingProcess = existingSlot.process;
        if (existingProcess == null) {
            return false;
        }
        boolean wasAlive = existingProcess.isAlive();
        if (sessionRegistry.getLocalSessionByPluginId(pluginId) != null) {
            return false;
        }
        if (wasAlive && withinStartGrace(existingSlot)) {
            return false;
        }
        if (!terminateAndRemove(pluginId, existingSlot)) {
            LOGGER.warning(PluginLogContext.prefix("keep plugin " + pluginShortName
                    + " reserved because the old process is still alive"));
            return false;
        }
        if (!wasAlive && shouldBackOffAfterUnexpectedExit(existingSlot, System.currentTimeMillis())) {
            cleanupUnexpectedExit(pluginId, pluginShortName, existingSlot, processExitCode(existingProcess));
            throw new PluginStartDeferredException("Plugin exited before the next start attempt");
        }
        if (!wasAlive) {
            cleanupUnexpectedExit(pluginId, pluginShortName, existingSlot, processExitCode(existingProcess));
        } else {
            cleanupProcessState(pluginId, pluginShortName);
        }
        LOGGER.warning(PluginLogContext.prefix("restart plugin " + pluginShortName + " because process has no local session"));
        return true;
    }

    private void cleanupUnexpectedExit(String pluginId,
                                       String pluginShortName,
                                       ProcessSlot slot,
                                       Integer exitCode) {
        long now = System.currentTimeMillis();
        if (shouldBackOffAfterUnexpectedExit(slot, now)) {
            recordUnexpectedExitBackoff(pluginId);
        }
        if (!slot.cleaned.compareAndSet(false, true)) {
            return;
        }
        try {
            cleanupProcessState(pluginId, pluginShortName);
        } catch (RuntimeException | Error cleanupFailure) {
            logBestEffort(Level.WARNING, "unable to clean up exited plugin process", cleanupFailure);
        }
        try {
            String message = exitCode == null
                    ? "Plugin process exited unexpectedly"
                    : "Plugin process exited unexpectedly with code " + exitCode;
            runtimeStateService().markFailed(pluginId, pluginNameOrShortName(pluginId, pluginShortName), message);
        } catch (RuntimeException | Error stateFailure) {
            logBestEffort(Level.WARNING, "unable to record unexpected plugin process exit", stateFailure);
        }
    }

    private boolean shouldBackOffAfterUnexpectedExit(ProcessSlot slot, long nowMs) {
        return slot.readyAt <= 0L || nowMs - slot.readyAt < PROCESS_STABLE_AFTER_READY_MS;
    }

    private void recordUnexpectedExitBackoff(String pluginId) {
        long backoffMs = PluginRuntimeSetting.DEFAULT_START_FAILURE_BACKOFF_SECONDS * 1000L;
        try {
            PluginRuntimeSetting setting = PluginCoreDAO.getInstance().loadSnapshot().getSetting().getRuntime();
            backoffMs = setting.getStartFailureBackoffSeconds() * 1000L;
        } catch (RuntimeException | Error settingFailure) {
            logBestEffort(Level.WARNING, "unable to load plugin restart backoff; using default", settingFailure);
        }
        startCoordinator.recordFailure(pluginId, backoffMs);
    }

    private Integer processExitCode(Process process) {
        try {
            return process.exitValue();
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    private boolean destroyCurrentProcess(String pluginId, String pluginShortName, Process expectedProcess) {
        ProcessSlot slot = processMap.get(pluginId);
        if (expectedProcess != null && (slot == null || slot.process != expectedProcess)) {
            return false;
        }
        if (slot != null && !terminateAndRemove(pluginId, slot)) {
            LOGGER.warning(PluginLogContext.prefix("plugin process is still alive; keep its runtime slot reserved"));
            return false;
        }
        cleanupProcessState(pluginId, pluginShortName);
        return true;
    }

    private boolean terminateAndRemove(String pluginId, ProcessSlot slot) {
        Process process = slot.process;
        if (process == null) {
            return processMap.remove(pluginId, slot);
        }
        if (!terminateProcess(process)) {
            return false;
        }
        return processMap.remove(pluginId, slot);
    }

    private boolean terminateProcess(Process process) {
        if (!process.isAlive()) {
            return true;
        }
        boolean gracefulRequested = false;
        try {
            process.destroy();
            gracefulRequested = true;
        } catch (RuntimeException | Error e) {
            logBestEffort(Level.WARNING, "unable to request graceful plugin shutdown", e);
        }
        if (gracefulRequested && awaitExit(process, PROCESS_STOP_GRACE_MS) && !process.isAlive()) {
            return true;
        }
        if (!process.isAlive()) {
            return true;
        }
        try {
            process.destroyForcibly();
        } catch (RuntimeException | Error e) {
            logBestEffort(Level.WARNING, "unable to force plugin shutdown", e);
            return !process.isAlive();
        }
        awaitExit(process, PROCESS_STOP_GRACE_MS);
        return !process.isAlive();
    }

    private boolean awaitExit(Process process, long timeoutMs) {
        try {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void logBestEffort(Level level, String message, Throwable failure) {
        try {
            LOGGER.log(level, PluginLogContext.prefix(message), failure);
        } catch (RuntimeException | Error ignored) {
            // Cleanup must continue even when the runtime is already under memory pressure.
        }
    }

    private void cleanupProcessState(String pluginId, String pluginShortName) {
        sessionRegistry.closeLocalSessionsByPluginId(pluginId);
        String pluginName = pluginNameOrShortName(pluginId, pluginShortName);
        markProcessRuntimeStopped(pluginId, pluginName);
        runtimeStateService().markStopped(pluginId, pluginName);
    }

    private boolean withinStartGrace(ProcessSlot slot) {
        return slot.startedAt > 0 && System.currentTimeMillis() - slot.startedAt < PROCESS_START_GRACE_MS;
    }

    private PluginRuntimeStateService runtimeStateService() {
        return new PluginRuntimeStateService(new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()), new DefaultPluginRuntimeStarter());
    }

    private PluginRuntimeStateService runtimeStateService(String runtimeInstanceId) {
        return new PluginRuntimeStateService(new PluginRuntimeStateStore(new WebsiteRuntimeKvStore()),
                new DefaultPluginRuntimeStarter(), runtimeInstanceId);
    }

    public Long processIdByPluginId(String pluginId) {
        return processIdMap.get(pluginId);
    }

    public Optional<String> runtimeInstanceIdByPluginId(String pluginId) {
        return Optional.ofNullable(processRuntimeInstanceIdMap.get(pluginId));
    }

    public void markReadyIfCurrent(String pluginId, String runtimeInstanceId, Long processId) {
        if (StringUtils.isEmpty(pluginId) || StringUtils.isEmpty(runtimeInstanceId) || processId == null) {
            return;
        }
        synchronized (processLifecycleLock) {
            ProcessSlot slot = processMap.get(pluginId);
            if (slot == null || slot.process == null
                    || !Objects.equals(processId, processIdMap.get(pluginId))
                    || !Objects.equals(runtimeInstanceId, processRuntimeInstanceIdMap.get(pluginId))) {
                return;
            }
            slot.readyAt = System.currentTimeMillis();
        }
    }

    public PluginSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    private void markProcessRuntimeStopped(String pluginId, String pluginName) {
        Long processId = processIdMap.remove(pluginId);
        String runtimeInstanceId = processRuntimeInstanceIdMap.remove(pluginId);
        if (processId == null || runtimeInstanceId == null) {
            return;
        }
        runtimeStateService(runtimeInstanceId).markStopped(pluginId, pluginName);
    }

    private String pluginNameOrShortName(String pluginId, String fallback) {
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        for (PluginVO pluginVO : pluginCore.getPluginInfoMap().values()) {
            if (pluginVO.getPlugin() != null && Objects.equals(pluginId, pluginVO.getPlugin().getId())) {
                return sessionRegistry.nameOrShortName(pluginVO.getPlugin());
            }
        }
        return fallback;
    }

    static LaunchCommand buildLaunchCommand(File pluginFile,
                                            int masterPort,
                                            String pluginId,
                                            String userDir,
                                            String tmpDir,
                                            String pluginJvmArgs,
                                            String javaHome) {
        List<String> args = new ArrayList<>();
        if (pluginFile.getName().endsWith(".jar")) {
            args.add("-Djava.io.tmpdir=" + tmpDir);
            args.add("-Duser.dir=" + userDir);
            args.add("-Duser.home=" + userDir);
            appendJvmArgs(args, pluginJvmArgs);
            args.add("-jar");
            args.add(pluginFile.getAbsolutePath());
        }
        args.add(masterPort + "");
        args.add(pluginId);
        Map<String, String> environment = new HashMap<>();
        environment.put("HOME", userDir);
        environment.put("USERPROFILE", userDir);
        environment.put("TMPDIR", tmpDir);
        environment.put("TEMP", tmpDir);
        environment.put("TMP", tmpDir);
        return new LaunchCommand(programName(pluginFile, javaHome), args, new File(userDir), environment);
    }

    private static void appendJvmArgs(List<String> args, String pluginJvmArgs) {
        if (StringUtils.isEmpty(pluginJvmArgs)) {
            return;
        }
        for (String arg : pluginJvmArgs.trim().split("\\s+")) {
            if (!StringUtils.isEmpty(arg)) {
                args.add(arg);
            }
        }
    }

    public static String runtimeMode(File pluginFile) {
        if (pluginFile != null && pluginFile.getName().endsWith(".jar")) {
            return "process";
        }
        return "native";
    }

    private static String programName(File file, String javaHome) {
        if (file.getName().endsWith(".jar")) {
            if (Objects.isNull(javaHome)) {
                return "java";
            }
            return javaHome + "/bin/java";
        }
        return file.getAbsolutePath();
    }

    static final class ProcessSlot {

        final String pluginShortName;
        final AtomicBoolean cleaned = new AtomicBoolean(false);
        final CountDownLatch spawnFinished = new CountDownLatch(1);
        final CountDownLatch initializationFinished = new CountDownLatch(1);
        final CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        volatile Process process;
        volatile long startedAt;
        volatile long readyAt;
        volatile boolean shutdownRequested;

        ProcessSlot(String pluginShortName) {
            this.pluginShortName = pluginShortName;
        }

        void finishSpawn(Process process) {
            this.process = process;
            if (process != null) {
                this.startedAt = System.currentTimeMillis();
            }
            spawnFinished.countDown();
        }
    }

    static class LaunchCommand {

        final String program;
        final List<String> args;
        final File workingDirectory;
        final Map<String, String> environment;

        LaunchCommand(String program, List<String> args, File workingDirectory, Map<String, String> environment) {
            this.program = program;
            this.args = args;
            this.workingDirectory = workingDirectory;
            this.environment = environment;
        }
    }
}
