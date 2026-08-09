package com.zrlog.plugincore.server.dao;

import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase.DatabaseType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class WebSiteDAOTest {

    private final DatabaseType databaseType;

    public WebSiteDAOTest(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    @Parameterized.Parameters(name = "{0}")
    public static DatabaseType[] databases() {
        return DatabaseType.values();
    }

    @Test
    public void shouldTreatStoredTextAndTypedValueAsSame() {
        assertTrue(WebSiteDAO.sameStoredValue("true", Boolean.TRUE));
        assertTrue(WebSiteDAO.sameStoredValue("TRUE", Boolean.TRUE));
        assertTrue(WebSiteDAO.sameStoredValue("10", 10));
    }

    @Test
    public void shouldGenerateCasVersionRemark() {
        assertTrue(WebSiteDAO.nextVersionRemark().startsWith("cas:"));
    }

    @Test
    public void shouldFilterUnchangedValues() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("flag", "true");
        existing.put("title", "Old");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flag", Boolean.TRUE);
        values.put("title", "New");
        values.put("missing", "value");

        Map<String, Object> changedValues = WebSiteDAO.changedValues(existing, values);

        assertEquals(2, changedValues.size());
        assertEquals("New", changedValues.get("title"));
        assertEquals("value", changedValues.get("missing"));
    }

    @Test
    public void shouldReadAndWriteWebsiteRowsWithSupportedDatabase() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            WebSiteDAO dao = new WebSiteDAO();

            assertTrue(dao.saveOrUpdateChanged("title", "ZrLog"));
            assertTrue(dao.saveOrUpdateChanged("enabled", Boolean.TRUE));

            assertEquals("ZrLog", dao.queryValueByName("title"));
            Map<String, Object> values = dao.getWebSiteByNameIn(Collections.singletonList("enabled"));
            assertEquals("true", String.valueOf(values.get("enabled")).toLowerCase());
        }
    }

    @Test
    public void shouldCompareAndSetWebsiteValueWithSupportedDatabase() throws Exception {
        try (InMemoryPluginCoreDatabase ignored = InMemoryPluginCoreDatabase.open(databaseType)) {
            WebSiteDAO dao = new WebSiteDAO();
            assertTrue(dao.saveOrUpdateVersioned("runtime.setting", "v1"));

            WebSiteDAO.WebSiteValueSnapshot snapshot = dao.queryValueSnapshotByName("runtime.setting");
            assertEquals(Optional.of("v1"), snapshot.getValue());
            assertTrue(dao.compareAndSet("runtime.setting", "v1", snapshot.getRemark(), "v2"));
            assertFalse(dao.compareAndSet("runtime.setting", "v1", snapshot.getRemark(), "v3"));
            assertEquals("v2", dao.queryValueByName("runtime.setting"));
        }
    }
}
