package com.zrlog.plugincore.server.runtime.capability;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.data.codec.*;
import com.zrlog.plugin.message.*;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.support.MemorySocketChannels.MemoryChannel;
import com.zrlog.plugincore.server.support.MemorySocketChannels.MemorySelector;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

public class SocketCapabilityInvokerTest {
    @Test public void completedViewRequiresVersionTwoBeforeSendingWork() {
        try (Harness h = new Harness()) {
            assertFalse(h.invoker.renderView("plugin", new HttpRequestInfo(), false, h.context).isSuccess());
            assertNull(h.sent); assertEquals(0, h.cancelled.get()); assertEquals(0, h.stopped.get());
        }
    }

    @Test public void completedViewReturnsConfirmedUnicodeWithTheOriginalRequest() {
        try (Harness h = new Harness()) {
            h.session.getPlugin().setCapabilityLifecycleVersion(2);
            HttpRequestInfo view = new HttpRequestInfo(); view.setUri("/index.action"); view.setUserId(-1);
            h.onInvoke = (session, request) -> {
                CapabilityInvokeResult rendered = result(true, true, false);
                rendered.setRenderedText("<p>你好</p>"); reply(session, request, rendered);
            };
            assertEquals("<p>你好</p>", h.invoker.renderView("plugin", view, false, h.context).getRenderedText());
            assertEquals(CapabilityInvokeRequest.HOST_VIEW_CAPABILITY, h.sent.getCapabilityKey());
            assertEquals("/index.action", h.sent.getViewRequest().getUri());
            assertEquals(Integer.valueOf(-1), h.sent.getViewRequest().getUserId());
            assertTrue(h.sent.isRequireCompletion()); assertEquals(0, h.cancelled.get());
        }
    }

    @Test public void viewSuccessWithoutContentOrWithOversizeContentRequiresCancellation() {
        for (String content : new String[]{null, "x".repeat(CapabilityInvokeRequest.MAX_VIEW_RESPONSE_BYTES + 1)}) {
            try (Harness h = new Harness()) {
                h.session.getPlugin().setCapabilityLifecycleVersion(2);
                h.onInvoke = (session, request) -> {
                    CapabilityInvokeResult rendered = result(true, true, false);
                    rendered.setRenderedText(content); reply(session, request, rendered);
                };
                assertTrue(h.invoker.renderView("plugin", new HttpRequestInfo(), false, h.context).isOutcomeUncertain());
                assertEquals(1, h.cancelled.get()); assertEquals(0, h.stopped.get());
            }
        }
    }

    @Test public void completedResponsePreservesOutcomeWithoutCancellation() {
        try (Harness h = new Harness()) {
            h.onInvoke = (session, request) -> reply(session, request, result(true, true, false));
            CapabilityInvokeResult result = h.invoke();
            assertTrue(result.isSuccess()); assertTrue(result.isExecutionCompleted());
            assertFalse(h.context.isOutcomeUncertain()); assertEquals(0, h.cancelled.get()); assertEquals(0, h.stopped.get());
            assertTrue(h.sent.isRequireCompletion()); assertEquals(h.context.getRequestId(), h.sent.getRequestId());
            assertTrue(h.sent.getDeadlineEpochMillis() <= System.currentTimeMillis() + 100);
        }
    }

    @Test public void uncertainCompletedResponseDoesNotPretendSuccessOrStopProcess() {
        try (Harness h = new Harness()) {
            h.onInvoke = (session, request) -> reply(session, request, result(true, true, true));
            assertFalse(h.invoke().isSuccess()); assertTrue(h.context.isOutcomeUncertain());
            assertEquals(0, h.cancelled.get()); assertEquals(0, h.stopped.get());
        }
    }

    @Test public void timeoutRequestsCancellationAndWaitsForMatchingCompletion() {
        try (Harness h = new Harness()) {
            CapabilityInvokeResult result = h.invoke();
            assertTrue(h.context.isOutcomeUncertain()); assertTrue(result.isExecutionCompleted());
            assertFalse(result.isSuccess()); assertEquals(1, h.cancelled.get()); assertEquals(0, h.stopped.get());
            assertFalse(h.session.isClosed());
        }
    }

    @Test public void missingCompletionCannotTurnEarlySuccessIntoFinishedExecution() {
        try (Harness h = new Harness()) {
            h.onInvoke = (session, request) -> reply(session, request, result(true, false, false));
            assertFalse(h.invoke().isSuccess()); assertTrue(h.context.isOutcomeUncertain()); assertEquals(1, h.cancelled.get());
        }
    }

    @Test public void contradictoryResponseCannotProveBusinessOutcome() {
        try (Harness h = new Harness()) {
            h.onInvoke = (session, request) -> session.dispose(new MsgPacket(result(true, true, false), ContentType.JSON,
                    MsgPacketStatus.RESPONSE_ERROR, request.getMsgId(), request.getMethodStr()));
            assertTrue(h.invoke().isOutcomeUncertain()); assertEquals(1, h.cancelled.get());
        }
    }

    @Test public void invalidRequestIdentityDoesNotReachPlugin() {
        try (Harness h = new Harness()) {
            h.context.setRequestId("invalid id");
            assertFalse(h.invoke().isSuccess()); assertNull(h.sent); assertFalse(h.context.isOutcomeUncertain());
        }
    }

    @Test public void ignoredCancellationStopsOriginalSessionBeforeReturning() {
        try (Harness h = new Harness()) {
            h.acknowledge = false;
            CapabilityInvokeResult result = h.invoke();
            assertFalse(result.isSuccess()); assertTrue(result.isExecutionCompleted());
            assertTrue(h.session.isClosed()); assertEquals(1, h.cancelled.get()); assertEquals(1, h.stopped.get());
        }
    }

    @Test public void wrongCancellationIdentityCannotAvoidProcessStop() {
        try (Harness h = new Harness()) {
            h.wrongIdentity = true;
            assertTrue(h.invoke().isOutcomeUncertain()); assertEquals(1, h.stopped.get());
        }
    }

    @Test public void failedTerminationRemainsUnconfirmed() {
        try (Harness h = new Harness()) {
            h.stopConfirmed = false; h.wrongIdentity = true;
            CapabilityInvokeResult result = h.invoke();
            assertFalse(result.isExecutionCompleted()); assertTrue(result.isOutcomeUncertain());
            assertTrue(result.getErrorMessage().contains("could not be confirmed"));
        }
    }

    @Test public void faasRejectsLegacyPluginBeforeBusinessSend() {
        try (Harness h = new Harness()) {
            h.session.getPlugin().setCapabilityLifecycleVersion(0); h.faas = true;
            assertFalse(h.invoke().isSuccess()); assertNull(h.sent); assertFalse(h.context.isOutcomeUncertain());
            assertEquals(0, h.stopped.get());
        }
    }

    @Test public void residentLegacyPluginRetainsResponseCompatibility() {
        try (Harness h = new Harness()) {
            h.session.getPlugin().setCapabilityLifecycleVersion(0);
            h.onInvoke = (session, request) -> reply(session, request, result(true, false, false));
            assertTrue(h.invoke().isSuccess()); assertFalse(h.sent.isRequireCompletion());
        }
    }

    @Test public void requiredCompletionRejectsLegacyPluginOnResidentHostBeforeSend() {
        try (Harness h = new Harness()) {
            h.session.getPlugin().setCapabilityLifecycleVersion(0);
            h.context.setRequireCompletion(true);
            assertFalse(h.invoke().isSuccess()); assertNull(h.sent);
            assertFalse(h.context.isOutcomeUncertain()); assertEquals(0, h.stopped.get());
        }
    }

    @Test public void insufficientBudgetDoesNotSendBusiness() {
        try (Harness h = new Harness()) {
            h.context.setDeadlineEpochMillis(System.currentTimeMillis() + 500);
            assertFalse(h.invoke().isSuccess()); assertNull(h.sent); assertFalse(h.context.isOutcomeUncertain());
        }
    }

    @Test public void stoppingSessionMakesOtherActiveInvocationUncertainWithoutReplay() throws Exception {
        try (Harness h = new Harness()) {
            h.acknowledge = false;
            CountDownLatch started = new CountDownLatch(2);
            h.onInvoke = (session, request) -> started.countDown();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                InvokeContext other = new InvokeContext(); other.setTimeoutSeconds(3);
                Future<CapabilityInvokeResult> second = workers.submit(() -> h.invoker.invoke("plugin", "second", Collections.emptyMap(), other));
                Future<CapabilityInvokeResult> first = workers.submit(h::invoke);
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(first.get(5, TimeUnit.SECONDS).isOutcomeUncertain());
                assertTrue(second.get(5, TimeUnit.SECONDS).isOutcomeUncertain());
                assertTrue(other.isOutcomeUncertain()); assertEquals(2, h.invocations.get());
            } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    private static CapabilityInvokeResult result(boolean success, boolean completed, boolean uncertain) {
        CapabilityInvokeResult result = new CapabilityInvokeResult(); result.setSuccess(success);
        result.setExecutionCompleted(completed); result.setOutcomeUncertain(uncertain); return result;
    }
    private static void reply(IOSession session, MsgPacket request, Object result) {
        session.dispose(new MsgPacket(result, ContentType.JSON, MsgPacketStatus.RESPONSE_SUCCESS, request.getMsgId(), request.getMethodStr()));
    }
    private static final class Harness implements AutoCloseable {
        final AtomicInteger cancelled = new AtomicInteger(), stopped = new AtomicInteger(), invocations = new AtomicInteger();
        final InvokeContext context = new InvokeContext();
        boolean acknowledge = true, wrongIdentity, stopConfirmed = true, faas;
        volatile CapabilityInvokeRequest sent;
        BiConsumer<IOSession, MsgPacket> onInvoke = (session, packet) -> { };
        final IOSession session;
        final SocketCapabilityInvoker invoker;
        Harness() {
            SocketEncode encode = new SocketEncode() {
                @Override public void doEncode(IOSession session, MsgPacket packet, Duration budget) {
                    assertTrue(budget.toMillis() > 0);
                    if (packet.getMethodStr().equals(ActionType.CAPABILITY_INVOKE.name())) {
                        sent = new Gson().fromJson(packet.getDataStr(), CapabilityInvokeRequest.class);
                        invocations.incrementAndGet(); onInvoke.accept(session, packet);
                    } else {
                        cancelled.incrementAndGet();
                        if (acknowledge) {
                            CapabilityCancelRequest cancel = new Gson().fromJson(packet.getDataStr(), CapabilityCancelRequest.class);
                            CapabilityCancelResult receipt = new CapabilityCancelResult(); receipt.setExecutionCompleted(true);
                            receipt.setRequestId(wrongIdentity ? "another-run" : cancel.getRequestId()); reply(session, packet, receipt);
                        }
                    }
                }
            };
            session = new IOSession(new MemoryChannel(), new MemorySelector(), new SocketCodec(encode, new SocketDecode(Runnable::run)), new ClientActionHandler());
            Plugin plugin = new Plugin(); plugin.setId("plugin"); plugin.setShortName("plugin"); plugin.setCapabilityLifecycleVersion(1); session.setPlugin(plugin);
            invoker = new SocketCapabilityInvoker(Duration.ofMillis(100), (id, context) -> session, (captured, deadline) -> {
                assertSame(session, captured); assertTrue(session.isClosed()); assertTrue(deadline.remainingMillis() > 0);
                stopped.incrementAndGet(); return stopConfirmed;
            }, () -> faas);
        }
        CapabilityInvokeResult invoke() { return invoker.invoke("plugin", "fixture", Collections.emptyMap(), context); }
        public void close() { session.close(); }
    }
}
