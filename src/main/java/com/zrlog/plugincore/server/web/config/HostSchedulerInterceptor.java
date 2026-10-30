package com.zrlog.plugincore.server.web.config;

import com.hibegin.http.HttpMethod;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpResponse;
import com.hibegin.http.server.api.Interceptor;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Separate machine boundary; browser login headers and external scheduler tokens do not authenticate it. */
public final class HostSchedulerInterceptor implements Interceptor {
    public static final String PREFIX = "/internal/runtime-scheduler";

    @Override
    public boolean doInterceptor(HttpRequest request, HttpResponse response) {
        if (!request.getUri().startsWith(PREFIX + "/")) return true;
        response.addHeader("Cache-Control", "no-store");
        if (!authorized(PluginRuntimeBridge.hostConnection().getBlogPluginToken(), request.getHeader("X-Plugin-Token"))
                || !validActor(request.getHeader("X-ZrLog-Actor-Id"))) {
            response.renderCode(401);
            return false;
        }
        String path = request.getUri().substring(PREFIX.length());
        boolean allowed = ("/tick".equals(path) || "/refresh-cache".equals(path) || "/render-view".equals(path))
                && request.getMethod() == HttpMethod.POST;
        if (!allowed) {
            response.renderCode(405);
            return false;
        }
        response.addHeader("X-ZrLog-Scheduler-Protocol", "1");
        return true;
    }

    static boolean authorized(String expected, String supplied) {
        return expected != null && !expected.isEmpty() && supplied != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean validActor(String value) {
        try { return value != null && Integer.parseInt(value) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }
}
