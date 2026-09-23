package com.zrlog.plugincore.server.util;

import com.hibegin.http.server.api.HttpRequest;
import com.zrlog.plugincore.server.dao.WebSiteDAO;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;

/** Backend messages only; UI resources live in the frontend TypeScript module. */
public final class PluginI18n {
    private static final String DEFAULT_LANGUAGE = "zh_CN";
    private static final ThreadLocal<String> LANGUAGE = new ThreadLocal<>();
    private static final Properties ZH_CN = load("/i18n/plugin_backend_zh_CN.properties");
    private static final Properties EN_US = load("/i18n/plugin_backend_en_US.properties");

    private PluginI18n() {
    }

    public static String normalizeLanguage(String language) {
        String value = language == null ? "" : language.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        return value.equals("en") || value.startsWith("en_") ? "en_US" : DEFAULT_LANGUAGE;
    }

    public static String resolveLanguage(HttpRequest request) {
        // Admin pages use the same website setting as admin-web, including direct plugin-core access.
        try {
            Object configured = new WebSiteDAO().queryValueByName("language");
            if (configured != null && !configured.toString().trim().isEmpty()) {
                return normalizeLanguage(configured.toString());
            }
        } catch (SQLException | IllegalStateException e) {
            // A standalone/dev runtime may not have a configured website database yet.
        }
        String acceptLanguage = request == null ? null : request.getHeader("Accept-Language");
        if (acceptLanguage != null) {
            acceptLanguage = acceptLanguage.split("[,;]", 2)[0];
        }
        return normalizeLanguage(acceptLanguage);
    }

    public static void setLanguage(String language) {
        LANGUAGE.set(normalizeLanguage(language));
    }

    public static String getLanguage() {
        String language = LANGUAGE.get();
        return language == null ? DEFAULT_LANGUAGE : language;
    }

    /** Always close on the request worker, also when dispatch fails or CallerRunsPolicy is used. */
    public static Scope open(String language) {
        String previous = LANGUAGE.get();
        setLanguage(language);
        return new Scope(previous);
    }

    public static String text(String key, Object... arguments) {
        Properties messages = "en_US".equals(getLanguage()) ? EN_US : ZH_CN;
        String pattern = messages.getProperty(key);
        if (pattern == null) {
            throw new IllegalArgumentException("Unknown plugin i18n key: " + key);
        }
        return arguments.length == 0 ? pattern : new MessageFormat(pattern,
                "en_US".equals(getLanguage()) ? Locale.US : Locale.SIMPLIFIED_CHINESE).format(arguments);
    }

    private static Properties load(String path) {
        Properties messages = new Properties();
        try (InputStream stream = PluginI18n.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing plugin i18n resource: " + path);
            }
            messages.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return messages;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load plugin i18n resource: " + path, e);
        }
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                LANGUAGE.remove();
            } else {
                LANGUAGE.set(previous);
            }
        }
    }
}
