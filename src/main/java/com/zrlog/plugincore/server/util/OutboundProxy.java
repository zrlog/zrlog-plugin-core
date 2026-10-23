package com.zrlog.plugincore.server.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Shared outbound policy for WebAPI databases, plugin HTTP forwarding and artifact downloads. */
public final class OutboundProxy {

    private OutboundProxy() {
    }

    public static ProxySelector selector() {
        return Active.SELECTOR;
    }

    private static final class Active {
        private static final ProxySelector SELECTOR = resolve(System.getProperties(), System.getenv(),
                ProxySelector.getDefault());
    }

    static ProxySelector resolve(Properties properties, Map<String, String> environment, ProxySelector inherited) {
        // The host has already resolved file/environment overrides. Its launch arguments are authoritative.
        Proxy http = endpoint(properties, environment, "http");
        Proxy https = endpoint(properties, environment, "https");
        boolean jvmBypass = properties.containsKey("http.nonProxyHosts");
        String noProxy = jvmBypass
                ? properties.getProperty("http.nonProxyHosts") : environmentValue(environment, "NO_PROXY");
        List<String> bypass = bypassPatterns(noProxy, jvmBypass);
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (uri == null) {
                    throw new IllegalArgumentException("URI is required");
                }
                boolean secure = "https".equalsIgnoreCase(uri.getScheme());
                if (!(secure || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                    return List.of(Proxy.NO_PROXY);
                }
                for (String pattern : bypass) {
                    if (matches(uri.getHost(), pattern)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                }
                Proxy configured = secure ? https : http;
                if (configured != null) {
                    return List.of(configured);
                }
                return inherited == null ? List.of(Proxy.NO_PROXY) : inherited.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException error) {
                // Configured proxies have no direct fallback.
            }
        };
    }

    private static Proxy endpoint(Properties properties, Map<String, String> environment, String scheme) {
        String hostKey = scheme + ".proxyHost";
        if (properties.containsKey(hostKey)) {
            String host = properties.getProperty(hostKey).trim();
            if (host.isEmpty()) {
                return Proxy.NO_PROXY;
            }
            try {
                return proxy(host, Integer.parseInt(properties.getProperty(scheme + ".proxyPort", "80")));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid " + scheme + " JVM proxy configuration");
            }
        }
        String variable = scheme.toUpperCase(Locale.ROOT) + "_PROXY";
        String value = environmentValue(environment, variable);
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            return Proxy.NO_PROXY;
        }
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())) || uri.getPort() < -1) {
                throw new IllegalArgumentException();
            }
            return proxy(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort());
        } catch (IllegalArgumentException error) {
            // Invalid input can contain credentials; never include it in the error.
            throw new IllegalArgumentException(variable + " must be an HTTP proxy URL without credentials");
        }
    }

    private static Proxy proxy(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException();
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }

    private static String environmentValue(Map<String, String> environment, String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        String value = environment.containsKey(lower) ? environment.get(lower) : environment.get(key);
        return value == null ? null : value.trim();
    }

    private static List<String> bypassPatterns(String noProxy, boolean jvmPatterns) {
        List<String> patterns = new ArrayList<>(List.of("localhost", "127.*", "[::1]", "[0:0:0:0:0:0:0:1]",
                "[::ffff:127.*"));
        if (noProxy == null) {
            return patterns;
        }
        for (String item : noProxy.split("[,|]")) {
            String token = item.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (jvmPatterns) {
                patterns.add(token);
                continue;
            }
            if (!token.matches("[A-Za-z0-9.*_:\\[\\]-]+") || token.contains("..") || token.equals(".")) {
                throw new IllegalArgumentException("Invalid proxy bypass host or domain pattern");
            }
            if (token.contains(":")) {
                if (!(token.startsWith("[") && token.endsWith("]"))) {
                    token = "[" + token + "]";
                }
                try {
                    if (URI.create("http://" + token + "/").getHost() == null) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("Proxy bypass entries cannot contain ports or CIDR ranges");
                }
            }
            if (token.startsWith(".")) {
                patterns.add(token.substring(1));
                token = "*" + token;
            }
            patterns.add(token);
        }
        return patterns;
    }

    private static boolean matches(String destination, String pattern) {
        String host = destination.toLowerCase(Locale.ROOT);
        String token = pattern.toLowerCase(Locale.ROOT);
        if ("*".equals(token)) {
            return true;
        }
        boolean prefix = token.startsWith("*");
        boolean suffix = token.endsWith("*");
        String literal = token.substring(prefix ? 1 : 0, token.length() - (suffix ? 1 : 0));
        if (prefix && suffix) return host.contains(literal);
        if (prefix) return host.endsWith(literal);
        if (suffix) return host.startsWith(literal);
        return host.equals(literal);
    }
}
