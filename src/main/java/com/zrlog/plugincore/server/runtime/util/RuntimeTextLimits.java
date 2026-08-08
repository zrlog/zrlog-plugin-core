package com.zrlog.plugincore.server.runtime.util;

public final class RuntimeTextLimits {

    public static final int MAX_ERROR_MESSAGE_CODE_POINTS = 4 * 1024;

    private RuntimeTextLimits() {
    }

    public static String truncateErrorMessage(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.codePointCount(0, value.length()) <= MAX_ERROR_MESSAGE_CODE_POINTS) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, MAX_ERROR_MESSAGE_CODE_POINTS);
        return value.substring(0, endIndex);
    }
}
