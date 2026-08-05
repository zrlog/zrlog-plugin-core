package com.zrlog.plugincore.server.web.handler;

import com.google.gson.Gson;
import com.hibegin.common.util.EnvKit;
import com.hibegin.http.server.api.HttpErrorHandle;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpResponse;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.common.IOUtil;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.FileDesc;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;
import com.zrlog.plugincore.server.runtime.pwa.PluginPwaResources;
import com.zrlog.plugincore.server.runtime.plugin.artifact.PluginFiles;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.util.StringUtils;
import com.zrlog.plugincore.server.vo.PluginVO;

import java.io.File;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by xiaochun on 2016/2/12.
 */
public class PluginHandle implements HttpErrorHandle {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginHandle.class);

    public static final String OLD_PATH = "/admin/plugins";

    private static boolean includePath(Set<String> paths, String uri) {
        if (Objects.isNull(paths) || Objects.isNull(uri)) {
            return false;
        }
        for (String path : paths) {
            if (Objects.isNull(path)) {
                continue;
            }
            String tPath = path.trim();
            if (!tPath.isEmpty()) {
                if (uri.startsWith(tPath)) {
                    return true;
                }
            }
        }
        return false;
    }

    static PluginRequestUriInfo parseRequestUri(String uri) {
        String realUri = uri.replaceFirst(OLD_PATH + "/", "").replaceFirst("/p/", "").replaceFirst("/plugin/", "");
        if (StringUtils.isEmpty(realUri)) {
            return new PluginRequestUriInfo("", "");
        }
        if (realUri.startsWith("/")) {
            realUri = realUri.substring(1);
        }
        String pluginShortName = realUri.split("/")[0];
        String action = realUri.replaceFirst(pluginShortName, "");
        if (StringUtils.isEmpty(action)) {
            action = "/";
        }
        return new PluginRequestUriInfo(pluginShortName, action);
    }


    @Override
    public void doHandle(HttpRequest httpRequest, HttpResponse httpResponse, Throwable e) {
        boolean isLogin = Boolean.parseBoolean(httpRequest.getHeader("IsLogin"));
        httpRequest.getAttr().put("isLogin", isLogin);
        PluginRequestUriInfo pluginRequestUriInfo = parseRequestUri(httpRequest.getUri());
        if (!isPluginRequest(pluginRequestUriInfo.getName())) {
            httpResponse.renderCode(404);
            return;
        }
        if (shouldRedirectPluginRoot(httpRequest.getUri(), pluginRequestUriInfo)) {
            httpResponse.redirect(pluginRootRedirectUri(httpRequest.getUri()));
            return;
        }

        try (PluginLogContext.Scope shortNameScope = PluginLogContext.open(null,
                pluginRequestUriInfo.getName(), pluginRequestUriInfo.getName())) {
            boolean devMode = EnvKit.isDevMode();
            boolean devRequest = devMode || isDevRequest(httpRequest);
            if (devRequest) {
                LOGGER.log(Level.INFO, PluginLogContext.prefix("plugin name " + pluginRequestUriInfo.getName()));
            }
            IOSession session = getReadySession(pluginRequestUriInfo.getName());
            if (Objects.isNull(session)) {
                httpResponse.renderCode(503);
                return;
            }
            if (!devMode && !isLogin && !canAccessPublicPluginPath(session.getPlugin().getPaths(),
                    pluginRequestUriInfo.getAction())) {
                httpResponse.renderCode(403);
                return;
            }
            try (PluginLogContext.Scope sessionScope = PluginLogContext.open(session)) {
                new PluginHttpStream(session, pluginRequestUriInfo, httpRequest, httpResponse).handle();
            }
        }
    }

    static boolean canAccessPublicPluginPath(Set<String> publicPaths, String action) {
        return PluginPwaResources.isPwaResource(action) || includePath(publicPaths, action);
    }

    private IOSession getReadySession(String pluginShortName) {
        return PluginSessions.getOrStartLocalSessionByPluginShortName(pluginShortName);
    }

    private boolean isPluginRequest(String pluginShortName) {
        return shouldTreatAsPluginRequest(pluginShortName,
                registeredPlugin(pluginShortName),
                pluginFileExists(pluginShortName),
                PluginSessions.isRequiredPlugin(pluginShortName));
    }

    static boolean shouldTreatAsPluginRequest(String pluginShortName, boolean registeredPlugin, boolean pluginFileExists) {
        return shouldTreatAsPluginRequest(pluginShortName, registeredPlugin, pluginFileExists, false);
    }

    static boolean shouldTreatAsPluginRequest(String pluginShortName, boolean registeredPlugin,
                                              boolean pluginFileExists, boolean requiredPlugin) {
        return !StringUtils.isEmpty(pluginShortName)
                && !isInternalUri(pluginShortName)
                && (registeredPlugin || pluginFileExists || requiredPlugin);
    }

    private boolean registeredPlugin(String pluginShortName) {
        if (StringUtils.isEmpty(pluginShortName)) {
            return false;
        }
        PluginVO pluginVO = PluginCoreDAO.getInstance().getPluginVOByShortName(pluginShortName);
        return pluginVO != null && pluginVO.getPlugin() != null;
    }

    private boolean pluginFileExists(String pluginShortName) {
        if (StringUtils.isEmpty(pluginShortName)) {
            return false;
        }
        File pluginFile = PluginFiles.getAvailablePluginFile(pluginShortName);
        return pluginFile.exists() && pluginFile.length() > 0;
    }

    private boolean isDevRequest(HttpRequest httpRequest) {
        return Objects.equals(httpRequest.getHeader("DEV_MODE"), "true");
    }

    static boolean isInternalUri(String firstSegment) {
        return Objects.equals("api", firstSegment)
                || Objects.equals("static", firstSegment)
                || Objects.equals("runtime-scheduler", firstSegment)
                || Objects.equals("runtime-states", firstSegment)
                || Objects.equals("runtime-notification", firstSegment)
                || Objects.equals("runtime-services", firstSegment);
    }

    static boolean shouldRedirectPluginRoot(String uri, PluginRequestUriInfo pluginRequestUriInfo) {
        return pluginRequestUriInfo != null
                && !StringUtils.isEmpty(pluginRequestUriInfo.getName())
                && "/".equals(pluginRequestUriInfo.getAction())
                && uri != null
                && !pathPart(uri).endsWith("/");
    }

    static String pluginRootRedirectUri(String uri) {
        if (uri == null) {
            return "/";
        }
        int queryIndex = uri.indexOf('?');
        if (queryIndex < 0) {
            return uri + "/";
        }
        return uri.substring(0, queryIndex) + "/" + uri.substring(queryIndex);
    }

    private static String pathPart(String uri) {
        int queryIndex = uri.indexOf('?');
        if (queryIndex < 0) {
            return uri;
        }
        return uri.substring(0, queryIndex);
    }
}
