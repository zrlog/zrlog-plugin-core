package com.zrlog.plugincore.server.vo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class PluginCoreSettingTest {

    @Test
    public void shouldCreateDefaultSchedulerProvider() {
        PluginCoreSetting setting = new PluginCoreSetting();

        assertEquals(1, setting.getScheduler().getProviders().size());
        assertEquals("default", setting.getScheduler().getProviders().get(0).getId());
        assertEquals(Boolean.FALSE, setting.getScheduler().getProviders().get(0).getEnabled());
        assertNotNull(setting.getScheduler().getProviders().get(0).getSecret());
        assertFalse(setting.getScheduler().getProviders().get(0).getSecret().isEmpty());
    }

    @Test
    public void shouldCreateDefaultNotificationSetting() {
        PluginCoreSetting setting = new PluginCoreSetting();

        assertNotNull(setting.getNotification());
        assertNotNull(setting.getNotification().getDefaultProviders());
    }

    @Test
    public void shouldCreateDefaultRuntimeSetting() {
        PluginCoreSetting setting = new PluginCoreSetting();

        assertEquals(Boolean.TRUE, setting.getRuntime().getOnDemandEnabled());
        assertEquals(Boolean.TRUE, setting.getRuntime().getAutoDownloadMissingPluginFileEnabled());
        assertEquals(Boolean.TRUE, setting.getRuntime().getIdleStopEnabled());
        assertEquals(Long.valueOf(300L), setting.getRuntime().getIdleTimeoutSeconds());
        assertEquals(Long.valueOf(300L), setting.getRuntime().getIdleScanIntervalSeconds());
        assertEquals(Long.valueOf(4L), setting.getRuntime().getMaxRunningPlugins());
        assertEquals(Long.valueOf(2L), setting.getRuntime().getMaxConcurrentStarts());
        assertEquals(Long.valueOf(30L), setting.getRuntime().getStartFailureBackoffSeconds());
    }

    @Test
    public void shouldBoundRuntimeCapacitySettings() {
        PluginCoreSetting setting = new PluginCoreSetting();

        setting.getRuntime().setIdleTimeoutSeconds(Long.MAX_VALUE);
        setting.getRuntime().setIdleScanIntervalSeconds(5L);
        setting.getRuntime().setMaxRunningPlugins(Long.MAX_VALUE);
        setting.getRuntime().setMaxConcurrentStarts(0L);
        setting.getRuntime().setStartFailureBackoffSeconds(0L);

        assertEquals(Long.valueOf(86400L), setting.getRuntime().getIdleTimeoutSeconds());
        assertEquals(Long.valueOf(60L), setting.getRuntime().getIdleScanIntervalSeconds());
        assertEquals(Long.valueOf(32L), setting.getRuntime().getMaxRunningPlugins());
        assertEquals(Long.valueOf(1L), setting.getRuntime().getMaxConcurrentStarts());
        assertEquals(Long.valueOf(1L), setting.getRuntime().getStartFailureBackoffSeconds());

        setting.getRuntime().setIdleScanIntervalSeconds(61L);
        assertEquals(Long.valueOf(120L), setting.getRuntime().getIdleScanIntervalSeconds());

        setting.getRuntime().setIdleScanIntervalSeconds(421L);
        assertEquals(Long.valueOf(600L), setting.getRuntime().getIdleScanIntervalSeconds());
    }

    @Test
    public void shouldKeepLegacyAutoDownloadSettingCompatible() {
        PluginCoreSetting setting = new PluginCoreSetting();

        setting.setDisableAutoDownloadLostFile(true);

        assertFalse(setting.isAutoDownloadMissingPluginFileEnabled());
        assertEquals(Boolean.FALSE, setting.getRuntime().getAutoDownloadMissingPluginFileEnabled());
    }

    @Test
    public void shouldPreferRuntimeAutoDownloadSetting() {
        PluginCoreSetting setting = new PluginCoreSetting();
        setting.getRuntime().setAutoDownloadMissingPluginFileEnabled(false);

        assertFalse(setting.isAutoDownloadMissingPluginFileEnabled());
        assertEquals(Boolean.TRUE, setting.isDisableAutoDownloadLostFile());
    }
}
