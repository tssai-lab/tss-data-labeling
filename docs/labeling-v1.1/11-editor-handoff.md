# D汤立娜：Label Studio 图片分类接入交接单

更新：本页主要描述原领取模式。本阶段按用户新确认要求，使用 [13-project-editor-phase-one.md](13-project-editor-phase-one.md) 中的项目＋图片入口，无需领取；原接口保留。

日期：2026-11-07。功能分支：`feat/tanglina-label-studio-integration`。

状态：后端接入及本地验证已完成；本地前端演示改动已按用户要求全部撤回。尚未 commit/push、合并、部署团队业务服务或执行共享数据库迁移。本文件为当前交接口径；旧交付 ZIP/JAR 早于最新消息通信改动，不能直接当作最新版部署。

## 1. 本次交付的功能

平台已登录标注员携带真实领取任务和图片 ID 创建会话，打开 Label Studio 编辑组件，选择类别并保存。后端校验用户、领取、图片、项目状态和模板，写入真实 LS 服务、读回校验，再持久保存平台分类进度。

实际结构：平台页面 → 平台受控编辑页面中的 LS 组件 → 平台保存接口 → LS 后端写入与读回 → 平台数据库。LS 的 completed_by 是后端技术账号；实际标注员由平台已验证 actor 与 assignment 的映射确定，不能将两者混称。

本次不交付平台项目/领取页面、正式整包提交、审核或矩形框标注。单图保存只是分类进度，不代表整包交付。

## 2. 给宋雷鸣：前端 API 与消息协议

统一前缀 `/api/labeling/v1`；HTTP API 使用现有平台 `Authorization: Bearer <token>`，响应统一 `{data,requestId}`。不传账号密码或 actorId 指定提交人，不调用 LS 原生 API。

| 接口 | 用途 | 归属 |
|---|---|---|
| GET /assignments?scope=mine&limit=20&cursor=... | 本人领取列表，items/nextCursor | A已有接口 |
| GET /assignments/{assignmentId}/items?limit=20&cursor=... | 包内图片，items/nextCursor/readOnly/specification | A已有接口 |
| POST /assignments/{assignmentId}/editor-sessions | 当前图片的编辑会话及入口 | D新增 |
| GET /assignments/{assignmentId}/drafts/{sampleId} | 本人分类结果与版本 | D新增实现 |
| POST /assignments/{assignmentId}/editor-sync | 再次回读核对 LS 结果 | D新增 |

两个 POST 请求头另带 `Content-Type: application/json`、`Idempotency-Key`，正文：

```json
{"sampleId":"真实图片UUID"}
```

editor-sessions 的 data 含 `editorSessionId,assignmentId,sampleId,actorId,editorUrl,launchExpiresAt,expiresAt,draft,config,mode`。editorUrl 是60秒一次性入口，会话30分钟；入口使用或过期后，使用新幂等键重新申请。Token 不放进 URL，入口 ticket 不写日志。

前端调用示意（复用现有 labeling.ts 的 post 和 operationKey）：

```typescript
const session = await post(
  `/assignments/${assignmentId}/editor-sessions`,
  { sampleId },
  operationKey(),
);
// 校验同源和 /api/labeling/v1/editor-ui/ 路径后，设置同源 iframe.src。
// 也可打开单图页面；不需要再登录 LS。
```

多图连续标注由前端组织任务包图片列表，在同一个编辑区域切换单图会话。每张图仍独立保存。D提供加载/保存能力与消息协议，C负责列表、返回、导航和进度的正式页面设计。

### 消息约定

父页面发送消息到 iframe.contentWindow，targetOrigin 固定为平台同源，不用 `*`。每条命令带唯一 requestId 和当前 editorSessionId。

```javascript
iframe.contentWindow.postMessage({
  type: 'labeling:save',
  editorSessionId: session.editorSessionId,
  requestId: crypto.randomUUID(),
}, window.location.origin);
```

| 命令 | 响应 | 附加字段 |
|---|---|---|
| 等待加载，无命令 | labeling:ready | revision |
| labeling:request-state | labeling:state | ready/saving/dirty |
| labeling:save | labeling:saved 或 labeling:save-failed | revision 或 message |
| labeling:discard | labeling:discarded | 确认解除当前未保存提醒，不写平台数据库 |

响应包含 editorSessionId/assignmentId/sampleId/actorId，命令响应另回传 requestId。编辑器自身保存按钮也会发送 saved，但可能没有 requestId。

接收时必须核对 event.origin、event.source===iframe.contentWindow，以及全部绑定 ID，不能仅检查消息 type。saved 后 GET drafts 再确认版本；成功后才能进入下一张。保存失败、版本冲突、超时、或者保存期间又产生修改时，停留当前图片。

切图或返回前 request-state：保存中等待；dirty=true 时让用户选择取消、先保存或丢弃。申请下一张入口成功后再确认 discard、替换 iframe，避免下一张加载失败导致丢失未保存提醒。未完成最后一张不自动算整包提交。

drafts 响应 data 为 `revision,explicitDone,resultFormat,result,savedAt`；未保存 revision=0。分类通常读取 `result[0].value.choices`，仍应处理空结果。列表分页不要把“已加载数量”误当成全包总数。

### 调用示例

可执行脚本：[editor-integration.mjs](../../labeling-service/examples/editor-integration.mjs)，要求 Node18+。在私有终端配置 `LABELING_API_BASE`（含 /api/labeling/v1）、`LABELING_TOKEN`、`LABELING_ASSIGNMENT_ID`、`LABELING_SAMPLE_ID` 后执行：

```text
node labeling-service/examples/editor-integration.mjs open
node labeling-service/examples/editor-integration.mjs read
node labeling-service/examples/editor-integration.mjs sync
```

open 默认不输出一次性入口到日志；仅私下手工演示时可设 LABELING_SHOW_ENTRY=true。不要把 token、Cookie、ticket 贴进交接记录。

**联调地址**：本次后端为 http://127.0.0.1:8088 ，只在运行该本地环境的机器上可访问，不是宋的可访问远程服务。宋本地开发需要启动相同后端版本并同源代理，或等待团队部署测试环境；服务器原服务尚未提供本次新增接口。

## 3. 给林玉洁：业务后端与迁移评审

**V5的含义**：数据库结构迁移文件的序号，不是新数据库或新一批项目。正式接入应使用平台已有项目和已导入图片，不要求用户重新创建/上传。当前新脚本用于补充结果、映射和会话存储；若团队已有对应表或接口，应复用并调整本实现，不重复建表。当前实现按领取＋图片授权；若首阶段页面只提供项目/图片，先与A确认如何取得有效领取/上下文及权限，不能直接放宽为任意项目图片可编辑。

- 审阅 editor 包、配置和 V5__create_editor_drafts.sql；核对团队最新迁移编号，避免V5冲突。
- 复用 actor/assignment/package_item/sample/spec，逐请求检查有效领取、包成员、项目和账号状态。外部技术作者与平台实际标注员分别保存。
- annotation_draft 是平台分类进度；editor_mapping 是平台与LS外部任务/结果映射；editor_session 是短时受控入口及会话。
- **契约差异需确认**：现有 PUT /assignments/{id}/drafts/{sampleId} 实现增加必填 editorSessionId；不能不经确认就称为完全兼容04-api。正式提交应使用已同步、校验的草稿版本，不能绕过统一保存逻辑。
- 正式整包提交、冻结、回收规则仍由A负责；共同核对暂停/回收/提交后的访问行为。
- 外部创建PENDING及外部保存成功、平台落库失败等情况需要对账，不盲目重复创建。相关处理及错误码见10文档。

## 4. 给汤笑诚：数据接口约定

复用导入后的稳定 sampleId、sourcePath、content checksum 和受授权的图片读取，不要求重新上传给标注员。外部 LS 导入由适配器处理。

本轮不需要给 B 新增专用 API。与B确认 ID/文件读取、存储备份及结果导出读取平台草稿/提交的边界即可；正式导出遵循平台授权和冻结清单，不能直接用 LS 全项目导出代替。

## 5. 版本、配置、部署与回滚

LS镜像标签1.23.2；组件来自同标签源码提交 df96d79183156e556d91825d15fa7721f1145e1a。官方镜像内部Python元数据1.23.0的已知标记差异，不代表本次改成1.23.0镜像。

```text
LABELING_ASSIGNMENT_EDITOR_ENABLED=true
LABELING_EDITOR_PROJECTS=允许测试的真实项目UUID
LABELING_EDITOR_BASE_URL=后端可访问的LS地址
LABELING_EDITOR_USERNAME=受控配置提供
LABELING_EDITOR_PASSWORD=受控配置提供
```

其他数据库/统一身份配置沿用现有部署约定。默认关闭、默认项目白名单为空；生产不要沿用测试用 `*`。LS服务凭据仅在后端。

正式环境检查 HTTPS、同源 editor-ui/editor-assets 转发、Cookie、iframe策略及 launch ticket 日志脱敏。上线前备份业务库、原始图片及LS数据卷。

回滚先关闭功能开关，再按团队流程恢复旧JAR并验证Flyway对已有新迁移的兼容性；保留新增表和LS数据，不通过删库/删卷回滚。

### 合并部署前的构建缺口

编辑组件生成目录 static/editor-assets 已被忽略，不在 Git 中。现有自动部署只执行 Maven，没有准备该目录。**仅推源码并直接合并会使新构建缺少编辑器资源，Maven成功不代表编辑器可用。**

需在合并前补齐团队认可的固定版本资源构建/制品取得方式，确保新checkout产生的JAR包含 main.js、main.css、动态chunk、图片和许可证。现有本地准备脚本不在backend仓库，不能假定CI可访问它。

可复现的基础步骤：取得官方固定提交源码，在其web目录使用Node22、Yarn1.22.22和锁文件安装，NODE_ENV=production、MODE=standalone、NX_DAEMON=false、NODE_OPTIONS=--max-old-space-size=4096，执行 `yarn nx run editor:build:production`。将 dist/libs/editor 的完整运行资源及上游许可证放入服务 static/editor-assets，再运行 Maven verify/package。CI落地与资源完整性检查尚待补齐。

## 6. 验证与待办

已验证与边界见 [12-editor-verification.md](12-editor-verification.md)。当前frontend仓库已无本轮改动；此前页面仅用于本地验收，未交付为正式页面。

| 事项 | 状态/对接人 |
|---|---|
| 单图保存、真实LS读回、真实身份本地多图验证 | 已通过，D提供记录 |
| 前端正式页面入口及多图流程 | 待C按接口接入 |
| 迁移编号、草稿契约兼容性、权限与提交 | 待A与D评审 |
| 编辑器静态资源进入自动构建 | 合并前补齐，D对接部署负责人 |
| 共享环境真实项目的双账号端到端验收 | 待团队部署后共同完成 |

## 7. 是否可以上传仓库

可以将源码、接口文档、测试、脱敏验证记录提交到自己的功能分支，发 **Draft PR → backend** 供审查；当前不应直接合并部署。

当前仓库存在部分已暂存文件与后续未暂存修改，commit前必须重新检查暂存差异，不能只提交旧暂存快照。不要使用不加审查的 git add .。

可提交：editor Java包、受控页面资源、application.yml的非秘密配置、V5迁移、FakeLabelStudio及集成测试、examples调用示例、本交接和验证文档。LocalEditorDemoTest与test resources是可选本地工具；若提交，确认仅显式环境变量启用、无真实凭据，不进入生产JAR。

不可提交：.tools、浏览器用户目录、数据库、图片原包、私有env、密码、Token、Cookie、ticket、本地日志、target或旧JAR/交付ZIP。除非团队另有制品策略，不把生成的二进制包加入源码PR。

仓库现有自动部署工作流仅在backend分支相关变更触发；本次功能分支推送不会触发该工作流的生产部署。这里是对本地工作流配置的检查，不代表已联网核查远端最新分支或所有保护规则。
