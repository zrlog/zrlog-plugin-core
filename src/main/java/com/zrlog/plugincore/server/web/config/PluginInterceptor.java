package com.zrlog.plugincore.server.web.config;

import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpResponse;
import com.hibegin.http.server.api.Interceptor;
import com.zrlog.plugincore.server.util.PluginI18n;

public class PluginInterceptor implements Interceptor {
    @Override
    public boolean doInterceptor(HttpRequest httpRequest, HttpResponse httpResponse) {
        if(httpRequest.getUri().startsWith("/static/") || httpRequest.getUri().startsWith("/admin/plugins/static/")){
            httpResponse.addHeader("Cache-Control", "max-age=31536000, immutable"); // 1 年的秒数
        } else {
            PluginI18n.setLanguage(PluginI18n.resolveLanguage(httpRequest));
            httpResponse.addHeader("Content-Language", PluginI18n.getLanguage().replace('_', '-'));
        }
        httpResponse.addHeader("Connection", "close");
        return true;
    }
}
