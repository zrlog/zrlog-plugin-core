package com.zrlog.plugincore.server.runtime.capability;

import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.InMemoryRuntimeKvStore;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.state.PluginIdentity;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStarter;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeState;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TrackingCapabilityInvokerTest {

    @Test
    public void shouldLogSuccessAndMarkRuntimeReady() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        TrackingCapabilityInvoker invoker = invoker(kvStore, successDelegate(), new FakeStarter(true));
        InvokeContext context = new InvokeContext();
        context.setSource("scheduler");

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "reminder.scanDueTasks", null, context);

        assertTrue(result.isSuccess());
        assertEquals(1, new InvocationLogStore(kvStore).list().size());
        assertEquals("success", new InvocationLogStore(kvStore).list().get(0).getStatus());
        assertTrue(new InvocationLogStore(kvStore).list().get(0).getStartedAt() > 0);
        assertTrue(new InvocationLogStore(kvStore).list().get(0).getFinishedAt() > 0);
        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals("ready", state.getStatus());
        assertEquals(Integer.valueOf(0), state.getActiveInvocationCount());
    }

    @Test
    public void shouldFinishTrackingWhenDelegateThrowsRuntimeException() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        TrackingCapabilityInvoker invoker = invoker(kvStore, (pluginId, capabilityKey, payload, context) -> {
            throw new IllegalStateException("delegate failed");
        }, new FakeStarter(true));

        CapabilityInvokeResult result = invoker.invoke(
                "plugin-a", "reminder.scanDueTasks", null, context("scheduler"));

        assertFalse(result.isSuccess());
        assertEquals("delegate failed", result.getErrorMessage());
        assertInvocationFinished(kvStore, "delegate failed");
    }

    @Test
    public void shouldFinishTrackingAndRethrowDelegateError() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        AssertionError fatal = new AssertionError("fatal delegate failure");
        TrackingCapabilityInvoker invoker = invoker(kvStore, (pluginId, capabilityKey, payload, context) -> {
            throw fatal;
        }, new FakeStarter(true));

        try {
            invoker.invoke("plugin-a", "reminder.scanDueTasks", null, context("scheduler"));
            fail("delegate error should be rethrown");
        } catch (AssertionError actual) {
            assertSame(fatal, actual);
        }

        assertInvocationFinished(kvStore, "fatal delegate failure");
    }

    @Test
    public void shouldFinishTrackingWhenDelegateReturnsNull() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        TrackingCapabilityInvoker invoker = invoker(kvStore,
                (pluginId, capabilityKey, payload, context) -> null, new FakeStarter(true));

        CapabilityInvokeResult result = invoker.invoke(
                "plugin-a", "reminder.scanDueTasks", null, context("scheduler"));

        assertFalse(result.isSuccess());
        assertEquals("Capability invoker returned no result", result.getErrorMessage());
        assertInvocationFinished(kvStore, "Capability invoker returned no result");
    }

    @Test
    public void shouldReturnErrorWhenPluginCannotStart() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        TrackingCapabilityInvoker invoker = invoker(kvStore, successDelegate(), new FakeStarter(false));

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "reminder.scanDueTasks", null, new InvokeContext());

        assertFalse(result.isSuccess());
        assertEquals("Plugin start failed", result.getErrorMessage());
        assertEquals("error", new InvocationLogStore(kvStore).list().get(0).getStatus());
    }

    @Test
    public void shouldRejectMissingCapabilityAndWriteLog() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        TrackingCapabilityInvoker invoker = invoker(kvStore, neverDelegate(), new FakeStarter(true), capabilityStore);

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "reminder.scanDueTasks", null, context("scheduler"));

        assertFalse(result.isSuccess());
        assertEquals("Capability not found", result.getErrorMessage());
        assertEquals("error", new InvocationLogStore(kvStore).list().get(0).getStatus());
        assertEquals("Capability not found", new InvocationLogStore(kvStore).list().get(0).getErrorMessage());
    }

    @Test
    public void shouldRejectDisabledCapabilityAndWriteLog() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "reminder.scanDueTasks", "scheduler");
        capability.setEnabled(Boolean.FALSE);
        capabilityStore.register(capability);
        TrackingCapabilityInvoker invoker = invoker(kvStore, neverDelegate(), new FakeStarter(true), capabilityStore);

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "reminder.scanDueTasks", null, context("scheduler"));

        assertFalse(result.isSuccess());
        assertEquals("Capability is disabled", result.getErrorMessage());
        assertEquals("Capability is disabled", new InvocationLogStore(kvStore).list().get(0).getErrorMessage());
    }

    @Test
    public void shouldRejectExposureMismatchAndWriteLog() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "internal"));
        TrackingCapabilityInvoker invoker = invoker(kvStore, neverDelegate(), new FakeStarter(true), capabilityStore);

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "reminder.scanDueTasks", null, context("scheduler"));

        assertFalse(result.isSuccess());
        assertEquals("Capability is not exposed to scheduler", result.getErrorMessage());
        assertEquals("Capability is not exposed to scheduler", new InvocationLogStore(kvStore).list().get(0).getErrorMessage());
    }

    @Test
    public void shouldAllowTickSourceForSchedulerExposureAndWriteTickLog() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        capabilityStore.register(capability("plugin-a", "reminder.scanDueTasks", "scheduler"));
        TrackingCapabilityInvoker invoker = invoker(kvStore, successDelegate(), new FakeStarter(true), capabilityStore);

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "reminder.scanDueTasks", null, context("tick"));

        assertTrue(result.isSuccess());
        assertEquals("tick", new InvocationLogStore(kvStore).list().get(0).getSource());
    }

    @Test
    public void shouldPassCapabilityTimeoutToDelegate() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "static.sync", "scheduler");
        capability.setTimeoutSeconds(900);
        capabilityStore.register(capability);
        final Integer[] observedTimeoutSeconds = new Integer[1];
        TrackingCapabilityInvoker invoker = invoker(kvStore, new CapabilityInvoker() {
            @Override
            public CapabilityInvokeResult invoke(String pluginId,
                                                 String capabilityKey,
                                                 Map<String, Object> payload,
                                                 InvokeContext context) {
                observedTimeoutSeconds[0] = context.getTimeoutSeconds();
                CapabilityInvokeResult result = new CapabilityInvokeResult();
                result.setSuccess(true);
                return result;
            }
        }, new FakeStarter(true), capabilityStore);

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "static.sync", null, context("scheduler"));

        assertTrue(result.isSuccess());
        assertEquals(Integer.valueOf(900), observedTimeoutSeconds[0]);
    }

    @Test
    public void shouldRejectHighRiskMcpInvocationAndWriteAuditLog() {
        InMemoryRuntimeKvStore kvStore = new InMemoryRuntimeKvStore();
        CapabilityStore capabilityStore = new CapabilityStore(kvStore);
        PluginCapability capability = capability("plugin-a", "dangerous.task", "mcp");
        capability.setRiskLevel("high");
        CapabilityDocument document = new CapabilityDocument();
        document.setItems(Arrays.asList(capability));
        capabilityStore.saveDocument(document);
        TrackingCapabilityInvoker invoker = invoker(kvStore, neverDelegate(), new FakeStarter(true), capabilityStore);

        CapabilityInvokeResult result = invoker.invoke("plugin-a", "dangerous.task", null, context("mcp"));

        assertFalse(result.isSuccess());
        assertEquals("High risk capability is not exposed to mcp", result.getErrorMessage());
        CapabilityInvocationLog log = new InvocationLogStore(kvStore).list().get(0);
        assertEquals("high", log.getRiskLevel());
        assertEquals(Boolean.TRUE, log.getAuditRequired());
        assertEquals("High risk capability is not exposed to mcp", log.getErrorMessage());
    }

    private TrackingCapabilityInvoker invoker(InMemoryRuntimeKvStore kvStore,
                                              CapabilityInvoker delegate,
                                              PluginRuntimeStarter starter) {
        return new TrackingCapabilityInvoker(
                delegate,
                new PluginRuntimeStateService(new PluginRuntimeStateStore(kvStore), starter, 1, 1),
                new InvocationLogStore(kvStore)
        );
    }

    private TrackingCapabilityInvoker invoker(InMemoryRuntimeKvStore kvStore,
                                              CapabilityInvoker delegate,
                                              PluginRuntimeStarter starter,
                                              CapabilityStore capabilityStore) {
        return new TrackingCapabilityInvoker(
                delegate,
                new PluginRuntimeStateService(new PluginRuntimeStateStore(kvStore), starter, 1, 1),
                new InvocationLogStore(kvStore),
                capabilityStore
        );
    }

    private CapabilityInvoker successDelegate() {
        return new CapabilityInvoker() {
            @Override
            public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
                CapabilityInvokeResult result = new CapabilityInvokeResult();
                result.setSuccess(true);
                return result;
            }
        };
    }

    private CapabilityInvoker neverDelegate() {
        return new CapabilityInvoker() {
            @Override
            public CapabilityInvokeResult invoke(String pluginId, String capabilityKey, Map<String, Object> payload, InvokeContext context) {
                throw new AssertionError("delegate should not be called");
            }
        };
    }

    private InvokeContext context(String source) {
        InvokeContext context = new InvokeContext();
        context.setSource(source);
        return context;
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

    private void assertInvocationFinished(InMemoryRuntimeKvStore kvStore, String errorMessage) {
        PluginRuntimeState state = new PluginRuntimeStateStore(kvStore).find("plugin-a").get();
        assertEquals(Integer.valueOf(0), state.getActiveInvocationCount());
        assertTrue(state.getInstances().get(0).getActiveInvocationIds().isEmpty());
        assertEquals(errorMessage, state.getLastError());
        assertEquals(1, new InvocationLogStore(kvStore).list().size());
        assertEquals("error", new InvocationLogStore(kvStore).list().get(0).getStatus());
        assertEquals(errorMessage, new InvocationLogStore(kvStore).list().get(0).getErrorMessage());
    }

    private static class FakeStarter implements PluginRuntimeStarter {

        private boolean startedAfterStart;
        private boolean startCalled;

        private FakeStarter(boolean startedAfterStart) {
            this.startedAfterStart = startedAfterStart;
        }

        @Override
        public boolean isStarted(String pluginId) {
            return startCalled && startedAfterStart;
        }

        @Override
        public Optional<PluginIdentity> findPlugin(String pluginId) {
            return Optional.of(new PluginIdentity(pluginId, "reminder"));
        }

        @Override
        public void start(PluginIdentity identity) {
            startCalled = true;
        }
    }
}
