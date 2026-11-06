# 接口契约 V1.1

前缀 `/api/labeling/v1`。这是本平台业务契约，不是Label Studio原生API。身份与项目接口已有本地实现；图片导入、样本列表及 owner 原图读取的功能分支实现见第 3.1 节，其余路径仍为设计契约。代码实现不代表服务已部署或真实数据集已验收。

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

上表中的身份、建项、本人项目列表和项目详情接口已在本地服务实现。服务将原始 `Authorization` 头转交训练后端 `/api/user/current-user` 验证，再把身份ID映射为唯一HUMAN actor；不读取或复制训练平台roleId。训练后端不可用时返回503。项目仅owner可查看和管理。创建项目与创建规范版本要求 `Idempotency-Key`，相同键和请求返回原资源，不同请求复用同键返回409。`taskType`先按大写编码保存，具体首期任务模板仍需团队确认后冻结。

创建示例：
```json
{"name":"表面缺陷标注","description":"按项目规范标注","taskType":"IMAGE_CLASSIFICATION"}
```
示例任务类型只用于说明字段，不代表首期范围已确定。调用者ID由登录身份写入ownerActorId；没有“申请成为需求方”的接口。

## 3. 上传与解析

| 方法与路径 | 权限 | 输入与行为 | 返回 |
|---|---|---|---|
| POST /projects/{id}/imports | owner | filename、sizeBytes、sha256 | 201 batchId、chunkSizeBytes、receivedBytes=0 |
| GET /imports/{batchId} | owner | 查询状态/失败原因/有效样本数 | UPLOADING/PROCESSING/READY/FAILED与offset |
| PUT /imports/{batchId}/chunks?offset=0 | owner | application/octet-stream；X-Chunk-SHA256；按服务器chunkSize发送 | receivedBytes；顺序追加 |
| POST /imports/{batchId}/complete | owner | 无 | 202解析任务；总长度/摘要不符422 |
| POST /imports/{batchId}/retry | owner | 仅FAILED且原始文件完整 | 202；按稳定批次路径幂等解析，不重复建sample |
| GET /imports/{batchId}/samples | owner | cursor/limit | sampleId、相对路径、媒体类型与解析元数据 |

同offset已上传分片且摘要一致返回当前offset；不一致409 UPLOAD_CONFLICT；未知间隙409 OFFSET_MISMATCH。完成后拒绝追加。解析只允许配置的格式，错误逐文件返回；压缩包校验路径、符号链接与展开数量/大小。容量上限由部署配置和任务格式确定，不硬编码100MB产品限制。

原始文件只能经授权文件接口下载；不会把storageKey、全库目录或长效凭据直接暴露给浏览器。

### 3.1 图片分类首轮实现

本轮固定字段、错误和配置见 [图片导入接口细则](08-image-import-api.md)。七个接口的调用示例见 [前端示例](../../labeling-service/examples/README.md)，分支与交付约束见 [交接说明](07-image-import-handoff.md)。

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

GET /admin/actors、POST /admin/actors/{id}/disable、POST /admin/actors/{id}/enable仅标注管理员可用；只影响标注平台使用状态，不改统一用户账号或训练权限。禁用不自动删除领取与结果；后续读写拒绝，可由管理员回收。初始管理员通过受控初始化配置建立，首期不开放自助授予管理员接口。

GET /admin/audit-events供标注管理员查看管理审计，不返回原始数据/完整答案。所有回收、暂停、禁用需reason。

| HTTP | code示例 |
|---|---|
| 401 | UNAUTHENTICATED |
| 403 | LABELING_DISABLED、ADMIN_REQUIRED |
| 404 | RESOURCE_NOT_FOUND（包括不应暴露的他人资源） |
| 409 | SLOT_FULL、CLAIM_BUSY、RESOURCE_BUSY、ALREADY_CLAIMED、BATCH_ALREADY_PUBLISHED、REVISION_CONFLICT、ASSIGNMENT_CLOSED、PROJECT_PAUSED、IDEMPOTENCY_CONFLICT |
| 410 | EXPORT_EXPIRED |
| 413 | UPLOAD_TOO_LARGE |
| 422 | INVALID_RESULT、INCOMPLETE_PACKAGE、UNSUPPORTED_FORMAT、NO_SUBMISSIONS |
| 503 | IDENTITY_UNAVAILABLE、EDITOR_UNAVAILABLE、TRANSACTION_RETRY_EXHAUSTED |

## 8. 最小联调顺序

普通用户GET /me → POST /projects（无人审批）→ 建规范 → 上传完成 → 打包预览/发布 → 两个不同用户领取N=2包 → 分别编辑/同步/提交 → owner查看两份结果 → 创建导出 → 下载核对ID。并行验收第三人超领、同人重复领取、未领取读文件、回收后迟到提交、同一结果重复提交、导出他人项目均被正确处理。

2026-11-04一致性补充：暂停事务提交后拒绝新工作数据访问和草稿/提交/同步写入；此前已持共享锁的短事务先完成，已传输的字节无法撤回。导出提交清单不随重试变化。数据库死锁/序列化冲突最多重试整个事务3次，外部编辑器和存储请求不放进数据库重试闭包。幂等记录与业务写入/响应同事务提交；上传分片回执持久化，持久化成功前不推进offset。
