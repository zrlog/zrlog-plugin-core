package com.zrlog.plugincore.server.util;

public final class PersistentJsonLimits {

    public static final int MAX_PLUGIN_CORE_DOCUMENT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_CAPABILITY_DOCUMENT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_RUNTIME_DOCUMENT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_PLUGIN_ENTRIES = 256;
    public static final int MAX_CAPABILITY_ENTRIES = 4096;

    private PersistentJsonLimits() {
    }

    public static void requireUtf8Length(String label, String value, int maxBytes) {
        if (value == null) {
            return;
        }
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current <= 0x7f) {
                bytes++;
            } else if (current <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
            if (bytes > maxBytes) {
                throw new IllegalArgumentException(label + " exceeds " + maxBytes + " bytes");
            }
        }
    }
}
