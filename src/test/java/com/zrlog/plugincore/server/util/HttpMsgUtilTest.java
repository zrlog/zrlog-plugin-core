package com.zrlog.plugincore.server.util;

import com.hibegin.http.server.api.HttpRequest;
import com.zrlog.plugin.data.codec.HttpRequestInfo;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class HttpMsgUtilTest {

    @Test
    public void shouldReuseCompleteArrayBackedRequestBody() {
        byte[] body = new byte[]{1, 2, 3};
        ByteBuffer buffer = ByteBuffer.wrap(body);

        byte[] result = HttpMsgUtil.requestBodyBytes(buffer);

        assertSame(body, result);
        assertEquals(0, buffer.position());
    }

    @Test
    public void shouldCopyOnlyVisibleBytesForPartialBuffer() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0, 1, 2, 3});
        buffer.position(1);
        buffer.limit(3);

        byte[] result = HttpMsgUtil.requestBodyBytes(buffer);

        assertArrayEquals(new byte[]{1, 2}, result);
        assertNotSame(buffer.array(), result);
        assertEquals(1, buffer.position());
    }

    @Test
    public void shouldLoadRequestBodyOnlyOnceWhileBuildingPluginMessage() {
        byte[] body = new byte[]{4, 5, 6};
        AtomicInteger bodyReads = new AtomicInteger();
        Map<String, String> headers = new HashMap<>();
        headers.put(AdminTheme.DARK_MODE_HEADER, "false");
        headers.put(AdminTheme.ADMIN_COLOR_PRIMARY_HEADER, "#1677ff");
        HttpRequest request = (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class[]{HttpRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRequestBodyByteBuffer":
                            bodyReads.incrementAndGet();
                            return ByteBuffer.wrap(body);
                        case "getHeaderMap":
                            return headers;
                        case "getHeader":
                            return headers.get((String) args[0]);
                        case "getRemoteHost":
                            return "127.0.0.1";
                        case "decodeParamMap":
                            return Collections.emptyMap();
                        default:
                            return null;
                    }
                }
        );

        HttpRequestInfo requestInfo = HttpMsgUtil.genInfo(request);

        assertEquals(1, bodyReads.get());
        assertSame(body, requestInfo.getRequestBody());
    }
}
