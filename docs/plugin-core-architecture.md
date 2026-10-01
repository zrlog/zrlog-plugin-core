# 插件核心架构

本文记录当前 `zrlog-plugin-core` 的包边界和主要调用方向。整体按两个服务面整理：

1. `web` 负责 HTTP、UI 和标准 MVC。
2. `runtime` 负责插件交互、插件进程、Socket 通讯和运行态能力。

## 系统边界

```mermaid
flowchart LR
    Browser[浏览器 / 管理后台]
    Application["Application<br/>JVM 入口"]

    subgraph PluginImpl["插件实现端"]
        PluginProcess["插件进程"]
        PluginCommon["zrlog-plugin-common<br/>NioClient / IOSession / 通讯模型"]
        PluginCode["插件代码<br/>@Service / @Capability / @ScheduledCapability"]
        PluginHandlers["插件处理器<br/>service / capability / event / notification"]
    end

    subgraph Startup["启动层"]
        ApplicationStartup["ApplicationStartup<br/>参数与环境"]
        ApplicationServers["ApplicationServers<br/>服务装配"]
    end

    subgraph Web["web：HTTP / MVC"]
        HttpServer["PluginHttpServer<br/>HTTP 生命周期"]
        WebConfig["web.config<br/>路由与拦截器"]
        WebController["web.controller<br/>页面与 REST"]
        WebHandler["web.handler<br/>插件页面代理"]
    end

    subgraph Runtime["runtime：运行态"]
        RuntimeFeature["运行态能力<br/>capability / scheduler / notification / state"]
        RuntimeServices["PluginRuntimeServices<br/>启动装配结果"]
        RuntimeBridge["PluginRuntimeBridge<br/>框架桥接"]

        subgraph RuntimePlugin["runtime.plugin：插件交互"]
            RuntimeServer["PluginRuntimeServer<br/>NIO 与运行态 worker"]
            Transport["transport<br/>Socket 服务与消息处理"]
            Bootstrap["bootstrap<br/>元数据与插件文件对齐"]
            Lifecycle["lifecycle<br/>注册 / 停止 / 删除"]
            Process["process<br/>本地插件进程"]
            Session["session<br/>IOSession 注册表"]
            Config["config<br/>运行态配置值"]
            Artifact["artifact<br/>插件文件"]
        end
    end

    subgraph Infra["共享基础设施"]
        DAO["dao"]
        Store["runtime.store"]
        Model["model / vo"]
    end

    Application --> ApplicationStartup
    ApplicationStartup --> ApplicationServers
    ApplicationServers --> RuntimeServices
    ApplicationServers --> RuntimeServer
    ApplicationServers --> HttpServer
    Process --> PluginProcess
    PluginProcess --> PluginCommon
    PluginCommon --> PluginCode
    PluginCode --> PluginHandlers

    Browser --> WebController
    Browser --> WebHandler
    HttpServer --> WebConfig
    WebConfig --> RuntimeBridge
    WebController --> RuntimeFeature
    WebController --> RuntimeBridge
    WebHandler --> Session

    RuntimeBridge --> RuntimeServices
    RuntimeServer --> Transport
    Transport <--> PluginCommon
    Transport --> Lifecycle
    Transport --> RuntimeFeature
    RuntimeFeature --> Bootstrap
    RuntimeFeature --> RuntimeBridge

    RuntimeServices --> Bootstrap
    RuntimeServices --> Process
    RuntimeServices --> Session
    RuntimeServices --> Config
    Bootstrap --> Artifact
    Bootstrap --> Config
    Bootstrap --> Process
    Bootstrap --> Session
    Lifecycle --> Process
    Lifecycle --> Session
    Process --> Config
    Process --> Artifact

    WebController --> DAO
    RuntimeFeature --> Store
    RuntimeFeature --> DAO
    RuntimePlugin --> DAO
    DAO --> Model
```

边界图只表达包方向和主要交接点。启动装配、HTTP 控制、插件 Socket 通讯拆成独立图，避免把它们看成一条混在一起的运行态依赖链。

## 启动装配

```mermaid
flowchart TD
    Application["Application.main<br/>JVM 入口"]
    Startup["ApplicationStartup<br/>启动流程"]
    Options["ApplicationStartupOptions<br/>启动参数"]
    Environment["ApplicationEnvironment<br/>环境和运行模式"]
    PluginConfig["PluginConfig<br/>插件运行配置"]
    HostConnection["PluginHostConnection<br/>宿主连接信息"]
    RuntimeServices["PluginRuntimeServices<br/>运行态服务集合"]
    DataSource["PluginDataSourceInitializer<br/>DAO 数据源初始化"]
    Servers["ApplicationServers<br/>服务编排"]
    RuntimeBridge["PluginRuntimeBridge.install<br/>安装框架桥"]
    NioServer["PluginNioServer<br/>插件 Socket 服务"]
    RuntimeServer["PluginRuntimeServer<br/>运行态服务入口"]
    HttpServer["PluginHttpServer<br/>HTTP 服务入口"]
    HttpConfig["PluginHttpServerConfig<br/>HTTP 路由配置"]

    Application --> Startup
    Startup --> Environment
    Startup --> Options
    Startup --> PluginConfig
    Startup --> HostConnection
    Startup --> RuntimeServices
    Startup --> DataSource
    Startup --> Servers

    Servers --> RuntimeBridge
    Servers --> NioServer
    Servers --> RuntimeServer
    Servers --> HttpServer
    HttpServer --> HttpConfig
    HttpConfig --> RuntimeBridge

    RuntimeServices --> PluginConfig
    RuntimeServices --> HostConnection
    NioServer --> PluginConfig
```

`PluginRuntimeServices` 在启动阶段创建，随后被立即拆成更窄的依赖传入各个服务。运行态服务端代码只接收自己需要的依赖，例如 `PluginConfig` 和 `PluginBootstrapService`。

## HTTP 控制流

```mermaid
flowchart LR
    Browser[浏览器 / 管理后台]
    Controller["web.controller<br/>页面与 REST"]
    RuntimeFeature["运行态能力服务"]
    Bridge["PluginRuntimeBridge<br/>框架桥"]
    Bootstrap["PluginBootstrapService<br/>插件启动 / 停止"]
    Session["PluginSessionRegistry<br/>会话注册表"]
    Values["PluginConfig / PluginHostConnection<br/>配置与宿主连接"]
    DAO["dao<br/>持久化访问"]

    Browser --> Controller
    Controller --> RuntimeFeature
    Controller --> Bridge
    Controller --> DAO
    Bridge --> Bootstrap
    Bridge --> Session
    Bridge --> Values
```

`PluginRuntimeBridge` 只服务于不能通过构造器接收依赖的框架创建对象。新的运行态代码应优先使用显式构造器依赖。

## 插件 Socket 流

```mermaid
flowchart LR
    subgraph PluginImpl["插件实现进程"]
        PluginMain["插件 main"]
        NioClient["zrlog-plugin-common<br/>NioClient / IOSession"]
        AnnotationScan["注解扫描<br/>@Service / @Capability / @ScheduledCapability"]
        ClientActionHandler["ClientActionHandler<br/>插件侧消息分发"]
        PluginService["IPluginService<br/>业务处理器"]
    end

    subgraph CoreRuntime["plugin-core runtime.plugin<br/>宿主侧插件运行时"]
        SocketServer["PluginCoreSocketServer<br/>Socket 接入"]
        ActionHandler["ServerActionHandler<br/>宿主侧消息处理"]
        Session["PluginSessionRegistry<br/>会话注册表"]
        Bootstrap["PluginBootstrapService<br/>插件注册与启动"]
        Lifecycle["PluginLifecycleService<br/>生命周期协调"]
        State["PluginRuntimeStateService<br/>运行态状态"]
        FeatureSetup["能力注册<br/>默认自动化初始化"]
        RuntimeFeature["运行态能力服务"]
    end

    PluginMain --> NioClient
    NioClient --> AnnotationScan
    AnnotationScan --> NioClient
    NioClient -- "初始化连接 INIT_CONNECT + Plugin.capabilities" --> SocketServer
    SocketServer --> ActionHandler

    ActionHandler --> Bootstrap
    ActionHandler --> Session
    ActionHandler --> Lifecycle
    ActionHandler --> State
    ActionHandler --> FeatureSetup
    ActionHandler --> RuntimeFeature

    RuntimeFeature -- "能力调用 CAPABILITY_INVOKE" --> ActionHandler
    ActionHandler -- "消息包 MsgPacket" --> NioClient
    NioClient --> ClientActionHandler
    ClientActionHandler --> PluginService
    PluginService -- "能力调用结果 CapabilityInvokeResult" --> NioClient
    NioClient --> SocketServer

    PluginService -- "通知发布 / 调度查询" --> NioClient
```

生命周期状态只描述插件连接、会话注册和路由可用性。能力、调度或默认自动化失败应保留在各自的运行态结果或日志路径里。

## 插件实现端边界

插件实现端不属于 `plugin-core`。它运行在插件进程内，依赖 `zrlog-plugin-common` 提供 Socket 协议、DTO、注解和客户端消息分发。

核心规则：

1. `plugin-core` 不扫描插件 classpath。
2. `zrlog-plugin-common` 在插件侧扫描 `@Service`、`@Capability` 和 `@ScheduledCapability`。
3. 插件能力元数据在 `INIT_CONNECT` 阶段通过 `Plugin.capabilities` 发送给 core。
4. `plugin-core` 注册这些元数据，并通过 `CAPABILITY_INVOKE` 等 Socket 消息调用插件工作。
5. 插件业务代码实现 `IPluginService` 等处理器；它不直接修改 plugin-core 的调度状态。
6. 插件发起的运行态调用，例如 `NOTIFICATION_PUBLISH` 和 `SCHEDULER_QUERY`，通过 `ServerActionHandler` 进入 plugin-core。

## 依赖规则

1. `web` 可以依赖 `runtime`、`dao`、`model`、`vo` 和 `util`。
2. `runtime` 不能依赖 `web`。
3. 插件 Socket 流量从 `runtime.plugin.transport` 进入，不能从 `web` 进入。
4. 插件进程、会话、启动和生命周期代码放在 `runtime.plugin` 下。
5. 运行态能力包通过 `PluginBootstrapService`、`PluginSessions` 和 `PluginFiles` 等公开入口调用插件侧能力。
6. `runtime.plugin.artifact` 只处理文件，不依赖 bootstrap 或 lifecycle。
7. `runtime.plugin.bootstrap` 可以依赖 artifact、process 和 session，因为它负责协调启动。
8. `runtime.plugin.lifecycle` 负责 process 与 session 之间的注册、停止、删除等横向协调。
9. HTTP 服务配置属于 `web.config`。
10. 插件运行态配置值属于 `runtime.plugin.config`，由启动层装配进 `PluginRuntimeServices`。
11. 数据源初始化是明确的启动工作，保留在 `PluginDataSourceInitializer`；`PluginConfig` 是值对象，不负责初始化。
12. `Application` 保持为 JVM 入口。参数解析、环境准备和服务编排分别交给启动层类。
13. `ApplicationServers` 只启动两类服务：运行态插件服务和 web 侧 HTTP 服务。
14. 插件生命周期状态绑定到宿主连接和路由可用性。能力、调度、默认自动化失败应保留在各自的运行态结果或日志路径。
15. `ApplicationServers` 负责服务装配：它把 `PluginRuntimeServices` 拆成 `PluginRuntimeServer` 所需的窄依赖，同时把同一组服务传给 `PluginHttpServerConfig` 用于 web 桥接。
16. `PluginRuntimeServer` 不能持有完整的运行态服务集合；它只持有启动和停止运行态服务所需的依赖。
17. `PluginRuntimeServices` 是启动装配结果，不是通用 application context，也不是 web 控制运行态的 API。新代码应优先使用显式构造器参数或 `PluginRuntimeBridge` 的窄方法。
18. 插件实现代码在 `plugin-core` 之外；core 不能扫描插件 classpath。
19. 插件侧元数据由 `zrlog-plugin-common` 收集，并通过 `Plugin.capabilities` 等 Socket 通讯模型发送给 core。

## 主要包

`com.zrlog.plugincore.server.web.controller`
: 标准 MVC controller 层，负责管理后台页面和 HTTP API。

`com.zrlog.plugincore.server.web.config`
: HTTP 服务路由、静态资源映射、HTTP 拦截器，以及 web controller 使用的运行态桥接安装。

`com.zrlog.plugincore.server.web.PluginHttpServer`
: HTTP 服务生命周期包装，负责组合 `PluginHttpServerConfig` 和 `WebServerBuilder`。

`com.zrlog.plugincore.server.web.handler`
: 插件渲染页面的 web 适配层。它解析目标插件会话并代理 HTTP 数据包，但不拥有插件启动内部逻辑。

`com.zrlog.plugincore.server.web.util`
: web 专用工具，例如把内存中的运行态列表分页成 commonDAO `PageData`。

`com.zrlog.plugincore.server.runtime.PluginRuntimeServices`
: 启动装配结果，包含插件运行态服务和启动后不可变的值。它替代之前的 context 对象，但不能作为宽泛业务 API 使用。

`com.zrlog.plugincore.server.runtime.PluginRuntimeBridge`
: 窄框架桥，用于不能通过构造器注入依赖的代码。优先使用 `pluginBootstrap()` 等明确方法，不传递完整 services 对象。

`com.zrlog.plugincore.server.runtime.plugin.PluginRuntimeServer`
: 运行态服务生命周期。它启动插件 NIO transport，并在非 native-agent 模式下启动运行态 worker。它直接接收 NIO、bootstrap 和 scheduler 依赖，不持有完整运行态服务集合。

`com.zrlog.plugincore.server.runtime.plugin.config`
: 插件运行态值对象，包括插件路径、FaaS 运行根目录、master 端口、blog 运行模式、宿主连接，以及明确的数据源初始化。

`com.zrlog.plugincore.server.runtime.plugin.transport`
: 插件进程连接回 plugin-core 的 TCP/Socket 适配层。

`com.zrlog.plugincore.server.runtime.plugin.session`
: 内存会话注册表，以及兼容现有查询调用的门面。

本地 V4 插件的心跳过期后，请求线程和后台扫描都先通过现有 Socket 探测，再决定是否关闭会话。
Lambda 可能同时冻结 core 和插件进程，不能仅凭冻结期间经过的墙钟时间判定插件失联，否则恢复后的首页请求会触发不必要的插件重启。
探测仍使用 3 秒超时；同一会话的并发探测复用新取得的心跳结果。不支持该心跳协议的旧版插件保留原过期规则。
这与数据库里的运行实例租约是两层机制，不改变远端实例的租约清理规则。

`com.zrlog.plugincore.server.runtime.plugin.process`
: 本地插件进程启动器、进程输出、退出监听、进程 ID 和运行态实例 ID。

`com.zrlog.plugincore.server.runtime.plugin.bootstrap`
: 启动编排、元数据收集、已安装插件文件对齐，以及异步 bootstrap。

`com.zrlog.plugincore.server.runtime.plugin.lifecycle`
: 跨会话注册表、进程运行态、插件元数据和运行态引用的注册、注销、停止、删除协调。

`com.zrlog.plugincore.server.runtime.*`
: 运行态能力，包括 capability、scheduler、notification、event、服务提供者选择、调用日志、状态和 KV 存储访问。

插件实现端
: 插件实现依赖 `zrlog-plugin-common`，通过插件侧注解声明 service/capability，使用 `NioClient` / `IOSession` 连接回 core，并处理 `CAPABILITY_INVOKE` 等 Socket action。

## 管理界面共享主题

plugin-core 前端固定依赖 `@zrlog/ui@0.1.2`，通过 `useUiTheme` / `ThemeStyles` 复用九套主题、主色、明暗与密度；页面容器读取当前主题 token。当前没有 `@zrlog/utils` 的实际调用点，不引入该依赖。

外观继续走已有服务端请求头链路，不增加浏览器通信：后台鉴权后解析管理员个人偏好，缺失字段继承站点默认，由 base 代理生成 `Admin-Theme`、`Dark-Mode`、`Admin-Color-Primary`、`Admin-Compact-Mode`。plugin-core 的 `AdminTheme` 忽略头名称大小写，缺失值从站点设置回退，随后将 `theme`、`dark`、`primaryColor`、`compactMode` 写入插件页面初始化数据及 `/api/plugins` 响应。新字段也保留在转发给业务插件的请求头中，插件可按需使用。

未设置主题时使用 `default`，缺失密度时为普通密度；未知主题由共享 UI 包回退默认主题。没有新增用户偏好持久化或鉴权协议。`PluginListResponse` 新字段随现有 Native warmup 序列化覆盖；base 的 `PluginAdminAppearance` 仅在单次请求内直接调用，不经 Gson。部署时需同步更新 base、admin-web 与 plugin-core。
