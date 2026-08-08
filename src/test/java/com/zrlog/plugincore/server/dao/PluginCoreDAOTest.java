package com.zrlog.plugincore.server.dao;

import com.google.gson.Gson;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeSetting;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import org.junit.Test;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PluginCoreDAOTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldSkipCasWhenUpdateDoesNotChangeJson() {
        String raw = gson.toJson(new PluginCore());
        FakePluginCoreDAO dao = new FakePluginCoreDAO(raw);

        PluginCore updated = dao.update(pluginCore -> {
        });

        assertEquals(1, dao.loadCalls);
        assertEquals(0, dao.casCalls);
        assertEquals(PluginRuntimeSetting.DEFAULT_MAX_RUNNING_PLUGINS,
                updated.getSetting().getRuntime().getMaxRunningPlugins().longValue());
    }

    @Test
    public void shouldCasWhenUpdateChangesJson() {
        String raw = gson.toJson(new PluginCore());
        FakePluginCoreDAO dao = new FakePluginCoreDAO(raw);

        PluginCore updated = dao.update(pluginCore ->
                pluginCore.getSetting().setDisableAutoDownloadLostFile(true));

        assertEquals(1, dao.loadCalls);
        assertEquals(1, dao.casCalls);
        assertEquals(raw, dao.casExpectedValue);
        assertEquals("remark-1", dao.casExpectedRemark);
        assertTrue(updated.getSetting().isDisableAutoDownloadLostFile());
        assertEquals(gson.toJson(updated), dao.casValue);
    }

    @Test
    public void shouldNormalizeRuntimeLimitsBeforePersisting() {
        String raw = gson.toJson(new PluginCore());
        FakePluginCoreDAO dao = new FakePluginCoreDAO(raw);

        PluginCore updated = dao.update(pluginCore ->
                pluginCore.getSetting().getRuntime().setMaxRunningPlugins(Long.MAX_VALUE));

        assertEquals(1, dao.casCalls);
        assertEquals(PluginRuntimeSetting.MAX_MAX_RUNNING_PLUGINS,
                updated.getSetting().getRuntime().getMaxRunningPlugins().longValue());
        assertEquals(gson.toJson(updated), dao.casValue);
    }

    @Test
    public void shouldPersistPluginCoreJsonWithH2Database() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open()) {
            PluginCoreDAO dao = new PluginCoreDAO();

            PluginCore updated = dao.update(pluginCore ->
                    pluginCore.getSetting().setDisableAutoDownloadLostFile(true));

            assertTrue(updated.getSetting().isDisableAutoDownloadLostFile());
            assertTrue(dao.loadSnapshot().getSetting().isDisableAutoDownloadLostFile());
        }
    }

    private static class FakePluginCoreDAO extends PluginCoreDAO {

        private String raw;
        private int loadCalls;
        private int casCalls;
        private String casExpectedValue;
        private String casExpectedRemark;
        private String casValue;

        private FakePluginCoreDAO(String raw) {
            this.raw = raw;
        }

        @Override
        protected WebSiteDAO.WebSiteValueSnapshot getPluginCoreRawByDb() {
            loadCalls++;
            return new WebSiteDAO.WebSiteValueSnapshot(Optional.ofNullable(raw), "remark-1");
        }

        @Override
        protected boolean compareAndSetPluginCore(String expectedValue, String expectedRemark, String value) throws SQLException {
            casCalls++;
            casExpectedValue = expectedValue;
            casExpectedRemark = expectedRemark;
            casValue = value;
            raw = value;
            return true;
        }
    }
}
