package com.zrlog.plugincore.server.dao;

import com.google.gson.Gson;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;
import com.zrlog.plugincore.server.vo.PluginVO;
import org.junit.Test;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginCoreDocumentLimitsTest {

    @Test
    public void shouldRejectOversizedStoredPluginCoreBeforeParsingIt() {
        FakePluginCoreDAO dao = new FakePluginCoreDAO(
                "x".repeat(PersistentJsonLimits.MAX_PLUGIN_CORE_DOCUMENT_BYTES + 1));

        assertRejected(dao::loadSnapshot, "exceeds");
        assertEquals(0, dao.casCalls);
    }

    @Test
    public void shouldRejectTooManyPersistedPluginEntries() {
        PluginCore pluginCore = new PluginCore();
        for (int i = 0; i <= PersistentJsonLimits.MAX_PLUGIN_ENTRIES; i++) {
            Plugin plugin = new Plugin();
            plugin.setId("plugin-" + i);
            plugin.setShortName("plugin-" + i);
            PluginVO pluginVO = new PluginVO();
            pluginVO.setPlugin(plugin);
            pluginCore.getPluginInfoMap().put(plugin.getShortName(), pluginVO);
        }
        FakePluginCoreDAO dao = new FakePluginCoreDAO(new Gson().toJson(pluginCore));

        assertRejected(dao::loadSnapshot, "entry count");
        assertEquals(0, dao.casCalls);
    }

    @Test
    public void shouldRejectProjectedPluginCoreBeforeRegistrationWrites() {
        FakePluginCoreDAO dao = new FakePluginCoreDAO(new Gson().toJson(new PluginCore()));
        Plugin plugin = new Plugin();
        plugin.setId("large-plugin");
        plugin.setShortName("large-plugin");
        plugin.setDesc("x".repeat(PersistentJsonLimits.MAX_PLUGIN_CORE_DOCUMENT_BYTES));

        assertRejected(() -> dao.validatePluginRegistration(plugin), "exceeds");
        assertEquals(0, dao.casCalls);
    }

    private void assertRejected(Runnable action, String messagePart) {
        try {
            action.run();
            fail("document should be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }

    private static class FakePluginCoreDAO extends PluginCoreDAO {

        private final String raw;
        private int casCalls;

        private FakePluginCoreDAO(String raw) {
            this.raw = raw;
        }

        @Override
        protected WebSiteDAO.WebSiteValueSnapshot getPluginCoreRawByDb() {
            return new WebSiteDAO.WebSiteValueSnapshot(Optional.ofNullable(raw), "remark-1");
        }

        @Override
        protected boolean compareAndSetPluginCore(String expectedValue, String expectedRemark, String value)
                throws SQLException {
            casCalls++;
            return true;
        }
    }
}
