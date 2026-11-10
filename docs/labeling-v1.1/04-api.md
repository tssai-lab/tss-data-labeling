# 接口契约｜已有接口与新版待开发方案

更新：2026-11-10。统一前缀 /api/labeling/v1。依据本地 Controller/Service 和最新业务规则整理，未联网验证部署。**第 2—5 节列现有能力，第 6—11 节是待实现契约；第 12 节说明旧编辑器接口停用方向。** 第15节补充模板与时序扩展，仍待实现。出现示例不代表接口已经可调用。

## 1. 通用协议、权限与字段

- 用户请求携带统一登录 Bearer Token，后端解析 identityUserId、HUMAN actor 和项目 owner；不采信请求体的角色和执行者身份。
- 成功 `{ "data": ..., "requestId": "..." }`；错误 `{ "error": {"code":"...","message":"..."}, "requestId":"..." }`。新接口可扩展 error.details 返回缺项/冲突，不假称现有错误都已带详情。
- 业务 ID 是 UUID 字符串，时间 ISO 8601 UTC。结果里的 labelId 是规范内的稳定字符串，不要求 UUID。示例的 <...> 需替换为服务返回值。
- 默认列表 limit=20，上限100，返回 items/nextCursor；admin/users 例外沿用 offset。查询筛选不是写授权，消费快照仍须重查。
- 新 POST 写操作带 Idempotency-Key；相同操作/调用者/键/内容重放同资源，同键不同内容 409。草稿 PUT 用 baseRevision，不同时强制幂等键。
- 服务必须逐请求检查项目、样本、轮次和作者。管理员没有通用 owner 数据绕过。文件、下载同样授权。
- 新处理发布、审核和成果接口都在项目范围，不要求 AI 样本先加入人工包。人工提交以单个 assignment 的领取包为完整性单位，同一发布计划的其他包不阻塞该包提交。
- 候选、草稿、已提交、已审核是不同状态。AI confidence 由可信 Worker 提供，人工/owner 不能通过请求体伪装模型分数。
- 默认 401 才引导重新登录；403 显示权限/启用提示，409 提示刷新或处理冲突，不能全部当作登录失效。

## 2. 身份、项目和权限（已有）

| 接口 | 权限/请求 | 响应与说明 |
|---|---|---|
| GET /me | 启用的统一登录用户 | actorId、labelingAdmin、canCreateProject、canAnnotate |
| POST /projects | 任意启用人工；name、description、taskType，幂等键 | 201，创建者为 owner，无需求方审批 |
| GET /projects?scope=owned | 本人；cursor/limit | 本人项目列表 |
| GET /projects/{id} | owner | 项目与规范版本，跨用户404 |
| POST /projects/{id}/spec-versions | owner；taskType、labels、instructions、editorConfig、schemaVersion，幂等键 | 新规范版本；本轮类型 IMAGE_CLASSIFICATION |
| GET /admin/users?limit=20&offset=0 | 标注管理员 | data 为已建立 HUMAN 映射的用户数组，不是全部统一登录账号 |
| PATCH /admin/users/{actorId} | 标注管理员；status 和/或 labelingAdmin、必填 reason | 200 data=null；仅标注权限，不修改训练角色 |

管理员不能停用自己或撤销自己的管理员权限；改他人状态需重新授权。查询项目和原图仍遵守 owner 规则。项目创建示例：

```json
{"name":"图片分类试点","description":"未标注数据的人工与AI流转","taskType":"IMAGE_CLASSIFICATION"}
```

当前 ProjectController 未见 pause/resume/archive 路由；状态规则已在服务内校验，管理入口列为待开发，不可据旧文档认为已有接口。后续路径为 POST /projects/{id}/pause、/resume、/archive，body 含 baseRevision、reason，响应项目状态/revision；owner 或具备标注运行管理权限者操作，归档前必须清理活动任务。

## 3. 图片 ZIP 导入、样本列表与原图读取

本节保留现有图片导入契约，属于本地已有接口；本次未重新验证服务器部署。首轮项目类型为 `IMAGE_CLASSIFICATION`，支持 ZIP/单卷 ZIP64 中的 JPG、JPEG、PNG。导入批次、样本及原图均归属项目 owner。以下路径均接在统一前缀 `/api/labeling/v1` 后。

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

所有接口带 `Authorization: Bearer <统一登录令牌>`。后端重新校验统一身份、标注平台 ENABLED 状态及项目 owner 归属。标注管理员按同一 owner 规则访问图片导入数据，他人资源返回 404。有效领取者的包内图片使用第 4 节的 assignment 文件接口授权。

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

已有可执行示例见 [examples/README.md](../../labeling-service/examples/README.md)。在 `labeling-service/` 目录、使用 Node 18+ 运行以下 PowerShell 示例，输入调用者本人创建的 projectId。TOKEN 通过现有登录流程取得并设置为当前终端环境变量。

```powershell
$env:LABELING_API_BASE = 'http://47.111.225.144/api/labeling/v1'
$projectId = Read-Host '请输入本人创建的项目 ID'
$imageZip = Read-Host '请输入图片 ZIP 的绝对路径'
$downloadDir = Read-Host '请输入原图下载验证目录'
node examples/image-import.mjs $projectId $imageZip $downloadDir
```

脚本执行上传、轮询、全部分页、原图下载及 SHA-256 校验，并保存包含项目、批次、样本 ID 的 manifest.json。批次查询和 samples 接口用于导入数据浏览，分类草稿与人工提交使用第 5 节已有接口。

部署可调容量、ZIP 安全检查及恢复细节见 [图片导入接口细则](08-image-import-api.md)，交接记录见 [交接说明](07-image-import-handoff.md)，验证记录见 [图片导入本地验证记录](09-image-import-verification.md)。

## 4. 人工打包与领取（已有，待适配新轮次）

| 接口 | 请求 | 当前响应 |
|---|---|---|
| POST /projects/{id}/package-plans | batchId、specVersionId、packageSize、annotatorsPerSample，幂等键 | 201 planId、status、selectionHash、sampleCount、packageSampleCounts、totalWorkload、packages |
| GET /package-plans/{id} | owner | 计划对象 |
| POST /package-plans/{id}/publish | selectionHash，幂等键 | 当前同步200，PUBLISHED 和包列表；首次使项目 OPEN |
| GET /task-pool?taskType=IMAGE_CLASSIFICATION | 启用人工，limit/cursor | 包摘要、sampleCount、requiredCount、remainingSlots，无原图和答案 |
| POST /packages/{id}/claims | 当前用户，空对象或无body，幂等键 | 201 assignmentId、packageId、slotNo、CLAIMED、revision |
| GET /assignments?scope=mine | 本人，limit/cursor | 本人领取；当前无 status 筛选 |
| GET /assignments/{id}/items | 本人领取 | assignmentId、packageId、status、readOnly、specification、items、nextCursor |
| GET /assignments/{id}/samples/{sampleId}/file | 本人有权范围 | 授权原图流 |

packageSize 默认10，人数默认1。当前实现支持多席位，但本轮产品默认只开放1人；任务成员和规范冻结。同人重复领取 ALREADY_CLAIMED、满额 SLOT_FULL。当前同包行锁串行，不宣称已实现 SKIP LOCKED。

**旧限制：**当前同 batch 只能发布一次（BATCH_ALREADY_PUBLISHED），没有 roundId。新版必须扩展为第6节，不能靠重新上传数据或伪造 batchId 实现再次发布。现有包路径领取兼容时只可映射到唯一合法活动人工轮次，否则报错；不能无声领取旧轮次。

## 5. 人工草稿与整包提交（已有）

当前仅支持 `resultFormat=image-classification-v1`，不接受原始 Label Studio JSON。

PUT /assignments/{assignmentId}/drafts/{sampleId}：

```json
{"baseRevision":0,"explicitDone":true,"resultFormat":"image-classification-v1","result":{"labelId":"normal"}}
```

normal 为示例，必须存在于绑定规范。首次 baseRevision=0，之后用服务器返回的 revision。未完成可保存 `result:{}, explicitDone:false`；标签错误422，旧 revision409。成功返回 assignmentId、sampleId、revision、savedAt、explicitDone、resultFormat、result。

GET 同一路径读草稿。响应丢失时先读回再决定是否重试，不盲目增加 revision。

POST /assignments/{id}/submissions，带幂等键：

```json
{"items":[{"sampleId":"<UUID-1>","revision":3},{"sampleId":"<UUID-2>","revision":1}]}
```

人工允许逐样本保存未完成草稿，但 `POST /assignments/{id}/submissions` 必须包含该 assignment 所属领取包的全部样本，各自已经保存、`explicitDone=true` 且 revision 最新；新轮次实现后，校验集合是该 assignment 在本轮领取包中冻结的成员，不是整轮的所有包。一次人工发布计划可有多个包，每包建立独立轮次，逐包独立提交；例如领取 10 个包，可先提交已全部完成的 5 个，其他包继续保存草稿。若重新发布时只选部分样本，先把选中范围拆成包，每个包再独立提交。漏项/未完成422 INCOMPLETE_PACKAGE，重复或非法答案422 INVALID_RESULT，旧版本409 REVISION_CONFLICT。成功201，返回 submissionId、assignmentId、SUBMITTED、sampleCount；已提交不可修改。

每个领取包分别调用一次提交接口；10 张完成 6 张只能保存草稿，不能提交该包，但不会阻止其他已完成包提交。成功响应只表示这个包的人工结果已交回，不表示 owner 已审核通过，也不表示整个任务完成。AI 不调用这个接口。

GET /submissions/{id} 供提交人或 owner 查看不可变内容；GET /projects/{id}/submissions?limit=20&cursor=... 为 owner 列表。当前没有 packageId/actorId 筛选参数，不按旧设计假设已实现。

POST /assignments/{id}/release 和 POST /admin/assignments/{id}/revoke 尚未在当前 Controller 实现。待实现请求为 baseRevision、reason，返回状态/revision；不接受已提交记录，回收不删除草稿，旧页面写入拒绝。

## 6. 人工范围选择、打包与再发布（全部待实现）

先通过第8节形成 purpose=PUBLISH 的选择快照，固定 sampleId、referenceResultId、reviewRevision。然后创建新人工包计划或原包的下一轮。

### 6.1 从任意项目样本创建人工包

扩展 POST /projects/{id}/package-plans：新版 body 使用 selectionId、specVersionId、packageSize、annotatorsPerSample=1、instructions、referenceMode NONE/SELECTED、reopenAccepted=false。与旧 batchId 模式互斥，不能混填。selectionHash 由后端返回。

新计划预览返回明确样本数、包数、每包数量、selectionHash 和涉及已确认样本数量。publish 仍使用已有路径和 hash，新版创建包及各自 HUMAN_POOL round，返回各 packageId/roundId。选择已确认样本必须 reopenAccepted=true 且预览告知；只有发布成功事务才重置所选当前审核，失败不影响原确认。

新方案允许跨已结束的旧包选样，不复制原图，不修改旧包成员。旧“同 batch 一次”约束需迁移后解除。

### 6.2 在同一人工包再次发布

POST /packages/{id}/rounds，owner、幂等键：

```json
{
  "mode":"HUMAN_POOL",
  "selectionId":"<选择快照UUID>",
  "referenceMode":"SELECTED",
  "instructions":"复核选定图片的分类",
  "baseRevision":4,
  "reopenAccepted":false
}
```

选择范围必须非空且恰好等于该包全部成员、规范兼容；子集请求返回422 PACKAGE_SCOPE_MISMATCH，改用第6.1节创建新包；不接收目标人工 actorId。201 返回 roundId、packageId、specVersionId、status=OPEN、sampleCount、revision。若只选择原包中的4张作为返工范围，应通过第6.1节将这4张组成一个新的人工包；该包必须4/4提交，不要求重做原包其他样本，也不等待同一发布计划的其他包。

新版 GET /assignments/{id}/items 还需返回 roundId、每样本 referenceResultId/referenceResult/sourceKind 和当前本人草稿。参考结果仅取本轮冻结引用，不能开放任意他人历史答案；当前已有接口尚无这些新字段。

GET /rounds/{id}：owner 或本人合法领取视图；返回 mode、status、specVersionId、packageId（AI为null）、sampleCount、submitted/failed counts、revision。不得向普通人暴露他人答案。

POST /rounds/{id}/claims：仅人工轮次，空body＋幂等键，当前身份原子领取，201 assignmentId/roundId/packageId/slotNo/status/revision。AI轮次422 INVALID_EXECUTION_MODE。

POST /rounds/{id}/cancel：owner 或运行管理权限者，baseRevision、reason；200状态/revision；结束活动工作、释放样本占用并拒绝迟到写入。已提交结果不撤回。人工与AI不能在相同样本上创建重叠活动轮次，409 SAMPLE_BUSY；可处理不相交范围。

## 7. AI 范围执行（全部待实现）

### 7.1 Agent 与创建执行

GET /agents?taskType=IMAGE_CLASSIFICATION：返回 agentId、displayName、taskTypes、modelVersion、supportedLabels/capabilities、supportsReference、confidenceDescription、available；不含模型密钥。服务端仍需校验能力和规范匹配。

POST /projects/{id}/ai-executions，owner、幂等键，无需 packageId/assignmentId：

```json
{
  "agentId":"<Agent UUID>",
  "specVersionId":"<规范UUID>",
  "selectionId":"<固定选择UUID>",
  "referenceMode":"NONE",
  "instructions":"按项目规范进行分类",
  "reopenAccepted":false
}
```

202 返回 executionId、roundId、status=QUEUED、sampleCount、modelVersion、revision。选样可来自未打包数据、多个已结束人工包；项目、规范、参考结果和活动占用需校验。模型不支持参考模式时422 REFERENCE_UNSUPPORTED，不静默忽略参考。

GET /ai-executions/{id}，owner：返回上述字段及 successCount/failedCount/pendingCount/cancelledCount、errorSummary、resultSnapshotId（未终结为null）。SUCCEEDED/PARTIAL/FAILED/CANCELLED 为终态。批次终结时固定成功结果和失败清单；PARTIAL 的成功结果可以先交需求方处理，失败项由需求方之后决定重试、换 Agent 或转人工。批次终态只表示 AI 已交回，不表示需求方审核通过或整个任务完成。

GET /ai-executions/{id}/results?limit=20&cursor=...：运行中只读进度，标记 provisional=true、reviewable=false；终结后绑定 snapshotId、resultId，成功项 reviewable=true，失败项没有答案。运行中的 resultId 不得伪装为可审核候选。

POST /ai-executions/{id}/cancel：baseRevision、reason，冻结有效结果与取消范围，拒绝迟到写入。

POST /ai-executions/{id}/retries：owner 选择已失败/取消未完成项的 selectionId、baseRevision，带幂等键；创建新 execution/round，202返回新ID和retryOfExecutionId；旧快照不变。换模型则新建执行并携带来源，不冒充原模型重试。

### 7.2 内部模型输出约定

Worker 使用受限内部服务调用，executionId、generation、sampleId 必须经过后端核验；不是给浏览器开放一个随意上传 AI 结果的接口。输出形式：

```json
{
  "sampleId":"<UUID>",
  "resultFormat":"image-classification-v1",
  "result":{"labelId":"normal"},
  "confidence":0.87
}
```

模型版本、actor 和来源由 execution 取得。无可信分数则null；非法标签、超范围样本、非有限或越界分数拒绝并记录错误。成功输出逐样本落库，终结快照进入第8—9节查询审核；不调用人工 submissions。正常结束、失败、取消和重试均有持久记录。

### 7.3 A 与 D 的内部执行契约（待实现）

统一内部边界为`/internal/labeling/v1`，不通过公开前端代理暴露；使用独立工作端凭据，服务端将凭据映射到workerId和允许的agentId，不能使用人类登录Token。A提供执行控制与鉴权，D调用并实现模型适配，B复用原图存储读取。若Worker内嵌Java服务，同样复用这些服务方法，不另写数据库旁路。实际凭据由部署配置注入，不提交仓库、不发给浏览器。

| 接口 | 请求及响应 | 规则 |
|---|---|---|
| POST /executions/claim | body agentId；200 executionId、generation、leaseUntil、specVersionId、固定规范、modelVersion、configVersion、labelMapping、supportsReference；无工作204 | 原子领取一个QUEUED或租约过期的RUNNING执行；仅允许凭据授权Agent，递增generation |
| POST /executions/{id}/heartbeat | generation；200 leaseUntil | 默认租约120秒，每30秒续约；身份、generation、租约或状态失效返回409 EXECUTION_LEASE_LOST，停止写回 |
| GET /executions/{id}/items | generation、limit/cursor；样本ID、outputStatus、受限fileUrl、referenceResult | 固定本轮全集，接管后跳过已持久化终结项；引用仅限本轮允许的参考答案 |
| GET /executions/{id}/samples/{sampleId}/file | generation | 校验Worker身份、有效租约及RoundItems，返回原图流；无任意磁盘路径，无长期存储凭据 |
| PUT /executions/{id}/outputs/{sampleId} | generation、status、resultFormat/result/confidence或errorCode/errorMessage；200 outputStatus | status仅SUCCEEDED或FAILED；成功与错误字段互斥；同内容重复写幂等，不同内容409 OUTPUT_CONFLICT |
| POST /executions/{id}/finish | generation、幂等键；200终态、resultSnapshotId及计数 | 尚有PENDING返回409 EXECUTION_INCOMPLETE；状态由后端计算，Worker不能自行宣称成功 |
| POST /executions/{id}/fail | generation、幂等键、errorCode/errorMessage | 模型不可用等整批故障：保留成功项，将剩余PENDING固定为FAILED，再由后端结算 |

所有路径均相对上述内部边界。有效工作端、generation、租约、项目/Agent状态和样本归属每次重新校验；401/403不能自动换成人类凭据绕过。暂停项目拒绝读取和写回，未结束执行保留，恢复后可重新领取；取消立即使旧generation失效。正常finish全部成功为SUCCEEDED、部分成功为PARTIAL、全部失败为FAILED；owner取消为CANCELLED，剩余项记CANCELLED。终态pendingCount=0，各项数量之和等于sampleCount。

执行创建时固定模型/权重版本、配置版本、标签映射及置信度含义，D不能执行到一半切换配置。单个样本内部重试在写入最终输出前完成；成功或失败输出持久化后不覆盖。租约接管保留已有输出；终态重试由owner新建execution。网络失败允许按相同内容重发；终结响应丢失按幂等键重放，返回原快照，不创建第二份。

失败输出示例：

```json
{"generation":1,"status":"FAILED","errorCode":"MODEL_LABEL_UNMAPPED","errorMessage":"模型输出类别无法映射到本轮标签"}
```

成功输出除第7.2节内容外须带generation和status=SUCCEEDED；sampleId以路径为准，不允许body不一致。错误信息脱敏，不包含Token、密钥或原始文件的宿主机路径。

## 8. 结果查询、选择快照与分档（全部待实现）

GET /projects/{id}/results：owner，筛选 specVersionId、packageId（可选）、roundId、executionId、sourceKind、reviewStatus、hasResult、confidencePolicyId＋band、limit/cursor。

返回 items 含 sampleId、sourcePath、resultId（可空）、result、sourceKind、sourceActor、modelVersion、confidence、reviewStatus、reviewRevision、hasResult、activeProcessing；另含nextCursor。单个样本有多份候选时默认返回当前选定结果，未选定且多候选时提示需选，不按最新时间猜测正确答案。GET /projects/{id}/samples/{sampleId}/results 列来源历史。

结果列表每行对应一个样本，增加selectedResultId、candidateCount、requiresResultSelection。hasResult表示当前筛选范围内存在可审核候选，不等于已确认；无候选时resultId=null、candidateCount=0。存在明确executionId等来源筛选时，只在该范围内选展示候选；否则优先展示当前selectedResultId。未选定且只有一份候选可展示该候选，但不自动改变审核指针；多份且无法确定时resultId=null、requiresResultSelection=true，调用方必须从样本历史中明确选择resultId，不能按时间猜测。reviewStatus/reviewRevision描述样本在该规范下的当前审核状态；仅当展示resultId等于selectedResultId且状态ACCEPTED时才能给该答案显示“已确认”。

GET /projects/{id}/result-statistics 使用相同筛选，返回total、hasResultCount、noResultCount、needsRevisionCount、acceptedCount、activeCount；状态计数可能重叠，不相加当总数。AI执行统计另返回successCount/failedCount/pendingCount/cancelledCount。置信度统计仅针对选定execution的成功结果，HIGH/MEDIUM/LOW/UNKNOWN互斥；未设policy仅展示分数直方图及unknown，不默认分档。

普通分页和统计为实时视图，不承诺跨请求一致。生成selection后，GET /result-selections/{id}/items分页及GET /result-selections/{id}/statistics只读固定清单和创建时统计；C必须用它们做最终预览，不再用实时统计冒充确认数量。审核消费时A重新校验当前状态和revision。

POST /projects/{id}/result-selections，owner＋幂等键：

```json
{
  "purpose":"REVIEW",
  "reviewAction":"ACCEPT",
  "specVersionId":"<UUID>",
  "filter":{"executionId":"<UUID>","confidencePolicyId":"<UUID>","band":"HIGH"},
  "excludeSampleIds":["<UUID>"]
}
```

支持两种互斥输入：filter＋excludeSampleIds，或items[{sampleId,resultId,reviewRevision}]；不能混填。purpose 可为 PUBLISH/REVIEW/FINALIZE。PUBLISH允许没有答案，resultId仅表示明确选择的参考结果；REVIEW必须指定reviewAction=ACCEPT或NEEDS_REVISION，ACCEPT要求可审核的不可变候选，NEEDS_REVISION允许无答案；FINALIZE只能选择当前已确认结果。消费接口必须匹配purpose及reviewAction，错用返回422 SELECTION_PURPOSE_MISMATCH。多数投票使用第10节inputs中的多个resultIds及独立aggregation_input快照，不使用此单结果选择表。固定selectionId、selectionHash、count、expiresAt；GET /result-selections/{id}/items 分页预览该清单。

选择快照固定projectId、specVersionId、createdBy、purpose、reviewAction（仅REVIEW）、筛选与policyId、每项sampleId/resultId/reviewRevision、selectionHash、count、createdAt、expiresAt；无审核行时revision=0。默认有效期30分钟，过期重新生成，不延长旧预览。hash按稳定sampleId顺序计算，覆盖范围、答案、revision及用途。空集合拒绝422 EMPTY_SELECTION，多候选未明确选答案且用途需要答案时拒绝422 RESULT_SELECTION_REQUIRED；未知筛选字段拒绝，不能静默忽略。referenceMode=SELECTED可有部分样本resultId为空，表示该样本无参考；NONE不向执行者展示任何已有答案。消费时不自动换用新答案。

选择本页只传本页items；选择全部筛选结果由后端快照，不反复抓分页猜集合。选择过期410 SELECTION_EXPIRED；创建和消费都检查权限，变更范围须新建selection。发布消费时重查审核revision和活动状态。

POST /rounds/{id}/confidence-policies：仅AI轮次owner＋幂等键，body executionId、resultSnapshotId、lowerThreshold、upperThreshold。201 返回 policyId、revision、模型及阈值；snapshot必须已终结且属于该执行，0≤L<H≤1。策略新建版本，不改历史。

分档 HIGH:c≥H，MEDIUM:L≤c<H，LOW:c<L，UNKNOWN:null。0.60/0.90只是举例，不自动应用。保存策略不会生成审核决定。AI统计只计成功结果；失败/未完成单列，不归入未知分数结果。

## 9. 需求方审核、修订和成果（全部待实现）

POST /projects/{id}/reviews，owner＋幂等键：

```json
{
  "action":"ACCEPT",
  "selectionId":"<固定结果选择UUID>",
  "confidencePolicyId":"<本次分档UUID或null>",
  "reason":"查看并确认所选结果"
}
```

也支持单条/明确批量 items[{sampleId,resultId,baseReviewRevision}]，与selectionId互斥。action为ACCEPT或NEEDS_REVISION，待修正要求reason；ACCEPT必须有有效结果，NEEDS_REVISION可针对尚无答案样本。相同样本只操作一份选定结果。无policy时可人工直接审核，有policy必须校验与目标AI快照一致。

返回200 affectedCount、reviewBatchId、各项新reviewRevision（大批可用查询链接）；任何过期、无权限、运行中结果或不匹配项整体拒绝，409 REVIEW_CONFLICT。实际数量必须等于预览选择减去明确排除项，不动态扩大到后来出现的高分结果。

POST /projects/{id}/results/{resultId}/revisions：owner＋幂等键，body baseReviewRevision、resultFormat、result、reason；201 新resultId、parentResultId、PENDING、新reviewRevision。只创建修订，不自动确认，随后显式调用reviews。修订后不继承AI置信度。

POST /projects/{id}/final-snapshots：owner＋幂等键，body selectionId（purpose FINALIZE）、name；201 finalSnapshotId、sampleCount、specVersionId、scopeHash、createdAt。所有样本需当前已确认且无活动执行；缺项422 FINAL_RESULTS_INCOMPLETE，审核变化409 REVIEW_CONFLICT。此处固定范围完成，不自动表示全项目完成。

GET /projects/{id}/final-snapshots、GET /final-snapshots/{id} 返回成果范围与确认来源。人工包complete可作为共享成果逻辑兼容入口，但不得强迫AI成果先打包。

## 10. 批量结果集成（全部待实现）

GET /aggregation-algorithms?taskType=IMAGE_CLASSIFICATION：当前目标只提供 majority-vote-v1，说明需要独立来源和至少两份有效答案。

POST /projects/{id}/aggregations，owner＋幂等键，body algorithmId、specVersionId、inputs[{sampleId,resultIds}]、parameters。输入固定，不实时拉全部答案；同样本同actor重复版本拒绝，派生来源第一版拒绝自动计独立票。202 aggregationId/QUEUED。

GET /aggregations/{id} 返回状态；GET /aggregations/{id}/items 分页返回 sampleId、suggestedResultId、status CONSENSUS/NO_CONSENSUS/INSUFFICIENT_INPUT、voteCounts、validVoteCount。严格过半才给建议；voteSupport不是confidence。owner采用时调用reviews确认suggestedResultId，不直接把任务置完成。

## 11. 导出（全部待实现）

POST /projects/{id}/exports，owner＋幂等键，body finalSnapshotIds、format="classification-csv-zip"、includeOriginals=false；202 exportId/QUEUED。输入不是 ALL_SUBMITTED 或 submissionIds，必须是已确认成果快照；多个快照重叠样本有不同答案时422 CONFLICTING_SNAPSHOTS。

GET /exports/{id} 返回QUEUED/RUNNING/SUCCEEDED/FAILED、sampleCount、errorCode、expiresAt；GET /exports/{id}/download 每次重新授权，未完成409，过期410。未实现原图打包时includeOriginals=true应明确422，不静默忽略。

ZIP包含 classifications.csv、manifest.json，记录sampleId/sourcePath/labelId/labelName/resultId、来源与审核；原图映射、规范、模型和快照可追溯。文件名包含项目可读名和快照/导出标识。不需要packageId、assignmentId的AI成果也可导出。重复生成固定快照时清单一致，不承诺含时间元数据的ZIP字节完全相同。

## 12. 编辑器现有路径与新边界

旧备份中的试验实现曾提供 POST /assignments/{id}/editor-sessions、/editor-sync 和 /editor-ui/{sessionId}/...，并依赖完整 LS 服务及 V6 外部映射；这些接口未合入当前 backend 分支，不是新版接入方式。

新版编辑器直接由统一前端加载裁剪组件，通过第4—5节的本人样本/规范/草稿接口读写。转换后只发送平台 resultFormat/labelId；后端独立验证。保存是草稿，提交必须另行整包操作。不得把旧editor-sync与直接保存同时启用争写同一个草稿。

切换时停止创建外部会话并拒绝旧会话写入，保留历史；新客户端不请求editor-sessions/editor-sync。不要求浏览器持有LS Token。详见 [编辑器接入](10-editor-integration.md)。

## 13. 错误处理与联调顺序

| 情形 | HTTP/code | 前端处理 |
|---|---|---|
| 未登录/身份不可用 | 401 UNAUTHENTICATED / 503 IDENTITY_UNAVAILABLE | 登录或稍后重试，不删除草稿 |
| 停用、无权限 | 403 FORBIDDEN/LABELING_DISABLED，隐藏资源404 | 提示权限，不切换成别人的身份 |
| 草稿版本冲突 | 409 REVISION_CONFLICT（已有） | 保留本地选择，重新读取，由用户决定 |
| 人工包缺结果 | 422 INCOMPLETE_PACKAGE（已有） | 定位未完成项，禁止伪装提交成功 |
| 样本活动范围冲突 | 409 SAMPLE_BUSY（待实现） | 显示被占用范围，调整选择或结束原执行 |
| 审核/选择过期 | 409 REVIEW_CONFLICT / 410 SELECTION_EXPIRED（待实现） | 刷新并重新预览确认 |
| 能力/阈值非法 | 422 AGENT_INCOMPATIBLE/REFERENCE_UNSUPPORTED/INVALID_THRESHOLDS（待实现） | 修正配置，不自动换模型 |
| AI执行未终结 | 409 AI_RESULTS_NOT_READY（待实现） | 仅展示进度，不审核运行中输出 |
| 请求重放内容不同 | 409 IDEMPOTENCY_CONFLICT（已有） | 新操作用新键，重试用原请求原键 |

新增错误最终进入同一错误模型。列表里的待实现错误不得被测试或页面假装成服务器已有行为。

人工链路：登录→me→建项目/规范→上传READY→选择→打包预览/发布→领取→所有页items→保存草稿→完整提交→owner查询/审核→成果快照→导出。

AI链路：上传READY→选择样本（不用包）→agents→ai-executions→轮询至终态→看结果→手动policy→选择快照预览→reviews→final-snapshots→exports。

混合链路：AI结束→owner选择待修正结果→打人工包并携带参考→领取且整包提交→owner审核；反向为人工提交后选样创建AI批次。最终验收必须有真实模型和真实接口，不能用Mock或旧编辑器服务代替。

## 14. 2026-11-10 契约审查结论

本次为静态代码与设计文档审查，没有执行迁移、调用共享数据库、部署或真实模型验收。第6—11节及内部Worker接口仍待实现，不代表B/D已确认或完成。

- A：统一迁移编号与事务；实现发布轮次、执行租约/终结、审核revision和最终快照。先交可调用的接口和错误响应，不能要求D直接改业务表。
- B：按第8节实现候选展示、统计、固定选择及受限原图读取；多数投票单独保存多结果输入；导出只消费最终快照。
- D：按第7.3节调用执行控制，提供真实模型、权重版本、标签映射、分数含义及一组实际成功/失败输出；不另建一套业务结果库。
- C：按固定选择预览操作，区分执行成功、结果交回和需求方确认；人工逐包提交，AI显示批次成功/失败范围。

联调必须覆盖：领取10包先提交5个完整包；原10张包只返工4张形成新包；多候选不自动选最新；无答案可标待修正但不能确认；选择后审核版本变化整体拒绝；AI部分成功可审成功项；重复回调不重复保存；租约过期/取消后旧回调拒绝；阈值变更不改变既有确认。模型实际可用性、资源需求和分数语义由D提交运行证据，不能仅凭本契约宣称验证通过。

## 15. 任务模板与能耗时序输入扩展（待实现）

详细依据、字段语义与示例见[任务插件、标注格式与工业能耗验收设计](14-task-plugins-and-energy-acceptance.md)第4—9节。现有图片接口仍按第2—5节使用，不能现在直接上传能耗CSV期待自动可用。

| 接口/变更 | 契约边界 | 负责 |
|---|---|---|
| GET /task-templates | 启用用户读取已注册且可用的id/version/taskType/inputFormat/resultFormat/editorKey/capabilities；不返回代码/凭据 | A，C读取 |
| POST /projects扩展 | 新模板使用templateId/templateVersion；服务端从注册表校验taskType，旧IMAGE_CLASSIFICATION请求映射既有模板；不允许任意远程schema | A |
| POST /projects/{id}/spec-versions扩展 | 固定模板和input/resultSchemaId及摘要；标签、说明和领域配置经模板校验；旧schemaVersion字段含义保留 | A/D |
| POST /projects/{id}/imports扩展 | 复用现有上传，增加importerKey及受校验importOptions；能耗配置TASK_ROWS或STATION_DAYS，不重复读取sample/preview | B |
| GET /assignments/{id}/samples/{sampleId}/data | 当前领取者的授权样本，含inputFormat/schemaId/inputHash/payload/context；不返回整份共享CSV | B/A |
| GET /projects/{id}/samples/{sampleId}/data | owner读取相同样本结构；仍按项目权限与状态验证 | B/A |
| GET /internal/labeling/v1/executions/{id}/samples/{sampleId}/data | Worker凭据、generation、租约和执行范围都有效；输入/上下文版本已冻结 | B/A，D调用 |
| items扩展 | 增加templateId/version、inputFormat和dataUrl；图片fileUrl继续保留；前端按注册editorKey装配 | B/C/D |
| 草稿、提交、AI输出、修订 | 复用原路径，以冻结规范决定resultFormat及payload校验；人工完整性仍按单包，AI成功项通过模板完成检查 | A/D |
| 导出扩展 | 注册annotation-jsonl-v1、energy-station-day-csv-v1；仅读取确认快照；公共来源和时序点/区间不丢失 | B |

新增未知/不兼容模板返回422 TEMPLATE_UNSUPPORTED或TEMPLATE_MISMATCH；样本输入版本错误409 INPUT_VERSION_CONFLICT；部署缺少历史模板实现时503 TEMPLATE_UNAVAILABLE，不静默按新schema解释旧记录。完整请求/响应schema须随实现与第14号设计同步，当前表为扩展边界，不代表现成接口。

本次质检要求引入抽样、双人盲标、金标准及一致性统计。A/B应先补质检计划、范围权限、指标输入/输出契约再实现，不能把分类多数投票返回值直接当kappa或金标准准确率。
