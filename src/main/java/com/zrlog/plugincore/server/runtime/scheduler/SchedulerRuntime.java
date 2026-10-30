package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugincore.server.runtime.capability.SocketCapabilityInvoker;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.CronParseException;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.capability.CapabilityInvoker;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.InvokeContext;
import com.zrlog.plugincore.server.runtime.capability.RuntimeSources;
import com.zrlog.plugincore.server.runtime.lock.DistributedLock;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.state.PluginIdleStopRunner;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.vo.PluginVO;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class SchedulerRuntime {

    static final int EXECUTION_LOCK_STRIPES = 256;
    private static final Semaphore[] EXECUTION_LOCKS = createExecutionLocks();
    private static final int STORE_UPDATE_RETRIES = 3;
    private static final int MAX_TICK_TASK_THREADS = 8;
    private static final int MAX_TICK_TASKS = 64;
    private static final long REQUEST_BUDGET_MILLIS = 645000L;
    private static final long RESULT_RESERVE_MILLIS = 5000L;
    private static final long LEASE_RESERVE_MILLIS = 30000L;
    private static final String LOCK_KEY_PREFIX = "plugin-scheduler-";

    private final AutomationStore automationStore;
    private final AutomationRunStore automationRunStore;
    private final CapabilityInvoker capabilityInvoker;
    private final CapabilityStore capabilityStore;
    private final BasicCronParser cronParser;
    private final SchedulerTaskLockFactory taskLockFactory;
    private final Supplier<PluginRuntimeSetting> runtimeSettingSupplier;
    private final Supplier<PluginCore> pluginCoreSupplier;
    private final PluginBootstrapService pluginBootstrapService;

    public SchedulerRuntime(AutomationStore automationStore,
                            AutomationRunStore automationRunStore,
                            CapabilityStore capabilityStore,
                            CapabilityInvoker capabilityInvoker,
                            BasicCronParser cronParser) {
        this(automationStore, automationRunStore, capabilityStore, capabilityInvoker, cronParser, new PluginCore());
    }

    public SchedulerRuntime(AutomationStore automationStore,
                            AutomationRunStore automationRunStore,
                            CapabilityStore capabilityStore,
                            CapabilityInvoker capabilityInvoker,
                            BasicCronParser cronParser,
                            PluginCore pluginCore) {
        this(automationStore, automationRunStore, capabilityStore, capabilityInvoker, cronParser,
                defaultLockFactory(), runtimeSettingSupplier(pluginCore), () -> pluginCore,
                PluginRuntimeBridge.pluginBootstrap());
    }

    SchedulerRuntime(AutomationStore automationStore,
                     AutomationRunStore automationRunStore,
                     CapabilityStore capabilityStore,
                     CapabilityInvoker capabilityInvoker,
                     BasicCronParser cronParser,
                     SchedulerTaskLockFactory taskLockFactory) {
        this(automationStore, automationRunStore, capabilityStore, capabilityInvoker, cronParser, taskLockFactory,
                PluginRuntimeSetting::new);
    }

    SchedulerRuntime(AutomationStore automationStore,
                     AutomationRunStore automationRunStore,
                     CapabilityStore capabilityStore,
                     CapabilityInvoker capabilityInvoker,
                     BasicCronParser cronParser,
                     SchedulerTaskLockFactory taskLockFactory,
                     Supplier<PluginRuntimeSetting> runtimeSettingSupplier) {
        this(automationStore, automationRunStore, capabilityStore, capabilityInvoker, cronParser, taskLockFactory,
                runtimeSettingSupplier, PluginCore::new, PluginRuntimeBridge.pluginBootstrap());
    }

    SchedulerRuntime(AutomationStore automationStore,
                     AutomationRunStore automationRunStore,
                     CapabilityStore capabilityStore,
                     CapabilityInvoker capabilityInvoker,
                     BasicCronParser cronParser,
                     SchedulerTaskLockFactory taskLockFactory,
                     Supplier<PluginRuntimeSetting> runtimeSettingSupplier,
                     Supplier<PluginCore> pluginCoreSupplier,
                     PluginBootstrapService pluginBootstrapService) {
        this.automationStore = automationStore;
        this.automationRunStore = automationRunStore;
        this.capabilityInvoker = capabilityInvoker;
        this.capabilityStore = capabilityStore;
        this.cronParser = cronParser;
        this.taskLockFactory = taskLockFactory;
        this.runtimeSettingSupplier = runtimeSettingSupplier;
        this.pluginCoreSupplier = pluginCoreSupplier;
        this.pluginBootstrapService = pluginBootstrapService;
    }

    public SchedulerTickResult tick(ZonedDateTime now) {
        return tick(now, RuntimeSources.SCHEDULER);
    }

    public SchedulerTickResult tick(ZonedDateTime now, String source) {
        return tick(now, source, UUID.randomUUID().toString());
    }

    public SchedulerTickResult tick(ZonedDateTime now, String source, String triggerId) {
        return tickBatch(now, source, triggerId, null, legacyBudgetMillis(automationStore.list()), false, false);
    }

    /** Host ownership suppresses plugin business schedules, not the core's process maintenance. */
    public SchedulerTickResult tickMaintenance(ZonedDateTime now) {
        return tickBatch(now, RuntimeSources.SCHEDULER, UUID.randomUUID().toString(), null,
                REQUEST_BUDGET_MILLIS, true, false);
    }

    /** Startup and invocation share the caller's budget; no business worker outlives this method. */
    public SchedulerTickResult tickFromHost(ZonedDateTime now, String triggerId, int actorId, long budgetMillis) {
        if (actorId < 1 || budgetMillis < 1000 || budgetMillis > REQUEST_BUDGET_MILLIS)
            throw new IllegalArgumentException("Invalid host tick");
        return tickBatch(now, RuntimeSources.TICK, triggerId, String.valueOf(actorId), budgetMillis, false, true);
    }

    private SchedulerTickResult tickBatch(ZonedDateTime now, String source, String triggerId, String actorId,
                                         long budgetMillis, boolean maintenanceOnly, boolean businessOnly) {
        if (triggerId == null || !triggerId.matches("[a-zA-Z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("Invalid scheduler trigger id");
        RuntimeDeadline deadline = RuntimeDeadline.afterMillis(budgetMillis);
        long startedNanos = System.nanoTime();
        SchedulerTickResult result = new SchedulerTickResult();
        result.setTriggerId(triggerId);
        recoverExpiredRuns(now);
        List<PluginAutomation> selected = new ArrayList<>();
        for (PluginAutomation task : businessOnly ? automationStore.list() : ensureSystemAutomations(now)) {
            boolean maintenance = RuntimeSystemAutomations.isRuntimeMaintenance(task);
            if ((!maintenanceOnly || maintenance) && (!businessOnly || !maintenance)) selected.add(task);
        }
        List<PluginAutomation> batch = selectClaimBatch(selected, now, result);
        if (batch.isEmpty()) return result;
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(MAX_TICK_TASK_THREADS, batch.size()), runnable -> {
            Thread thread = new Thread(runnable, "zrlog-plugin-scheduler-task");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<SchedulerTickResult>> futures = new ArrayList<>();
            AtomicInteger next = new AtomicInteger();
            for (int i = 0; i < Math.min(MAX_TICK_TASK_THREADS, batch.size()); i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    SchedulerTickResult part = new SchedulerTickResult();
                    int index;
                    while ((index = next.getAndIncrement()) < batch.size()) {
                        PluginAutomation task = batch.get(index);
                        int timeout = timeoutSeconds(task);
                        if (Thread.currentThread().isInterrupted()
                                || deadline.remainingMillis() < timeout * 1000L + RESULT_RESERVE_MILLIS
                                        + SocketCapabilityInvoker.CLEANUP_RESERVE_MILLIS) {
                            skipChild(part, task, "insufficient_budget");
                            continue;
                        }
                        Semaphore lock = executionLock(task);
                        if (!lock.tryAcquire()) { skipChild(part, task, "already_running"); continue; }
                        SchedulerTaskLock taskLock = null;
                        boolean acquired = false;
                        try (PluginLogContext.Scope ignored = automationLogScope(task)) {
                            taskLock = taskLockFactory.create(executionKey(task), deadline.remainingMillis() + LEASE_RESERVE_MILLIS);
                            acquired = taskLock.tryLock();
                            if (!acquired) { skipChild(part, task, "already_running"); continue; }
                            if (deadline.remainingMillis() < timeout * 1000L + RESULT_RESERVE_MILLIS
                                        + SocketCapabilityInvoker.CLEANUP_RESERVE_MILLIS) {
                                skipChild(part, task, "insufficient_budget"); continue;
                            }
                            ZonedDateTime claimTime = elapsedTime(now, startedNanos);
                            // Workers claim only the task they can start now, never the waiting queue.
                            PluginAutomation claimed = claimAutomation(task, claimTime, UUID.randomUUID().toString(), false,
                                    claimTime.plusNanos((deadline.remainingMillis() + LEASE_RESERVE_MILLIS) * 1000000));
                            if (claimed == null) { skipChild(part, task, "not_due"); continue; }
                            PluginAutomationRun run;
                            try {
                                run = executeAndRecord(claimed, claimTime, source, triggerId, actorId,
                                        System.currentTimeMillis() + deadline.remainingMillis() - RESULT_RESERVE_MILLIS, timeout);
                                finishClaimedAutomation(claimed, elapsedTime(now, startedNanos), false,
                                        "uncertain".equals(run.getStatus()));
                            } catch (RuntimeException unavailable) {
                                run = newRun(claimed);
                                run.setStatus("uncertain");
                                run.setErrorMessage("Execution could not be recorded. Review the outcome in plugin management.");
                            }
                            part.getChildren().add(SchedulerChildResult.from(claimed, run));
                            part.getRunIds().add(run.getId());
                            if ("success".equals(run.getStatus())) part.executed();
                            else { part.failed(); if ("uncertain".equals(run.getStatus())) part.uncertain(); }
                        } finally {
                            try { if (acquired) taskLock.unlock(); }
                            finally { lock.release(); }
                        }
                    }
                    return part;
                }, workers));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<SchedulerTickResult> future : futures) result.merge(future.join());
        } finally {
            workers.shutdown();
        }
        return result;
    }

    private int timeoutSeconds(PluginAutomation task) {
        if (RuntimeSystemAutomations.isRuntimeMaintenance(task)) return 30;
        return capabilityStore.find(task.getPluginId(), task.getCapabilityKey())
                .map(capability -> capability.getTimeoutSeconds()).filter(seconds -> seconds > 0)
                .orElse(com.zrlog.plugin.common.PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT_SECONDS);
    }

    private long legacyBudgetMillis(List<PluginAutomation> tasks) {
        // Preserve explicitly declared long capabilities on legacy/manual routes; host requests keep their caller limit.
        long budget = REQUEST_BUDGET_MILLIS;
        for (PluginAutomation task : tasks) if (Boolean.TRUE.equals(task.getEnabled()))
            budget = Math.max(budget, timeoutSeconds(task) * 1000L + 45000L);
        return budget;
    }

    private static ZonedDateTime elapsedTime(ZonedDateTime startedAt, long startedNanos) {
        return startedAt.plusNanos(Math.max(0, System.nanoTime() - startedNanos));
    }

    private void skipChild(SchedulerTickResult result, PluginAutomation task, String reason) {
        PluginAutomationRun unstarted = new PluginAutomationRun();
        unstarted.setStatus("skipped");
        SchedulerChildResult child = SchedulerChildResult.from(task, unstarted);
        child.reason = reason;
        result.getChildren().add(child);
        result.skipped();
    }

    private List<PluginAutomation> selectClaimBatch(List<PluginAutomation> automations,
                                                    ZonedDateTime now,
                                                    SchedulerTickResult result) {
        Comparator<PluginAutomation> priority = Comparator.comparingLong(SchedulerRuntime::nextRunPriority);
        PriorityQueue<PluginAutomation> oldest = new PriorityQueue<>(MAX_TICK_TASKS, priority.reversed());
        for (PluginAutomation automation : automations) {
            if (!Boolean.TRUE.equals(automation.getEnabled()) || !readyToClaim(automation, now)) {
                result.skipped();
                continue;
            }
            if (oldest.size() < MAX_TICK_TASKS) {
                oldest.offer(automation);
                continue;
            }
            if (priority.compare(automation, oldest.peek()) < 0) {
                oldest.poll();
                oldest.offer(automation);
            }
            result.skipped();
        }
        List<PluginAutomation> claimBatch = new ArrayList<>(oldest);
        claimBatch.sort(priority);
        return claimBatch;
    }

    private boolean readyToClaim(PluginAutomation automation, ZonedDateTime now) {
        if (automation.isReviewRequired()) return false;
        if (automation.getNextRunAt() == null) {
            return true;
        }
        return automation.getNextRunAt() <= SchedulerTimes.millis(now) && !leaseActive(automation, now);
    }

    private static long nextRunPriority(PluginAutomation automation) {
        return automation.getNextRunAt() == null ? Long.MIN_VALUE : automation.getNextRunAt();
    }

    private List<PluginAutomation> ensureSystemAutomations(ZonedDateTime now) {
        PluginRuntimeSetting runtimeSetting = runtimeSettingSupplier.get();
        if (runtimeSetting == null) {
            runtimeSetting = new PluginRuntimeSetting();
        }
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            List<PluginAutomation> automations = new ArrayList<>(snapshot.getDocument().getItems());
            boolean changed = RuntimeSystemAutomations.ensureRuntimeMaintenance(automations, runtimeSetting, cronParser, now);
            if (!changed) {
                return snapshot.getDocument().getItems();
            }
            snapshot.getDocument().setItems(automations);
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return automations;
            }
        }
        throw new IllegalStateException("Failed to ensure scheduler system automations due to concurrent modification");
    }

    public PluginAutomationRun runNow(String automationId, ZonedDateTime now) {
        if (automationId == null || automationId.trim().isEmpty()) {
            throw new CronParseException("Automation id is empty");
        }
        recoverExpiredRuns(now);
        List<PluginAutomation> automations = automationStore.list();
        for (PluginAutomation automation : automations) {
            if (!Objects.equals(automationId, automation.getId())) {
                continue;
            }
            if (automation.isReviewRequired()) {
                throw new CronParseException("Review the previous execution outcome before running this task again.");
            }
            try (PluginLogContext.Scope ignored = automationLogScope(automation)) {
                Semaphore lock = executionLock(automation);
                if (!lock.tryAcquire()) {
                    throw new CronParseException("Automation is already running");
                }
                SchedulerTaskLock taskLock = null;
                boolean acquired = false;
                long startedNanos = System.nanoTime();
                RuntimeDeadline deadline = RuntimeDeadline.afterMillis(legacyBudgetMillis(List.of(automation)));
                try {
                    int timeout = timeoutSeconds(automation);
                    if (deadline.remainingMillis() < timeout * 1000L + RESULT_RESERVE_MILLIS
                                        + SocketCapabilityInvoker.CLEANUP_RESERVE_MILLIS)
                        throw new CronParseException("The task requires a longer execution budget");
                    taskLock = taskLockFactory.create(executionKey(automation), deadline.remainingMillis() + LEASE_RESERVE_MILLIS);
                    acquired = taskLock.tryLock();
                    if (!acquired) throw new CronParseException("Automation is already running");
                    if (deadline.remainingMillis() < timeout * 1000L + RESULT_RESERVE_MILLIS
                                        + SocketCapabilityInvoker.CLEANUP_RESERVE_MILLIS)
                        throw new CronParseException("The task requires a longer execution budget");
                    ZonedDateTime claimTime = elapsedTime(now, startedNanos);
                    PluginAutomation claimed = claimAutomation(automation, claimTime, UUID.randomUUID().toString(), true,
                            claimTime.plusNanos((deadline.remainingMillis() + LEASE_RESERVE_MILLIS) * 1000000));
                    if (claimed == null) throw new CronParseException("Automation is already running");
                    PluginAutomationRun run = executeAndRecord(claimed, claimTime, RuntimeSources.TICK,
                            UUID.randomUUID().toString(), null,
                            System.currentTimeMillis() + deadline.remainingMillis() - RESULT_RESERVE_MILLIS, timeout);
                    finishClaimedAutomation(claimed, elapsedTime(now, startedNanos), true, "uncertain".equals(run.getStatus()));
                    return run;
                } finally {
                    try { if (acquired) taskLock.unlock(); }
                    finally { lock.release(); }
                }
            }
        }
        throw new CronParseException("Automation not found");
    }

    private Semaphore executionLock(PluginAutomation automation) {
        // Local semaphore gates this JVM; DistributedLock gates duplicate scheduler tasks across processes.
        return EXECUTION_LOCKS[executionLockIndex(executionKey(automation))];
    }

    private String executionKey(PluginAutomation automation) {
        return automation.getPluginId() + ":" + automation.getCapabilityKey();
    }

    static int executionLockIndex(String key) {
        return Math.floorMod(Objects.hashCode(key), EXECUTION_LOCK_STRIPES);
    }

    static int executionLockStripeCount() {
        return EXECUTION_LOCKS.length;
    }

    static int maxTickTasks() {
        return MAX_TICK_TASKS;
    }

    private static Semaphore[] createExecutionLocks() {
        Semaphore[] locks = new Semaphore[EXECUTION_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Semaphore(1);
        }
        return locks;
    }

    private static SchedulerTaskLockFactory defaultLockFactory() {
        return new SchedulerTaskLockFactory() {
            public SchedulerTaskLock create(String key) { return create(key, REQUEST_BUDGET_MILLIS + LEASE_RESERVE_MILLIS); }
            public SchedulerTaskLock create(String key, long leaseMillis) { return distributedTaskLock(key, leaseMillis); }
        };
    }

    private static SchedulerTaskLock distributedTaskLock(String key, long leaseMillis) {
        DistributedLock lock = new DistributedLock(LOCK_KEY_PREFIX + key, leaseMillis);
        return new SchedulerTaskLock() {
            @Override
            public boolean tryLock() {
                return lock.tryLock();
            }

            @Override
            public void unlock() {
                lock.unlock();
            }
        };
    }

    private synchronized PluginAutomation claimAutomation(PluginAutomation automation, ZonedDateTime now, String leaseOwner,
                                              boolean ignoreSchedule, ZonedDateTime leaseUntil) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            PluginAutomation current = findAutomation(snapshot.getDocument().getItems(), automation);
            if (current == null || !Boolean.TRUE.equals(current.getEnabled()) || current.isReviewRequired()) {
                return null;
            }
            if (!ignoreSchedule) {
                if (current.getNextRunAt() == null) {
                    updateNextRunAt(current, now);
                    if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                        return null;
                    }
                    continue;
                }
                if (current.getNextRunAt() > SchedulerTimes.millis(now)) {
                    return null;
                }
            }
            if (current.getLeaseOwner() != null) {
                return null;
            }
            current.setLeaseOwner(leaseOwner);
            current.setLeaseUntil(leaseUntil.toString());
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return current;
            }
        }
        return null;
    }

    private synchronized void finishClaimedAutomation(PluginAutomation automation, ZonedDateTime now,
                                         boolean keepFutureNextRunAt, boolean uncertain) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            PluginAutomation current = findAutomation(snapshot.getDocument().getItems(), automation);
            if (current == null || !Objects.equals(automation.getLeaseOwner(), current.getLeaseOwner())) {
                return;
            }
            current.setLastRunAt(SchedulerTimes.millis(now));
            if (uncertain) {
                current.setEnabled(false);
                current.setReviewRequired(true);
                current.setReviewRunId(automation.getLeaseOwner());
            }
            if ((!keepFutureNextRunAt && Objects.equals(current.getNextRunAt(), automation.getNextRunAt()))
                    || current.getNextRunAt() == null) {
                updateNextRunAt(current, now);
            }
            clearLease(current);
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
        throw new IllegalStateException("Unable to finish automation claim");
    }

    /** An expired lease is evidence of an interrupted run, never permission to repeat its effects. */
    private void recoverExpiredRuns(ZonedDateTime now) {
        for (PluginAutomation automation : automationStore.list()) {
            if (automation.getLeaseOwner() == null || leaseActive(automation, now)) continue;
            PluginAutomationRun run = automationRunStore.find(automation.getLeaseOwner()).orElse(null);
            if (run == null) {
                run = newRun(automation);
                run.setStatus("running");
                automationRunStore.begin(run);
            }
            if ("running".equals(run.getStatus())) {
                run.setStatus("uncertain");
                run.setErrorMessage("Execution was interrupted. Review the outcome before enabling this task again.");
                finish(run);
                if (!automationRunStore.complete(run)) {
                    run = automationRunStore.find(run.getId()).orElseThrow(
                            () -> new IllegalStateException("Automation recovery record is missing"));
                }
            }
            finishClaimedAutomation(automation, now, false, "uncertain".equals(run.getStatus()));
        }
    }

    private PluginAutomationRun executeAndRecord(PluginAutomation automation, ZonedDateTime now,
                                                 String source, String triggerId, String actorId,
                                                 Long deadline, Integer timeoutSeconds) {
        PluginAutomationRun run = newRun(automation);
        run.setStatus("running");
        run.setSource(invocationSource(source));
        run.setActorId(actorId);
        run.setTriggerId(triggerId);
        automationRunStore.begin(run);
        try {
            executeAutomation(automation, now, source, run, deadline, timeoutSeconds);
        } catch (RuntimeException error) {
            run.setStatus("uncertain");
            run.setErrorMessage("Execution outcome is unknown. Review before retrying.");
            finish(run);
        }
        if (!automationRunStore.complete(run)) {
            return automationRunStore.find(run.getId()).orElseThrow(
                    () -> new IllegalStateException("Automation result is missing"));
        }
        return run;
    }

    private PluginAutomation findAutomation(List<PluginAutomation> automations, PluginAutomation target) {
        for (PluginAutomation automation : automations) {
            if (sameAutomation(automation, target)) {
                return automation;
            }
        }
        return null;
    }

    private boolean sameAutomation(PluginAutomation left, PluginAutomation right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && right.getId() != null) {
            return Objects.equals(left.getId(), right.getId());
        }
        return Objects.equals(left.getPluginId(), right.getPluginId())
                && Objects.equals(left.getCapabilityKey(), right.getCapabilityKey());
    }

    private boolean leaseActive(PluginAutomation automation, ZonedDateTime now) {
        if (automation.getLeaseOwner() == null || automation.getLeaseOwner().trim().isEmpty()
                || automation.getLeaseUntil() == null || automation.getLeaseUntil().trim().isEmpty()) {
            return false;
        }
        return ZonedDateTime.parse(automation.getLeaseUntil()).isAfter(now);
    }

    private void clearLease(PluginAutomation automation) {
        automation.setLeaseOwner(null);
        automation.setLeaseUntil(null);
    }

    private String invocationSource(String source) {
        return source == null || source.trim().isEmpty() ? RuntimeSources.SCHEDULER : source.trim();
    }

    private PluginAutomationRun executeAutomation(PluginAutomation automation, ZonedDateTime now, String source,
                                                 PluginAutomationRun run, Long deadline, Integer timeoutSeconds) {
        if (RuntimeSystemAutomations.isRuntimeMaintenance(automation)) {
            executeRuntimeMaintenance(automation, now);
            run.setStatus("success");
            return finish(run);
        }
        InvokeContext context = new InvokeContext();
        context.setSource(invocationSource(source));
        context.setRequestId(run.getId());
        context.setTraceId(run.getTriggerId());
        context.setAuditRequired(true);
        context.setUserId(run.getActorId());
        context.setDeadlineEpochMillis(deadline);
        context.setAllowStartup(true);
        context.setTimeoutSeconds(timeoutSeconds);
        CapabilityInvokeResult invokeResult = capabilityInvoker.invoke(automation.getPluginId(), automation.getCapabilityKey(), automation.getPayload(), context);
        run.setLegacyCompletion(context.isLegacyCompletion());
        if (context.isOutcomeUncertain()) {
            run.setStatus("uncertain");
            run.setErrorMessage("Plugin did not confirm the outcome. Review before enabling this task again.");
        } else if (invokeResult.isSuccess()) {
            run.setStatus("success");
        } else {
            run.setStatus("error");
            run.setErrorMessage(invokeResult.getErrorMessage());
        }
        return finish(run);
    }

    private void executeRuntimeMaintenance(PluginAutomation automation, ZonedDateTime now) {
        PluginCore pluginCore = pluginCore();
        PluginRuntimeStates.cleanupDirtyRuntimeStates(pluginCore);
        PluginRuntimeSetting runtimeSetting = RuntimeSystemAutomations.runtimeSettingFromPayload(automation.getPayload());
        if (!runtimeSetting.getOnDemandEnabled()) {
            pluginBootstrapService.loadPluginsAsync();
        }
        if (runtimeSetting.getIdleStopEnabled()) {
            new PluginIdleStopRunner(pluginBootstrapService).stopIdlePlugins(now.toInstant().toEpochMilli(), runtimeSetting, pluginCore);
        }
    }

    private PluginCore pluginCore() {
        PluginCore pluginCore = pluginCoreSupplier.get();
        return pluginCore == null ? new PluginCore() : pluginCore;
    }

    private PluginLogContext.Scope automationLogScope(PluginAutomation automation) {
        if (automation == null) {
            return PluginLogContext.open(null, null, null);
        }
        Plugin plugin = pluginById(automation.getPluginId());
        return PluginLogContext.open(automation.getPluginId(),
                plugin == null ? null : plugin.getShortName(),
                plugin == null ? null : plugin.getName());
    }

    private Plugin pluginById(String pluginId) {
        PluginCore pluginCore = pluginCore();
        if (pluginCore.getPluginInfoMap() == null) {
            return null;
        }
        for (PluginVO pluginVO : pluginCore.getPluginInfoMap().values()) {
            if (pluginVO != null && pluginVO.getPlugin() != null
                    && Objects.equals(pluginId, pluginVO.getPlugin().getId())) {
                return pluginVO.getPlugin();
            }
        }
        return null;
    }

    private static Supplier<PluginRuntimeSetting> runtimeSettingSupplier(PluginCore pluginCore) {
        return () -> pluginCore == null ? new PluginRuntimeSetting() : pluginCore.getSetting().getRuntime();
    }

    private PluginAutomationRun newRun(PluginAutomation automation) {
        PluginAutomationRun run = new PluginAutomationRun();
        run.setId(automation.getLeaseOwner());
        run.setAutomationId(automation.getId());
        run.setPluginId(automation.getPluginId());
        run.setCapabilityKey(automation.getCapabilityKey());
        run.setStartedAt(System.currentTimeMillis());
        return run;
    }

    private PluginAutomationRun finish(PluginAutomationRun run) {
        long finishedAt = System.currentTimeMillis();
        run.setFinishedAt(finishedAt);
        run.setDurationMs(Math.max(0L, finishedAt - run.getStartedAt()));
        return run;
    }

    private void updateNextRunAt(PluginAutomation automation, ZonedDateTime now) {
        ZoneId zoneId = resolveZone(automation.getTimezone());
        automation.setTimezone(zoneId.getId());
        automation.setNextRunAt(SchedulerTimes.nextRunAtMillis(cronParser, automation.getCron(), zoneId, now));
    }

    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.trim().isEmpty() || Objects.equals("system", timezone)) {
            return ZoneId.systemDefault();
        }
        return ZoneId.of(timezone);
    }

    interface SchedulerTaskLockFactory {
        SchedulerTaskLock create(String key);
        default SchedulerTaskLock create(String key, long leaseMillis) { return create(key); }
    }

    interface SchedulerTaskLock {
        boolean tryLock();

        void unlock();
    }
}
