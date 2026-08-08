package com.zrlog.plugincore.server.runtime;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PluginRuntimeServicesTest {

    @Test
    public void shouldCloseOwnedRuntimeComponentsIdempotently() {
        PluginRuntimeServices services = PluginRuntimeServices.unconfigured();

        services.beginShutdown();
        services.shutdown();
        services.shutdown();

        assertTrue(services.isShutdown());
        assertTrue(services.pluginStarts().isShutdown());
        assertTrue(services.pluginBootstrap().isShutdown());
        assertTrue(services.pluginSessions().isShutdown());
    }
}
