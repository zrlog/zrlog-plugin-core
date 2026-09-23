import {getRes} from "../../i18n/plugin";
import React, {useEffect, useState} from "react";
import {Button, Descriptions, Drawer, Grid, Popconfirm, Space, Table, Tag, Tooltip, Typography, message} from "antd";
import {InfoCircleOutlined, PoweroffOutlined} from "@ant-design/icons";
import type {ColumnsType} from "antd/es/table";
import axios from "axios";
import {apiPath, Capability, formatEpoch, formatTime, InvocationLog, paginationFromResponse, rowsFromResponse, RuntimeInstanceState, RuntimePagination, useCapabilityView} from "./common";

const {Text} = Typography;

type RuntimeStatesTabProps = {
    dark: boolean;
}

type RuntimeVisibleStatus = "stopped" | "starting" | "running" | "executing" | "failed";

const runtimeVisibleStatus = (record: RuntimeInstanceState): RuntimeVisibleStatus => {
    if (record.status === "failed") {
        return "failed";
    }
    if (Number(record.activeInvocationCount || 0) > 0 || record.effectiveStatus === "executing") {
        return "executing";
    }
    if (record.status === "starting" || record.status === "initializing") {
        return "starting";
    }
    if (record.status === "ready" || record.status === "idle") {
        return "running";
    }
    return "stopped";
};

const runtimeStatusLabel = (value: RuntimeVisibleStatus) => {
    const labels: Record<RuntimeVisibleStatus, string> = {
        stopped: getRes().runtimeStates.stopped,
        starting: getRes().runtimeStates.starting,
        running: getRes().runtimeStates.running,
        executing: getRes().runtimeStates.executing,
        failed: getRes().runtimeStates.failed
    };
    return labels[value];
};

const runtimeStatusColor = (value: RuntimeVisibleStatus) => {
    const colors: Record<RuntimeVisibleStatus, string> = {
        stopped: "default",
        starting: "processing",
        running: "success",
        executing: "processing",
        failed: "error"
    };
    return colors[value];
};

const formatBytes = (value?: number) => {
    if (value == null) {
        return "-";
    }
    const units = ["B", "KB", "MB", "GB", "TB"];
    let size = value;
    let unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
        size = size / 1024;
        unitIndex += 1;
    }
    const fixed = size >= 10 || unitIndex === 0 ? 0 : 1;
    return `${size.toFixed(fixed)} ${units[unitIndex]}`;
};

const formatDuration = (value?: number) => {
    if (value == null) {
        return "-";
    }
    if (value < 1000) {
        return `${value} ms`;
    }
    const seconds = Math.floor(value / 1000);
    if (seconds < 60) {
        return `${seconds} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    if (minutes < 60) {
        return `${minutes} m ${remainingSeconds} s`;
    }
    const hours = Math.floor(minutes / 60);
    return `${hours} h ${minutes % 60} m`;
};

const processAliveTag = (value?: boolean, key?: string) => {
    if (value == null) {
        return <Tag key={key}>{getRes().runtimeStates.unknown}</Tag>;
    }
    return value ? <Tag key={key} color="success">{getRes().runtimeStates.available}</Tag> : <Tag key={key} color="error">{getRes().runtimeStates.failed}</Tag>;
};

const pluginVersionLabel = (value?: string) => {
    const version = value?.trim();
    return version ? `v${version}` : undefined;
};

const RuntimeStatesTab: React.FC<RuntimeStatesTabProps> = () => {
    const screens = Grid.useBreakpoint();
    const isMobile = Boolean((screens.xs || screens.sm) && !screens.md);
    const [messageApi, contextHolder] = message.useMessage({maxCount: 3});
    const [loading, setLoading] = useState(false);
    const [stateActions, setStateActions] = useState<Record<string, boolean>>({});
    const [selectedState, setSelectedState] = useState<RuntimeInstanceState | null>(null);
    const [capabilities, setCapabilities] = useState<Capability[]>([]);
    const [states, setStates] = useState<RuntimeInstanceState[]>([]);
    const [invocationLogs, setInvocationLogs] = useState<InvocationLog[]>([]);
    const [invocationLogPagination, setInvocationLogPagination] = useState<RuntimePagination>({
        current: 1,
        pageSize: 10,
        total: 0
    });
    const {renderPlugin, renderCapability} = useCapabilityView(capabilities);

    const loadData = async (logPage = invocationLogPagination.current, logPageSize = invocationLogPagination.pageSize) => {
        setLoading(true);
        try {
            const [capabilitiesRes, statesRes, logsRes] = await Promise.all([
                axios.get(apiPath("/runtime-capabilities")),
                axios.get(apiPath("/runtime-states")),
                axios.get(apiPath("/runtime-invocation-logs"), {params: {page: logPage, pageSize: logPageSize}})
            ]);
            setCapabilities(capabilitiesRes.data.items || []);
            setStates(statesRes.data.items || []);
            setInvocationLogs(rowsFromResponse<InvocationLog>(logsRes.data));
            setInvocationLogPagination(paginationFromResponse<InvocationLog>(logsRes.data, {
                current: logPage,
                pageSize: logPageSize,
                total: 0
            }));
        } catch (e) {
            messageApi.error(getRes().runtimeStates.loadError);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadData();
    }, []);

    const stateActionKey = (record: RuntimeInstanceState, action: string) => `${record.pluginId}:${record.instanceId}:${action}`;

    const setStateActionLoading = (record: RuntimeInstanceState, action: string, loading: boolean) => {
        setStateActions(prev => ({...prev, [stateActionKey(record, action)]: loading}));
    };

    const stopPlugin = async (record: RuntimeInstanceState) => {
        setStateActionLoading(record, "stop", true);
        try {
            const params = new URLSearchParams();
            params.set("pluginId", record.pluginId);
            const {data} = await axios.post(apiPath("/runtime-states/stop"), params.toString());
            if (data.code > 0) {
                messageApi.error(data.message);
                return;
            }
            messageApi.success(getRes().runtimeStates.stopSuccess);
            await loadData(invocationLogPagination.current, invocationLogPagination.pageSize);
        } finally {
            setStateActionLoading(record, "stop", false);
        }
    };

    const stopDisabled = (record: RuntimeInstanceState) =>
        !record.local || !["ready", "idle"].includes(record.status) || Number(record.activeInvocationCount || 0) > 0;
    const runtimeStatusTag = (record: RuntimeInstanceState) => {
        const status = runtimeVisibleStatus(record);
        return <Tag color={runtimeStatusColor(status)}>{runtimeStatusLabel(status)}</Tag>;
    };
    const resourceSummary = (record: RuntimeInstanceState) => {
        const tags: React.ReactNode[] = [];
        if (record.processAlive != null) {
            tags.push(processAliveTag(record.processAlive, "alive"));
        }
        if (record.residentMemoryBytes != null) {
            tags.push(<Tag key="rss">RSS {formatBytes(record.residentMemoryBytes)}</Tag>);
        }
        if (record.heapUsedBytes != null) {
            tags.push(<Tag key="heap">{getRes().runtimeStates.heap}{" "}{formatBytes(record.heapUsedBytes)}</Tag>);
        }
        if (record.threadCount != null) {
            tags.push(<Tag key="threads">{getRes().runtimeStates.threads}{" "}{record.threadCount}</Tag>);
        }
        if (tags.length === 0) {
            return <Text type="secondary">-</Text>;
        }
        return <Space size={[4, 4]} wrap>{tags}</Space>;
    };
    const runtimePluginCell = (record: RuntimeInstanceState) => (
        <Space direction="vertical" size={8} style={{width: "100%", minWidth: 0}}>
            {renderPlugin(record.pluginId, record.pluginName, record.pluginPreviewImageBase64, undefined, pluginVersionLabel(record.pluginVersion))}
            {isMobile && (
                <Space direction="vertical" size={4} style={{width: "100%"}}>
                    <Space size={[4, 4]} wrap>
                        {runtimeStatusTag(record)}
                        <Tag style={{margin: 0}}>{getRes().runtimeStates.invocations}{" "}{record.activeInvocationCount || 0}</Tag>
                        {resourceSummary(record)}
                    </Space>
                    <Text type="secondary" style={{fontSize: 12}}>{getRes().runtimeStates.activity}{" "}{formatEpoch(record.lastActiveAt)}</Text>
                </Space>
            )}
        </Space>
    );
    const runtimeStatusCell = (record: RuntimeInstanceState) => (
        <Space direction="vertical" size={4}>
            {runtimeStatusTag(record)}
            <Tag style={{margin: 0}}>{getRes().runtimeStates.invocations}{" "}{record.activeInvocationCount || 0}</Tag>
        </Space>
    );

    const stateColumns: ColumnsType<RuntimeInstanceState> = [
        {
            title: getRes().common.plugin,
            key: "plugin",
            width: isMobile ? undefined : 260,
            render: (_, record) => (
                runtimePluginCell(record)
            )
        },
        {
            title: getRes().runtimeStates.status,
            key: "status",
            width: 130,
            responsive: ["md"],
            render: (_, record) => runtimeStatusCell(record)
        },
        {
            title: getRes().runtimeStates.resources,
            key: "resource",
            width: 260,
            responsive: ["md"],
            render: (_, record) => resourceSummary(record)
        },
        {title: getRes().runtimeStates.lastActive, dataIndex: "lastActiveAt", width: 180, render: formatEpoch, responsive: ["md"]},
        {
            title: getRes().common.actions,
            key: "action",
            width: isMobile ? 96 : 160,
            render: (_, record) => (
                <Space size={isMobile ? 2 : "small"}>
                    <Tooltip title={getRes().runtimeStates.details}>
                        <Button
                            type={isMobile ? "text" : "link"}
                            size="small"
                            icon={<InfoCircleOutlined />}
                            aria-label={getRes().runtimeStates.details}
                            onClick={() => setSelectedState(record)}
                        >
                            {!isMobile && getRes().runtimeStates.details}
                        </Button>
                    </Tooltip>
                    <Popconfirm title={getRes().runtimeStates.confirmStop} okText={getRes().runtimeStates.stop} cancelText={getRes().common.cancel} onConfirm={() => stopPlugin(record)}>
                        <Button
                            danger
                            type={isMobile ? "text" : "link"}
                            size="small"
                            icon={<PoweroffOutlined />}
                            aria-label={getRes().runtimeStates.stop}
                            disabled={stopDisabled(record)}
                            loading={stateActions[stateActionKey(record, "stop")]}
                        >
                            {!isMobile && getRes().runtimeStates.stop}
                        </Button>
                    </Popconfirm>
                </Space>
            )
        }
    ];
    const detailDrawer = (
        <Drawer
            title={getRes().runtimeStates.instanceTitle}
            open={Boolean(selectedState)}
            onClose={() => setSelectedState(null)}
            width={isMobile ? "100%" : 640}
        >
            {selectedState && (
                <Space direction="vertical" size={16} style={{width: "100%"}}>
                    {renderPlugin(selectedState.pluginId, selectedState.pluginName, selectedState.pluginPreviewImageBase64, undefined, pluginVersionLabel(selectedState.pluginVersion))}
                    <Descriptions bordered size="small" column={1}>
                        <Descriptions.Item label={getRes().runtimeStates.version}>{pluginVersionLabel(selectedState.pluginVersion) || "-"}</Descriptions.Item>
                        <Descriptions.Item label={getRes().common.status}>
                            <Space size={[4, 4]} wrap>
                                {runtimeStatusTag(selectedState)}
                                {selectedState.local ? <Tag color="success">{getRes().runtimeStates.local}</Tag> : <Tag>{getRes().runtimeStates.remote}</Tag>}
                                <Tag style={{margin: 0}}>{getRes().runtimeStates.invocations}{" "}{selectedState.activeInvocationCount || 0}</Tag>
                            </Space>
                        </Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.instance}>
                            <Text copyable ellipsis style={{maxWidth: "100%"}}>{selectedState.instanceId}</Text>
                        </Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.processId}>{selectedState.processId || "-"}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.mode}>{selectedState.runtimeMode || "-"}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.connectedAt}>{formatEpoch(selectedState.readyAt)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.lastActive}>{formatEpoch(selectedState.lastActiveAt)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.heartbeat}>{formatEpoch(selectedState.heartbeatAt)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.leaseExpires}>{formatEpoch(selectedState.leaseExpiresAt)}</Descriptions.Item>
                    </Descriptions>
                    <Descriptions bordered size="small" column={1} title={getRes().runtimeStates.resources}>
                        <Descriptions.Item label={getRes().runtimeStates.processAlive}>{processAliveTag(selectedState.processAlive)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.sampledAt}>{formatEpoch(selectedState.processSampledAt)}</Descriptions.Item>
                        <Descriptions.Item label="RSS">{formatBytes(selectedState.residentMemoryBytes)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.virtualMemory}>{formatBytes(selectedState.virtualMemoryBytes)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.heapUsed}>{formatBytes(selectedState.heapUsedBytes)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.heapCommitted}>{formatBytes(selectedState.heapCommittedBytes)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.heapLimit}>{formatBytes(selectedState.heapMaxBytes)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.cpuTime}>{formatDuration(selectedState.totalCpuDurationMillis)}</Descriptions.Item>
                        <Descriptions.Item label={getRes().runtimeStates.threadCount}>{selectedState.threadCount == null ? "-" : selectedState.threadCount}</Descriptions.Item>
                    </Descriptions>
                    {(selectedState.lastError || selectedState.processErrorMessage) && (
                        <Descriptions bordered size="small" column={1} title={getRes().common.error}>
                            {selectedState.lastError && (
                                <Descriptions.Item label={getRes().runtimeStates.runtimeError}>
                                    <Text type="danger">{selectedState.lastError}</Text>
                                </Descriptions.Item>
                            )}
                            {selectedState.processErrorMessage && (
                                <Descriptions.Item label={getRes().runtimeStates.processQuery}>
                                    <Text type="danger">{selectedState.processErrorMessage}</Text>
                                </Descriptions.Item>
                            )}
                        </Descriptions>
                    )}
                </Space>
            )}
        </Drawer>
    );

    const invocationStatusTag = (value: string) => value === "success" ? <Tag color="success">{getRes().common.success}</Tag> : <Tag color="error">{getRes().common.failure}</Tag>;
    const invocationRiskTag = (value?: string) => {
        if (!value) {
            return null;
        }
        const labels: Record<string, string> = {
            low: getRes().runtimeStates.riskLow,
            medium: getRes().runtimeStates.riskMedium,
            high: getRes().runtimeStates.riskHigh,
            critical: getRes().runtimeStates.riskCritical
        };
        const colors: Record<string, string> = {
            low: "default",
            medium: "warning",
            high: "orange",
            critical: "error"
        };
        return <Tag color={colors[value] || "default"}>{labels[value] || value}</Tag>;
    };
    const invocationSourceLabel = (value?: string) => {
        const labels: Record<string, string> = {
            scheduler: getRes().runtimeStates.sourceScheduler,
            tick: getRes().runtimeStates.sourceTick,
            notification: getRes().common.notification,
            runtime_event: getRes().runtimeStates.sourceEvent,
            internal: getRes().runtimeStates.sourceInternal,
            admin_ui: getRes().runtimeStates.sourceAdmin,
            mcp: "MCP"
        };
        return value ? (labels[value] || value) : "-";
    };
    const invocationLogCell = (record: InvocationLog) => (
        <Space direction="vertical" size={8} style={{width: "100%", minWidth: 0}}>
            {renderCapability(record.pluginId, record.capabilityKey, undefined, record.pluginPreviewImageBase64, record.pluginName)}
            {isMobile && (
                <Space direction="vertical" size={4} style={{width: "100%"}}>
                    <Space size={[4, 4]} wrap>
                        {invocationStatusTag(record.status)}
                        {record.source && <Tag>{invocationSourceLabel(record.source)}</Tag>}
                        {invocationRiskTag(record.riskLevel)}
                        {record.auditRequired && <Tag color="processing">{getRes().runtimeStates.audit}</Tag>}
                        {record.durationMs != null && <Tag>{record.durationMs} ms</Tag>}
                    </Space>
                    <Text type="secondary" style={{fontSize: 12}}>{formatEpoch(record.startedAt)}</Text>
                    {record.errorMessage && (
                        <Text type="danger" ellipsis style={{fontSize: 12, maxWidth: "100%"}}>
                            {record.errorMessage}
                        </Text>
                    )}
                </Space>
            )}
        </Space>
    );

    const invocationLogColumns: ColumnsType<InvocationLog> = [
        {title: getRes().runtimeStates.pluginInvocation, key: "capability", render: (_, record) => invocationLogCell(record)},
        {title: getRes().runtimeStates.source, dataIndex: "source", width: 120, render: invocationSourceLabel, responsive: ["md"]},
        {
            title: getRes().common.status,
            dataIndex: "status",
            width: 100,
            responsive: ["md"],
            render: invocationStatusTag
        },
        {
            title: getRes().runtimeStates.risk,
            dataIndex: "riskLevel",
            width: 140,
            responsive: ["md"],
            render: (value?: string, record?: InvocationLog) => (
                <Space size={[4, 4]} wrap>
                    {invocationRiskTag(value)}
                    {record?.auditRequired && <Tag color="processing">{getRes().runtimeStates.audit}</Tag>}
                </Space>
            )
        },
        {title: getRes().common.duration, dataIndex: "durationMs", width: 100, render: (value?: number) => value == null ? "-" : `${value} ms`, responsive: ["md"]},
        {title: getRes().common.startedAt, dataIndex: "startedAt", width: 240, render: formatEpoch, responsive: ["md"]},
        {title: getRes().common.error, dataIndex: "errorMessage", render: formatTime, responsive: ["md"]}
    ];

    return (
        <Space direction="vertical" size={16} style={{width: "100%"}}>
            {contextHolder}
            <Table<RuntimeInstanceState>
                loading={loading}
                rowKey={record => `${record.pluginId}:${record.instanceId}`}
                columns={stateColumns}
                dataSource={states}
                pagination={false}
                scroll={isMobile ? undefined : {x: 1000}}
            />
            {detailDrawer}
            <Text strong>{getRes().runtimeStates.logs}</Text>
            <Table<InvocationLog>
                loading={loading}
                rowKey="id"
                columns={invocationLogColumns}
                dataSource={invocationLogs}
                pagination={{...invocationLogPagination, showSizeChanger: !isMobile}}
                onChange={pagination => loadData(pagination.current || 1, pagination.pageSize || 10)}
                scroll={isMobile ? undefined : {x: 1100}}
            />
        </Space>
    );
};

export default RuntimeStatesTab;
