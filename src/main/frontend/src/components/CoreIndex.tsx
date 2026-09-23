import {getRes, formatText} from "../i18n/plugin";
import React, { useEffect, useState } from "react";
import {
  Avatar,
  Button,
  Card,
  Col,
  Empty,
  Grid,
  Input,
  message,
  Popconfirm,
  Row,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  theme,
} from "antd";
import type { TableColumnsType } from "antd";
import {
  ApiOutlined,
  AppstoreOutlined,
  CloudDownloadOutlined,
  DeleteOutlined,
  FieldTimeOutlined,
  LockOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  UnorderedListOutlined,
} from "@ant-design/icons";
import axios from "axios";
import { useNavigate } from "react-router-dom";
import type { Plugin, PluginCoreInfoResponse } from "../index";

const runtimePath = () => {
  if (window.location.pathname.startsWith("/admin/plugins"))
    return "/admin/plugins/runtime-scheduler";
  if (
    window.location.pathname.startsWith("/p/") ||
    window.location.pathname === "/p"
  )
    return "/p/runtime-scheduler";
  if (
    window.location.pathname.startsWith("/plugin/") ||
    window.location.pathname === "/plugin"
  )
    return "/plugin/runtime-scheduler";
  return "/runtime-scheduler";
};

type CoreIndexProps = {
  data: PluginCoreInfoResponse;
  onRefresh: () => Promise<void>;
};
type ServiceDisplay = { key: string; label: string };
const displayServices = (plugin: Plugin): ServiceDisplay[] => {
  if (plugin.capabilities && plugin.capabilities.length > 0) {
    return plugin.capabilities.map((capability) => ({
      key: capability.key,
      label: capability.label?.trim() || capability.key,
    }));
  }
  return (plugin.services || []).map((service) => ({
    key: service,
    label: service,
  }));
};
const pluginNameText = (plugin: Plugin) =>
  plugin.name?.trim() || plugin.shortName;

const CoreIndex: React.FC<CoreIndexProps> = ({ data, onRefresh }) => {
  const navigate = useNavigate();
  const screens = Grid.useBreakpoint();
  const { token } = theme.useToken();
  const [messageApi, contextHolder] = message.useMessage({ maxCount: 3 });
  const [searchText, setSearchText] = useState("");
  const [filter, setFilter] = useState("all");
  const [viewType, setViewType] = useState<"grid" | "list">(() => {
    try {
      return localStorage.getItem("zrlog-plugin-view-type") === "grid"
        ? "grid"
        : "list";
    } catch {
      return "list";
    }
  });
  useEffect(() => {
    onRefresh();
  }, [onRefresh]);
  const isRequired = (name: string) =>
    Boolean(data.requiredPlugins?.includes(name));
  const deletePlugin = (pluginName: string) =>
    axios
      .get("api/uninstall?name=" + encodeURIComponent(pluginName))
      .then(({ data }) => {
        if (data.code > 0) {
          void messageApi.error(data.message);
          return;
        }
        return onRefresh();
      });
  const filteredPlugins = data.plugins.filter((plugin) => {
    if (filter === "system" && !isRequired(plugin.shortName)) return false;
    if (filter === "removable" && isRequired(plugin.shortName)) return false;
    const keyword = searchText.trim().toLocaleLowerCase();
    return [
      pluginNameText(plugin),
      plugin.shortName,
      plugin.desc,
      ...displayServices(plugin).map(
        (service) => `${service.key} ${service.label}`
      ),
    ]
      .join(" ")
      .toLocaleLowerCase()
      .includes(keyword);
  });
  const renderIcon = (plugin: Plugin) => (
    <Avatar
      shape="square"
      size="large"
      src={plugin.previewImageBase64 || undefined}
      icon={<ApiOutlined />}
      style={{
        flexShrink: 0,
        borderRadius: token.borderRadiusLG,
        color: token.colorPrimary,
        background: token.colorPrimaryBg,
      }}
    />
  );
  const renderServices = (plugin: Plugin) => (
    <Space size="small" wrap>
      {displayServices(plugin).map((service) => (
        <Tooltip title={service.key} key={service.key}>
          <Tag style={{ whiteSpace: "normal", overflowWrap: "anywhere" }}>
            {service.label}
          </Tag>
        </Tooltip>
      ))}
    </Space>
  );
  const renderSystemTag = (plugin: Plugin) =>
    isRequired(plugin.shortName) && (
      <Tag icon={<SafetyCertificateOutlined />}>{getRes().common.system}</Tag>
    );
  const renderActions = (plugin: Plugin) => (
    <Space size="small" wrap>
      <Button
        type="link"
        href={plugin.shortName + "/"}
        icon={<SettingOutlined />}
      >{getRes().plugins.manage}</Button>
      {isRequired(plugin.shortName) ? (
        <Tooltip title={getRes().plugins.requiredTip}>
          <span>
            <Button
              type="text"
              disabled
              icon={<LockOutlined />}
              aria-label={getRes().plugins.requiredLabel}
            />
          </span>
        </Tooltip>
      ) : (
        <Popconfirm
          title={formatText(getRes().plugins.confirmUninstall, {name: pluginNameText(plugin)})}
          description={getRes().plugins.uninstallDescription}
          okText={getRes().common.confirm}
          cancelText={getRes().common.cancel}
          okButtonProps={{ danger: true }}
          onConfirm={() => deletePlugin(plugin.shortName)}
        >
          <Button
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label={formatText(getRes().plugins.uninstallLabel, {name: pluginNameText(plugin)})}
          />
        </Popconfirm>
      )}
    </Space>
  );
  const columns: TableColumnsType<Plugin> = [
    {
      title: getRes().plugins.info,
      key: "plugin",
      render: (_, plugin) => (
        <div
          style={{ display: "flex", gap: token.margin, whiteSpace: "normal" }}
        >
          {renderIcon(plugin)}
          <Space
            direction="vertical"
            size="small"
            style={{ minWidth: 0, overflowWrap: "anywhere" }}
          >
            <Space wrap>
              <Typography.Text strong>{pluginNameText(plugin)}</Typography.Text>
              {renderSystemTag(plugin)}
            </Space>
            <Typography.Text type="secondary">
              {plugin.desc || getRes().plugins.noDescription}
            </Typography.Text>
            {plugin.version && (
              <Typography.Text type="secondary">
                v{plugin.version}
              </Typography.Text>
            )}
            {!screens.lg && (
              <>
                {renderServices(plugin)}
                {renderActions(plugin)}
              </>
            )}
          </Space>
        </div>
      ),
    },
    {
      title: getRes().plugins.services,
      key: "services",
      responsive: ["lg"],
      width: "25%",
      render: (_, plugin) => renderServices(plugin),
    },
    {
      title: getRes().common.actions,
      key: "actions",
      responsive: ["lg"],
      width: 160,
      render: (_, plugin) => renderActions(plugin),
    },
  ];

  return (
    <div style={{ padding: screens.md ? token.paddingLG : token.paddingSM }}>
      {contextHolder}
      <Space
        direction="vertical"
        size="large"
        style={{
          width: "100%",
          maxWidth: 1440,
          margin: "0 auto",
          display: "flex",
        }}
      >
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            flexWrap: "wrap",
            gap: token.margin,
          }}
        >
          <Space direction="vertical" size="small">
            <Space wrap>
              <Typography.Title level={4} style={{ margin: 0 }}>{getRes().common.plugin}</Typography.Title>
              <Tag>{getRes().plugins.installed}{" "}{data.plugins.length}</Tag>
            </Space>
            <Typography.Text type="secondary">{getRes().plugins.description}</Typography.Text>
          </Space>
          <Space wrap>
            <Button
              icon={<FieldTimeOutlined />}
              onClick={() => navigate(runtimePath())}
            >{getRes().plugins.runtimeConsole}</Button>
            <Button
              type="primary"
              href={data.pluginCenter}
              icon={<CloudDownloadOutlined />}
            >{getRes().plugins.getMore}</Button>
          </Space>
        </div>
        <div
          style={{
            display: "flex",
            gap: token.marginSM,
            alignItems: "center",
            flexWrap: "wrap",
          }}
        >
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder={getRes().plugins.searchPlaceholder}
            aria-label={getRes().plugins.searchLabel}
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            style={{ width: screens.md ? 280 : "100%" }}
          />
          <Select
            value={filter}
            onChange={setFilter}
            aria-label={getRes().plugins.filter}
            style={{ minWidth: 130 }}
            options={[
              { label: getRes().plugins.all, value: "all" },
              { label: getRes().plugins.system, value: "system" },
              { label: getRes().plugins.removable, value: "removable" },
            ]}
          />
          <Segmented
            value={viewType}
            aria-label={getRes().plugins.viewMode}
            style={{ marginLeft: "auto" }}
            onChange={(value) => {
              const next = value === "grid" ? "grid" : "list";
              setViewType(next);
              try {
                localStorage.setItem("zrlog-plugin-view-type", next);
              } catch {
                /* View changes remain usable without storage. */
              }
            }}
            options={[
              { label: getRes().plugins.list, value: "list", icon: <UnorderedListOutlined /> },
              { label: getRes().plugins.grid, value: "grid", icon: <AppstoreOutlined /> },
            ]}
          />
        </div>
        {filteredPlugins.length === 0 ? (
          <Empty
            description={
              data.plugins.length
                ? getRes().plugins.noMatches
                : getRes().plugins.empty
            }
          >
            {data.plugins.length > 0 ? (
              <Button
                onClick={() => {
                  setSearchText("");
                  setFilter("all");
                }}
              >{getRes().plugins.clearFilter}</Button>
            ) : (
              <Button
                type="primary"
                href={data.pluginCenter}
                icon={<CloudDownloadOutlined />}
              >{getRes().plugins.getMore}</Button>
            )}
          </Empty>
        ) : viewType === "list" ? (
          <Table
            columns={columns}
            dataSource={filteredPlugins}
            rowKey="shortName"
            pagination={false}
            style={{
              background: token.colorBgContainer,
              borderRadius: token.borderRadiusLG,
            }}
          />
        ) : (
          <Row gutter={[token.marginLG, token.marginLG]} align="stretch">
            {filteredPlugins.map((plugin) => (
              <Col
                key={plugin.shortName}
                xs={24}
                md={12}
                xl={8}
                style={{ display: "flex" }}
              >
                <Card
                  style={{ width: "100%" }}
                  styles={{
                    body: {
                      height: "100%",
                      display: "flex",
                      flexDirection: "column",
                    },
                  }}
                >
                  <div
                    style={{
                      display: "flex",
                      gap: token.margin,
                      alignItems: "center",
                      marginBottom: token.margin,
                    }}
                  >
                    {renderIcon(plugin)}
                    <Space
                      direction="vertical"
                      size="small"
                      style={{ minWidth: 0 }}
                    >
                      <Space wrap>
                        <Typography.Title
                          level={5}
                          style={{ margin: 0, overflowWrap: "anywhere" }}
                        >
                          {pluginNameText(plugin)}
                        </Typography.Title>
                        {renderSystemTag(plugin)}
                      </Space>
                      {plugin.version && (
                        <Typography.Text type="secondary">
                          v{plugin.version}
                        </Typography.Text>
                      )}
                    </Space>
                  </div>
                  <Typography.Paragraph
                    type="secondary"
                    ellipsis={{ rows: 2, tooltip: plugin.desc }}
                    style={{ minHeight: token.fontSize * token.lineHeight * 2 }}
                  >
                    {plugin.desc || getRes().plugins.noDescription}
                  </Typography.Paragraph>
                  {renderServices(plugin)}
                  <div style={{ flex: 1 }} />
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "space-between",
                      flexWrap: "wrap",
                      gap: token.marginXS,
                      marginTop: token.margin,
                      paddingTop: token.paddingSM,
                      borderTop: `${token.lineWidth}px ${token.lineType} ${token.colorBorderSecondary}`,
                    }}
                  >
                    <Typography.Text
                      type="secondary"
                      style={{ overflowWrap: "anywhere" }}
                    >
                      {plugin.shortName}
                    </Typography.Text>
                    {renderActions(plugin)}
                  </div>
                </Card>
              </Col>
            ))}
          </Row>
        )}
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            flexWrap: "wrap",
            gap: token.marginSM,
          }}
        >
          <Typography.Text type="secondary">
            {formatText(getRes().plugins.count, {visible: filteredPlugins.length, total: data.plugins.length})}
          </Typography.Text>
          <Typography.Text type="secondary">{getRes().plugins.systemTip}</Typography.Text>
        </div>
      </Space>
    </div>
  );
};

export default CoreIndex;
