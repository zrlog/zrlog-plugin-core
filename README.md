# zrlog-plugin-core

ZrLog 插件运行时服务。负责插件启动、运行状态、功能注册、定时任务、通知渠道路由和运行时管理页面。

## 功能

- 启动和管理本地插件进程
- 展示插件运行状态、调用日志和通知投递记录
- 管理插件定时任务和外部触发入口
- 为插件提供运行时调用、通知渠道和静态页面代理
- 管理页面及核心接口提示支持中英文，跟随后台站点语言设置；维护方式见 [i18n 说明](docs/i18n.md)

## 插件上传

```shell
zrlogctl login --permissions plugin.manage
zrlogctl plugin upload travel.jar
zrlogctl plugin upload travel.jar --overwrite
zrlogctl plugin upload travel-Linux-amd64.bin --overwrite
```

通过站点 `POST /api/admin/plugins/upload` 转发到本服务 `POST /api/upload`，需要
`plugin.manage` 权限。multipart 文件字段为 `file`，查询参数为 `fileName` 和可选的
`overwrite`（默认 `false`）。接口契约见 [OpenAPI](docs/api/openapi.yaml)。暂不提供上传 UI。

文件最大 64 MiB，JVM 部署使用 `shortName.jar`，原生部署使用与服务端系统、架构一致的
`shortName-OS-architecture.bin/.exe`。沿用现有插件文件命名，不接收 ZIP 或源码目录。
覆盖会停止已有插件；上传成功表示新插件已完成注册，运行方式仍由按需加载设置决定。
注册失败会恢复原文件与元数据，原插件可能需要手动启动；恢复失败保留插件目录中的备份。
客户端超时不自动重试，应先检查服务端插件状态。FaaS 上文件持久性取决于部署的可写插件目录。

## HTTP 代理

WebAPI 数据库连接检查和查询、插件 `HTTP_METHOD` 对外转发、插件包及 MD5 下载共用代理策略。
主程序启动 plugin-core 时会通过 `http.proxyHost`、`https.proxyHost`、对应的 `proxyPort`
和 `http.nonProxyHosts` 传入当前生效的代理配置，JVM 和 Native 模式使用同一策略。
在主程序“系统信息 → 网络代理”保存后，需要重启 ZrLog 才会生效。

独立启动时可使用上述 JVM 参数，或 `HTTP_PROXY`、`HTTPS_PROXY`、`NO_PROXY` 环境变量；
小写变量优先，空值表示显式停用对应协议的代理。主程序传入的 JVM 参数优先于环境变量，
避免重新解释已经生效的配置。HTTP/HTTPS 分别配置，HTTPS 使用 CONNECT 隧道并保留证书校验，
暂不支持代理认证。直连例外支持逗号分隔的主机名、IP、`.example.com` 和 `*.internal`，
不支持端口或 CIDR。本机回环地址始终直连，原生 JDBC/SQLite 不走 HTTP 代理；代理失败不会回退直连。
plugin-core 不自行读取主程序尚未重启生效的 `conf/proxy.properties`。

## 构建

公共协议固定依赖 `zrlog-plugin-common:4.0.5`，数据库工具固定依赖 `common-dao:1.1.16`。先确认公共库正式制品已发布到 Maven Central，再合入依赖升级并发布运行时。构建在 `validate` 阶段拒绝项目、父 POM 及直接、传递依赖中的 SNAPSHOT。

```shell
export JAVA_HOME=${HOME}/dev/graalvm-jdk-latest
export PATH=${JAVA_HOME}/bin:$PATH
```

Linux Native 和 FaaS Native workflow 会在上传前通过固定到 `v1.0.1` 完整提交 SHA 的
[`zrlog-process-artifact`](https://github.com/zrlog/zrlog-process-artifact) Action 调用
`zrlog-artifact-service` 处理并校验可执行文件。客户端用法与故障处理见该仓库 README。
仓库需要配置 `ARTIFACT_SERVICE_TOKEN` GitHub Actions Secret；Windows 和 macOS 构建不调用
该服务。

更多插件开发说明见 [ZrLog 插件开发文档](https://blog.zrlog.com/zrlog-plugin-dev.html)。
