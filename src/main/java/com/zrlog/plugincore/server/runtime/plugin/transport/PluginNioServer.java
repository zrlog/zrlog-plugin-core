package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.hibegin.http.server.api.ISocketServer;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;

public class PluginNioServer {

    private final ISocketServer socketServer;
    private boolean started;
    private boolean stopped;

    public PluginNioServer() {
        this(new PluginCoreSocketServer());
    }

    public PluginNioServer(PluginConfig pluginConfig, PluginBootstrapService pluginBootstrap) {
        this(new PluginCoreSocketServer(pluginConfig, pluginBootstrap));
    }

    public PluginNioServer(ISocketServer socketServer) {
        this.socketServer = socketServer;
    }

    public synchronized boolean start() {
        if (stopped) {
            return false;
        }
        if (started) {
            return true;
        }
        if (!socketServer.create()) {
            return false;
        }
        started = true;
        new Thread(socketServer::listen, "zrlog-plugin-socket").start();
        return true;
    }

    public synchronized void stop(String reason) {
        if (stopped) {
            return;
        }
        stopped = true;
        socketServer.destroy(reason);
    }
}
