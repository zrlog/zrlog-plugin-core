package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.CronParseException;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RuntimeAutomationService {

    private static final int STORE_UPDATE_RETRIES = 3;

    private final AutomationStore automationStore;
    private final CapabilityStore capabilityStore;
    private final BasicCronParser cronParser;
    private final Supplier<PluginRuntimeSetting> runtimeSettingSupplier;
    private final Consumer<PluginRuntimeSetting> runtimeSettingUpdater;

    public RuntimeAutomationService(AutomationStore automationStore, CapabilityStore capabilityStore, BasicCronParser cronParser) {
        this(automationStore, capabilityStore, cronParser, PluginRuntimeSetting::new);
    }

    public RuntimeAutomationService(AutomationStore automationStore,
                                    CapabilityStore capabilityStore,
                                    BasicCronParser cronParser,
                                    PluginRuntimeSetting runtimeSetting) {
        this(automationStore, capabilityStore, cronParser,
                () -> runtimeSetting == null ? new PluginRuntimeSetting() : runtimeSetting);
    }

    RuntimeAutomationService(AutomationStore automationStore,
                             CapabilityStore capabilityStore,
                             BasicCronParser cronParser,
                             Supplier<PluginRuntimeSetting> runtimeSettingSupplier) {
        this(automationStore, capabilityStore, cronParser, runtimeSettingSupplier,
                RuntimeAutomationService::persistRuntimeSetting);
    }

    RuntimeAutomationService(AutomationStore automationStore,
                             CapabilityStore capabilityStore,
                             BasicCronParser cronParser,
                             Supplier<PluginRuntimeSetting> runtimeSettingSupplier,
                             Consumer<PluginRuntimeSetting> runtimeSettingUpdater) {
        this.automationStore = automationStore;
        this.capabilityStore = capabilityStore;
        this.cronParser = cronParser;
        this.runtimeSettingSupplier = runtimeSettingSupplier;
        this.runtimeSettingUpdater = runtimeSettingUpdater;
    }

    public List<PluginAutomation> list() {
        return automationStore.list();
    }

    public List<PluginAutomation> listWithSystemAutomations() {
        return ensureSystemAutomations(ZonedDateTime.now());
    }

    public List<PluginAutomation> ensureSystemAutomations(ZonedDateTime now) {
        if (now == null) {
            now = ZonedDateTime.now(ZoneId.systemDefault());
        }
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
        throw new IllegalStateException("Failed to ensure system automations due to concurrent modification");
    }

    public List<PluginAutomation> ensureDefaultAutomations(List<PluginCapability> capabilities, ZonedDateTime now) {
        List<PluginAutomation> created = new ArrayList<>();
        if (capabilities == null) {
            return created;
        }
        List<PluginCapability> registeredCapabilities = capabilityStore.listAll();
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            AutomationMutationResult result = ensureDefaultAutomationItems(
                    snapshot.getDocument().getItems(), capabilities, registeredCapabilities, now);
            if (!result.isChanged()) {
                return result.getCreated();
            }
            snapshot.getDocument().setItems(result.getItems());
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return result.getCreated();
            }
        }
        throw new IllegalStateException("Failed to update default automations due to concurrent modification");
    }

    private AutomationMutationResult ensureDefaultAutomationItems(List<PluginAutomation> currentItems,
                                                                  List<PluginCapability> capabilities,
                                                                  List<PluginCapability> registeredCapabilities,
                                                                  ZonedDateTime now) {
        List<PluginAutomation> created = new ArrayList<>();
        List<PluginAutomation> automations = new ArrayList<>(currentItems);
        boolean changed = false;
        for (PluginCapability capability : capabilities) {
            if (!canCreateDefaultAutomation(capability)) {
                continue;
            }
            Optional<PluginAutomation> existing = findAutomationForCapability(automations, capability);
            if (existing.isPresent()) {
                changed = removeOrphanedAutomationsByCapabilityKey(automations, capability, registeredCapabilities) || changed;
                continue;
            }
            Optional<PluginAutomation> stale = findOrphanedAutomationByCapabilityKey(automations, capability, registeredCapabilities);
            if (stale.isPresent()) {
                PluginAutomation automation = stale.get();
                automation.setPluginId(capability.getPluginId());
                automation.setCapabilityKey(capability.getKey());
                prepareAutomation(automation, now, capability, automations);
                created.add(automation);
                changed = true;
                continue;
            }
            PluginAutomation automation = new PluginAutomation();
            automation.setId(defaultAutomationId(capability));
            automation.setName(defaultAutomationName(capability));
            automation.setPluginId(capability.getPluginId());
            automation.setCapabilityKey(capability.getKey());
            automation.setCron(capability.getDefaultCron());
            automation.setEnabled(Boolean.TRUE);
            automation.setPayload(new HashMap<>());
            prepareAutomation(automation, now, capability, automations);
            automations.add(automation);
            created.add(automation);
            changed = true;
        }
        return new AutomationMutationResult(automations, created, changed);
    }

    public Optional<PluginAutomation> ensureDefaultAutomation(PluginCapability capability, ZonedDateTime now) {
        List<PluginAutomation> created = ensureDefaultAutomations(Collections.singletonList(capability), now);
        return created.isEmpty() ? Optional.empty() : Optional.of(created.get(0));
    }

    public PluginAutomation save(PluginAutomation input, ZonedDateTime now) {
        if (RuntimeSystemAutomations.isRuntimeMaintenance(input)) {
            return saveSystemAutomation(input, now);
        }
        if (input == null) {
            throw new CronParseException("Automation is empty");
        }
        ZoneId zoneId = ZoneId.systemDefault();
        if (now == null) {
            now = ZonedDateTime.now(zoneId);
        }
        PluginAutomation baseInput = copyAutomation(input);
        List<PluginCapability> registeredCapabilities = capabilityStore.listAll();
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            PluginAutomation candidate = copyAutomation(baseInput);
            preserveExistingCapabilityBinding(candidate, snapshot.getDocument().getItems());
            validate(candidate);
            PluginCapability capability = CapabilityStore.find(
                    registeredCapabilities, candidate.getPluginId(), candidate.getCapabilityKey())
                    .orElseThrow(() -> new CronParseException("Capability not found"));
            validateSchedulableCapability(capability);
            SaveAutomationResult result = saveAutomationItems(
                    snapshot.getDocument().getItems(), candidate, now, zoneId, registeredCapabilities);
            if (!result.isChanged()) {
                copyAutomation(result.getSaved(), input);
                return input;
            }
            snapshot.getDocument().setItems(result.getItems());
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                copyAutomation(result.getSaved(), input);
                return input;
            }
        }
        throw new IllegalStateException("Failed to save automation due to concurrent modification");
    }

    public PluginAutomation saveRuntimeMaintenance(PluginRuntimeSetting runtimeSetting, ZonedDateTime now) {
        PluginAutomation input = new PluginAutomation();
        input.setId(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_ID);
        input.setPayload(RuntimeSystemAutomations.runtimePayload(runtimeSetting));
        return saveSystemAutomation(input, now);
    }

    private PluginAutomation saveSystemAutomation(PluginAutomation input, ZonedDateTime now) {
        ZoneId zoneId = ZoneId.systemDefault();
        if (now == null) {
            now = ZonedDateTime.now(zoneId);
        }
        PluginAutomation baseInput = RuntimeSystemAutomations.prepareRuntimeMaintenanceSave(input, cronParser, now);
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            List<PluginAutomation> automations = new ArrayList<>(snapshot.getDocument().getItems());
            PluginAutomation saved = copyAutomation(baseInput);
            List<PluginAutomation> next = new ArrayList<>();
            boolean replaced = false;
            for (PluginAutomation old : automations) {
                if (RuntimeSystemAutomations.isRuntimeMaintenance(old)) {
                    if (!replaced) {
                        saved.setLastRunAt(old.getLastRunAt());
                        next.add(saved);
                        replaced = true;
                    }
                    continue;
                }
                next.add(old);
            }
            if (!replaced) {
                next.add(saved);
            }
            if (sameAutomationItems(automations, next)) {
                updateRuntimeSetting(saved);
                return saved;
            }
            snapshot.getDocument().setItems(next);
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                updateRuntimeSetting(saved);
                return saved;
            }
        }
        throw new IllegalStateException("Failed to save system automation due to concurrent modification");
    }

    private void updateRuntimeSetting(PluginAutomation automation) {
        runtimeSettingUpdater.accept(RuntimeSystemAutomations.runtimeSettingFromPayload(automation.getPayload()));
    }

    private static void persistRuntimeSetting(PluginRuntimeSetting runtimeSetting) {
        PluginCoreDAO.getInstance().update(pluginCore -> {
            pluginCore.getSetting().setAutoDownloadMissingPluginFileEnabled(
                    runtimeSetting.getAutoDownloadMissingPluginFileEnabled());
            applyRuntimeSetting(pluginCore.getSetting().getRuntime(), runtimeSetting);
        });
    }

    static void applyRuntimeSetting(PluginRuntimeSetting target, PluginRuntimeSetting source) {
        target.setOnDemandEnabled(source.getOnDemandEnabled());
        target.setIdleStopEnabled(source.getIdleStopEnabled());
        target.setIdleTimeoutSeconds(source.getIdleTimeoutSeconds());
        target.setIdleScanIntervalSeconds(source.getIdleScanIntervalSeconds());
        target.setMaxRunningPlugins(source.getMaxRunningPlugins());
        target.setMaxConcurrentStarts(source.getMaxConcurrentStarts());
        target.setStartFailureBackoffSeconds(source.getStartFailureBackoffSeconds());
    }

    public boolean delete(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        if (Objects.equals(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_ID, id)) {
            return false;
        }
        for (int i = 0; i < STORE_UPDATE_RETRIES; i++) {
            AutomationStore.AutomationDocumentSnapshot snapshot = automationStore.loadSnapshot();
            List<PluginAutomation> automations = new ArrayList<>(snapshot.getDocument().getItems());
            boolean removed = automations.removeIf(item -> Objects.equals(id, item.getId()));
            if (!removed) {
                return false;
            }
            snapshot.getDocument().setItems(automations);
            if (automationStore.saveDocumentIfUnchanged(snapshot)) {
                return true;
            }
        }
        throw new IllegalStateException("Failed to delete automation due to concurrent modification");
    }

    private SaveAutomationResult saveAutomationItems(List<PluginAutomation> currentItems,
                                                     PluginAutomation input,
                                                     ZonedDateTime now,
                                                     ZoneId zoneId,
                                                     List<PluginCapability> registeredCapabilities) {
        List<PluginAutomation> automations = new ArrayList<>(currentItems);
        String id = input.getId();
        if (id == null || id.trim().isEmpty()) {
            id = existingAutomationId(automations, input).orElse(UUID.randomUUID().toString());
        }
        input.setId(id);
        input.setTriggerType("cron");
        input.setTimezone(zoneId.getId());
        if (input.getEnabled() == null) {
            input.setEnabled(Boolean.TRUE);
        }
        input.setNextRunAt(SchedulerTimes.nextRunAtMillis(cronParser, input.getCron(), zoneId, now));
        if (input.getPayload() == null) {
            input.setPayload(new HashMap<>());
        }

        List<PluginAutomation> next = new ArrayList<>();
        boolean saved = false;
        for (PluginAutomation old : automations) {
            if (Objects.equals(id, old.getId()) || sameCapability(old, input)
                    || orphanedSameCapabilityKey(old, input, registeredCapabilities)) {
                if (!saved) {
                    if (Objects.equals(id, old.getId())) {
                        input.setPluginId(old.getPluginId());
                        input.setCapabilityKey(old.getCapabilityKey());
                    }
                    input.setLastRunAt(old.getLastRunAt());
                    next.add(input);
                    saved = true;
                }
                continue;
            }
            next.add(old);
        }
        if (!saved) {
            next.add(input);
        }
        return new SaveAutomationResult(next, input, !sameAutomationItems(currentItems, next));
    }

    private void preserveExistingCapabilityBinding(PluginAutomation input, List<PluginAutomation> automations) {
        if (input == null || isBlank(input.getId())) {
            return;
        }
        for (PluginAutomation automation : automations) {
            if (Objects.equals(input.getId(), automation.getId())) {
                input.setPluginId(automation.getPluginId());
                input.setCapabilityKey(automation.getCapabilityKey());
                return;
            }
        }
    }

    private void validateSchedulableCapability(PluginCapability capability) {
        if (capability.getExposure() == null || !capability.getExposure().contains("scheduler")) {
            throw new CronParseException("Capability is not exposed to scheduler");
        }
        if (Boolean.FALSE.equals(capability.getEnabled())) {
            throw new CronParseException("Capability is disabled");
        }
    }

    private void validate(PluginAutomation input) {
        if (input == null) {
            throw new CronParseException("Automation is empty");
        }
        if (isBlank(input.getName())) {
            throw new CronParseException("Automation name is empty");
        }
        if (isBlank(input.getPluginId())) {
            throw new CronParseException("Plugin id is empty");
        }
        if (isBlank(input.getCapabilityKey())) {
            throw new CronParseException("Capability key is empty");
        }
        if (isBlank(input.getCron())) {
            throw new CronParseException("Cron expression is empty");
        }
    }

    private boolean canCreateDefaultAutomation(PluginCapability capability) {
        return capability != null
                && Objects.equals("scheduled", capability.getType())
                && capability.getExposure() != null
                && capability.getExposure().contains("scheduler")
                && !Boolean.FALSE.equals(capability.getEnabled())
                && !isBlank(capability.getPluginId())
                && !isBlank(capability.getKey())
                && !isBlank(capability.getDefaultCron());
    }

    private Optional<String> existingAutomationId(List<PluginAutomation> automations, PluginAutomation input) {
        return automations.stream()
                .filter(item -> sameCapability(item, input))
                .map(PluginAutomation::getId)
                .filter(id -> id != null && !id.trim().isEmpty())
                .findFirst();
    }

    private PluginAutomation prepareAutomation(PluginAutomation input,
                                               ZonedDateTime now,
                                               PluginCapability capability,
                                               List<PluginAutomation> automations) {
        validate(input);
        if (capability.getExposure() == null || !capability.getExposure().contains("scheduler")) {
            throw new CronParseException("Capability is not exposed to scheduler");
        }
        if (Boolean.FALSE.equals(capability.getEnabled())) {
            throw new CronParseException("Capability is disabled");
        }
        ZoneId zoneId = ZoneId.systemDefault();
        if (now == null) {
            now = ZonedDateTime.now(zoneId);
        }
        String id = input.getId();
        if (id == null || id.trim().isEmpty()) {
            id = existingAutomationId(automations, input).orElse(UUID.randomUUID().toString());
        }
        input.setId(id);
        input.setTriggerType("cron");
        input.setTimezone(zoneId.getId());
        if (input.getEnabled() == null) {
            input.setEnabled(Boolean.TRUE);
        }
        input.setNextRunAt(SchedulerTimes.nextRunAtMillis(cronParser, input.getCron(), zoneId, now));
        if (input.getPayload() == null) {
            input.setPayload(new HashMap<>());
        }
        return input;
    }

    private Optional<PluginAutomation> findAutomationForCapability(List<PluginAutomation> automations, PluginCapability capability) {
        return automations.stream()
                .filter(item -> Objects.equals(item.getPluginId(), capability.getPluginId())
                        && Objects.equals(item.getCapabilityKey(), capability.getKey()))
                .findFirst();
    }

    private Optional<PluginAutomation> findOrphanedAutomationByCapabilityKey(List<PluginAutomation> automations,
                                                                            PluginCapability capability,
                                                                            List<PluginCapability> registeredCapabilities) {
        return automations.stream()
                .filter(item -> Objects.equals(item.getCapabilityKey(), capability.getKey()))
                .filter(item -> !findCapability(registeredCapabilities, item.getPluginId(), item.getCapabilityKey()).isPresent())
                .findFirst();
    }

    private boolean removeOrphanedAutomationsByCapabilityKey(List<PluginAutomation> automations,
                                                            PluginCapability capability,
                                                            List<PluginCapability> registeredCapabilities) {
        return automations.removeIf(item -> Objects.equals(item.getCapabilityKey(), capability.getKey())
                && !findCapability(registeredCapabilities, item.getPluginId(), item.getCapabilityKey()).isPresent());
    }

    private Optional<PluginCapability> findCapability(List<PluginCapability> capabilities, String pluginId, String key) {
        return capabilities.stream()
                .filter(item -> Objects.equals(pluginId, item.getPluginId()))
                .filter(item -> Objects.equals(key, item.getKey()))
                .findFirst();
    }

    private boolean sameCapability(PluginAutomation left, PluginAutomation right) {
        return Objects.equals(left.getPluginId(), right.getPluginId())
                && Objects.equals(left.getCapabilityKey(), right.getCapabilityKey());
    }

    private boolean orphanedSameCapabilityKey(PluginAutomation left,
                                             PluginAutomation right,
                                             List<PluginCapability> registeredCapabilities) {
        return Objects.equals(left.getCapabilityKey(), right.getCapabilityKey())
                && !findCapability(registeredCapabilities, left.getPluginId(), left.getCapabilityKey()).isPresent();
    }

    private PluginAutomation copyAutomation(PluginAutomation source) {
        PluginAutomation target = new PluginAutomation();
        copyAutomation(source, target);
        return target;
    }

    private void copyAutomation(PluginAutomation source, PluginAutomation target) {
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
        target.setPayload(source.getPayload() == null ? null : new HashMap<>(source.getPayload()));
    }

    private boolean sameAutomationItems(List<PluginAutomation> left, List<PluginAutomation> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!sameAutomation(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameAutomation(PluginAutomation left, PluginAutomation right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getPluginId(), right.getPluginId())
                && Objects.equals(left.getCapabilityKey(), right.getCapabilityKey())
                && Objects.equals(left.getName(), right.getName())
                && Objects.equals(left.getTriggerType(), right.getTriggerType())
                && Objects.equals(left.getCron(), right.getCron())
                && Objects.equals(left.getTimezone(), right.getTimezone())
                && Objects.equals(left.getEnabled(), right.getEnabled())
                && Objects.equals(left.getSystem(), right.getSystem())
                && Objects.equals(left.getDeletable(), right.getDeletable())
                && Objects.equals(left.getNextRunAt(), right.getNextRunAt())
                && Objects.equals(left.getLastRunAt(), right.getLastRunAt())
                && Objects.equals(left.getLeaseOwner(), right.getLeaseOwner())
                && Objects.equals(left.getLeaseUntil(), right.getLeaseUntil())
                && samePayloadValue(left.getPayload(), right.getPayload());
    }

    private boolean samePayloadValue(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return Objects.equals(left, right);
            }
        }
        if (left instanceof Map && right instanceof Map) {
            Map<?, ?> leftMap = (Map<?, ?>) left;
            Map<?, ?> rightMap = (Map<?, ?>) right;
            if (leftMap.size() != rightMap.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : leftMap.entrySet()) {
                if (!rightMap.containsKey(entry.getKey())
                        || !samePayloadValue(entry.getValue(), rightMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<?> leftList = (List<?>) left;
            List<?> rightList = (List<?>) right;
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!samePayloadValue(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private String defaultAutomationId(PluginCapability capability) {
        return "default:" + capability.getPluginId() + ":" + capability.getKey();
    }

    private String defaultAutomationName(PluginCapability capability) {
        if (!isBlank(capability.getLabel())) {
            return capability.getLabel();
        }
        return capability.getKey();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class AutomationMutationResult {
        private final List<PluginAutomation> items;
        private final List<PluginAutomation> created;
        private final boolean changed;

        private AutomationMutationResult(List<PluginAutomation> items,
                                         List<PluginAutomation> created,
                                         boolean changed) {
            this.items = items;
            this.created = created;
            this.changed = changed;
        }

        public List<PluginAutomation> getItems() {
            return items;
        }

        public List<PluginAutomation> getCreated() {
            return created;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    private static class SaveAutomationResult {
        private final List<PluginAutomation> items;
        private final PluginAutomation saved;
        private final boolean changed;

        private SaveAutomationResult(List<PluginAutomation> items, PluginAutomation saved, boolean changed) {
            this.items = items;
            this.saved = saved;
            this.changed = changed;
        }

        public List<PluginAutomation> getItems() {
            return items;
        }

        public PluginAutomation getSaved() {
            return saved;
        }

        public boolean isChanged() {
            return changed;
        }
    }
}
