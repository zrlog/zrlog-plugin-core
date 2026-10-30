package com.zrlog.plugincore.server.runtime.event;

/** A public inline view and the receipt that its handler has returned. */
public final class HostViewResult {
    public String triggerId;
    public String requestId;
    public boolean completed;
    public boolean outcomeUncertain;
    public String content;
    public String errorMessage;
}
