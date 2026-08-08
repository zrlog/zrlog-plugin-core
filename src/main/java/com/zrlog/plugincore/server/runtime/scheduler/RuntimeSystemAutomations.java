package com.zrlog.plugincore.server.runtime.scheduler;

import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.CronParseException;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RuntimeSystemAutomations {

    static final String RUNTIME_MAINTENANCE_ID = "system:plugin-runtime-maintenance";
    static final String SYSTEM_PLUGIN_ID = "__system__";
    static final String RUNTIME_MAINTENANCE_KEY = "plugin.runtime.maintenance";

    private static final String RUNTIME_MAINTENANCE_NAME = "运行态维护";
    private static final String IDLE_SCAN_INTERVAL_SECONDS = "idleScanIntervalSeconds";

    private RuntimeSystemAutomations() {
    }

    static boolean isRuntimeMaintenance(PluginAutomation automation) {
        return automation != null
                && isRuntimeMaintenanceIdentity(automation.getId(), automation.getPluginId(), automation.getCapabilityKey());
    }

    public static boolean isRuntimeMaintenanceIdentity(String id, String pluginId, String capabilityKey) {
        return Objects.equals(RUNTIME_MAINTENANCE_ID, id)
                || (Objects.equals(SYSTEM_PLUGIN_ID, pluginId)
                && Objects.equals(RUNTIME_MAINTENANCE_KEY, capabilityKey));
    }

    public static String runtimeMaintenanceTargetLabel() {
        return "系统任务 / " + RUNTIME_MAINTENANCE_NAME;
    }

    public static boolean isSystemPluginId(String pluginId) {
        return Objects.equals(SYSTEM_PLUGIN_ID, pluginId);
    }

    public static String systemPluginName() {
        return "系统";
    }

    static boolean ensureRuntimeMaintenance(List<PluginAutomation> automations,
                                            PluginRuntimeSetting setting,
                                            BasicCronParser cronParser,
                                            ZonedDateTime now) {
        PluginAutomation existing = null;
        for (PluginAutomation automation : automations) {
            if (isRuntimeMaintenance(automation)) {
                existing = automation;
                break;
            }
        }
        if (existing == null) {
            PluginAutomation automation = new PluginAutomation();
            automation.setId(RUNTIME_MAINTENANCE_ID);
            automation.setName(RUNTIME_MAINTENANCE_NAME);
            automation.setPluginId(SYSTEM_PLUGIN_ID);
            automation.setCapabilityKey(RUNTIME_MAINTENANCE_KEY);
            PluginRuntimeSetting runtimeSetting = normalizedRuntimeSetting(setting);
            automation.setCron(runtimeMaintenanceCron(runtimeSetting.getIdleScanIntervalSeconds()));
            automation.setEnabled(Boolean.TRUE);
            automation.setPayload(runtimePayload(runtimeSetting));
            prepareSystemAutomation(automation, cronParser, now);
            automations.add(automation);
            return true;
        }
        return normalizeRuntimeMaintenance(existing, setting, cronParser, now);
    }

    static PluginAutomation prepareRuntimeMaintenanceSave(PluginAutomation input,
                                                          BasicCronParser cronParser,
                                                          ZonedDateTime now) {
        if (input == null) {
            throw new CronParseException("Automation is empty");
        }
        PluginAutomation automation = new PluginAutomation();
        automation.setId(RUNTIME_MAINTENANCE_ID);
        automation.setName(RUNTIME_MAINTENANCE_NAME);
        automation.setPluginId(SYSTEM_PLUGIN_ID);
        automation.setCapabilityKey(RUNTIME_MAINTENANCE_KEY);
        Map<String, Object> inputPayload = input.getPayload();
        PluginRuntimeSetting runtimeSetting = runtimeSettingFromPayload(inputPayload);
        Map<String, Object> normalizedPayload = runtimePayload(runtimeSetting);
        if (inputPayload != null && inputPayload.containsKey(IDLE_SCAN_INTERVAL_SECONDS)) {
            automation.setCron(runtimeMaintenanceCron(runtimeSetting.getIdleScanIntervalSeconds()));
        } else {
            Long legacyIntervalSeconds = legacyRuntimeMaintenanceIntervalSeconds(input.getCron());
            if (legacyIntervalSeconds != null) {
                runtimeSetting.setIdleScanIntervalSeconds(legacyIntervalSeconds);
                normalizedPayload = runtimePayload(runtimeSetting);
                automation.setCron(input.getCron());
            } else if (!isBlank(input.getCron())) {
                normalizedPayload.remove(IDLE_SCAN_INTERVAL_SECONDS);
                automation.setCron(input.getCron());
            } else {
                automation.setCron(runtimeMaintenanceCron(runtimeSetting.getIdleScanIntervalSeconds()));
            }
        }
        automation.setEnabled(Boolean.TRUE);
        automation.setPayload(normalizedPayload);
        prepareSystemAutomation(automation, cronParser, now);
        return automation;
    }

    static PluginRuntimeSetting runtimeSettingFromPayload(Map<String, Object> payload) {
        PluginRuntimeSetting setting = new PluginRuntimeSetting();
        overlayRuntimeSetting(setting, payload);
        return setting;
    }

    private static void overlayRuntimeSetting(PluginRuntimeSetting setting, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        if (payload.containsKey("onDemandEnabled")) {
            setting.setOnDemandEnabled(booleanValue(payload.get("onDemandEnabled"), setting.getOnDemandEnabled()));
        }
        if (payload.containsKey("autoDownloadMissingPluginFileEnabled")) {
            setting.setAutoDownloadMissingPluginFileEnabled(booleanValue(
                    payload.get("autoDownloadMissingPluginFileEnabled"),
                    setting.getAutoDownloadMissingPluginFileEnabled()));
        }
        if (payload.containsKey("idleStopEnabled")) {
            setting.setIdleStopEnabled(booleanValue(payload.get("idleStopEnabled"), setting.getIdleStopEnabled()));
        }
        if (payload.containsKey("idleTimeoutSeconds")) {
            setting.setIdleTimeoutSeconds(longValue(payload.get("idleTimeoutSeconds"), setting.getIdleTimeoutSeconds(),
                    PluginRuntimeSetting.MIN_IDLE_TIMEOUT_SECONDS, PluginRuntimeSetting.MAX_IDLE_TIMEOUT_SECONDS));
        }
        if (payload.containsKey(IDLE_SCAN_INTERVAL_SECONDS)) {
            setting.setIdleScanIntervalSeconds(longValue(payload.get(IDLE_SCAN_INTERVAL_SECONDS),
                    setting.getIdleScanIntervalSeconds(), PluginRuntimeSetting.MIN_IDLE_SCAN_INTERVAL_SECONDS,
                    PluginRuntimeSetting.MAX_IDLE_SCAN_INTERVAL_SECONDS));
        }
        if (payload.containsKey("maxRunningPlugins")) {
            setting.setMaxRunningPlugins(longValue(payload.get("maxRunningPlugins"), setting.getMaxRunningPlugins(),
                    PluginRuntimeSetting.MIN_MAX_RUNNING_PLUGINS, PluginRuntimeSetting.MAX_MAX_RUNNING_PLUGINS));
        }
        if (payload.containsKey("maxConcurrentStarts")) {
            setting.setMaxConcurrentStarts(longValue(payload.get("maxConcurrentStarts"),
                    setting.getMaxConcurrentStarts(), PluginRuntimeSetting.MIN_MAX_CONCURRENT_STARTS,
                    PluginRuntimeSetting.MAX_MAX_CONCURRENT_STARTS));
        }
        if (payload.containsKey("startFailureBackoffSeconds")) {
            setting.setStartFailureBackoffSeconds(longValue(payload.get("startFailureBackoffSeconds"),
                    setting.getStartFailureBackoffSeconds(), PluginRuntimeSetting.MIN_START_FAILURE_BACKOFF_SECONDS,
                    PluginRuntimeSetting.MAX_START_FAILURE_BACKOFF_SECONDS));
        }
        normalizeLoadStrategy(setting);
    }

    static Map<String, Object> runtimePayload(PluginRuntimeSetting setting) {
        PluginRuntimeSetting runtimeSetting = normalizedRuntimeSetting(setting);
        Map<String, Object> payload = new HashMap<>();
        payload.put("onDemandEnabled", runtimeSetting.getOnDemandEnabled());
        payload.put("autoDownloadMissingPluginFileEnabled", runtimeSetting.getAutoDownloadMissingPluginFileEnabled());
        payload.put("idleStopEnabled", runtimeSetting.getIdleStopEnabled());
        payload.put("idleTimeoutSeconds", runtimeSetting.getIdleTimeoutSeconds());
        payload.put(IDLE_SCAN_INTERVAL_SECONDS, runtimeSetting.getIdleScanIntervalSeconds());
        payload.put("maxRunningPlugins", runtimeSetting.getMaxRunningPlugins());
        payload.put("maxConcurrentStarts", runtimeSetting.getMaxConcurrentStarts());
        payload.put("startFailureBackoffSeconds", runtimeSetting.getStartFailureBackoffSeconds());
        return payload;
    }

    private static boolean normalizeRuntimeMaintenance(PluginAutomation automation,
                                                       PluginRuntimeSetting setting,
                                                       BasicCronParser cronParser,
                                                       ZonedDateTime now) {
        boolean changed = false;
        ZoneId zoneId = ZoneId.systemDefault();
        if (!Objects.equals(RUNTIME_MAINTENANCE_ID, automation.getId())) {
            automation.setId(RUNTIME_MAINTENANCE_ID);
            changed = true;
        }
        if (isBlank(automation.getName())) {
            automation.setName(RUNTIME_MAINTENANCE_NAME);
            changed = true;
        }
        if (!Objects.equals(SYSTEM_PLUGIN_ID, automation.getPluginId())) {
            automation.setPluginId(SYSTEM_PLUGIN_ID);
            changed = true;
        }
        if (!Objects.equals(RUNTIME_MAINTENANCE_KEY, automation.getCapabilityKey())) {
            automation.setCapabilityKey(RUNTIME_MAINTENANCE_KEY);
            changed = true;
        }
        PluginRuntimeSetting normalizedSetting = normalizedRuntimeSetting(setting);
        Map<String, Object> currentPayload = automation.getPayload();
        overlayRuntimeSetting(normalizedSetting, currentPayload);
        boolean explicitInterval = currentPayload != null && currentPayload.containsKey(IDLE_SCAN_INTERVAL_SECONDS);
        String normalizedCron = automation.getCron();
        Map<String, Object> normalizedPayload;
        if (explicitInterval) {
            normalizedPayload = runtimePayload(normalizedSetting);
            normalizedCron = runtimeMaintenanceCron(normalizedSetting.getIdleScanIntervalSeconds());
        } else {
            Long legacyIntervalSeconds = legacyRuntimeMaintenanceIntervalSeconds(normalizedCron);
            if (legacyIntervalSeconds != null) {
                normalizedSetting.setIdleScanIntervalSeconds(legacyIntervalSeconds);
                normalizedPayload = runtimePayload(normalizedSetting);
            } else if (isBlank(normalizedCron)) {
                normalizedPayload = runtimePayload(normalizedSetting);
                normalizedCron = runtimeMaintenanceCron(normalizedSetting.getIdleScanIntervalSeconds());
            } else {
                normalizedPayload = runtimePayload(normalizedSetting);
                normalizedPayload.remove(IDLE_SCAN_INTERVAL_SECONDS);
            }
        }
        boolean cronChanged = !Objects.equals(normalizedCron, automation.getCron());
        if (cronChanged) {
            automation.setCron(normalizedCron);
            changed = true;
        }
        if (!Objects.equals(normalizedPayload, automation.getPayload())) {
            automation.setPayload(normalizedPayload);
            changed = true;
        }
        if (!Objects.equals("cron", automation.getTriggerType())) {
            automation.setTriggerType("cron");
            changed = true;
        }
        if (!Objects.equals(zoneId.getId(), automation.getTimezone())) {
            automation.setTimezone(zoneId.getId());
            changed = true;
        }
        if (!Boolean.TRUE.equals(automation.getEnabled())) {
            automation.setEnabled(Boolean.TRUE);
            changed = true;
        }
        if (!Boolean.TRUE.equals(automation.getSystem())) {
            automation.setSystem(Boolean.TRUE);
            changed = true;
        }
        if (!Boolean.FALSE.equals(automation.getDeletable())) {
            automation.setDeletable(Boolean.FALSE);
            changed = true;
        }
        if (cronChanged || automation.getNextRunAt() == null) {
            automation.setNextRunAt(SchedulerTimes.nextRunAtMillis(cronParser, automation.getCron(), zoneId, now));
            changed = true;
        }
        return changed;
    }

    private static void prepareSystemAutomation(PluginAutomation automation,
                                                BasicCronParser cronParser,
                                                ZonedDateTime now) {
        if (isBlank(automation.getCron())) {
            throw new CronParseException("Cron expression is empty");
        }
        ZoneId zoneId = ZoneId.systemDefault();
        automation.setTriggerType("cron");
        automation.setTimezone(zoneId.getId());
        automation.setEnabled(Boolean.TRUE);
        automation.setSystem(Boolean.TRUE);
        automation.setDeletable(Boolean.FALSE);
        automation.setNextRunAt(SchedulerTimes.nextRunAtMillis(cronParser, automation.getCron(), zoneId, now));
    }

    private static Boolean booleanValue(Object value, Boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static Long longValue(Object value, Long fallback, long min, long max) {
        if (value == null) {
            return fallback;
        }
        long number;
        if (value instanceof Number) {
            number = ((Number) value).longValue();
        } else {
            number = Long.parseLong(value.toString());
        }
        return Math.max(min, Math.min(max, number));
    }

    static String runtimeMaintenanceCron(Long intervalSeconds) {
        PluginRuntimeSetting setting = new PluginRuntimeSetting();
        setting.setIdleScanIntervalSeconds(intervalSeconds);
        long intervalMinutes = setting.getIdleScanIntervalSeconds() / 60L;
        if (intervalMinutes == 1L) {
            return "* * * * *";
        }
        if (intervalMinutes == 60L) {
            return "0 * * * *";
        }
        return "*/" + intervalMinutes + " * * * *";
    }

    static Long legacyRuntimeMaintenanceIntervalSeconds(String cron) {
        if (isBlank(cron)) {
            return null;
        }
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5 || !"*".equals(fields[1]) || !"*".equals(fields[2])
                || !"*".equals(fields[3]) || !"*".equals(fields[4])) {
            return null;
        }
        if ("*".equals(fields[0])) {
            return 60L;
        }
        if ("0".equals(fields[0])) {
            return 3600L;
        }
        if (!fields[0].startsWith("*/")) {
            return null;
        }
        try {
            long minutes = Long.parseLong(fields[0].substring(2));
            if (!isExactRuntimeMaintenanceIntervalMinutes(minutes)) {
                return null;
            }
            return minutes * 60L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isExactRuntimeMaintenanceIntervalMinutes(long minutes) {
        return minutes >= 1L && minutes <= 60L && 60L % minutes == 0L;
    }

    private static void normalizeLoadStrategy(PluginRuntimeSetting setting) {
        if (!setting.getOnDemandEnabled()) {
            setting.setIdleStopEnabled(Boolean.FALSE);
        }
    }

    private static PluginRuntimeSetting normalizedRuntimeSetting(PluginRuntimeSetting setting) {
        PluginRuntimeSetting normalized = new PluginRuntimeSetting();
        if (setting != null) {
            normalized.setOnDemandEnabled(setting.getOnDemandEnabled());
            normalized.setAutoDownloadMissingPluginFileEnabled(setting.getAutoDownloadMissingPluginFileEnabled());
            normalized.setIdleStopEnabled(setting.getIdleStopEnabled());
            normalized.setIdleTimeoutSeconds(setting.getIdleTimeoutSeconds());
            normalized.setIdleScanIntervalSeconds(setting.getIdleScanIntervalSeconds());
            normalized.setMaxRunningPlugins(setting.getMaxRunningPlugins());
            normalized.setMaxConcurrentStarts(setting.getMaxConcurrentStarts());
            normalized.setStartFailureBackoffSeconds(setting.getStartFailureBackoffSeconds());
        }
        normalizeLoadStrategy(normalized);
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
