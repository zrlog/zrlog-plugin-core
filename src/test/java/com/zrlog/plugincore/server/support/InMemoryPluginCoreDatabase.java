package com.zrlog.plugincore.server.support;

import com.hibegin.common.dao.InMemoryDatabase;
import com.hibegin.common.dao.SqlConvertUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public final class InMemoryPluginCoreDatabase implements AutoCloseable {

    private final InMemoryDatabase database;
    private final Path sqliteFile;

    private InMemoryPluginCoreDatabase(DatabaseType databaseType) throws Exception {
        this.sqliteFile = databaseType == DatabaseType.SQLITE
                ? Files.createTempFile("plugin-core-test-", ".db")
                : null;
        this.database = openDatabase(databaseType, sqliteFile);
        createSchema();
    }

    public static InMemoryPluginCoreDatabase open() throws Exception {
        return open(DatabaseType.H2);
    }

    public static InMemoryPluginCoreDatabase open(DatabaseType databaseType) throws Exception {
        return new InMemoryPluginCoreDatabase(databaseType);
    }

    private static InMemoryDatabase openDatabase(DatabaseType databaseType, Path sqliteFile) {
        if (databaseType == DatabaseType.H2) {
            return InMemoryDatabase.openH2("plugin_core_" + UUID.randomUUID());
        }
        Properties properties = new Properties();
        properties.setProperty("driverClass", "org.sqlite.JDBC");
        properties.setProperty("jdbcUrl", "jdbc:sqlite:" + sqliteFile.toAbsolutePath().normalize()
                + "?journal_mode=WAL&busy_timeout=10000&foreign_keys=on&synchronous=NORMAL"
                + "&date_class=TEXT&date_string_format=yyyy-MM-dd HH:mm:ss");
        properties.setProperty("user", "");
        properties.setProperty("password", "");
        return InMemoryDatabase.open(properties, true);
    }

    private void createSchema() throws SQLException {
        String schema = "CREATE TABLE website (\n"
                + "  `name` varchar(191) NOT NULL,\n"
                + "  `value` longtext,\n"
                + "  `remark` varchar(255),\n"
                + "  PRIMARY KEY (`name`)\n"
                + ");\n"
                + "CREATE TABLE log (\n"
                + "  `logId` int(11) NOT NULL AUTO_INCREMENT,\n"
                + "  `title` varchar(255),\n"
                + "  `alias` varchar(64),\n"
                + "  `extensions` longtext,\n"
                + "  PRIMARY KEY (`logId`)\n"
                + ");\n"
                + "CREATE TABLE log_extension_index (\n"
                + "  `id` int(11) NOT NULL AUTO_INCREMENT,\n"
                + "  `log_id` int NOT NULL,\n"
                + "  `namespace` varchar(64) NOT NULL,\n"
                + "  `extension_path` varchar(191) NOT NULL,\n"
                + "  `extension_value` varchar(512),\n"
                + "  PRIMARY KEY (`id`)\n"
                + ");\n"
                + "CREATE INDEX log_extension_article ON log_extension_index(log_id, namespace);\n"
                + "CREATE INDEX log_extension_filter ON log_extension_index(namespace, extension_path, extension_value);\n";
        List<String> statements = sqliteFile == null
                ? SqlConvertUtils.doMySQLToH2BySqlText(schema)
                : SqlConvertUtils.doMySQLToSqliteBySqlText(schema);
        database.executeStatements(statements);
    }

    public int update(String sql, Object... params) throws SQLException {
        return database.update(sql, params);
    }

    public Map<String, Object> queryOne(String sql, Object... params) throws SQLException {
        return database.queryOne(sql, params);
    }

    @Override
    public void close() {
        try {
            database.close();
        } finally {
            deleteIfExists(sqliteFile);
            deleteIfExists(sqliteFile == null ? null : Path.of(sqliteFile + "-wal"));
            deleteIfExists(sqliteFile == null ? null : Path.of(sqliteFile + "-shm"));
        }
    }

    private static void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            throw new IllegalStateException("Delete SQLite test database failed: " + path, e);
        }
    }

    public enum DatabaseType {
        H2,
        SQLITE
    }
}
