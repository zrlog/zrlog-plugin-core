package com.zrlog.plugincore.server.dao;

import com.hibegin.common.dao.DAO;

import java.sql.SQLException;

public class TypeDAO extends DAO {

    public TypeDAO() {
        this.tableName = "type";
    }

    public Number findByName(String type) throws SQLException {
        return (Number) queryFirstObj("select typeId from type where typeName = ? ", type);
    }
}
