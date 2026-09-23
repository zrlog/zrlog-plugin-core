import {getRes} from "../../i18n/plugin";
import React, {useEffect, useState} from "react";
import {Button, Grid, Space, Table, Tag, Tooltip, Typography, message} from "antd";
import {CheckOutlined, ReloadOutlined, SendOutlined} from "@ant-design/icons";
import type {ColumnsType} from "antd/es/table";
import axios from "axios";
import {apiPath, Capability, formatEpoch, formatTime, NotificationDelivery, NotificationProviderRow, paginationFromResponse, rowsFromResponse, RuntimePagination, useCapabilityView} from "./common";

const {Text} = Typography;

const NotificationRuntimeTab: React.FC = () => {
    const screens = Grid.useBreakpoint();
    const isMobile = Boolean((screens.xs || screens.sm) && !screens.md);
    const [messageApi, contextHolder] = message.useMessage({maxCount: 3});
    const [loading, setLoading] = useState(false);
    const [capabilities, setCapabilities] = useState<Capability[]>([]);
    const [providers, setProviders] = useState<NotificationProviderRow[]>([]);
    const [deliveries, setDeliveries] = useState<NotificationDelivery[]>([]);
    const [deliveryPagination, setDeliveryPagination] = useState<RuntimePagination>({
        current: 1,
        pageSize: 8,
        total: 0
    });
    const {renderCapability} = useCapabilityView(capabilities);

    const loadData = async (deliveryPage = deliveryPagination.current, deliveryPageSize = deliveryPagination.pageSize) => {
        setLoading(true);
        try {
            const [capabilitiesRes, providersRes, deliveriesRes] = await Promise.all([
                axios.get(apiPath("/runtime-capabilities")),
                axios.get(apiPath("/runtime-notification/channels")),
                axios.get(apiPath("/runtime-notification/deliveries"), {params: {page: deliveryPage, pageSize: deliveryPageSize}})
            ]);
            setCapabilities(capabilitiesRes.data.items || []);
            setProviders(providersRes.data.items || []);
            setDeliveries(rowsFromResponse<NotificationDelivery>(deliveriesRes.data));
            setDeliveryPagination(paginationFromResponse<NotificationDelivery>(deliveriesRes.data, {
                current: deliveryPage,
                pageSize: deliveryPageSize,
                total: 0
            }));
        } catch (e) {
            messageApi.error(getRes().runtimeNotification.loadError);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadData();
    }, []);

    const setDefaultProvider = async (row: NotificationProviderRow) => {
        const params = new URLSearchParams();
        params.set("channel", row.channel);
        params.set("pluginId", row.providerPluginId);
        params.set("capabilityKey", row.capabilityKey);
        const {data: resp} = await axios.post(apiPath("/runtime-notification/provider"), params.toString());
        if (resp.code > 0) {
            messageApi.error(resp.message);
            return;
        }
        messageApi.success(getRes().common.saved);
        await loadData(deliveryPagination.current, deliveryPagination.pageSize);
    };

    const restoreAutoProvider = async (channel: string) => {
        const params = new URLSearchParams();
        params.set("channel", channel);
        const {data: resp} = await axios.post(apiPath("/runtime-notification/provider/auto"), params.toString());
        if (resp.code > 0) {
            messageApi.error(resp.message);
            return;
        }
        messageApi.success(getRes().common.autoRestored);
        await loadData(deliveryPagination.current, deliveryPagination.pageSize);
    };

    const testProvider = async (row: NotificationProviderRow) => {
        const params = new URLSearchParams();
        params.set("channel", row.channel);
        params.set("pluginId", row.providerPluginId);
        params.set("capabilityKey", row.capabilityKey);
        const {data: resp} = await axios.post(apiPath("/runtime-notification/test"), params.toString());
        if (resp.code > 0 || !resp.success) {
            messageApi.error(resp.message || resp.delivery?.errorMessage || getRes().runtimeNotification.testError);
            await loadData(deliveryPagination.current, deliveryPagination.pageSize);
            return;
        }
        messageApi.success(getRes().runtimeNotification.testSent);
        await loadData(1, deliveryPagination.pageSize);
    };

    const statusTags = (selected: boolean, reviewRequired: boolean, confirmed: boolean) => (
        <Space size={[4, 4]} wrap>
            {selected && <Tag color="success">{getRes().runtimeNotification.default}</Tag>}
            {reviewRequired && <Tag color="warning">{getRes().common.reviewRequired}</Tag>}
            {confirmed && <Tag color="blue">{getRes().common.confirmed}</Tag>}
        </Space>
    );

    const deliveryStatusTag = (value?: string) => value === "success" ? <Tag color="success">{getRes().common.success}</Tag> : <Tag color="error">{getRes().common.failure}</Tag>;

    const providerDeliveryCell = (record: NotificationProviderRow) => {
        if (!record.lastDeliveryStatus) {
            return <Text type="secondary">{getRes().runtimeNotification.none}</Text>;
        }
        return (
            <Space direction="vertical" size={4} style={{width: "100%", minWidth: 0}}>
                <Space size={[4, 4]} wrap>
                    {deliveryStatusTag(record.lastDeliveryStatus)}
                    {record.lastDeliveryAt && <Text type="secondary" style={{fontSize: 12}}>{formatEpoch(record.lastDeliveryAt)}</Text>}
                </Space>
                {record.lastDeliveryError && (
                    <Text type="danger" ellipsis style={{fontSize: 12, maxWidth: "100%"}}>
                        {record.lastDeliveryError}
                    </Text>
                )}
            </Space>
        );
    };

    const providerCell = (record: NotificationProviderRow) => (
        <Space direction="vertical" size={8} style={{width: "100%", minWidth: 0}}>
            {renderCapability(record.providerPluginId, record.capabilityKey, record.capabilityLabel, record.providerPluginPreviewImageBase64, record.providerPluginName)}
            {isMobile && (
                <Space direction="vertical" size={4}>
                    <Text type="secondary" style={{fontSize: 12}}>{getRes().runtimeNotification.channel}{" "}{record.channel}</Text>
                    {statusTags(record.selected, record.reviewRequired, record.confirmed)}
                    {providerDeliveryCell(record)}
                </Space>
            )}
        </Space>
    );

    const providerColumns: ColumnsType<NotificationProviderRow> = [
        {title: getRes().runtimeNotification.channel, dataIndex: "channel", width: 120, responsive: ["md"]},
        {
            title: getRes().runtimeNotification.plugin,
            key: "provider",
            render: (_, record) => providerCell(record)
        },
        {
            title: getRes().common.status,
            key: "status",
            width: 180,
            responsive: ["md"],
            render: (_, record) => statusTags(record.selected, record.reviewRequired, record.confirmed)
        },
        {
            title: getRes().runtimeNotification.latestStatus,
            key: "lastDelivery",
            width: 220,
            responsive: ["md"],
            render: (_, record) => providerDeliveryCell(record)
        },
        {
            title: getRes().common.actions,
            key: "action",
            width: isMobile ? 128 : 270,
            render: (_, record) => (
                <Space size={isMobile ? 2 : "small"}>
                    <Tooltip title={getRes().runtimeNotification.sendTest}>
                        <Button type={isMobile ? "text" : "link"} size="small" icon={<SendOutlined />} aria-label={getRes().runtimeNotification.sendTest} onClick={() => testProvider(record)}>
                            {!isMobile && getRes().runtimeNotification.test}
                        </Button>
                    </Tooltip>
                    <Tooltip title={getRes().common.setDefault}>
                        <Button type={isMobile ? "text" : "link"} size="small" icon={<CheckOutlined />} aria-label={getRes().common.setDefault} disabled={record.confirmed} onClick={() => setDefaultProvider(record)}>
                            {!isMobile && getRes().common.setDefault}
                        </Button>
                    </Tooltip>
                    <Tooltip title={getRes().common.autoSelect}>
                        <Button type={isMobile ? "text" : "link"} size="small" icon={<ReloadOutlined />} aria-label={getRes().common.autoSelect} onClick={() => restoreAutoProvider(record.channel)}>
                            {!isMobile && getRes().common.autoSelect}
                        </Button>
                    </Tooltip>
                </Space>
            )
        }
    ];

    const deliveryCell = (record: NotificationDelivery) => (
        <Space direction="vertical" size={8} style={{width: "100%", minWidth: 0}}>
            {renderCapability(record.providerPluginId, record.capabilityKey, undefined, record.providerPluginPreviewImageBase64, record.providerPluginName)}
            {isMobile && (
                <Space direction="vertical" size={4} style={{width: "100%"}}>
                    <Space size={[4, 4]} wrap>
                        {deliveryStatusTag(record.status)}
                        <Tag>{record.channel}</Tag>
                    </Space>
                    <Text type="secondary" style={{fontSize: 12}}>{formatEpoch(record.createdAt)}</Text>
                    {record.errorMessage && (
                        <Text type="danger" ellipsis style={{fontSize: 12, maxWidth: "100%"}}>
                            {record.errorMessage}
                        </Text>
                    )}
                </Space>
            )}
        </Space>
    );

    const deliveryColumns: ColumnsType<NotificationDelivery> = [
        {title: getRes().runtimeNotification.channel, dataIndex: "channel", width: 120, responsive: ["md"]},
        {title: getRes().runtimeNotification.source, key: "capability", render: (_, record) => deliveryCell(record)},
        {
            title: getRes().common.status,
            dataIndex: "status",
            width: 100,
            responsive: ["md"],
            render: deliveryStatusTag
        },
        {title: getRes().runtimeNotification.time, dataIndex: "createdAt", width: 240, render: formatEpoch, responsive: ["md"]},
        {title: getRes().common.error, dataIndex: "errorMessage", render: formatTime, responsive: ["md"]}
    ];

    return (
        <Space direction="vertical" size={16} style={{width: "100%"}}>
            {contextHolder}
            <Table<NotificationProviderRow> loading={loading} rowKey={record => `${record.channel}:${record.providerPluginId}:${record.capabilityKey}`} columns={providerColumns} dataSource={providers} pagination={false} scroll={isMobile ? undefined : {x: 760}} />
            <Text strong>{getRes().runtimeNotification.deliveries}</Text>
            <Table<NotificationDelivery>
                loading={loading}
                rowKey="id"
                columns={deliveryColumns}
                dataSource={deliveries}
                pagination={{...deliveryPagination, showSizeChanger: !isMobile}}
                onChange={pagination => loadData(pagination.current || 1, pagination.pageSize || 8)}
                scroll={isMobile ? undefined : {x: 760}}
            />
        </Space>
    );
};

export default NotificationRuntimeTab;
