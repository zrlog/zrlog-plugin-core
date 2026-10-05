# zrlog-plugin-core

ZrLog 插件运行时服务。负责插件启动、运行状态、功能注册、定时任务、通知渠道路由和运行时管理页面。

## 功能

- 启动和管理本地插件进程
- 展示插件运行状态、调用日志和通知投递记录
- 管理插件定时任务和外部触发入口
- 为插件提供运行时调用、通知渠道和静态页面代理
- 管理页面及核心接口提示支持中英文，跟随后台站点语言设置；维护方式见 [i18n 说明](docs/i18n.md)

## 构建

公共协议固定依赖 `zrlog-plugin-common:4.0.5`，数据库工具固定依赖 `common-dao:1.1.11`。先确认公共库正式制品已发布到 Maven Central，再合入依赖升级并发布运行时。构建在 `validate` 阶段拒绝项目、父 POM 及直接、传递依赖中的 SNAPSHOT。

```shell
export JAVA_HOME=${HOME}/dev/graalvm-jdk-latest
export PATH=${JAVA_HOME}/bin:$PATH
```

Linux Native 和 FaaS Native workflow 会在上传前通过 `zrlog-artifact-service` 处理可执行文件。
仓库需要配置 `ARTIFACT_SERVICE_TOKEN` GitHub Actions Secret；Windows 和 macOS 构建不调用
该服务。

更多插件开发说明见 [ZrLog 插件开发文档](https://blog.zrlog.com/zrlog-plugin-dev.html)。
