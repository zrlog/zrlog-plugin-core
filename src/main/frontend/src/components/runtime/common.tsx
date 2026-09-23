import {getRes, formatText, getLanguage} from "../../i18n/plugin";
import {useMemo} from "react";
import type {ReactNode} from "react";
import {Avatar, Space, Typography, theme} from "antd";
import {ApiOutlined} from "@ant-design/icons";
export {apiBase, apiPath} from "../../api";

const {Text} = Typography;

export type RuntimeTab = "scheduler" | "runtime" | "notification" | "services";

export type SchedulerProvider = {
    id: string;
    enabled: boolean;
    secret: string;
}

export type SchedulerSettings = {
    enabled: boolean;
    externalHost?: string;
    effectiveExternalHost?: string;
    externalTickPath?: string;
    externalTickUrl?: string;
    providers: SchedulerProvider[];
    systemTimezone: string;
}

export type SchedulerTickResult = {
    executedCount?: number;
    skippedCount?: number;
    failedCount?: number;
}

export type RuntimePagination = {
    current: number;
    pageSize: number;
    total: number;
}

export type PageDataResponse<T> = {
    code?: number;
    message?: string;
    rows?: T[];
    items?: T[];
    page?: number;
    size?: number;
    pageSize?: number;
    totalElements?: number;
    total?: number;
}

export const paginationFromResponse = <T,>(data: PageDataResponse<T>, fallback: RuntimePagination): RuntimePagination => ({
    current: Number(data.page || fallback.current),
    pageSize: Number(data.size || data.pageSize || fallback.pageSize),
    total: Number(data.totalElements ?? data.total ?? 0)
});

export const rowsFromResponse = <T,>(data: PageDataResponse<T>): T[] => data.rows || data.items || [];

export type Capability = {
    pluginId: string;
    pluginName?: string;
    pluginPreviewImageBase64?: string;
    key: string;
    type: string;
    label?: string;
    description?: string;
    exposure?: string[];
    serviceName?: string;
    channel?: string;
    defaultCron?: string;
    timezone?: string;
    timeoutSeconds?: number;
}

export type Automation = {
    id?: string;
    pluginId: string;
    pluginName?: string;
    pluginPreviewImageBase64?: string;
    capabilityKey: string;
    name: string;
    triggerType?: string;
    cron: string;
    timezone?: string;
    enabled?: boolean;
    system?: boolean;
    deletable?: boolean;
    nextRunAt?: number;
    lastRunAt?: number;
    targetLabel?: string;
    payload?: Record<string, unknown>;
}

export type AutomationRun = {
    id: string;
    automationId: string;
    pluginId: string;
    pluginName?: string;
    pluginPreviewImageBase64?: string;
    capabilityKey: string;
    status: string;
    startedAt?: number;
    finishedAt?: number;
    durationMs?: number;
    errorMessage?: string;
    targetLabel?: string;
}

export type RuntimeState = {
    pluginId: string;
    pluginName?: string;
    pluginPreviewImageBase64?: string;
    status: string;
    effectiveStatus?: string;
    runtimeMode?: string;
    startedAt?: number;
    readyAt?: number;
    lastActiveAt?: number;
    activeInvocationCount?: number;
    instances?: RuntimeInstanceState[];
    lastError?: string;
}

export type RuntimeInstanceState = {
    pluginId: string;
    pluginName?: string;
    pluginVersion?: string;
    pluginPreviewImageBase64?: string;
    instanceId: string;
    ownerId?: string;
    status: string;
    effectiveStatus?: string;
    runtimeMode?: string;
    processId?: number;
    local?: boolean;
    startedAt?: number;
    readyAt?: number;
    lastActiveAt?: number;
    heartbeatAt?: number;
    leaseExpiresAt?: number;
    activeInvocationCount?: number;
    lastError?: string;
    processAlive?: boolean;
    processSampledAt?: number;
    totalCpuDurationMillis?: number;
    residentMemoryBytes?: number;
    virtualMemoryBytes?: number;
    threadCount?: number;
    heapUsedBytes?: number;
    heapCommittedBytes?: number;
    heapMaxBytes?: number;
    processErrorMessage?: string;
}

export type InvocationLog = {
    id: string;
    pluginId: string;
    pluginName?: string;
    pluginPreviewImageBase64?: string;
    capabilityKey: string;
    source?: string;
    riskLevel?: string;
    auditRequired?: boolean;
    requestId?: string;
    status: string;
    startedAt?: number;
    finishedAt?: number;
    durationMs?: number;
    errorMessage?: string;
}

export type NotificationProviderRow = {
    channel: string;
    providerPluginId: string;
    providerPluginName?: string;
    providerPluginPreviewImageBase64?: string;
    capabilityKey: string;
    capabilityLabel?: string;
    providerStatus: string;
    selected: boolean;
    confirmed: boolean;
    reviewRequired: boolean;
    lastDeliveryStatus?: string;
    lastDeliveryAt?: number;
    lastDeliveryError?: string;
    updatedAt?: number;
}

export type NotificationDelivery = {
    id: string;
    channel: string;
    providerPluginId?: string;
    providerPluginName?: string;
    providerPluginPreviewImageBase64?: string;
    capabilityKey?: string;
    status: string;
    errorMessage?: string;
    createdAt?: number;
}

export type ServiceProviderRow = {
    serviceName: string;
    serviceLabel?: string;
    providerPluginId: string;
    providerPluginName?: string;
    providerPluginPreviewImageBase64?: string;
    capabilityKey: string;
    capabilityLabel?: string;
    selected: boolean;
    confirmed: boolean;
    reviewRequired: boolean;
}

export type CommentProviderRow = {
    shortName: string;
    pluginId?: string;
    pluginName?: string;
    pluginPreviewImageBase64?: string;
    description?: string;
    selected: boolean;
    confirmed: boolean;
    reviewRequired: boolean;
}

export const backPath = () => {
    if (window.location.pathname.startsWith("/admin/plugins")) {
        return "/admin/plugins";
    }
    if (window.location.pathname.startsWith("/p/") || window.location.pathname === "/p") {
        return "/p/";
    }
    if (window.location.pathname.startsWith("/plugin/") || window.location.pathname === "/plugin") {
        return "/plugin/";
    }
    return "/";
};

export const runtimeTabSegment: Record<RuntimeTab, string> = {
    scheduler: "runtime-scheduler",
    runtime: "runtime-states",
    notification: "runtime-notification",
    services: "runtime-services"
};

export const runtimeTabPath = (tab: RuntimeTab) => {
    if (window.location.pathname.startsWith("/admin/plugins")) {
        return `/admin/plugins/${runtimeTabSegment[tab]}`;
    }
    if (window.location.pathname.startsWith("/p/") || window.location.pathname === "/p") {
        return `/p/${runtimeTabSegment[tab]}`;
    }
    if (window.location.pathname.startsWith("/plugin/") || window.location.pathname === "/plugin") {
        return `/plugin/${runtimeTabSegment[tab]}`;
    }
    return `/${runtimeTabSegment[tab]}`;
};

export const runtimeTabFromPath = (pathname: string): RuntimeTab => {
    if (pathname.includes("runtime-notification")) {
        return "notification";
    }
    if (pathname.includes("runtime-services")) {
        return "services";
    }
    if (pathname.includes("runtime-states")) {
        return "runtime";
    }
    return "scheduler";
};

export const formatTime = (value?: string) => value && value.length > 0 ? value : "-";
export const formatEpoch = (value?: number) => value ? new Date(value).toLocaleString(getLanguage().replace("_", "-")) : "-";
export const textOrEmpty = (value?: string) => value && value.trim().length > 0 ? value.trim() : "";
export const formatDurationSeconds = (value?: number) => {
    const seconds = Number(value);
    if (!Number.isFinite(seconds) || seconds <= 0) {
        return "-";
    }
    if (seconds >= 3600) {
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        return minutes > 0 ? formatText(getRes().common.hoursMinutes, {hours, minutes}) : formatText(getRes().common.hours, {hours});
    }
    if (seconds >= 60) {
        const minutes = Math.floor(seconds / 60);
        const remainSeconds = seconds % 60;
        return remainSeconds > 0 ? formatText(getRes().common.minutesSeconds, {minutes, seconds: remainSeconds}) : formatText(getRes().common.minutes, {minutes});
    }
    return formatText(getRes().common.seconds, {seconds});
};

type PluginIdentityOptions = {
    title: ReactNode;
    subtitle?: ReactNode;
    description?: ReactNode;
    pluginPreviewImageBase64?: string;
    avatarSize?: number;
    iconColor?: string;
    iconBackgroundColor?: string;
}

export const renderPluginIdentity = ({
    title,
    subtitle,
    description,
    pluginPreviewImageBase64,
    avatarSize = 34,
    iconColor,
    iconBackgroundColor
}: PluginIdentityOptions) => {
    const image = textOrEmpty(pluginPreviewImageBase64);
    const titleText = typeof title === "string" ? textOrEmpty(title) : "";
    const subtitleText = typeof subtitle === "string" ? textOrEmpty(subtitle) : "";
    const descriptionText = typeof description === "string" ? textOrEmpty(description) : "";
    const visibleSubtitle = titleText && subtitleText && titleText === subtitleText ? undefined : subtitle;
    const visibleDescription = typeof description === "string"
        ? descriptionText && descriptionText !== titleText && descriptionText !== subtitleText ? description : undefined
        : description;
    const titleNode = (
        <Text strong ellipsis style={{lineHeight: 1.35, maxWidth: "100%"}}>
            {title}
        </Text>
    );
    return (
        <Space align="center" size={10} style={{width: "100%", minWidth: 0, maxWidth: "100%"}}>
            <Avatar
                shape="square"
                size={avatarSize}
                src={image || undefined}
                icon={!image ? <ApiOutlined style={iconColor ? {color: iconColor} : undefined} /> : undefined}
                style={{
                    flexShrink: 0,
                    ...(iconBackgroundColor ? {backgroundColor: "transparent"} : {})
                }}
            />
            <Space direction="vertical" size={0} style={{minWidth: 0, maxWidth: "100%"}}>
                {titleNode}
                {visibleSubtitle && (
                    <Text type="secondary" ellipsis style={{fontSize: 12, lineHeight: 1.35, maxWidth: "100%"}}>
                        {visibleSubtitle}
                    </Text>
                )}
                {visibleDescription && (
                    <Text type="secondary" ellipsis style={{fontSize: 12, lineHeight: 1.35, opacity: 0.78, maxWidth: "100%"}}>
                        {visibleDescription}
                    </Text>
                )}
            </Space>
        </Space>
    );
};

export const useCapabilityView = (capabilities: Capability[]) => {
    const {token} = theme.useToken();
    const capabilityByIdentity = useMemo(() => {
        const map = new Map<string, Capability>();
        capabilities.forEach(item => {
            map.set(`${item.pluginId}@@${item.key}`, item);
        });
        return map;
    }, [capabilities]);

    const pluginNameById = useMemo(() => {
        const map = new Map<string, string>();
        capabilities.forEach(item => {
            if (textOrEmpty(item.pluginName)) {
                map.set(item.pluginId, textOrEmpty(item.pluginName));
            }
        });
        return map;
    }, [capabilities]);

    const pluginPreviewImageById = useMemo(() => {
        const map = new Map<string, string>();
        capabilities.forEach(item => {
            if (textOrEmpty(item.pluginPreviewImageBase64)) {
                map.set(item.pluginId, textOrEmpty(item.pluginPreviewImageBase64));
            }
        });
        return map;
    }, [capabilities]);

    const pluginNameLabel = (pluginId?: string, pluginName?: string) =>
        pluginId ? (pluginNameById.get(pluginId) || textOrEmpty(pluginName) || getRes().common.unnamedPlugin) : (textOrEmpty(pluginName) || getRes().common.unnamedPlugin);

    const pluginPreviewImage = (pluginId?: string, explicitPreviewImage?: string) =>
        textOrEmpty(explicitPreviewImage) || (pluginId ? pluginPreviewImageById.get(pluginId) || "" : "");
    const systemIconStyle = (pluginId?: string) => pluginId === "__system__" ? {
        iconColor: token.colorPrimary,
        iconBackgroundColor: token.colorPrimaryBg
    } : {};

    const capabilityOf = (pluginId?: string, key?: string) => {
        if (!pluginId || !key) {
            return undefined;
        }
        return capabilityByIdentity.get(`${pluginId}@@${key}`);
    };

    const capabilityLabel = (pluginId?: string, key?: string, explicitLabel?: string) => {
        const capability = capabilityOf(pluginId, key);
        return textOrEmpty(explicitLabel) || textOrEmpty(capability?.label) || textOrEmpty(key) || "-";
    };

    const capabilityDescription = (pluginId?: string, key?: string) => {
        const capability = capabilityOf(pluginId, key);
        return textOrEmpty(capability?.description);
    };

    const capabilityTimeoutLabel = (pluginId?: string, key?: string) => {
        const capability = capabilityOf(pluginId, key);
        if (!capability?.timeoutSeconds) {
            return "";
        }
        return formatText(getRes().common.executionTimeout, {duration: formatDurationSeconds(capability.timeoutSeconds)});
    };

    const capabilityDescriptionNode = (pluginId?: string, key?: string): ReactNode => {
        const description = capabilityDescription(pluginId, key);
        const timeoutLabel = capabilityTimeoutLabel(pluginId, key);
        if (!description) {
            return timeoutLabel || undefined;
        }
        if (!timeoutLabel) {
            return description;
        }
        return `${description} · ${timeoutLabel}`;
    };

    const capabilityMeta = (pluginId?: string, key?: string) => {
        const capability = capabilityOf(pluginId, key);
        return {
            label: capabilityLabel(pluginId, key),
            pluginName: pluginNameLabel(pluginId, capability?.pluginName),
            pluginPreviewImageBase64: pluginPreviewImage(pluginId, capability?.pluginPreviewImageBase64),
            description: capabilityDescriptionNode(pluginId, key),
            key: textOrEmpty(key)
        };
    };

    const renderPlugin = (
        pluginId?: string,
        pluginName?: string,
        pluginPreviewImageBase64?: string,
        title?: ReactNode,
        subtitle?: ReactNode,
        description?: ReactNode
    ) => {
        return renderPluginIdentity({
            title: title || pluginNameLabel(pluginId, pluginName),
            subtitle: subtitle || undefined,
            description: description || undefined,
            pluginPreviewImageBase64: pluginPreviewImage(pluginId, pluginPreviewImageBase64),
            ...systemIconStyle(pluginId)
        });
    };

    const renderCapability = (
        pluginId?: string,
        key?: string,
        explicitLabel?: string,
        explicitPreviewImage?: string,
        explicitPluginName?: string
    ) => {
        const meta = capabilityMeta(pluginId, key);
        const label = capabilityLabel(pluginId, key, explicitLabel);
        return renderPluginIdentity({
            title: pluginNameLabel(pluginId, explicitPluginName || meta.pluginName),
            subtitle: label,
            description: meta.description,
            pluginPreviewImageBase64: pluginPreviewImage(pluginId, explicitPreviewImage || meta.pluginPreviewImageBase64),
            ...systemIconStyle(pluginId)
        });
    };

    return {
        capabilityDescription,
        capabilityLabel,
        capabilityTimeoutLabel,
        pluginNameLabel,
        renderPlugin,
        renderCapability
    };
};
