# tss-data-labeling：前端分支

本分支 `frontend` 保存 2026-11-03 本地 Trunk 工作区的源码快照，包含尚未提交到原仓库的修改；不代表生产环境已验收。

两个分支分别保存前端与后端，请克隆到两个目录，不要把两条分支相互合并：

```bash
git clone --branch frontend --single-branch https://github.com/tssai-lab/tss-data-labeling.git frontend
git clone --branch backend --single-branch https://github.com/tssai-lab/tss-data-labeling.git backend
```

## 当前功能范围

- 统一登录、平台选择页与训练平台：现有源码。
- 数据标注平台 Demo：已有人工标注页面与独立 Python 服务。
- 新数据标注平台：目前只有待接入入口与 V1.1 产品、架构、数据库、接口和分工设计，尚未完成新业务服务。最近的数据库审查建议尚未全部落实到设计稿或建表脚本。
- 后端分支 `docs/labeling-v1.1/README.md` 为新标注平台文档索引。

## 配套运行

前端按下方开发说明安装依赖并配置代理；训练后端按后端分支 `backend/README.md` 配置数据库和依赖；Demo 按后端分支 `manual-label/README.md` 启动。仓库不包含本机数据库、用户上传数据、运行凭据、依赖目录及构建产物。文中 `/home/leriger/` 绝对路径描述原维护者环境，其他机器应按实际配置替换。

# TSS AI 平台前端

基于 Umi 4.6.61、TypeScript 5.9.3、React 19 和 Ant Design 5。
本仓库是前端源码；仅启动前端不能完成平台部署，还需要后端、数据库、对象存储和 Kubernetes。

## 本地开发

需要 Node.js 20 及以上、npm，以及已经可访问的后端。以 `package-lock.json` 为依赖基线，不混用其他包管理器的锁文件。

```bash
npm ci
npm run dev:local
```

`npm ci` 会执行 `max setup` 生成 Umi 类型与运行时代码。
`dev:local` 监听本机 8001 端口，后端默认 `http://127.0.0.1:8080`，MLflow 默认 `http://127.0.0.1:5000`。
`npm start` 使用 3000 端口；以终端实际输出为准。远端联调请先按 [本地联调说明](docs/local-integration.md) 配置根目录 `.env.local` 并重启开发服务器；克隆仓库不会带入其他机器的联调环境配置。通用部署说明见 [连接与部署说明](DEPLOY_SERVER.md)。

## 修改后的检查

```bash
npx max setup
npm run tsc
npm test
npm run lint:governance
npm run build
```

- 类型检查、Node 测试、生产构建是不同检查，不能互相代替。
- `npm test` 执行仓库中的 `*.test.mjs` 等测试；不等于浏览器全页面验收。
- 构建产物为 `dist/`。`prebuild` 将本地 Swagger UI 资源复制到静态目录，内网 API 文档页面不依赖外部 CDN。
- 不手工修改 `node_modules/`、`.umi/`、`dist/`；修复源码后重新生成。

## 从哪里开始读代码

先查[代码维护索引](docs/代码维护索引.md)定位功能和必跑测试，再查[接口契约与修改指南](docs/接口契约与修改指南.md)确认字段、状态和影响范围。

| 目录或文件 | 职责 |
| --- | --- |
| `config/routes.ts` | 页面路由、菜单和页面访问条件 |
| `config/proxy.ts` | 开发代理；无实际服务器 IP 预设 |
| `src/app.tsx` | 登录态初始化、请求处理、布局入口 |
| `src/access.ts` | 前端角色与按钮可见性；真正鉴权仍由后端执行 |
| `src/pages/` | 模型、数据集、训练、推理、系统管理页面 |
| `src/services/` | 请求、返回值适配和接口类型；`platform.ts` 是聚合导出入口 |
| `src/utils/` | 上传回执、下载、状态规范化等可复用逻辑与相邻测试 |
| `src/components/` | 页面共用的交互组件 |
| `scripts/` | 静态资源复制、本地预览等辅助脚本 |

## 维护约定

1. 新页面沿用“页面 → services → 后端 API”的模式，不在多个页面复制复杂请求逻辑。
2. 角色为 `super_admin`、`normal_admin`、`user`；不要把隐藏按钮当作安全校验。
3. 请求失败展示错误，不用 Mock 数据代替真实状态。旧 API 兼容与失败重试必须分开，不能在权限失败、限流或超时后自动换一个写接口重试。
4. 硬件列表来自后端集群检测；环境地址通过配置注入，不在业务代码写死卡号、节点名或 IP。
5. 中文注释重点解释业务规则、并发与失败边界；接口契约和测试一起维护。
6. TypeScript、Umi 版本已固定；依赖变更必须同步锁文件并重新检查。保留第三方许可文件，不将第三方源码当作项目冗余删除。

此说明描述当前代码组织与开发入口，不构成“所有页面或所有 Ubuntu 版本已验收”的声明。


## 统一工作空间

统一登录和平台选择页面保留，训练服务位于后端 `backend/`。

## 整体规划与当前进度

[项目整体设计与开发规划](https://github.com/tssai-lab/tss-data-labeling/blob/backend/项目整体设计与开发规划.md)说明统一入口与训练平台的代码位置。

## 本地手动标注演示模块

2026-11-02 从 GitHub demo 分支恢复手动标注。统一平台选择页提供训练平台和数据标注平台入口。前端页面为 `src/pages/label-projects` 和 `src/pages/manual-label`，后端位于后端 Trunk 的 `manual-label/`。启动脚本为 `/home/leriger/tss-trunk-local/start-manual-label.sh`；本地数据不与服务器共享。
