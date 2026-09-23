import {getRes, formatText} from "../../i18n/plugin";
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
const maintenanceIntervalOptions = () => exactMaintenanceIntervalSeconds.map(seconds => ({
    value: seconds,
    label: seconds === 3600 ? getRes().runtimeScheduler.oneHour : formatText(getRes().common.minutes, {minutes: seconds / 60})
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
            messageApi.error(getRes().runtimeScheduler.loadError);
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
            messageApi.error(getRes().runtimeScheduler.settingsLoadError);
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
            messageApi.success(getRes().runtimeScheduler.copied);
        } catch (e) {
            messageApi.error(getRes().runtimeScheduler.copyError);
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
            messageApi.success(getRes().common.saved);
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
            name: isRuntimeMaintenanceAutomation(automation) ? getRes().runtimeScheduler.maintenance : automation.name,
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
        messageApi.success(getRes().common.saved);
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
        messageApi.success(getRes().runtimeScheduler.deleted);
        await loadData(runPagination.current, runPagination.pageSize);
    };

    const formatTickResult = (result?: SchedulerTickResult) => {
        if (!result) {
            return getRes().runtimeScheduler.tickTriggered;
        }
        return formatText(getRes().runtimeScheduler.tickResult, {executed: result.executedCount || 0, failed: result.failedCount || 0, skipped: result.skippedCount || 0});
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
        pluginId === "__system__" ? getRes().common.system : pluginNameLabel(pluginId, pluginName);
    const stripOwnerFromTargetLabel = (targetLabel: string | undefined, ownerLabel: string) => {
        const label = textOrEmpty(targetLabel);
        const ownerPrefix = `${ownerLabel} / `;
        if (label.startsWith(ownerPrefix)) {
            return textOrEmpty(label.substring(ownerPrefix.length));
        }
        return label;
    };
    const automationTaskLabel = (automation: Automation) =>
        isRuntimeMaintenanceAutomation(automation) ? getRes().runtimeScheduler.maintenance :
        textOrEmpty(automation.name) ||
        stripOwnerFromTargetLabel(automation.targetLabel, automationOwnerLabel(automation.pluginId, automation.pluginName)) ||
        capabilityLabel(automation.pluginId, automation.capabilityKey);
    const automationRunTaskLabel = (run: AutomationRun) =>
        isRuntimeMaintenance(run.automationId, run.pluginId, run.capabilityKey) ? getRes().runtimeScheduler.maintenance :
        stripOwnerFromTargetLabel(run.targetLabel, automationOwnerLabel(run.pluginId, run.pluginName)) ||
        capabilityLabel(run.pluginId, run.capabilityKey);
    const automationLastRunDescription = (automation: Automation) => {
        const timeoutLabel = capabilityTimeoutLabel(automation.pluginId, automation.capabilityKey);
        const lastRunLabel = formatText(getRes().runtimeScheduler.lastRun, {time: formatEpoch(automation.lastRunAt)});
        return timeoutLabel ? `${lastRunLabel} · ${timeoutLabel}` : lastRunLabel;
    };
    const automationStatusTag = (automation: Automation) => isSystemAutomation(automation)
        ? <Tag color="processing">{getRes().common.system}</Tag>
        : automation.enabled === false ? <Tag>{getRes().runtimeScheduler.disabled}</Tag> : <Tag color="success">{getRes().runtimeScheduler.enabled}</Tag>;
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
                    <Text type="secondary" style={{fontSize: 12}}>{getRes().runtimeScheduler.next}{" "}{formatEpoch(automation.nextRunAt)}
                    </Text>
                </Space>
            )}
        </Space>
    );
    const runStatusTag = (value: string) => value === "success" ? <Tag color="success">{getRes().common.success}</Tag> : <Tag color="error">{getRes().common.failure}</Tag>;
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
    return new Response(${JSON.stringify(getRes().runtimeScheduler.workerRunning)}, {
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
    throw new Error(${JSON.stringify(getRes().runtimeScheduler.workerFailed)} + response.status + " " + await response.text());
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
                messageApi.success(getRes().runtimeScheduler.runSuccess);
            } else {
                messageApi.error(resp.item?.errorMessage || getRes().runtimeScheduler.runError);
            }
            await loadData(1, runPagination.pageSize);
        } finally {
            setRunningAutomations(prev => ({...prev, [key]: false}));
        }
    };

    const automationColumns: ColumnsType<Automation> = [
        {
            title: getRes().runtimeScheduler.task,
            dataIndex: "name",
            render: (value: string, record) => automationTaskCell({...record, name: value})
        },
        {title: getRes().runtimeScheduler.schedule, dataIndex: "cron", width: 140, responsive: ["md"]},
        {
            title: getRes().common.status,
            dataIndex: "enabled",
            width: 100,
            responsive: ["md"],
            render: (_: boolean, record) => automationStatusTag(record)
        },
        {title: getRes().runtimeScheduler.nextRun, dataIndex: "nextRunAt", width: 220, render: formatEpoch, responsive: ["md"]},
        {
            title: getRes().common.actions,
            key: "action",
            width: isMobile ? 104 : 230,
            render: (_, record) => (
                <Space size={isMobile ? 2 : "small"} wrap={!isMobile}>
                    <Tooltip title={getRes().runtimeScheduler.runNow}>
                        <Button
                            type={isMobile ? "text" : "link"}
                            size="small"
                            aria-label={getRes().runtimeScheduler.runNow}
                            icon={<PlayCircleOutlined />}
                            disabled={!record.id}
                            loading={runningAutomations[automationKey(record)]}
                            onClick={() => runAutomationNow(record)}
                        >
                            {!isMobile && getRes().runtimeScheduler.runNow}
                        </Button>
                    </Tooltip>
                    <Tooltip title={getRes().runtimeScheduler.edit}>
                        <Button
                            type={isMobile ? "text" : "link"}
                            size="small"
                            aria-label={getRes().runtimeScheduler.edit}
                            icon={<EditOutlined />}
                            onClick={() => openEdit(record)}
                        >
                            {!isMobile && getRes().runtimeScheduler.edit}
                        </Button>
                    </Tooltip>
                    {record.deletable === false || record.system ? (
                        <Tooltip title={getRes().runtimeScheduler.delete}>
                            <Button danger type={isMobile ? "text" : "link"} size="small" aria-label={getRes().runtimeScheduler.delete} icon={<DeleteOutlined />} disabled>
                                {!isMobile && getRes().runtimeScheduler.delete}
                            </Button>
                        </Tooltip>
                    ) : (
                        <Popconfirm title={getRes().runtimeScheduler.confirmDelete} okText={getRes().runtimeScheduler.delete} okButtonProps={{danger: true}} cancelText={getRes().common.cancel} onConfirm={() => deleteAutomation(record.id)}>
                            <Tooltip title={getRes().runtimeScheduler.delete}>
                                <Button danger type={isMobile ? "text" : "link"} size="small" aria-label={getRes().runtimeScheduler.delete} icon={<DeleteOutlined />}>
                                    {!isMobile && getRes().runtimeScheduler.delete}
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
            title: getRes().runtimeScheduler.task,
            key: "target",
            render: (_, record) => automationRunCell(record)
        },
        {
            title: getRes().common.status,
            dataIndex: "status",
            width: 100,
            responsive: ["md"],
            render: runStatusTag
        },
        {title: getRes().common.duration, dataIndex: "durationMs", width: 100, render: (value?: number) => value == null ? "-" : `${value} ms`, responsive: ["md"]},
        {title: getRes().common.startedAt, dataIndex: "startedAt", width: 220, render: formatEpoch, responsive: ["md"]},
        {title: getRes().common.error, dataIndex: "errorMessage", render: formatTime, responsive: ["lg"]}
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
                    <Text strong>{getRes().runtimeScheduler.tasks}</Text>
                    <Button type="link" size="small" icon={<PlusOutlined />} disabled={scheduledCapabilities.length === 0} onClick={openCreate}>{getRes().runtimeScheduler.create}</Button>
                </Space>
                <Space wrap style={isMobile ? {width: "100%"} : undefined}>
                    <Tooltip title={getRes().runtimeScheduler.tickTip}>
                        <Button icon={<PlayCircleOutlined />} loading={ticking} onClick={triggerTick} style={isMobile ? {flex: 1} : undefined}>{getRes().runtimeScheduler.checkDue}</Button>
                    </Tooltip>
                    <Button type="primary" icon={<CodeOutlined />} onClick={openExternalDrawer} style={isMobile ? {flex: 1} : undefined}>{getRes().runtimeScheduler.externalTrigger}</Button>
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

            <Text strong>{getRes().runtimeScheduler.runs}</Text>
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
                title={getRes().runtimeScheduler.externalTrigger}
                open={externalDrawerOpen}
                width={isMobile ? "100%" : 640}
                onClose={() => setExternalDrawerOpen(false)}
                destroyOnClose
                loading={settingsLoading}
                extra={<Button icon={<SaveOutlined />} type="primary" loading={savingSettings} disabled={settingsLoading || !settingsLoaded} onClick={saveSchedulerSettings}>{getRes().runtimeScheduler.save}</Button>}
            >
                <Space direction="vertical" size={16} style={{width: "100%"}}>
                    <Text type="secondary">{getRes().runtimeScheduler.externalHelp}</Text>
                    <Input
                        addonBefore={getRes().runtimeScheduler.externalAddress}
                        placeholder="https://blog.example.com"
                        value={settings.externalHost || ""}
                        onChange={event => setSettings({...settings, externalHost: event.target.value})}
                    />
                    <Text type="secondary">{getRes().runtimeScheduler.effectiveAddress}{settings.effectiveExternalHost || "-"}
                    </Text>
                    {settings.providers.map(provider => (
                        <Space key={provider.id} direction="vertical" size={10} style={{width: "100%"}}>
                            <Space align="center">
                                <Text>{getRes().runtimeScheduler.externalEndpoint}</Text>
                                <Switch checked={provider.enabled === true} onChange={checked => setProviderEnabled(provider.id, checked)} />
                            </Space>
                            <Input
                                readOnly
                                addonBefore={<Tooltip title="Authorization Bearer token"><KeyOutlined /> Bearer token</Tooltip>}
                                value={provider.secret}
                                addonAfter={(
                                    <Tooltip title={getRes().runtimeScheduler.copyToken}>
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
                            <Text type="secondary">{getRes().runtimeScheduler.tokenHelp}</Text>
                        </Space>
                    ))}
                    <Text strong>{getRes().runtimeScheduler.integration}</Text>
                    <Tabs
                        items={[
                            {
                                key: "curl",
                                label: "curl",
                                children: (
                                    <Space direction="vertical" size={12} style={{width: "100%"}}>
                                        <Text type="secondary">{getRes().runtimeScheduler.curlHelp}</Text>
                                        <Input.TextArea readOnly autoSize value={curlCommand(helpProvider?.secret || "")} />
                                        <Button icon={<CopyOutlined />} onClick={() => copyText(curlCommand(helpProvider?.secret || ""))}>{getRes().runtimeScheduler.copyCurl}</Button>
                                    </Space>
                                )
                            },
                            {
                                key: "cloudflare",
                                label: "Cloudflare Worker",
                                children: (
                                    <Space direction="vertical" size={12} style={{width: "100%"}}>
                                        <Text type="secondary">{getRes().runtimeScheduler.workerHelp}</Text>
                                        <Text strong>src/index.js</Text>
                                        <Input.TextArea readOnly autoSize value={workerCode()} />
                                        <Button icon={<CopyOutlined />} onClick={() => copyText(workerCode())}>{getRes().runtimeScheduler.copyWorker}</Button>
                                        <Text strong>wrangler.toml</Text>
                                        <Input.TextArea readOnly autoSize value={wranglerConfig()} />
                                        <Button icon={<CopyOutlined />} onClick={() => copyText(wranglerConfig())}>{getRes().runtimeScheduler.copyWrangler}</Button>
                                        <Text type="secondary">{getRes().runtimeScheduler.secretCommand}</Text>
                                    </Space>
                                )
                            }
                        ]}
                    />
                </Space>
            </Drawer>

            <Modal
                title={editing ? getRes().runtimeScheduler.editTitle : getRes().runtimeScheduler.createTitle}
                width={isMobile ? "calc(100vw - 24px)" : undefined}
                open={modalOpen}
                onOk={saveAutomation}
                onCancel={() => setModalOpen(false)}
                destroyOnClose
                styles={{body: {maxHeight: "calc(100vh - 220px)", overflowY: "auto", paddingRight: 4}}}
            >
                <Form form={form} layout="vertical">
                    <Form.Item label={getRes().runtimeScheduler.taskName} name="name" rules={[{required: true, message: getRes().runtimeScheduler.nameRequired}]}>
                        <Input disabled={editingSystemAutomation} />
                    </Form.Item>
                    <Form.Item label={getRes().runtimeScheduler.pluginTask} name="capability" hidden={!!editing} rules={[{required: !editing, message: getRes().runtimeScheduler.taskRequired}]}>
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
                        <Form.Item label={getRes().runtimeScheduler.schedule} name="cron" rules={[{required: true, message: getRes().runtimeScheduler.scheduleRequired}]}>
                            <Input placeholder="*/5 * * * *" />
                        </Form.Item>
                    )}
                    {!editingSystemAutomation && (
                        <Form.Item label={getRes().runtimeScheduler.enabled} name="enabled" valuePropName="checked">
                            <Switch />
                        </Form.Item>
                    )}
                    {editingRuntimeMaintenance ? (
                        <Space direction="vertical" size={12} style={{width: "100%"}}>
                            <Form.Item label={getRes().runtimeScheduler.loadStrategy} name="maintenanceLoadStrategy" rules={[{required: true, message: getRes().runtimeScheduler.strategyRequired}]}>
                                <Segmented
                                    block
                                    options={[
                                        {label: getRes().runtimeScheduler.onDemand, value: "onDemand"},
                                        {label: getRes().runtimeScheduler.onStartup, value: "startup"}
                                    ]}
                                />
                            </Form.Item>
                            <Form.Item label={getRes().runtimeScheduler.autoDownload} name="maintenanceAutoDownloadMissingPluginFileEnabled" valuePropName="checked">
                                <Switch />
                            </Form.Item>
                            {!editingLegacyCustomRuntimeMaintenance && (
                                <Form.Item
                                    label={getRes().runtimeScheduler.maintenanceInterval}
                                    name="maintenanceIdleScanIntervalSeconds"
                                    rules={[{required: true, message: getRes().runtimeScheduler.intervalRequired}]}
                                >
                                    <Select options={maintenanceIntervalOptions()} />
                                </Form.Item>
                            )}
                            {showMaintenanceOnDemandSettings && (
                                <Form.Item
                                    label={getRes().runtimeScheduler.maxRunning}
                                    name="maintenanceMaxRunningPlugins"
                                    rules={[{required: true, message: getRes().runtimeScheduler.maxRunningRequired}]}
                                >
                                    <InputNumber min={1} max={32} precision={0} style={{width: "100%"}} />
                                </Form.Item>
                            )}
                            <Form.Item
                                label={getRes().runtimeScheduler.maxStarts}
                                name="maintenanceMaxConcurrentStarts"
                                rules={[{required: true, message: getRes().runtimeScheduler.maxStartsRequired}]}
                            >
                                <InputNumber min={1} max={8} precision={0} style={{width: "100%"}} />
                            </Form.Item>
                            <Form.Item
                                label={getRes().runtimeScheduler.retryInterval}
                                name="maintenanceStartFailureBackoffSeconds"
                                rules={[{required: true, message: getRes().runtimeScheduler.retryRequired}]}
                            >
                                <InputNumber min={1} max={3600} precision={0} addonAfter={getRes().common.secondsUnit} style={{width: "100%"}} />
                            </Form.Item>
                            {showMaintenanceOnDemandSettings && (
                                <Form.Item label={getRes().runtimeScheduler.idleStop} name="maintenanceIdleStopEnabled" valuePropName="checked">
                                    <Switch />
                                </Form.Item>
                            )}
                            {showMaintenanceIdleTimeout && (
                                <Form.Item
                                    label={getRes().runtimeScheduler.idleSeconds}
                                    name="maintenanceIdleTimeoutSeconds"
                                    rules={[{required: true, message: getRes().runtimeScheduler.idleRequired}]}
                                >
                                    <InputNumber min={10} max={86400} addonAfter={getRes().common.secondsUnit} style={{width: "100%"}} />
                                </Form.Item>
                            )}
                        </Space>
                    ) : (
                        <Form.Item label={getRes().runtimeScheduler.payload} name="payload" rules={[{
                            validator: (_, value) => {
                                try {
                                    JSON.parse(value || "{}");
                                    return Promise.resolve();
                                } catch (e) {
                                    return Promise.reject(new Error(getRes().runtimeScheduler.payloadInvalid));
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
