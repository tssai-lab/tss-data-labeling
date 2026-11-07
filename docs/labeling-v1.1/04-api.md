# 接口契约 V1.1

统一前缀为 `/api/labeling/v1`。本文包含已实现接口及后续设计。2026-11-07 以 `backend` 提交 `bbf022d` 为核对基线，身份与项目、图片导入与 owner 原图读取、用户权限、任务包与领取已实现并合入 `backend`。图片导入契约见第 3 节，身份权限和任务包的实际范围见文末补充。编辑器会话、草稿保存、结果提交、导出及其他规划接口按各章节标注推进。部署与真实账号验收进度由交接、验证记录单独记录。

## 1. 通用规则

- 使用现有统一登录Bearer Token，后端解析身份；不信任请求体中的owner/actor/role。标注管理员权限单独判断，不用训练roleId替代。
- 成功返回200/201；异步任务202。统一JSON响应 `{ "data": ..., "requestId": "..." }`，文件下载除外。
- 错误 `{ "error": {"code":"SLOT_FULL","message":"任务包已被领满"}, "requestId":"..." }`。
- 数据库业务ID为UUID，API以规范UUID字符串传递，调用方将ID视为不透明值；`identityUserId`保持统一身份服务给出的原始字符串。时间ISO8601 UTC；列表用cursor、limit（默认20，上限100），响应items/nextCursor。
- 创建、发布、领取、放弃、提交、导出等POST写操作必须带Idempotency-Key；同调用者/方法/资源/键和相同请求返回原结果，同键不同内容409。幂等记录至少保存到所属资源归档清理，清理后仍由业务唯一约束防重复。
- 草稿用baseRevision，分片用offset与sha256；二者按各自协议避免重复。403/404统一隐藏不属于调用者的资源，不能通过错误枚举他人数据。

领取者的原始样本、编辑器和草稿访问还须项目处于OPEN；暂停时本人不可继续读取工作数据。图片导入首轮允许 owner 在 DRAFT/OPEN 导入并预览自己的数据，以支持新建项目后的检查；ARCHIVED 可只读预览已导入图片，PAUSED 不可读取原图。该例外不授予领取者任何 DRAFT 数据权限。本人已提交结果可只读查看；owner可管理历史结果并导出。归档后禁止新增标注、导入和发布；owner仍可查看历史结果并创建只读结果导出任务。创建项目的taskType与规范taskType必须一致，不能跨类型发布。

## 2. 身份与项目

| 方法与路径 | 权限 | 输入要点 | 返回 |
|---|---|---|---|
| GET /me | 标注平台启用用户 | 无 | actorId、labelingAdmin、canCreateProject=true、canAnnotate=true |
| POST /projects | 任意启用人工用户，无需审批 | name、description、taskType | 201 projectId、ownerActorId、DRAFT、revision |
| GET /projects?scope=owned | 当前用户 | cursor/limit | 只列本人创建项目；不是全站项目原数据入口 |
| GET /projects/{projectId} | 项目owner | 无 | 项目与已有规范版本；批次和进度随数据导入模块补齐 |
| POST /projects/{projectId}/pause | owner或标注管理员 | reason、baseRevision | PAUSED和新revision |
| POST /projects/{projectId}/resume | owner或标注管理员 | baseRevision | OPEN和新revision；规范/批次需仍有效 |
| POST /projects/{projectId}/archive | owner或标注管理员 | reason、baseRevision | 无有效领取及执行中发布/导入任务后ARCHIVED，否则409 |
| POST /projects/{projectId}/spec-versions | owner | taskType、labels[{id,name}]、instructions、editorConfig、schemaVersion | 新specVersionId；不能原位改已发布规范 |

上表中的身份、建项、本人项目列表和项目详情接口已实现并合入 `backend`。服务将原始 `Authorization` 头转交训练后端 `/api/user/current-user` 验证，再把身份ID映射为唯一HUMAN actor；不读取或复制训练平台roleId。训练后端不可用时返回503。项目仅owner可查看和管理。创建项目与创建规范版本要求 `Idempotency-Key`，相同键和请求返回原资源，不同请求复用同键返回409。`taskType`按大写编码保存，本轮图片导入接受 `IMAGE_CLASSIFICATION` 项目。

创建示例：
```json
{"name":"表面缺陷标注","description":"按项目规范标注","taskType":"IMAGE_CLASSIFICATION"}
```
本轮图片导入使用上述 `IMAGE_CLASSIFICATION` 项目。调用者ID由登录身份写入ownerActorId。

## 3. 图片 ZIP 导入、样本列表与原图读取

本节按 `backend` 提交 `bbf022d` 的 `ImportController`、`ImportService` 和解析器核对。首轮项目类型为 `IMAGE_CLASSIFICATION`，支持 ZIP/单卷 ZIP64 中的 JPG、JPEG、PNG。导入批次、样本及原图均归属项目 owner。以下路径均接在统一前缀 `/api/labeling/v1` 后。

| 方法与路径 | 输入与请求头 | 成功返回 |
|---|---|---|
| POST /projects/{projectId}/imports | JSON `{filename,sizeBytes,sha256}`，带 Idempotency-Key | 201，完整批次对象 |
| GET /imports/{batchId} | 无请求体 | 200，完整批次对象 |
| PUT /imports/{batchId}/chunks?offset={receivedBytes} | 二进制分片，Content-Type 为 application/octet-stream，带 X-Chunk-SHA256 | 200，`data.receivedBytes` |
| POST /imports/{batchId}/complete | 空请求体，带 Idempotency-Key | 202，当前批次对象，首次受理进入 PROCESSING |
| POST /imports/{batchId}/retry | 空请求体，带 Idempotency-Key | 202，当前批次对象 |
| GET /imports/{batchId}/samples?limit=20&cursor={nextCursor} | 首次省略 cursor，后续使用上一页 nextCursor | 200，`data.items` 和 `data.nextCursor` |
| GET /projects/{projectId}/samples/{sampleId}/file | 无请求体 | 200，JPEG/PNG 二进制原图 |

### 3.1 登录、权限与请求约定

所有接口带 `Authorization: Bearer <统一登录令牌>`。后端重新校验统一身份、标注平台 ENABLED 状态及项目 owner 归属。标注管理员按同一 owner 规则访问图片导入数据，他人资源返回 404。有效领取者的包内图片使用第 5 节的 assignment 文件接口授权。

owner 的 DRAFT/OPEN 项目允许创建批次、上传、完成、重试和预览。PAUSED/ARCHIVED 项目拒绝导入写操作，PAUSED 拒绝读取原图，ARCHIVED 允许读取已有原图。批次查询及 READY 批次的样本元数据查询可供历史查看。

三个 POST 均要求非空且不超过 200 字符的 `Idempotency-Key`。同一调用者、操作、键及相同请求返回首次保存的响应快照。创建时同键传入不同 JSON 内容返回 409 IDEMPOTENCY_CONFLICT。网络重试复用原键，新操作使用新键。重放后通过 GET 批次取得当前状态。

### 3.2 创建、查询与批次字段

创建请求使用 `Content-Type: application/json`。`filename` 为长度 1–255 的 ZIP 文件名，使用 `.zip` 后缀并且不含目录分隔符。`sizeBytes` 为正整数字节数，`sha256` 为整包原始字节的 64 位十六进制 SHA-256，大小受部署上限约束。

```json
{
  "filename": "images.zip",
  "sizeBytes": 1024,
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

创建响应示例，查询、complete 和 retry 的成功响应使用相同字段结构。

```json
{
  "data": {
    "batchId": "22222222-2222-4222-8222-222222222222",
    "projectId": "11111111-1111-4111-8111-111111111111",
    "status": "UPLOADING",
    "sizeBytes": 1024,
    "receivedBytes": 0,
    "chunkSizeBytes": 4194304,
    "originalComplete": false,
    "validSampleCount": 0,
    "errorCount": 0,
    "errors": [],
    "errorCode": null
  },
  "requestId": "44444444-4444-4444-8444-444444444444"
}
```

示例中的 UUID、摘要和字节数用于说明结构。实际调用使用服务返回的 ID，并计算所选 ZIP 的真实大小和摘要。

| 批次字段 | 含义 |
|---|---|
| batchId、projectId | 批次及所属项目的 UUID 字符串 |
| status | UPLOADING、PROCESSING、READY 或 FAILED |
| sizeBytes、receivedBytes | 整包声明大小、已持久化接收字节数 |
| chunkSizeBytes | 为此批次冻结的分片尺寸，调用方按返回值上传 |
| originalComplete | 原包曾通过整包长度和摘要校验，重试解析时仍会复核原包 |
| validSampleCount | 成功解析并持久化的图片数 |
| errorCount、errors | 错误总数及 `[{sourcePath,code,message}]` 明细，明细受部署展示上限限制 |
| errorCode | 批次失败原因，正常批次通常为 null |

### 3.3 分片上传与续传

首次 `offset=0`，后续 offset 使用服务器返回的 `receivedBytes`。请求体为当前分片的原始字节，`X-Chunk-SHA256` 为当前分片的 64 位十六进制 SHA-256。非末片长度等于 `chunkSizeBytes`，末片等于剩余字节数。

```json
{"data":{"receivedBytes":1024},"requestId":"44444444-4444-4444-8444-444444444444"}
```

UPLOADING 状态下，同一已记录 offset、长度和摘要的分片重传返回当前总进度，避免重复追加。断线后先 GET 批次，再用同一 ZIP 从 `receivedBytes` 继续。数据库进度持久化后才推进返回值。完成受理后，继续上传或重传分片返回 409 UPLOAD_CLOSED。

### 3.4 完成、解析状态与失败重试

上传全部字节后调用 complete，发送零字节请求体。complete/retry 传入 `{}` 也会返回 400 INVALID_REQUEST。首次 complete 校验整包长度和摘要，通过后保存 PROCESSING 状态并返回 202，由后台 worker 解析。调用方持续 GET 批次，按返回状态更新页面。

- UPLOADING 表示仍在接收分片。
- PROCESSING 表示等待或正在解析。
- READY 表示有效样本可浏览。部分文件损坏或格式不支持时，可同时存在 `errorCount > 0`，页面展示有效样本和错误明细。
- FAILED 表示本次解析失败。查看 `errorCode` 和 `errors`；原包完整且 `originalComplete=true` 时可调用 retry 重新解析。

无有效图片时为 FAILED/NO_VALID_SAMPLES。危险路径、符号链接、重复路径、展开超限、ZIP 完整性等问题使整批 FAILED。后台解析问题通过批次对象中的 `errorCode`、`errors[].code` 返回，GET 批次本身仍可成功返回 200。常见解析码包括 INVALID_ARCHIVE、UNSAFE_ARCHIVE、EXPANSION_LIMIT、UNSUPPORTED_FORMAT、INVALID_IMAGE、IMAGE_TOO_LARGE、ARCHIVE_READ_FAILED、NO_VALID_SAMPLES。

retry 会再次校验完整原包，并复用稳定的批次路径和 sampleId。同批次同规范化源路径保持同一 sampleId，避免重试重复入库。文件内容需要修正时，修正 ZIP 后创建新批次。

### 3.5 样本列表与分页

仅 READY 批次允许读取样本列表，其他状态返回 409 IMPORT_NOT_READY。`limit` 默认为 20，范围 1–100。响应按 sampleId 稳定排序，`cursor` 与批次绑定。下一页使用服务器返回的 `nextCursor`，最后一页为 null。

```json
{
  "data": {
    "items": [
      {
        "sampleId": "33333333-3333-4333-8333-333333333333",
        "projectId": "11111111-1111-4111-8111-111111111111",
        "batchId": "22222222-2222-4222-8222-222222222222",
        "sourcePath": "normal/one.jpg",
        "mediaType": "image/jpeg",
        "contentSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "metadata": {"width": 640, "height": 480, "sizeBytes": 1024},
        "fileUrl": "/api/labeling/v1/projects/11111111-1111-4111-8111-111111111111/samples/33333333-3333-4333-8333-333333333333/file"
      }
    ],
    "nextCursor": null
  },
  "requestId": "44444444-4444-4444-8444-444444444444"
}
```

`sourcePath` 保存 ZIP 内的 Unicode NFC 规范化相对路径，`contentSha256` 用于核对原图内容。metadata 提供宽、高和原始字节数。游标按不透明字符串传回，并进行 URL 编码。

### 3.6 原图读取与页面预览

使用样本返回的 `fileUrl`，携带与 owner 相同的登录身份读取。成功响应体为原始图片字节，`Content-Type` 为 image/jpeg 或 image/png，带 Content-Length、`Cache-Control: private, no-store` 和 `X-Content-Type-Options: nosniff`。错误响应使用通用 JSON 错误结构。

前端通过携带 Authorization 的 fetch 取得 Blob，再创建 Blob URL 供图片元素展示，切换或关闭时释放 Blob URL。项目ID和样本ID须对应，图片内容可按 `contentSha256` 校验。调用示例见 [image-preview.js](../../labeling-service/examples/image-preview.js)。

### 3.7 图片导入错误与完整调用示例

下表是同步请求的 HTTP 错误。异步解析失败按第 3.4 节读取批次状态。

| HTTP | code | 调用方处理 |
|---|---|---|
| 400 | INVALID_REQUEST、INVALID_CURSOR、UPLOAD_INTERRUPTED | 检查字段、请求头、请求体和批次游标，传输中断后查询进度再重试 |
| 401 | UNAUTHENTICATED | 重新取得统一登录令牌 |
| 403 | FORBIDDEN、LABELING_DISABLED | 检查标注身份启用状态和权限 |
| 404 | RESOURCE_NOT_FOUND | 检查资源 ID 和 owner 身份 |
| 409 | UPLOAD_CONFLICT、OFFSET_MISMATCH | 核对所选 ZIP，重新查询 receivedBytes 和分片边界 |
| 409 | UPLOAD_CLOSED、IMPORT_NOT_READY、IMPORT_FAILED、IMPORT_NOT_RETRYABLE | 查询批次状态，按完成、等待或失败重试流程处理 |
| 409 | IDEMPOTENCY_CONFLICT、PROJECT_PAUSED、PROJECT_ARCHIVED | 核对幂等键对应的请求及项目状态 |
| 413 | UPLOAD_TOO_LARGE | 检查整包部署上限和服务器返回的分片尺寸 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 分片请求使用 application/octet-stream |
| 422 | UNSUPPORTED_FORMAT、UPLOAD_INCOMPLETE、CHECKSUM_MISMATCH、CHUNK_SIZE_MISMATCH | 检查项目类型、ZIP、长度及摘要，原文件需修正时新建批次 |
| 503 | IDENTITY_UNAVAILABLE、STORAGE_UNAVAILABLE | 检查统一身份服务或持久化存储，恢复后按原进度和幂等键重试 |

完整调用顺序为统一登录、GET /me、创建项目、创建导入批次、按返回尺寸上传分片、complete、轮询至 READY、读取所有样本页、携带授权读取原图。

给宋雷鸣的可执行示例见 [examples/README.md](../../labeling-service/examples/README.md)。在 `labeling-service/` 目录、使用 Node 18+ 运行以下 PowerShell 示例，输入调用者本人创建的 projectId。TOKEN 通过现有登录流程取得并设置为当前终端环境变量。

```powershell
$env:LABELING_API_BASE = 'http://47.111.225.144/api/labeling/v1'
$projectId = Read-Host '请输入本人创建的项目 ID'
$imageZip = Read-Host '请输入图片 ZIP 的绝对路径'
$downloadDir = Read-Host '请输入原图下载验证目录'
node examples/image-import.mjs $projectId $imageZip $downloadDir
```

脚本执行上传、轮询、全部分页、原图下载及 SHA-256 校验，并保存包含项目、批次、样本 ID 的 manifest.json。批次查询和 samples 接口用于导入数据浏览，分类结果的草稿保存与最终提交按第 5 节后续接口接入。

部署可调容量、ZIP 安全检查及恢复细节见 [图片导入接口细则](08-image-import-api.md)，交接记录见 [交接说明](07-image-import-handoff.md)，验证记录见 [图片导入本地验证记录](09-image-import-verification.md)。

## 4. 打包与领取


| 方法与路径 | 权限 | 输入 | 返回 |
|---|---|---|---|
| POST /projects/{id}/package-plans | owner | batchId、specVersionId、packageSize、annotatorsPerSample | 201 planId、各包样本数、总工作量、selectionHash |
| POST /package-plans/{planId}/publish | owner | selectionHash | 202/200，计划BUILDING/PUBLISHED；重试不重复建包 |
| GET /package-plans/{planId} | owner | 无 | 发布状态及包清单摘要 |
| GET /task-pool | 启用人工用户 | taskType、cursor、limit | 只返回可见包简介及remainingSlots，无原文件与他人答案 |
| POST /packages/{packageId}/claims | 启用且在发布范围内的用户 | 空对象 | 201 assignmentId、packageId、slotNo、CLAIMED、revision |
| GET /assignments?scope=mine | 本人 | status/cursor/limit | 本人领取列表 |
| GET /assignments/{assignmentId}/items | 本人有效或已提交领取 | cursor/limit | 样本、本人草稿/提交、只读或可编辑状态 |
| POST /assignments/{assignmentId}/release | 本人 | baseRevision、reason | RELEASED；已提交409 |
| POST /admin/assignments/{assignmentId}/revoke | 标注管理员 | baseRevision、reason必填 | REVOKED；不自动赋予查看原数据权限 |

同批次只能正式发布一次；成功计划的publishedAt永久保留。重复发布同一计划返回原结果；同批次其他计划已发布返回409 BATCH_ALREADY_PUBLISHED。发布前检查样本集合、每包人数与席位编号1..N完整，失败不开放领取。

默认packageSize=10、annotatorsPerSample=1，必须为正整数并满足配置上限；发布后冻结。N=2为同包创建2个不同人的席位，remainingSlots不能为负。可用席位暂时被并发事务锁定返回409 CLAIM_BUSY，客户端刷新后有限次重试；确定已领满才返回SLOT_FULL。同人重复领取409 ALREADY_CLAIMED；相同幂等键重试返回第一次领取响应。

## 5. 编辑器与保存提交

| 方法与路径 | 权限 | 输入 | 返回 |
|---|---|---|---|
| POST /assignments/{id}/editor-sessions | 本人有效领取、项目OPEN | sampleId | editorSessionId、受控入口、expiresAt；只授权指定领取 |
| POST /assignments/{id}/editor-sync | 本人有效领取 | sampleId | 后端回读映射结果，校验作者，返回平台draft revision；未就绪503 |
| GET /assignments/{id}/samples/{sampleId}/file | 本人有效或已提交领取，且项目可访问 | 无 | 原始文件流；不返回其他包的文件 |
| PUT /assignments/{id}/drafts/{sampleId} | 本人有效领取、项目OPEN | baseRevision、explicitDone、resultFormat、result | revision、savedAt |
| POST /assignments/{id}/submissions | 本人有效领取、项目OPEN | 每个样本的已保存revision清单 | 201 submissionId、SUBMITTED |
| GET /submissions/{id} | 提交人或项目owner | 无 | 冻结的本人/项目结果；其他标注员404 |
| GET /projects/{id}/submissions | owner | packageId/actorId/cursor/limit | 按人和包查看所有提交 |
| GET /projects/{id}/samples/{sampleId}/file | owner | 无 | 需求方查看原始文件 |

统一保存逻辑支持编辑器适配器回读及平台保存；实施时不得同时让未经校验的浏览器结果和外部结果竞争覆盖同一revision。Label Studio路径以回读后的校验结果为准；PUT草稿的result结构须通过所绑定模板/schema校验。

草稿请求示例（result具体结构以选定模板为准）：
```json
{"baseRevision":0,"explicitDone":true,"resultFormat":"label-studio-json","result":[{"from_name":"category","to_name":"image","type":"choices","value":{"choices":["正常"]}}]}
```

提交示例：
```json
{"items":[{"sampleId":"sample-001","revision":3},{"sampleId":"sample-002","revision":1}]}
```
首个草稿保存将CLAIMED变为IN_PROGRESS；CLAIMED不直接提交。提交清单必须与包成员完全一致且不含重复sampleId；漏样本、多样本、未完成或过期revision分别返回422 INCOMPLETE_PACKAGE/INVALID_RESULT或409 REVISION_CONFLICT。示例ID为占位，不是实际UUID。身份、规范版本、外部任务ID由后端映射取得，不允许客户端指定提交人。

编辑器保存只代表草稿，提交接口成功才代表交付。首期提交后只读，不提供覆盖、撤回或返工接口。同幂等键重试返回原submissionId；换键再次提交已结束领取返回409 ASSIGNMENT_CLOSED。后续返工按新轮次设计，不改变已导出的快照。

## 6. 查看与导出

| 方法与路径 | 权限 | 输入 | 返回 |
|---|---|---|---|
| POST /projects/{id}/exports | owner | format、scope、submissionIds（选定模式）、includeOriginals=false | 202 exportId、QUEUED |
| GET /exports/{id} | owner/该任务创建者且仍有项目权限 | 无 | QUEUED/RUNNING/SUCCEEDED/FAILED、数量及错误摘要 |
| GET /exports/{id}/download | 同上 | 无 | 授权文件下载；未完成409；产物已清理410 EXPORT_EXPIRED |

scope为ALL_SUBMITTED或SELECTED_SUBMISSIONS；创建时在同一数据库快照中冻结提交清单和完成情况，清单写入export_job_submission并校验同项目归属。空清单返回422 NO_SUBMISSIONS。后台开始、产物发布和下载时重查权限。首期建议只实现原生结果JSON＋ID映射，业务确认后增加一种适用标准；未支持format返回422 UNSUPPORTED_FORMAT。includeOriginals=true首期未实现时明确拒绝，不静默忽略。

导出包含结果、mapping.csv、manifest.json和标签说明。mapping字段至少projectId/batchId/packageId/sampleId/assignmentId/actorId/submissionId/specVersionId/sourcePath/checksum。N=2分别保存两人结果；清单列出目标人数、已提交人数和未完成样本，不把缺结果表示成正常标签。不使用编辑器默认全项目导出替代本平台授权范围。

## 7. 管理接口与错误码

用户管理本轮采用 `GET /admin/users` 和 `PATCH /admin/users/{actorId}`，替代此前规划的 `/admin/actors` 启停接口。只影响标注身份，普通用户不得自助授予管理员权限；管理员可管理其他用户的权限。具体字段与行为见下方身份权限补充。

`GET /admin/audit-events` 尚未实现。本轮用户权限变更要求 reason，并在事务成功后写入服务日志（操作者、目标、操作内容、原因、requestId）；持久化审计查询后续实现。所有回收、暂停、禁用需 reason。

| HTTP | code示例 |
|---|---|
| 400 | INVALID_REQUEST、INVALID_CURSOR、UPLOAD_INTERRUPTED |
| 401 | UNAUTHENTICATED |
| 403 | FORBIDDEN、LABELING_DISABLED |
| 404 | RESOURCE_NOT_FOUND（包括不应暴露的他人资源） |
| 409 | SLOT_FULL、CLAIM_BUSY、RESOURCE_BUSY、ALREADY_CLAIMED、BATCH_ALREADY_PUBLISHED、REVISION_CONFLICT、ASSIGNMENT_CLOSED、PROJECT_PAUSED、PROJECT_ARCHIVED、IDEMPOTENCY_CONFLICT、UPLOAD_CONFLICT、OFFSET_MISMATCH、UPLOAD_CLOSED、IMPORT_NOT_READY、IMPORT_FAILED、IMPORT_NOT_RETRYABLE |
| 410 | EXPORT_EXPIRED |
| 413 | UPLOAD_TOO_LARGE |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 422 | INVALID_RESULT、INCOMPLETE_PACKAGE、UNSUPPORTED_FORMAT、NO_SUBMISSIONS、UPLOAD_INCOMPLETE、CHECKSUM_MISMATCH、CHUNK_SIZE_MISMATCH |
| 503 | IDENTITY_UNAVAILABLE、STORAGE_UNAVAILABLE、EDITOR_UNAVAILABLE、TRANSACTION_RETRY_EXHAUSTED |

## 8. 最小联调顺序

普通用户GET /me → POST /projects（无人审批）→ 建规范 → 上传完成 → 打包预览/发布 → 两个不同用户领取N=2包 → 分别编辑/同步/提交 → owner查看两份结果 → 创建导出 → 下载核对ID。并行验收第三人超领、同人重复领取、未领取读文件、回收后迟到提交、同一结果重复提交、导出他人项目均被正确处理。

2026-11-04一致性补充：暂停事务提交后拒绝新工作数据访问和草稿/提交/同步写入；此前已持共享锁的短事务先完成，已传输的字节无法撤回。导出提交清单不随重试变化。数据库死锁/序列化冲突最多重试整个事务3次，外部编辑器和存储请求不放进数据库重试闭包。幂等记录与业务写入/响应同事务提交；上传分片回执持久化，持久化成功前不推进offset。

## 标注身份权限补充（2026-11-07，已合入 backend）

所有以下接口使用 `Authorization: Bearer <登录令牌>`，前缀 `/api/labeling/v1`。
标注角色独立于训练平台的 roleId。普通启用用户可直接创建项目，无需求方申请审批。

| 接口 | 权限与响应 |
| --- | --- |
| `GET /me` | 已登录且标注身份启用；返回 actorId、labelingAdmin、canCreateProject、canAnnotate |
| `GET /admin/users?limit=20&offset=0` | 仅启用的标注管理员；data 为用户数组，limit 为 1–100，offset 非负 |
| `PATCH /admin/users/{actorId}` | 仅启用的标注管理员；成功返回 200，data 为 null |

用户列表只包含已经访问标注平台并建立 HUMAN 映射的人，不是统一登录系统全部账号。
列表字段：actorId、identityUserId、displayName、status、labelingAdmin；不返回密码或令牌。
修改请求支持部分更新，至少提供一个非空字段：

```json
{"status":"DISABLED","labelingAdmin":false,"reason":"联调停用验证"}
```

`reason` 必填，1–500 字符且不能全为空白。`status` 只接受 ENABLED / DISABLED；`labelingAdmin` 为布尔值。
停用不删除项目、不修改统一身份账号或训练角色；单独停用保留已有标注管理员授予记录，重新启用会恢复其有效权限。
管理员不能停用自己或撤销自己的管理员权限（409），请由另一个管理员操作。
授权变更在事务内串行化，并重新核验操作者当前身份，防止并发互相撤权导致无人管理。
不存在的 HUMAN actor 返回 404，普通用户调用管理员接口返回 403。
身份与管理接口的 403 错误码为 FORBIDDEN。图片导入事务内重新核验停用状态时也可能返回 LABELING_DISABLED，前端按 403 展示标注身份或权限提示，重新登录由 401 响应触发。

项目列表、详情和规范创建仍按 owner 授权，标注管理员无权直接查看他人的原始数据或项目详情。
他人项目详情返回 404。普通用户仍可以创建自己的项目。
停用后新的 /me、项目和管理请求被拒绝；已经执行中的请求不会强制中断。

### 对 B、C、D 的接入要求

- C：用 /me 控制管理入口显示；调用管理接口时携带同一个统一登录令牌。隐藏按钮不能替代后端鉴权。
- B、D：图片读取、导入、编辑器与后续提交接口都必须先验证统一身份并调用 ActorService.resolve，再检查项目或领取归属；写事务中重查并锁定启用状态。
- 本次已合并 backend 的 V3 图片导入代码和测试；V1/V2/V3 迁移保持原样。权限功能复用已有 actor 和 actor_role_grant 表，不新增迁移。

### 验收

执行 `mvn -f labeling-service/pom.xml test` 运行权限单元测试。
部署后用两个普通账号和一个标注管理员联调：普通账号管理接口 403、普通用户创建项目成功、跨账号项目详情 404、管理员也不能读取他人项目、停用后 /me 403、恢复后 /me 200、撤销管理员后管理接口 403、自停用或自撤权 409。
单元测试不等同于真实 PostgreSQL 并发测试和服务器验收。

## 任务包首轮实现（2026-11-07，已合入 backend）

本节是本轮实际接口范围；上文 release/revoke、编辑器、草稿、提交、导出仍为后续设计，不能当作已实现接口。

已实现创建打包预览、发布、查看计划、任务池、领取、本人领取列表、包内样本与图片读取。
所有接口带统一登录 Bearer Token；所有 POST 带 Idempotency-Key。领取请求只接受空对象或无请求体。

### 给 B、C、D 的调用顺序

1. B 的批次导入达到 READY，取得 batchId；项目 owner 创建规范，取得 specVersionId。
2. owner 调用 `POST /projects/{projectId}/package-plans`，请求：

```json
{"batchId":"批次UUID","specVersionId":"规范UUID","packageSize":10,"annotatorsPerSample":2}
```

省略 packageSize / annotatorsPerSample 时分别为 10 / 1。响应 data 包含 planId、status=DRAFT、selectionHash、sampleCount、packageSampleCounts、totalWorkload、packages（发布前为空）。样本按固定 UUID 顺序打包，不复制文件。每个包的 N 个席位代表 N 人分别标注该包全部样本。

3. owner 调用 `POST /package-plans/{planId}/publish`，请求 `{"selectionHash":"预览返回的摘要"}`。
本轮在单个事务中同步完成，成功返回 200 和 PUBLISHED 计划、包列表；项目由 DRAFT 转为 OPEN。发布后结构冻结。同批次其他计划发布返回 409 BATCH_ALREADY_PUBLISHED；重复发布同一计划不新建包。

4. 标注员调用 `GET /task-pool?limit=20&taskType=IMAGE_CLASSIFICATION`。
返回 data.items：packageId、projectId、projectName、taskType、sampleCount、requiredCount、remainingSlots；不返回原图、文件路径或他人身份。默认全部启用人工用户可见开放包，已领取或已领满的包不再出现。

5. 标注员调用 `POST /packages/{packageId}/claims`，请求 `{}`。
201 返回 assignmentId、packageId、slotNo、status=CLAIMED、revision=1。同一用户同一幂等键重试返回原结果；换键重复领取返回 ALREADY_CLAIMED；席位已满返回 SLOT_FULL。首轮同包请求使用行锁短事务串行等待，不使用跳锁策略，暂不返回上文规划的 CLAIM_BUSY。

6. `GET /assignments?scope=mine&limit=20` 列出本人领取。首轮不提供 status 筛选。
7. `GET /assignments/{assignmentId}/items?limit=20` 返回包内样本和冻结规范。
响应包含 assignmentId、packageId、status、readOnly、items、nextCursor、specification；规范包含 specVersionId、labels 数组、instructions、editorConfig 对象。样本包含 sampleId、sourcePath、mediaType、contentSha256、ordinal、fileUrl。
8. 图片通过返回的 fileUrl（`/assignments/{assignmentId}/samples/{sampleId}/file`）携带相同令牌读取。D 使用 assignmentId＋sampleId＋specVersionId 做编辑器映射，不能仅用 sampleId 区分个人结果。本接口尚不保存标注结果。

任务池、本人领取和包内样本使用 UUID 游标：下一页传响应 nextCursor，末页为 null；limit 为 1–100。包内样本分页按 sampleId 排序，ordinal 供显示打包顺序。

### 权限与部署边界

- owner 才能打包、发布和查看计划，batch/spec 必须同项目；管理员无 owner 绕过。
- 项目 OPEN 才能领取及读取包内图片；暂停或归档拒绝工作数据访问。停用身份拒绝新请求。
- 他人的领取、包外样本返回 404；原 owner 图片接口不会因领取而放开整个项目。
- 每个请求重新鉴权；已经开始传输的文件不强行中断。
- 新增 Flyway V4，保留 V1/V2/V3；升级只创建任务包相关表和约束，数据仍在独立标注库。
- 同步发布默认最多 10000 个样本、每包最多 1000 个、每包最多 10 人。分别由 LABELING_TASK_MAX_SAMPLES、LABELING_TASK_MAX_PACKAGE_SIZE、LABELING_TASK_MAX_ANNOTATORS 配置；样本超过上限返回 422 SAMPLE_LIMIT，应拆分导入批次，异步发布后续实现。
- 任务推荐算法、领取释放/回收、编辑器、结果提交与集成尚未实现，不能将 CLAIMED 当作已完成。

验证命令：`mvn -f labeling-service/pom.xml verify`。测试覆盖现有图片导入、多人并发争抢、重复领取与重放、跨用户和跨包读图、暂停/停用拒绝访问、同批次防重复发布。
