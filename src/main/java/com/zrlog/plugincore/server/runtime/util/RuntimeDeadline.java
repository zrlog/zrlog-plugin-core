package com.zrlog.plugincore.server.runtime.util;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One monotonic deadline shared by synchronous stages of a request. */
public final class RuntimeDeadline {
    private static final long MAX_BUDGET_MILLIS = TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE / 2);
    private static final LongSupplier SYSTEM_CLOCK = System::nanoTime;
    private static final RuntimeDeadline UNLIMITED = new RuntimeDeadline(Long.MAX_VALUE, false, SYSTEM_CLOCK);
    private final long deadlineNanos;
    private final boolean bounded;
    private final LongSupplier nanoClock;

    private RuntimeDeadline(long deadlineNanos, boolean bounded, LongSupplier nanoClock) {
        this.deadlineNanos = deadlineNanos;
        this.bounded = bounded;
        this.nanoClock = nanoClock;
    }

    public static RuntimeDeadline unlimited() { return UNLIMITED; }

    public static RuntimeDeadline afterMillis(long millis) {
        return afterMillis(millis, SYSTEM_CLOCK);
    }

    static RuntimeDeadline afterMillis(long millis, LongSupplier nanoClock) {
        if (millis < 0 || millis > MAX_BUDGET_MILLIS) throw new IllegalArgumentException("Invalid execution budget");
        return new RuntimeDeadline(nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(millis), true, nanoClock);
    }

    public static RuntimeDeadline atEpochMillis(Long deadline) {
        return deadline == null ? unlimited() : afterMillis(Math.min(MAX_BUDGET_MILLIS, Math.max(0, deadline - System.currentTimeMillis())));
    }

    public boolean isBounded() { return bounded; }

    public long remainingMillis() {
        if (!bounded) return Long.MAX_VALUE;
        long remaining = deadlineNanos - nanoClock.getAsLong();
        return remaining <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    public long waitMillis(long maximum) { return Math.min(Math.max(0, maximum), remainingMillis()); }

    public RuntimeDeadline capMillis(long maximum) {
        if (maximum < 0 || maximum > MAX_BUDGET_MILLIS) throw new IllegalArgumentException("Invalid execution budget");
        long limit = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(maximum);
        return new RuntimeDeadline(bounded ? Math.min(deadlineNanos, limit) : limit, true, nanoClock);
    }

    public RuntimeDeadline reserveMillis(long reserve) {
        if (reserve < 0) throw new IllegalArgumentException("Invalid reserve");
        if (bounded && reserve >= remainingMillis()) return afterMillis(0, nanoClock);
        return bounded ? new RuntimeDeadline(deadlineNanos - TimeUnit.MILLISECONDS.toNanos(reserve), true, nanoClock) : this;
    }

    public void check() {
        if (Thread.currentThread().isInterrupted() || remainingMillis() == 0) throw new Expired();
    }

    public static final class Expired extends RuntimeException {
        public Expired() { super("Plugin execution time budget exhausted"); }
    }
}
