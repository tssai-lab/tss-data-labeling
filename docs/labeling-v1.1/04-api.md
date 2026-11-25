# 接口契约｜实际后端接口与边界

更新：2026-11-18。统一前缀 `/api/labeling/v1`。当前本地 `labeling-backend` 源码已包含 V1—V23 迁移，以及固定范围消费、逐结果审核与修订、AI 重试、集成建议、异步导出、项目状态/历史恢复、图片和能耗输入、原生任务/资源及编辑器适配。以下“已实现”指当前源码中可见实现；CI、本地全量测试、服务器部署/迁移、真实模型质量和业务专家验收须分别核对，本文件不据此声称通过。

## 本轮实现入口速查

| 功能 | 实际路径 | 说明 |
|---|---|---|
| 固定范围消费 | 原 `package-plans`、`ai-executions`、`final-snapshots` 加 `selectionId`；新增 `/projects/{id}/reviews` | 所有成员原子校验；规范、用途、答案、审核 revision、有效期与占用须一致 |
| 通用修订 | POST `/projects/{id}/results/{resultId}/revisions` | OWNER 不可变新候选；父结果保留；不自动确认 |
| 历史 | GET `/projects/{id}/imports`、`/rounds`、`/ai-executions`、`/package-plans` | owner，items/nextCursor，UUID 升序；创建时间仅用于展示 |
| 本人恢复 | GET `/me/submissions`、`/me/operation-receipts` | 后者按创建时间/operation/key 稳定分页，每行含 resourceId/operation/idempotencyKey/response/createdAt；cursor 为不透明字符串，仅本人；只返回已持久保存响应的操作 |
| 归属绑定 | GET `/package-plans/{id}` | 已返回 projectId、specVersionId；不得在另一个项目盲目发布 |
| 状态与删除 | POST `/projects/{id}/pause`、`/resume`、`/archive`；DELETE `/projects/{id}` | owner，baseRevision/reason/幂等键；删除仅草稿，逻辑删除保留审计和原始数据 |
| AI 重试 | POST `/ai-executions/{id}/retries` | 新执行，固定 retryOfExecutionId，只重试终态失败/未完成子集 |
| 集成 | `/aggregation-algorithms`、项目 `/aggregations`、`/aggregations/{id}` 及 `/items` | 有界同步计算严格过半建议；不自动审核 |
| 正式导出 | 项目 `/exports`、`/exports/{id}`、`/exports/{id}/download` | 持久异步作业，真实 ZIP；分类 CSV、能耗 JSONL；7 天到期 |
| 能耗 | `/task-templates`；owner/assignment/Worker `/samples/{id}/data` | 站点日 CSV 首轮、按样本授权、统一结果校验；历史上下文未接入 |

兼容：旧 `sample-reviews`、显式 samples[]、旧 submissionId 参考字段、图片格式和授权 file 路径继续有效。新界面应使用明确 resultId + baseReviewRevision 的审核；不能把兼容接口当成逐答案 CAS 接口。`sample-reviews` 的 ACCEPT 已正确写为 ACCEPTED；事件 action 仍为 ACCEPT。规范归属会在读取人工修订前校验。

### 能耗首轮联调契约（2026-11-14，团队实施基线）

以下技术字段和交互规则作为 A、B、C、D 首轮实现的共同基线；发生兼容性问题时由 A 协调并版本化调整。本轮已实现下列首轮技术契约；不代表真实交付包、模型或专家验收已经完成。先导入交付包 `sample/station_day_tasks_2k.csv`，一行对应一个站点日；完整小时/日表及历史基线另行接入。复用第15节路径，公共响应、认证、幂等和分页遵循第1节。

| 对象 | 字段及约束 | 责任与来源 |
|---|---|---|
| 模板 | `templateId=energy-station-day`、`templateVersion=1.0.0`、`taskType=TIMESERIES_ANNOTATION`、`editorKey=energy-station-day-editor`、`inputFormat=energy-station-day-input-v1`、`inputSchemaId=energy-station-day-input@1.0.0`、`resultFormat=annotation-record-v1`、`resultSchemaId=energy-station-day-result@1.0.0`；冻结各 schema 摘要 | A 实现注册和服务端校验分派；B/D 按这些标识实现。当前内置注册表已实现，模板声明不接受远程代码 |
| 样本 | 平台 `sampleId` 为 UUID；`sourceTaskId` 对应 CSV 的 `task_id`，并保留数据集版本、来源文件及行定位、`stationKey`、`enterpriseKey`、`stationLevel`、`dateLocal`、`timezone=Asia/Shanghai` 和输入摘要 | B 导入并生成映射；来源 ID 不当作全局唯一 ID，重试应保持相同样本身份 |
| 能耗输入 | `dailyKwh` 独立于 `series`；`series.name=load_curve`、`channel=value_kwh`、`unit=kWh`、`start` 为带时区的当天零点、`periodSeconds=3600`、`values` 为 24 个有限非负数或明确的 `null`；series 为单个对象；0 保留为 0 | B 提供授权读取接口；日值不当作第25个小时。缺失和重复处理按下列导入规则执行 |
| 标注结果 | 使用 `annotation-record-v1` 的 `result.schemaVersion` 和 `annotations[]`，每项含 `id`、`from_name`、`to_name=load_curve`、`type`、`value`；主标签示例为 `primary_label/choices/value.labelIds`；点和区间分别使用 `timeseries_point` 与 `timeseries_interval` | D 提供转换和校验；A 在草稿、提交及 AI 成功结果统一按冻结规范校验。点必须落在样本日的实际小时桶，区间端点对齐小时并采用 `[start,end)`，标签必须在该规范中 |
| 置信度 | AI `confidence` 为实际可解释的 0—1 分数或 `null`；另保存 `modelVersion`、规则/模型版本和 `confidenceDescription`。人工自评放结果的独立字段，不能写成模型分数 | D 提供真实含义、版本和标签映射；A 校验并绑定执行记录；没有可信分数时保持未知 |
| 需求方审核 | 结果保留 `sampleId`、规范版本、来源（人工提交或 AI execution）、结果版本及审核 revision；owner 执行接受、修改或标记待修正，记录操作人、原因和时间 | A 负责授权及审核记录，B 对齐结果列表/快照；AI 分档阈值仅用于筛选，不自动确认。现有 `POST /projects/{id}/sample-reviews` 的实际字段见9.3，新逐答案/CAS 审核见9.2 |

项目创建时选择已启用模板；创建 `specVersionId` 时冻结模板 ID/版本、输入和结果 schema ID/摘要、标签与完成规则。`publication_round.spec_version_id` 绑定该规范，`round_item` 固定样本范围和可选参考结果；人工包的 assignment、草稿和 submission，以及 AI execution/结果应继续引用同一规范，并由服务端通过规范解析冻结的模板与 schema。已有图片分类项目保留原格式和语义；运行中或历史规范不可原地改模板，升级须创建新规范版本并说明兼容策略。当前 spec_version 保存模板 ID/版本、inputSchemaId、resultSchemaId、schemaDigest（内置注册描述摘要）；旧图片规范回填模板/schema ID，旧记录 schemaDigest 允许为 null。schemaDigest 不是业务标签专家认证，标签仍按该规范逐条校验。

#### 固定导入与校验规则

1. B 使用受控导入器读取归档内的 `sample/station_day_tasks_2k.csv`，忽略 `preview` 和其他文件作为样本来源；数据集版本来自归档 manifest。`task_id`、站点、企业、层级、日期、`h00`—`h23`、`daily_kwh` 列必须存在。空小时值转 `null`，零保留为数值 0；非空值须为有限非负数。日值空时为 `null`，非空值同样须为有限非负数。
2. 空的 `label`、`label_detail`、`annotator`、`confidence`、`notes` 列是正常输入；任一行这些列有非空内容时拒绝该导入批次，提示不支持导入已有标注。缺必需列或值格式非法拒绝批次并报告文件、行号、字段和错误码；同一项目、数据集版本中重复 `task_id` 或重复站点日拒绝批次。导入失败不发布部分样本；同一批次重试使用原样本 ID，已存在的不同批次重复导入返回冲突，不静默生成重复样本。
3. 导入数据映射：`task_id→sourceTaskId`、`station_key→stationKey`、`enterprise_key→enterpriseKey`、`station_level→stationLevel`、`date_local→dateLocal`、`h00`—`h23→series.values[0..23]`、`daily_kwh→dailyKwh`。`stationLevel` 仅接受 `enterprise` 或 `device`；日期须为有效 `YYYY-MM-DD`。`sourceTaskId` 和站点日按项目及数据集版本限定作用域。
4. owner 使用 `GET /projects/{id}/samples/{sampleId}/data`，人工使用 `GET /assignments/{id}/samples/{sampleId}/data`，Worker 使用第15节内部路径。成功响应统一为 `data` 对象：`sampleId`、`inputFormat`、`schemaId`、`inputHash`、`source`、`payload`、`context`。`source` 含 `datasetVersion`、`sourceTaskId`、`sourceFile`、`sourceRow`；`payload` 含 `stationKey`、`enterpriseKey`、`stationLevel`、`dateLocal`、`timezone`、`dailyKwh` 和 `series`。payload.qualityReasons 在有缺测时为 ["MISSING_HOURLY_VALUES"]，否则为空数组；`context` 初期固定为 `{ "status": "NOT_PROVIDED", "availableHistoryDays": 0 }`，不伪造历史统计。三个路径数据结构相同，分别执行 owner、领取范围或 Worker 租约授权。
5. 数据错误按统一错误包返回：缺列/非法值为 `422 INVALID_ENERGY_INPUT`，已有标签为 `422 EXISTING_ANNOTATIONS_UNSUPPORTED`，重复来源为 `409 DUPLICATE_ENERGY_SAMPLE`；错误详情包含 `file`、`row`、`field`，不暴露其他项目数据。异步导入在批次状态中保存同样的错误信息，不因上传成功显示解析成功。

#### 固定结果和完成规则

`annotations` 中 ID 在当前结果内唯一；`to_name` 固定为 `load_curve`。`primary_label` 使用 `choices` 且 `value.labelIds` 恰有一个规范内标签；`secondary_labels` 使用 `choices` 且不含重复或主标签；`point_labels` 使用 `timeseries_point`，其 `value` 带 `timestamp`、`channel=value_kwh` 和含一个标签的 `labelIds` 数组；`interval_labels` 使用 `timeseries_interval`，其 `value` 带 `start`、`end`、`channel=value_kwh` 和含一个标签的 `labelIds` 数组，区间为样本日内的 `[start,end)` 且起点小于终点。`notes` 和 `whole_day_reason` 为 `text`；人工自评 `self_reported_certainty` 为 `choices`，值为 `HIGH`、`MEDIUM`、`LOW` 或 `UNKNOWN`，与 AI confidence 分开。点、区间和标签的完整结构与 [14 的结果示例](07-energy-requirements.md) 一致。

草稿允许没有主标签、`explicitDone=false`，但已有字段必须类型正确且不越界；正式完成必须 `explicitDone=true`，有且仅有一个规范内主标签，所有标注均合法。人工提交检查当前包的每条样本，其他包不阻塞；AI 每条成功结果执行相同完成检查，失败项不生成假标签。空小时和未知置信度本身不判为完成或未完成。正式业务标签含义、异常是否必须定位、备注/自评是否必填和 Agent 规则阈值由业务专家确认；本基线只固定最小技术完成条件，不将建议规则当作正式判定标准。

#### 审核与交付衔接

旧来源审核 `sample-reviews` 保留兼容；新接口使用9.2的明确结果 ID 和审核版本，修订产生不可变 OWNER 候选后须另行确认。阈值只生成策略；人工修订不会继承 AI 分数。

A 负责模板、规范、权限、结果校验分派、迁移与审核；B 负责导入、授权样本读取、统计及导出；D 负责编辑器结果转换和真实 Agent，C 负责界面与接入。B/D 依本节和第15节直接开发，样例数据以 [14 的输入和结果示例](07-energy-requirements.md) 为基础；A 汇总实际联调产生的不兼容并维护契约版本。业务标签需确认时先用显式标注的试标规范联调，不冒称正式验收规范。

迁移：当前源码包含 V1—V23。V15—V16 增加 owner 工作流及模板/能耗输入；V17—V23 继续增加原生任务输入和完成状态、持久化导出、任务资源分离、版本化原生结果、输入字段索引、多规范成果快照及能耗结果格式约束。V1—V14 的历史脚本未因后续演进而改写。应用这些迁移前，必须核对目标环境 Flyway 历史、checksum 和数据状态，并按发布说明备份；本地迁移文件存在不代表服务器已经应用。不得使用 `repair`、`clean` 或 `CASCADE` 绕过不一致。

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
| GET /projects?scope=owned | 本人；cursor/limit | 本人项目列表；每项额外返回 `datasetImported`（是否有成功导入记录或已登记样本/源文件）、`sampleCount`（已生成样本数）、`resourceCount`（已登记源文件数）、`activeImportCount`（上传或解析中的批次数），用于区分未导入、已导入及导入处理中状态 |
| GET /projects/{id} | owner | 项目与规范版本，跨用户404 |
| POST /projects/{id}/spec-versions | owner；taskType、labels、instructions、editorConfig、schemaVersion，幂等键 | 201，新规范；支持 IMAGE_CLASSIFICATION / TIMESERIES_ANNOTATION；可传 templateId/templateVersion，必须匹配内置注册表 |
| GET /admin/users?limit=20&offset=0 | 标注管理员 | data 为已建立 HUMAN 映射的用户数组，不是全部统一登录账号 |
| PATCH /admin/users/{actorId} | 标注管理员；status 和/或 labelingAdmin、必填 reason | 200 data=null；仅标注权限，不修改训练角色 |

管理员不能停用自己或撤销自己的管理员权限；改他人状态需重新授权。查询项目和原图仍遵守 owner 规则。项目创建示例：

```json
{"name":"图片分类试点","description":"未标注数据的人工与AI流转","taskType":"IMAGE_CLASSIFICATION"}
```

项目状态操作与删除均为 owner 专属，带 Idempotency-Key 和 `{baseRevision,reason}`（原因必填，最多1000字）。返回 `{projectId,status,revision,deleted}`。暂停 OPEN→PAUSED，恢复 PAUSED→OPEN；暂停阻止新领取、发布、草稿/提交，已有 Worker 租约允许完成当前执行。归档 DRAFT/OPEN/PAUSED→ARCHIVED，存在 OPEN 轮次或 UPLOADING/PROCESSING 导入时 409 PROJECT_BUSY。归档后结果只读（审核/修订/新成果/集成返回409 PROJECT_ARCHIVED），仍可读取历史和既有成果、创建导出。

DELETE 仅 DRAFT，且不得有已发布计划或活动处理；非草稿409 DRAFT_ONLY，旧 revision409 REVISION_CONFLICT。他人404；成功后项目列表/详情不可见，使用相同键可重放删除回执。采用 deleted_at 逻辑删除，存储和审计保留，不在请求中物理回收原图/CSV；恢复或清理需另行运维策略，不提供伪删除或通用物理 DELETE 权限。

## 3. 图片 ZIP 导入、样本列表与原图读取

本节保留现有图片导入契约。首轮项目类型为 `IMAGE_CLASSIFICATION`，支持 ZIP/单卷 ZIP64 中的 JPG、JPEG、PNG。导入批次、样本及原图均归属项目 owner。以下路径均接在统一前缀 `/api/labeling/v1` 后。

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

部署可调容量、ZIP 安全检查及恢复细节见 [图片导入接口细则](04-api.md)，交接记录见 [交接说明](05-team-and-acceptance.md)，验证记录见 [图片导入本地验证记录](archive/09-image-import-verification.md)。

### 3.8 图片导入格式和容量配置

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| LABELING_STORAGE_ROOT | ./data/labeling | 独立持久化目录，相对路径以服务工作目录为基准 |
| LABELING_CHUNK_SIZE_BYTES | 4194304 | 单片4 MiB，新批次冻结 |
| LABELING_MAX_ARCHIVE_BYTES | 2147483648 | 压缩包2 GiB |
| LABELING_MAX_EXPANDED_BYTES | 8589934592 | 全部展开内容8 GiB，含非图片 |
| LABELING_MAX_FILE_BYTES | 67108864 | 单个展开文件64 MiB |
| LABELING_MAX_ENTRIES | 100000 | 总条目数量，含目录和非图片 |
| LABELING_MAX_PIXELS | 40000000 | 单张图片最大像素数，解码前检查 |
| LABELING_MAX_ERRORS | 1000 | 状态接口最多展示的错误数 |
| LABELING_ZIP_ENCODING | UTF-8 | 无 UTF-8 标记的文件名回退编码，可按真实样本改为 GBK |
| LABELING_IMPORT_WORKER_ENABLED | true | 是否在本实例处理持久化导入队列 |
| LABELING_IMPORT_POLL_DELAY_MS | 1000 | 两次队列扫描间隔 |

支持单卷 ZIP/ZIP64；不支持加密、分卷、RAR、7z、SVG、TIFF 等格式。JPEG 扩展名允许 jpg/jpeg，PNG 为 png；扩展名须与真实解码格式一致。其他扩展名、损坏图片与超像素图片返回文件级错误。

这些是可调的部署初始值，并非已验证的容量或性能承诺。大数据量应根据真实样本、内存和磁盘重新配置和测试。本轮 worker 有界串行处理；长批次解析会保持数据库事务和项目共享锁，应测量实际耗时后再安排生产部署。

## 4. 人工打包与领取

| 接口 | 请求 | 响应 |
|---|---|---|
| POST /projects/{id}/package-plans | batchId 或 samples[]（二选一）、specVersionId、packageSize、annotatorsPerSample、instructions，幂等键 | 201 planId、status、selectionHash、sampleCount、packageSampleCounts、totalWorkload、packages |
| GET /package-plans/{id} | owner | 计划对象 |
| POST /package-plans/{id}/publish | selectionHash，幂等键 | 200，PUBLISHED 与包列表（含 packageId/roundId）；首次使项目 OPEN |
| GET /task-pool?taskType=IMAGE_CLASSIFICATION | 启用人工，limit/cursor | 包摘要、sampleCount、requiredCount、roundId、remainingSlots，无原图和答案 |
| POST /packages/{id}/claims | 当前用户，空对象或无body，幂等键 | 201 assignmentId、packageId、roundId、slotNo、CLAIMED、revision |
| GET /assignments?scope=mine | 本人，limit/cursor | 本人领取（含 roundId）；当前无 status 筛选 |
| GET /assignments/{id}/items | 本人领取 | assignmentId、packageId、roundId、status、readOnly、specification、items（含 referenceResult）、nextCursor |
| GET /assignments/{id}/samples/{sampleId}/file | 本人有权范围 | 授权原图流 |

packageSize 默认10，人数默认1。当前实现支持多席位，但本轮产品默认只开放1人；任务成员和规范冻结。同人重复领取 ALREADY_CLAIMED、满额 SLOT_FULL。当前同包行锁串行，不宣称已实现 SKIP LOCKED。同一样本同一时间只能属于一个 OPEN 轮次，重叠发布 409 SAMPLE_BUSY。

## 5. 人工草稿与整包提交（已有）

支持图片 `image-classification-v1` 和能耗 `annotation-record-v1`，按冻结规范分派；不接受原始 Label Studio 私有 JSON。能耗结果结构和完成条件见首节。草稿可选传 inputHash，若与冻结样本不符409 INPUT_HASH_MISMATCH；草稿读取返回 inputHash。

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

人工允许逐样本保存未完成草稿，但 `POST /assignments/{id}/submissions` 必须包含该 assignment 所属领取包的全部样本，各自已经保存、`explicitDone=true` 且 revision 最新；校验集合是该 assignment 在本轮领取包中冻结的成员（round_item），不是整轮的所有包。一次人工发布计划可有多个包，每包建立独立轮次，逐包独立提交；例如领取 10 个包，可先提交已全部完成的 5 个，其他包继续保存草稿。若重新发布时只选部分样本，先把选中范围拆成包，每个包再独立提交。漏项/未完成422 INCOMPLETE_PACKAGE，重复或非法答案422 INVALID_RESULT，旧版本409 REVISION_CONFLICT。成功201，返回 submissionId、assignmentId、roundId、SUBMITTED、sampleCount；已提交不可修改。

每个领取包分别调用一次提交接口；10 张完成 6 张只能保存草稿，不能提交该包，但不会阻止其他已完成包提交。成功响应只表示这个包的人工结果已交回，不表示 owner 已审核通过，也不表示整个任务完成。AI 不调用这个接口。

GET /submissions/{id} 供提交人或 owner 查看不可变内容，`items[].resultId` 标识样本的这版正式提交结果。V14 为已有正式提交明细补齐 UUID，并在同一次提交事务中为新明细生成 UUID。GET /projects/{id}/submissions?limit=20&cursor=... 为 owner 列表，当前支持游标分页。

POST /assignments/{id}/release（本人）和 POST /admin/assignments/{id}/revoke（LABELING_ADMIN）已实现，请求 baseRevision、reason 和幂等键，返回状态/revision；已提交记录不可回收，保留草稿，旧页面继续写入拒绝。管理员撤销是明确的运行管理权限，不能据此绕过其他 owner 数据读取权限。

## 6. 人工范围选择、打包与再发布

发布轮次由 `publication_round` 表示：首次发布和再次发布都会冻结本轮样本范围（`round_item`），同一样本同一时间只能属于一个 OPEN 轮次（`sample_work_claim`）。本轮全部提交后轮次自动关闭并释放样本占用，需求方即可再次发布这些样本。历史结果不覆盖。

选择快照 `result_selection` 的创建和预览见第 8 节。按范围发布同时支持显式 `samples[]` 和固定 `selectionId`，后者在 publish 时再次校验。

### 6.1 按显式样本清单再次发布

POST /projects/{id}/package-plans，owner、幂等键，`batchId`、`samples`、`selectionId` 三选一：

首次发布：

```json
{"batchId":"<UUID>","specVersionId":"<UUID>","packageSize":10,"annotatorsPerSample":1}
```

按范围再次发布（可选携带参考结果）：

```json
{
  "specVersionId":"<UUID>",
  "packageSize":10,
  "annotatorsPerSample":1,
  "instructions":"复核选定图片的分类",
  "samples":[{"sampleId":"<UUID>","referenceResultId":"<提交ID或null>"}]
}
```

`referenceResultId` 指向同一项目内该样本的已提交结果（submission ID），作为下一轮参考，不自动计入本轮完成。新参考字段 `referenceCandidateId` 使用真正的 resultId，可引用 HUMAN/AI/OWNER/AGGREGATION 的不可变答案；不得与旧 referenceResultId 同时传。两种参考都必须属于同一项目、样本和规范。计划预览返回 projectId、specVersionId、sampleCount、packageSampleCounts、selectionHash、packages；publish 后创建包及各自 HUMAN_POOL round，返回各 packageId/roundId。

### 6.2 在同一人工包再次发布

POST /packages/{id}/rounds，owner、幂等键，空 body 或 `{"instructions":"..."}`；复用原包成员创建新轮次（round_no 递增），返回 roundId、packageId、specVersionId、status=OPEN、sampleCount、revision。原包成员被其他 OPEN 轮次占用时返回 409 SAMPLE_BUSY。

固定范围发布可传 `{specVersionId,selectionId,packageSize,annotatorsPerSample,referenceMode:"NONE"|"SELECTED",instructions}`；默认 NONE。SELECTED 将固定的 resultId 作为参考，没有答案的样本无参考。创建计划和最终 publish 两次校验范围有效期、版本及占用；参数/范围变更需新预览。可选 reopenAccepted=false 时，范围含已确认样本409 ACCEPTED_REQUIRES_REOPEN，true 明确允许；省略兼容旧行为（允许重新处理）。计划预览和最终发布都会核对该选项。重新发布撤销当前 ACCEPTED 指针、推进审核版本，保留历史和已生成成果。

GET /assignments/{id}/items 返回 roundId 和每样本 referenceResult（resultFormat、result、sourceKind，新参考还含 resultId）。参考结果仅取本轮冻结引用，不开放他人历史答案。

GET /rounds/{id}：owner 或本轮参与者；返回 mode、status、specVersionId、packageId、roundNo、sampleCount、submittedCount、revision。

POST /rounds/{id}/claims：仅人工轮次，空body＋幂等键，当前身份原子领取，201 assignmentId/roundId/packageId/slotNo/status/revision。AI 轮次 422 INVALID_EXECUTION_MODE。

POST /rounds/{id}/cancel：owner，baseRevision、reason；结束活动工作、释放样本占用并拒绝迟到写入（有已提交时状态为 CLOSED，否则 CANCELLED）。已提交结果不撤回。重叠活动范围 409 SAMPLE_BUSY。

## 7. AI 范围执行

执行生命周期、Agent 注册、创建/查询/取消、Worker 租约及终结快照均已实现；本轮追加固定 selectionId 消费、真正的 resultId 参考和失败子集重试。Worker 必须提供实际输出；GET 查询不会推进作业或伪造成功。

### 7.1 Agent 与创建执行

GET /agents?taskType=IMAGE_CLASSIFICATION：data 是数组，含 agentId、displayName、taskType（单数）、modelVersion、supportsReference、confidenceDescription、enabled；不含模型密钥。服务端仍需校验能力和规范匹配。

注册 Agent 时 `capabilities` 可省略（按空对象保存），`supportedLabels` 可省略（按空数组保存）；非对象/数组分别返回422 `INVALID_AGENT_CONFIG`。Worker 凭据摘要全局唯一，重复密钥拒绝注册。

POST /projects/{id}/ai-executions，owner、幂等键，无需 packageId/assignmentId：

```json
{
  "agentId":"<Agent UUID>",
  "specVersionId":"<规范UUID>",
  "selectionId":"<固定选择UUID>",
  "referenceMode":"NONE",
  "instructions":"按项目规范进行分类"
}
```

202 返回 executionId、roundId、status=QUEUED、sampleCount、modelVersion、revision。AI 创建同样支持 reopenAccepted（false 阻止重开已确认样本，省略兼容旧行为）；selectionId 与显式 samples 二选一。选样可来自未打包数据、多个已结束人工包；项目、规范、参考结果和活动占用需校验。模型不支持参考模式时422 REFERENCE_UNSUPPORTED，不静默忽略参考。

GET /ai-executions/{id}，owner：返回上述字段及 revision、successCount/failedCount/pendingCount/cancelledCount、errorSummary、resultSnapshotId（未终结为null）。`errorSummary` 为 `{failedCount, byCode:[{errorCode,count}]}`，不返回可能含敏感信息的逐条错误文本；详细失败原因在结果列表中按权限查看。SUCCEEDED/PARTIAL/FAILED/CANCELLED 为终态。批次终结时固定成功结果和失败清单；PARTIAL 的成功结果可以先交需求方处理，失败项由需求方之后决定重试、换 Agent 或转人工。批次终态只表示 AI 已交回，不表示需求方审核通过或整个任务完成。

GET /ai-executions/{id}/results?limit=20&cursor=...：响应含 `snapshotId`、`provisional`、`items`、`nextCursor`。运行中 `snapshotId=null`、`provisional=true`，每项 `resultId=null`、`reviewable=false`，结果仅供查看进度，不能作为审核候选。终结后 `snapshotId` 指向不可变 AI 结果快照，`provisional=false`；答案、置信度和失败信息均从快照读取。每个成功项有稳定 `resultId`、`reviewable=true`，失败或取消项 `resultId=null`、`reviewable=false`，且不包含标注答案；错误码保留，错误文本统一显示脱敏提示。结果 ID 在 V12 迁移中为既有成功快照回填，并对新快照持续生成；重试执行会有自己的快照和结果 ID。

POST /ai-executions/{id}/cancel：baseRevision、reason，baseRevision 必须等于执行查询返回的 revision；领取、逐样本结果写入和执行终结会递增 revision。版本过期返回409 REVISION_CONFLICT。成功后冻结有效结果与取消范围，拒绝迟到写入；取消轮次保持 CANCELLED。

失败子集重试的实际 sampleIds/baseRevision 请求和201响应见7.4；不接收演示 selectionId 重试字段。

### 7.2 内部模型输出约定

Worker 使用受限内部服务调用，executionId、generation、sampleId 必须经过后端核验；不是给浏览器开放一个随意上传 AI 结果的接口。输出形式：

```json
{
  "generation":2,
  "status":"SUCCEEDED",
  "result":{"labelId":"normal"},
  "confidence":0.87
}
```

模型版本、actor 和来源由 execution 取得。无可信分数则null；非法标签、超范围样本、非有限或越界分数拒绝并记录错误。成功输出逐样本落库，终结快照进入第8—9节查询审核；不调用人工 submissions。正常结束、失败、取消和重试均有持久记录。

### 7.3 A 与 D 的内部执行契约（已实现）

统一内部边界为`/internal/labeling/v1`，不通过公开前端代理暴露；使用独立工作端凭据，服务端将凭据映射到workerId和允许的agentId，不能使用人类登录Token。A提供执行控制与鉴权，D调用并实现模型适配，B复用原图存储读取。若Worker内嵌Java服务，同样复用这些服务方法，不另写数据库旁路。实际凭据由部署配置注入，不提交仓库、不发给浏览器。

| 接口 | 请求及响应 | 规则 |
|---|---|---|
| POST /executions/claim | body agentId；200 executionId、generation、leaseUntil、specVersionId、固定规范、modelVersion、configVersion、labelMapping、supportsReference；无工作204 | 原子领取一个QUEUED或租约过期的RUNNING执行；仅允许凭据授权Agent，递增generation |
| POST /executions/{id}/heartbeat | generation；200 leaseUntil | 默认租约120秒，每30秒续约；身份、generation、租约或状态失效返回409 EXECUTION_LEASE_LOST，停止写回 |
| GET /executions/{id}/items | generation、limit/cursor；样本ID、outputStatus、受限fileUrl、referenceResult | 固定本轮全集，接管后跳过已持久化终结项；引用仅限本轮允许的参考答案 |
| GET /executions/{id}/samples/{sampleId}/file | generation | 校验Worker身份、有效租约及RoundItems，返回原图流；无任意磁盘路径，无长期存储凭据 |
| PUT /executions/{id}/outputs/{sampleId} | generation、status、result/confidence或errorCode/errorMessage；200 outputStatus | status仅SUCCEEDED或FAILED；成功与错误字段互斥；首次终结输出时同步更新进度计数；同内容重复写幂等且不重复计数，不同内容409 OUTPUT_CONFLICT |
| POST /executions/{id}/finish | generation、幂等键；200终态、resultSnapshotId及计数 | 尚有PENDING返回409 EXECUTION_INCOMPLETE；状态由后端计算，Worker不能自行宣称成功 |
| POST /executions/{id}/fail | generation、幂等键、errorCode/errorMessage | 模型不可用等整批故障：保留成功项，将剩余PENDING固定为FAILED，再由后端结算 |

所有路径均相对上述内部边界。有效工作端、generation、租约、项目/Agent状态和样本归属每次重新校验；401/403不能自动换成人类凭据绕过。暂停阻止新用户操作；已有 Worker 有效租约仍可完成当前执行（不强制中断模型），未结束执行保留；取消立即使旧generation失效。正常finish全部成功为SUCCEEDED、部分成功为PARTIAL、全部失败为FAILED；owner取消为CANCELLED，剩余项记CANCELLED。终态pendingCount=0，各项数量之和等于sampleCount。

执行创建时固定模型/权重版本、配置版本、标签映射及置信度含义，D不能执行到一半切换配置。单个样本内部重试在写入最终输出前完成；成功或失败输出持久化后不覆盖。租约接管保留已有输出；终态重试由owner新建execution。网络失败允许按相同内容重发；终结响应丢失按幂等键重放，返回原快照，不创建第二份。

失败输出示例：

```json
{"generation":1,"status":"FAILED","errorCode":"MODEL_LABEL_UNMAPPED","errorMessage":"模型输出类别无法映射到本轮标签"}
```

成功输出见第7.2节；sampleId 以路径为准，结果格式由冻结规范推导，不采信客户端来源/模型声明。错误信息脱敏，不包含Token、密钥或原始文件的宿主机路径。

### 7.4 失败子集重试（已实现）

POST `/ai-executions/{id}/retries`，owner + 幂等键，`{baseRevision,sampleIds?,instructions?}`，201 返回新执行完整视图，含 retryOfExecutionId。原执行必须终态，sampleIds 省略则选全部非 SUCCEEDED 项；明确子集不得重复、不得包括成功项。原快照不变，新执行复用原 agent/model/spec，重新校验活动占用；原 Agent 不再可用时拒绝，不自动切换模型。非终态409 AI_RESULTS_NOT_READY，版本不符409 REVISION_CONFLICT，空/非法子集422 INVALID_RETRY_SCOPE。同键只创建一个子执行。

## 8. 结果查询、选择快照与分档

项目结果列表、样本历史、基本统计和选择快照预览已在后端实现。选择快照可供前端预览固定范围，审核、再次发布、AI 和成果接口消费 `selectionId` 已实现。置信度策略由 `POST /rounds/{id}/confidence-policies` 保存，`GET /rounds/{id}/confidence-policies` 查询。

GET /projects/{id}/results：owner，筛选 specVersionId、packageId（可选）、roundId、executionId、sourceKind、reviewStatus、hasResult、confidencePolicyId＋band、limit/cursor。

省略 `specVersionId` 时，优先采用筛选中的任务包、轮次或执行关联的规范版本，否则用项目最新规范版本。`hasResult` 可传 `true` 或 `false`，`reviewStatus` 可传 `UNREVIEWED`、`PENDING`、`NEEDS_REVISION`、`ACCEPTED`。没有审核记录时状态为 `UNREVIEWED`，版本为 0。列表的游标是上一页返回的 `nextCursor`。样本历史可用 `specVersionId` 缩小范围，省略时返回该样本各规范版本的历史结果。

返回 items 含 sampleId、sourcePath、resultId（可空）、result、sourceKind、sourceActor、modelVersion、confidence、reviewStatus、reviewRevision、hasResult、activeProcessing；另含nextCursor。单个样本有多份候选时默认返回当前选定结果，未选定且多候选时提示需选，不按最新时间猜测正确答案。GET /projects/{id}/samples/{sampleId}/results 列来源历史；含 currentVersion/reviewable，已被修订的父答案保留为 false；selectedResultId 精确反映当前采用指针。结果列表新增 submissionId，避免将 submissionId 与 resultId 混用。

结果列表每行对应一个样本，增加selectedResultId、candidateCount、requiresResultSelection。hasResult表示当前筛选范围内存在可审核候选，不等于已确认；无候选时resultId=null、candidateCount=0。存在明确executionId等来源筛选时，只在该范围内选展示候选；否则优先展示当前selectedResultId。未选定且只有一份候选可展示该候选，但不自动改变审核指针；多份且无法确定时resultId=null、requiresResultSelection=true，调用方必须从样本历史中明确选择resultId，不能按时间猜测。reviewStatus/reviewRevision描述样本在该规范下的当前审核状态；仅当展示resultId等于selectedResultId且状态ACCEPTED时才能给该答案显示“已确认”。

GET /projects/{id}/result-statistics 使用与结果列表相同的筛选，保留 `total`、`hasResultCount`、`noResultCount`、`needsRevisionCount`、`acceptedCount`、`activeCount`，增加 `confidenceStatistics`。AI 执行的成功、失败、等待和取消数量仍由执行详情接口返回。

`confidenceStatistics` 的统计单元为筛选范围内每个样本当前可明确展示的 AI 候选，与结果列表的候选选择规则一致，不累计同一样本的全部历史答案。

| 字段 | 含义 |
|---|---|
| `aiResultCount` | 当前展示答案明确且来源为 AI 的样本数，含未知分数 |
| `scoredCount` | 上述答案中有有效 0—1 分数的数量，包含分数恰为 0 或 1 |
| `unknownCount` | 上述答案中分数未知的数量；人工/owner/集成答案和没有答案的样本不计入未知 AI 分数 |
| `unresolvedSampleCount` | 多份候选尚未明确选择展示答案的样本数；这些样本不参与分档或直方图，调用样本历史后显式选择 |
| `confidencePolicyId` | 本次使用的策略 ID，未指定时为 null |
| `bandCounts` | 指定策略时返回 `HIGH`、`MEDIUM`、`LOW`、`UNKNOWN` 四个计数；未指定策略时为 null，不套用默认阈值 |
| `histogram` | 固定 10 个等宽区间，每项含 `lower`、`upper`、`upperInclusive`、`count`；区间左闭右开，最后一档 `[0.9,1.0]` 右闭；空档仍返回 0 |

- 策略筛选必须同时传 `executionId` 和属于该执行的 `confidencePolicyId`。分档使用已有策略阈值 `L`、`H`：`LOW=[0,L)`、`MEDIUM=[L,H)`、`HIGH=[H,1]`，未知分数为 `UNKNOWN`。不提供策略也可读取数值直方图。
- `band`、`hasResult`、审核状态和其他来源/规范/范围筛选同时约束基础统计、分档和直方图。例如带 `band=HIGH` 时只统计高分档当前结果，不返回未经该筛选的全执行分布。
- `aiResultCount = scoredCount + unknownCount`；直方图的 count 之和等于 `scoredCount`；指定策略时四档数量之和等于 `aiResultCount`。统计不修改审核状态，不为模型生成或推断分数。

调用示例（UUID 为占位符，阈值来自已保存策略）：

```http
GET /api/labeling/v1/projects/<projectId>/result-statistics?executionId=<executionId>&confidencePolicyId=<policyId>
Authorization: Bearer <token>
```

以下是测试数据示例的 `data.confidenceStatistics`，`L=0.4`、`H=0.8`，不是生产模型的执行报告：

```json
{
  "aiResultCount": 15,
  "scoredCount": 14,
  "unknownCount": 1,
  "unresolvedSampleCount": 0,
  "confidencePolicyId": "<policyId>",
  "bandCounts": {"HIGH": 3, "MEDIUM": 5, "LOW": 6, "UNKNOWN": 1},
  "histogram": [
    {"lower": 0.0, "upper": 0.1, "upperInclusive": false, "count": 2},
    {"lower": 0.1, "upper": 0.2, "upperInclusive": false, "count": 1},
    {"lower": 0.2, "upper": 0.3, "upperInclusive": false, "count": 1},
    {"lower": 0.3, "upper": 0.4, "upperInclusive": false, "count": 2},
    {"lower": 0.4, "upper": 0.5, "upperInclusive": false, "count": 1},
    {"lower": 0.5, "upper": 0.6, "upperInclusive": false, "count": 1},
    {"lower": 0.6, "upper": 0.7, "upperInclusive": false, "count": 1},
    {"lower": 0.7, "upper": 0.8, "upperInclusive": false, "count": 2},
    {"lower": 0.8, "upper": 0.9, "upperInclusive": false, "count": 1},
    {"lower": 0.9, "upper": 1.0, "upperInclusive": true, "count": 2}
  ]
}
```

普通分页和统计为实时视图，不承诺跨请求一致。生成selection后，GET /result-selections/{id}/items分页及GET /result-selections/{id}/statistics只读固定清单和创建时统计；C必须用它们做最终预览，不再用实时统计冒充确认数量。审核消费时A重新校验当前状态和revision。

新建选择快照的 statistics 同时冻结 `confidenceStatistics`，筛选排除的样本不计入；显式 items 指定 resultId 时按实际选中的答案统计，即使实时列表当时有多份候选。之后的审核或新增策略不重算旧快照。升级前已创建的快照保留原 statistics 结构，可能没有 `confidenceStatistics`，前端应显示统计不可用，需要新统计时新建选择快照，不把缺字段当作全零。

前端请求示例和展示注意事项见 [置信度统计交接说明](confidence-statistics-handoff.md)。

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

支持两种互斥输入：filter＋excludeSampleIds，或items[{sampleId,resultId,reviewRevision}]；不能混填。purpose 可为 PUBLISH/REVIEW/FINALIZE。PUBLISH允许没有答案，resultId仅表示明确选择的参考结果；REVIEW必须指定reviewAction=ACCEPT或NEEDS_REVISION，ACCEPT要求可审核的不可变候选，NEEDS_REVISION允许无答案；FINALIZE只能选择当前已确认结果。消费接口必须匹配purpose及reviewAction，错用返回409 SELECTION_PURPOSE_MISMATCH。多数投票使用第10节items中的多个resultIds及独立 aggregation_job 输入快照，不使用此单结果选择表。固定selectionId、selectionHash、count、expiresAt；GET /result-selections/{id}/items 分页预览该清单。

选择快照固定projectId、specVersionId、createdBy、purpose、reviewAction（仅REVIEW）、筛选与policyId、每项sampleId/resultId/reviewRevision、selectionHash、count、createdAt、expiresAt；无审核行时revision=0。默认有效期30分钟，过期重新生成，不延长旧预览。hash按稳定sampleId顺序计算，覆盖范围、答案、revision及用途。空集合拒绝422 EMPTY_SELECTION，多候选未明确选答案且用途需要答案时拒绝422 RESULT_SELECTION_REQUIRED；未知筛选字段拒绝，不能静默忽略。referenceMode=SELECTED可有部分样本resultId为空，表示该样本无参考；NONE不向执行者展示任何已有答案。消费时不自动换用新答案。

选择本页只传本页items；选择全部筛选结果由后端快照，不反复抓分页猜集合。选择过期410 SELECTION_EXPIRED；创建和消费都检查权限，变更范围须新建selection。发布消费时重查审核revision和活动状态。

### 图片项目联调示例

使用项目 owner 的 `Authorization: Bearer <token>` 请求。先调用 `GET /projects/<projectId>/results?specVersionId=<specVersionId>&hasResult=true&limit=20`，从返回的 `data.items` 取得要排除的 `sampleId`。再调用 `POST /projects/<projectId>/result-selections`，带 `Content-Type: application/json` 和本次操作的 `Idempotency-Key`。

```json
{
  "purpose": "PUBLISH",
  "specVersionId": "<specVersionId>",
  "filter": {"hasResult": true},
  "excludeSampleIds": ["<排除的sampleId>"]
}
```

响应为 HTTP 201，读取 `data.selectionId` 和 `data.count`。随后查询 `GET /result-selections/<selectionId>/items?limit=20` 和 `GET /result-selections/<selectionId>/statistics`。如果有 10 张带结果的图片并排除 2 张，预览 `total` 和创建响应 `count` 均为 8。翻页使用预览响应的 `nextCursor`，每项包括 `sampleId`、`resultId`、`reviewRevision`。如只勾选指定行，请将 `filter` 和 `excludeSampleIds` 换成 `items`，并原样带上列表中的这三个字段。

POST /rounds/{id}/confidence-policies：仅AI轮次owner＋幂等键，body snapshotId、lowerThreshold、upperThreshold。执行ID从轮次关联的执行记录取得；snapshotId必须是该执行的终结快照。201 返回 policyId、revision、模型及阈值；0≤L<H≤1。策略新建版本，不改历史。

分档 HIGH:c≥H，MEDIUM:L≤c<H，LOW:c<L，UNKNOWN:null。0.60/0.90只是举例，不自动应用。保存策略不会生成审核决定。AI统计只计成功结果；失败/未完成单列，不归入未知分数结果。

## 9. 需求方审核、修订和成果

### 9.1 按提交审核

针对已提交的人工 submission 逐样本审核，owner 执行、提交者与 owner 可查看；审核不覆盖标注员答案，修改结果必须属于提交绑定的规范版本。

| 接口 | 权限/请求 | 响应 |
|---|---|---|
| GET /submissions/{id}/review | owner 或提交者 | submissionId、projectId、status（OPEN/ACCEPTED/REJECTED/CORRECTION/PARTIAL）、sampleCount、items[] |
| PUT /submissions/{id}/review/items | owner；items[{sampleId,action,reason,result?}]，幂等键 | 审核视图；action ∈ ACCEPT/MODIFY/REJECT/CORRECTION，MODIFY 需 result |
| POST /submissions/{id}/review/decide | owner；{action:ACCEPT/REJECT/CORRECTION,reason}，幂等键 | 整份接受/退回/待修正 |
| GET /submissions/{id}/review/audit | owner 或提交者；limit/cursor | 只增审计记录（操作人、动作、样本、前后结果、理由、时间） |

整体审核状态由逐样本结论推导：全部 ACCEPT/MODIFY 为 ACCEPTED，全部 REJECT 为 REJECTED，全部 CORRECTION 为 CORRECTION，混合或未审完为 PARTIAL，未审为 OPEN。

### 9.2 按明确结果版本审核、修订与固定范围成果（已实现）

POST `/projects/{id}/reviews`，owner + 幂等键。固定范围请求：

```json
{"action":"ACCEPT","selectionId":"<REVIEW选择UUID>","reason":"确认所选答案"}
```

显式单条/批量请求：

```json
{"specVersionId":"<UUID>","action":"ACCEPT","items":[{"sampleId":"<UUID>","resultId":"<UUID>","baseReviewRevision":0}],"reason":"已检查"}
```

selectionId 与 items 互斥；显式 items 须给 specVersionId，每次 1—10000 条，不得重复。action 仅 ACCEPT/NEEDS_REVISION；待修正 reason 必填，可无 resultId。ACCEPT 需当前可审核候选。返回200 `{projectId,reviewedCount}`。项目锁与事务内校验整个范围，任何一项版本变化409 REVISION_CONFLICT，答案已修订409 RESULT_VERSION_CHANGED，处理占用409 SAMPLE_BUSY，范围过期410 SELECTION_EXPIRED，全部回滚。重复同键重放原响应，不再推进版本。置信度策略用于创建选择的筛选；此请求不另收 confidencePolicyId，不把分档当成审核决定。

POST `/projects/{id}/results/{resultId}/revisions`，owner + 幂等键：

```json
{"specVersionId":"<UUID>","sampleId":"<UUID>","baseReviewRevision":1,"resultFormat":"image-classification-v1","result":{"labelId":"normal"},"reason":"修正分类"}
```

resultFormat 可省略并由冻结规范推导；提供时须匹配。返回200 `{resultId,parentResultId,sourceKind:"OWNER",reviewRevision}`。保留父答案，只增新候选和审计，当前审核改 PENDING、revision+1，需另行确认；父答案保留历史但不再是当前可审核版本。新候选不继承模型置信度。图片和能耗共用此接口。

POST `/projects/{id}/final-snapshots`，owner + 幂等键：`{name,specVersionId,selectionId}`（FINALIZE），或兼容 `{name,specVersionId,sampleIds}`，二选一。201 返回完整快照。固定选择逐项校验 resultId/reviewRevision/当前采用指针；必须全为 ACCEPTED 且无活动占用。旧 sampleIds 模式仅表示在事务中固化当时的已确认答案，不提供浏览器所见版本的 CAS，新的界面应采用 selectionId。

快照详情包含 finalSnapshotId/projectId/specVersionId/name/scopeHash/createdAt/sampleCount/items；item 包含 sampleId、稳定 resultId（新快照）、sourceKind、submissionId、executionId、result、reviewRevision、sourcePath、contentHash。sourceKind 支持 HUMAN/AI/OWNER/AGGREGATION；旧快照 resultId 可为空。已生成快照永不因后续修订或再次发布而变化。

### 9.3 统一逐样本审核与最终成果（当前实现）

统一逐样本审核（跨人工/AI 来源，owner 执行）：

- `POST /projects/{id}/sample-reviews`：items[{sampleId,specVersionId,action,sourceKind?,submissionId?,executionId?,reason}]，幂等键。action 为 ACCEPT（需 HUMAN/AI 来源，校验归属与规范一致）或 NEEDS_REVISION（无来源）；AI 来源必须是已终结执行快照中的成功样本，运行中或失败/取消样本不能确认（409 AI_RESULTS_NOT_READY / 422 RESULT_NOT_REVIEWABLE）；每次写入推进 sample_review.revision 并追加 review_event 审计。
- `GET /projects/{id}/sample-reviews`：分页读取逐样本审核状态。

最终成果（只固化已确认结果，owner 执行）：

- `POST /projects/{id}/final-snapshots`：{name,specVersionId,sampleIds[]}，幂等键。所有样本须已 ACCEPTED，否则 422 FINAL_RESULTS_INCOMPLETE；固化结果内容与来源。
- `GET /projects/{id}/final-snapshots`、`GET /final-snapshots/{id}`：读取成果范围与冻结结果。

新批量确认及精确答案 CAS 使用9.2，不在旧 sample-reviews 中猜测 resultId 或选择最新答案。

## 10. 批量结果集成（已实现）

GET `/aggregation-algorithms`：启用用户；返回数组，当前仅 `majority-vote-v1`，图片分类、至少两名独立参与者、严格过半。GET 不运行集成。

POST `/projects/{id}/aggregations`，owner + 幂等键：

```json
{"algorithmId":"majority-vote-v1","specVersionId":"<UUID>","items":[{"sampleId":"<UUID>","resultIds":["<答案UUID1>","<答案UUID2>"]}]}
```

有界同步计算，201 `{aggregationId,projectId,specVersionId,algorithmId,status:"SUCCEEDED",createdAt}`；每次最多10000个不同样本，每样本1—100个明确当前答案。不接受演示 inputs/parameters 字段作为实际输入。每样本同 sourceActor 只能一票，重复结果、同一参与者跨轮次答案、OWNER/AGGREGATION 派生答案均422 DUPLICATE_SOURCE。输入及所有投票持久保存。

GET `/aggregations/{id}` 返回上述作业；GET `/aggregations/{id}/items?limit&cursor` 返回 `{sampleId,resultId,outcome,votes,result}`。outcome 为 SUGGESTED/TIE/INSUFFICIENT_INPUT；少于两名参与者不给建议；没有严格过半（含平票）不给建议。resultId/result 此时为 null。建议是 AGGREGATION 候选，不能当成已接受成果；需 owner 用9.2明确审核。votes 是各 labelId 的票数，不是模型 confidence。

## 11. 正式成果导出（已实现）

POST `/projects/{id}/exports`，owner + 幂等键：

```json
{"finalSnapshotIds":["<成果UUID>"],"format":"classification-csv-zip","includeOriginals":false}
```

支持 `classification-csv-zip` 和 `energy-jsonl-zip`，202 `{exportId,projectId,format,status:"QUEUED",errorCode,sha256,createdAt,expiresAt,downloadUrl}`。仅消费该项目的不可变成果快照，1—100个不同快照、最多10000条输入；重叠样本不同结果/规范/审核版本409 OVERLAPPING_SNAPSHOTS，完全一致则去重。includeOriginals=true 或不支持格式422 UNSUPPORTED_EXPORT_FORMAT。

持久作业由 Worker 处理，服务重启仍可继续；GET `/exports/{id}` 只查询 QUEUED/SUCCEEDED/FAILED，不模拟推进。失败 errorCode=EXPORT_BUILD_FAILED。导出生成数据上限64MiB，归档存入专用数据库 export_job；后续大规模导出可迁移对象存储，目前不宣称支持无限任务。`labeling.exports.worker-enabled` 默认true，poll-delay-ms 默认1000。

GET `/exports/{id}/download` 每次重新检查 owner，返回真正 `application/zip` 二进制和附件文件名，私有 no-store；未成功409 EXPORT_NOT_READY、7天过期410 EXPORT_EXPIRED、他人404。过期归档不再下载，数据回收需另定保留策略。元数据含 ZIP sha256，downloadUrl 只在成功时非空；它不是公开授权 URL。

分类 ZIP 包含 `results.csv` 与 `manifest.json`；CSV 字段 sampleId/sourcePath/specVersionId/resultId/labelId/reviewRevision/sourceKind/submissionId/executionId/inputHash，文本单元格防公式注入。能耗 ZIP 包含 `results.jsonl` 与 manifest；JSONL 保留完整 annotations、时区、24点/日值、来源行、数据集版本、审核/输入摘要等，不压平点或区间。manifest 含 schemaVersion/exportId/projectId/format/finalSnapshotIds/sampleCount/files[path,sha256,sizeBytes]，另附 specifications（冻结标签、说明、模板/schema）及 finalSnapshots（名称、范围摘要、时间）。能耗 JSONL 每条还保留可用的来源参与者、模型版本、分数和修订父结果 provenance。新快照稳定 resultId；旧快照可能为空，保留来源字段，不伪造。

## 12. 编辑器现有路径与新边界

旧备份中的试验实现曾提供 POST /assignments/{id}/editor-sessions、/editor-sync 和 /editor-ui/{sessionId}/...，并依赖完整 LS 服务及 V6 外部映射；这些接口未合入当前 backend 分支，不是新版接入方式。

新版编辑器直接由统一前端加载裁剪组件，通过第4—5节的本人样本/规范/草稿接口读写。转换后只发送平台 resultFormat/labelId；后端独立验证。保存是草稿，提交必须另行整包操作。不得把旧editor-sync与直接保存同时启用争写同一个草稿。

切换时停止创建外部会话并拒绝旧会话写入，保留历史；新客户端不请求editor-sessions/editor-sync。不要求浏览器持有LS Token。详见 [编辑器接入](06-editor-integration.md)。

## 13. 错误处理与联调顺序

| 情形 | HTTP/code | 前端处理 |
|---|---|---|
| 未登录/身份不可用 | 401 UNAUTHENTICATED / 503 IDENTITY_UNAVAILABLE | 登录或稍后重试，不删除草稿 |
| 停用、无权限 | 403 FORBIDDEN/LABELING_DISABLED，隐藏资源404 | 提示权限，不切换成别人的身份 |
| 草稿版本冲突 | 409 REVISION_CONFLICT（已有） | 保留本地选择，重新读取，由用户决定 |
| 人工包缺结果 | 422 INCOMPLETE_PACKAGE（已有） | 定位未完成项，禁止伪装提交成功 |
| 样本活动范围冲突 | 409 SAMPLE_BUSY | 显示被占用范围，调整选择或结束原执行 |
| 审核/选择过期 | 409 REVISION_CONFLICT/RESULT_VERSION_CHANGED / 410 SELECTION_EXPIRED（已实现） | 刷新并重新预览确认 |
| 能力/阈值非法 | 422 AGENT_INCOMPATIBLE/REFERENCE_UNSUPPORTED/INVALID_THRESHOLDS（已实现） | 修正配置，不自动换模型 |
| AI执行未终结 | 409 AI_RESULTS_NOT_READY（已有） | 仅展示进度，不审核运行中输出 |
| AI结果不可审核 | 422 RESULT_NOT_REVIEWABLE（已有） | 仅可审核终结快照中的成功结果 |
| 请求重放内容不同 | 409 IDEMPOTENCY_CONFLICT（已有） | 新操作用新键，重试用原请求原键 |

错误统一使用本节错误包；测试替身不代表共享服务器已经部署。

人工链路：登录→me→建项目/规范→上传READY→选择→打包预览/发布→领取→所有页items→保存草稿→完整提交→owner查询/审核→成果快照→导出。

AI链路：上传READY→选择样本（不用包）→agents→ai-executions→轮询至终态→看结果→手动policy→选择快照预览→reviews→final-snapshots→exports。

混合链路：AI结束→owner选择待修正结果→打人工包并携带参考→领取且整包提交→owner审核；反向为人工提交后选样创建AI批次。最终验收必须有真实模型和真实接口，不能用Mock或旧编辑器服务代替。

## 14. 历史审查记录

2026-11-10 的审查结论已归档，不用于判断当前接口状态；见 [合并前接口记录](archive/04-api.md)。

## 15. 注册模板与结构化输入（首轮已实现）

GET `/task-templates`：启用身份，data 为内置模板数组；字段 templateId/templateVersion/taskType/inputFormat/resultFormat/inputSchemaId/resultSchemaId/editorKey/enabled。当前图片 `image-classification@1.0.0` 和能耗 `energy-station-day@1.0.0`。它是可信注册声明，不返回远程代码、凭据或磁盘路径。未知 taskType/模板版本422 TEMPLATE_UNSUPPORTED/TEMPLATE_MISMATCH。

创建项目与规范可传 templateId/templateVersion；旧图片请求省略仍兼容。规范只增版本，保存模板、输入/结果 schema ID 和注册描述 schemaDigest；旧图片规范回填标识，历史摘要可为空。草稿、人工完整提交、按提交 MODIFY、通用 OWNER 修订及 AI 成功输出均调用同一规范校验器。

能耗继续复用 ZIP 分片上传和异步批次；不接受客户端任意 importerKey/importOptions。服务端按项目注册任务类型选择导入器。首轮只读 `sample/station_day_tasks_2k.csv`，根 `manifest.json` 必须含非空 datasetVersion（兼容 dataset_version；同时出现须相同），UTF-8 CSV 可带 BOM，支持标准带引号字段。忽略 preview 和其他文件作为样本；所有 ZIP 路径仍检查重复、穿越、符号链接、加密和展开大小。一次最多10000条站点日。

失败批次 GET `/imports/{id}` 为200业务状态 FAILED，errorCode 按首节约定，errors 包含 sourcePath/sourceRow/field/code/message；不存在“上传成功即解析成功”。跨批次重复导入整个批次失败，项目/数据集/任务和站点日均有唯一约束；失败不写任何样本或 source_asset。一份源 CSV 关联一个 source_asset，sample_input 保存受约束来源行与24点输入，不复制整份 CSV 给每个样本。sampleId 由项目、数据集版本、task_id 稳定生成。inputHash 对不含 sampleId/inputHash 的结构化输入按对象键排序、UTF-8 JSON 取 SHA-256。

结构化输入路径：

| GET 路径 | 授权 | 包装 |
|---|---|---|
| `/projects/{projectId}/samples/{sampleId}/data` | owner；项目/样本绑定 | 公共 data/requestId |
| `/assignments/{assignmentId}/samples/{sampleId}/data` | 本人有效 CLAIMED/IN_PROGRESS，或本人已 SUBMITTED；包内成员；非已提交要求项目 OPEN | 公共 data/requestId |
| `/internal/labeling/v1/executions/{executionId}/samples/{sampleId}/data?generation=N` | X-Worker-Key + 有效执行租约/generation + 固定成员 | 内部接口直接返回输入对象，与既有 Worker 协议一致 |

对象为 sampleId/inputFormat/schemaId/inputHash/source/payload/context，具体字段见首节。跨项目/范围404，过期或关闭领取409，旧 generation409 EXECUTION_LEASE_LOST。禁止借 CSV 文件地址读取他人样本。图片继续授权 file；能耗 file 返回422 STRUCTURED_INPUT_REQUIRED，不返回整份 CSV。

样本及 assignment/Worker items 对能耗给 dataUrl、inputFormat，fileUrl=null；图片 fileUrl 不变。assignment specification 含模板/schema 标识；Worker claim 也返回绑定模板和结果格式。未保存能耗草稿返回合法空 record（schemaVersion=1.0.0、annotations=[]），只有 explicitDone=true 且一个合法主标签才可完整提交。人工自评分开保存 labelIds=[HIGH|MEDIUM|LOW|UNKNOWN]，不能当 AI confidence。

未实现/未宣称验收：完整小时/日资产和历史上下文（当前 NOT_PROVIDED）、专家最终标签语义及额外必填规则、金标准/质检计划/统计快照接口、原图打包、第三方编辑器服务兼容、真实模型质量。质检 API 尚无确认的业务契约，本次不创建返回假统计的占位接口。

## 16. 可信任务插件目录与版本绑定

`GET /task-templates` 现在由后端 `TaskPluginRegistry` 产生，包含已登记插件的 `name`、五项 `capabilities`、有效 `enabled`、`defaultVersion`、`schemaDigest` 与 `exportFormats`，并保留已有模板标识和格式字段。前端创建项目必须同时检查后端能力及本地组件版本；未知版本不能回退。

2026-11-21 新增 `creationAvailable`，表示当前项目创建界面是否开放此契约。新项目仅选择 `COMPUTER_VISION`（图像标注）或 `TIME_SERIES`（时序标注），分别绑定 `computer-vision@1.0.0`、`time-series@1.0.0`。`IMAGE_CLASSIFICATION` 和 `TIMESERIES_ANNOTATION` 目录项保留且 `creationAvailable=false`，用于历史项目及旧客户端兼容；不改其冻结格式。具体分类、框选、时间点/区间由后续规范中的模板/XML 配置，业务细分不注册为独立项目类型。`GET /label-configs/task-types` 返回两个通用类别。

项目详情新增 `templateId`、`templateVersion`；创建项目传入这两个字段后，由 V24 持久绑定。导入批次也在创建时固定版本。历史规范和成果继续按其冻结版本读取与校验，修改规范不覆盖旧版本。不兼容输入 schema 的升级返回 `PLUGIN_INPUT_MIGRATION_REQUIRED`。

开发说明、接口边界与新增插件步骤见 [24-task-plugins.md](08-task-plugins.md)。此机制只运行随平台发布的可信代码，不提供上传后执行任意代码的接口。

插件成果格式查询：`GET /final-snapshots/{snapshotId}/plugin-export-formats`；作业列表：`GET /projects/{projectId}/exports?snapshotId=...&limit=20&cursor=...`。图片分类和站点日能耗的前端导出使用这些接口及既有 `POST /projects/{projectId}/exports`、`GET /exports/{exportId}/download`，编码器由成果的冻结插件版本决定。

## 结构化与半结构化数据插件

新增 `STRUCTURED`、`SEMI_STRUCTURED` 两类可信插件，沿用样本 `/data`、规范、草稿、提交、审核、成果和导出接口。新增记录校验只读接口及完整结果结构见 [记录插件接口与操作](10-record-data-plugins.md)。
