package com.zrlog.plugincore.server.runtime.state;

public class PluginRuntimeSetting {

    public static final long MIN_IDLE_TIMEOUT_SECONDS = 10L;
    public static final long MAX_IDLE_TIMEOUT_SECONDS = 86400L;
    public static final long MIN_IDLE_SCAN_INTERVAL_SECONDS = 60L;
    public static final long MAX_IDLE_SCAN_INTERVAL_SECONDS = 3600L;
    public static final long DEFAULT_IDLE_SCAN_INTERVAL_SECONDS = 300L;
    public static final long MIN_MAX_RUNNING_PLUGINS = 1L;
    public static final long MAX_MAX_RUNNING_PLUGINS = 32L;
    public static final long DEFAULT_MAX_RUNNING_PLUGINS = 4L;
    public static final long MIN_MAX_CONCURRENT_STARTS = 1L;
    public static final long MAX_MAX_CONCURRENT_STARTS = 8L;
    public static final long DEFAULT_MAX_CONCURRENT_STARTS = 2L;
    public static final long MIN_START_FAILURE_BACKOFF_SECONDS = 1L;
    public static final long MAX_START_FAILURE_BACKOFF_SECONDS = 3600L;
    public static final long DEFAULT_START_FAILURE_BACKOFF_SECONDS = 30L;
    private static final long[] EXACT_IDLE_SCAN_INTERVAL_SECONDS = {
            60L, 120L, 180L, 240L, 300L, 360L, 600L, 720L, 900L, 1200L, 1800L, 3600L
    };

    private Boolean onDemandEnabled = true;
    private Boolean autoDownloadMissingPluginFileEnabled;
    private Boolean idleStopEnabled = true;
    private Long idleTimeoutSeconds = 300L;
    private Long idleScanIntervalSeconds = DEFAULT_IDLE_SCAN_INTERVAL_SECONDS;
    private Long maxRunningPlugins = DEFAULT_MAX_RUNNING_PLUGINS;
    private Long maxConcurrentStarts = DEFAULT_MAX_CONCURRENT_STARTS;
    private Long startFailureBackoffSeconds = DEFAULT_START_FAILURE_BACKOFF_SECONDS;

    public Boolean getOnDemandEnabled() {
        return onDemandEnabled == null ? Boolean.TRUE : onDemandEnabled;
    }

    public void setOnDemandEnabled(Boolean onDemandEnabled) {
        this.onDemandEnabled = onDemandEnabled;
    }

    public Boolean getAutoDownloadMissingPluginFileEnabled() {
        return autoDownloadMissingPluginFileEnabled == null ? Boolean.TRUE : autoDownloadMissingPluginFileEnabled;
    }

    public Boolean getConfiguredAutoDownloadMissingPluginFileEnabled() {
        return autoDownloadMissingPluginFileEnabled;
    }

    public void setAutoDownloadMissingPluginFileEnabled(Boolean autoDownloadMissingPluginFileEnabled) {
        this.autoDownloadMissingPluginFileEnabled = autoDownloadMissingPluginFileEnabled;
    }

    public Boolean getIdleStopEnabled() {
        return idleStopEnabled == null ? Boolean.TRUE : idleStopEnabled;
    }

    public void setIdleStopEnabled(Boolean idleStopEnabled) {
        this.idleStopEnabled = idleStopEnabled;
    }

    public Long getIdleTimeoutSeconds() {
        return bounded(idleTimeoutSeconds, 300L, MIN_IDLE_TIMEOUT_SECONDS, MAX_IDLE_TIMEOUT_SECONDS);
    }

    public void setIdleTimeoutSeconds(Long idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public Long getIdleScanIntervalSeconds() {
        long seconds = bounded(idleScanIntervalSeconds, DEFAULT_IDLE_SCAN_INTERVAL_SECONDS,
                MIN_IDLE_SCAN_INTERVAL_SECONDS, MAX_IDLE_SCAN_INTERVAL_SECONDS);
        long remainder = seconds % 60L;
        long roundedSeconds = remainder == 0L
                ? seconds
                : Math.min(MAX_IDLE_SCAN_INTERVAL_SECONDS, seconds + 60L - remainder);
        for (long exactIntervalSeconds : EXACT_IDLE_SCAN_INTERVAL_SECONDS) {
            if (exactIntervalSeconds >= roundedSeconds) {
                return exactIntervalSeconds;
            }
        }
        return MAX_IDLE_SCAN_INTERVAL_SECONDS;
    }

    public void setIdleScanIntervalSeconds(Long idleScanIntervalSeconds) {
        this.idleScanIntervalSeconds = idleScanIntervalSeconds;
    }

    public Long getMaxRunningPlugins() {
        return bounded(maxRunningPlugins, DEFAULT_MAX_RUNNING_PLUGINS,
                MIN_MAX_RUNNING_PLUGINS, MAX_MAX_RUNNING_PLUGINS);
    }

    public void setMaxRunningPlugins(Long maxRunningPlugins) {
        this.maxRunningPlugins = maxRunningPlugins;
    }

    public Long getMaxConcurrentStarts() {
        return bounded(maxConcurrentStarts, DEFAULT_MAX_CONCURRENT_STARTS,
                MIN_MAX_CONCURRENT_STARTS, MAX_MAX_CONCURRENT_STARTS);
    }

    public void setMaxConcurrentStarts(Long maxConcurrentStarts) {
        this.maxConcurrentStarts = maxConcurrentStarts;
    }

    public Long getStartFailureBackoffSeconds() {
        return bounded(startFailureBackoffSeconds, DEFAULT_START_FAILURE_BACKOFF_SECONDS,
                MIN_START_FAILURE_BACKOFF_SECONDS, MAX_START_FAILURE_BACKOFF_SECONDS);
    }

    public void setStartFailureBackoffSeconds(Long startFailureBackoffSeconds) {
        this.startFailureBackoffSeconds = startFailureBackoffSeconds;
    }

    public void normalize() {
        onDemandEnabled = getOnDemandEnabled();
        autoDownloadMissingPluginFileEnabled = getAutoDownloadMissingPluginFileEnabled();
        idleStopEnabled = getIdleStopEnabled();
        idleTimeoutSeconds = getIdleTimeoutSeconds();
        idleScanIntervalSeconds = getIdleScanIntervalSeconds();
        maxRunningPlugins = getMaxRunningPlugins();
        maxConcurrentStarts = getMaxConcurrentStarts();
        startFailureBackoffSeconds = getStartFailureBackoffSeconds();
    }

    private static Long bounded(Long value, long fallback, long min, long max) {
        long resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }
}
