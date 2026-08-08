package com.zrlog.plugincore.server.runtime.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RuntimeTextLimitsTest {

    @Test
    public void shouldTruncateWithoutSplittingSupplementaryCodePoint() {
        String prefix = repeat("a", RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS - 1);
        String rocket = "\uD83D\uDE80";

        String truncated = RuntimeTextLimits.truncateErrorMessage(prefix + rocket + "tail");

        assertEquals(prefix + rocket, truncated);
        assertEquals(RuntimeTextLimits.MAX_ERROR_MESSAGE_CODE_POINTS,
                truncated.codePointCount(0, truncated.length()));
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
