package com.zrlog.plugincore.server.runtime.state;

import java.util.Optional;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;

public interface PluginRuntimeStarter {

    boolean isStarted(String pluginId);

    default boolean isReady(String pluginId) {
        return isStarted(pluginId);
    }

    Optional<PluginIdentity> findPlugin(String pluginId);

    default String runtimeMode(PluginIdentity identity) {
        return "process";
    }

    default boolean managesRuntimeState() {
        return false;
    }

    default void cleanupStartFailure(PluginIdentity identity) {
    }

    default int maxConcurrentStarts() {
        return Integer.MAX_VALUE;
    }

    default long startFailureBackoffMs() {
        return 0L;
    }

    default boolean reclaimIdleCapacity(PluginIdentity identity) {
        return false;
    }

    default boolean isStartViable(PluginIdentity identity) {
        return true;
    }

    default void start(PluginIdentity identity, RuntimeDeadline deadline) {
        deadline.check();
        start(identity);
        deadline.check();
    }

    default void cleanupStartFailure(PluginIdentity identity, RuntimeDeadline deadline) { cleanupStartFailure(identity); }

    default boolean reclaimIdleCapacity(PluginIdentity identity, RuntimeDeadline deadline) {
        deadline.check();
        return reclaimIdleCapacity(identity);
    }

    void start(PluginIdentity identity);
}
