package com.zrlog.plugincore.server.util;

import com.hibegin.http.server.api.HttpRequest;
import com.zrlog.plugin.data.codec.HttpRequestInfo;

import java.nio.ByteBuffer;
import java.util.Objects;

public class HttpMsgUtil {

    private HttpMsgUtil() {
    }


    static byte[] requestBodyBytes(ByteBuffer buffer) {
        if (buffer.hasArray()
                && buffer.arrayOffset() + buffer.position() == 0
                && buffer.remaining() == buffer.array().length) {
            return buffer.array();
        }
        ByteBuffer duplicate = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }

    public static HttpRequestInfo genInfo(HttpRequest request) {
        return genInfo(request, request.getRequestBodyByteBuffer());
    }

    public static HttpRequestInfo genInfo(HttpRequest request, ByteBuffer requestBody) {
        HttpRequestInfo msgBody = new HttpRequestInfo();
        msgBody.setFullUrl(request.getHeader("Full-Url"));
        msgBody.setUserName(Objects.requireNonNullElse(request.getHeader("LoginUserName"), ""));
        if (request.getHeader("LoginUserId") != null && !"".equals(request.getHeader("LoginUserId"))) {
            msgBody.setUserId(Integer.valueOf(request.getHeader("LoginUserId")));
        } else {
            msgBody.setUserId(-1);
        }
        msgBody.setVersion(request.getHeader("Blog-Version"));
        msgBody.setAccessUrl(request.getHeader("AccessUrl"));

        //
        if (requestBody != null) {
            msgBody.setRequestBody(requestBodyBytes(requestBody));
        }
        AdminTheme.applyTo(msgBody, request);
        msgBody.setParam(request.decodeParamMap());

        return msgBody;
    }
}
