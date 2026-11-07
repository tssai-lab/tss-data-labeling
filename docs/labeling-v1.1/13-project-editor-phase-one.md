# 第一阶段：已有项目和图片直接进入 Label Studio

2026-11-07。按本次明确的阶段要求新增此模式。无需发布任务包或领取，代码也不会在后台自动生成包/领取。原 assignment 接口保留，属于后续按任务标注模式，不作为本阶段调用前提。

2026-11-08交接：本实现准备提交到个人功能分支供审查，不直接合并部署。当前项目模式仅限本人项目；V5/V6迁移、接口兼容性及编辑器资源自动构建需要团队评审后再上线。本文的验证结论针对本地环境，分支提交不代表服务器已接入。

## 1. 使用流程和权限

```text
平台正常登录 → 打开本人已有项目 → 选择已导入图片
→ POST 项目 editor-sessions → 打开受控 Label Studio 编辑页面
→ 选择分类并保存 → 后端写入LS并读回校验 → 平台保存本人分类结果
```

沿用现有项目与图片接口的权限：当前只支持项目创建者在自己的项目内标注。项目管理者同时作为当前标注员，实际身份由登录 Token 验证。没有借此向所有标注员开放他人的项目；协作授权需后续明确增加，不能用图片 ID 绕过项目权限。

项目 DRAFT 或 OPEN 均可用于本阶段；图片必须已解析为 READY、属于该项目，任务类型为 IMAGE_CLASSIFICATION，并已配置分类标签。暂停、归档、禁用账户、无权访问、跨项目图片均拒绝。

规范取项目最新 spec_version。会话绑定规范版本；规范变更后旧会话拒绝读写，重新打开绑定新规范。新版本结果独立保存，读取接口默认返回最新规范下的本人结果；旧版本记录保留，不把旧类别强行套到新规范。

## 2. 给宋雷鸣的两个主要接口

前缀 `/api/labeling/v1`，沿用现有 `Authorization: Bearer <平台Token>`。统一响应 `{data,requestId}`。不传 actorId、用户名、LS Token 或领取 ID。

### 打开编辑器

`POST /projects/{projectId}/editor-sessions`

请求头：Authorization、Content-Type: application/json、Idempotency-Key。正文：

```json
{"sampleId":"平台已经导入的图片UUID"}
```

响应 data 示意：

```json
{
  "editorSessionId":"会话UUID",
  "projectId":"项目UUID",
  "sampleId":"图片UUID",
  "actorId":"平台后端识别的当前用户UUID",
  "specVersionId":"当前分类规范UUID",
  "mode":"PROJECT_IMAGE_CLASSIFICATION",
  "editorUrl":"/api/labeling/v1/project-editor-ui/会话UUID/launch?ticket=一次性值",
  "launchExpiresAt":"ISO8601时间",
  "expiresAt":"ISO8601时间",
  "config":"后端生成的分类XML",
  "draft":{"revision":0,"explicitDone":false,"resultFormat":"label-studio-json","result":[]}
}
```

draft 实际还包含项目、图片、用户、规范 ID 和 savedAt；未保存 savedAt=null。入口60秒一次性、会话30分钟。相同幂等键和请求返回原会话；入口使用或过期后，以新键重新打开。不得把Token放入URL或把ticket写入日志。

前端只需调用接口，校验返回地址属于平台同源 `/api/labeling/v1/project-editor-ui/`，再通过同页导航、独立窗口或同源iframe打开。异步弹窗先在用户点击时创建空窗口，避免浏览器拦截。

### 读取平台已保存结果

`GET /projects/{projectId}/samples/{sampleId}/annotation`

响应 data 示意：

```json
{
  "projectId":"项目UUID",
  "sampleId":"图片UUID",
  "actorId":"当前用户UUID",
  "specVersionId":"当前规范UUID",
  "revision":1,
  "explicitDone":true,
  "resultFormat":"label-studio-json",
  "result":[{"id":"category","from_name":"category","to_name":"image","type":"choices","value":{"choices":["正常"]}}],
  "savedAt":"ISO8601时间"
}
```

结果以 project＋sample＋actor＋specVersion 唯一保存。未保存revision=0、result=[]，不能展示为“正常”。刷新、返回项目页面后可用此接口显示类别和保存状态。

## 3. 保存、切图与消息

编辑器内已有“保存分类并同步到平台”。保存包括：授权/规范/revision校验 → LS写入 → LS读回核对任务、技术作者、类别 → 平台持久化。只有完成平台保存才显示成功。平台结果的实际用户来自经过验证的actor，LS技术账号不等于标注员。

C不需要调用原生LS API或另写一份分类保存逻辑。若需要从外部统一触发保存，沿用消息协议：发送 `labeling:save`，附 editorSessionId 和 requestId，等待 `labeling:saved` 或 `labeling:save-failed`。成功后再GET annotation确认revision，才能切到下一张。

编辑器加载后发 `labeling:ready`。本模式消息包含 editorSessionId、projectId、sampleId、actorId、specVersionId；不使用assignmentId。父页面核对event.origin、event.source和这些ID。状态检查 `labeling:request-state` 返回 ready/saving/dirty；用户明确确认丢弃后可用 `labeling:discard`。不要在正在保存时切图，或在未保存时静默丢弃。

保留一个直接保存API供集成：

`PUT /projects/{projectId}/samples/{sampleId}/annotation`

```json
{
  "editorSessionId":"本人该图的有效会话UUID",
  "baseRevision":0,
  "result":[{"from_name":"category","to_name":"image","type":"choices","value":{"choices":["正常"]}}]
}
```

通常由编辑器受控页面调用。同一baseRevision及相同已保存内容可在响应丢失后重试；旧revision和不同内容返回409，不覆盖新结果。会话必须属于本人、当前项目、图片及规范。非法类别422；服务失败503；失败不返回成功或自动跳图。

没有另增“整包提交”或外部结果任意导入接口。这里保存的是本阶段个人图片分类结果，不自动转换为未来assignment的正式提交。

## 4. 调用示例

[project-editor-integration.mjs](../../labeling-service/examples/project-editor-integration.mjs)，Node18+，使用私有环境变量：

```text
LABELING_API_BASE=http://127.0.0.1:8088/api/labeling/v1
LABELING_PROJECT_ID=已有项目UUID
LABELING_SAMPLE_ID=该项目已有图片UUID
LABELING_TOKEN=仅在私有终端配置，不发送给他人
```

```text
node labeling-service/examples/project-editor-integration.mjs open
node labeling-service/examples/project-editor-integration.mjs read
```

open默认隐藏一次性入口；仅本地私下手工演示需要时设置LABELING_SHOW_ENTRY=true。C正式页面直接使用接口响应的editorUrl，不需要执行该脚本。本文中的本机地址不代表同事可远程访问或服务器已经提供新接口。

## 5. 配置、存储与部署

新增 `LABELING_PROJECT_EDITOR_ENABLED=true`；继续配置LABELING_EDITOR_PROJECTS项目白名单、LS地址和仅后端可用的服务凭据。原LABELING_ASSIGNMENT_EDITOR_ENABLED不必开启。默认项目模式关闭，白名单为空。

新增 V6__create_project_editor.sql，仅增加 project_editor_mapping、project_annotation、project_editor_session 三张表，分别存外部映射、个人分类结果和受控会话。不新建项目、复制平台业务库、迁移原图片或生成领取。项目/图片/用户仍引用已有表；与V5的领取结果分开，避免混淆归属。

交由A审查迁移编号及是否复用团队已有等价表，再按团队流程迁移。现有V5及领取代码未被删除；不要在服务器手工重复运行脚本。LS技术凭据不放进源码。

正式环境需同源转发 project-editor-ui 和 editor-assets，检查HTTPS、Cookie、iframe策略与入口日志脱敏。当前仍有编辑器生成资源未纳入自动CI构建的交付缺口，不能只合并源码就假定新JAR包含编辑器；原先的静态资源准备要求仍适用。

回滚先关闭LABELING_PROJECT_EDITOR_ENABLED，再恢复已验证旧制品并核对Flyway兼容性；保留数据表、原图和LS数据卷，不通过删表删卷回滚。旧ZIP/JAR不是本阶段最新产物。

## 6. 验证边界及交接

新增集成测试验证现有DRAFT项目无需发布/领取即可保存；包/领取记录数量不变；跨项目图片、他人项目、匿名、错会话、过期、暂停、旧规范、非法标签、版本冲突和一次性入口受到限制。

另用真实登录身份、已有本地项目及图片、真实本地LS和实际浏览器验证打开、保存、修改、刷新恢复和平台读回。验证脚本未调用创建项目、上传、发布或领取接口；但所用项目仍是此前准备的本地验收项目，不是服务器业务项目。

本次全量Maven package：49项，0失败、0错误、3项可选测试跳过；新增项目模式4项全部通过，原领取模式同时回归。另行执行真实浏览器验证通过，消息/请求JS测试7项通过。JAR内已检查包含编辑器main.js/main.css和V6；这不表示CI资源构建缺口已补齐。

脱敏浏览器记录：[editor-evidence/project-editor-acceptance.json](editor-evidence/project-editor-acceptance.json)。本地截图保存在 `.tools/local-platform/project-editor-1.png` 和 `project-editor-2.png`，不把原图或浏览器凭据目录放进源码仓库。

给C：本页两个主要API及消息协议。给A：权限边界、V6及原草稿契约的衔接评审。给B：继续复用稳定sampleId、项目标签和受授权图片，不要求新增专用API。线上原项目与正式页面的最终联调仍待团队部署；未推送或部署服务器业务服务。

## 7. 项目页面按钮（后续按用户要求新增）

当前前端新增 `ProjectEditorEntry.tsx`，并在原图片预览区域接入“打开 Label Studio 标注”和“读取已保存结果”。保留原分类试选并明确标记此处不保存；实际保存使用编辑器。未增加任务/领取页面。

入口在点击时先创建窗口，申请会话并核对同源地址、项目、图片、用户和规范后导航。保存通知校验消息来源窗口、同源和全部上下文ID，再调用平台annotation接口确认结果；选图、切换项目后旧请求不得覆盖当前状态。

本地前端 `http://127.0.0.1:8020/#/labeling`，标注代理连接本机8088；原8001启动脚本仍连接服务器，不应用于验证尚未部署的新接口。启动脚本为Annotation根目录 `start-project-editor-frontend.ps1`，后端仍使用 `start-local-editor-demo.ps1 -RealIdentity`。两者均保持终端运行。

按钮端到端浏览器验证通过：原项目页面选图 → 点击按钮 → 正确图片的编辑器 → 保存 → 原页面自动读回本人结果；TypeScript检查通过。脱敏记录见 [editor-evidence/project-button-acceptance.json](editor-evidence/project-button-acceptance.json)。新增前端仅在本地，等待C审查设计并按团队流程提交。
