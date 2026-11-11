# 真实数据标注平台：侧栏与结果审核样式

2026-11-11。本轮以用户最新的《工业零件质检审核台.png》为视觉参考，将真实平台调整为左侧功能导航、中间结果表格、右侧样本详情，并保留旧版人工标注 Demo。

工作分支为 `feat/labeling-v11-frontend-gap`，起点 `3287eb391a010d39390bd1f9b32a09eb120c3d0f`。开始时工作区干净；获取远端后确认起点源码与已上线的 `frontend` 合并提交 `4a0eef57073c0a1c067bd7198cf41af7cf923fbb` 一致。未发现适用的 AGENTS.md。

## 页面与入口

- `/#/platforms`：训练平台、数据标注平台、旧版数据标注平台 Demo 三个独立入口。
- `/#/labeling`：使用同一页头、紫蓝色侧栏、浅色背景和白色面板。项目名称和“进入工作台”直接进入结果审核；“规范与数据”保留原有配置和导入流程。
- `/#/labeling/projects/:projectId/workbench`：结果审核表格、授权缩略图、四张统计卡片、范围及来源筛选、独立勾选、批量操作栏、可收起的右侧详情和上一张/下一张。
- 侧栏“任务发布”进入项目数据页的发布区域；没有选定项目时先选项目。成果导出、AI进度和提交记录保留对应入口，缺失能力显示待后端接入。
- 项目规范与导入、任务广场、我的任务、实际标注编辑器、只读提交详情及用户管理共享新版外壳。
- `/#/label-projects`、`/#/manual-label-server/:projectId` 的旧 Demo 页面、服务请求、上传和标注逻辑没有修改。侧栏也保留“旧版 Demo”。

手机上侧栏转为可横向滚动的功能导航，结果表格在自身容器内滚动，样本详情按顺序置于表格下方。

## 真实数据边界

工作台使用现有 `GET /projects/:id`、`GET /projects/:id/submissions`、`GET /submissions/:id` 和授权原图接口。按选中的已交回人工提交展示样本，不将某次提交的样本数冒充全项目数量；全量筛选计数和已加载行数分别显示，先加载20行。

标签按提交绑定的冻结规范解析，缺少规范时保留原始标签ID。人工结果的AI置信度为“不适用”；真实审核状态、已确认数量、AI模型批次及阈值尚无相应服务，显示“待接入”或“—”，不填入参考图的假数据。当前提交不提供文件名时明确说明，图片尺寸从实际授权图片读取。

核对过远端 backend 的 `labeling-service/.../tasks/ResultController.java`：当前支持人工草稿、整包交回及提交列表/详情，尚未提供需求方审核、修订、AI执行和固定成果导出。本轮不增加未实现的接口请求，不向生产混入Mock；相关操作保持禁用。切换提交或图片会取消旧请求，失败显示实际错误、code和requestId，可重试；暂停项目不请求原图。缩略图按可见区域延迟读取，离开页面释放本地图片URL。

本地合成审核演示 `/#/labeling/demo` 保持原有独立流程及生产禁用规则。恢复旧 Demo 卡片不改变这条规则。

## 本地运行与验收

```powershell
Set-Location -LiteralPath 'D:\ASUS\Documents\codex\tssai\labeling-v11'
npm run dev:labeling-demo
```

正常使用真实项目访问 `http://127.0.0.1:8001/#/platforms`，需要真实后端代理和登录。离线浏览器验收脚本会拦截HTTP请求并注入独立夹具，不能当作真实平台联调。

```powershell
node scripts/qa/labeling-platform.browser.mjs
$env:LABELING_CORE_ONLY = 'true'
$env:LABELING_BROWSER_OUTPUT = '.qa/platform-core-evidence'
node scripts/qa/labeling-v11.browser.mjs
$env:LABELING_REVIEW_EVIDENCE = '.qa/platform-owner-evidence'
node scripts/qa/labeling-review.browser.mjs
npm run tsc
npm test
npm run lint:governance
# 先停止开发服务，再构建、预览；开发服务会重建输出目录。
npm run build
```

浏览器测试使用本机已有 `.qa/node_modules/playwright` 与 Chrome，应用依赖及锁文件未修改。`LABELING_CORE_ONLY` 仅跳过旧版长页合成演示用例，实际标注保存、冲突、未保存导航保护和整包提交仍完整执行；合成审核由新的review脚本回归。

截图和报告在 [labeling-platform-style-evidence](labeling-platform-style-evidence/)。主截图为 [工作台桌面](labeling-platform-style-evidence/workbench-1586.png) 和 [手机](labeling-platform-style-evidence/workbench-390.png)，均来自合成HTTP夹具，图中项目名、提交和图片不是生产业务数据。完整检查结果见该目录的 `validation.json`，逐文件清单见 `changed-files.json`。上一轮已归档截图及证据未覆盖。

## 改动文件

- `src/pages/platforms/index.tsx`：恢复旧 Demo 卡片。
- `src/pages/labeling/WorkspaceLayout.tsx`、`Navigation.tsx`、`index.less`：统一页头、项目入口、紫蓝色导航和响应式外壳。
- `ProjectWorkflow.tsx`、`ProjectReviewWorkbench.tsx`、`projectReview.less`、`projectReview.less.d.ts`：真实结果审核表格与右侧详情。
- `index.tsx`：项目直达审核台、保留规范入口及发布区域。
- `taskList.tsx`、`assignment.tsx`、`submission.tsx`、`admin.tsx`：应用共享外壳，保留原业务处理。
- `scripts/qa/labeling-platform.browser.mjs`：本轮平台与工作台验收。
- `scripts/qa/labeling-review.browser.mjs`、`labeling-review-mode.browser.mjs`、`labeling-v11.browser.mjs`：适配真实工作台入口和分开存放本轮回归材料。
- 本说明及 `docs/labeling-platform-style-evidence/`：验收截图、报告和清单。

不在本轮创建PR、合并到frontend或部署平台；此类动作需要在这份可验收结果上另行决定。
