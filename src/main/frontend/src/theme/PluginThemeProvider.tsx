import type {PropsWithChildren} from "react";
import {App, ConfigProvider, Layout, theme} from "antd";
import type {ConfigProviderProps} from "antd";
import {ThemeStyles, useUiTheme} from "@zrlog/ui";
import type {UiThemeOptions} from "@zrlog/ui";
import {legacyLogicalPropertiesTransformer, StyleProvider} from "@ant-design/cssinjs";

const PluginContent = ({children}: PropsWithChildren) => {
    const {token} = theme.useToken();
    return <Layout.Content style={{minHeight: "100vh", background: token.colorBgLayout, color: token.colorText}}>
        <App>{children}</App>
    </Layout.Content>;
};

export const PluginThemeProvider = ({appearance, locale, children}: PropsWithChildren<{
    appearance: UiThemeOptions;
    locale: ConfigProviderProps["locale"];
}>) => {
    const config = useUiTheme(appearance);
    return <ConfigProvider
        {...config}
        locale={locale}
        divider={{style: {margin: "16px 0"}}}
        table={{style: {whiteSpace: "nowrap"}}}
        drawer={{closable: {placement: "end"}}}
    >
        <StyleProvider transformers={[legacyLogicalPropertiesTransformer]}>
            <ThemeStyles theme={appearance.theme}/>
            <PluginContent>{children}</PluginContent>
        </StyleProvider>
    </ConfigProvider>;
};
