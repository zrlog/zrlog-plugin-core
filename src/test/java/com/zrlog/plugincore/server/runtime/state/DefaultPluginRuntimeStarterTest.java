package com.zrlog.plugincore.server.runtime.state;

import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.vo.PluginVO;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultPluginRuntimeStarterTest {

    @Test
    public void shouldResolveRequiredPluginBeforeMetadataRegistration() {
        PluginBootstrapService bootstrapService = new PluginBootstrapService(
                Collections.singletonMap("comment", "comment"), null, null, null);
        try {
            PluginCore pluginCore = new PluginCore();
            DefaultPluginRuntimeStarter starter = new DefaultPluginRuntimeStarter(
                    () -> pluginCore, bootstrapService);

            Optional<PluginIdentity> identity = starter.findPlugin("comment");

            assertTrue(identity.isPresent());
            assertEquals("comment", identity.get().getPluginId());
            assertEquals("comment", identity.get().getPluginShortName());
            assertEquals("comment", identity.get().getPluginName());
            assertFalse(starter.findPlugin("unknown-plugin").isPresent());

            Plugin registered = new Plugin();
            registered.setId("comment");
            registered.setShortName("comment");
            registered.setName("Registered Comment Plugin");
            PluginVO registeredEntry = new PluginVO();
            registeredEntry.setPlugin(registered);
            pluginCore.getPluginInfoMap().put("comment", registeredEntry);

            PluginIdentity registeredIdentity = starter.findPlugin("comment").orElseThrow(AssertionError::new);
            assertEquals("Registered Comment Plugin", registeredIdentity.getPluginName());
        } finally {
            bootstrapService.shutdown();
        }
    }
}
