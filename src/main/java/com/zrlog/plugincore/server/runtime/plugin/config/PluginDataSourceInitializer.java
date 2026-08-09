package com.zrlog.plugincore.server.runtime.plugin.config;

import com.hibegin.common.dao.DAO;
import com.hibegin.common.dao.DataSourceWrapperImpl;
import com.hibegin.common.util.EnvKit;
import com.zrlog.plugin.common.LoggerUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;

public class PluginDataSourceInitializer {

    public void initialize(File dbPropertiesFile) {
        try (FileInputStream fis = new FileInputStream(dbPropertiesFile)) {
            Properties properties = new Properties();
            properties.load(fis);
            DataSourceWrapperImpl dataSource = new DataSourceWrapperImpl(properties, EnvKit.isDevMode());
            DAO.setDs(dataSource);
            String driverClass = properties.getProperty("driverClass");
            if (Objects.nonNull(driverClass)) {
                dataSource.setDriverClassName(driverClass);
            }
            dataSource.setJdbcUrl(withMySqlReconnect(properties.get("jdbcUrl").toString()));
            dataSource.setPassword(properties.get("password").toString());
            dataSource.setUsername(properties.get("user").toString());
        } catch (IOException e) {
            LoggerUtil.getLogger(PluginDataSourceInitializer.class).log(Level.SEVERE, "", e);
        }
    }

    static String withMySqlReconnect(String jdbcUrl) {
        if (!jdbcUrl.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())
                || jdbcUrl.toLowerCase(Locale.ROOT).contains("autoreconnect=")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "autoReconnect=true";
    }
}
