# 前端连接与部署说明

## 开发环境

默认仅连接开发机自身的后端和 MLflow，不会自动连接 Main、Second 或实验室服务器。

```bash
npm ci
npm run dev:local
```

远端联调必须明确配置，以下为 Linux shell 示例。将占位地址替换为获准使用的网关地址：

```bash
export DEV_API_TARGET=https://YOUR_PLATFORM_GATEWAY
export DEV_MLFLOW_TARGET=https://YOUR_PLATFORM_GATEWAY
export DEV_MLFLOW_THROUGH_GATEWAY=true
npm run dev
```

Windows PowerShell 对应使用 `$env:DEV_API_TARGET = 'https://YOUR_PLATFORM_GATEWAY'` 设置环境变量。
不再提供 `DEV_SERVER=master/node` 或 `dev:master/dev:node` 的机器预设。
直连 MLflow 时，将 `DEV_MLFLOW_TARGET` 设为 MLflow 服务地址，并取消 `DEV_MLFLOW_THROUGH_GATEWAY`；开发代理会把 `/mlflow-api` 改为 `/ajax-api`。

## 生产部署边界

浏览器对同一个站点请求 `/api`、`/mlflow-api` 和 `/v3/api-docs`；由部署网关转发到对应服务。
`config/proxy.ts` 不会随着 `dist/` 变成生产代理；生产必须另外配置网关。
浏览器中的 `127.0.0.1` 指访问者自己的电脑，不能用它代表远端后端。

```bash
npm ci
npm run tsc
npm test
npm run build
```

正式交付使用上述构建生成的静态资源或固定摘要的前端镜像，不要求甲方服务器现场联网安装 npm 包。
宿主机 Ubuntu 版本由平台离线安装方案核验，前端静态资源本身不绑定某个 Ubuntu 版本。

## 辅助预览（不替代正式安装器）

需要已经安装 Node.js 和本项目依赖。若已有 `dist/`，可运行：

```bash
HOST=127.0.0.1 PORT=8000 \
BACKEND_PROXY_TARGET=http://127.0.0.1:8080 \
MLFLOW_PROXY_TARGET=http://127.0.0.1:5000 \
node scripts/serve-dist.cjs
```

该服务提供静态文件与代理；外部访问需要另行配置监听、TLS 和防火墙。不为联调开放数据库、MinIO 管理端或后端内部回调端口。
验收应包含登录、上传、训练详情指标、API 文档以及接口失败提示，不以“首页能打开”代替验收。
