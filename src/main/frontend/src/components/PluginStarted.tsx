import {getRes} from "../i18n/plugin";
import React from "react";
import { Result, Button } from "antd";

const PluginStarted: React.FC = () => {
    return (
        <Result
            status="error"
            title={getRes().pluginStarted.title}
            subTitle=""
            extra={<Button type="primary">{getRes().common.goBack}</Button>}
        />
    );
};

export default PluginStarted;