package com.zrlog.plugincore.server.runtime.event;

import com.zrlog.plugin.data.codec.HttpRequestInfo;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.runtime.capability.InvokeContext;
import com.zrlog.plugincore.server.runtime.capability.SocketCapabilityInvoker;
import com.zrlog.plugincore.server.runtime.invocation.CapabilityInvocationLog;
import com.zrlog.plugincore.server.runtime.invocation.InvocationLogStore;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.util.RuntimeDeadline;

import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HostViewRuntime {
    private static final Logger LOGGER = Logger.getLogger(HostViewRuntime.class.getName());
    private final Function<String, Plugin> plugins;
    private final PluginRuntimeStateService states;
    private final SocketCapabilityInvoker invoker;
    private final InvocationLogStore logs;

    public HostViewRuntime(Function<String, Plugin> plugins, PluginRuntimeStateService states,
                           SocketCapabilityInvoker invoker, InvocationLogStore logs) {
        this.plugins = plugins; this.states = states; this.invoker = invoker; this.logs = logs;
    }

    public HostViewResult render(String name, HttpRequestInfo view, String triggerId, String requestId, int actor,
                                 RuntimeDeadline deadline) {
        HostViewResult result = new HostViewResult();
        result.triggerId = triggerId; result.requestId = requestId;
        long started = System.currentTimeMillis();
        Plugin plugin = plugins.apply(name);
        if (plugin == null) { result.errorMessage = "Plugin is not installed"; return result; }
        String path = view.getUri();
        if ("/".equals(path) && plugin.getIndexPage() != null && !plugin.getIndexPage().isEmpty()) path = plugin.getIndexPage();
        if (!validPath(path)) throw new IllegalArgumentException("Invalid plugin view path");
        if (!states.ensureStarted(plugin.getId(), deadline.reserveMillis(7000).capMillis(30000))) {
            result.errorMessage = "Plugin view startup did not complete"; return result;
        }
        deadline.check();
        boolean resource = new java.io.File(path).getName().contains(".");
        view.setUri(resource ? path : path + ".action");
        // Public exports never borrow the assistant owner's browser identity.
        view.setUserId(-1); view.setUserName(""); view.setRequestBody(new byte[0]);
        view.setHttpMethod(com.zrlog.plugin.common.type.HttpMethod.GET);
        InvokeContext context = new InvokeContext();
        context.setSource("host_view"); context.setUserId(String.valueOf(actor));
        context.setRequestId(requestId); context.setTraceId(triggerId);
        context.setDeadline(deadline); context.setRequireCompletion(true); context.setAllowStartup(false);
        states.markInvocationStart(plugin.getId(), plugin.getName(), requestId);
        try {
            CapabilityInvokeResult rendered = invoker.renderView(plugin.getId(), view, resource, context);
            result.outcomeUncertain = context.isOutcomeUncertain() || rendered.isOutcomeUncertain();
            result.completed = rendered.isSuccess() && rendered.isExecutionCompleted() && !result.outcomeUncertain;
            result.content = result.completed ? rendered.getRenderedText() : null;
            result.errorMessage = rendered.getErrorMessage();
        } catch (RuntimeException error) {
            result.outcomeUncertain = true;
            result.errorMessage = "Plugin view result is unavailable";
        } catch (Error fatal) {
            result.outcomeUncertain = true;
            result.errorMessage = "Plugin view result is unavailable";
            throw fatal;
        } finally {
            try {
                states.markInvocationEndWithRetry(plugin.getId(), plugin.getName(), requestId, result.errorMessage);
            } catch (RuntimeException | Error trackingFailure) {
                LOGGER.log(Level.WARNING, "Unable to finish plugin view invocation tracking", trackingFailure);
            }
            CapabilityInvocationLog log = new CapabilityInvocationLog();
            log.setId(requestId); log.setRequestId(requestId); log.setTraceId(triggerId);
            log.setPluginId(plugin.getId()); log.setCapabilityKey(CapabilityInvokeRequest.HOST_VIEW_CAPABILITY);
            log.setSource("host_view"); log.setAuditRequired(true);
            log.setStartedAt(started); log.setFinishedAt(System.currentTimeMillis());
            log.setDurationMs(log.getFinishedAt() - started);
            log.setStatus(result.outcomeUncertain ? "uncertain" : result.completed ? "success" : "error");
            log.setErrorMessage(result.errorMessage);
            try { logs.append(log); }
            catch (RuntimeException | Error loggingFailure) {
                LOGGER.log(Level.WARNING, "Unable to append plugin view invocation log", loggingFailure);
            }
        }
        return result;
    }

    public static boolean validPath(String path) {
        if (path == null || path.length() > 1024 || !path.startsWith("/") || path.startsWith("//")
                || path.contains("\\") || path.contains("?") || path.contains("#")) return false;
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return false;
        for (String segment : path.split("/")) if (".".equals(segment) || "..".equals(segment)) return false;
        return true;
    }
}
