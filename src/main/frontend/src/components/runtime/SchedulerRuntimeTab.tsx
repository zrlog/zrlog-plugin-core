import React, {useEffect, useMemo, useState} from "react";
import {Button, Drawer, Form, Grid, Input, InputNumber, Modal, Popconfirm, Segmented, Select, Space, Switch, Table, Tabs, Tag, Tooltip, Typography, message} from "antd";
import {CodeOutlined, CopyOutlined, DeleteOutlined, EditOutlined, KeyOutlined, PlayCircleOutlined, PlusOutlined, SaveOutlined} from "@ant-design/icons";
import type {ColumnsType} from "antd/es/table";
import axios from "axios";
import {
    apiPath,
    Automation,
    AutomationRun,
    Capability,
    formatEpoch,
    formatTime,
    paginationFromResponse,
    RuntimePagination,
    rowsFromResponse,
    SchedulerSettings,
    SchedulerTickResult,
    textOrEmpty,
    useCapabilityView
} from "./common";

const {Text} = Typography;
const {TextArea} = Input;

type Props = {
    dark: boolean;
}

type RuntimeMaintenancePayload = {
    onDemandEnabled: boolean;
    autoDownloadMissingPluginFileEnabled: boolean;
    idleStopEnabled: boolean;
    idleTimeoutSeconds: number;
    idleScanIntervalSeconds: number;
    maxRunningPlugins: number;
    maxConcurrentStarts: number;
    startFailureBackoffSeconds: number;
}

type RuntimeLoadStrategy = "onDemand" | "startup";

const defaultSchedulerSettings: SchedulerSettings = {
    enabled: true,
    externalHost: "",
    effectiveExternalHost: "",
    externalTickPath: "",
    externalTickUrl: "",
    providers: [],
    systemTimezone: ""
};

const defaultRuntimeMaintenancePayload: RuntimeMaintenancePayload = {
    onDemandEnabled: true,
    autoDownloadMissingPluginFileEnabled: true,
    idleStopEnabled: true,
    idleTimeoutSeconds: 300,
    idleScanIntervalSeconds: 300,
    maxRunningPlugins: 4,
    maxConcurrentStarts: 2,
    startFailureBackoffSeconds: 30
};

const exactMaintenanceIntervalSeconds = [60, 120, 180, 240, 300, 360, 600, 720, 900, 1200, 1800, 3600];
const maintenanceIntervalOptions = exactMaintenanceIntervalSeconds.map(seconds => ({
    value: seconds,
    label: seconds === 3600 ? "1 小时" : `${seconds / 60} 分钟`
}));

const defaultRunPagination: RuntimePagination = {
    current: 1,
    pageSize: 8,
    total: 0
};

const SchedulerRuntimeTab: React.FC<Props> = () => {
    const screens = Grid.useBreakpoint();
    const isMobile = Boolean((screens.xs || screens.sm) && !screens.md);
    const [messageApi, contextHolder] = message.useMessage({maxCount: 3});
    const [loading, setLoading] = useState(false);
    const [savingSettings, setSavingSettings] = useState(false);
    const [settingsLoading, setSettingsLoading] = useState(false);
    const [settingsLoaded, setSettingsLoaded] = useState(false);
    const [settings, setSettings] = useState<SchedulerSettings>(defaultSchedulerSettings);
    const [capabilities, setCapabilities] = useState<Capability[]>([]);
    const [automations, setAutomations] = useState<Automation[]>([]);
    const [runs, setRuns] = useState<AutomationRun[]>([]);
    const [runPagination, setRunPagination] = useState<RuntimePagination>(defaultRunPagination);
    const [editing, setEditing] = useState<Automation | null>(null);
    const [modalOpen, setModalOpen] = useState(false);
    const [externalDrawerOpen, setExternalDrawerOpen] = useState(false);
    const [ticking, setTicking] = useState(false);
    const [runningAutomations, setRunningAutomations] = useState<Record<string, boolean>>({});
    const [form] = Form.useForm();
    const maintenanceLoadStrategy = Form.useWatch("maintenanceLoadStrategy", form) as RuntimeLoadStrategy | undefined;
    const maintenanceIdleStopEnabled = Form.useWatch("maintenanceIdleStopEnabled", form);
    const {capabilityLabel, capabilityTimeoutLabel, pluginNameLabel, renderPlugin} = useCapabilityView(capabilities);

    const scheduledCapabilities = useMemo(() => capabilities.filter(item =>
        item.type === "scheduled" && item.exposure?.includes("scheduler")
    ), [capabilities]);

    const isRuntimeMaintenance = (id?: string, pluginId?: string, capabilityKey?: string) =>
        id === "system:plugin-runtime-maintenance" || (pluginId === "__system__" && capabilityKey === "plugin.runtime.maintenance");
    const isRuntimeMaintenanceAutomation = (automation?: Automation | null) =>
        !!automation && isRuntimeMaintenance(automation.id, automation.pluginId, automation.capabilityKey);
    const hasExplicitRuntimeMaintenanceInterval = (automation?: Automation | null) =>
        !!automation?.payload && Object.prototype.hasOwnProperty.call(automation.payload, "idleScanIntervalSeconds");
    const isSystemAutomation = (automation?: Automation | null) => automation?.system === true;
    const parseBooleanPayload = (value: unknown, fallback: boolean) => {
        if (value === undefined || value === null) {
            return fallback;
        }
        if (typeof value === "boolean") {
            return value;
        }
        return value !== "false";
    };
    const parseNumberPayload = (value: unknown, fallback: number, min: number, max: number) => {
        const number = Number(value);
        return Number.isFinite(number) ? Math.max(min, Math.min(max, number)) : fallback;
    };
    const parseMaintenanceInterval = (value: unknown) => {
        const seconds = parseNumberPayload(value, defaultRuntimeMaintenancePayload.idleScanIntervalSeconds, 60, 3600);
        return exactMaintenanceIntervalSeconds.find(candidate => candidate >= seconds) || 3600;
    };
    const runtimeMaintenancePayload = (payload?: Record<string, unknown>): RuntimeMaintenancePayload => {
        const onDemandEnabled = parseBooleanPayload(payload?.onDemandEnabled, defaultRuntimeMaintenancePayload.onDemandEnabled);
        return {
            onDemandEnabled,
            autoDownloadMissingPluginFileEnabled: parseBooleanPayload(payload?.autoDownloadMissingPluginFileEnabled,
                defaultRuntimeMaintenancePayload.autoDownloadMissingPluginFileEnabled),
            idleStopEnabled: onDemandEnabled && parseBooleanPayload(payload?.idleStopEnabled, defaultRuntimeMaintenancePayload.idleStopEnabled),
            idleTimeoutSeconds: parseNumberPayload(payload?.idleTimeoutSeconds,
                defaultRuntimeMaintenancePayload.idleTimeoutSeconds, 10, 86400),
            idleScanIntervalSeconds: parseMaintenanceInterval(payload?.idleScanIntervalSeconds),
            maxRunningPlugins: parseNumberPayload(payload?.maxRunningPlugins,
                defaultRuntimeMaintenancePayload.maxRunningPlugins, 1, 32),
            maxConcurrentStarts: parseNumberPayload(payload?.maxConcurrentStarts,
                defaultRuntimeMaintenancePayload.maxConcurrentStarts, 1, 8),
            startFailureBackoffSeconds: parseNumberPayload(payload?.startFailureBackoffSeconds,
                defaultRuntimeMaintenancePayload.startFailureBackoffSeconds, 1, 3600)
        };
    };

    const loadData = async (runPage = runPagination.current, runPageSize = runPagination.pageSize) => {
        setLoading(true);
        try {
            const [capabilitiesRes, automationsRes, runsRes] = await Promise.all([
                axios.get(apiPath("/runtime-capabilities")),
                axios.get(apiPath("/runtime-automations")),
                axios.get(apiPath("/runtime-automation-runs"), {params: {page: runPage, pageSize: runPageSize}})
            ]);
            setCapabilities(capabilitiesRes.data.items || []);
            setAutomations(automationsRes.data.items || []);
            setRuns(rowsFromResponse<AutomationRun>(runsRes.data));
            setRunPagination(paginationFromResponse<AutomationRun>(runsRes.data, {
                current: runPage,
                pageSize: runPageSize,
                total: 0
            }));
        } catch (e) {
            messageApi.error("调度数据加载失败");
        } finally {
            setLoading(false);
        }
    };

    const loadSchedulerSettings = async () => {
        setSettingsLoading(true);
        setSettingsLoaded(false);
        try {
            const {data} = await axios.get(apiPath("/runtime-scheduler/settings"));
            setSettings(data);
            setSettingsLoaded(true);
        } catch (e) {
            messageApi.error("外部触发设置加载失败");
        } finally {
            setSettingsLoading(false);
        }
    };

    useEffect(() => {
        loadData();
    }, []);

    const copyText = async (text: string) => {
        try {
            await navigator.clipboard.writeText(text);
            messageApi.success("已复制");
        } catch (e) {
            messageApi.error("复制失败");
        }
    };

    const saveSchedulerSettings = async () => {
        setSavingSettings(true);
        try {
            const params = new URLSearchParams();
            params.set("externalHost", settings.externalHost || "");
            params.set("externalTickEnabled", String(defaultProvider()?.enabled === true));
            const {data: resp} = await axios.post(apiPath("/runtime-scheduler/settings"), params.toString());
            if (resp.code > 0) {
                messageApi.error(resp.message);
                return;
            }
            setSettings(resp);
            messageApi.success("已保存");
        } finally {
            setSavingSettings(false);
        }
    };

    const openExternalDrawer = async () => {
        setExternalDrawerOpen(true);
        await loadSchedulerSettings();
    };

    const openCreate = () => {
        const first = scheduledCapabilities[0];
        const capabilityValue = first ? `${first.pluginId}@@${first.key}` : undefined;
        setEditing(null);
        form.setFieldsValue({
            name: "",
            capability: capabilityValue,
            cron: first?.defaultCron || "*/5 * * * *",
            enabled: true,
            maintenanceLoadStrategy: defaultRuntimeMaintenancePayload.onDemandEnabled ? "onDemand" : "startup",
            maintenanceAutoDownloadMissingPluginFileEnabled: defaultRuntimeMaintenancePayload.autoDownloadMissingPluginFileEnabled,
            maintenanceIdleStopEnabled: defaultRuntimeMaintenancePayload.idleStopEnabled,
            maintenanceIdleTimeoutSeconds: defaultRuntimeMaintenancePayload.idleTimeoutSeconds,
            maintenanceIdleScanIntervalSeconds: defaultRuntimeMaintenancePayload.idleScanIntervalSeconds,
            maintenanceMaxRunningPlugins: defaultRuntimeMaintenancePayload.maxRunningPlugins,
            maintenanceMaxConcurrentStarts: defaultRuntimeMaintenancePayload.maxConcurrentStarts,
            maintenanceStartFailureBackoffSeconds: defaultRuntimeMaintenancePayload.startFailureBackoffSeconds,
            payload: "{}"
        });
        setModalOpen(true);
    };

    const openEdit = (automation: Automation) => {
        const maintenancePayload = runtimeMaintenancePayload(automation.payload);
        setEditing(automation);
        form.setFieldsValue({
            name: automation.name,
            capability: `${automation.pluginId}@@${automation.capabilityKey}`,
            cron: automation.cron,
            enabled: automation.enabled !== false,
            maintenanceLoadStrategy: maintenancePayload.onDemandEnabled ? "onDemand" : "startup",
            maintenanceAutoDownloadMissingPluginFileEnabled: maintenancePayload.autoDownloadMissingPluginFileEnabled,
            maintenanceIdleStopEnabled: maintenancePayload.idleStopEnabled,
            maintenanceIdleTimeoutSeconds: maintenancePayload.idleTimeoutSeconds,
            maintenanceIdleScanIntervalSeconds: maintenancePayload.idleScanIntervalSeconds,
            maintenanceMaxRunningPlugins: maintenancePayload.maxRunningPlugins,
            maintenanceMaxConcurrentStarts: maintenancePayload.maxConcurrentStarts,
            maintenanceStartFailureBackoffSeconds: maintenancePayload.startFailureBackoffSeconds,
            payload: JSON.stringify(automation.payload || {}, null, 2)
        });
        setModalOpen(true);
    };

    const saveAutomation = async () => {
        const values = await form.validateFields();
        const systemRuntimeMaintenance = editing ? isRuntimeMaintenanceAutomation(editing) : false;
        const legacyCustomRuntimeMaintenance = systemRuntimeMaintenance && !hasExplicitRuntimeMaintenanceInterval(editing);
        const onDemandEnabled = values.maintenanceLoadStrategy !== "startup";
        let payload = values.payload || "{}";
        if (systemRuntimeMaintenance) {
            const runtimePayload: Record<string, unknown> = {
                onDemandEnabled,
                autoDownloadMissingPluginFileEnabled: values.maintenanceAutoDownloadMissingPluginFileEnabled !== false,
                idleStopEnabled: onDemandEnabled && values.maintenanceIdleStopEnabled !== false,
                idleTimeoutSeconds: Number(values.maintenanceIdleTimeoutSeconds || defaultRuntimeMaintenancePayload.idleTimeoutSeconds),
                maxRunningPlugins: Number(values.maintenanceMaxRunningPlugins || defaultRuntimeMaintenancePayload.maxRunningPlugins),
                maxConcurrentStarts: Number(values.maintenanceMaxConcurrentStarts || defaultRuntimeMaintenancePayload.maxConcurrentStarts),
                startFailureBackoffSeconds: Number(values.maintenanceStartFailureBackoffSeconds
                    || defaultRuntimeMaintenancePayload.startFailureBackoffSeconds)
            };
            if (!legacyCustomRuntimeMaintenance) {
                runtimePayload.idleScanIntervalSeconds = Number(values.maintenanceIdleScanIntervalSeconds
                    || defaultRuntimeMaintenancePayload.idleScanIntervalSeconds);
            }
            payload = JSON.stringify(runtimePayload);
        }
        if (!systemRuntimeMaintenance) {
            JSON.parse(payload);
        }
        const [pluginId, capabilityKey] = editing?.id
            ? [editing.pluginId, editing.capabilityKey]
            : values.capability.split("@@");
        const params = new URLSearchParams();
        if (editing?.id) {
            params.set("id", editing.id);
        }
        params.set("name", values.name);
        params.set("pluginId", pluginId);
        params.set("capabilityKey", capabilityKey);
        if (!systemRuntimeMaintenance || legacyCustomRuntimeMaintenance) {
            params.set("cron", values.cron);
        }
        params.set("enabled", String(systemRuntimeMaintenance ? true : values.enabled));
        params.set("payload", payload);
        const url = editing?.id ? "/runtime-automations/update" : "/runtime-automations";
        const {data: resp} = await axios.post(apiPath(url), params.toString());
        if (resp.code > 0) {
            messageApi.error(resp.message);
            return;
        }
        setModalOpen(false);
        messageApi.success("已保存");
        await loadData(runPagination.current, runPagination.pageSize);
    };

    const deleteAutomation = async (id?: string) => {
        if (!id) {
            return;
        }
        const params = new URLSearchParams();
        params.set("id", id);
        const {data: resp} = await axios.post(apiPath("/runtime-automations/delete"), params.toString());
        if (resp.code > 0) {
            messageApi.error(resp.message);
            return;
        }
        messageApi.success("已删除");
        await loadData(runPagination.current, runPagination.pageSize);
    };

    const formatTickResult = (result?: SchedulerTickResult) => {
        if (!result) {
            return "调度检查已触发";
        }
        return `调度检查完成：执行 ${result.executedCount || 0}，失败 ${result.failedCount || 0}，跳过 ${result.skippedCount || 0}`;
    };

    const triggerTick = async () => {
        setTicking(true);
        try {
            const {data: resp} = await axios.post(apiPath("/runtime-scheduler/tick"));
            if (resp.code > 0) {
                messageApi.error(resp.message);
                return;
            }
            messageApi.success(formatTickResult(resp.result));
            await loadData(1, runPagination.pageSize);
        } finally {
            setTicking(false);
        }
    };

    const automationKey = (automation: Automation) => automation.id || `${automation.pluginId}:${automation.capabilityKey}`;
    const automationOwnerLabel = (pluginId: string, pluginName?: string) =>
        pluginId === "__system__" ? "系统" : pluginNameLabel(pluginId, pluginName);
    const stripOwnerFromTargetLabel = (targetLabel: string | undefined, ownerLabel: string) => {
        const label = textOrEmpty(targetLabel);
        const ownerPrefix = `${ownerLabel} / `;
        if (label.startsWith(ownerPrefix)) {
            return textOrEmpty(label.substring(ownerPrefix.length));
        }
        const systemPrefix = "系统任务 / ";
        if (label.startsWith(systemPrefix)) {
            return textOrEmpty(label.substring(systemPrefix.length));
        }
        return label;
    };
    const automationTaskLabel = (automation: Automation) =>
        textOrEmpty(automation.name) ||
        stripOwnerFromTargetLabel(automation.targetLabel, automationOwnerLabel(automation.pluginId, automation.pluginName)) ||
        capabilityLabel(automation.pluginId, automation.capabilityKey);
    const automationRunTaskLabel = (run: AutomationRun) =>
        stripOwnerFromTargetLabel(run.targetLabel, automationOwnerLabel(run.pluginId, run.pluginName)) ||
        capabilityLabel(run.pluginId, run.capabilityKey);
    const automationLastRunDescription = (automation: Automation) => {
        const timeoutLabel = capabilityTimeoutLabel(automation.pluginId, automation.capabilityKey);
        const lastRunLabel = `上次执行 ${formatEpoch(automation.lastRunAt)}`;
        return timeoutLabel ? `${lastRunLabel} · ${timeoutLabel}` : lastRunLabel;
    };
    const automationStatusTag = (automation: Automation) => isSystemAutomation(automation)
        ? <Tag color="processing">系统</Tag>
        : automation.enabled === false ? <Tag>停用</Tag> : <Tag color="success">启用</Tag>;
    const automationTaskCell = (automation: Automation) => (
        <Space direction="vertical" size={8} style={{width: "100%", minWidth: 0}}>
            {renderPlugin(
                automation.pluginId,
                automation.pluginName,
                automation.pluginPreviewImageBase64,
                automationOwnerLabel(automation.pluginId, automation.pluginName),
                automationTaskLabel(automation),
                automationLastRunDescription(automation)
            )}
            {isMobile && (
                <Space direction="vertical" size={4} style={{width: "100%"}}>
                    <Space size={[4, 4]} wrap>
                        {automationStatusTag(automation)}
                        <Tag style={{margin: 0}}>{automation.cron}</Tag>
                    </Space>
                    <Text type="secondary" style={{fontSize: 12}}>
                        下次 {formatEpoch(automation.nextRunAt)}
                    </Text>
                </Space>
            )}
        </Space>
    );
    const runStatusTag = (value: string) => value === "success" ? <Tag color="success">成功</Tag> : <Tag color="error">失败</Tag>;
    const automationRunCell = (run: AutomationRun) => (
        <Space direction="vertical" size={8} style={{width: "100%", minWidth: 0}}>
            {renderPlugin(
                run.pluginId,
                run.pluginName,
                run.pluginPreviewImageBase64,
                automationOwnerLabel(run.pluginId, run.pluginName),
                automationRunTaskLabel(run)
            )}
            {isMobile && (
                <Space direction="vertical" size={4} style={{width: "100%"}}>
                    <Space size={[4, 4]} wrap>
                        {runStatusTag(run.status)}
                        {run.durationMs != null && <Tag>{run.durationMs} ms</Tag>}
                    </Space>
                    <Text type="secondary" style={{fontSize: 12}}>{formatEpoch(run.startedAt)}</Text>
                    {run.errorMessage && (
                        <Text type="danger" ellipsis style={{fontSize: 12, maxWidth: "100%"}}>
                            {run.errorMessage}
                        </Text>
                    )}
                </Space>
            )}
        </Space>
    );
    const defaultProvider = () => settings.providers.find(provider => provider.id === "default") || settings.providers[0];
    const setProviderEnabled = (providerId: string, enabled: boolean) => {
        setSettings({
            ...settings,
            providers: settings.providers.map(provider => provider.id === providerId ? {...provider, enabled} : provider)
        });
    };
    const externalTickUrl = settings.externalTickUrl || apiPath("/internal/plugin/scheduler/tick");
    const curlCommand = (secret: string) => `curl -X POST \\
  -H "Authorization: Bearer ${secret || "<secret>"}" \\
  ${externalTickUrl}`;
    const workerCode = () => `export default {
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(tick(env));
  },

  async fetch() {
    return new Response("ZrLog 调度 Worker 正在运行，定时任务通过 scheduled() 触发", {
      headers: {"content-type": "text/plain; charset=utf-8"},
    });
  },
};

async function tick(env) {
  const response = await fetch("${externalTickUrl}", {
    method: "POST",
    headers: {
      Authorization: \`Bearer \${env.ZRLOG_SCHEDULER_SECRET}\`,
    },
  });

  if (!response.ok) {
    throw new Error("ZrLog 调度检查失败: " + response.status + " " + await response.text());
  }
}`;
    const wranglerConfig = () => `name = "zrlog-scheduler"
main = "src/index.js"
compatibility_date = "2026-05-30"

[triggers]
crons = ["*/5 * * * *"]`;
    const helpProvider = defaultProvider();

    const runAutomationNow = async (automation: Automation) => {
        if (!automation.id) {
            return;
        }
        const key = automationKey(automation);
        setRunningAutomations(prev => ({...prev, [key]: true}));
        try {
            const params = new URLSearchParams();
            params.set("id", automation.id);
            const {data: resp} = await axios.post(apiPath("/runtime-automations/run"), params.toString());
            if (resp.code > 0) {
                messageApi.error(resp.message);
                return;
            }
            if (resp.item?.status === "success") {
                messageApi.success("任务已执行");
            } else {
                messageApi.error(resp.item?.errorMessage || "任务执行失败");
            }
            await loadData(1, runPagination.pageSize);
        } finally {
            setRunningAutomations(prev => ({...prev, [key]: false}));
        }
    };

    const automationColumns: ColumnsType<Automation> = [
        {
            title: "任务",
            dataIndex: "name",
            render: (value: string, record) => automationTaskCell({...record, name: value})
        },
        {title: "执行周期", dataIndex: "cron", width: 140, responsive: ["md"]},
        {
            title: "状态",
            dataIndex: "enabled",
            width: 100,
            responsive: ["md"],
            render: (_: boolean, record) => automationStatusTag(record)
        },
        {title: "下次执行", dataIndex: "nextRunAt", width: 220, render: formatEpoch, responsive: ["md"]},
        {
            title: "操作",
            key: "action",
            width: isMobile ? 104 : 230,
            render: (_, record) => (
                <Space size={isMobile ? 2 : "small"} wrap={!isMobile}>
                    <Tooltip title="立即执行">
                        <Button
                            type={isMobile ? "text" : "link"}
                            size="small"
                            aria-label="立即执行"
                            icon={<PlayCircleOutlined />}
                            disabled={!record.id}
                            loading={runningAutomations[automationKey(record)]}
                            onClick={() => runAutomationNow(record)}
                        >
                            {!isMobile && "立即执行"}
                        </Button>
                    </Tooltip>
                    <Tooltip title="编辑">
                        <Button
                            type={isMobile ? "text" : "link"}
                            size="small"
                            aria-label="编辑"
                            icon={<EditOutlined />}
                            onClick={() => openEdit(record)}
                        >
                            {!isMobile && "编辑"}
                        </Button>
                    </Tooltip>
                    {record.deletable === false || record.system ? (
                        <Tooltip title="删除">
                            <Button danger type={isMobile ? "text" : "link"} size="small" aria-label="删除" icon={<DeleteOutlined />} disabled>
                                {!isMobile && "删除"}
                            </Button>
                        </Tooltip>
                    ) : (
                        <Popconfirm title="删除这个定时任务？" okText="删除" okButtonProps={{danger: true}} cancelText="取消" onConfirm={() => deleteAutomation(record.id)}>
                            <Tooltip title="删除">
                                <Button danger type={isMobile ? "text" : "link"} size="small" aria-label="删除" icon={<DeleteOutlined />}>
                                    {!isMobile && "删除"}
                                </Button>
                            </Tooltip>
                        </Popconfirm>
                    )}
                </Space>
            )
        }
    ];

    const runColumns: ColumnsType<AutomationRun> = [
        {
            title: "任务",
            key: "target",
            render: (_, record) => automationRunCell(record)
        },
        {
            title: "状态",
            dataIndex: "status",
            width: 100,
            responsive: ["md"],
            render: runStatusTag
        },
        {title: "耗时", dataIndex: "durationMs", width: 100, render: (value?: number) => value == null ? "-" : `${value} ms`, responsive: ["md"]},
        {title: "开始时间", dataIndex: "startedAt", width: 220, render: formatEpoch, responsive: ["md"]},
        {title: "错误", dataIndex: "errorMessage", render: formatTime, responsive: ["lg"]}
    ];
    const editingRuntimeMaintenance = isRuntimeMaintenanceAutomation(editing);
    const editingLegacyCustomRuntimeMaintenance = editingRuntimeMaintenance && !hasExplicitRuntimeMaintenanceInterval(editing);
    const editingSystemAutomation = isSystemAutomation(editing);
    const showMaintenanceOnDemandSettings = (maintenanceLoadStrategy || "onDemand") === "onDemand";
    const showMaintenanceIdleTimeout = showMaintenanceOnDemandSettings && maintenanceIdleStopEnabled !== false;

    return (
        <Space direction="vertical" size={16} style={{width: "100%"}}>
            {contextHolder}
            <div style={{display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, flexWrap: "wrap"}}>
                <Space>
                    <Text strong>定时任务</Text>
                    <Button type="link" size="small" icon={<PlusOutlined />} disabled={scheduledCapabilities.length === 0} onClick={openCreate}>新建任务</Button>
                </Space>
                <Space wrap style={isMobile ? {width: "100%"} : undefined}>
                    <Tooltip title="触发一次调度检查，只执行已到期且启用的任务；未到期任务请使用行内立即执行">
                        <Button icon={<PlayCircleOutlined />} loading={ticking} onClick={triggerTick} style={isMobile ? {flex: 1} : undefined}>检查到期任务</Button>
                    </Tooltip>
                    <Button type="primary" icon={<CodeOutlined />} onClick={openExternalDrawer} style={isMobile ? {flex: 1} : undefined}>外部触发</Button>
                </Space>
            </div>
            <Table<Automation>
                loading={loading}
                rowKey={record => record.id || `${record.pluginId}:${record.capabilityKey}`}
                columns={automationColumns}
                dataSource={automations}
                pagination={false}
                scroll={isMobile ? undefined : {x: 1040}}
            />

            <Text strong>定时任务执行记录</Text>
            <Table<AutomationRun>
                loading={loading}
                rowKey="id"
                columns={runColumns}
                dataSource={runs}
                pagination={{...runPagination, showSizeChanger: !isMobile}}
                onChange={pagination => loadData(pagination.current || 1, pagination.pageSize || defaultRunPagination.pageSize)}
                scroll={isMobile ? undefined : {x: 900}}
            />

            <Drawer
                title="外部触发"
                open={externalDrawerOpen}
                width={isMobile ? "100%" : 640}
                onClose={() => setExternalDrawerOpen(false)}
                destroyOnClose
                loading={settingsLoading}
                extra={<Button icon={<SaveOutlined />} type="primary" loading={savingSettings} disabled={settingsLoading || !settingsLoaded} onClick={saveSchedulerSettings}>保存</Button>}
            >
                <Space direction="vertical" size={16} style={{width: "100%"}}>
                    <Text type="secondary">
                        开启外部入口后，外部调度器可通过 POST 请求触发一次到期任务检查；接口使用 Authorization: Bearer token 校验
                    </Text>
                    <Input
                        addonBefore="外部地址"
                        placeholder="https://blog.example.com"
                        value={settings.externalHost || ""}
                        onChange={event => setSettings({...settings, externalHost: event.target.value})}
                    />
                    <Text type="secondary">
                        生效地址用于生成接入命令：{settings.effectiveExternalHost || "-"}
                    </Text>
                    {settings.providers.map(provider => (
                        <Space key={provider.id} direction="vertical" size={10} style={{width: "100%"}}>
                            <Space align="center">
                                <Text>外部入口</Text>
                                <Switch checked={provider.enabled === true} onChange={checked => setProviderEnabled(provider.id, checked)} />
                            </Space>
                            <Input
                                readOnly
                                addonBefore={<Tooltip title="Authorization Bearer token"><KeyOutlined /> Bearer token</Tooltip>}
                                value={provider.secret}
                                addonAfter={(
                                    <Tooltip title="复制 Bearer token">
                                        <Button
                                            type="text"
                                            size="small"
                                            icon={<CopyOutlined />}
                                            onClick={() => copyText(provider.secret)}
                                            style={{
                                                width: 22,
                                                height: 22,
                                                padding: 0,
                                                display: "inline-flex",
                                                alignItems: "center",
                                                justifyContent: "center"
                                            }}
                                        />
                                    </Tooltip>
                                )}
                            />
                            <Text type="secondary">复制命令时会自动把这个 token 写入 Authorization 请求头</Text>
                        </Space>
                    ))}
                    <Text strong>接入方式</Text>
                    <Tabs
                        items={[
                            {
                                key: "curl",
                                label: "curl",
                                children: (
                                    <Space direction="vertical" size={12} style={{width: "100%"}}>
                                        <Text type="secondary">适合系统定时器、GitHub Actions 或其它可执行命令的调度器</Text>
                                        <Input.TextArea readOnly autoSize value={curlCommand(helpProvider?.secret || "")} />
                                        <Button icon={<CopyOutlined />} onClick={() => copyText(curlCommand(helpProvider?.secret || ""))}>复制 curl</Button>
                                    </Space>
                                )
                            },
                            {
                                key: "cloudflare",
                                label: "Cloudflare Worker",
                                children: (
                                    <Space direction="vertical" size={12} style={{width: "100%"}}>
                                        <Text type="secondary">Cloudflare 定时触发器会调用 scheduled() 执行调度检查；直接访问 Worker 只返回运行状态</Text>
                                        <Text strong>src/index.js</Text>
                                        <Input.TextArea readOnly autoSize value={workerCode()} />
                                        <Button icon={<CopyOutlined />} onClick={() => copyText(workerCode())}>复制 Worker 代码</Button>
                                        <Text strong>wrangler.toml</Text>
                                        <Input.TextArea readOnly autoSize value={wranglerConfig()} />
                                        <Button icon={<CopyOutlined />} onClick={() => copyText(wranglerConfig())}>复制 wrangler 配置</Button>
                                        <Text type="secondary">密钥：wrangler secret put ZRLOG_SCHEDULER_SECRET</Text>
                                    </Space>
                                )
                            }
                        ]}
                    />
                </Space>
            </Drawer>

            <Modal
                title={editing ? "编辑定时任务" : "新建定时任务"}
                width={isMobile ? "calc(100vw - 24px)" : undefined}
                open={modalOpen}
                onOk={saveAutomation}
                onCancel={() => setModalOpen(false)}
                destroyOnClose
                styles={{body: {maxHeight: "calc(100vh - 220px)", overflowY: "auto", paddingRight: 4}}}
            >
                <Form form={form} layout="vertical">
                    <Form.Item label="任务名称" name="name" rules={[{required: true, message: "请输入任务名称"}]}>
                        <Input disabled={editingSystemAutomation} />
                    </Form.Item>
                    <Form.Item label="插件任务" name="capability" hidden={!!editing} rules={[{required: !editing, message: "请选择插件任务"}]}>
                        <Select
                            onChange={(value) => {
                                const [pluginId, key] = value.split("@@");
                                const selected = scheduledCapabilities.find(item => item.pluginId === pluginId && item.key === key);
                                if (selected?.defaultCron) {
                                    form.setFieldsValue({cron: selected.defaultCron});
                                }
                            }}
                            options={scheduledCapabilities.map(item => ({
                                value: `${item.pluginId}@@${item.key}`,
                                label: (
                                    <Space direction="vertical" size={0}>
                                        <Text>{pluginNameLabel(item.pluginId, item.pluginName)} / {capabilityLabel(item.pluginId, item.key, item.label)}</Text>
                                        <Text type="secondary" style={{fontSize: 12}}>{capabilityTimeoutLabel(item.pluginId, item.key)}</Text>
                                    </Space>
                                )
                            }))}
                        />
                    </Form.Item>
                    {(!editingRuntimeMaintenance || editingLegacyCustomRuntimeMaintenance) && (
                        <Form.Item label="执行周期" name="cron" rules={[{required: true, message: "请输入执行周期"}]}>
                            <Input placeholder="*/5 * * * *" />
                        </Form.Item>
                    )}
                    {!editingSystemAutomation && (
                        <Form.Item label="启用" name="enabled" valuePropName="checked">
                            <Switch />
                        </Form.Item>
                    )}
                    {editingRuntimeMaintenance ? (
                        <Space direction="vertical" size={12} style={{width: "100%"}}>
                            <Form.Item label="加载策略" name="maintenanceLoadStrategy" rules={[{required: true, message: "请选择加载策略"}]}>
                                <Segmented
                                    block
                                    options={[
                                        {label: "按需加载", value: "onDemand"},
                                        {label: "启动时加载", value: "startup"}
                                    ]}
                                />
                            </Form.Item>
                            <Form.Item label="缺失包自动下载" name="maintenanceAutoDownloadMissingPluginFileEnabled" valuePropName="checked">
                                <Switch />
                            </Form.Item>
                            {!editingLegacyCustomRuntimeMaintenance && (
                                <Form.Item
                                    label="维护间隔"
                                    name="maintenanceIdleScanIntervalSeconds"
                                    rules={[{required: true, message: "请选择维护间隔"}]}
                                >
                                    <Select options={maintenanceIntervalOptions} />
                                </Form.Item>
                            )}
                            {showMaintenanceOnDemandSettings && (
                                <Form.Item
                                    label="运行插件上限"
                                    name="maintenanceMaxRunningPlugins"
                                    rules={[{required: true, message: "请输入运行插件上限"}]}
                                >
                                    <InputNumber min={1} max={32} precision={0} style={{width: "100%"}} />
                                </Form.Item>
                            )}
                            <Form.Item
                                label="并发启动上限"
                                name="maintenanceMaxConcurrentStarts"
                                rules={[{required: true, message: "请输入并发启动上限"}]}
                            >
                                <InputNumber min={1} max={8} precision={0} style={{width: "100%"}} />
                            </Form.Item>
                            <Form.Item
                                label="失败重试间隔"
                                name="maintenanceStartFailureBackoffSeconds"
                                rules={[{required: true, message: "请输入失败重试间隔"}]}
                            >
                                <InputNumber min={1} max={3600} precision={0} addonAfter="秒" style={{width: "100%"}} />
                            </Form.Item>
                            {showMaintenanceOnDemandSettings && (
                                <Form.Item label="空闲回收" name="maintenanceIdleStopEnabled" valuePropName="checked">
                                    <Switch />
                                </Form.Item>
                            )}
                            {showMaintenanceIdleTimeout && (
                                <Form.Item
                                    label="空闲秒数"
                                    name="maintenanceIdleTimeoutSeconds"
                                    rules={[{required: true, message: "请输入空闲秒数"}]}
                                >
                                    <InputNumber min={10} max={86400} addonAfter="秒" style={{width: "100%"}} />
                                </Form.Item>
                            )}
                        </Space>
                    ) : (
                        <Form.Item label="任务参数 JSON" name="payload" rules={[{
                            validator: (_, value) => {
                                try {
                                    JSON.parse(value || "{}");
                                    return Promise.resolve();
                                } catch (e) {
                                    return Promise.reject(new Error("任务参数必须是合法 JSON"));
                                }
                            }
                        }]}>
                            <TextArea rows={5} />
                        </Form.Item>
                    )}
                </Form>
            </Modal>
        </Space>
    );
};

export default SchedulerRuntimeTab;
