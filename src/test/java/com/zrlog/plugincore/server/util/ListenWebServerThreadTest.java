package com.zrlog.plugincore.server.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;

import static org.junit.Assert.assertEquals;

public class ListenWebServerThreadTest {

    @Test
    public void shouldDrainWatcherInputWithoutRetainingIt() throws Exception {
        CountingInputStream inputStream = new CountingInputStream(new byte[1024 * 1024]);

        ListenWebServerThread.awaitWatcherClose(inputStream);

        assertEquals(1024 * 1024, inputStream.getReadBytes());
    }

    private static class CountingInputStream extends ByteArrayInputStream {

        private int readBytes;

        private CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                readBytes += read;
            }
            return read;
        }

        private int getReadBytes() {
            return readBytes;
        }
    }
}
