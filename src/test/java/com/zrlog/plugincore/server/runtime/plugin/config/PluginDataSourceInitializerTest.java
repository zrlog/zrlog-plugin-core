package com.zrlog.plugincore.server.runtime.plugin.config;

import com.hibegin.common.dao.DAO;
import com.hibegin.common.dao.DataSourceWrapper;
import com.zrlog.plugincore.server.dao.WebSiteDAO;
import com.zrlog.plugincore.server.support.InMemoryPluginCoreDatabase.DatabaseType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class PluginDataSourceInitializerTest {

    private final DatabaseType databaseType;

    public PluginDataSourceInitializerTest(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    @Parameterized.Parameters(name = "{0}")
    public static DatabaseType[] databases() {
        return DatabaseType.values();
    }

    @Test
    public void shouldAppendAutoReconnectOnlyToMySqlUrls() {
        assertEquals("jdbc:mysql://localhost/zrlog?autoReconnect=true",
                PluginDataSourceInitializer.withMySqlReconnect("jdbc:mysql://localhost/zrlog"));
        assertEquals("jdbc:mysql://localhost/zrlog?useSSL=false&autoReconnect=true",
                PluginDataSourceInitializer.withMySqlReconnect("jdbc:mysql://localhost/zrlog?useSSL=false"));
        assertEquals("jdbc:mysql://localhost/zrlog?autoReconnect=false",
                PluginDataSourceInitializer.withMySqlReconnect("jdbc:mysql://localhost/zrlog?autoReconnect=false"));
        assertEquals("jdbc:sqlite:/tmp/zrlog.db?_journal_mode=WAL",
                PluginDataSourceInitializer.withMySqlReconnect("jdbc:sqlite:/tmp/zrlog.db?_journal_mode=WAL"));
        assertEquals("jdbc:webapi://example.com/zrlog",
                PluginDataSourceInitializer.withMySqlReconnect("jdbc:webapi://example.com/zrlog"));
        assertEquals("jdbc:h2:mem:zrlog",
                PluginDataSourceInitializer.withMySqlReconnect("jdbc:h2:mem:zrlog"));
    }

    @Test
    public void shouldInitializeSupportedDatabaseFromProperties() throws Exception {
        Path databaseFile = databaseType == DatabaseType.SQLITE
                ? Files.createTempFile("plugin-core-initializer-", ".db")
                : null;
        Path propertiesFile = Files.createTempFile("plugin-core-db-", ".properties");
        String jdbcUrl = databaseType == DatabaseType.SQLITE
                ? "jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize()
                : "jdbc:h2:mem:plugin_initializer_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,COMMENT,TYPE;DB_CLOSE_DELAY=-1";
        String driverClass = databaseType == DatabaseType.SQLITE ? "org.sqlite.JDBC" : "org.h2.Driver";
        String schema = databaseType == DatabaseType.SQLITE
                ? "create table website (siteId integer primary key autoincrement, `name` varchar(191) not null unique,"
                + " `value` text, remark varchar(255))"
                : "create table website (siteId int auto_increment primary key, `name` varchar(191) not null unique,"
                + " `value` longtext, remark varchar(255))";

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(schema);
        }

        Properties properties = new Properties();
        properties.setProperty("driverClass", driverClass);
        properties.setProperty("jdbcUrl", jdbcUrl);
        properties.setProperty("user", "");
        properties.setProperty("password", "");
        try (OutputStream outputStream = Files.newOutputStream(propertiesFile)) {
            properties.store(outputStream, null);
        }

        DataSourceWrapper previousDataSource = DAO.getDefaultDataSource();
        try {
            new PluginDataSourceInitializer().initialize(propertiesFile.toFile());

            WebSiteDAO dao = new WebSiteDAO();
            assertTrue(dao.saveOrUpdateChanged("plugin.initializer", databaseType.name()));
            assertEquals(databaseType.name(), dao.queryValueByName("plugin.initializer"));
        } finally {
            DataSourceWrapper initializedDataSource = DAO.getDefaultDataSource();
            if (initializedDataSource != null && initializedDataSource != previousDataSource) {
                initializedDataSource.close();
            }
            DAO.setDs(previousDataSource);
            Files.deleteIfExists(propertiesFile);
            deleteIfExists(databaseFile == null ? null : Path.of(databaseFile + "-wal"));
            deleteIfExists(databaseFile == null ? null : Path.of(databaseFile + "-shm"));
            deleteIfExists(databaseFile);
        }
    }

    private static void deleteIfExists(Path path) throws Exception {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }
}
