package com.zrlog.plugincore.server.runtime.pwa;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class PluginPwaManifest {

    private String id;
    private String name;
    @SerializedName("short_name")
    private String shortName;
    private String description;
    @SerializedName("start_url")
    private String startUrl;
    private String scope;
    private String display;
    @SerializedName("theme_color")
    private String themeColor;
    @SerializedName("background_color")
    private String backgroundColor;
    private List<Icon> icons;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStartUrl() {
        return startUrl;
    }

    public void setStartUrl(String startUrl) {
        this.startUrl = startUrl;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getDisplay() {
        return display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public String getThemeColor() {
        return themeColor;
    }

    public void setThemeColor(String themeColor) {
        this.themeColor = themeColor;
    }

    public String getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public List<Icon> getIcons() {
        return icons;
    }

    public void setIcons(List<Icon> icons) {
        this.icons = icons;
    }

    public static class Icon {

        private String src;
        private String sizes;
        private String type;

        public String getSrc() {
            return src;
        }

        public void setSrc(String src) {
            this.src = src;
        }

        public String getSizes() {
            return sizes;
        }

        public void setSizes(String sizes) {
            this.sizes = sizes;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }
}
