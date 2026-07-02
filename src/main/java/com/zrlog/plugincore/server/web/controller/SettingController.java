package com.zrlog.plugincore.server.web.controller;


import com.hibegin.http.annotation.ResponseBody;
import com.hibegin.http.server.web.Controller;
import com.zrlog.plugincore.server.vo.PluginCoreSetting;
import com.zrlog.plugincore.server.dao.PluginCoreDAO;

import java.sql.SQLException;

public class SettingController extends Controller {

    @ResponseBody
    public PluginCoreSetting load() throws SQLException {
        return PluginCoreDAO.getInstance().loadSnapshot().getSetting();
    }

    @ResponseBody
    public PluginApiModels.ActionResponse update() throws SQLException {
        PluginCoreDAO.getInstance().update(pluginCore -> pluginCore.getSetting().setDisableAutoDownloadLostFile(request.getParaToBool(
                "disableAutoDownloadLostFile")));
        return PluginApiModels.ActionResponse.success("成功");
    }
}
