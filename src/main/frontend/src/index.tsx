/* eslint-disable */
import {createRoot} from "react-dom/client";
import * as serviceWorker from './serviceWorker';
import zh_CN from "antd/es/locale/zh_CN";
import {legacyLogicalPropertiesTransformer, StyleProvider} from "@ant-design/cssinjs";
import {useCallback, useEffect, useState} from "react";
import {App, ConfigProvider, Layout, theme} from "antd";
import {BrowserRouter} from "react-router-dom";
import AppBase from "./AppBase";
import axios from "axios";
import {apiPath} from "./api";

const {darkAlgorithm, defaultAlgorithm} = theme;
const {Content} = Layout;

export interface PluginCoreInfoResponse {
    pluginBuildId: string
    pluginBuildNumber: string
    pluginVersion: string
    pluginCenter: string
    dark: boolean
    primaryColor: string
    pluginMetadataReady?: boolean
    pluginMetadataLoading?: boolean
    plugins: Plugin[];
    requiredPlugins: string[]
    setting?: PluginCoreSetting
}

export interface PluginCoreSetting {
    disableAutoDownloadLostFile?: boolean
    runtime?: PluginRuntimeSetting
}

export interface PluginRuntimeSetting {
    onDemandEnabled?: boolean
    autoDownloadMissingPluginFileEnabled?: boolean
    idleStopEnabled?: boolean
    idleTimeoutSeconds?: number
    idleScanIntervalSeconds?: number
    maxRunningPlugins?: number
    maxConcurrentStarts?: number
    startFailureBackoffSeconds?: number
}

export interface Plugin {
    id: string
    version: string
    name: string
    paths: string[]
    actions: any[]
    desc: string
    author: string
    shortName: string
    indexPage: string
    previewImageBase64: string
    services: string[]
    capabilities?: PluginCapability[]
    dependentService: string[]
}

export interface PluginCapability {
    key: string
    label?: string
    type?: string
}

const covertData = (data: PluginCoreInfoResponse) => {
    let locationHref = window.location.href;
    if (locationHref.endsWith("/")) {
        locationHref = locationHref.substring(0, locationHref.length - 1);
    }
    return {
        ...data,
        pluginCenter: data.pluginCenter.replace(`#locationHref`, locationHref)
    }
}

const loadFromDocument = () => {
    try {
        const a = document.getElementById("pluginInfo");
        if (a === null || a.innerText.length === 0) {
            return null;
        }
        return covertData(JSON.parse(a.innerText));
    } catch (e) {
        return null;
    }
}

const Index = () => {
    const [pluginInfo, setPluginInfo] = useState<PluginCoreInfoResponse | null>(loadFromDocument);

    const reloadPluginInfo = useCallback(async () => {
        const {data} = await axios.get(apiPath("/plugins"));
        setPluginInfo(covertData(data));
    }, []);

    useEffect(() => {
        if (pluginInfo === null) {
            reloadPluginInfo();
        }
    }, [pluginInfo, reloadPluginInfo]);

    useEffect(() => {
        if (!pluginInfo?.pluginMetadataLoading) {
            return;
        }
        const timer = window.setTimeout(reloadPluginInfo, 1500);
        return () => window.clearTimeout(timer);
    }, [pluginInfo?.pluginMetadataLoading, reloadPluginInfo]);

    if (pluginInfo === null) {
        return <></>
    }

    return (
        <ConfigProvider
            locale={zh_CN}
            theme={{
                algorithm: pluginInfo.dark ? darkAlgorithm : defaultAlgorithm,
                token: {
                    colorPrimary: pluginInfo.primaryColor
                }
            }}
            divider={{
                style: {
                    margin: "16px 0px"
                }
            }}
            table={
                {
                    style: {
                        whiteSpace: "nowrap"
                    },
                }}
        >
            <BrowserRouter>
                <StyleProvider transformers={[legacyLogicalPropertiesTransformer]}>
                    <Content style={{minHeight: "100vh", backgroundColor: pluginInfo.dark ? "#141414" : undefined, color: pluginInfo.dark ? "#dfdfdf" : undefined}}>
                        <App>
                            <AppBase pluginInfo={pluginInfo} onPluginInfoRefresh={reloadPluginInfo}/>
                        </App>
                    </Content>
                </StyleProvider>
            </BrowserRouter>
        </ConfigProvider>
    );
};

const container = document.getElementById("app");
const root = createRoot(container!); // createRoot(container!) if you use TypeScript
root.render(<Index/>);
// If you want your app to work offline and load faster, you can change
// unregister() to register() below. Note this comes with some pitfalls.
// Learn more about service workers: http://bit.ly/CRA-PWA
serviceWorker.unregister();
