# 插件运行时前端

在本目录安装锁定依赖并启动开发服务器：

```sh
yarn install --frozen-lockfile
yarn start
```

提交前按改动运行类型检查、测试和生产构建：

```sh
yarn type-check
CI=true yarn test --watchAll=false --runInBand
yarn build
```

脚本以 [package.json](package.json) 为准；文案维护见 [i18n 说明](../../../docs/i18n.md)。
