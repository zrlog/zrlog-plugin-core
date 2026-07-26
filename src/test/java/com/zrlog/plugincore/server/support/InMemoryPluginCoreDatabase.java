package com.zrlog.plugincore.server.support;

import com.hibegin.common.dao.InMemoryDatabase;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public final class InMemoryPluginCoreDatabase implements AutoCloseable {

    private final InMemoryDatabase database;

    private InMemoryPluginCoreDatabase() throws SQLException {
        this.database = InMemoryDatabase.openH2("plugin_core_" + UUID.randomUUID());
        createSchema();
    }

    public static InMemoryPluginCoreDatabase open() throws SQLException {
        return new InMemoryPluginCoreDatabase();
    }

    private void createSchema() throws SQLException {
        database.update("create table website ("
                + "`name` varchar(191) not null primary key,"
                + "`value` longtext,"
                + "`remark` varchar(255)"
                + ")");
        database.update("create table log ("
                + "`logId` int auto_increment primary key,"
                + "`title` varchar(255),"
                + "`alias` varchar(64),"
                + "`extensions` longtext"
                + ")");
        database.update("create table log_extension_index ("
                + "`id` bigint auto_increment primary key,"
                + "`log_id` int not null,"
                + "`namespace` varchar(64) not null,"
                + "`extension_path` varchar(191) not null,"
                + "`extension_value` varchar(512)"
                + ")");
        database.update("create index log_extension_article on log_extension_index(log_id, namespace)");
        database.update("create index log_extension_filter on log_extension_index(namespace, extension_path, extension_value)");
    }

    public int update(String sql, Object... params) throws SQLException {
        return database.update(sql, params);
    }

    public Map<String, Object> queryOne(String sql, Object... params) throws SQLException {
        return database.queryOne(sql, params);
    }

    @Override
    public void close() {
        database.close();
    }
}
