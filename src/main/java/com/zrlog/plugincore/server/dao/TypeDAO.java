package com.zrlog.plugincore.server.dao;

import com.hibegin.common.dao.DAO;

import java.sql.SQLException;

public class TypeDAO extends DAO {

    public TypeDAO() {
        this.tableName = "type";
    }

    public Integer findByName(String type) throws SQLException {
        Number typeId = (Number) queryFirstObj("select typeId from type where typeName = ?", type);
        return typeId == null ? null : typeId.intValue();
    }
}
