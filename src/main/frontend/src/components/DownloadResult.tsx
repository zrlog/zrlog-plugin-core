import {getRes} from "../i18n/plugin";
import React, {useEffect, useState} from "react";
import {Button, Result} from "antd";


const DownloadResult: React.FC = () => {
    const [viewLink, setViewLink] = useState<string>("");
    const [message, setMessage] = useState<string>("");

    useEffect(() => {
        const query = new URLSearchParams(window.location.search);
        const message = query.get("message");
        const pluginName = query.get("pluginName");
        setMessage(message || "");
        setViewLink(pluginName ? `${pluginName}/` : "");
    }, []);

    return (
        <Result
            status="success"
            title={message}
            subTitle=""
            extra={
                <a href={viewLink}>
                    <Button type="primary">{getRes().downloadResult.view}</Button>
                </a>
            }
        />
    );
};

export default DownloadResult;