package com.zrlog.plugincore.server.runtime.scheduler;

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
    private static final long LEASE_SECONDS = 300L;
    private static final String LOCK_KEY_PREFIX = "plugin-scheduler-";

    private final AutomationStore automationStore;
    private final AutomationRunStore automationRunStore;
    private final CapabilityInvoker capabilityInvoker;
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
                SchedulerRuntime::distributedTaskLock, runtimeSettingSupplier(pluginCore), () -> pluginCore,
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
        SchedulerTickResult result = new SchedulerTickResult();
        List<PluginAutomation> automations = ensureSystemAutomations(now);
        List<PluginAutomation> claimBatch = selectClaimBatch(automations, now, result);
        List<ClaimCandidate> claimCandidates = new ArrayList<>(claimBatch.size());
        String invocationSource = invocationSource(source);
        for (PluginAutomation automation : claimBatch) {
            Semaphore lock = executionLock(automation);
            if (!lock.tryAcquire()) {
                result.skipped();
                continue;
            }
            SchedulerTaskLock taskLock = taskLockFactory.create(executionKey(automation));
            if (!taskLock.tryLock()) {
                lock.release();
                result.skipped();
                continue;
            }
            claimCandidates.add(new ClaimCandidate(automation, lock, taskLock, UUID.randomUUID().toString()));
        }
        List<ClaimedAutomation> claimedAutomations;
        try {
            claimedAutomations = claimDueAutomations(claimCandidates, now);
        } finally {
            for (ClaimCandidate claimCandidate : claimCandidates) {
                if (!claimCandidate.isClaimed()) {
                    claimCandidate.release();
                }
            }
        }
        for (int i = claimedAutomations.size(); i < claimCandidates.size(); i++) {
            result.skipped();
        }
        if (claimedAutomations.isEmpty()) {
            return result;
        }
        ExecutorService executorService = Executors.newFixedThreadPool(
                Math.min(MAX_TICK_TASK_THREADS, claimedAutomations.size()),
                runnable -> {
                    Thread thread = new Thread(runnable, "zrlog-plugin-scheduler-task");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<CompletableFuture<SchedulerTickResult>> futures = new ArrayList<>();
            AtomicInteger nextTaskIndex = new AtomicInteger();
            int workerCount = Math.min(MAX_TICK_TASK_THREADS, claimedAutomations.size());
            for (int i = 0; i < workerCount; i++) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> executeClaimedAutomations(claimedAutomations, nextTaskIndex, now, invocationSource),
                        executorService));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<SchedulerTickResult> future : futures) {
                result.merge(future.join());
            }
        } finally {
            executorService.shutdown();
        }
        return result;
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

    private SchedulerTickResult executeClaimedAutomations(List<ClaimedAutomation> claimedAutomations,
                                                           AtomicInteger nextTaskIndex,
                                                           ZonedDateTime now,
                                                           String source) {
        SchedulerTickResult result = new SchedulerTickResult();
        int taskIndex;
        while ((taskIndex = nextTaskIndex.getAndIncrement()) < claimedAutomations.size()) {
            result.merge(executeClaimedAutomation(claimedAutomations.get(taskIndex), now, source));
        }
        return result;
    }

    private boolean readyToClaim(PluginAutomation automation, ZonedDateTime now) {
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

    private SchedulerTickResult executeClaimedAutomation(ClaimedAutomation claimedAutomation, ZonedDateTime now, String source) {
        try (PluginLogContext.Scope ignored = automationLogScope(claimedAutomation.getAutomation())) {
            SchedulerTickResult result = new SchedulerTickResult();
            try {
                PluginAutomationRun run = executeAutomation(claimedAutomation.getAutomation(), now, source);
                automationRunStore.append(run);
                if (Objects.equals("success", run.getStatus())) {
                    result.executed();
                } else {
                    result.failed();
                }
            } catch (RuntimeException e) {
                result.failed();
            } finally {
                try {
                    finishClaimedAutomation(claimedAutomation.getAutomation(), now, false);
                } finally {
                    try {
                        claimedAutomation.getTaskLock().unlock();
                    } finally {
                        claimedAutomation.getLock().release();
                    }
                }
            }
            return result;
        }
    }

    public PluginAutomationRun runNow(String automationId, ZonedDateTime now) {
        if (automationId == null || automationId.trim().isEmpty()) {
            throw new CronParseException("Automation id is empty");
        }
        List<PluginAutomation> automations = automationStore.list();
        for (PluginAutomation automation : automations) {
            if (!Objects.equals(automationId, automation.getId())) {
                continue;
            }
            try (PluginLogContext.Scope ignored = automationLogScope(automation)) {
                Semaphore lock = executionLock(automation);
                if (!lock.tryAcquire()) {
                    throw new CronParseException("Automation is already running");
                }
                try {
                    PluginAutomation claimed = claimAutomation(automation, now, UUID.randomUUID().toString(), true);
                    if (claimed == null) {
                        throw new CronParseException("Automation is already running");
                    }
                    PluginAutomationRun run = executeAutomation(claimed, now, RuntimeSources.TICK);
                    automationRunStore.append(run);
                    finishClaimedAutomation(claimed, now, true);
                    return run;
                } finally {
                    lock.release();
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

    private static SchedulerTaskLock distributedTaskLock(String key) {
        DistributedLock lock = new DistributedLock(LOCK_KEY_PREFIX + key);
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

    private List<ClaimedAutomation> claimDueAutomations(List<ClaimCandidate> candidates, ZonedDateTime now) {
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            boolean changed = false;
            List<ClaimedAutomation> claimedAutomations = new ArrayList<>();
            for (ClaimCandidate candidate : candidates) {
                PluginAutomation current = findAutomation(snapshot.getDocument().getItems(), candidate.getAutomation());
                if (current == null || !Boolean.TRUE.equals(current.getEnabled())) {
                    continue;
                }
                if (current.getNextRunAt() == null) {
                    updateNextRunAt(current, now);
                    changed = true;
                    continue;
                }
                if (current.getNextRunAt() > SchedulerTimes.millis(now)) {
                    continue;
                }
                if (leaseActive(current, now)) {
                    continue;
                }
                current.setLeaseOwner(candidate.getLeaseOwner());
                current.setLeaseUntil(now.plusSeconds(LEASE_SECONDS).toString());
                claimedAutomations.add(new ClaimedAutomation(copyAutomation(current), candidate));
                changed = true;
            }
            if (!changed) {
                return new ArrayList<>();
            }
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                for (ClaimedAutomation claimedAutomation : claimedAutomations) {
                    claimedAutomation.getCandidate().markClaimed();
                }
                return claimedAutomations;
            }
        }
        return new ArrayList<>();
    }

    private PluginAutomation claimAutomation(PluginAutomation automation, ZonedDateTime now, String leaseOwner, boolean ignoreSchedule) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            PluginAutomation current = findAutomation(snapshot.getDocument().getItems(), automation);
            if (current == null || !Boolean.TRUE.equals(current.getEnabled())) {
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
            if (leaseActive(current, now)) {
                return null;
            }
            current.setLeaseOwner(leaseOwner);
            current.setLeaseUntil(now.plusSeconds(LEASE_SECONDS).toString());
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return current;
            }
        }
        return null;
    }

    private void finishClaimedAutomation(PluginAutomation automation, ZonedDateTime now, boolean keepFutureNextRunAt) {
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            PluginAutomation current = findAutomation(snapshot.getDocument().getItems(), automation);
            if (current == null || !Objects.equals(automation.getLeaseOwner(), current.getLeaseOwner())) {
                return;
            }
            current.setLastRunAt(SchedulerTimes.millis(now));
            if (!keepFutureNextRunAt || current.getNextRunAt() == null) {
                updateNextRunAt(current, now);
            }
            clearLease(current);
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return;
            }
        }
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

    private PluginAutomationRun executeAutomation(PluginAutomation automation, ZonedDateTime now, String source) {
        PluginAutomationRun run = newRun(automation);
        if (RuntimeSystemAutomations.isRuntimeMaintenance(automation)) {
            executeRuntimeMaintenance(automation, now);
            run.setStatus("success");
            return finish(run);
        }
        InvokeContext context = new InvokeContext();
        context.setSource(invocationSource(source));
        context.setRequestId(UUID.randomUUID().toString());
        context.setAuditRequired(true);
        CapabilityInvokeResult invokeResult = capabilityInvoker.invoke(automation.getPluginId(), automation.getCapabilityKey(), automation.getPayload(), context);
        if (invokeResult.isSuccess()) {
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
        run.setId(UUID.randomUUID().toString());
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

    private PluginAutomation copyAutomation(PluginAutomation source) {
        PluginAutomation target = new PluginAutomation();
        target.setId(source.getId());
        target.setPluginId(source.getPluginId());
        target.setCapabilityKey(source.getCapabilityKey());
        target.setName(source.getName());
        target.setTriggerType(source.getTriggerType());
        target.setCron(source.getCron());
        target.setTimezone(source.getTimezone());
        target.setEnabled(source.getEnabled());
        target.setSystem(source.getSystem());
        target.setDeletable(source.getDeletable());
        target.setNextRunAt(source.getNextRunAt());
        target.setLastRunAt(source.getLastRunAt());
        target.setLeaseOwner(source.getLeaseOwner());
        target.setLeaseUntil(source.getLeaseUntil());
        target.setPayload(source.getPayload());
        return target;
    }

    private static class ClaimCandidate {
        private final PluginAutomation automation;
        private final Semaphore lock;
        private final SchedulerTaskLock taskLock;
        private final String leaseOwner;
        private boolean claimed;

        private ClaimCandidate(PluginAutomation automation, Semaphore lock, SchedulerTaskLock taskLock, String leaseOwner) {
            this.automation = automation;
            this.lock = lock;
            this.taskLock = taskLock;
            this.leaseOwner = leaseOwner;
        }

        public PluginAutomation getAutomation() {
            return automation;
        }

        public String getLeaseOwner() {
            return leaseOwner;
        }

        public boolean isClaimed() {
            return claimed;
        }

        public void markClaimed() {
            this.claimed = true;
        }

        public void release() {
            try {
                taskLock.unlock();
            } finally {
                lock.release();
            }
        }
    }

    private static class ClaimedAutomation {
        private final PluginAutomation automation;
        private final ClaimCandidate candidate;

        private ClaimedAutomation(PluginAutomation automation, ClaimCandidate candidate) {
            this.automation = automation;
            this.candidate = candidate;
        }

        public PluginAutomation getAutomation() {
            return automation;
        }

        public ClaimCandidate getCandidate() {
            return candidate;
        }

        public Semaphore getLock() {
            return candidate.lock;
        }

        public SchedulerTaskLock getTaskLock() {
            return candidate.taskLock;
        }
    }

    interface SchedulerTaskLockFactory {
        SchedulerTaskLock create(String key);
    }

    interface SchedulerTaskLock {
        boolean tryLock();

        void unlock();
    }
}
