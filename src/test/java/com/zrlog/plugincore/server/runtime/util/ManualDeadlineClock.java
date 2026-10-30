package com.zrlog.plugincore.server.runtime.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Advances deadline tests without depending on operating-system wakeup timing. */
public final class ManualDeadlineClock {
    private final AtomicLong nanos = new AtomicLong();

    public RuntimeDeadline afterMillis(long millis) {
        return RuntimeDeadline.afterMillis(millis, nanos::get);
    }

    public void advanceToMillis(long millis) {
        nanos.accumulateAndGet(TimeUnit.MILLISECONDS.toNanos(millis), Math::max);
    }
}
