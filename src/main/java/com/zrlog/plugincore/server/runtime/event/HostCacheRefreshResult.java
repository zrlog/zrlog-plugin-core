package com.zrlog.plugincore.server.runtime.event;

/** Completion receipt for one authenticated host refresh; never includes plugin payloads or credentials. */
public final class HostCacheRefreshResult {
    public String triggerId;
    public String cacheVersion;
    public boolean completed;
    public boolean outcomeUncertain;
    public int successCount;
    public int failedCount;
    public int unsupportedCount;
}
