package com.zrlog.plugincore.server.util;

import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;
import com.zrlog.plugin.data.codec.HttpResponseInfo;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

public class HttpUtils {


    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GET_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);
    static final long MAX_RESPONSE_BODY_BYTES = 4L * 1024L * 1024L;
    static final long MAX_PLUGIN_DOWNLOAD_BYTES = 256L * 1024L * 1024L;
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public static byte[] sendGetRequest(String url, Map<String, String> headers) throws Exception {
        return sendGetRequest(url, headers, MAX_RESPONSE_BODY_BYTES);
    }

    static byte[] sendGetRequest(String url, Map<String, String> headers, long maxResponseBytes) throws Exception {
        HttpResponse<byte[]> response = httpClient.send(getRequest(url, headers, GET_TIMEOUT),
                limitedBodyHandler(HttpResponse.BodySubscribers::ofByteArray, maxResponseBytes));
        return response.body();
    }

    public static void downloadToFile(String url, Map<String, String> headers, File target) throws Exception {
        downloadToFile(url, headers, target, MAX_PLUGIN_DOWNLOAD_BYTES);
    }

    static void downloadToFile(String url, Map<String, String> headers, File target, long maxDownloadBytes) throws Exception {
        Path targetPath = target.toPath();
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = parent == null
                ? Files.createTempFile(target.getName(), ".download")
                : Files.createTempFile(parent, target.getName(), ".download");
        try {
            HttpResponse<Path> response = httpClient.send(getRequest(url, headers, DOWNLOAD_TIMEOUT),
                    limitedBodyHandler(() -> HttpResponse.BodySubscribers.ofFile(tempFile), maxDownloadBytes));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Download returned HTTP " + response.statusCode());
            }
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static HttpRequest getRequest(String url, Map<String, String> headers, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return builder.uri(new URI(url)).timeout(timeout).GET().build();
    }

    public static HttpResponseInfo doRequest(BaseHttpRequestInfo httpRequestInfo) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        if (Objects.nonNull(httpRequestInfo.getHeader())) {
            httpRequestInfo.getHeader().forEach(builder::header);
        }
        builder.uri(new URI(httpRequestInfo.getAccessUrl()));
        if (httpRequestInfo.getRequestBody() == null) {
            httpRequestInfo.setRequestBody(new byte[0]);
        }
        if (httpRequestInfo.getRequestBody().length > MAX_RESPONSE_BODY_BYTES) {
            throw new IOException("HTTP request body exceeds " + MAX_RESPONSE_BODY_BYTES + " bytes");
        }
        HttpRequest httpRequest = builder.timeout(GET_TIMEOUT)
                .method(httpRequestInfo.getHttpMethod().name(),
                        HttpRequest.BodyPublishers.ofByteArray(httpRequestInfo.getRequestBody()))
                .build();
        HttpResponse<byte[]> send = httpClient.send(httpRequest,
                limitedBodyHandler(HttpResponse.BodySubscribers::ofByteArray, MAX_RESPONSE_BODY_BYTES));
        HttpResponseInfo httpResponseInfo = new HttpResponseInfo();
        httpResponseInfo.setHeader(new LinkedHashMap<>());
        for (Map.Entry<String, List<String>> header : send.headers().map().entrySet()) {
            httpResponseInfo.getHeader().put(header.getKey(), header.getValue().get(0));
        }
        httpResponseInfo.setResponseBody(send.body());
        httpResponseInfo.setStatusCode(send.statusCode());
        return httpResponseInfo;

    }

    private static <T> HttpResponse.BodyHandler<T> limitedBodyHandler(Supplier<HttpResponse.BodySubscriber<T>> factory,
                                                                      long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        return responseInfo -> new LimitedBodySubscriber<T>(factory.get(), maxBytes);
    }

    private static final class LimitedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {

        private final HttpResponse.BodySubscriber<T> delegate;
        private final long maxBytes;
        private Flow.Subscription subscription;
        private long receivedBytes;
        private boolean done;

        private LimitedBodySubscriber(HttpResponse.BodySubscriber<T> delegate, long maxBytes) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (done) {
                return;
            }
            long nextSize = receivedBytes;
            for (ByteBuffer buffer : item) {
                int remaining = buffer.remaining();
                if (nextSize > maxBytes - remaining) {
                    fail(new IOException("HTTP response body exceeds " + maxBytes + " bytes"));
                    return;
                }
                nextSize += remaining;
            }
            receivedBytes = nextSize;
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            if (done) {
                return;
            }
            done = true;
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            delegate.onComplete();
        }

        private void fail(IOException failure) {
            done = true;
            if (subscription != null) {
                subscription.cancel();
            }
            delegate.onError(failure);
        }
    }
}
