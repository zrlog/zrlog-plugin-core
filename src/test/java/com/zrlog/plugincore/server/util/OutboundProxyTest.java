package com.zrlog.plugincore.server.util;

import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

public class OutboundProxyTest {

    @Test
    public void hostLaunchArgumentsOverrideEnvironmentAndRemainStable() {
        Properties properties = new Properties();
        properties.setProperty("http.proxyHost", "host-proxy.invalid");
        properties.setProperty("http.proxyPort", "3128");
        properties.setProperty("https.proxyHost", "");
        properties.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]|[::ffff:127.*|*.internal");
        ProxySelector selector = OutboundProxy.resolve(properties,
                Map.of("HTTP_PROXY", "http://ignored.invalid:8888", "HTTPS_PROXY", "http://ignored.invalid:8888",
                        "NO_PROXY", "*"), null);
        properties.setProperty("http.proxyHost", "pending.invalid");

        assertProxy(selector, "http://external.invalid", "host-proxy.invalid", 3128);
        assertDirect(selector, "https://external.invalid");
        assertDirect(selector, "http://service.internal");
        assertDirect(selector, "http://[::ffff:127.0.0.1]/");
    }

    @Test
    public void resolvesSeparateProtocolsAndLowercaseEnvironmentOverrides() {
        ProxySelector selector = OutboundProxy.resolve(new Properties(), Map.of(
                "HTTP_PROXY", "http://ignored.invalid:8080", "http_proxy", "http://plain.invalid:3128",
                "HTTPS_PROXY", "http://secure.invalid", "NO_PROXY", "*", "no_proxy", ".internal,::1"), null);
        assertProxy(selector, "http://external.invalid", "plain.invalid", 3128);
        assertProxy(selector, "https://external.invalid", "secure.invalid", 80);
        assertDirect(selector, "http://internal");
        assertDirect(selector, "https://service.internal");
        assertProxy(selector, "https://notinternal", "secure.invalid", 80);
    }

    @Test
    public void emptyValuesDisableOnlyTheConfiguredProtocol() {
        ProxySelector inherited = ProxySelector.of(new InetSocketAddress("inherited.invalid", 8080));
        ProxySelector selector = OutboundProxy.resolve(new Properties(), Map.of(
                "HTTP_PROXY", "http://ignored.invalid:3128", "http_proxy", ""), inherited);
        assertDirect(selector, "http://external.invalid");
        assertProxy(selector, "https://external.invalid", "inherited.invalid", 8080);
    }

    @Test
    public void loopbackAlwaysBypassesEvenAnInheritedProxy() {
        ProxySelector selector = OutboundProxy.resolve(new Properties(), Map.of(),
                ProxySelector.of(new InetSocketAddress("inherited.invalid", 8080)));
        for (String host : List.of("localhost", "127.0.0.1", "127.23.4.5", "[::1]", "[0:0:0:0:0:0:0:1]")) {
            assertDirect(selector, "http://" + host);
            assertDirect(selector, "https://" + host);
        }
        assertProxy(selector, "http://localhost.external.invalid", "inherited.invalid", 8080);
    }

    @Test
    public void supportsIpv6ProxyAndExplicitAllDirectPolicy() {
        ProxySelector selector = OutboundProxy.resolve(new Properties(),
                Map.of("HTTPS_PROXY", "http://[2001:db8::1]:3128"), null);
        assertProxy(selector, "https://external.invalid", "2001:db8::1", 3128);
        assertDirect(OutboundProxy.resolve(new Properties(),
                Map.of("HTTPS_PROXY", "http://proxy.invalid", "NO_PROXY", "*"), null), "https://external.invalid");
    }

    @Test
    public void rejectsMalformedSettingsWithoutExposingCredentials() {
        for (String value : List.of("http://user:secret@proxy.invalid", "socks5://proxy.invalid:1080",
                "http://proxy.invalid/path", "http://proxy.invalid:0", "http://proxy.invalid:65536",
                "http://proxy.invalid?secret", "http://proxy.invalid#secret")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OutboundProxy.resolve(new Properties(), Map.of("HTTP_PROXY", value), null));
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause());
        }
        for (String value : List.of("host.invalid:8080", "10.0.0.0/8", ".", "a..b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OutboundProxy.resolve(new Properties(), Map.of("NO_PROXY", value), null));
        }
    }

    @Test
    public void configuredProxyFailureDoesNotAddDirectFallback() {
        ProxySelector selector = OutboundProxy.resolve(new Properties(),
                Map.of("HTTPS_PROXY", "http://proxy.invalid:3128"), null);
        URI target = URI.create("https://external.invalid");
        selector.connectFailed(target, new InetSocketAddress("proxy.invalid", 3128), new IOException("failure"));
        assertProxy(selector, target.toString(), "proxy.invalid", 3128);
    }

    private static void assertDirect(ProxySelector selector, String url) {
        assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create(url)));
    }

    private static void assertProxy(ProxySelector selector, String url, String host, int port) {
        List<Proxy> proxies = selector.select(URI.create(url));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        InetSocketAddress address = (InetSocketAddress) proxies.get(0).address();
        assertEquals(host, address.getHostString());
        assertEquals(port, address.getPort());
    }
}
