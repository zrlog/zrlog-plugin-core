package com.zrlog.plugincore.server.util;

import com.zrlog.plugin.common.LoggerUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ListenWebServerThread extends Thread {

    private static final Logger LOGGER = LoggerUtil.getLogger(ListenWebServerThread.class);
    private static final int WATCHER_READ_BUFFER_BYTES = 1024;

    private final int port;

    public ListenWebServerThread(int port) {
        this.port = port;
    }

    @Override
    public void run() {
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
            Socket socket = serverSocket.accept();
            InputStream inputStream = socket.getInputStream();
            awaitWatcherClose(inputStream);
            socket.close();
            serverSocket.close();
            System.exit(0);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "", e);
        }
    }

    static void awaitWatcherClose(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[WATCHER_READ_BUFFER_BYTES];
        while (inputStream.read(buffer) != -1) {
            // The watcher connection is only a lifetime signal; discard any received bytes.
        }
    }
}
