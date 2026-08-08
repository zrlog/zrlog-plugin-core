package com.zrlog.plugincore.server.web.handler;

import com.hibegin.common.dao.ResultBeanUtils;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpResponse;
import com.hibegin.http.server.util.MimeTypeUtil;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.common.*;
import com.zrlog.plugin.common.type.PluginVersion;
import com.zrlog.plugin.data.codec.*;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugincore.server.runtime.pwa.PluginPwaResources;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStates;
import com.zrlog.plugincore.server.util.HttpMsgUtil;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public class PluginHttpStream {

    private static final Logger LOGGER = LoggerUtil.getLogger(PluginHttpStream.class);

    private static final PluginPwaResources PWA_RESOURCES = new PluginPwaResources();
    private static final PluginHttpRequestMemoryBudget REQUEST_MEMORY_BUDGET =
            new PluginHttpRequestMemoryBudget(PluginHttpRequestMemoryBudget.DEFAULT_MAX_WEIGHTED_BYTES);

    static final String STATIC_ASSET_CACHE_CONTROL = "max-age=31536000, immutable";
    private final IOSession session;
    private final PluginRequestUriInfo pluginRequestUriInfo;
    private final HttpRequest httpRequest;
    private final HttpResponse httpResponse;

    public PluginHttpStream(IOSession session, PluginRequestUriInfo pluginRequestUriInfo, HttpRequest httpRequest, HttpResponse httpResponse) {
        this.session = session;
        this.pluginRequestUriInfo = pluginRequestUriInfo;
        this.httpRequest = httpRequest;
        this.httpResponse = httpResponse;
    }

    public void handle() {
        PluginRuntimeStateService stateService = PluginRuntimeStates.newStateService(session);
        String pluginId = session.getPlugin().getId();
        String pluginName = PluginSessions.nameOrShortName(session.getPlugin());
        String errorMessage = null;
        stateService.markInvocationStart(pluginId, pluginName);
        //Full Blog System ENV
        int id = IdUtil.getInt();
        try {
            if (PWA_RESOURCES.renderIfMatched(session.getPlugin(), pluginRequestUriInfo, httpRequest, httpResponse)) {
                return;
            }
            ByteBuffer requestBody = httpRequest.getRequestBodyByteBuffer();
            int requestBodyBytes = requestBody == null ? 0 : requestBody.remaining();
            PluginHttpRequestMemoryBudget.Lease requestMemory = REQUEST_MEMORY_BUDGET.tryAcquire(requestBodyBytes);
            if (requestMemory == null) {
                errorMessage = "Plugin HTTP request memory capacity reached";
                httpResponse.renderCode(503);
                return;
            }
            try (PluginHttpRequestMemoryBudget.Lease ignored = requestMemory) {
                HttpRequestInfo msgBody = HttpMsgUtil.genInfo(httpRequest, requestBody);
                msgBody.setUri(pluginRequestUriInfo.getAction());
                if (("/".equals(msgBody.getUri()) && !"".equals(session.getPlugin().getIndexPage()))) {
                    msgBody.setUri(session.getPlugin().getIndexPage());
                }
                ActionType actionType;
                if (new File(msgBody.getUri()).getName().contains(".")) {
                    actionType = ActionType.HTTP_FILE;
                } else {
                    actionType = ActionType.HTTP_METHOD;

                    msgBody.setUri(msgBody.getUri() + ".action");
                }
                if (PluginVersionUtils.getPluginVersion(session.getPlugin()) == PluginVersion.V1) {
                    Map convert = ResultBeanUtils.convert(msgBody, Map.class);
                    //
                    convert.put("class", "com.fzb.zrlog.plugin.data.codec.HttpRequestInfo");
                    convert.put("userId", 0);
                    session.sendJsonMsg(convert, actionType.name(), id, MsgPacketStatus.SEND_REQUEST);
                } else {
                    session.sendJsonMsg(msgBody, actionType.name(), id, MsgPacketStatus.SEND_REQUEST);
                }
                try (ResponseLease responseLease = session.getResponseLeaseByMsgId(id)) {
                    if (responseLease == null) {
                        errorMessage = "plugin " + session.getPlugin().getShortName() + " not response";
                        LOGGER.warning(PluginLogContext.prefix(httpRequest.getUri() + " -> error, " + errorMessage));
                        httpResponse.renderCode(500);
                        return;
                    }
                    MsgPacket responseMsgPacket = responseLease.getPacket();
                    if (responseMsgPacket.getStatus() == MsgPacketStatus.RESPONSE_ERROR) {
                        errorMessage = "plugin " + session.getPlugin().getShortName() + " response error";
                    }
                    if (responseMsgPacket.getMethodStr().equals(ActionType.HTTP_ATTACHMENT_FILE.name())) {
                        httpResponse.renderFile(attachmentFile(responseMsgPacket));
                        return;
                    }
                    String ext = getExt(httpRequest, responseMsgPacket);
                    InputStream in = new ByteArrayInputStream(responseMsgPacket.getData().array());
                    httpResponse.addHeader("Content-Type", MimeTypeUtil.getMimeStrByExt(ext));
                    if (responseMsgPacket.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS) {
                        addPluginStaticCacheHeader(httpResponse, session.getPlugin(), pluginRequestUriInfo.getAction(), actionType);
                    }
                    httpResponse.write(in, responseMsgPacket.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS ? 200 : 500);
                }
            }
        } catch (RuntimeException ex) {
            errorMessage = ex.getMessage();
            throw ex;
        } finally {
            stateService.markInvocationEnd(pluginId, pluginName, errorMessage);
        }
    }

    static File attachmentFile(MsgPacket responseMsgPacket) {
        FilePacketPayload filePayload = responseMsgPacket == null ? null : responseMsgPacket.getFilePayload();
        if (filePayload == null) {
            throw new IllegalStateException("Plugin attachment response has no FILE payload");
        }
        return filePayload.getFile();
    }

    private static String getExt(HttpRequest httpRequest, MsgPacket responseMsgPacket) {
        if (responseMsgPacket.getContentType() == ContentType.JSON) {
            return "json";
        } else if (responseMsgPacket.getContentType() == ContentType.HTML) {
            return "html";
        } else if (responseMsgPacket.getContentType() == ContentType.XML) {
            return "xml";
        } else if (responseMsgPacket.getContentType() == ContentType.IMAGE_SVG_XML) {
            return "svg";
        }
        return httpRequest.getUri().substring(httpRequest.getUri().lastIndexOf(".") + 1);
    }

    static void addPluginStaticCacheHeader(HttpResponse response, Plugin plugin, String action, ActionType actionType) {
        if (response != null && shouldCachePluginStaticResource(plugin, action, actionType)) {
            response.addHeader("Cache-Control", STATIC_ASSET_CACHE_CONTROL);
        }
    }

    static boolean shouldCachePluginStaticResource(Plugin plugin, String action, ActionType actionType) {
        if (plugin == null || actionType != ActionType.HTTP_FILE) {
            return false;
        }
        return matchesReportedCacheableStaticPath(action, plugin.getCacheableStaticPaths());
    }

    private static boolean matchesReportedCacheableStaticPath(String action, Set<String> cacheableStaticPaths) {
        String path = normalizeCacheableStaticPath(action);
        if (path == null || cacheableStaticPaths == null || cacheableStaticPaths.isEmpty()) {
            return false;
        }
        for (String cacheableStaticPath : cacheableStaticPaths) {
            String rule = normalizeCacheableStaticPath(cacheableStaticPath);
            if (rule == null) {
                continue;
            }
            if (rule.endsWith("/") && path.startsWith(rule)) {
                return true;
            }
            if (path.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCacheableStaticPath(String value) {
        if (value == null) {
            return null;
        }
        String path = value.trim();
        if (path.isEmpty()) {
            return null;
        }
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        int fragmentIndex = path.indexOf('#');
        if (fragmentIndex >= 0) {
            path = path.substring(0, fragmentIndex);
        }
        int staticIndex = path.indexOf("/static/");
        if (staticIndex >= 0) {
            path = path.substring(staticIndex);
        } else if (path.startsWith("static/")) {
            path = "/" + path;
        }
        return path.startsWith("/static/") ? path : null;
    }
}
