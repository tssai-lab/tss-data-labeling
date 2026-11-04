# 模块二对外边界契约 v1

## 1. 契约目标

本文档用于稳定模块二对前端、模块一、训练执行模块、推理模块等其他模块暴露的接口边界。

模块二负责：

- 模型资产与模型版本管理。
- 数据集资产与数据集版本管理。
- 代码资产、在线编辑工作区、不可变代码版本、校验与审批证据管理。
- 模型/数据集分片上传与 MinIO 存储。
- 训练任务/训练实验元数据管理、结果回写和当前本地轻量训练启动。
- 用户资源隔离与文件归属校验。

其他模块应优先通过模块二提供的稳定 ID 引用资源，而不是直接依赖数据库表结构或 MinIO 对象路径。

## 2. 鉴权边界

所有模块二业务接口都依赖模块一登录态：

```http
Authorization: Bearer <token>
```

模块二读取：

```java
StpUtil.getLoginIdAsInt()
StpUtil.getTokenSession().get("roleId")
```

角色规则：

| roleId | 说明 | 模块二权限 |
| --- | --- | --- |
| 1 | super_admin | 通常可访问全部资源；通过显式 `/api/v2/admin/code-*` 管理面跨 owner 管理训练代码 |
| 2 | admin | 通常可访问全部资源；通过显式 `/api/v2/admin/code-*` 管理面跨 owner 管理训练代码 |
| 3 | user | 只能访问自己的资源 |

资源隔离字段：

```text
owner_user_id
```

普通用户查询、详情、删除、下载时均按 `owner_user_id` 过滤。V2 普通代码资产、版本、工作区及源码接口也始终按当前用户过滤，管理员使用这些普通路径时不获得隐式跨 owner 权限。跨用户管理只能通过显式 `/api/v2/admin/**` 代码管理、审核、审批和历史制品恢复能力完成，且管理员权限检查先于参数绑定和资源查询。

## 3. 统一响应格式

Legacy 模块二接口主要使用 `ApiResponse<T>`：

```json
{
  "success": true,
  "data": {},
  "errorMessage": null
}
```

失败示例：

```json
{
  "success": false,
  "data": null,
  "errorMessage": "model not found or no permission"
}
```

调用 Legacy 接口时以前述 `success` 为准。V2 数据集和代码资产接口成功时直接返回 DTO、列表或文件流，不包裹 `ApiResponse`；JSON 失败响应使用 `V2ErrorResponse`：

```json
{
  "success": false,
  "errorCode": "CODE_ASSET_CONFLICT",
  "errorMessage": "code asset conflict",
  "details": {
    "reasonCode": "WORKSPACE_REVISION_CONFLICT"
  },
  "traceId": "..."
}
```

V2 代码接口使用真实 HTTP 状态：`403` 表示管理员能力不足，`404` 隐藏不存在或跨用户资源，`409` 表示并发或生命周期冲突，`413` 表示超出在线内容上限，`422` 表示校验失败，`503` 表示制品存储暂不可用。

## 4. 稳定资源 ID

其他模块应使用以下 ID 做跨模块引用：

| ID | 说明 | 稳定性 |
| --- | --- | --- |
| `modelVersionId` | 模型版本 ID，对应模型包和模型元数据 | 稳定 |
| `modelAssetId` / `assetId` | 模型资产 ID，表示同一模型的逻辑集合 | 稳定 |
| `datasetVersionId` | 数据集版本 ID，对应数据集文件和数据集元数据 | 稳定 |
| `datasetAssetId` / `assetId` | 数据集资产 ID，表示同一数据集的逻辑集合 | 稳定 |
| `codeAssetId` / `assetId` | 代码资产 ID，表示同一代码资产的逻辑集合 | 稳定 |
| `codeVersionId` / `versionId` | 已发布、不可变代码版本 ID | 稳定 |
| `workspaceId`（数据集工作区接口） | 数据集版本工作区 ID，只在 DRAFT 生命周期中有效 | 临时 |
| `workspaceId`（代码工作区接口） | 代码编辑工作区 ID，只在草稿生命周期中有效 | 临时 |
| `artifactSha256` | 已发布代码制品原始字节的 SHA-256 证据 | 版本级稳定证据 |
| `experimentId` | 训练实验 ID，包含多个实验版本 | 稳定 |
| `trainingVersionId` | 训练实验版本 ID | 稳定 |
| `uploadId` | 上传会话 ID，只在上传流程中有效 | 临时 |
| `importJobId` | 初始导入或 APPEND ImportJob ID；V2 中用于查询、FULL 重试 FAILED 导入和 INCREMENTAL 重试 PARTIAL 导入 | 业务句柄 |

不建议其他模块持久依赖：

```text
storagePath
objectName
MinIO bucket
MinIO endpoint
packageId
minio_delete_task id
scheduler_lock row
```

这些属于存储或后台调度实现细节，后续可调整。

## 5. 模型边界

### 5.1 模型上传

基础路径：

```text
/api/model/upload
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/init` | 初始化上传，返回 `uploadId` 和分片信息 |
| `POST` | `/chunk` | 上传指定分片 |
| `GET` | `/progress` | 查询上传进度 |
| `POST` | `/complete` | 合并分片，生成模型资产和模型版本 |

稳定返回字段：

| 字段 | 说明 |
| --- | --- |
| `uploadId` | 上传会话 ID |
| `id` | 模型版本 ID，也可视为 `modelVersionId` |
| `assetId` | 模型资产 ID |
| `name` | 模型名称 |
| `version` | 模型版本号 |
| `type` | 模型/训练任务类型，`CV`、`NLP`、`POINT_CLOUD` 或 `ROBOT` |
| `remark` | 备注 |
| `fileName` | 原始文件名 |
| `sizeBytes` | 文件大小 |
| `artifactSha256` | 服务端读取完整对象后计算的 SHA-256 |
| `commitInfo` | 本次模型提交信息 |
| `hyperParams` | 模型超参数对象，未提供时为 `{}` |
| `isCurrent` | 该版本是否为资产当前版本 |
| `status` | 上传状态 |
| `ownerUserId` | 资源归属用户 ID |
| `createdAt` | 创建时间 |
| `updatedAt` | 更新时间 |

上传状态可能为 `UPLOADING`、`COMPLETING`、`COMPLETED`。`COMPLETING` 表示服务端正在合并分片；同一个 `uploadId` 并发调用 `/complete` 不会重复生成模型资产/版本。

上传约束：

```text
模型文件只支持 .zip
init 时 commitInfo 必填，去除首尾空白后长度为 1～1024；hyperParams 可选且默认为 {}
complete 时 uploadId、modelName、version、type、remark、commitInfo 都不能为空
type 仅支持 CV、NLP、POINT_CLOUD 或 ROBOT
zip 必须是合法且非空的压缩包
zip 内路径可以使用 / 或 \，后端统一规范化为 /；规范化后不能包含绝对路径、盘符、.. 或空字节
zip 内规范化路径必须唯一，并拒绝文件/目录冲突
zip 条目数不超过 100000，解压后总体积不超过 50GB
```

模型 `type`（V2 init 中为 `taskType`）是上传者声明的业务元数据。后端校验枚举值、
已有资产类型一致性、ZIP 安全、大小、扩展名和制品完整性，但不从权重文件名、模型框架
或二进制内容推断其实际属于 CV、NLP、POINT_CLOUD 还是 ROBOT。因此，将同一个合法权重
包分别声明为 `CV` 或 `NLP` 均可按上传契约进入 READY；原 A-MODEL-13
“根据权重内容识别 CV/NLP”的失败预期由本声明型契约取代。训练创建时模型元数据与
数据集元数据的类型匹配规则保持不变。

创建模型资产的 CRUD 和上传流程共用名称规则：去除首尾空白后必须为 1～255 个字符；
同一 owner 下未删除模型资产名称按去除首尾空白后、不区分大小写唯一。纯空白名称返回
HTTP `400`，重复名称或并发重复创建返回 HTTP `409`。模型与数据集使用不同命名空间，
软删除资产不占用名称。

完成上传时，后端会检查 MinIO 对象存在性、实际长度并完整计算 SHA-256；只有检查和 ZIP 校验全部成功才创建或提升 `READY` 版本。每次成功上传的 `READY` 版本自动成为 `model_asset.currentVersionId`。

### 5.2 模型查询

基础路径：

```text
/api/model
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/list` | 查询当前用户可见模型版本列表 |
| `GET` | `/detail?id={modelVersionId}` | 查询模型版本详情 |
| `GET` | `/code-files?id={modelVersionId}` | 查询模型 zip 内可预览代码文件 |
| `GET` | `/previewCode?id={modelVersionId}&path={path}` | 预览模型 zip 内代码文本 |
| `DELETE` | `/delete?id={modelVersionId}` | 删除模型版本及对象文件 |

`/list` 可选 query: `type`, `keyword`, `page`/`current`, `pageSize`。返回体包含 `data`, `total`, `page`, `pageSize`。
`keyword` 去除首尾空白后，仅按模型显示名称做不区分大小写的子串匹配；空白值等同未传，`%`、`_`、`\` 按普通字符匹配。

模型列表稳定字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 模型版本 ID |
| `assetId` | 模型资产 ID |
| `name` | 模型名称 |
| `version` | 模型版本号 |
| `type` | `CV`、`NLP`、`POINT_CLOUD` 或 `ROBOT` |
| `remark` | 备注 |
| `ownerUserId` | 归属用户 |
| `sizeBytes` | 文件大小 |
| `artifactSha256` | 制品 SHA-256；尚未校验的历史版本可为空 |
| `commitInfo` | 提交信息；历史版本可为空 |
| `hyperParams` | 模型超参数对象 |
| `status` | `DRAFT`、`READY`、`DEPRECATED` 或 `ARCHIVED` |
| `isCurrent` | 是否为资产当前版本 |
| `createdAt` | 创建时间 |

`/list` 和 `/detail` 均不返回 `storagePath`。代码预览严格按 UTF-8 解码；非法字节会被
拒绝，不会使用替换字符继续返回。

### 5.3 模型版本生命周期与稳定消费接口

- `POST /api/model-versions` 只创建 `DRAFT` 元数据版本，不接受 `status`、存储路径、文件名、大小、摘要、发布时间等服务端字段；提交这些字段返回 HTTP `400`。
- `PUT /api/model-versions/{id}` 只允许补录版本号、说明、变更信息、`commitInfo` 和 `hyperParams`，不能修改资产归属、状态或制品字段。
- `PUT /api/model-assets/{assetId}/current-version` 是兼容切换接口；正式 V2 路径为 `PUT /api/v2/model-assets/{assetId}/current-version`，请求体均为 `{"versionId":"..."}`。
- 切换目标必须属于同一资产、未删除、状态为 `READY`，并通过实时对象长度和 SHA-256 校验。
- 当前版本不能废弃、归档或通过版本接口删除。一个资产的最后一个未删除版本也不能通过版本接口删除，只能随资产删除；其他版本仍受训练引用检查。
- `DELETE /api/model/delete` 与 `DELETE /api/model-versions/{id}` 共用同一生命周期服务。

稳定消费接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v2/model-versions/{modelVersionId}/consumer-manifest` | 返回模型版本稳定消费清单 |
| `GET` | `/api/v2/model-versions/{modelVersionId}/download` | 按模型版本下载制品 |
| `GET` | `/api/v2/model-versions/{modelVersionId}/files` | 返回模型 ZIP 文件树 |
| `GET` | `/api/v2/model-versions/{modelVersionId}/files/content?path=...` | 预览允许的文本文件 |

`consumer-manifest` 固定返回 `modelAssetId`、`modelVersionId`、`version`、`status`、`type`、`fileName`、`sizeBytes`、`artifactSha256`、`commitInfo`、`hyperParams`、`isCurrent`、`downloadUrl`、`filesUrl`，不返回 bucket、`storagePath`、objectName 或签名 URL。

切换当前版本、`consumer-manifest`、文件树和文本内容在交付前执行完整对象长度、
SHA-256 与 ZIP 结构校验；历史 `READY` 版本摘要为空且对象有效时原子回填。下载接口
只做一次 MinIO 对象读取：先 stat 校验长度，再在向调用方发送的同一条流上计算长度与
SHA-256，不再完整预读后重新打开。已有摘要时返回 `X-Artifact-Sha256`；历史空摘要首次
下载不返回该头，在流完整到达 EOF 且校验通过后原子回填。若流式校验在响应提交后失败，
调用方会收到不完整流，不能期待 JSON 错误体。

对象缺失、长度不符、摘要不符或 ZIP 结构非法时，能在响应提交前判定的请求返回
`422 / MODEL_ARTIFACT_INVALID`，并将确定损坏版本降为 `DRAFT`、清除对应当前指针。
MinIO 临时不可用返回 `503 / MODEL_STORAGE_UNAVAILABLE`，不修改版本状态。

## 6. 数据集边界

### 6.1 数据集上传

基础路径：

```text
/api/dataset/upload
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/init` | 初始化数据集分片上传 |
| `POST` | `/chunk` | 上传数据集分片 |
| `GET` | `/progress` | 查询上传进度 |
| `POST` | `/complete` | 合并分片，生成数据集资产和版本 |
| `POST` | `/folder` | 上传 CV 文件夹，服务端打包为 zip |

稳定返回字段：

| 字段 | 说明 |
| --- | --- |
| `uploadId` | 上传会话 ID |
| `id` | 数据集版本 ID，也可视为 `datasetVersionId` |
| `assetId` | 数据集资产 ID |
| `name` | 数据集名称 |
| `version` | 数据集版本号 |
| `datasetVersionId` | 数据集版本 ID，与 `id` 一致 |
| `versionNo` | 后端生成的版本序号 |
| `versionLabel` | 展示版本标签 |
| `description` | 当前版本说明 |
| `changeLog` | 相对父版本的变更说明 |
| `parentVersionId` | 父版本 ID |
| `type` | 数据集任务类型，`CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL` |
| `cvTaskType` | CV 子任务类型，非 CV 为 `null` |
| `annotationFormat` | CV 标注格式，非 CV 为 `null` |
| `remark` | 备注 |
| `fileName` | 文件名 |
| `sizeBytes` | 文件大小 |
| `status` / `uploadStatus` | 上传会话状态 |
| `versionStatus` | 数据集版本状态，普通类型完成后为 `READY`，`MULTIMODAL` 完成后为 `DRAFT` |
| `importJobId` / `importStatus` | `MULTIMODAL` 导入任务信息，普通类型为 `null` |
| `ownerUserId` | 资源归属用户 ID |
| `createdAt` | 创建时间 |
| `updatedAt` | 更新时间 |

上传状态可能为 `UPLOADING`、`COMPLETING`、`COMPLETED`。`COMPLETING` 表示服务端正在合并分片；同一个 `uploadId` 并发调用 `/complete` 不会重复生成数据集资产/版本。

上传约束：

| 类型 | 规则 |
| --- | --- |
| `CV` | 分片上传只支持 zip，`/folder` 可上传图片文件夹；必须至少包含一个图片文件；可按 `annotationFormat` 携带标注文件 |
| `NLP` | 支持 `.txt`、`.json`、`.jsonl`、`.csv`、`.xlsx`、`.xls`、`.pdf`、`.docx`、`.xml`，或仅包含这些文件的 zip |
| `POINT_CLOUD` | 支持单文件 `.ply`、`.pcd`，或仅包含 `.ply`、`.pcd`、`.txt`、`.json`、`.yaml`、`.yml` 且至少包含一个点云文件的 zip |
| `ROBOT` | 支持单文件 `.xml`、`.yaml`、`.yml`，或仅包含 `.xml`、`.yaml`、`.yml`、`.json`、`.txt` 的 zip |
| `MULTIMODAL` | 只支持 zip；`sampleGrouping` 支持 `AUTO_DIRECTORY` 或 `MANIFEST`，未传时默认 `AUTO_DIRECTORY`；`strictManifest=true` 仅支持 `MANIFEST`；完成上传后先创建 `DRAFT` 版本和 `PENDING` ImportJob |

CV 子任务和标注格式：

| 字段 | 默认值 | 支持值 |
| --- | --- | --- |
| `cvTaskType` | `UNLABELED` | `IMAGE_CLASSIFICATION`、`OBJECT_DETECTION`、`SEMANTIC_SEGMENTATION`、`INSTANCE_SEGMENTATION`、`UNLABELED`、`OTHER` |
| `annotationFormat` | `NONE` | `NONE`、`FOLDER_CLASSIFICATION`、`CSV`、`YOLO`、`COCO`、`VOC`、`MASK`、`LABELME`、`OTHER` |

CV 标注文件白名单：

| `annotationFormat` | 允许的非图片文件 | 是否必须包含标注文件 |
| --- | --- | --- |
| `NONE` / `FOLDER_CLASSIFICATION` / `MASK` | 无 | 否 |
| `CSV` | `.csv` | 是 |
| `YOLO` | `.txt`、`.yaml`、`.yml` | 是 |
| `COCO` / `LABELME` | `.json` | 是 |
| `VOC` | `.xml` | 是 |
| `OTHER` | `.txt`、`.json`、`.xml`、`.csv`、`.yaml`、`.yml` | 否 |

创建数据集资产的 CRUD、分片上传和文件夹上传共用名称规则：去除首尾空白后必须为
1～255 个字符；同一 owner 下未删除数据集资产名称按去除首尾空白后、不区分大小写
唯一。纯空白名称返回 HTTP `400`，重复名称或并发重复创建返回 HTTP `409`。软删除资产
不占用名称。

`annotationFormat=YOLO` 时，类别元数据 YAML 不按标签行解析；其余 `.txt` 标签必须
严格 UTF-8，非空行必须恰好五列：
`classId centerX centerY width height`。`classId` 是 0～2147483647 的十进制整数，
坐标必须为有限十进制数，中心坐标范围 `[0,1]`，宽高范围 `(0,1]`。

zip 附加约束：

```text
zip 内路径可以使用 / 或 \，后端统一规范化为 /；规范化后不能包含绝对路径、盘符、.. 或空字节
限制条目数量不超过 100000，解压后总体积不超过 50GB
```

`MULTIMODAL + MANIFEST` 的 `manifestPath` 以及 manifest JSON 内的 `data[].path`、`annotations[].path`、`annotations[].ref_data_path` 使用同一套 ZIP 路径规则：接受 `/` 或 `\`，统一规范化为 `/`，继续拒绝空路径、绝对路径、Windows 盘符、空字节和 `..`。

`strictManifest` 为可选字段，默认 `false`。宽松模式下未被 manifest 声明的普通 ZIP entry 只产生 warning，不中断导入；`strictManifest=true` 时，任一未声明普通 ZIP entry 都会使 ImportJob 失败，错误码为 `INVALID_MANIFEST_UNDECLARED_ENTRY`，V2 `userError` 透出对应用户错误。该字段不支持 `AUTO_DIRECTORY` 或单模态上传。

`MULTIMODAL` complete 不执行单模态 zip 白名单和全量解压校验；manifest、AUTO_DIRECTORY 目录结构和 ZIP 内容校验由异步 ImportJob 完成。导入全部成功后版本变为 `READY`；任务级失败或 0 个样本成功时 ImportJob 为 `FAILED`，版本保持 `DRAFT`；部分成功时 ImportJob 为 `PARTIAL`，成功样本保留在 DRAFT 工作区，但该版本不可 publish，不能进入 READY 查询或训练消费。

MANIFEST 校验失败会把安全的定位信息持久化到 ImportJob，并由 V2 上传状态、数据集列表、
工作区和 ImportJob 状态接口统一映射到 `userError.details`。`field` 和 `reason` 固定存在；
能够定位样本或文件时还会返回 `externalId`、`sampleIndex`、`path`，非法 JSON 可返回
`line/column`。详情不包含 bucket、对象存储路径、堆栈或内部异常类名。

### 6.1.1 ImportJob 失败重试

MULTIMODAL 导入失败后，允许对失败任务做受控重试：

```http
POST /api/dataset-samples/import/{importJobId}/retry?mode=FULL
POST /api/dataset-samples/import/{importJobId}/retry?mode=INCREMENTAL
POST /api/v2/import-jobs/{importJobId}/retry
```

V2 请求体为
`{"mode":"FULL|INCREMENTAL","expectedWorkspaceRevision":12}`；Legacy
仍使用 query 参数且不作为新前端契约。

边界：

- 只支持 `FULL` 和 `INCREMENTAL` 两种 mode；`PARTIAL` 或未知 mode 会被拒绝。
- `FULL` 只允许 `FAILED -> PENDING`，随后由现有 ImportJob launcher 重新调度；FULL 不扩展到 `PARTIAL`。
- `FULL` 会清空错误字段并重置进度，但不会清理已落库的半导入样本；若检测到已有样本，会拒绝重试。
- `INCREMENTAL` 只允许 `PARTIAL -> PENDING`，只重跑 failure 表中未解决样本，复用 failure row 保存的 `externalId` 和原始 `sampleIndex`，不重新计算 sampleIndex。
- 0 个样本成功的导入仍为 `FAILED`，不能走 `INCREMENTAL`。
- DatasetVersion 在导入成功前仍保持 `DRAFT`；重试要求对应 DatasetVersion 仍为 `DRAFT`，且同版本不存在其他 `PENDING` 或 `RUNNING` ImportJob。
- V2 `importJobId` 是用户侧重试句柄，不是存储路径、owner 标识或 MinIO objectName；无权限或不存在时重试端点返回 404 语义。

V2 重试返回稳定字段：

| 字段 | 说明 |
| --- | --- |
| `importJobId` | 被重试的导入任务句柄 |
| `workspaceId` / `workspaceRevision` | 所属版本工作区 ID 和重试后递增的 revision；只读状态查询当前不填充这两个字段 |
| `status` | ImportJob 原始状态 |
| `displayStatus` | V2 展示状态：`IMPORTING`、`IMPORT_FAILED`、`IMPORT_PARTIAL` 或 `READY` |
| `importProgress` | 当前导入进度 |
| `totalSamples` / `importedSamples` / `failedSamples` | 导入样本计数；`failedSamples` 来自未解决 failure row |
| `retryable` / `retryModes` | 是否可由用户重试；`PARTIAL` 返回 `["INCREMENTAL"]` |
| `userError` | 重试后仍失败时的结构化用户错误；非失败状态为 `null` |

V2 重试错误语义：

| HTTP | `errorCode` | 场景 |
| --- | --- | --- |
| `404` | `IMPORT_JOB_NOT_FOUND` | 状态查询时任务不存在或无权访问；重试时任务 ID 不存在 |
| `404` | `DATASET_WORKSPACE_NOT_FOUND` | 重试任务存在，但所属工作区不存在、不再是 DRAFT 或调用方无权访问 |
| `422` | `IMPORT_JOB_NOT_RETRYABLE` | mode 与任务状态不匹配、FULL retry 已存在导入样本，或 INCREMENTAL 无未解决失败样本 |
| `400` | `INVALID_IMPORT_JOB_RETRY` | JSON 请求体为 `null` |
| `400` | `INVALID_REQUEST` | 请求体缺失、JSON 或字段类型无法解析 |
| `400` | `EXPECTED_WORKSPACE_REVISION_REQUIRED` | 缺少 `expectedWorkspaceRevision` |
| `409` | `WORKSPACE_REVISION_CONFLICT` | `expectedWorkspaceRevision` 已过期 |
| `409` | `WORKSPACE_BUSY` | 工作区存在其他活动上传或导入 |

### 6.1.2 数据集版本工作区

已有 READY 数据集可以派生 DRAFT 版本工作区，用于在不修改父 READY 的前提下编辑样本、数据文件、原始标注文件和版本元数据，最终通过 publish 成为新的 READY 版本并更新 `currentVersionId`。

- `MULTIMODAL` 继续支持 `MANIFEST` 或 `AUTO_DIRECTORY` ZIP 追加。
- ZIP-backed `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 也支持工作区增删改和 ZIP 追加；单模态追加必须省略 `sampleGrouping`、`manifestPath` 和 `strictManifest`，后端按任务类型校验 ZIP 内容，并按 ZIP entry 生成一文件一样本的元数据。
- 没有 package 元数据的 ZIP-backed 单模态旧版本创建 DRAFT 时会把父 ZIP 登记为 `PRIMARY` package，并生成 Sample/Data 元数据，因而可以在工作区删除已有文件。
- 非 ZIP 旧版本来源可唯一推导时，在新 DRAFT 中懒规范化为 RAW 主包；来源歧义时不修改父 READY，并返回 `DATASET_WORKSPACE_SOURCE_AMBIGUOUS`。
- DRAFT 查询、变更、预览、下载、放弃和发布必须使用 V2 workspace 专用接口；普通样本查询仍只承诺 READY 版本。

### 6.2 数据集消费清单

新模块读取数据集内容时，优先使用 V2 只读消费清单：

```http
GET /api/v2/dataset-versions/{datasetVersionId}/consumer-manifest?page=1&pageSize=100
```

该接口只接受调用方有权限访问的 `READY` 数据集版本。返回内容包括：

| 字段 | 说明 |
| --- | --- |
| `datasetVersionId` | 数据集版本 ID |
| `datasetId` | 数据集资产 ID |
| `type` | 数据集类型 |
| `versionLabel` | 版本展示标签 |
| `samples[]` | 样本清单 |
| `samples[].data[]` | 样本数据项及固定 preview/download 链接 |
| `samples[].annotations[]` | 标注项及固定 download 链接 |

该接口不返回 `storagePath`、MinIO objectName、bucket、packageId、ZIP offset、CRC 或数据库内部字段。

训练、推理、评估等模块如果需要枚举样本，应依赖该消费清单或固定 preview/download 接口，不应直接扫描 MinIO 或查询模块二数据库表。

跨版本按场景 ID 查询时，可以使用数据管理侧 externalId 查询：

```http
GET /api/dataset-samples/multimodal?externalId=scene-001&datasetVersionIds=version-1&datasetVersionIds=version-2&page=1&pageSize=20
GET /api/v2/dataset-samples/multimodal?externalId=scene-001&datasetVersionIds=version-1,version-2&page=1&pageSize=20
```

该查询只接受 `READY`、`deleted=false` 且调用方有权访问的 DatasetVersion。每个 `datasetVersionId` 都会回溯到 `DatasetAsset.ownerUserId` 做权限判断；任一版本不存在、无权限、已删除或不是 READY 时，整个查询失败，错误语义统一为“不存在或无权访问”，不暴露是哪一个 versionId 失败。

成功结果按 `datasetVersionId ASC, sampleIndex ASC, createdAt ASC, id ASC` 稳定分页，只返回 `datasetVersionId`、`sampleId`、`externalId`、`sampleIndex`、`data`、`annotations` 以及固定 preview/download 链接；不返回 `storagePath`、MinIO objectName、bucket、packageId、ZIP offset、CRC 或数据库内部字段。该接口只负责数据管理侧查找，不做训练 batch 组装、样本选择策略、模型匹配或 TaskType 扩展。

### 6.2.1 当前非稳定契约
- workspace 审计日志已实现，legacy 路径为 `GET /api/dataset-versions/{datasetVersionId}/workspace/audit-logs`，V2 路径为 `GET /api/v2/dataset-versions/{datasetVersionId}/workspace/audit-logs`。该接口做 owner 权限校验，但 `operation`、`targetId`、`packageId`、`sampleId`、`details` 等字段只用于模块二内部回溯，不属于训练、推理或其他模块稳定集成契约。
- workspace 审计日志不记录也不返回 `storagePath`、MinIO objectName、bucket 或 ZIP offset；不得用于推导物理文件位置或驱动 publish、retry、清理等业务状态。
- package 引用感知物理清理已在模块二内部实现 dry-run 与显式安全入队；默认不删除，只有 `canDelete=true` 时写入 `MinioDeleteTask`，且不会直接调用 MinIO delete。该能力当前不作为训练、推理或其他外部模块稳定契约；外部仍只应依赖软删除状态、READY `datasetVersionId` 和 consumer manifest。

### 6.3 数据集查询

基础路径：

```text
/api/dataset
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/list` | 查询当前用户可见数据集列表 |

`/list` 可选 query: `type`, `keyword`, `page`/`current`, `pageSize`。返回体包含 `data`, `total`, `page`, `pageSize`。未传 `pageSize` 时 legacy V1 返回全部；传入时最大 `200`。
legacy V1 与 `GET /api/v2/datasets` 共用搜索语义：`keyword` 去除首尾空白后，仅按数据集显示名称做不区分大小写的子串匹配；空白值等同未传，`%`、`_`、`\` 按普通字符匹配。

数据集列表稳定字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 数据集资产 ID |
| `assetId` | 数据集资产 ID |
| `versionId` | 当前推荐 READY 数据集版本 ID；没有 READY 时为 `null` |
| `currentVersionId` | 当前推荐 READY 数据集版本 ID；语义与 `versionId` 一致 |
| `name` | 数据集名称 |
| `version` | 当前推荐 READY 版本展示标签 |
| `type` | `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL` |
| `cvTaskType` | CV 子任务类型，NLP 为 `null` |
| `annotationFormat` | CV 标注格式，NLP 为 `null` |
| `remark` | 数据集备注 |
| `fileName` | 当前推荐 READY 版本文件名 |
| `sizeBytes` | 当前推荐 READY 版本文件大小 |
| `ownerUserId` | 归属用户 |
| `uploadTime` | 当前推荐 READY 版本上传时间；没有 READY 时回退资产创建时间 |
| `createdAt` | 资产创建时间 |
| `updatedAt` | 资产更新时间 |
| `currentVersionNo` / `currentVersionLabel` | 当前推荐 `READY` 版本的序号和展示标签 |
| `versionStatus` | 当前推荐版本状态；新建 `MULTIMODAL` 只有 DRAFT 时可为 `null` |
| `currentVersionFileCount` / `fileCount` | 当前推荐 READY 版本文件计数；元数据化版本按 Sample Data 与 Annotation 计数，传统 ZIP 按 ZIP 非目录 entry 计数，无法计算时为 `null` |
| `latestDraftVersionId` | 最新未删除 DRAFT 版本 ID |
| `importJobId` / `importStatus` / `importProgress` | 最新 DRAFT 的导入任务展示字段 |

`versionId` / `currentVersionId` 始终指向当前推荐 READY 版本；后端优先使用 `dataset_asset.current_version_id`，不可用时回退到 `versionNo` 最大的 READY 版本。`latestDraftVersionId` 和 `import*` 只用于展示工作区/导入状态，不改变当前 READY 语义。文件数优先读取 `dataset_version.file_count`；旧数据为空时懒计算并回写，计算失败返回 `null`，不阻断列表。

其他模块如果要引用数据集参与训练，应优先使用 `versionId`。

V2 数据集列表：

```text
GET /api/v2/datasets
```

V2 `pageSize` 默认 `20`、最大 `200`，`current` 优先于 `page`。稳定字段如下：

| 字段 | 说明 |
| --- | --- |
| `datasetId` | 数据集资产 ID |
| `name` / `type` | 数据集展示名称和类型 |
| `currentVersion` | 当前 READY 摘要：`versionId`、`versionLabel`、`versionNo`、`status` |
| `currentVersionFileCount` / `fileCount` | 当前 READY 文件数；无当前版本或计数不可用时为 `null` |
| `displayStatus` | `EMPTY`、`READY`、`EDITING`、`IMPORTING`、`IMPORT_FAILED` 或 `IMPORT_PARTIAL` |
| `hasDraft` | 是否存在活动 DRAFT |
| `workspaceId` / `workspaceRevision` | 活动版本工作区 ID 和并发 revision；没有工作区时为 `null` |
| `importProgress` | 最新导入进度 |
| `publishReadiness` | `{canPublish,evaluatedRevision,blockers[]}`；没有工作区时为 `null` |
| `editability` | 是否能从当前 READY 派生工作区及稳定 blocker |
| `availableActions` | `VIEW`、`PREVIEW`、`CREATE_WORKSPACE`、`OPEN_WORKSPACE`、`ADD_DATA`、`PUBLISH` 的可用子集 |
| `userError` | 导入失败时的结构化用户错误 |

V2 数据集列表不返回 `storagePath`、`ownerUserId`、`currentVersionId`、`latestDraftVersionId`、MinIO objectName 或 ZIP offset。

V2 数据集版本工作区的稳定路径为：

| 能力 | 路径 |
| --- | --- |
| 标签分配提示 | `GET /api/v2/datasets/{datasetId}/version-allocation?versionLabel=...` |
| 创建或继续 | `POST /api/v2/datasets/{datasetId}/workspaces`；可选请求体为 `{"baseVersionId":"dataset-ver-v1","versionLabel":"1.0.3"}` |
| 详情、元数据 Merge Patch、放弃 | `GET/PATCH/DELETE /api/v2/dataset-workspaces/{workspaceId}` |
| readiness、发布 | `GET .../{workspaceId}/readiness`、`POST .../{workspaceId}/publish` |
| 样本 CRUD | `GET/POST .../{workspaceId}/samples`、`GET/PATCH/DELETE .../samples/{sampleId}`、`POST .../restore` |
| 数据组件 CRUD | `POST .../samples/{sampleId}/data`、`GET/PATCH/DELETE .../data/{dataId}`、`PUT .../content`、`POST .../restore` |
| 标注组件 CRUD | `POST .../samples/{sampleId}/annotations`、`GET/PATCH/DELETE .../annotations/{annotationId}`、`PUT .../content`、`POST .../restore` |
| 组件文件读取 | 数据/标注资源路径下的 `GET .../preview` 与 `GET .../download` |
| 大文件或二进制组件 | `POST .../{workspaceId}/file-uploads` |
| 追加 ZIP | `POST .../{workspaceId}/package-uploads` |
| 分片、轮询、完成、取消 | `/api/v2/dataset-uploads/{uploadId}` 下的 `chunks`、`GET`、`complete`、`cancel` |

首次数据集上传在合并、同步内容校验或版本确认失败后进入可重试的 `FAILED`，V2 展示为
`UPLOAD_FAILED`。会话保留原 `uploadId` 和分片；原 ID 可以再次 complete，也可以先
替换分片，替换成功后回到 `UPLOADING`。相同文件指纹 init 会恢复
`UPLOADING/FAILED` 会话。V2 complete 仍返回
`422 / DATASET_UPLOAD_NOT_COMPLETABLE`，`details.reasonCode` 与后续 GET 的
`userError.errorCode` 一致，只可能为 `INVALID_DATASET_CONTENT`、
`DATASET_UPLOAD_STORAGE_FAILED` 或 `DATASET_UPLOAD_FINALIZATION_FAILED`。错误详情只包含
安全阶段，不返回对象路径、SQL、堆栈或底层异常。缺少分片保持 `UPLOADING`；该语义
不扩展到工作区组件和 APPEND 上传。

创建请求无请求体、`{}` 或 JSON `null` 时，新建工作区默认从资产当前 READY 派生。
显式 `baseVersionId` 时，所选版本必须属于该资产、未删除且为 READY；工作区
`baseVersion`、`parentVersionId` 和物化内容都来自该版本，不会静默改用当前版本。
服务端同时在内部记录创建事务锁定时的 `currentVersionId` 作为 head 快照。

后端在资产行锁内分配下一 `versionNo` 并生成 `v{nextVersionNo}`。READY、DEPRECATED、
ARCHIVED 等普通软删除历史仍参与分配和占用；只有软删除且状态为 `ABANDONED` 的工作区
审计墓碑被忽略。默认标签已被其他保留记录占用时继续向后寻找。显式 `versionLabel`
会 trim，长度必须为 1–64，空白或超长返回 `400 / INVALID_VERSION_LABEL`。标签在创建
事务内首次保存到 `version/versionLabel`，不需要 Legacy PUT。已有活动工作区时，省略
基线或显式传相同基线可幂等继续；显式传不同基线返回
`409 / WORKSPACE_BASE_CONFLICT`，`details` 包含
`workspaceId/activeBaseVersionId/requestedBaseVersionId`。无标签或同标签重试幂等返回
原工作区，不同标签仍返回 `409 / DATASET_VERSION_LABEL_CONFLICT`。

基线校验错误固定为：空白 `baseVersionId` 返回
`400 / INVALID_BASE_VERSION_ID`；不存在、跨资产、已删除或无权访问返回
`404 / DATASET_NOT_FOUND`；同资产但不是 READY 返回
`422 / BASE_VERSION_NOT_READY`。

分配提示返回
`nextVersionNo/defaultVersionLabel/requestedVersionLabel/requestedVersionLabelAvailable/unavailableReason`。请求标签不可用时，原因仅为
`ACTIVE_VERSION_EXISTS` 或 `DELETED_VERSION_RESERVED`，不暴露已删除版本 ID；未传查询标签时只提供下一内部序号和默认标签。该结果仅用于提示，POST 会重新原子校验。普通软删除版本永久占用标签和 `versionNo`；放弃工作区的 ABANDONED 墓碑同时释放原标签和内部序号，可由后续工作区复用。

所有标签冲突的 409 `details` 都包含
`reasonCode/requestedVersionLabel/nextVersionNo/defaultVersionLabel`；活动工作区标签不一致时再包含
`workspaceId/currentVersionLabel`。并发写入只把
`uk_dataset_version_asset_version` 冲突映射为该错误，其他数据库错误不作转换。

`dataset-edit-sessions` V2 别名不再保留。Legacy 样本工作区接口暂时保留 `ApiResponse` 并标记 deprecated，新 V2 前端不得调用。

工作区 DTO 固定返回 `workspaceId/datasetId/baseVersion/targetVersion/status/workspaceRevision/sampleCount/activeOperation/publishReadiness/availableActions/userError`。`activeOperation` 固定为 `{type,id,status,progress}`：`type=UPLOAD` 时 `id` 为 `uploadId`，`type=IMPORT` 时 `id` 为 `importJobId`，且只在对应任务仍处于活动状态时返回；没有活动任务时为 `null`。最新 ImportJob 为 `FAILED` 或 `PARTIAL` 时，`userError` 返回结构化用户错误，但不包含任务 ID。所有工作区写请求都携带 `expectedWorkspaceRevision`；过期返回 `409 / WORKSPACE_REVISION_CONFLICT` 和 expected/current revision。活动上传或导入期间除读取、轮询、取消和放弃外返回 `409 / WORKSPACE_BUSY`。

`PATCH` 使用 `application/merge-patch+json`。样本只允许 `tags/metadata`；数据只允许 `dataType/sensor/channel/seq/format/fileName/contentType/metadata`；标注只允许 `sampleDataId/annotationType/format/fileName/contentType/metadata`；工作区只允许 `description/changeLog/cvTaskType/annotationFormat`。存储 ID、大小、校验和、包 ID 和 ZIP offset 均不可写。

小文本组件限制为 1 MiB 和 `.txt/.json/.jsonl/.xml/.csv/.yaml/.yml`，执行 UTF-8、NUL、MIME、format 和安全语法校验。大文件创建/替换生成不可变 RAW `OVERLAY` 对象；继承 ZIP 继续保持不可变，读取服务按 `storageKind=ZIP|RAW` 选择 ZIP entry 或 RAW object。有效标注仍引用数据组件时，删除数据返回 `409 / RESOURCE_IN_USE`。

`publishReadiness` 固定为 `{canPublish,evaluatedRevision,blockers[]}`。列表、详情和发布使用同一套 readiness 规则，统一覆盖样本、上传/导入、包关系、重复标识、数据—标注关联、文件描述、版本元数据和版本谱系。所选 `parentVersionId` 可以是历史 READY；发布要求资产当前 head 仍等于创建工作区时记录的 head 快照，之后发生任何 current 漂移均返回 `409 / BASE_VERSION_STALE`，不得把工作区改接到新 current。列表通过数据库存在性聚合计算等价的 `canPublish`，每种 blocker code 至多返回一项，`resourceType/resourceId` 可以为 `null`；工作区详情和发布复检可返回具体资源 blocker。其他业务阻塞返回 `422 / DATASET_NOT_PUBLISHABLE`。无状态变化时，同 revision 的 `canPublish=true` 保证不会因同一业务规则返回 422。

发布结果固定为：

```json
{
  "datasetId": "dataset-xxx",
  "currentVersion": {
    "versionId": "dataset-version-xxx",
    "versionLabel": "1.0.3",
    "versionNo": 2,
    "status": "READY"
  },
  "publishedAt": "2026-07-23T00:00:00Z"
}
```

发布响应中的 `versionLabel` 来自持久化版本标签，不根据 `versionNo` 重新拼接。

放弃工作区幂等转入 `ABANDONED` 并软删除为内部审计墓碑，上传转 `DISCARDED`，导入转 `SUPERSEDED`，仅清理工作区独占对象。放弃版本从版本列表、版本详情和工作区读取中隐藏，同时释放原 `versionLabel/versionNo`；使用原 `workspaceId` 重复 DELETE 仍返回原 `ABANDONED` 结果。墓碑和已有审计行继续保留且不进入生命周期物理清理。历史非 ZIP READY 来源可唯一推导时只在新 DRAFT 懒规范化为 RAW；无法唯一推导时返回 `DATASET_WORKSPACE_SOURCE_AMBIGUOUS`，并由列表 `editability.blockers` 提前展示。

V2 上传、状态查询和重试 DTO 会返回 `importJobId`，用于导入失败后的 V2 重试。工作区 DTO 不提供失败任务的持久重试句柄：

| DTO / 接口 | 字段 | 说明 |
| --- | --- | --- |
| `V2DatasetUploadDto`：`POST /api/v2/dataset-uploads/{uploadId}/complete`、`GET /api/v2/dataset-uploads/{uploadId}` | `importJobId` | 当前上传触发的 ImportJob 重试句柄；普通非导入上传或尚未创建任务时为 `null` |
| `V2DatasetWorkspaceDto`：`GET /api/v2/dataset-workspaces/{workspaceId}` | `activeOperation.id` / `userError` | 仅活动导入的 `activeOperation.id` 是 `importJobId`；进入 `FAILED`/`PARTIAL` 后 `activeOperation` 为 `null`，`userError` 不包含任务 ID |
| `V2ImportJobStatusDto`：`GET /api/v2/import-jobs/{importJobId}`、`POST /api/v2/import-jobs/{importJobId}/retry` | `importJobId` | 被查询或重试的 ImportJob 句柄；重试请求体必须包含 `mode` 和 `expectedWorkspaceRevision`，`PARTIAL` 仅返回 `retryModes=["INCREMENTAL"]` |

调用方如果需要在失败后重试，必须保留上传完成或上传轮询响应中的 `importJobId`；V2 数据集列表和失败后的工作区详情都不能重新发现该句柄。调用方只能使用自己有权限访问的 `importJobId` 调用 V2 重试端点；无权限或不存在按 404 处理。`importJobId` 不允许被当作数据库外键、存储路径或跨资源枚举入口。

前端迁移顺序：用 `workspaceId/workspaceRevision/publishReadiness` 取代 `editSessionId/canPublish`；需要标签提示时调用 `version-allocation`；把用户标签直接放入创建工作区请求，不再通过 Legacy PUT 二次改名；每次命令使用上一响应的 revision；冲突后重新拉取；仅以 blockers 展示发布原因；发布后使用 `currentVersion.versionId` 并展示实际 `currentVersion.versionLabel`。界面文案为“创建/继续版本工作区”，不是“编辑当前版本”。

workspace 审计日志的 V2 查询路径为：

```http
GET /api/v2/dataset-versions/{datasetVersionId}/workspace/audit-logs?page=1&pageSize=20
```

该路径只面向模块二内部工作区回溯，返回字段不作为训练执行模块、推理模块或前端以外新模块的稳定契约。训练侧仍应只依赖 READY `datasetVersionId`、consumer manifest 以及固定 preview/download 链接。

## 7. 代码资产与训练实验边界

### 7.1 V2 代码资产管理

代码资产的稳定生命周期为：

```text
CodeAsset -> CodeWorkspace（可变草稿） -> CodeVersion（不可变版本）
          -> ValidationRun（SHA 绑定校验证据）
          -> RiskAssessment/RiskFinding（风险分流证据）
          -> ApprovalRecord（系统策略或管理员审批证据）
```

V2 代码资源按以下稳定路径分组：

| 资源 | 稳定路径与能力 |
| --- | --- |
| 资产 | `POST/GET /api/v2/code-assets`、`POST /api/v2/code-assets/import`、`GET/PATCH/DELETE /api/v2/code-assets/{assetId}`、版本列表和工作区列表/创建 |
| 工作区 | `GET /api/v2/code-workspaces/{workspaceId}`、目录树、文件 metadata/内容/下载/写入/移动/删除、`validate`、`publish`、`abandon` |
| 版本 | `GET /api/v2/code-versions/{versionId}`、目录树、文件内容/下载、完整 ZIP、`consumer-manifest`、owner 风险详情、`validate`、`approval`、`artifact-upgrade`、`deprecate`、`archive` |
| 管理员管理 | `/api/v2/admin/code-assets` 分页资产管理、`/api/v2/admin/code-workspaces/**` 工作区维护、`/api/v2/admin/code-versions/**` 版本读取与生命周期 |
| 管理员审核 | `/api/v2/admin/code-review-tasks` 的列表、详情、目录树、内容、findings 和 `rescan` |

资产、版本和工作区的普通读取与编辑都是 owner-only；跨用户访问按 `404 / CODE_ASSET_NOT_FOUND` 处理。全部 `/api/v2/admin/**` 路径在参数绑定和资源查询前检查管理员身份，非管理员统一返回 `403 / CODE_APPROVAL_FORBIDDEN`。管理员管理面可维护普通用户资产及其唯一 OPEN 工作区，但不能转移 owner；新版本及对象仍写入原 owner 的 `users/{owner}/codes/**` 命名空间。已发布版本不可修改，继续编辑必须创建基于版本的工作区。工作区写、移动、删除、校验、发布和放弃必须使用 `expectedWorkspaceRevision` 做 CAS；已有文件的修改、移动和删除还必须携带匹配的 `expectedContentHash`，新建文件不传内容哈希，冲突为 `409 / CODE_ASSET_CONFLICT`。

代码资产名称在去除首尾空白后，按同一 owner、未删除资产范围内不区分大小写唯一；不同 owner 可以同名，同一资产的多个版本不受此规则影响。空资产创建、V2/Legacy ZIP 导入及 owner/管理员重命名都执行相同校验，已软删除资产释放名称。V2 预检和数据库并发冲突统一返回 `409 / CODE_ASSET_NAME_CONFLICT`；Legacy `/api/code/upload` 返回 HTTP `409` 并保留 `ApiResponse` JSON 结构。导入预检发生在对象上传前，上传后并发冲突继续执行补偿清理。

在线文件仅支持 `.py`、`.json`、`.jsonl`、`.yaml`、`.yml`、`.txt`、`.md`。后端返回 `python`、`json`、`yaml`、`markdown` 或 `plaintext` 形式的 `languageId`，前端据此选择 Monaco Editor 高亮器；后端不返回高亮 HTML，也不执行源码。在线内容必须是 UTF-8 且原始字节不超过 `1,048,576` bytes；大文件仍可列出和下载，但内容接口返回 `413 / CODE_CONTENT_TOO_LARGE`。`contentHash` 针对原始字节计算，不规范化 BOM 或换行。`GET .../files/metadata?path=...` 不受在线内容上限限制，返回原始内容哈希和 `workspaceRevision`，使大文件保持不可预览/编辑但仍可按 CAS 安全删除；删除在草稿中写 `DELETE` 墓碑，不修改基础 ZIP。

ZIP 条目名严格按 UTF-8 解码。Windows ZIP 的反斜杠先规范化为 `/`，最终不可变 ZIP 也只保存 `/`；规范化后仍拒绝控制字符、绝对路径、盘符、UNC、`.`、`..`、空段、超长路径、符号链接、加密 ZIP 和压缩限制违规项。两个原始名称规范化成同一路径时必须以 `422 / DUPLICATE_PATH` 拒绝，不能覆盖；文件/目录冲突同样拒绝。路径规范化不修改文件内容、BOM 或换行。

代码版本生命周期状态为 `READY / DEPRECATED / ARCHIVED`，校验状态为 `NOT_RUN / PASSED / FAILED`，审批状态为 `PENDING / APPROVED / REJECTED / REVOKED`。风险任务状态为 `QUEUED / RUNNING / COMPLETED / ERROR / CANCELED`，风险等级为 `LOW / MEDIUM / HIGH / UNKNOWN`，分流为 `AUTO_APPROVE / MANUAL_REVIEW / BLOCK / DIRECT_PASS`。

训练代码审核模式通过超级管理员接口 `GET /api/system/config/get` 和 `POST /api/system/config/update` 提供给前端。请求字段 `trainingCodeReviewMode` 只允许 `DIRECT_PASS`、`STANDARD_REVIEW` 或 `MANUAL_ONLY`，数据库默认 `STANDARD_REVIEW`。`DIRECT_PASS` 对新的成功校验证据跳过静态风险扫描和管理员审核，写入 `UNKNOWN + DIRECT_PASS` 风险证据及 `decisionSource=SYSTEM_CONFIG` 的批准记录；`STANDARD_REVIEW` 保持现有 `CODE_ASSET_RISK_MODE` 分流；`MANUAL_ONLY` 不触发自动扫描判定，写入 `UNKNOWN + MANUAL_REVIEW` 证据并进入人工待审，管理员仍可显式重新扫描获取辅助证据。直通模式仍执行 ZIP 结构、固定入口、实际对象名、SHA-256、长度以及消费时重新读取核对。模式变更不批量处理既有 `PENDING` 版本，也不自动撤销此前的批准；已排队或运行中的扫描可以收尾，但切到 `MANUAL_ONLY` 后不得再自动批准、拒绝或撤销既有批准。配置变更会写入统一权限变更审计。

V2 `/validate` 和 Legacy `/training-check` 都核对实际对象名、SHA-256 和长度。当前策略及全部通过证据完全一致时返回 `reused=true`，不新增校验/风险/审计证据，也不改变既有审批状态；只有制品或策略证据确实变化时才产生新 validation run，并使旧审批绑定回到 `PENDING`。因此前端应在已经批准且策略未变化时隐藏普通准入校验按钮，或至少二次确认。管理员审核中心的 `/rescan` 不等于普通校验：它显式生成新的风险证据，不应暴露给普通上传者。

风险扫描是有界的静态分析，不执行用户代码。Python 自动准入采用保守策略：只有安全导入白名单内且语法落在扫描器置信边界内的代码才有资格判为 `LOW`；高能力导入、未知导入以及超出语法置信边界的形式统一进入 `MANUAL_REVIEW`，绝不判为 `LOW`。finding 只暴露规则 ID、严重度、类别、文件路径、行号和安全描述，不包含源码片段、密钥实际值、MinIO 路径、下载地址或 Token。系统审核模式为 `STANDARD_REVIEW` 时，部署模式 `CODE_ASSET_RISK_MODE` 当前默认 `ENFORCE`：`MANUAL_ONLY` 统一生成 `UNKNOWN + MANUAL_REVIEW`；`SHADOW` 执行扫描并记录但不自动决策；`ENFORCE` 中完整低风险证据可由 `AUTO_POLICY` 自动批准，中高风险转人工，阻断项在发布风险摘要的同一事务内先使旧 `APPROVED` 失效，再自动拒绝，扫描异常不得自动放行。“LOW”只表示当前策略未发现已知信号，不代表代码绝对安全。

owner 通过 `GET /api/v2/code-versions/{versionId}/risk-assessment` 查看当前 SHA 绑定的风险证据和脱敏 findings。管理员审核列表默认查询 `PENDING`，支持按审批状态、风险等级、owner、关键字、提交时间筛选，以及提交时间、版本、风险等级、owner 排序；详情、目录树和内容预览均不返回存储信息，内容仍受 1 MiB 上限。

管理员资产管理使用独立命名空间，不改变普通 owner 契约：

- `GET /api/v2/admin/code-assets` 从 0 开始分页，支持 owner、关键字和
  `trainingProfile` 筛选，默认更新时间倒序；响应包含 `ownerUserId`，不包含存储字段。
- `GET/PATCH/DELETE /api/v2/admin/code-assets/{assetId}` 复用资产 revision CAS、
  OPEN 工作区检查、引用检查和软删除规则。
- `/api/v2/admin/code-workspaces/**` 镜像普通工作区文件维护、校验、发布和放弃能力；
  `/api/v2/admin/code-versions/**` 提供跨 owner 版本读取、下载、校验、弃用和归档。
- 管理员与 owner 操作同一个 OPEN 工作区；资产、工作区、发布版本和对象路径始终保留
  原 `ownerUserId`。管理员动作以真实管理员用户 ID 和 `actorType=ADMIN` 写入 append-only
  审计。
- 管理员管理面不授予训练消费权。Legacy 训练代码列表和
  `CodeArtifactResolver` 继续要求当前登录用户就是资产 owner；管理员不能使用他人的
  `codeVersionId` 发起训练。

人工 `APPROVE/REJECT` 请求必须携带 `expectedValidationRunId`、`expectedRiskAssessmentId`、`expectedArtifactSha256`、`expectedPolicyVersion`；证据变化返回 `409 / APPROVAL_EVIDENCE_STALE` 或 `RISK_EVIDENCE_STALE`。批准要求版本 `READY + PASSED`、风险评估已完成且非 `BLOCK`，并再次核对实际对象 SHA 和长度。`APPROVE` 的 reason 可选，提供时在清除控制字符、折叠换行/制表符和连续空白、trim 后最多保存 1024 个字符；`REJECT/REVOKE` 的 reason 必填并使用相同清洗规则。相同证据的重复批准幂等返回首次记录，不覆盖首次 reason；证据变化或冲突决策返回 `409`。审批记录区分 `decisionSource=ADMIN/AUTO_POLICY/LEGACY/SYSTEM_CONFIG`，响应不暴露 reviewer 身份。

创建资产或在首个未删除代码版本产生前修改 `trainingProfile` 时，非空值必须是
`TrainingPlanRegistry` 当前启用的方案。未知或已禁用值返回
`422 / CODE_VALIDATION_FAILED`，`details.reasonCode=UNSUPPORTED_TRAINING_PROFILE`，
且不落库。当前启用值为 `image_text_consistency_fusion_logreg` 和
`yolo_object_detection`。

资产 `trainingProfile` 在还没有未删除代码版本时可改；首个版本产生后，改为不同值或置空返回 `409`，`details.reasonCode=TRAINING_PROFILE_IMMUTABLE`。每个版本保存发布时的 profile 快照，`consumer-manifest` 从不可变版本返回 `assetId`、`versionId`、用途、运行时、入口脚本、训练类型、`trainingProfile`、`artifactSha256`、校验策略和审批证据，并追加 `approvalSource/riskAssessmentId/riskLevel/riskPolicyVersion`；历史数据的新增字段允许为空。它不返回 `storagePath`、MinIO 参数或下载 URL。

内部 `CodeArtifactResolver` 要求 `READY + PASSED + APPROVED`，核对校验、风险与审批证据绑定，并在每次解析时实际读取对象，核对 objectName、SHA-256 和长度；数据库引用、实际 SHA 或实际长度不一致时拒绝消费。通用 `/api/files` 写入和删除接口也不能操作归一化后的 `users/{owner}/codes` 及其后代，防止绕过版本服务修改或删除制品。

`DELETE /api/v2/code-assets/{assetId}?expectedAssetRevision=...` 只允许 owner；管理员使用 `/api/v2/admin/code-assets/{assetId}` 执行相同的资产 revision CAS 和软删除规则。存在打开工作区返回 `OPEN_WORKSPACE_EXISTS`，存在其他模块持久化引用返回 `CODE_ASSET_IN_USE`。成功只软删除资产，保留版本、ZIP、校验、风险、审批和审计证据；删除后禁止新的解析消费。删除与发布在资产行锁上串行化，已发布版本不提供物理删除接口。

`POST /api/v2/code-versions/{versionId}/artifact-upgrade` 是管理员对单个历史版本的恢复操作：它只接受精确符合旧规则的来源路径，复制到唯一 canonical 对象，核对实际字节、SHA 和长度后原子更新证据，并对失败上传做补偿清理。恢复成功会重新校验并先保持 `PENDING`：系统审核模式为 `DIRECT_PASS` 时写入直通证据后批准；`STANDARD_REVIEW` 时再按当前 `MANUAL_ONLY/SHADOW/ENFORCE` 风险模式分流。响应不暴露新旧存储路径。已经 canonical 且证据一致的重试是幂等操作。

### 7.2 当前训练兼容边界

本节描述现有训练调用兼容行为，不表示本次代码资产改动修改了训练执行器。训练任务和训练实验不是纯元数据管理：创建实验首个版本或实验新版本后，当前代码会在事务提交后异步启动 `TrainingExecutorRouter`。不带 `trainingProfile` 的兼容任务走本地 runner；带 `trainingProfile` 的任务走 K8s profile 训练路径，要求训练代码版本已准入。

当前本地 runner 只解析数据集 zip 中的图片和路径包含 `labels/` 的 YOLO `.txt` 标签，并写回 `running`、`success` 或 `failed` 结果。模型/数据集类型校验通过，不代表所有数据集类型或标注格式都能被当前本地 runner 成功训练。

当前启用 profile 为 `image_text_consistency_fusion_logreg` 和
`yolo_object_detection`。profile 路径会校验基础模型权重版本、训练数据集版本、训练
代码版本和冻结后的资产 `trainingProfile` 的匹配关系；Worker 使用固定 profile 命令，
`hyperParams` 只作为记录和传递字段，不能覆盖固定训练命令。模块二面向新消费者的稳定
契约仍以代码版本快照和 `consumer-manifest` 为准。

兼容路径：

```text
/api/task
/api/experiments
```

Legacy 代码资产路径：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/code/upload` | 上传训练代码 ZIP，生成 `codeAssetId` 和 `codeVersionId` |
| `GET` | `/api/code/version/list` | 查询当前用户可用于训练的 `READY` + `APPROVED` 代码版本 |
| `POST` | `/api/code/version/{codeVersionId}/approve` | 管理员独立批准当前 SHA 已通过校验的版本 |
| `GET` | `/api/code/version/{codeVersionId}/training-check?trainingProfile=...` | 校验或幂等复用证据；真正的新证据进入当前系统审核模式分流 |

代码包准入包含结构校验和当前静态风险策略分流，但它不执行用户代码，也不构成完整安全审计。Python 只有通过安全导入白名单和语法置信边界检查才可能判为 `LOW`；高能力或未知导入、超出置信边界的语法必须人工复核。“LOW”只表示当前规则未发现已知信号；是否需要人工审批由部署风险模式和风险结论共同决定。

### 7.3 训练任务接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/task/create` | 创建训练任务，生成实验首个版本 |
| `GET` | `/api/task/list` | 查询当前用户可见训练任务 |
| `GET` | `/api/task/detail?id={id}` | 查询训练任务详情 |
| `POST` | `/api/task/stop?id={id}` | 停止训练任务 |
| `POST` | `/api/task/result?id={id}` | 回写训练结果，`id` 可为训练版本 ID 或实验 ID |
| `DELETE` | `/api/task/delete?id={id}` | 删除训练实验 |

创建训练任务请求稳定字段：

| 字段 | 说明 |
| --- | --- |
| `name` | 实验名称，可选 |
| `modelVersionId` | 兼容模型版本 ID；未传 `baseModelVersionId` 时作为基础模型权重版本使用 |
| `baseModelVersionId` | 基础模型权重版本 ID；带 `trainingProfile` 时必填，可与 `modelVersionId` 二选一，二者同时传入时必须一致 |
| `datasetVersionId` | 数据集版本 ID，必填 |
| `codeVersionId` | 训练代码版本 ID，必填 |
| `trainingProfile` | 训练方案 ID；不传走 legacy 本地训练路径，传入时进入 profile/K8s 路径 |
| `hyperParams` / `params` | 超参数 JSON；legacy 路径必填，profile 路径不传时按 `{}` 记录 |
| `remark` | 备注，可选 |

创建时后端会校验数据集版本存在且调用方可访问，数据集版本为 `READY` 且具备
`storagePath`。legacy 和 profile 两条路径都会在启动执行器前对基础模型执行 READY
消费准入：核对 MinIO 对象存在、实际大小、完整 SHA-256 和 ZIP 结构，成功时原子回填
历史空摘要；确定性损坏会将版本降为 `DRAFT` 并清除当前指针，临时存储故障只拒绝本次
创建。不带 `trainingProfile` 的 legacy 路径还会校验模型类型与数据集类型一致，
`codeVersionId` 仍只做非空校验。带 `trainingProfile` 的路径会额外校验代码版本存在且
为 `READY` + `APPROVED`、冻结后的代码资产 `trainingProfile` 与请求一致，并校验
数据集类型符合该 profile 要求；新消费者应以代码版本快照为准。

返回稳定字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 训练实验版本 ID |
| `experimentId` | 实验 ID |
| `versionNo` | 实验版本号 |
| `name` | 实验名称 |
| `modelVersionId` | 模型版本 ID |
| `baseModelVersionId` | 基础模型权重版本 ID；当前与 `modelVersionId` 相同 |
| `codeVersionId` | 代码版本 ID |
| `trainingProfile` | 训练方案 ID；legacy 任务为空 |
| `datasetVersionId` | 数据集版本 ID |
| `hyperParams` | 超参数 JSON |
| `status` | 训练状态 |
| `progress` | 展示进度 |
| `metrics` | 指标 JSON |
| `runId` | MLflow 或外部执行器运行 ID |
| `logPath` | 日志路径 |
| `outputPath` | 输出路径 |
| `errorMessage` | 失败信息 |
| `startedAt` | 开始时间 |
| `finishedAt` | 结束时间 |
| `remark` | 备注 |
| `ownerUserId` | 归属用户 |
| `createdAt` | 创建时间 |
| `updatedAt` | 更新时间 |

### 7.4 实验版本接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/experiments` | 创建实验，等价于 `/api/task/create` |
| `GET` | `/api/experiments/{experimentId}/versions` | 查询实验版本历史 |
| `GET` | `/api/experiments/{experimentId}/versions/{versionNo}` | 查询指定实验版本 |
| `POST` | `/api/experiments/{experimentId}/versions` | 基于已有实验创建新版本 |
| `PUT` | `/api/experiments/{experimentId}/versions/{versionNo}/hyper-parameters` | 更新超参数 |
| `PUT` | `/api/experiments/{experimentId}/versions/{versionNo}/result` | 按实验 ID 和版本号精确回写训练结果 |

训练状态约定：

| 状态 | 说明 |
| --- | --- |
| `pending` | 已创建，等待训练 |
| `queued` | 预留，进入队列 |
| `running` | 训练中 |
| `success` | 训练成功 |
| `failed` | 训练失败 |
| `stopped` | 已停止 |

创建接口先返回 `pending`；当前本地 runner 会异步写入 `running`、`success` 或 `failed`；停止接口写入 `stopped`；结果回写接口允许上述状态集合。

## 8. 文件边界

基础路径：

```text
/api/files
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health` | MinIO 健康检查，仍受 `/api/**` 登录拦截 |
| `POST` | `/upload` | 通用文件上传 |
| `GET` | `/download?objectName=...` | 下载文件 |
| `DELETE` | `/delete?objectName=...` | 删除文件 |

通用文件接口主要用于调试或辅助能力。业务模块优先通过模型、数据集或代码版本 ID 引用文件。

普通用户上传相对 `objectName` 时，后端会返回归一化后的对象名：

```text
users/{当前用户ID}/files/{objectName}
```

如果请求已经传入 `users/{当前用户ID}/...` 前缀，则保持该前缀下路径。对象名会拒绝控制字符、`.`、`..`、绝对路径等不安全片段。

普通用户只能访问：

```text
users/{当前用户ID}/...
```

管理员通常可访问全部对象，但所有角色都不能通过通用上传或删除接口操作归一化后的 `users/{owner}/codes` 或其任意后代；该命名空间由代码资产服务独占管理。`GET /download` 保持只读兼容，普通 `users/{owner}/files/**` 行为不变。

### 8.1 MinIO 启动初始化与删除清理

启动阶段会通过 `ApplicationRunner` 确保配置 bucket 存在。该流程默认最多尝试 30 次，初始退避 1000 ms，指数退避上限 30 秒。网络超时、MinIO 暂未就绪等运行期异常会重试；`InvalidBucketName`、`InvalidAccessKeyId`、`SignatureDoesNotMatch`、`AccessDenied` 会快速失败。bucket 参数构造在重试循环前完成，因此非法 bucket 名称会在循环前失败，不会进入退避重试。

删除接口、模型版本删除、数据集版本删除和失败 DRAFT 清理只把对象加入异步 MinIO 删除任务；业务接口返回成功不等于物理对象已删除。删除任务当前语义：

- 目标对象已不存在时按成功处理。
- `PROCESSING` 超过 30 分钟会重置为 `PENDING`。
- 默认单轮最多 5 次尝试；FAILED 后最多 2 次失败重置，每次重置清空单轮 `retryCount` 并递增跨轮 `failedResetCount`，因此默认最多 15 次删除尝试。
- 超过有界重试后任务保持 `FAILED`，需要运维介入或重新创建删除任务。
- 删除任务表、任务 ID、调度锁和具体执行线程池不作为外部集成契约。

## 9. 对其他模块的集成规则

### 9.1 模块一

模块一提供：

```text
token
userId
roleId
```

模块二不复制用户表、不维护独立角色体系。

### 9.2 前端

前端应：

- 所有模块二业务请求都带 `Authorization: Bearer <token>`。
- Legacy 接口按 `ApiResponse.success` 判断；V2 代码接口按真实 HTTP 状态和 `V2ErrorResponse.errorCode/details.reasonCode` 处理。
- 训练创建时提交 `modelVersionId` 和 `datasetVersionId`。
- 代码编辑器保存 `codeAssetId`、`workspaceId`、`codeVersionId`、workspace revision 和内容哈希；以 `languageId` 配置 Monaco Editor，不要求后端生成高亮 HTML。
- 大文件删除先调用 workspace `files/metadata` 获取 `contentHash`，不要为删除而调用会返回 `413` 的内容接口。
- 已批准且校验策略未变化时隐藏普通 `validate`，或至少二次确认；等价校验以后端 `reused=true` 为准，不应在前端改成待审核。
- 管理员 `rescan` 只出现在审核中心；它会显式创建新的风险证据，不能当作普通上传页面的准入按钮。
- 普通用户根据 `riskStatus/riskLevel/reviewDisposition` 展示扫描中、自动通过、待人工、阻断或扫描异常；管理员统一从审核队列处理待审核项。
- 不直接拼接或持久依赖 MinIO 对象路径。

### 9.3 训练执行模块

训练执行模块应以训练实验为入口：

```text
experimentId
versionNo
modelVersionId
datasetVersionId
codeVersionId
hyperParams
ownerUserId
```

训练执行模块不应绕过模块二直接扫描 MinIO。需要文件时，应先通过模型版本 ID、数据集版本 ID 获取元数据，再由后端内部服务读取对象。代码制品应按 `codeVersionId` 使用 `consumer-manifest` 和内部解析器；不得依赖 JPA 实体或 MinIO 路径。解析器只有在版本状态和校验/审批证据满足调用要求，且实际对象名、SHA-256、长度均与版本证据一致时才交付制品。本条是模块二提供的消费边界，不声明现有训练执行器已完成统一解析器改造。

### 9.3.1 数据集交付与训练适配边界

模块二向训练执行模块交付的是 READY `datasetVersionId` 和只读 consumer manifest。

模块二不承诺当前训练 runner 能解析所有数据集类型或所有标注格式。当前 `MULTIMODAL` 数据集只能作为数据资产交付给训练团队，不能直接调用 `/api/task/create` 进入现有训练创建流程。

训练团队不得依赖 `storagePath`、MinIO objectName、ZIP offset 或数据库表结构。若训练侧要支持 `MULTIMODAL`，应由训练侧基于 READY `datasetVersionId` 调用 consumer manifest，并自行完成 batch 组装、数据选择和模型/数据集适配策略。

训练版本 DTO 当前已返回执行结果字段：

```text
runId
metrics
logPath
outputPath
startedAt
finishedAt
errorMessage
```

### 9.4 推理模块

推理模块应引用：

```text
modelVersionId
```

不要直接引用：

```text
storagePath
MinIO objectName
```

创建推理任务时，后端会在调度前对 `modelVersionId` 执行与训练相同的 READY 消费
准入：核对对象存在、实际大小、完整 SHA-256 和 ZIP 结构。历史空摘要成功后原子回填；
确定性损坏会降级版本并清除当前指针，临时 MinIO 故障不会改变版本状态。

这样后续模型存储迁移、路径调整或权限规则变化时，不影响推理模块契约。

## 10. 兼容性规则

模块二 v1 契约遵循：

- 可以新增响应字段。
- 不删除或重命名本文档标记为稳定的字段。
- 不改变稳定 ID 的含义。
- 不改变普通用户只能访问自己资源的规则；管理员权限按资源显式定义，普通 V2 代码接口仍为 owner-only，跨 owner 管理、审核、审批和历史制品恢复仅通过专用管理员能力开放。
- 不要求其他模块直接访问 MinIO。
- 若需要破坏性变更，应新增接口版本或新增兼容字段，保留旧字段一段时间。

历史代码版本的旧批准状态不等价于当前 SHA 的有效审批证据。历史恢复必须按“单版本 `artifact-upgrade` -> 重新校验 -> 当前系统审核模式分流”执行；`DIRECT_PASS` 生成显式系统直通证据后批准，`STANDARD_REVIEW` 再按 `MANUAL_ONLY/SHADOW/ENFORCE` 处理。

## 11. 当前内部实现，不作为外部契约

### 11.1 Legacy 与 V2 调用边界

数据资产公开响应不再返回 `storagePath`：包括 Legacy 模型上传、模型版本 CRUD、
数据集列表/上传/版本 CRUD 和训练代码上传，以及全部 V2 数据资产 DTO。实体字段仍仅供
后端存储定位、下载、预览、训练和清理使用；客户端必须使用资产/版本/上传任务 ID 与
授权下载接口。推理脚本 `/api/inference/scripts` 保持独立的既有兼容契约，不属于本次
收口范围。`latestDraftVersionId` 等其他 Legacy 展示字段仍可能保留，但不作为新模块的
稳定集成契约。V2 代码 DTO、`consumer-manifest` 和 `artifact-upgrade` 响应永不返回
存储路径。`importJobId` 已在
V2 中重分类为导入状态查询和失败重试句柄，稳定暴露于 V2 上传、状态查询和重试结果；
版本工作区只在导入活动期间通过 `activeOperation.id` 暴露它，失败后不能重新发现。新模块应保留上传响应中的句柄，并使用 `GET /api/v2/import-jobs/{importJobId}` 和请求体携带
mode/revision 的 `POST /api/v2/import-jobs/{importJobId}/retry`，而不是 Legacy retry
路径。

新模块必须优先使用 V2 数据集列表、V2 预览 descriptor 和 consumer manifest。需要数据集文件内容时，通过 consumer manifest 返回的固定 preview/download 链接访问。代码消费者必须优先使用 V2 代码版本 ID、代码 `consumer-manifest` 和内部 `CodeArtifactResolver`，不能把 Legacy `storagePath` 当作契约。

### 11.2 后台调度与分布式锁

ImportJob 恢复、上传会话恢复、MinIO 删除任务、数据集生命周期维护和代码风险扫描均使用数据库分布式锁，锁由同一 owner 在任务结束时条件释放；如果执行实例崩溃，则依赖 `lockedUntil` 过期兜底。启动恢复路径也走对应的加锁方法，避免多实例启动时绕过调度锁。

当前锁的最大持有窗口是内部实现参数：ImportJob 恢复、上传恢复和 MinIO 删除任务为 55 秒，数据集生命周期维护为 55 分钟，代码风险扫描为 10 分钟。外部模块只应观察公开状态字段或消费清单，不应读取或修改 `scheduler_lock`、`minio_delete_task`、风险任务表等内部表。

以下内容属于内部实现细节：

- JPA 实体字段完整结构。
- Repository 方法名称。
- MinIO bucket 名称。
- MinIO objectName 具体拼接规则。
- 上传临时分片对象路径。
- `storagePath` 的长期格式。
- 数据库表名和索引名。

其他模块不应直接依赖这些细节。

workspace 审计日志查询已经提供，但只属于模块二内部回溯能力，不纳入下列跨模块稳定交付边界。

## 12. 当前可交付边界

当前模块二可对外承诺：

```text
模型版本 ID
数据集版本 ID
代码资产 ID、代码工作区 ID、不可变代码版本 ID
READY 数据集消费清单
不含存储路径的代码 consumer manifest
发布时固化的 trainingProfile 与制品 SHA-256/长度证据
与当前 SHA 和策略版本绑定的代码校验、风险分流和系统/管理员审批证据
代码资产 revision CAS 软删除与草稿大文件 metadata/delete CAS
管理员代码审核队列、脱敏 findings、只读预览和显式 rescan
跨 READY DatasetVersion 的 externalId 查询
FAILED ImportJob FULL retry
PARTIAL ImportJob INCREMENTAL retry
V2 importJobId 查询和重试句柄
有界异步 MinIO 删除清理
训练实验 ID
训练实验版本 ID
owner_user_id 资源隔离
模型/训练任务类型 CV/NLP/POINT_CLOUD/ROBOT
数据集任务类型 CV/NLP/POINT_CLOUD/ROBOT/MULTIMODAL
pending/running/success/failed/stopped 训练状态语义
Authorization: Bearer <token> 鉴权方式
Legacy ApiResponse 与 V2 代码 V2ErrorResponse/真实 HTTP 状态
不向新消费者暴露 MinIO 路径、凭据或持久下载地址
```

这就是模块二 v1 的稳定对外边界。
