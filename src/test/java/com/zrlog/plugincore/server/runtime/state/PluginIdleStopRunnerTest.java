package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.vo.PluginVO;
import org.junit.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginIdleStopRunnerTest {

    private static final String PLUGIN_ID = "plugin-a";
    private static final long NOW_MS = 20000L;
    private static final long IDLE_TIMEOUT_MS = 10000L;

    @Test
    public void shouldKeepIdlePluginWhenDemandWasClaimed() {
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        coordinator.claimDemand(PLUGIN_ID, 30000L);
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        PluginRuntimeStateStore stateStore = stateStore(idleState());

        runner(bootstrapService, coordinator).stopIdlePlugins(
                NOW_MS, runtimeSetting(), pluginCore(), stateStore);

        assertEquals(0, bootstrapService.stopCount);
    }

    @Test
    public void shouldRejectStaleIdleSnapshotWhenPluginBecameActive() {
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        PluginRuntimeState staleIdleState = idleState();
        PluginRuntimeState activeState = idleState();
        activeState.setActiveInvocationCount(1);
        PluginRuntimeStateStore stateStore = new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()) {
            @Override
            public List<PluginRuntimeState> list() {
                return Collections.singletonList(staleIdleState);
            }

            @Override
            public Optional<PluginRuntimeState> find(String pluginId) {
                return Optional.of(activeState);
            }
        };

        runner(bootstrapService, new PluginStartCoordinator()).stopIdlePlugins(
                NOW_MS, runtimeSetting(), pluginCore(), stateStore);

        assertEquals(0, bootstrapService.stopCount);
    }

    @Test
    public void shouldStopCurrentIdlePluginByPluginIdentity() {
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();

        runner(bootstrapService, new PluginStartCoordinator()).stopIdlePlugins(
                NOW_MS, runtimeSetting(), pluginCore(), stateStore(idleState()));

        assertEquals(1, bootstrapService.stopCount);
        assertEquals(PLUGIN_ID, bootstrapService.stoppedPluginId);
        assertEquals("reminder", bootstrapService.stoppedPluginShortName);
    }

    @Test
    public void shouldReclaimOneEligibleIdlePluginForCapacity() {
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();

        boolean reclaimed = runner(bootstrapService, new PluginStartCoordinator())
                .reclaimOneIdlePluginForCapacity(NOW_MS, runtimeSetting(), pluginCore(), "plugin-target",
                        stateStore(idleState()));

        assertTrue(reclaimed);
        assertEquals(1, bootstrapService.stopCount);
        assertEquals(PLUGIN_ID, bootstrapService.stoppedPluginId);
    }

    @Test
    public void shouldNotReportCapacityReclaimedWhenProcessStopFails() {
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        bootstrapService.stopResult = false;

        boolean reclaimed = runner(bootstrapService, new PluginStartCoordinator())
                .reclaimOneIdlePluginForCapacity(NOW_MS, runtimeSetting(), pluginCore(), "plugin-target",
                        stateStore(idleState()));

        assertFalse(reclaimed);
        assertEquals(1, bootstrapService.stopCount);
    }

    @Test
    public void shouldTryCandidatesUntilOneIdleProcessIsActuallyStopped() {
        String activePluginId = "plugin-b";
        String lostSlotPluginId = "plugin-c";
        String failedStopPluginId = "plugin-d";
        String stoppedPluginId = "plugin-e";
        String untouchedPluginId = "plugin-f";
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        bootstrapService.slotLostAfterFirstCheckPluginId = lostSlotPluginId;
        bootstrapService.stopResults.put(failedStopPluginId, false);
        PluginStartCoordinator coordinator = new PluginStartCoordinator();
        coordinator.claimDemand(PLUGIN_ID, 30000L);
        PluginRuntimeState activeState = idleState(activePluginId, NOW_MS - IDLE_TIMEOUT_MS - 4000L);
        activeState.setActiveInvocationCount(1);
        PluginRuntimeStateStore stateStore = new PluginRuntimeStateStore(new InMemoryRuntimeKvStore()) {
            @Override
            public Optional<PluginRuntimeState> find(String pluginId) {
                if (activePluginId.equals(pluginId)) {
                    return Optional.of(activeState);
                }
                return super.find(pluginId);
            }
        };
        stateStore.upsert(idleState(PLUGIN_ID, NOW_MS - IDLE_TIMEOUT_MS - 5000L));
        stateStore.upsert(idleState(activePluginId, NOW_MS - IDLE_TIMEOUT_MS - 4000L));
        stateStore.upsert(idleState(lostSlotPluginId, NOW_MS - IDLE_TIMEOUT_MS - 3000L));
        stateStore.upsert(idleState(failedStopPluginId, NOW_MS - IDLE_TIMEOUT_MS - 2000L));
        stateStore.upsert(idleState(stoppedPluginId, NOW_MS - IDLE_TIMEOUT_MS - 1000L));
        stateStore.upsert(idleState(untouchedPluginId, NOW_MS - IDLE_TIMEOUT_MS));

        boolean reclaimed = runner(bootstrapService, coordinator).reclaimOneIdlePluginForCapacity(
                NOW_MS, runtimeSetting(), pluginCore(PLUGIN_ID, activePluginId, lostSlotPluginId,
                        failedStopPluginId, stoppedPluginId, untouchedPluginId), "plugin-target", stateStore);

        assertTrue(reclaimed);
        assertEquals(Arrays.asList(failedStopPluginId, stoppedPluginId), bootstrapService.stoppedPluginIds);
        assertFalse(bootstrapService.stoppedPluginIds.contains(untouchedPluginId));
    }

    @Test
    public void shouldNotReclaimActiveOrRecentPluginForCapacity() {
        RecordingBootstrapService bootstrapService = new RecordingBootstrapService();
        PluginRuntimeState activeState = idleState();
        activeState.setActiveInvocationCount(1);

        assertFalse(runner(bootstrapService, new PluginStartCoordinator())
                .reclaimOneIdlePluginForCapacity(NOW_MS, runtimeSetting(), pluginCore(), "plugin-target",
                        stateStore(activeState)));

        PluginRuntimeState recentState = idleState();
        recentState.setLastActiveAt(NOW_MS - IDLE_TIMEOUT_MS + 1L);
        assertFalse(runner(bootstrapService, new PluginStartCoordinator())
                .reclaimOneIdlePluginForCapacity(NOW_MS, runtimeSetting(), pluginCore(), "plugin-target",
                        stateStore(recentState)));
        assertEquals(0, bootstrapService.stopCount);
    }

    private PluginIdleStopRunner runner(RecordingBootstrapService bootstrapService,
                                        PluginStartCoordinator coordinator) {
        return new PluginIdleStopRunner(bootstrapService, coordinator);
    }

    private PluginRuntimeStateStore stateStore(PluginRuntimeState state) {
        PluginRuntimeStateStore stateStore = new PluginRuntimeStateStore(new InMemoryRuntimeKvStore());
        stateStore.upsert(state);
        return stateStore;
    }

    private PluginRuntimeState idleState() {
        return idleState(PLUGIN_ID, NOW_MS - IDLE_TIMEOUT_MS);
    }

    private PluginRuntimeState idleState(String pluginId, long lastActiveAt) {
        PluginRuntimeState state = new PluginRuntimeState();
        state.setPluginId(pluginId);
        state.setPluginName("Reminder");
        state.setStatus("ready");
        state.setRuntimeMode("process");
        state.setActiveInvocationCount(0);
        state.setLastActiveAt(lastActiveAt);
        return state;
    }

    private PluginRuntimeSetting runtimeSetting() {
        PluginRuntimeSetting setting = new PluginRuntimeSetting();
        setting.setOnDemandEnabled(true);
        setting.setIdleStopEnabled(true);
        setting.setIdleTimeoutSeconds(IDLE_TIMEOUT_MS / 1000L);
        return setting;
    }

    private PluginCore pluginCore() {
        return pluginCore(PLUGIN_ID);
    }

    private PluginCore pluginCore(String... pluginIds) {
        PluginCore pluginCore = new PluginCore();
        for (String pluginId : pluginIds) {
            Plugin plugin = new Plugin();
            plugin.setId(pluginId);
            plugin.setName("Reminder " + pluginId);
            plugin.setShortName(PLUGIN_ID.equals(pluginId) ? "reminder" : "reminder-" + pluginId);
            PluginVO pluginVO = new PluginVO();
            pluginVO.setPlugin(plugin);
            pluginCore.getPluginInfoMap().put(plugin.getShortName(), pluginVO);
        }
        return pluginCore;
    }

    private static class RecordingBootstrapService extends PluginBootstrapService {

        private int stopCount;
        private String stoppedPluginId;
        private String stoppedPluginShortName;
        private boolean stopResult = true;
        private final List<String> stoppedPluginIds = new ArrayList<>();
        private final Map<String, Boolean> stopResults = new HashMap<>();
        private final Map<String, Integer> slotCheckCounts = new HashMap<>();
        private String slotLostAfterFirstCheckPluginId;

        RecordingBootstrapService() {
            super(Collections.emptyMap(), null, null, null);
        }

        @Override
        public boolean stopPlugin(String pluginId, String pluginShortName) {
            stopCount++;
            stoppedPluginId = pluginId;
            stoppedPluginShortName = pluginShortName;
            stoppedPluginIds.add(pluginId);
            return stopResults.getOrDefault(pluginId, stopResult);
        }

        @Override
        public boolean stopPlugin(String pluginShortName) {
            throw new AssertionError("idle stop must include the plugin id");
        }

        @Override
        public boolean hasManagedProcessSlot(Plugin plugin) {
            int checkCount = slotCheckCounts.merge(plugin.getId(), 1, Integer::sum);
            return !plugin.getId().equals(slotLostAfterFirstCheckPluginId) || checkCount == 1;
        }
    }
}
