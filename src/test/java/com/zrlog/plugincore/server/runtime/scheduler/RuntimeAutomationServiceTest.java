package com.zrlog.plugincore.server.runtime.scheduler;

import com.google.gson.Gson;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.CronParseException;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RuntimeAutomationServiceTest {

    @Test
    public void shouldCreateAutomationWithResolvedTimezoneAndNextRunAt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation saved = service.save(automation(null), now());

        assertNotNull(saved.getId());
        assertEquals("cron", saved.getTriggerType());
        assertEquals(ZoneId.systemDefault().getId(), saved.getTimezone());
        assertNotNull(saved.getNextRunAt());
        assertEquals(1, service.list().size());
    }

    @Test
    public void shouldCreateDefaultAutomationFromScheduledCapability() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        capability.setLabel("扫描到期提醒");
        capability.setDefaultCron("*/5 * * * *");
        capabilityStore.register(capability);
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation created = service.ensureDefaultAutomations(Collections.singletonList(capability), now()).get(0);

        assertEquals("default:plugin-a:reminder.scanDueTasks", created.getId());
        assertEquals("扫描到期提醒", created.getName());
        assertEquals("reminder.scanDueTasks", created.getCapabilityKey());
        assertEquals("*/5 * * * *", created.getCron());
        assertEquals(Boolean.TRUE, created.getEnabled());
        assertNextRunAt(created.getNextRunAt(), 10, 5);
        assertEquals(1, service.list().size());
    }

    @Test
    public void shouldSkipDefaultAutomationForDisabledCapability() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        capability.setDefaultCron("*/5 * * * *");
        capability.setEnabled(Boolean.FALSE);
        capabilityStore.register(capability);
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        assertTrue(service.ensureDefaultAutomations(Collections.singletonList(capability), now()).isEmpty());

        assertTrue(service.list().isEmpty());
    }

    @Test
    public void shouldBatchDefaultAutomationBootstrapDocumentAccess() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability first = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        first.setDefaultCron("*/5 * * * *");
        PluginCapability second = capability("plugin-a", "reminder.dailySummary", "scheduler");
        second.setDefaultCron("0 9 * * *");
        capabilityStore.register(first);
        capabilityStore.register(second);
        kvStore.resetCounts();
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        service.ensureDefaultAutomations(Arrays.asList(first, second), now());

        assertEquals(1, kvStore.getCount(AutomationStore.KEY));
        assertEquals(1, kvStore.getCount(CapabilityStore.KEY));
        assertEquals(1, kvStore.putCount(AutomationStore.KEY));
        assertEquals(2, service.list().size());
    }

    @Test
    public void shouldKeepExistingAutomationForScheduledCapability() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        capability.setLabel("扫描到期提醒");
        capability.setDefaultCron("*/5 * * * *");
        capabilityStore.register(capability);
        PluginAutomation existing = automation("custom");
        existing.setCron("*/15 * * * *");
        existing.setEnabled(Boolean.FALSE);
        new AutomationStore(kvStore).saveAll(Collections.singletonList(existing));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        assertTrue(service.ensureDefaultAutomations(Collections.singletonList(capability), now()).isEmpty());
        assertEquals(1, service.list().size());
        assertEquals("custom", service.list().get(0).getId());
        assertEquals("*/15 * * * *", service.list().get(0).getCron());
        assertEquals(Boolean.FALSE, service.list().get(0).getEnabled());
    }

    @Test
    public void shouldMigrateOrphanedDefaultAutomationToCurrentPluginId() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-new", "reminder.scanDueTasks", "scheduler");
        capability.setLabel("扫描到期提醒");
        capability.setDefaultCron("*/5 * * * *");
        capabilityStore.register(capability);
        PluginAutomation old = automation("default:plugin-old:reminder.scanDueTasks");
        old.setPluginId("plugin-old");
        old.setCron("*/15 * * * *");
        new AutomationStore(kvStore).saveAll(Collections.singletonList(old));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation migrated = service.ensureDefaultAutomations(Collections.singletonList(capability), now()).get(0);

        assertEquals("default:plugin-old:reminder.scanDueTasks", migrated.getId());
        assertEquals("plugin-new", migrated.getPluginId());
        assertEquals("*/15 * * * *", migrated.getCron());
        assertEquals(1, service.list().size());
    }

    @Test
    public void shouldMigrateDefaultAutomationUsingCurrentInitCapabilityWhenCapabilitySnapshotIsStale() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-new", "reminder.scanDueTasks", "scheduler");
        capability.setLabel("扫描到期提醒");
        capability.setDefaultCron("*/5 * * * *");
        PluginAutomation old = automation("default:plugin-old:reminder.scanDueTasks");
        old.setPluginId("plugin-old");
        old.setCron("*/15 * * * *");
        new AutomationStore(kvStore).saveAll(Collections.singletonList(old));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation migrated = service.ensureDefaultAutomations(Collections.singletonList(capability), now()).get(0);

        assertEquals("default:plugin-old:reminder.scanDueTasks", migrated.getId());
        assertEquals("plugin-new", migrated.getPluginId());
        assertEquals("*/15 * * * *", migrated.getCron());
        assertEquals(1, service.list().size());
    }

    @Test
    public void shouldRemoveOrphanedDuplicateAutomationWhenCurrentOneExists() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-new", "reminder.scanDueTasks", "scheduler");
        capability.setDefaultCron("*/5 * * * *");
        capabilityStore.register(capability);
        PluginAutomation current = automation("current");
        current.setPluginId("plugin-new");
        PluginAutomation old = automation("old");
        old.setPluginId("plugin-old");
        new AutomationStore(kvStore).saveAll(Arrays.asList(current, old));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        assertTrue(service.ensureDefaultAutomations(Collections.singletonList(capability), now()).isEmpty());

        assertEquals(1, service.list().size());
        assertEquals("current", service.list().get(0).getId());
        assertEquals("plugin-new", service.list().get(0).getPluginId());
    }

    @Test
    public void shouldUpdateAutomationAndPreserveLastRunAt() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        AutomationStore automationStore = new AutomationStore(kvStore);
        PluginAutomation old = automation("a1");
        old.setLastRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(old));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation update = automation("a1");
        update.setName("Updated");
        PluginAutomation saved = service.save(update, now());

        assertEquals("Updated", saved.getName());
        assertEquals(Long.valueOf(SchedulerTimes.millis(now())), saved.getLastRunAt());
        assertEquals(1, service.list().size());
    }

    @Test
    public void shouldPreserveBindingFromSaveSnapshotWithoutExtraAutomationRead() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        AutomationStore automationStore = new AutomationStore(kvStore);
        automationStore.saveAll(Collections.singletonList(automation("a1")));
        RuntimeAutomationService service = service(kvStore, capabilityStore);
        PluginAutomation update = new PluginAutomation();
        update.setId("a1");
        update.setName("Updated");
        update.setCron("*/10 * * * *");
        update.setEnabled(Boolean.TRUE);
        kvStore.resetCounts();

        PluginAutomation saved = service.save(update, now());

        assertEquals("plugin-a", saved.getPluginId());
        assertEquals("reminder.scanDueTasks", saved.getCapabilityKey());
        assertEquals(1, kvStore.getCount(AutomationStore.KEY));
        assertEquals(1, kvStore.putCount(AutomationStore.KEY));
    }

    @Test
    public void shouldSkipSaveWriteWhenAutomationIsUnchangedAfterNormalization() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        RuntimeAutomationService service = service(kvStore, capabilityStore);
        PluginAutomation saved = service.save(automation(null), now());
        kvStore.resetCounts();

        PluginAutomation update = automation(saved.getId());
        PluginAutomation unchanged = service.save(update, now());

        assertEquals(saved.getId(), unchanged.getId());
        assertEquals(1, kvStore.getCount(CapabilityStore.KEY));
        assertEquals(1, kvStore.getCount(AutomationStore.KEY));
        assertEquals(0, kvStore.putCount(AutomationStore.KEY));
    }

    @Test
    public void shouldCompareNestedPayloadNumbersByValueWhenSaving() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        RuntimeAutomationService service = service(kvStore, capabilityStore);
        PluginAutomation input = automation(null);
        Map<String, Object> nested = new HashMap<>();
        nested.put("attempts", 2L);
        Map<String, Object> payload = new HashMap<>();
        payload.put("limits", Arrays.asList(1L, nested));
        input.setPayload(payload);
        PluginAutomation saved = service.save(input, now());
        kvStore.resetCounts();

        PluginAutomation update = automation(saved.getId());
        update.setPayload(payload);
        service.save(update, now());

        assertEquals(0, kvStore.putCount(AutomationStore.KEY));
    }

    @Test
    public void shouldReuseExistingAutomationWhenIdIsMissingForSameCapability() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        AutomationStore automationStore = new AutomationStore(kvStore);
        PluginAutomation old = automation("default:plugin-a:reminder.scanDueTasks");
        old.setLastRunAt(SchedulerTimes.millis(now()));
        automationStore.saveAll(Collections.singletonList(old));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation update = automation(null);
        update.setName("Updated");
        update.setCron("*/15 * * * *");
        PluginAutomation saved = service.save(update, now());

        assertEquals("default:plugin-a:reminder.scanDueTasks", saved.getId());
        assertEquals("Updated", saved.getName());
        assertEquals("*/15 * * * *", saved.getCron());
        assertEquals(Long.valueOf(SchedulerTimes.millis(now())), saved.getLastRunAt());
        assertEquals(1, service.list().size());
    }

    @Test
    public void shouldCollapseDuplicateAutomationsForSameCapability() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        AutomationStore automationStore = new AutomationStore(kvStore);
        automationStore.saveAll(Arrays.asList(automation("a1"), automation("a2")));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation update = automation("a2");
        update.setName("Updated");
        PluginAutomation saved = service.save(update, now());

        assertEquals("a2", saved.getId());
        assertEquals(1, service.list().size());
        assertEquals("Updated", service.list().get(0).getName());
    }

    @Test(expected = CronParseException.class)
    public void shouldRejectCapabilityWithoutSchedulerExposure() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "internal"));

        service(kvStore, capabilityStore).save(automation(null), now());
    }

    @Test(expected = CronParseException.class)
    public void shouldRejectDisabledCapability() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        capability.setEnabled(Boolean.FALSE);
        capabilityStore.register(capability);

        service(kvStore, capabilityStore).save(automation(null), now());
    }

    @Test
    public void shouldDeleteAutomationById() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        RuntimeAutomationService service = service(kvStore, capabilityStore);
        PluginAutomation saved = service.save(automation(null), now());

        assertTrue(service.delete(saved.getId()));
        assertFalse(service.delete(saved.getId()));
        assertEquals(0, service.list().size());
    }

    @Test
    public void shouldSaveRuntimeMaintenanceIntervalAndUpdateRuntimeSetting() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        AtomicReference<PluginRuntimeSetting> persistedSetting = new AtomicReference<>();
        RuntimeAutomationService service = new RuntimeAutomationService(
                new AutomationStore(kvStore), capabilityStore, new BasicCronParser(),
                PluginRuntimeSetting::new, persistedSetting::set);
        PluginAutomation automation = new PluginAutomation();
        automation.setId(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_ID);
        automation.setCron("*/5 * * * *");
        Map<String, Object> payload = new HashMap<>();
        payload.put("idleScanIntervalSeconds", 600L);
        automation.setPayload(payload);

        PluginAutomation saved = service.save(automation, now());

        assertEquals("*/10 * * * *", saved.getCron());
        assertEquals(Long.valueOf(600L), saved.getPayload().get("idleScanIntervalSeconds"));
        assertEquals(Long.valueOf(SchedulerTimes.millis(now().plusMinutes(10))), saved.getNextRunAt());
        assertNotNull(persistedSetting.get());
        assertEquals(Long.valueOf(600L), persistedSetting.get().getIdleScanIntervalSeconds());

        persistedSetting.set(null);
        service.save(saved, now());
        assertNotNull(persistedSetting.get());
        assertEquals(Long.valueOf(600L), persistedSetting.get().getIdleScanIntervalSeconds());
    }

    @Test
    public void shouldPreserveLegacyCustomRuntimeMaintenanceCronWhenSaved() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        RuntimeAutomationService service = new RuntimeAutomationService(
                new AutomationStore(kvStore), capabilityStore, new BasicCronParser(),
                PluginRuntimeSetting::new, ignored -> {});
        PluginAutomation automation = new PluginAutomation();
        automation.setId(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_ID);
        automation.setCron("*/7 * * * *");
        Map<String, Object> payload = new HashMap<>();
        payload.put("onDemandEnabled", Boolean.FALSE);
        automation.setPayload(payload);

        PluginAutomation saved = service.save(automation, now());

        assertEquals("*/7 * * * *", saved.getCron());
        assertEquals(Boolean.FALSE, saved.getPayload().get("onDemandEnabled"));
        assertFalse(saved.getPayload().containsKey("idleScanIntervalSeconds"));
    }

    @Test
    public void shouldSaveRuntimeMaintenanceAndSynchronizeAllRuntimeSettings() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AtomicReference<PluginRuntimeSetting> persistedSetting = new AtomicReference<>();
        AtomicInteger updateCount = new AtomicInteger();
        RuntimeAutomationService service = new RuntimeAutomationService(
                new AutomationStore(kvStore), new CapabilityStore(kvStore), new BasicCronParser(),
                PluginRuntimeSetting::new, setting -> {
                    persistedSetting.set(setting);
                    updateCount.incrementAndGet();
                });
        PluginRuntimeSetting requested = runtimeSetting();

        PluginAutomation saved = service.saveRuntimeMaintenance(requested, now());

        assertEquals(RuntimeSystemAutomations.RUNTIME_MAINTENANCE_ID, saved.getId());
        assertEquals("*/10 * * * *", saved.getCron());
        assertEquals(RuntimeSystemAutomations.runtimePayload(requested), saved.getPayload());
        assertRuntimeSettingEquals(requested, persistedSetting.get());
        assertEquals(1, updateCount.get());

        kvStore.resetCounts();
        persistedSetting.set(null);
        PluginAutomation unchanged = service.saveRuntimeMaintenance(requested, now());

        assertEquals(saved.getPayload(), unchanged.getPayload());
        assertEquals(0, kvStore.putCount(AutomationStore.KEY));
        assertRuntimeSettingEquals(requested, persistedSetting.get());
        assertEquals(2, updateCount.get());
    }

    @Test
    public void shouldUpdateRuntimeSettingOnlyAfterRuntimeMaintenanceCasSucceeds() {
        PluginAutomation external = externalAutomation("external");
        StaleAutomationKvStore kvStore = new StaleAutomationKvStore(automationDocumentJson(external));
        AtomicInteger updateCount = new AtomicInteger();
        AtomicReference<PluginRuntimeSetting> persistedSetting = new AtomicReference<>();
        RuntimeAutomationService service = new RuntimeAutomationService(
                new AutomationStore(kvStore), new CapabilityStore(kvStore), new BasicCronParser(),
                PluginRuntimeSetting::new, setting -> {
                    persistedSetting.set(setting);
                    updateCount.incrementAndGet();
                });
        PluginRuntimeSetting requested = runtimeSetting();

        service.saveRuntimeMaintenance(requested, now());

        assertEquals(2, kvStore.getAutomationCompareAndSetCount());
        assertEquals(1, updateCount.get());
        assertRuntimeSettingEquals(requested, persistedSetting.get());
        assertEquals(2, service.list().size());
        assertTrue(service.list().stream().anyMatch(item -> "external".equals(item.getId())));
        assertTrue(service.list().stream().anyMatch(RuntimeSystemAutomations::isRuntimeMaintenance));
    }

    @Test
    public void shouldRetryDefaultAutomationWhenConcurrentAutomationWriteWinsFirst() {
        PluginAutomation external = externalAutomation("external");
        StaleAutomationKvStore kvStore = new StaleAutomationKvStore(automationDocumentJson(external));
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        capability.setDefaultCron("*/5 * * * *");
        capabilityStore.register(capability);
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        assertEquals(1, service.ensureDefaultAutomations(Collections.singletonList(capability), now()).size());

        assertEquals(2, service.list().size());
        assertTrue(service.list().stream().anyMatch(item -> "external".equals(item.getId())));
        assertTrue(service.list().stream().anyMatch(item -> "default:plugin-a:reminder.scanDueTasks".equals(item.getId())));
        assertEquals(2, kvStore.getAutomationCompareAndSetCount());
    }

    @Test
    public void shouldRetrySaveWhenConcurrentAutomationWriteWinsFirst() {
        PluginAutomation external = externalAutomation("external");
        StaleAutomationKvStore kvStore = new StaleAutomationKvStore(automationDocumentJson(external));
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        PluginAutomation saved = service.save(automation(null), now());

        assertNotNull(saved.getId());
        assertEquals(2, service.list().size());
        assertTrue(service.list().stream().anyMatch(item -> "external".equals(item.getId())));
        assertTrue(service.list().stream().anyMatch(item -> "plugin-a".equals(item.getPluginId())));
        assertEquals(2, kvStore.getAutomationCompareAndSetCount());
    }

    @Test
    public void shouldRetryDeleteWhenConcurrentAutomationWriteWinsFirst() {
        PluginAutomation old = automation("old");
        PluginAutomation external = externalAutomation("external");
        StaleAutomationKvStore kvStore = new StaleAutomationKvStore(automationDocumentJson(old, external));
        new AutomationStore(kvStore).saveAll(Collections.singletonList(old));
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        RuntimeAutomationService service = service(kvStore, capabilityStore);

        assertTrue(service.delete("old"));

        assertEquals(1, service.list().size());
        assertEquals("external", service.list().get(0).getId());
        assertEquals(2, kvStore.getAutomationCompareAndSetCount());
    }

    private RuntimeAutomationService service(InMemoryRuntimeKvStore kvStore, CapabilityStore capabilityStore) {
        return new RuntimeAutomationService(new AutomationStore(kvStore), capabilityStore, new BasicCronParser());
    }

    private PluginAutomation automation(String id) {
        PluginAutomation automation = new PluginAutomation();
        automation.setId(id);
        automation.setName("Scan due reminders");
        automation.setPluginId("plugin-a");
        automation.setCapabilityKey("reminder.scanDueTasks");
        automation.setCron("*/5 * * * *");
        automation.setEnabled(Boolean.TRUE);
        return automation;
    }

    private PluginAutomation externalAutomation(String id) {
        PluginAutomation automation = automation(id);
        automation.setPluginId("plugin-b");
        automation.setCapabilityKey("email.send");
        automation.setName("External automation");
        return automation;
    }

    private PluginCapability capability(String pluginId, String key, String exposure) {
        PluginCapability capability = new PluginCapability();
        capability.setPluginId(pluginId);
        capability.setPluginName(pluginId);
        capability.setKey(key);
        capability.setType("scheduled");
        capability.setExposure(Arrays.asList(exposure));
        capability.setEnabled(Boolean.TRUE);
        return capability;
    }

    private PluginRuntimeSetting runtimeSetting() {
        PluginRuntimeSetting setting = new PluginRuntimeSetting();
        setting.setOnDemandEnabled(Boolean.TRUE);
        setting.setAutoDownloadMissingPluginFileEnabled(Boolean.FALSE);
        setting.setIdleStopEnabled(Boolean.TRUE);
        setting.setIdleTimeoutSeconds(420L);
        setting.setIdleScanIntervalSeconds(600L);
        setting.setMaxRunningPlugins(3L);
        setting.setMaxConcurrentStarts(1L);
        setting.setStartFailureBackoffSeconds(45L);
        return setting;
    }

    private void assertRuntimeSettingEquals(PluginRuntimeSetting expected, PluginRuntimeSetting actual) {
        assertNotNull(actual);
        assertEquals(expected.getOnDemandEnabled(), actual.getOnDemandEnabled());
        assertEquals(expected.getAutoDownloadMissingPluginFileEnabled(), actual.getAutoDownloadMissingPluginFileEnabled());
        assertEquals(expected.getIdleStopEnabled(), actual.getIdleStopEnabled());
        assertEquals(expected.getIdleTimeoutSeconds(), actual.getIdleTimeoutSeconds());
        assertEquals(expected.getIdleScanIntervalSeconds(), actual.getIdleScanIntervalSeconds());
        assertEquals(expected.getMaxRunningPlugins(), actual.getMaxRunningPlugins());
        assertEquals(expected.getMaxConcurrentStarts(), actual.getMaxConcurrentStarts());
        assertEquals(expected.getStartFailureBackoffSeconds(), actual.getStartFailureBackoffSeconds());
    }

    private void assertNextRunAt(Long nextRunAt, int hour, int minute) {
        ZonedDateTime next = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextRunAt), now().getZone());
        assertEquals(hour, next.getHour());
        assertEquals(minute, next.getMinute());
    }

    private ZonedDateTime now() {
        return ZonedDateTime.of(2026, 5, 29, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
    }

    private String automationDocumentJson(PluginAutomation... automations) {
        AutomationDocument document = new AutomationDocument();
        document.setItems(Arrays.asList(automations));
        return new Gson().toJson(document);
    }

    private static class StaleAutomationKvStore extends InMemoryRuntimeKvStore {
        private final String staleValue;
        private boolean staleInjected;
        private int automationCompareAndSetCount;

        private StaleAutomationKvStore(String staleValue) {
            this.staleValue = staleValue;
        }

        @Override
        public synchronized boolean compareAndSet(String key, Optional<String> expectedValue, String value) {
            if (!AutomationStore.KEY.equals(key)) {
                return super.compareAndSet(key, expectedValue, value);
            }
            automationCompareAndSetCount++;
            if (!staleInjected) {
                put(key, staleValue);
                staleInjected = true;
                return false;
            }
            return super.compareAndSet(key, expectedValue, value);
        }

        private int getAutomationCompareAndSetCount() {
            return automationCompareAndSetCount;
        }
    }
}
