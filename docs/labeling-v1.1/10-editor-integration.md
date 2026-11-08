# Label Studio 领取模式接入与接口交接

## 范围与分工

本阶段按产品和业务API要求实现领取模式：项目导入、打包发布、标注员领取由已有平台负责；本适配器使用当前登录用户的有效assignment、包内sample和发布时绑定的specVersion打开编辑器，并将校验后的结果写入业务草稿。项目owner或标注管理员没有绕过领取的编辑权限。

本轮只修改后端、后端提供的编辑器受控HTML/交互脚本、示例和文档；不修改正式前端业务代码。旧项目直标前端功能分支已移除，不作为此次交付；当前交付是后端接口，由前端开发人员按本文接入正式“我的任务”入口，不需要另写LS通信。

## 完整链路

```text
已有平台：导入 → 打包发布 → 领取，取得assignmentId
→ GET assignments/{id}/items，取得包内sample、fileUrl及冻结规范
→ POST assignments/{id}/editor-sessions
→ 同源新窗口或iframe加载返回的editorUrl
→ 受控HTML加载固定LS官方编辑组件、当前图片及本人草稿
→ 标注员选择类别并点击保存
→ 后端在短事务中检查权限/revision并记录SYNCING操作
→ 事务外写入LS并GET读回，核对技术作者、外部任务及分类
→ 短事务重查领取权限/revision，按冻结规范转换成labelId
→ 复用ResultService保存annotation_draft，与映射同步状态一起提交
→ 通知前端；前端GET本人草稿确认显示
→ 全包完成后，由平台调用既有submissions接口正式提交
```

每张图片一条LS任务；同样本不同领取具有独立映射和答案。当前每个领取＋图片建立一个外部LS项目/任务。一个会话只显示一张图片；图片列表、上一张/下一张、返回和整包提交由平台前端组织。会话不是整包提交，也不打开LS完整管理后台。

## 接口清单

统一前缀`/api/labeling/v1`。成功返回`{data,requestId}`，失败`{error:{code,message},requestId}`。
平台API携带既有`Authorization: Bearer <平台Token>`；身份从IdentityClient和ActorService取得，不接受客户端指定保存人。

| 方法与路径 | 状态 | 用途 |
|---|---|---|
| GET /assignments?scope=mine | 已有业务接口 | 本人领取列表 |
| GET /assignments/{id}/items | 已有业务接口 | 包内图片、文件地址、冻结规范和草稿 |
| POST /assignments/{id}/editor-sessions | 本次新增 | 指定领取内一张图片的编辑入口 |
| POST /assignments/{id}/editor-sync | 本次新增 | 外部保存异常后的读回核对与同步 |
| GET /assignments/{id}/drafts/{sampleId} | 已有业务接口 | 读本人草稿/确认保存 |
| PUT /assignments/{id}/drafts/{sampleId} | 已有业务接口 | 平台标准草稿协议；LS流程由适配器复用同一ResultService |
| POST /assignments/{id}/submissions | 已有业务接口 | 正式提交整包，不由编辑器自动调用 |

两个新增POST均需`Content-Type: application/json`和`Idempotency-Key`，正文：

```json
{"sampleId":"该领取包内的图片UUID"}
```

### 打开编辑器

会话响应data示例（ID为占位）：

```json
{
  "editorSessionId":"会话UUID",
  "assignmentId":"领取UUID",
  "projectId":"项目UUID",
  "sampleId":"图片UUID",
  "actorId":"服务端确认的标注员UUID",
  "specVersionId":"发布时绑定的规范UUID",
  "mode":"ASSIGNMENT_IMAGE_CLASSIFICATION",
  "editorUrl":"/api/labeling/v1/editor-ui/会话UUID/launch?ticket=一次性票据",
  "launchExpiresAt":"UTC时间",
  "expiresAt":"UTC时间",
  "draft":{"assignmentId":"领取UUID","sampleId":"图片UUID","revision":0,"explicitDone":false,"resultFormat":"image-classification-v1","result":{},"savedAt":null}
}
```

入口60秒一次性，会话30分钟。相同幂等键和图片在入口未使用且有效时返回同一会话；已使用、过期、密钥轮换返回409 EDITOR_ENTRY_EXPIRED，用新操作键重开。不得将Token或服务密码放URL；不得记录ticket。

受控页面使用HttpOnly、SameSite=Strict、限会话路径Cookie。每次上下文、图片及保存请求重新核验启用状态、OPEN项目/任务包、本人有效领取及包成员；SUBMITTED/RELEASED/REVOKED均不能继续编辑。标签取领取绑定的冻结规范，不取项目最新规范。服务账号仅代表LS技术作者，平台真实actor来自领取映射。

### 保存与恢复

编辑器页面内部调用`PUT /editor-ui/{sessionId}/result`，携带会话Cookie，正文为`{baseRevision,result}`，result为LS单选choices数组。这是受控编辑器内部接口，正式前端不直接构造LS结果。

适配器回读校验后，平台草稿保存为：

```json
{"assignmentId":"领取UUID","sampleId":"图片UUID","revision":1,"explicitDone":true,"resultFormat":"image-classification-v1","result":{"labelId":"normal"},"savedAt":"保存时间"}
```

normal必须来自冻结规范的稳定ID。LS显示名称只用于适配，不作为业务结果主键。打开/刷新时读取业务草稿，将labelId反向转换成LS选择。未保存为revision=0、result={}，不能解释成“正常”。成功保存进入IN_PROGRESS；正式提交后不能继续update，也不能重新打开编辑会话，个人结果通过已有草稿/提交查询接口只读查看，本适配器未提供提交后的只读编辑器页面。

LS保存操作在数据库中预留，网络调用不持有业务行锁。相同baseRevision和已确认相同内容的编辑器重试返回原草稿，不重复加版本；不同内容或被其他操作更新则409。业务原有PUT仍按其CAS契约处理，响应丢失先GET确认。前端不要同时启用试选标签直写和编辑器同步两条保存路径。

### 异常同步

外部写入/回读超时后，不确认平台保存成功，映射保留SYNCING；不要盲目重发POST/PATCH。调用editor-sync，后端只GET读取唯一外部答案，核对作者、任务、冻结规范和已记录的待保存内容，再按原baseRevision保存。多次同步不重复增加草稿版本；同一同步幂等键返回已记录响应。若期间平台草稿已变化，则409 REVISION_CONFLICT，禁止迟到答案覆盖。一次已成功的同步响应不是之后LS实时状态的证明；展示结果仍以GET业务草稿为准。

PENDING导入、外部缺失/多答案、作者或内容异常、草稿竞争导致无法同步时，需要管理员对账；目前无自动修复工具。管理员不能通过把状态直接改READY绕过校验。

## 前端调用示例

以下为交接示例，不是前端仓库修改。先从`GET /me`取得actorId，从本人领取items取得sample和specVersionId：

```javascript
const response = await fetch(`/api/labeling/v1/assignments/${assignmentId}/editor-sessions`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Idempotency-Key': operationKey },
  body: JSON.stringify({ sampleId })
});
const body = await response.json();
if (!response.ok) throw new Error(body.error?.message || '打开失败');
const session = body.data;
// 检查assignmentId/sampleId/actorId/specVersionId与当前页面一致。
const url = new URL(session.editorUrl, location.origin);
if (url.origin !== location.origin ||
    url.pathname !== `/api/labeling/v1/editor-ui/${session.editorSessionId}/launch`) throw new Error('无效入口');
// 将url赋给预先打开的新窗口，或同源iframe；勿输出到日志。
```

编辑器向同源parent/opener发送`labeling:ready`、`labeling:saved`、`labeling:save-failed`，含editorSessionId、assignmentId、projectId、sampleId、actorId、specVersionId；保存成功还含revision。接收必须检查event.origin、event.source和全部上下文ID，然后GET drafts确认，不能只凭postMessage显示成功。

宿主可发送`labeling:save`、`labeling:request-state`、`labeling:discard`，带editorSessionId及唯一requestId；回复saved/save-failed、state、discarded。state含ready/saving/dirty。切图前处理未保存修改，保存失败或保存期间产生新修改时不自动切图。

Node18+命令示例，私有环境提供LABELING_API_BASE（含/api/labeling/v1）、LABELING_TOKEN、LABELING_ASSIGNMENT_ID、LABELING_SAMPLE_ID：

```text
node labeling-service/examples/assignment-editor-integration.mjs open
node labeling-service/examples/assignment-editor-integration.mjs read
node labeling-service/examples/assignment-editor-integration.mjs sync
```

open默认隐藏票据入口；仅本人私下查看时可设LABELING_SHOW_ENTRY=true。不要分享输出或发送Token。示例不自动领取、不自动提交。

## 错误与交付边界

400检查必填字段、UUID及幂等键；401重新从平台打开/登录；403身份停用；404他人领取或包外图片；409领取/项目关闭、幂等冲突、revision冲突或需对账；422非法分类；503外部服务/存储不可用。保留本地选择并明确失败，不能自动当作保存成功。

后端入口与同步由AssignmentEditorController/Service负责，受控页面由EditorUiController提供。V6只增加editor_mapping/editor_session；复用业务V5草稿和提交，不创建project_annotation。当前没有实现任务领取页面、导出、释放/回收API或生产部署，相关状态拒绝已在适配器校验。

部署见[部署说明](11-editor-deployment.md)，验证和剩余限制见[验证记录](12-editor-verification.md)。
