import {getRes} from "../i18n/plugin";
import React from "react";
import { Result, Button } from "antd";

interface UnknownErrorPageProps {
    message: string;
}

const UnknownErrorPage: React.FC<UnknownErrorPageProps> = ({ message }) => {
    const getSecondTitle = () => message;

    return (
        <Result
            status="500"
            title="500"
            subTitle={getSecondTitle()}
            extra={<Button type="primary">{getRes().error.unknown}</Button>}
        />
    );
};

export default UnknownErrorPage;