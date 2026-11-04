# 模块二 API 接口文档

本文档描述模块二当前后端实际提供的 API，范围包括模型资产、模型版本、数据集资产、数据集版本、代码资产、代码工作区、不可变代码版本、上传、训练实验、训练任务和通用文件对象接口。模块一登录、注册、用户管理接口不在本文档范围内；模块二只依赖模块一提供的登录态。

## 1. 通用约定

### 1.1 基础信息

| 项目 | 说明 |
| --- | --- |
| 默认服务地址 | `http://localhost:8080` |
| 请求编码 | UTF-8 |
| JSON 请求头 | `Content-Type: application/json` |
| 文件上传请求头 | `Content-Type: multipart/form-data` |
| 鉴权头 | `Authorization: Bearer <token>` |

当前 `WebConfig` 会拦截全部 `/api/**` 请求，因此包括 `/api/files/health` 在内的模块二接口都需要登录态。普通用户只能访问自己的资源；`roleId` 为 `1` 或 `2` 的管理员通过显式 `/api/v2/admin/**` 管理面跨 owner 管理 V2 训练代码，普通 `/api/v2/code-*` 接口仍保持 owner-only，具体见对应章节。

### 1.2 统一响应格式

大多数接口返回 `ApiResponse<T>`：

```json
{
  "success": true,
  "data": {},
  "errorMessage": null
}
```

失败时 HTTP 状态通常仍为 `200`，业务成功与否以 `success` 字段为准：

```json
{
  "success": false,
  "data": null,
  "errorMessage": "not found or no permission"
}
```

例外：

- `GET /api/files/download` 成功时直接返回文件流；失败时返回 JSON。
- V2 数据集与代码资产接口成功时直接返回 DTO、列表或文件流，不包裹 legacy `ApiResponse`；失败时返回 `V2ErrorResponse` 并使用真实 HTTP 状态，详见第 18 节。
- 未登录时，请求会先被拦截器拦截，HTTP 状态为 `401`，响应体是模块一 `Result` 格式，例如 `{"code":401,"message":"请先登录","data":null}`，不会进入模块二 Controller。
- 图片流、点云流和通用下载接口成功时返回二进制流；失败时返回 JSON，前端不能固定按 JSON 解析这些接口。

### 1.3 枚举

模型和训练模块使用的任务类型 `type`：

| 值 | 说明 |
| --- | --- |
| `CV` | 计算机视觉 |
| `NLP` | 自然语言处理 |
| `POINT_CLOUD` | 点云数据 |
| `ROBOT` | 机器人数据，类型预留 |

数据集模块使用独立的数据集任务类型，额外支持：

| 值 | 说明 |
| --- | --- |
| `MULTIMODAL` | 单个数据集版本内管理图片、文本、点云、音频、视频等多种模态；支持 MANIFEST 或 AUTO_DIRECTORY ZIP 异步导入 |

`MULTIMODAL` 只适用于数据集资产、数据集列表和数据集上传接口。模型上传和训练接口仍不接受该类型。

CV 子任务 `cvTaskType`：

| 值 |
| --- |
| `IMAGE_CLASSIFICATION` |
| `OBJECT_DETECTION` |
| `SEMANTIC_SEGMENTATION` |
| `INSTANCE_SEGMENTATION` |
| `UNLABELED` |
| `OTHER` |

CV 标注格式 `annotationFormat`：

| 值 |
| --- |
| `NONE` |
| `FOLDER_CLASSIFICATION` |
| `CSV` |
| `YOLO` |
| `COCO` |
| `VOC` |
| `MASK` |
| `LABELME` |
| `OTHER` |

CV 数据集未传 `cvTaskType` 时默认归一化为 `UNLABELED`，未传 `annotationFormat` 时默认归一化为 `NONE`。非 CV 数据集的这两个字段会归一化为 `null`。

数据集版本状态：

| 值 | 说明 |
| --- | --- |
| `DRAFT` | 草稿元数据，不能通过正式版本预览或进入训练；MULTIMODAL 工作区只能使用 workspace 接口维护 |
| `READY` | 可作为当前版本、可预览、可用于创建训练 |
| `DEPRECATED` | 历史版本，不能创建新训练；CV/NLP/POINT_CLOUD 预览允许该状态，MULTIMODAL 样本、文件和 V2 预览只允许 `READY` |
| `ARCHIVED` | 归档版本，不能预览或训练 |

训练状态 `status`：

| 值 | 说明 |
| --- | --- |
| `pending` | 已创建，等待训练 |
| `queued` | 已排队 |
| `running` | 训练中 |
| `success` | 训练成功 |
| `failed` | 训练失败 |
| `stopped` | 已停止 |

### 1.4 常用 ID

| 字段 | 说明 |
| --- | --- |
| `modelAssetId` / `assetId` | 模型资产 ID |
| `modelVersionId` / `id` | 模型版本 ID |
| `datasetAssetId` / `assetId` | 数据集资产 ID |
| `datasetVersionId` / `id` | 数据集版本 ID |
| `codeAssetId` / `assetId` | 代码资产 ID |
| `codeVersionId` / `versionId` | 不可变代码版本 ID |
| `workspaceId`（数据集工作区接口） | 数据集版本工作区 ID，只在 DRAFT 生命周期中有效 |
| `workspaceId`（代码工作区接口） | 代码编辑工作区 ID，只在草稿生命周期中有效 |
| `experimentId` | 训练实验 ID |
| `trainingVersionId` / `id` | 训练实验版本 ID |
| `uploadId` | 上传会话 ID |
| `importJobId` | 初始导入或 APPEND 导入任务 ID；在 V2 中也是失败导入的重试句柄，不是存储路径、owner 标识或可枚举外键 |

业务集成时优先引用稳定 ID，不建议长期依赖 `storagePath` 或 MinIO 对象路径。

### 1.5 前端统一接入方式

1. 前端先调用模块一 `POST /api/user/login` 登录，从 `data.token` 取得令牌。
2. 后续模块二请求统一携带 `Authorization: Bearer <token>`。如果使用 Cookie 登录态，跨域请求还需要启用 `credentials`。
3. 普通 JSON 接口先判断 HTTP 状态，再判断响应体 `success`；不能只以 HTTP `200` 作为业务成功。
4. 文件流接口应使用 `blob` 或 `arrayBuffer` 接收。若响应 `Content-Type` 为 `application/json`，说明后端返回了错误对象，不应继续交给预览器解析。
5. 前端保存和传递业务 ID 时要区分资产 ID 与版本 ID。训练、模型代码预览、数据集内容预览和点云预览都使用版本 ID。
6. `previewUrl` 是受鉴权保护的相对 URL。使用 `<img>`、Three.js Loader 等不会自动附加 Axios 拦截器请求头的组件时，应先用带令牌的 `fetch`/请求库取得 `Blob` 或 `ArrayBuffer`，再交给组件解析。
7. 一般列表接口中的 `page` 和 `current` 都从 `1` 开始；同时传入时 `current` 优先。模型和数据集主列表未传 `pageSize` 时会返回全部数据。管理员代码资产列表和审核队列是明确例外，其 `page` 从 `0` 开始。
8. zip entry 路径入参可以使用 `/` 或 `\`，后端会统一规范化为 `/`；后端返回的 `path` 固定使用 `/`，作为查询参数时仍需 URL 编码，不要自行拼接。
9. 当前 CORS 配置允许 `GET`、`POST`、`PUT`、`PATCH`、`DELETE`、`OPTIONS`；Merge Patch 请求必须发送 `Content-Type: application/merge-patch+json`。
10. ImportJob 恢复、上传会话恢复、数据集生命周期维护、代码风险扫描和 MinIO 删除任务均为后台异步流程；前端应以状态查询、版本工作区和列表聚合结果为准，不假设扫描、物理文件清理或失败恢复已经在业务接口返回时完成。

登录示例：

```ts
const loginResponse = await fetch('/api/user/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    type: 'account',
    username: 'admin',
    password: 'password123',
  }),
});
const loginResult = await loginResponse.json();
const token = loginResult.data.token;

const response = await fetch('/api/dataset/list?page=1&pageSize=20', {
  headers: { Authorization: `Bearer ${token}` },
});
const result = await response.json();
if (!response.ok || !result.success) {
  throw new Error(result.errorMessage || result.message || '请求失败');
}
```

### 1.6 推荐业务流程

#### 模型上传

新模型直接上传：

```text
/api/model/upload/init
  -> 按 uploadedPartIndexes 补传 /chunk
  -> /progress 确认全部分片
  -> /complete（不传 assetId）
  -> 保存返回的 assetId 和 id(modelVersionId)
```

给已有模型增加版本：

```text
/api/model-assets 或已有业务数据取得 assetId
  -> /api/model/upload/init
  -> /chunk
  -> /complete（传 assetId 和新 version）
  -> 使用返回的 id 作为新 modelVersionId
```

上传分片允许按同一 `partIndex` 重试。`complete` 对已经完成的 `uploadId` 可重复调用并返回原结果。`fileFingerprint` 只用于恢复未完成会话，不是后端文件完整性校验值。

#### 数据集上传

```text
/api/dataset/upload/init
  -> /chunk
  -> /progress
  -> /complete
  -> 普通数据集使用返回的 id/versionId
```

- 不传 `assetId` 时创建新数据集资产；传入 `assetId` 时给已有资产创建新版本。
- CV 本地文件夹可使用 `/api/dataset/upload/folder` 一次提交，后端会打包为 zip；大文件仍建议使用分片流程。
- CV/NLP/POINT_CLOUD/ROBOT 旧上传流程完成后，新版本为 `READY`，并自动成为资产的 `currentVersionId`。
- `MULTIMODAL` 必须上传 zip。init 未传 `sampleGrouping` 时默认 `AUTO_DIRECTORY`；高级调用方可显式传 `MANIFEST`。complete 后先返回 `DRAFT` 版本和 `PENDING` ImportJob，后台随后解析 manifest 或按根级样本目录生成内存导入计划。
- 导入成功后 ImportJob 变为 `SUCCESS`、版本变为 `READY`，并按 `versionNo` 条件更新 `currentVersionId`；失败时 ImportJob 变为 `FAILED`，版本保持 `DRAFT`。前端可轮询 `/api/dataset-samples/import/{importJobId}/status`。V2 前端必须保留上传 DTO 返回的 `importJobId`，并将其作为后续状态查询和失败重试句柄；版本工作区 DTO 不能在任务失败后重新提供该 ID。
- 上传到已有数据集时，`type`、`cvTaskType`、`annotationFormat` 必须和资产一致。

#### 数据集预览

```text
/api/dataset/list 取得 versionId
  -> CV/NLP 调用 /api/dataset/preview/files
  -> 使用 files[].previewUrl 查看文本、CSV 或图片
```

```text
/api/dataset/list?type=POINT_CLOUD 取得 versionId
  -> /api/dataset/point-cloud/preview
  -> 单文件使用 previewUrl
  -> zip 展示 pointCloudFiles，选择 previewAllowed=true 的 previewUrl
```

#### 训练实验

```text
legacy: 准备 modelVersionId、datasetVersionId、codeVersionId、hyperParams
profile: 准备 baseModelVersionId、datasetVersionId、codeVersionId、trainingProfile、hyperParams
  -> POST /api/task/create 或 POST /api/experiments
  -> GET /api/task/detail 轮询最新状态
  -> 需要保留实验历史时使用 /api/experiments/{experimentId}/versions
```

创建训练后，服务会在数据库事务提交后异步启动训练执行器。不带 `trainingProfile` 的兼容任务走本地训练器；带 `trainingProfile` 的任务走 K8s profile 训练路径，并要求训练代码版本通过准入。当前本地训练实现会读取 YOLO zip 中的图片和 `labels/` 下 `.txt` 标签；类型匹配通过并不代表任意格式都能被当前本地训练器实际解析。

### 1.7 接口速查与前端注意事项

以下表格覆盖当前模块二主要 legacy Controller 接口。V2 用户型门面接口见第 18 章，详细字段仍以各章节为准。

#### 模型接口

| 接口 | 前端用途 | 注意事项 |
| --- | --- | --- |
| `POST /api/model/upload/init` | 创建或恢复模型上传会话 | 仅接受 `.zip`；保存返回的 `uploadId`、`chunkSize`、`totalChunks` |
| `POST /api/model/upload/chunk` | 上传单个模型分片 | `partIndex` 从 `0` 开始；非末片必须等于 `chunkSize`；可重传同一分片 |
| `GET /api/model/upload/progress` | 断点续传和进度恢复 | 以 `uploadedPartIndexes` 判断缺失分片，不要只用 `uploadedChunks` |
| `POST /api/model/upload/complete` | 合并模型文件并创建版本 | 所有分片完成后调用；已有资产模式传 `assetId`；重复调用已完成会话会返回原结果 |
| `GET /api/model/list` | 模型版本主列表 | 返回项是一条模型版本，不是一条资产；`id` 是 `modelVersionId` |
| `GET /api/model/detail` | 模型版本详情 | 查询参数 `id` 必须是模型版本 ID |
| `GET /api/model/code-files` | 列出模型 zip 中可预览代码 | 最多返回 500 个白名单代码/文本文件 |
| `GET /api/model/previewCode` | 在线查看模型代码文本 | `path` 使用 `code-files` 返回值；单文件超过 1MB 会拒绝 |
| `DELETE /api/model/delete` | 从模型版本列表删除版本 | 与 `/api/model-versions/{id}` 共用生命周期服务；当前版本、最后版本或被训练引用时拒绝 |
| `POST /api/model-assets` | 预创建空模型资产 | 创建后还没有可训练文件；后续通过 upload complete 的 `assetId` 挂版本 |
| `GET /api/model-assets/{id}` | 查询模型资产元数据 | `id` 是资产 ID，不是版本 ID |
| `GET /api/model-assets` | 获取可选模型资产 | 无分页；普通用户只看到自己的未删除资产 |
| `PUT /api/model-assets/{id}` | 编辑模型名称、类型、备注 | 修改 `type` 会影响该资产所有版本的训练类型判断，前端应二次确认 |
| `DELETE /api/model-assets/{id}` | 删除资产和全部版本 | 软删除元数据，MinIO 异步清理；任一版本被训练引用时整体拒绝 |
| `POST /api/model-versions` | 手工创建 DRAFT 模型版本元数据 | 不上传文件；提交状态、存储、摘要或发布时间等服务端字段返回 `400` |
| `GET /api/model-versions/{id}` | 查询原始版本实体 | 用于版本管理页，不替代 `/api/model/detail` 的聚合详情 |
| `GET /api/model-versions` | 查询资产下版本 | 传 `assetId` 获取指定资产版本；当前无分页 |
| `PUT /api/model-versions/{id}` | 修改版本号和业务元数据 | 可补录说明、变更信息、`commitInfo`、`hyperParams`；任何角色都不能改资产、状态或制品字段 |
| `PUT /api/model-assets/{id}/current-version` | 兼容切换当前版本 | 目标必须是同资产、完整性校验通过的 READY 版本 |
| `DELETE /api/model-versions/{id}` | 删除指定模型版本 | 当前版本、最后版本或被训练引用时拒绝；MinIO 清理由任务异步执行 |

#### 数据集接口

| 接口 | 前端用途 | 注意事项 |
| --- | --- | --- |
| `POST /api/dataset/upload/init` | 创建或恢复数据集上传会话 | 支持 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 和 `MULTIMODAL + MANIFEST/AUTO_DIRECTORY`；分片大小由文件大小动态计算 |
| `POST /api/dataset/upload/chunk` | 上传数据集分片 | 使用 init 返回的 `chunkSize`；最多 10000 个分片；按 `uploadedPartIndexes` 支持续传 |
| `GET /api/dataset/upload/progress` | 查询上传进度和版本预分配信息 | 完成后可取得 `assetId`、`versionId`、`versionNo`、`versionLabel` |
| `POST /api/dataset/upload/complete` | 合并文件并创建版本 | 普通类型创建 READY；MULTIMODAL 创建 DRAFT、PRIMARY package 和 PENDING ImportJob，并异步启动导入 |
| `GET /api/dataset-samples/import/{importJobId}/status` | 查询多模态导入任务状态 | 通过任务关联的数据集资产校验权限；不返回 MinIO 路径 |
| `GET /api/dataset-versions/{versionId}/samples` | 分页查询多模态样本 | 仅允许查询未删除的 READY 版本；默认每页 20 条，最多 100 条 |
| `GET /api/dataset-samples/multimodal` | 按 `externalId` 跨版本查询样本 | 仅接受未删除、调用方有权限访问、状态为 READY 的 `datasetVersionIds`；返回 data/annotation 稳定字段和固定 preview/download 链接 |
| `GET /api/dataset-samples/{sampleId}` | 查询多模态样本详情 | 返回 sample、data 和 annotations，不返回 MinIO/ZIP 定位字段 |
| `GET /api/dataset-samples/{sampleId}/data` | 查询样本数据项 | 返回安全元数据；链接由调用方按 dataId 组装 |
| `GET /api/dataset-sample-data/{dataId}/preview` | 预览 ZIP 内数据项 | IMAGE/TEXT/POINT_CLOUD/安全文本类 OTHER 使用 D2 全量流；STORED VIDEO 支持单段 HTTP Range |
| `GET /api/dataset-sample-data/{dataId}/download` | 下载 ZIP 内数据项 | 使用 ZIP Entry Index + MinIO range 流式读取，不支持 HTTP Range |
| `GET /api/dataset-annotations/{annotationId}/download` | 下载 ZIP 内 annotation | 只返回文件流，不暴露 ZIP 路径或索引 |
| `POST /api/dataset-versions/{readyVersionId}/draft` | 基于 READY 创建维护工作区 | 物化父版本元数据；ZIP-backed 单模态旧版本会索引父 ZIP，不复制 MinIO ZIP，不改变 `currentVersionId` |
| `POST /api/dataset-versions/{draftVersionId}/packages/init` | 初始化 DRAFT 追加 ZIP 上传 | 支持 MULTIMODAL DRAFT 和 ZIP-backed 单模态 DRAFT；分片仍使用通用 chunk/progress 接口 |
| `POST /api/dataset-versions/{draftVersionId}/packages/complete` | 完成追加 ZIP 上传 | 创建 APPEND package、版本关系和独立 ImportJob；导入成功后 DRAFT 仍保持 DRAFT |
| `GET /api/dataset-versions/{draftVersionId}/workspace/samples` | 查询 DRAFT 工作区样本 | 支持分页及 `includeDeleted`，只允许访问有权限的未删除 DRAFT |
| `GET /api/dataset-samples/{sampleId}/workspace` | 查询 DRAFT 样本详情 | 返回物化后的 data 和 annotations，不返回 package 或 ZIP 定位字段 |
| `GET /api/dataset-samples/{sampleId}/workspace/data` | 查询 DRAFT 样本数据项 | 只返回安全元数据；本接口不开放 DRAFT preview/download |
| `DELETE /api/dataset-samples/{sampleId}/workspace` | 软删除 DRAFT 样本 | 只修改 DRAFT 样本删除标记，不修改父 READY，不物理删除 ZIP |
| `POST /api/dataset-samples/{sampleId}/workspace/restore` | 恢复 DRAFT 样本 | 清除 DRAFT 样本删除标记 |
| `POST /api/dataset-versions/{draftVersionId}/publish` | 发布 DRAFT 为新 READY | 校验导入、package、样本唯一性和版本顺序后更新 `currentVersionId` |
| `GET /api/dataset-versions/{datasetVersionId}/workspace/audit-logs` | 查询工作区审计日志 | owner 权限校验；返回内部回溯字段，不返回存储定位字段 |
| `POST /api/dataset/upload/folder` | 上传 CV 文件夹 | `files` 与 `paths` 必须一一对应；后端同步打包上传，不适合超大文件夹 |
| `GET /api/dataset/list` | 数据集资产主列表 | 每条记录代表资产并携带当前 READY 版本及最新 DRAFT 的导入状态；预览/训练仍使用 `versionId` |
| `POST /api/dataset-assets` | 预创建空数据集资产 | 只创建资产元数据；没有版本时列表中的版本字段为空 |
| `GET /api/dataset-assets/{id}` | 查询数据集资产元数据 | `id` 是资产 ID |
| `GET /api/dataset-assets` | 获取可选数据集资产 | 无分页；适合上传“新增版本”时的资产选择器 |
| `PUT /api/dataset-assets/{id}` | 编辑名称、类型和 CV 元数据 | 改动会影响后续上传匹配和训练类型判断，前端应谨慎开放 |
| `PUT /api/dataset-assets/{id}/current-version` | 切换列表默认版本 | 目标版本必须属于该资产且状态为 `READY` |
| `DELETE /api/dataset-assets/{id}` | 删除资产及其版本 | 当前版本也会随资产整体软删除；任一版本被训练引用时拒绝 |
| `POST /api/dataset-versions` | 管理员创建 DRAFT 元数据版本 | 普通用户必定被拒绝；不能携带存储元数据；不会上传文件 |
| `GET /api/dataset-versions/{id}` | 查询完整版本实体 | 预览和训练仍传这里返回的版本 `id` |
| `GET /api/dataset-versions` | 获取版本历史 | 传 `assetId` 时按 `versionNo` 倒序；不传时当前无统一排序和分页 |
| `PUT /api/dataset-versions/{id}` | 编辑版本标签和说明 | `fileName`、`storagePath`、`sizeBytes` 对所有角色都不可通过此接口修改 |
| `PATCH /api/dataset-versions/{id}/status` | 发布、废弃或归档版本 | 当前版本不能废弃或归档 |
| `DELETE /api/dataset-versions/{id}` | 删除非当前版本 | 当前版本或被训练引用的版本不能删除 |

#### 训练、文件和预览接口

| 接口 | 前端用途 | 注意事项 |
| --- | --- | --- |
| `POST /api/task/create` | 创建训练实验首版本 | 必须传三类版本 ID 和超参数；模型与数据集类型必须一致 |
| `GET /api/task/list` | 训练任务主列表 | 每个 `experimentId` 只返回最新版本；当前无分页 |
| `GET /api/task/detail` | 查询任务详情或轮询状态 | `id` 可为训练版本 ID 或实验 ID；实验 ID 返回最新版本 |
| `POST /api/task/stop` | 标记任务停止 | 代码直接更新状态，不负责中断已经启动的后台线程 |
| `POST /api/task/result` | 外部训练器回写结果 | `id` 可为训练版本 ID 或实验 ID；`progress` 范围为 0 到 100 |
| `DELETE /api/task/delete` | 删除整个实验 | 无论传实验 ID 还是其版本 ID，都会删除该实验全部版本 |
| `POST /api/experiments` | 创建实验首版本 | 与 `/api/task/create` 使用同一服务逻辑 |
| `GET /api/experiments/{experimentId}/versions` | 查询实验全部版本 | 按 `versionNo` 升序；无权限的数据会返回空数组 |
| `GET /api/experiments/{experimentId}/versions/{versionNo}` | 查询指定实验版本 | `versionNo` 是整数，不是训练版本 ID |
| `POST /api/experiments/{experimentId}/versions` | 基于最新版本创建下一版 | 未传字段继承最新版本；新版本自动启动本地训练 |
| `PUT /api/experiments/{experimentId}/versions/{versionNo}/hyper-parameters` | 修改指定版本超参数 | 修改后不会自动重新启动训练；需要新一次训练时应创建实验新版本 |
| `PUT /api/experiments/{experimentId}/versions/{versionNo}/result` | 精确回写指定实验版本结果 | 适合训练执行器按实验 ID 和版本号回调 |
| `GET /api/files/health` | 检查 MinIO 连通性 | 当前也需要登录；只检查后端到 MinIO，不代表具体对象存在 |
| `POST /api/files/upload` | 上传通用对象 | 普通用户对象会放到自己的 `users/{id}/files/` 前缀；不创建模型或数据集元数据 |
| `GET /api/files/download` | 按对象路径下载 | 当前必须传 `objectName`；成功为附件流，错误 HTTP 状态为 `404` |
| `DELETE /api/files/delete` | 异步删除通用对象 | 只返回删除任务已入队，不表示 MinIO 对象已经物理删除 |
| `GET /api/dataset/preview/files` | 获取 CV/NLP 可预览文件清单 | 先调用此接口，再使用后端返回的 `previewUrl` |
| `GET /api/dataset/preview/content` | 查看文本、JSON、JSONL、XML、CSV | zip 数据集必须传 `path`；NLP 单文件必须不传 `path` |
| `GET /api/dataset/preview/image` | 获取 CV zip 内图片流 | 只支持 CV zip；图片超过 20MB 默认限制时拒绝 |
| `GET /api/dataset/point-cloud/preview` | 获取点云预览元信息 | 单文件返回 `previewUrl`；zip 返回 `pointCloudFiles` |
| `GET /api/dataset/point-cloud/file` | 获取单文件 PCD/PLY 流 | 只接受原始文件为 `.pcd` 或 `.ply` 的版本 |
| `GET /api/dataset/point-cloud/zip-file` | 获取 zip 内单个 PCD/PLY 流 | `path` 必须来自预览接口并 URL 编码；默认单文件上限 200MB |

## 2. 模型上传接口

基础路径：`/api/model/upload`

模型上传采用分片流程：初始化上传、上传分片、查询进度、完成合并。分片大小由后端返回，当前为 `5MB`。`partIndex` 从 `0` 开始。

### 2.1 初始化模型上传

```http
POST /api/model/upload/init
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `fileName` | string | 是 | 模型文件名，仅支持 `.zip` |
| `fileSize` | number | 是 | 文件字节数，必须大于 `0` |
| `fileFingerprint` | string | 否 | 文件指纹；同用户同文件未完成上传可复用上传会话 |
| `commitInfo` | string | 是 | 提交信息；去除首尾空白后长度为 `1`～`1024` |
| `hyperParams` | object | 否 | 模型超参数；默认 `{}` |

请求示例：

```json
{
  "fileName": "resnet50.zip",
  "fileSize": 10485760,
  "fileFingerprint": "sha256-demo",
  "commitInfo": "train: imagenet baseline",
  "hyperParams": {
    "imageSize": 224
  }
}
```

响应 `data` 示例：

```json
{
  "uploadId": "model-upload-1710000000000-abc",
  "status": "UPLOADING",
  "fileName": "resnet50.zip",
  "fileSize": 10485760,
  "chunkSize": 5242880,
  "totalChunks": 2,
  "uploadedChunks": 0,
  "uploadedBytes": 0,
  "uploadedPartIndexes": [],
  "assetId": null,
  "versionId": null,
  "createdAt": "2026-05-16T10:00:00Z",
  "updatedAt": "2026-05-16T10:00:00Z"
}
```

### 2.2 上传模型分片

```http
POST /api/model/upload/chunk
Content-Type: multipart/form-data
```

表单参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `uploadId` | string | 是 | 上传会话 ID |
| `partIndex` | integer | 是 | 分片序号，从 `0` 开始 |
| `file` | file | 是 | 当前分片文件 |

规则：

- 非最后一个分片大小必须等于后端返回的 `chunkSize`。
- 最后一个分片可以小于 `chunkSize`。
- 同一 `uploadId` 只能由上传会话所有者访问。

### 2.3 查询模型上传进度

```http
GET /api/model/upload/progress?uploadId={uploadId}
```

响应 `data` 与初始化接口相同。`status` 可能为 `UPLOADING`、`COMPLETING`、`COMPLETED`。

### 2.4 完成模型上传

```http
POST /api/model/upload/complete
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `uploadId` | string | 是 | 上传会话 ID |
| `assetId` | string | 否 | 已有模型资产 ID；不传时创建新资产，传入时给已有资产新增版本 |
| `modelName` | string | 条件必填 | 创建新资产时必填；使用已有资产时可省略，若传入则必须与资产名称一致 |
| `version` | string | 是 | 模型版本号 |
| `type` | string | 条件必填 | 创建新资产时必填；使用已有资产时可省略，若传入则必须与资产类型一致 |
| `remark` | string | 条件必填 | 创建新资产时必填；使用已有资产时可省略且不会更新资产备注 |
| `commitInfo` | string | 条件必填 | 必须与 init 保存的信息一致；省略时使用 init 值 |
| `hyperParams` | object | 否 | 若提供必须与 init 保存的信息一致；省略时使用 init 值 |

创建新模型资产并上传首个版本：

```json
{
  "uploadId": "model-upload-1710000000000-abc",
  "modelName": "ResNet50",
  "version": "v1",
  "type": "CV",
  "remark": "baseline model",
  "commitInfo": "train: imagenet baseline",
  "hyperParams": {
    "imageSize": 224
  }
}
```

前端注意事项：

- `fileFingerprint` 相同且 `fileName`、`fileSize`、`commitInfo`、`hyperParams` 全部一致时，后端只会复用状态为 `UPLOADING` 的会话。
- 指纹仅用于查找可续传会话；发布时后端仍会读取完整对象并独立计算 SHA-256。
- 前端重新进入上传页面时，可以再次调用 init，再根据返回的 `uploadedPartIndexes` 只上传缺失分片。

给已有模型资产上传新版本：

```json
{
  "uploadId": "model-upload-1710000000000-def",
  "assetId": "model-asset-8e2b",
  "version": "v2",
  "commitInfo": "fine-tune: internal dataset"
}
```

响应 `data` 示例：

```json
{
  "uploadId": "model-upload-1710000000000-abc",
  "id": "model-ver-9f1a",
  "assetId": "model-asset-8e2b",
  "name": "ResNet50",
  "version": "v1",
  "type": "CV",
  "remark": "baseline model",
  "fileName": "resnet50.zip",
  "sizeBytes": 10485760,
  "artifactSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "commitInfo": "train: imagenet baseline",
  "hyperParams": {
    "imageSize": 224
  },
  "isCurrent": true,
  "status": "COMPLETED",
  "ownerUserId": 3,
  "createdAt": "2026-05-16T10:00:00Z",
  "updatedAt": "2026-05-16T10:00:10Z"
}
```

说明：

- 返回的 `id` 是模型版本 ID，可作为 `modelVersionId` 使用。
- 返回的 `assetId` 是模型资产 ID。
- 不传 `assetId` 时，后端创建新的模型资产和首个模型版本，保持原有上传行为。
- 传入 `assetId` 时，后端只在该资产下创建新版本，不修改已有资产的名称、类型、备注和所有者。
- 已有资产模式只允许资产所有者本人完成上传；管理员也不能通过该接口给其他用户的资产代上传版本。
- 新建资产时，`modelName` 会去除首尾空白，去除后必须为 1～255 个字符；同一 owner 下未删除模型资产名称按去除首尾空白后、不区分大小写唯一。重名或并发重复 complete 返回 HTTP `409`，纯空白名称返回 HTTP `400`。
- 同一模型资产下 `version` 唯一，包括已经软删除的历史版本；重复版本会返回明确错误。
- 已完成的 `uploadId` 重复调用完成接口时，返回第一次完成生成的资产和版本，不会重复创建。
- 完成上传会校验对象存在性、实际长度和完整 SHA-256；检查成功的 READY 版本自动成为资产当前版本。
- 模型最终对象使用版本和上传会话隔离的内部路径；前端及消费者不得拼接或持久化该路径，应使用模型版本下载接口。
- 模型 zip 必须为合法非空压缩包；zip 内路径不能包含绝对路径、盘符、`..` 或空字节。
- 模型 zip 规范化后的路径必须唯一，并拒绝 `/`、`\` 别名重复及文件/目录冲突。
- 模型 zip 当前最多检查 `100000` 个条目，解压后累计内容不能超过 `50GB`。
- complete 执行期间状态为 `COMPLETING`，前端不要并发重复发起合并；收到“正在合并”提示时应改为轮询 progress。

## 3. 模型查询接口

基础路径：`/api/model`

### 3.1 查询模型列表

```http
GET /api/model/list
```

查询参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 否 | `CV`、`NLP`、`POINT_CLOUD` 或 `ROBOT` |
| `keyword` | string | 否 | 仅按模型显示名称做不区分大小写的子串搜索 |
| `page` | integer | 否 | 页码，从 `1` 开始 |
| `current` | integer | 否 | 页码；优先级高于 `page` |
| `pageSize` | integer | 否 | 每页数量；不传则返回全部 |

前端查询对接说明：

- 模型名称输入框应传 `keyword`，后端仅按模型显示名称做不区分大小写的子串搜索；空白值等同未传，`%`、`_`、`\` 按普通字符处理。
- 类型下拉框应传 `type`，模型接口只支持 `CV`、`NLP`、`POINT_CLOUD` 或 `ROBOT`。
- 如果前端表单字段名是 `modelName` 或 `name`，提交请求前需要映射为 `keyword`。
- 示例：`GET /api/model/list?keyword=resnet&type=CV&page=1&pageSize=10`。

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `data` | 模型版本列表 |
| `total` | 过滤后的总数 |
| `page` | 当前页 |
| `pageSize` | 每页数量 |

列表项字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 模型版本 ID |
| `assetId` | 模型资产 ID |
| `name` | 模型名称 |
| `version` | 模型版本 |
| `type` | 任务类型 |
| `remark` | 模型资产备注 |
| `ownerUserId` | 归属用户 ID |
| `fileName` | 文件名 |
| `sizeBytes` | 文件大小 |
| `artifactSha256` | 模型制品 SHA-256；未校验的历史版本可为空 |
| `commitInfo` | 提交信息；历史版本可为空 |
| `hyperParams` | 模型超参数对象 |
| `status` | `DRAFT`、`READY`、`DEPRECATED` 或 `ARCHIVED` |
| `isCurrent` | 是否为资产当前版本 |
| `createdAt` | 创建时间 |

### 3.2 查询模型详情

```http
GET /api/model/detail?id={modelVersionId}
```

返回指定模型版本详情。普通用户只能查询自己的模型版本。列表和详情均不返回
`storagePath`；需要制品内容时使用 `/api/v2/model-versions/{versionId}/download`。

### 3.3 查询模型代码文件

```http
GET /api/model/code-files?id={modelVersionId}
```

响应 `data` 为数组：

| 字段 | 说明 |
| --- | --- |
| `path` | zip 内路径 |
| `fileName` | 文件名 |
| `extension` | 后缀 |
| `sizeBytes` | 文件大小 |

前端注意事项：

- 仅支持模型原始文件为 zip 的版本。
- 后端只返回代码/文本扩展名白名单中的文件，最多返回 `500` 项，并按 `path` 排序。
- 该接口不会返回权重、图片或任意二进制文件。

### 3.4 预览模型代码

```http
GET /api/model/previewCode?id={modelVersionId}&path={path}
```

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `path` | zip 内路径 |
| `fileName` | 文件名 |
| `content` | 文本内容 |
| `sizeBytes` | 文件大小 |

前端注意事项：

- `path` 应直接使用 `/api/model/code-files` 返回值，并进行 URL 编码。
- 后端严格按 UTF-8 文本读取；非法字节会返回 `success=false`，不会使用替换字符
  `U+FFFD` 继续返回。单个文件超过 `1MB` 也会返回 `success=false`。
- 前端代码编辑器应按纯文本展示，不应执行返回内容中的 HTML、脚本或命令。

### 3.5 删除模型版本

```http
DELETE /api/model/delete?id={modelVersionId}
```

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `id` | 模型版本 ID |
| `assetId` | 模型资产 ID |
| `deleted` | 是否已软删除 |
| `minioDeleteQueued` | 是否已加入 MinIO 删除任务 |

当前版本、资产的最后一个未删除版本或被训练实验引用的版本不能通过该接口删除。应先切换当前版本；最后版本只能随模型资产删除。

## 4. 模型资产 CRUD 接口

基础路径：`/api/model-assets`

### 4.1 创建模型资产

```http
POST /api/model-assets
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 模型资产名称 |
| `type` | string | 是 | `CV`、`NLP`、`POINT_CLOUD` 或 `ROBOT` |
| `remark` | string | 否 | 备注 |

注意：即使客户端传入 `id`、`ownerUserId`、`deleted` 等字段，后端创建时也会重新生成 `id`，并使用当前登录用户作为 `ownerUserId`。

该接口可以先创建一个没有版本的模型资产。前端随后上传模型文件时，在
`POST /api/model/upload/complete` 中传入返回的 `id` 作为 `assetId`，即可为该资产创建首个或后续模型版本。

模型资产名称会去除首尾空白，去除后必须为 1～255 个字符。同一 owner 下的未删除
模型资产名称按去除首尾空白后、不区分大小写唯一；创建、重命名和上传创建资产均执行
同一规则。纯空白或超长名称返回 HTTP `400`，重名以及快速重复提交的数据库竞争返回
HTTP `409`。软删除资产不占用名称。

### 4.2 查询模型资产详情

```http
GET /api/model-assets/{id}
```

### 4.3 查询模型资产列表

```http
GET /api/model-assets
```

普通用户返回自己的未删除资产；管理员返回全部未删除资产。

资产详情和列表响应增加 `currentVersionId`。没有可用当前版本时为 `null`。

### 4.4 更新模型资产

```http
PUT /api/model-assets/{id}
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 新名称 |
| `type` | string | 是 | `CV`、`NLP`、`POINT_CLOUD` 或 `ROBOT` |
| `remark` | string | 否 | 备注 |

只更新 `name`、`type`、`remark` 和 `updatedAt`。名称使用 4.1 的规范化和唯一性规则。

该接口是完整 `PUT` 更新：前端应回传当前 `name`、`type` 和 `remark`，不要只提交单个改动字段。修改资产 `type` 会改变其全部模型版本的训练类型判断。

### 4.5 切换当前模型版本

兼容接口：

```http
PUT /api/model-assets/{assetId}/current-version
Content-Type: application/json

{"versionId":"model-ver-..."}
```

正式 V2 接口为 `PUT /api/v2/model-assets/{assetId}/current-version`，请求体相同。目标版本必须属于该资产、未删除、状态为 `READY`，并通过对象存在性、实际长度和完整 SHA-256 校验。成功只更新 `currentVersionId`。

### 4.6 删除模型资产

```http
DELETE /api/model-assets/{id}
```

删除模型资产会清除当前指针，软删除该资产及其下未删除的模型版本，并将相关 MinIO 对象加入删除任务。如果任一模型版本被训练实验引用，删除会失败。

## 5. 模型版本 CRUD 接口

基础路径：`/api/model-versions`

### 5.1 创建模型版本

```http
POST /api/model-versions
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `assetId` | string | 是 | 模型资产 ID |
| `version` | string | 是 | 版本号；同一资产下唯一 |
| `description` | string | 否 | 版本说明 |
| `changeLog` | string | 否 | 版本变更信息 |
| `commitInfo` | string | 否 | 提交信息；最长 `1024` 字符 |
| `hyperParams` | object | 否 | 模型超参数；默认 `{}` |

该接口只创建状态为 `DRAFT` 的模型版本元数据，不上传模型文件、不写入 MinIO，也不会成为当前版本。`id`、`status`、`fileName`、`storagePath`、`sizeBytes`、`artifactSha256`、`publishedAt` 以及所有创建者/删除字段均由服务端管理；请求中只要出现这些字段即返回 HTTP `400`。生产文件上传应使用 `/api/model/upload/**` 或 V2 上传流程。

### 5.2 查询模型版本详情

```http
GET /api/model-versions/{id}
```

### 5.3 查询模型版本列表

```http
GET /api/model-versions?assetId={assetId}
```

`assetId` 可选。不传时普通用户返回自己可见模型资产下的全部未删除版本，管理员返回全部未删除版本。

详情和列表响应增加 `artifactSha256`、`commitInfo`、`hyperParams` 和 `isCurrent`。

### 5.4 更新模型版本

```http
PUT /api/model-versions/{id}
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `version` | string | 是 | 版本号；同一资产下唯一 |
| `description` | string | 否 | 版本说明 |
| `changeLog` | string | 否 | 版本变更信息 |
| `commitInfo` | string | 否 | 提交信息；历史版本可通过此接口补录 |
| `hyperParams` | object | 否 | 模型超参数；省略时保留原值 |

`assetId`、状态、存储路径、文件名、大小、摘要、发布时间和创建者/删除字段对所有角色均不可修改。

### 5.5 删除模型版本

```http
DELETE /api/model-versions/{id}
```

当前版本、资产的最后一个未删除版本或被训练实验引用的版本不能删除。其他版本删除成功后会软删除版本，并将对象加入 MinIO 删除任务。`DELETE /api/model/delete?id=...` 使用同一服务和相同限制。

## 6. 数据集上传接口

基础路径：`/api/dataset/upload`

数据集分片上传流程与模型上传一致，但数据集分片大小会根据文件大小动态计算：

```text
chunkSize = max(5MB, ceil(fileSize / 10000))
chunkSize 再向上取整到 1MB
totalChunks = ceil(fileSize / chunkSize)
```

`totalChunks` 不会超过 `10000`。前端必须使用 init 响应中的 `chunkSize` 和 `totalChunks`，不能固定按 5MB 切分。

### 6.1 初始化数据集上传

```http
POST /api/dataset/upload/init
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `fileName` | string | 是 | 数据集文件名 |
| `fileSize` | number | 是 | 文件字节数，必须大于 `0` |
| `fileFingerprint` | string | 否 | 文件指纹 |
| `assetId` | string | 否 | 已有数据集资产 ID；不传时创建新资产 |
| `datasetName` | string | 条件必填 | 创建新资产时必填；传 `assetId` 时使用已有资产名称 |
| `version` | string | 否 | 兼容的版本展示标签 |
| `versionLabel` | string | 否 | 优先使用的版本展示标签；不传时自动生成 `v{versionNo}` |
| `description` | string | 否 | 当前版本说明 |
| `changeLog` | string | 否 | 相对父版本的变更说明 |
| `parentVersionId` | string | 否 | 父版本 ID；已有资产模式默认使用当前版本 |
| `type` | string | 是 | `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL` |
| `cvTaskType` | string | 否 | CV 子任务；非 CV 会被置为 `null` |
| `annotationFormat` | string | 否 | CV 标注格式；非 CV 会被置为 `null` |
| `remark` | string | 否 | 备注 |
| `sampleGrouping` | string | 否 | `MULTIMODAL` 默认 `AUTO_DIRECTORY`，可显式传 `MANIFEST`；其他类型禁止传 |
| `manifestPath` | string | 否 | 仅 `MANIFEST` 允许；未传时默认为 `manifest.json`。可使用 `/` 或 `\`，后端统一规范化为 `/`；`AUTO_DIRECTORY` 不允许传 |
| `strictManifest` | boolean | 否 | 仅 `MULTIMODAL + MANIFEST` 支持，默认 `false`；为 `true` 时 ZIP 内普通文件必须全部被 manifest 声明 |

文件类型规则：

| 类型 | 规则 |
| --- | --- |
| `CV` | 分片上传只支持 `.zip`；zip 内必须至少包含 `.jpg`、`.jpeg`、`.png`、`.bmp`、`.gif`、`.webp`、`.tif` 或 `.tiff` 图片 |
| `NLP` | 支持 `.txt`、`.json`、`.jsonl`、`.csv`、`.xlsx`、`.xls`、`.pdf`、`.docx`、`.xml`，或只包含这些文件的 `.zip` |
| `POINT_CLOUD` | 支持单文件 `.ply`、`.pcd`，或 `.zip`；zip 内至少包含一个 `.ply` 或 `.pcd`，且仅允许 `.ply`、`.pcd`、`.txt`、`.json`、`.yaml`、`.yml` |
| `ROBOT` | 支持单文件 `.xml`、`.yaml`、`.yml`，或 `.zip`；zip 内仅允许 `.xml`、`.yaml`、`.yml`、`.json`、`.txt` |
| `MULTIMODAL` | 只支持 `.zip`；未传 `sampleGrouping` 时默认 `AUTO_DIRECTORY`，显式 `MANIFEST` 时可传 `manifestPath` 和可选 `strictManifest`；complete 后由异步 ImportJob 解析 ZIP 索引并生成导入计划 |

`manifestPath` 规则：

- 长度不能超过 `255`。
- 不能是 `/` 开头的绝对路径或 Windows 盘符路径。
- 可以使用 `/` 或 `\` 作为分隔符，后端统一规范化为 `/`。
- 规范化后不能包含 `..` 路径段或空字节。
- 只有 `sampleGrouping=MANIFEST` 时允许传入。

manifest JSON 内的 `data[].path`、`annotations[].path` 和 `annotations[].ref_data_path` 使用同一套 ZIP 路径规则：接受 `/` 或 `\`，统一规范化为 `/`，继续拒绝空路径、绝对路径、Windows 盘符、空字节和 `..`。

`strictManifest` 规则：

- 未传或传 `false` 时保持宽松模式：manifest 声明的文件必须存在，未声明的普通 ZIP entry 只作为 warning 记录，不中断导入。
- 传 `true` 仅允许 `MULTIMODAL + MANIFEST`；`AUTO_DIRECTORY` 或单模态请求会在 init 阶段拒绝。
- 严格模式下，ZIP 内任一未被 manifest 声明的普通文件都会使 ImportJob 进入 `FAILED`，错误码为 `INVALID_MANIFEST_UNDECLARED_ENTRY`，`errorDetailsJson` 包含首个未声明路径及未声明路径列表。

CV zip 中的非图片文件会按照 `annotationFormat` 过滤：

| `annotationFormat` | 允许的标注文件 | 是否至少需要一个标注文件 |
| --- | --- | --- |
| `NONE`、`FOLDER_CLASSIFICATION`、`MASK` | 不允许非图片文件 | 否 |
| `CSV` | `.csv` | 是 |
| `YOLO` | `.txt`、`.yaml`、`.yml` | 是 |
| `COCO`、`LABELME` | `.json` | 是 |
| `VOC` | `.xml` | 是 |
| `OTHER` | `.txt`、`.json`、`.xml`、`.csv`、`.yaml`、`.yml` | 否 |

`annotationFormat=YOLO` 时，作为类别元数据的 YAML 文件不按标签行解析；每个其他
`.txt` 标签文件必须是严格 UTF-8。空行允许；每个非空行必须恰好为
`classId centerX centerY width height` 五列：`classId` 是 `0` 到
`2147483647` 的十进制整数，四个坐标必须是有限十进制数，
`centerX/centerY ∈ [0,1]`，`width/height ∈ (0,1]`。任一行不满足规则时拒绝上传。

响应 `data` 字段与模型上传进度类似，额外包含 `cvTaskType`、`annotationFormat` 和 `strictManifest`。其中 `chunkSize` 是本次会话实际使用的动态分片大小。

多模态初始化示例：

```json
{
  "datasetName": "autonomous-driving-scenes",
  "fileName": "scene-bundle.zip",
  "fileSize": 53687091200,
  "fileFingerprint": "sha256-demo",
  "type": "MULTIMODAL",
  "sampleGrouping": "MANIFEST",
  "manifestPath": "metadata/manifest.json",
  "strictManifest": true,
  "versionLabel": "v1"
}
```

AUTO_DIRECTORY 初始化时不传 `manifestPath`：

```json
{
  "datasetName": "autonomous-driving-scenes",
  "fileName": "scene-bundle.zip",
  "fileSize": 53687091200,
  "type": "MULTIMODAL",
  "sampleGrouping": "AUTO_DIRECTORY",
  "versionLabel": "v1"
}
```

AUTO_DIRECTORY ZIP 根目录必须直接是样本目录，不会自动剥离 `v1/`、`append/` 等 wrapper：

```text
scene_001/
  image/front.jpg
  point_cloud/lidar.pcd
  annotations/front.jpg.json
scene_002/
  image/front.png
```

确定性规则：

- 根级目录名就是 `external_id`；根级普通文件直接失败。样本按 `external_id` 排序生成 `sampleIndex`。
- 每个样本至少包含一个 Data；各样本可使用不同模态子集，只有图片和点云也合法。
- `annotations/` 下文件视为 Annotation，其余文件视为 Data。
- Data 扩展名白名单：图片 `jpg/jpeg/png/bmp/gif/webp/tif/tiff`，点云 `pcd/ply`，视频 `mp4/webm/mov/avi/mkv`，音频 `wav/mp3/flac`，文本 `txt/md/csv/jsonl`，其他 `bin/log/npy/npz/pkl`。未知或无扩展名文件失败。
- Annotation 只允许 `json/txt/xml/csv/yaml/yml`。先按去掉 annotation 扩展名后的完整 Data 文件名匹配，再按唯一文件名 stem 匹配；零匹配或多匹配都失败，不猜测。
- AUTO 生成的 `sensor`、`channel` 为 `null`，tags/metadata 为空。需要自定义这些字段时使用 MANIFEST。
- 生成结果只存在 ImportJob 内存中；后端不修改用户 ZIP，也不向 MinIO 写入 `manifest.json`。
- MANIFEST 和 AUTO_DIRECTORY 每个导入计划最多包含 10000 个样本；每个样本最多 100 个 Data、100 个 Annotation；全部 Data 与 Annotation 引用合计最多 100000 个。
- 数据集 ZIP 最多 100000 个 entry，声明的解压后总大小最多 50GB；ZIP entry 路径同样会把 `\` 规范化为 `/`，规范化后重复、绝对路径、盘符、空字节或 `..` 会失败。

50GB 文件的典型响应会返回：

```json
{
  "chunkSize": 6291456,
  "totalChunks": 8534
}
```

版本管理补充：

- `assetId`：初始化上传时可选。为空表示创建新的数据集资产；非空表示给已有数据集资产新增版本。
- 新建资产时，`datasetName` 会去除首尾空白，去除后必须为 1～255 个字符；同一 owner
  下未删除数据集资产名称按去除首尾空白后、不区分大小写唯一。纯空白名称返回 HTTP
  `400`，重名或并发重复 complete 返回 HTTP `409`。
- `versionNo`：后端生成的真实版本序号，同一 `assetId` 下从 `1` 递增。
- `versionLabel`：展示标签；旧字段 `version` 作为兼容别名。客户端不应依赖 `version` 判断版本顺序。
- `description`：当前版本说明。
- `changeLog`：相对父版本的变更说明。
- `parentVersionId`：父版本 ID；新增到已有资产且不传时默认使用该资产的当前版本。父版本必须属于同一资产、未删除且状态为 `READY`。
- CV/NLP/POINT_CLOUD/ROBOT 上传完成后新版本为 `READY`，并自动成为 `dataset_asset.currentVersionId`。
- MULTIMODAL 上传完成后版本保持 `DRAFT`，不会修改 `dataset_asset.currentVersionId`，并创建状态为 `PENDING` 的 ImportJob。
- 训练、预览和文件接口继续使用返回的 `id` / `versionId` 作为 `datasetVersionId`，不要使用 `assetId` 或 `versionLabel` 代替。
- 非 MULTIMODAL zip complete 仍执行对应类型的同步格式校验。MULTIMODAL complete 不读取或解压整个 zip，也不执行单模态文件白名单；manifest 和 ZIP 内容校验由异步 ImportJob 使用 range 读取完成。
- `fileFingerprint` 的恢复规则还会比较资产、版本标签、类型和版本说明等上传元数据；这些字段变化后可能会创建新会话。
- 管理员可以给其他用户拥有的数据集资产新增版本，最终对象仍写入该资产所有者的 `users/{ownerUserId}/...` 前缀。模型已有资产上传没有这一管理员代传能力。
- init 阶段返回的 `versionNo` 是当时的预估值；并发上传时应以 complete 最终返回的 `versionNo`、`versionLabel` 为准。
- 自定义 `versionLabel` 在同一资产下必须唯一，已经软删除的历史标签也可能占用唯一值。

### 6.2 上传数据集分片

```http
POST /api/dataset/upload/chunk
Content-Type: multipart/form-data
```

表单参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `uploadId` | string | 是 | 上传会话 ID |
| `partIndex` | integer | 是 | 分片序号，从 `0` 开始 |
| `file` | file | 是 | 当前分片文件 |

规则：

- `partIndex` 从 `0` 开始，必须小于 init 返回的 `totalChunks`。
- 非最后一个分片的大小必须严格等于 init 返回的 `chunkSize`。
- 最后一个分片可以小于 `chunkSize`。

### 6.3 查询数据集上传进度

```http
GET /api/dataset/upload/progress?uploadId={uploadId}
```

### 6.4 完成数据集上传

```http
POST /api/dataset/upload/complete
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `uploadId` | string | 是 | 上传会话 ID |

普通 CV/NLP/POINT_CLOUD/ROBOT 响应 `data` 示例：

```json
{
  "uploadId": "dataset-upload-1710000000000-abc",
  "id": "dataset-ver-9f1a",
  "assetId": "dataset-asset-8e2b",
  "name": "casting defect",
  "version": "v1",
  "type": "CV",
  "cvTaskType": "IMAGE_CLASSIFICATION",
  "annotationFormat": "FOLDER_CLASSIFICATION",
  "remark": "sample dataset",
  "fileName": "casting.zip",
  "sizeBytes": 10485760,
  "status": "COMPLETED",
  "uploadStatus": "COMPLETED",
  "datasetVersionId": "dataset-ver-9f1a",
  "versionStatus": "READY",
  "importJobId": null,
  "importStatus": null,
  "ownerUserId": 3,
  "createdAt": "2026-05-16T10:00:00Z",
  "updatedAt": "2026-05-16T10:00:10Z"
}
```

首次数据集上传在合并、同步内容校验或版本确认阶段失败时，会话进入可重试的
`FAILED` 状态。已上传分片继续保留；调用方可以使用原 `uploadId` 再次 complete，或
重新上传任一分片后重试。重新上传分片会把会话切回 `UPLOADING`，成功抢占 complete
会切到 `COMPLETING`，两种重试都会清除旧错误。缺少分片等尚未进入完成阶段的错误
仍保持 `UPLOADING`。

V2 `POST /api/v2/dataset-uploads/{uploadId}/complete` 对上述失败继续返回 HTTP `422 / DATASET_UPLOAD_NOT_COMPLETABLE`，`details.reasonCode` 为
`INVALID_DATASET_CONTENT`、`DATASET_UPLOAD_STORAGE_FAILED` 或
`DATASET_UPLOAD_FINALIZATION_FAILED`。随后查询原上传 ID 返回
`status=FAILED`、`displayStatus=UPLOAD_FAILED` 和同一稳定错误码的 `userError`；错误内容
不包含对象路径、SQL、堆栈或内部异常类名。相同文件指纹重新 init 时也会恢复原
`UPLOADING/FAILED` 会话。该状态只适用于首次数据集上传，工作区组件和 APPEND 继续
使用各自的 revision/ImportJob 错误生命周期。

MULTIMODAL + MANIFEST/AUTO_DIRECTORY 响应示例：

```json
{
  "uploadId": "dataset-upload-1710000000000-xyz",
  "id": "dataset-ver-draft",
  "datasetVersionId": "dataset-ver-draft",
  "assetId": "dataset-asset-mm",
  "type": "MULTIMODAL",
  "fileName": "scene-bundle.zip",
  "status": "COMPLETED",
  "uploadStatus": "COMPLETED",
  "versionStatus": "DRAFT",
  "importJobId": "ijob-abc",
  "importStatus": "PENDING"
}
```

返回的 `id` 和 `datasetVersionId` 都是数据集版本 ID。

前端注意事项：

- 响应中的 `status` / `uploadStatus=COMPLETED` 是上传会话状态，不能当作数据集版本状态。
- 普通类型的 `versionStatus=READY`；MULTIMODAL 的 `versionStatus=DRAFT`。
- MULTIMODAL complete 只执行 MinIO compose 和 stat，不调用现有 zip 全量格式校验。
- MULTIMODAL complete 会为当前 ZIP 创建一个物理 `dataset_package`，并以 `PRIMARY`、`packageOrder=0` 关联到新版本；`ImportJob.packageId` 指向该 package。
- 导入生成的 Sample、Data 和 Annotation 会记录来源 package。初始导入成功后版本变为 READY，`DatasetVersion.storagePath` 仍保留，兼容没有 packageId 的旧数据。
- MULTIMODAL 首次上传 complete 在合并或版本确认失败时会清理目标对象和本次创建的 DRAFT；若本次创建了空资产也会清理，并把会话置为可重试的 `FAILED`。异步 ImportJob 失败仍使用 `IMPORT_FAILED/IMPORT_PARTIAL` 语义。
- complete 可对已完成的 `uploadId` 重试并返回原结果；状态为 `COMPLETING` 时应停止重复提交并轮询 progress。
- 数据集名称、类型和版本说明来自 init 会话，complete 请求只接受 `uploadId`。

### 6.5 上传 CV 文件夹

```http
POST /api/dataset/upload/folder
Content-Type: multipart/form-data
```

表单参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `assetId` | string | 否 | 已有数据集资产 ID；不传时创建新资产 |
| `datasetName` | string | 条件必填 | 创建新资产时必填；已有资产模式使用资产名称 |
| `version` | string | 否 | 兼容的版本展示标签 |
| `versionLabel` | string | 否 | 优先使用的版本展示标签 |
| `type` | string | 是 | 必须为 `CV` |
| `cvTaskType` | string | 否 | CV 子任务 |
| `annotationFormat` | string | 否 | CV 标注格式 |
| `remark` | string | 否 | 备注 |
| `description` | string | 否 | 当前版本说明 |
| `changeLog` | string | 否 | 版本变更说明 |
| `parentVersionId` | string | 否 | 父版本 ID |
| `files` | file[] | 是 | 文件列表 |
| `paths` | string[] | 是 | 每个文件对应的相对路径，数量必须与 `files` 一致 |

后端会将文件夹打包为 zip 并生成数据集资产和版本。路径不能是绝对路径、盘符路径，也不能包含 `..` 或空字节。

文件夹上传同样支持 `assetId`、`versionLabel`、`description`、`changeLog`、`parentVersionId`。传 `assetId` 时会在已有数据集资产下创建下一个 `versionNo`，不再创建新的数据集资产。

前端注意事项：

- 浏览器选择目录时应同时提交每个文件的相对路径，不能只提交文件名，否则目录结构和同名文件关系会丢失。
- 路径重复、非法路径、空文件、没有图片、或文件扩展名不符合 `annotationFormat` 时整个请求失败。
- 该接口在一个 multipart 请求中接收全部文件，受 `spring.servlet.multipart` 当前 `64MB` 单请求限制；较大目录应先在客户端压缩后走分片上传。

### 6.6 查询多模态导入任务状态

```http
GET /api/dataset-samples/import/{importJobId}/status
```

路径参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `importJobId` | string | 是 | MULTIMODAL complete 返回的导入任务 ID |

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `importJobId` | 导入任务 ID |
| `datasetVersionId` | 对应的 DRAFT 数据集版本 ID |
| `status` | `PENDING`、`RUNNING`、`SUCCESS`、`FAILED` 或 `PARTIAL`；`PARTIAL` 表示部分样本已导入、部分失败，不能直接 publish，可走 `INCREMENTAL` retry |
| `progress` | 进度，当前新任务初始化为 `0` |
| `totalSamples` | 导入计划样本总数；任务成功后写入 |
| `importedSamples` | 已导入样本数；成功时等于 `totalSamples` |
| `errorCode` | 结构化错误码；成功或尚未失败时为 `null` |
| `errorMessage` | 全局失败原因 |
| `errorDetailsJson` | 结构化错误详情的 JSON 文本；没有详情时为 `null` |
| `createdAt` | 任务创建时间 |
| `startedAt` | 开始执行时间 |
| `finishedAt` | 完成时间 |

示例：

```json
{
  "success": true,
  "data": {
    "importJobId": "ijob-abc",
    "datasetVersionId": "dataset-ver-draft",
    "status": "PENDING",
    "progress": 0,
    "totalSamples": null,
    "importedSamples": 0,
    "errorCode": null,
    "errorMessage": null,
    "errorDetailsJson": null,
    "createdAt": "2026-06-10T10:00:00Z",
    "startedAt": null,
    "finishedAt": null
  },
  "errorMessage": null
}
```

权限规则：

- 后端按 `ImportJob -> DatasetVersion -> DatasetAsset` 回溯资源归属。
- 普通用户只能查询自己数据集资产的任务；管理员可查询全部。
- 接口不返回 `storagePath`、bucket 或其他 MinIO 对象路径。

#### ImportJob 重试

```http
POST /api/dataset-samples/import/{importJobId}/retry?mode=FULL
POST /api/dataset-samples/import/{importJobId}/retry?mode=INCREMENTAL
POST /api/v2/import-jobs/{importJobId}/retry
```

V2 请求体为
`{"mode":"FULL|INCREMENTAL","expectedWorkspaceRevision":12}`；Legacy
仍使用 query 参数。

约束：

- 只支持 `mode=FULL` 或 `mode=INCREMENTAL`，未传时按 `FULL` 处理；`PARTIAL` 和未知 mode 会被拒绝。
- `FULL` 只允许 `FAILED -> PENDING`，不扩展到 `PARTIAL`。
- `FULL` 要求对应 DatasetVersion 必须仍为 `DRAFT`；若该 ImportJob 对应的版本或 package 已经存在导入样本，后端拒绝重试，当前不做半导入样本清理。
- `INCREMENTAL` 只允许 `PARTIAL -> PENDING`，要求存在未解决的失败样本记录；它只重跑 `import_job_sample_failure` 中的失败样本，并复用 failure row 保存的 `externalId` 和原始 `sampleIndex`，避免重新计算导致 sampleIndex 漂移。
- 0 个样本成功的导入仍为 `FAILED`，不能走 `INCREMENTAL`。
- 同一 DatasetVersion 存在其他 `PENDING` 或 `RUNNING` ImportJob 时，后端拒绝重试，调用方应等待活动导入结束后再重试。
- FULL 重试会清空 `errorCode`、`errorMessage`、`errorDetailsJson`，重置 `progress=0`、`importedSamples=0`，把状态置为 `PENDING`，并通过现有 ImportJob launcher 重新调度。
- INCREMENTAL 重试会把未解决 failure row 标记为 `RETRYING`，任务置为 `PENDING`，保留已成功导入样本。

Legacy 重试成功返回 `ApiResponse<ImportJobStatusDto>`。V2 重试成功返回 `V2ImportJobStatusDto`，并增加 `workspaceId/workspaceRevision`；其余字段为 `importJobId`、`status`、`displayStatus`、`importProgress`、`totalSamples`、`importedSamples`、`failedSamples`、`retryable`、`retryModes` 和 `userError`。V2 状态查询对不存在或无权限的任务返回 `404 / IMPORT_JOB_NOT_FOUND`；重试时，任务 ID 不存在返回同一错误，任务存在但工作区不存在、不再是 DRAFT 或无权访问时返回 `404 / DATASET_WORKSPACE_NOT_FOUND`。不可重试状态或 mode 与状态不匹配返回 `422 / IMPORT_JOB_NOT_RETRYABLE`；JSON 请求体为 `null` 返回 `400 / INVALID_IMPORT_JOB_RETRY`，请求体缺失、JSON 或字段类型无法解析返回 `400 / INVALID_REQUEST`，缺少 revision 返回 `400 / EXPECTED_WORKSPACE_REVISION_REQUIRED`，revision 过期返回 `409 / WORKSPACE_REVISION_CONFLICT`，工作区存在其他活动操作时返回 `409 / WORKSPACE_BUSY`。

当前实现：

- complete 创建 `PENDING` ImportJob，并通过 Spring 管理的任务线程池异步执行。
- ImportJob 状态会进入 `RUNNING`，全部成功后为 `SUCCESS`，0 个样本成功或任务级异常时为 `FAILED`，部分成功时为 `PARTIAL`。
- 失败 ImportJob 支持受控 `FULL` 重试；`PARTIAL` ImportJob 仅支持受控 `INCREMENTAL` 重试。
- MANIFEST 会解析用户提供的 manifest；AUTO_DIRECTORY 会根据根级样本目录生成内存导入计划。两种模式最终都写入 DatasetSample、DatasetSampleData 和 DatasetAnnotation。
- 运行中的任务每 30 秒更新 heartbeat；超过 30 分钟无 heartbeat 的任务会重置为 `PENDING` 并重新调度。
- ImportJob 恢复调度带数据库分布式锁；启动恢复和每 60 秒恢复共用同一加锁路径，避免多实例重复调度同一批 `PENDING` 任务。

### 6.7 查询多模态样本

以下接口只允许查询 `status=READY` 且 `deleted=false` 的 DatasetVersion。DRAFT 版本即使已经存在部分样本记录也不可查询。

#### 分页查询版本样本

```http
GET /api/dataset-versions/{versionId}/samples?page=1&pageSize=20
```

- `page` 默认 `1`，小于等于 `0` 时按 `1` 处理。
- `pageSize` 默认 `20`，最大 `100`。
- 按 `sampleIndex ASC, createdAt ASC, id ASC` 稳定排序。

响应 `data`：

```json
{
  "data": [{
    "sampleId": "sample-001",
    "datasetVersionId": "dataset-ver-ready",
    "externalId": "scene-001",
    "sampleIndex": 0,
    "tags": { "weather": "sunny" },
    "metadata": { "split": "train" },
    "createdAt": "2026-06-11T08:00:00Z"
  }],
  "total": 1,
  "page": 1,
  "pageSize": 20,
  "totalPages": 1
}
```

#### 查询样本详情

```http
GET /api/dataset-samples/{sampleId}
```

详情包含 Sample 基础字段，以及 `data` 和 `annotations`：

```json
{
  "sampleId": "sample-001",
  "datasetVersionId": "dataset-ver-ready",
  "externalId": "scene-001",
  "sampleIndex": 0,
  "tags": {},
  "metadata": {},
  "createdAt": "2026-06-11T08:00:00Z",
  "data": [{
    "sampleDataId": "data-001",
    "dataType": "VIDEO",
    "sensor": "front",
    "channel": "rgb",
    "seq": 0,
    "format": "mp4",
    "fileName": "front.mp4",
    "sizeBytes": 1048576,
    "checksum": null,
    "contentType": "video/mp4",
    "metadata": { "codec": "h264" },
    "createdAt": "2026-06-11T08:00:01Z"
  }],
  "annotations": [{
    "annotationId": "annotation-001",
    "sampleDataId": "data-001",
    "annotationType": "TRACK",
    "format": "json",
    "fileName": "track.json",
    "sizeBytes": 4096,
    "checksum": null,
    "contentType": "application/json",
    "metadata": {},
    "createdAt": "2026-06-11T08:00:02Z"
  }]
}
```

#### 查询样本 Data 列表

```http
GET /api/dataset-samples/{sampleId}/data
```

返回字段与样本详情中的 `data` 一致。

#### 跨版本 externalId 查询

```http
GET /api/dataset-samples/multimodal?externalId=scene-001&datasetVersionIds=version-1&datasetVersionIds=version-2&page=1&pageSize=20
GET /api/v2/dataset-samples/multimodal?externalId=scene-001&datasetVersionIds=version-1,version-2&page=1&pageSize=20
```

该查询用于按同一个 `externalId` 在多个 DatasetVersion 中查找样本、Data 和 Annotation。`datasetVersionIds` 支持重复 query 参数或逗号分隔值；后端会去重并按 `datasetVersionId ASC, sampleIndex ASC, createdAt ASC, id ASC` 稳定分页。`page` 默认 `1`，`pageSize` 默认 `20`、最大 `100`。

所有传入的 `datasetVersionIds` 都必须满足：未删除、`status=READY`，并且调用方可通过 `DatasetVersion -> DatasetAsset.ownerUserId` 访问。任一版本不存在、无权限、已删除或不是 READY 时，整个查询失败；legacy 返回 `ApiResponse.success=false` 和 `dataset version not found or no permission`，V2 返回 `404 / DATASET_NOT_FOUND`，不会暴露是哪一个 versionId 失败。

legacy 成功响应为 `ApiResponse<PageResponse<DatasetMultimodalExternalIdSampleDto>>`；V2 成功响应直接返回同一分页对象：

```json
{
  "data": [
    {
      "datasetVersionId": "version-1",
      "sampleId": "sample-1",
      "externalId": "scene-001",
      "sampleIndex": 0,
      "data": [
        {
          "sampleDataId": "data-1",
          "dataType": "IMAGE",
          "sensor": "front",
          "channel": "rgb",
          "seq": 0,
          "format": "jpg",
          "fileName": "front.jpg",
          "sizeBytes": 12345,
          "checksum": "sha256:abc",
          "contentType": "image/jpeg",
          "previewUrl": "/api/dataset-sample-data/data-1/preview",
          "downloadUrl": "/api/dataset-sample-data/data-1/download"
        }
      ],
      "annotations": [
        {
          "annotationId": "ann-1",
          "sampleDataId": "data-1",
          "annotationType": "bbox",
          "format": "json",
          "fileName": "front.json",
          "sizeBytes": 456,
          "checksum": "sha256:def",
          "contentType": "application/json",
          "downloadUrl": "/api/dataset-annotations/ann-1/download"
        }
      ]
    }
  ],
  "total": 1,
  "page": 1,
  "pageSize": 20,
  "totalPages": 1
}
```

该接口只返回稳定字段：`datasetVersionId`、`sampleId`、`externalId`、`sampleIndex`、`data`、`annotations` 以及固定 preview/download 链接；不返回 `storagePath`、bucket、objectName、`originalPath`、packageId、ZIP offset、压缩定位字段或 CRC。

权限和安全规则：

- 所有查询都按 `Sample -> DatasetVersion -> DatasetAsset` 回溯资源归属。
- 普通用户只能查询自己的数据集样本，管理员可按现有 `AuthContext` 规则跨用户查询。
- 不存在、无权限、版本已删除或版本不是 READY 时统一返回失败，不暴露资源真实存在性。
- 响应不包含 `storagePath`、bucket、`originalPath`、ZIP offset、压缩信息、CRC 或 MinIO objectName。
- D1 DTO 仍不返回 `previewUrl`、`downloadUrl`；调用方可使用 `sampleDataId` 或 `annotationId` 调用下述 D2 文件接口。

### 6.8 多模态样本 preview/download

以下接口仅适用于已成功导入、`status=READY` 且 `deleted=false` 的 DatasetVersion：

```http
GET /api/dataset-sample-data/{dataId}/preview
GET /api/dataset-sample-data/{dataId}/download
GET /api/dataset-annotations/{annotationId}/download
```

读取流程：

1. 根据 dataId 或 annotationId 读取已落库的 `zipDataOffset`、`compressedSize`、`uncompressedSize` 和 `compressionMethod`。
2. preview 通过 DatasetSampleData -> DatasetSample -> DatasetVersion -> DatasetAsset 回溯；download 通过数据项关联的 DatasetVersion -> DatasetAsset 回溯，完成状态、删除标记和所有权校验。
3. Data/Annotation 有 `packageId` 时从对应 `dataset_package.storagePath` 定位 ZIP；旧数据 `packageId` 为空时回退到 `DatasetVersion.storagePath`。
4. 使用 MinIO range request 只读取目标 ZIP entry 的压缩字节。
5. `STORED` 直接返回，`DEFLATED` 使用 raw deflate 流式解压。
6. 不支持其他压缩算法，也不回退到从 ZIP 开头顺序扫描。

preview 支持：

- `IMAGE`
- `TEXT`
- `POINT_CLOUD`
- `OTHER` 中 contentType 为 `text/plain`、`text/csv`、`application/json`、`application/xml` 或 `text/xml`

普通 preview 限制：

- `AUDIO` 返回 `415`。
- 解压后大小超过 `100MB` 返回 `413`。
- 缺少 ZIP Entry Index 或无法确定 preview 大小时返回 `422`。

VIDEO preview：

- 仅 `dataType=VIDEO` 且 `compressionMethod=STORED` 支持在线播放。
- `compressedSize` 必须等于 `uncompressedSize`，且 `zipDataOffset`、`compressedSize`、`uncompressedSize` 均必须有效；否则返回 `422`。
- `DEFLATED` 或其他压缩方式返回 `415`，但 download 仍沿用 D2 解压下载逻辑。
- 不带 `Range` 时流式返回完整视频 `200 OK`。
- 支持单段 `bytes=start-end`、`bytes=start-` 和 `bytes=-suffixLength`。
- 多段、格式非法、起始位置越界或显式结束位置越界返回 `416`。
- Range 读取的 MinIO 偏移为 `zipDataOffset + rangeStart`，长度为 `rangeEnd - rangeStart + 1`，不会把视频整体载入内存。
- VIDEO 的数据库 contentType 为空时回退为 `video/mp4`。

响应头：

- preview 使用数据库 `contentType` 和 `Content-Disposition: inline`。
- download 使用数据库 `contentType`；缺失或非法时回退 `application/octet-stream`，并使用安全处理后的 attachment 文件名。
- 普通非 VIDEO preview 和全部 download 保持完整文件 `200 OK`，不处理请求中的 Range。
- VIDEO STORED 全量响应返回 `200`、`Accept-Ranges: bytes` 和完整 `Content-Length`。
- VIDEO STORED 单段 Range 响应返回 `206`、`Accept-Ranges: bytes`、`Content-Range: bytes start-end/total` 和区间 `Content-Length`。
- VIDEO Range 返回 `416` 时包含 `Content-Range: bytes */total`。

权限和安全：

- data 和 annotation 都会回溯 `DatasetVersion -> DatasetAsset`。
- 普通用户只能访问自己的数据集文件，管理员沿用现有 AuthContext 规则。
- 不存在、无权限、DRAFT、FAILED 导入版本或已删除版本统一返回 `404`，不暴露资源真实存在性。
- 响应和错误信息不包含 `storagePath`、bucket、`originalPath`、ZIP offset、CRC 或 MinIO objectName。
- 本阶段不实现 thumbnail、转码、抽帧或视频播放。

## 7. 数据集查询接口

基础路径：`/api/dataset`

### 7.1 查询数据集列表

```http
GET /api/dataset/list
```

查询参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 否 | `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL` |
| `keyword` | string | 否 | 仅按数据集显示名称做不区分大小写的子串搜索 |
| `page` | integer | 否 | 页码 |
| `current` | integer | 否 | 页码；优先级高于 `page` |
| `pageSize` | integer | 否 | 每页数量；不传则返回全部，传入时最大 `200` |

前端查询对接说明：

- 数据集名称输入框应传 `keyword`，后端仅按数据集显示名称做不区分大小写的子串搜索；空白值等同未传，`%`、`_`、`\` 按普通字符处理。
- 类型下拉框应传 `type`，取值为 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL`。
- 如果前端表单字段名是 `datasetName` 或 `name`，提交请求前需要映射为 `keyword`。
- 示例：`GET /api/dataset/list?keyword=casting&type=CV&page=1&pageSize=10`。

列表项字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 数据集资产 ID |
| `assetId` | 数据集资产 ID |
| `name` | 数据集名称 |
| `type` | `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL` |
| `cvTaskType` | CV 子任务 |
| `annotationFormat` | 标注格式 |
| `remark` | 数据集备注 |
| `ownerUserId` | 归属用户 ID |
| `versionId` | 当前推荐数据集版本 ID |
| `version` | 当前推荐版本展示标签 |
| `fileName` | 当前推荐版本文件名 |
| `sizeBytes` | 文件大小 |
| `size` | 格式化后的文件大小 |
| `versionRemark` | 版本备注 |
| `fileCount` | 当前推荐版本文件数；兼容前端旧列名 |
| `currentVersionFileCount` | 当前推荐版本文件数 |
| `versionCount` | 当前资产下未删除版本数量 |
| `uploadTime` | 当前推荐版本上传时间 |
| `createdAt` | 资产创建时间 |
| `updatedAt` | 资产更新时间 |
| `latestDraftVersionId` | 该资产下 `versionNo` 最大的 DRAFT 版本 ID；没有时为 `null` |
| `importJobId` | 最新 DRAFT 对应的导入任务 ID；没有时为 `null` |
| `importStatus` | 最新 DRAFT 的导入状态；active `PENDING`/`RUNNING` 优先，未解决 `PARTIAL` 表示 V2 `IMPORT_PARTIAL`、不可 publish，重试模式包含 `INCREMENTAL`；成功后版本转为 READY，不再属于最新 DRAFT |
| `importProgress` | 最新 DRAFT 导入进度 |
| `importErrorMessage` | 最新 DRAFT 导入失败原因；非失败状态通常为 `null` |

训练创建时应使用 `versionId` 作为 `datasetVersionId`。

版本字段：

- `versionId` / `currentVersionId`：当前推荐版本 ID。
- `currentVersionNo`：当前推荐版本的后端序号。
- `currentVersionLabel` / `version`：当前推荐版本展示标签。
- `versionStatus`：当前推荐版本状态。
- `versionDescription`：当前推荐版本说明。
- `latestDraftVersionId` 和 `import*` 字段只描述最新 DRAFT，不改变 `versionId` / `currentVersionId` 的 READY 当前版本语义。
- 列表优先使用 `dataset_asset.current_version_id`；如果为空或不可用，则回退到该资产下 `status=READY` 且 `versionNo` 最大的版本。
- `fileCount` 与 `currentVersionFileCount` 当前都表示当前推荐 READY 版本文件数；没有当前推荐版本或计数不可用时为 `null`。
- `versionCount` 表示当前资产下未删除版本数量。
- 文件数优先读取 `dataset_version.file_count`；旧数据为空时懒计算并回写，计算失败返回 `null`，不阻断列表。
- 已物化为 Sample/Data/Annotation 元数据的版本按 Sample Data 与 Annotation 条目数相加；传统普通 ZIP 数据集按当前版本源 ZIP 的非目录 entry 计数；非压缩单文件版本计为 1。
- 列表先在数据库按资产分页，再只补全当前页资产的版本、导入任务和文件数；V1 未传 `pageSize` 时仍按兼容逻辑返回全部。

## 8. 数据集资产 CRUD 接口

基础路径：`/api/dataset-assets`

### 8.1 创建数据集资产

```http
POST /api/dataset-assets
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 数据集名称 |
| `type` | string | 是 | `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL` |
| `cvTaskType` | string | 否 | CV 子任务；非 CV 可为空 |
| `annotationFormat` | string | 否 | CV 标注格式；非 CV 可为空 |
| `remark` | string | 否 | 备注 |

注意：创建时后端会忽略客户端传入的 `id`，重新生成 `dataset-asset-...`，并使用当前登录用户作为 `ownerUserId`。

该接口只创建数据集资产元数据，不会创建数据集版本或上传文件。前端创建后可将返回的 `id` 作为 `/api/dataset/upload/init` 或 `/api/dataset/upload/folder` 的 `assetId`。

数据集资产名称会去除首尾空白，去除后必须为 1～255 个字符。同一 owner 下的未删除
数据集资产名称按去除首尾空白后、不区分大小写唯一；创建、重命名和上传创建资产均执行
同一规则。纯空白或超长名称返回 HTTP `400`，重名以及快速重复提交的数据库竞争返回
HTTP `409`。模型资产和数据集资产分属不同命名空间，软删除资产不占用名称。

### 8.2 查询数据集资产详情

```http
GET /api/dataset-assets/{id}
```

### 8.3 查询数据集资产列表

```http
GET /api/dataset-assets
```

普通用户返回自己的未删除资产；管理员返回全部未删除资产。

### 8.4 更新数据集资产

```http
PUT /api/dataset-assets/{id}
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 新名称 |
| `type` | string | 是 | `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 或 `MULTIMODAL` |
| `cvTaskType` | string | 否 | CV 子任务；非 CV 可为空 |
| `annotationFormat` | string | 否 | CV 标注格式；非 CV 可为空 |
| `remark` | string | 否 | 备注 |

前端注意事项：当前接口会直接修改资产级 `type`、`cvTaskType` 和 `annotationFormat`，不会同步改写历史版本。资产已有版本后，普通编辑页不建议随意开放类型修改。

该接口是完整 `PUT` 更新，前端应回传当前名称、类型、CV 子任务、标注格式和备注，避免省略字段被清空或按默认值重新归一化。名称使用 8.1 的规范化和唯一性规则。

### 8.5 删除数据集资产

```http
DELETE /api/dataset-assets/{id}
```

删除数据集资产会软删除该资产及其下未删除的数据集版本，并将相关 MinIO 对象加入删除任务。如果任一数据集版本被训练实验引用，删除会失败。

### 8.6 切换当前版本

```http
PUT /api/dataset-assets/{assetId}/current-version
Content-Type: application/json
```

请求体：

```json
{ "versionId": "dataset-ver-xxx" }
```

只能切换到同一数据集资产下、未删除、`status=READY` 的版本。普通用户只能切换自己的数据集，管理员可切换全部。

## 9. 数据集版本 CRUD 接口

基础路径：`/api/dataset-versions`

### 9.1 创建数据集版本

```http
POST /api/dataset-versions
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `assetId` | string | 是 | 数据集资产 ID |
| `versionLabel` | string | 否 | 版本展示标签，优先于 `version` |
| `version` | string | 否 | 兼容的版本展示标签；两者都不传时自动生成 `v{versionNo}` |
| `remark` | string | 否 | 备注 |
| `description` | string | 否 | 版本说明 |
| `changeLog` | string | 否 | 变更说明 |
| `status` | string | 否 | 只能为空或 `DRAFT` |

该接口只允许管理员调用，并且只能创建没有文件的 `DRAFT` 元数据版本。`storagePath`、`fileName`、`sizeBytes` 只要任一非空都会被拒绝。

注意：创建时后端会忽略客户端传入的 `id`，重新生成 `dataset-ver-...`。

前端建议：普通数据集上传和新增版本不要调用此接口，应使用 `/api/dataset/upload/**`。当前接口创建的 DRAFT 没有后续文件挂载接口，不能直接进入预览或训练。

### 9.2 基于 READY 创建 DRAFT 工作区

```http
POST /api/dataset-versions/{readyVersionId}/draft
```

该接口为已有数据集创建后续维护工作区。`readyVersionId` 必须属于调用方可访问的数据集资产，且版本必须未删除并处于 `READY`。同一资产已有未删除 `DRAFT` 时拒绝创建，并返回已有 DRAFT 的版本 ID。

创建结果：

- 新版本分配新的 `versionNo` 和默认 `versionLabel=v{versionNo}`。
- `parentVersionId` 指向请求中的 READY 版本。
- 新版本状态固定为 `DRAFT`，`publishedAt` 为空。
- 若父 READY 已有 Sample/Data/Annotation 元数据，物化复制其中未删除的 DatasetSample、DatasetSampleData 和 DatasetAnnotation，复制记录使用新 ID，Annotation 的 sampleDataId 映射到 DRAFT 内的新 Data。
- 复制父版本的 `dataset_version_package` 关系，复用同一批 `dataset_package` 和 ZIP 对象；不复制 MinIO ZIP。
- ZIP-backed `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` 旧版本若没有 package 元数据，创建 DRAFT 时会把父 ZIP 登记为 `PRIMARY` package，并按 ZIP 非目录 entry 生成一文件一样本的 Sample/Data 元数据。
- 非 ZIP 旧版本若能唯一推导单一对象或资源映射，只在新 DRAFT 中懒规范化为 RAW 主包并补齐资源引用；无法唯一推导时返回 `DATASET_WORKSPACE_SOURCE_AMBIGUOUS`，父 READY 不变。
- DRAFT 的样本元数据是独立数据库记录，后续软删除、恢复或追加不会修改父 READY。
- 不创建 ImportJob，不读取 manifest，不执行导入。
- 不更新 DatasetAsset.currentVersionId；当前版本仍保持原 READY 版本。
- 普通样本查询仍只接受 READY；DRAFT 必须使用 workspace 专用查询接口。

响应示例：

```json
{
  "success": true,
  "data": {
    "draftVersionId": "dataset-ver-workspace",
    "parentVersionId": "dataset-ver-ready",
    "datasetAssetId": "dataset-asset-001",
    "versionNo": 3,
    "status": "DRAFT",
    "currentVersionId": "dataset-ver-ready",
    "message": "workspace draft created"
  },
  "errorMessage": null
}
```

响应不返回 `storagePath`、MinIO objectName 或 bucket。同一资产只允许一个未删除 DRAFT。

### 9.3 追加 ZIP 到 DRAFT

初始化上传：

```http
POST /api/dataset-versions/{draftVersionId}/packages/init
Content-Type: application/json
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `fileName` | string | 是 | 必须是 `.zip` |
| `fileSize` | number | 是 | ZIP 字节数，必须大于 0 |
| `fileFingerprint` | string | 否 | 客户端文件指纹 |
| `sampleGrouping` | string | 否 | 仅 `MULTIMODAL` 使用；未传默认 `AUTO_DIRECTORY`，可显式传 `MANIFEST`；单模态禁止传 |
| `manifestPath` | string | 否 | 仅 `MULTIMODAL + MANIFEST` 允许，未传时默认 `manifest.json`；可使用 `/` 或 `\`，后端统一规范化为 `/`；`AUTO_DIRECTORY` 和单模态都禁止传 |
| `strictManifest` | boolean | 否 | 仅 `MULTIMODAL + MANIFEST` 支持，默认 `false`；为 `true` 时未声明普通 ZIP entry 会导致 ImportJob `FAILED` |

init 返回通用上传进度结构。后续分片继续调用：

```http
POST /api/dataset/upload/chunk
GET /api/dataset/upload/progress?uploadId={uploadId}
```

完成上传：

```http
POST /api/dataset-versions/{draftVersionId}/packages/complete
Content-Type: application/json

{ "uploadId": "dataset-upload-..." }
```

要求与结果：

- 版本必须是调用方有权限访问的、未删除的 DRAFT；支持 `MULTIMODAL` 以及 ZIP-backed `CV`、`NLP`、`POINT_CLOUD`、`ROBOT`。
- complete 创建新的 `dataset_package`、`APPEND` 版本关系和独立 ImportJob。
- `MULTIMODAL` APPEND 继续按 `MANIFEST` 或 `AUTO_DIRECTORY` 生成导入计划。
- 单模态 APPEND 只接受 ZIP，禁止 `sampleGrouping`、`manifestPath` 和 `strictManifest`；complete 会执行对应任务类型的 ZIP 安全与文件白名单校验，ImportJob 再按 ZIP entry 生成一文件一样本的 Sample/Data。
- APPEND 导入只新增 Sample/Data/Annotation，来源字段指向新 package；不会修改继承样本。
- APPEND 导入计划的未删除 `externalId` 或 `sampleIndex` 与当前 DRAFT 冲突时导入失败，不做跨包合并；AUTO_DIRECTORY 同样适用。
- 导入成功后 package 状态变为 READY，但 DatasetVersion 仍保持 DRAFT，`currentVersionId` 不变。
- complete 响应包含 `packageId`、`packageRole=APPEND`、`packageOrder`、`importJobId` 和状态；这些字段只描述导入结果，不进入样本查询 DTO。

### 9.4 查询 DRAFT 工作区样本

```http
GET /api/dataset-versions/{draftVersionId}/workspace/samples?page=1&pageSize=20&includeDeleted=false
GET /api/dataset-samples/{sampleId}/workspace
GET /api/dataset-samples/{sampleId}/workspace/data
```

- 只允许查询 `status=DRAFT`、`deleted=false` 且调用方有权限的数据集版本。
- 列表默认不返回软删除样本；`includeDeleted=true` 可用于工作区维护页展示已删除项。
- `page` 默认 1，`pageSize` 默认 20、最大 100，按 `sampleIndex ASC, createdAt ASC, id ASC` 稳定排序。
- 数据来自 DRAFT 已物化的独立记录，不沿 `parentVersionId` 动态合并。
- detail 返回 data 和 annotations；响应不包含 packageId、storagePath、bucket、originalPath、ZIP offset、CRC 或 MinIO objectName。
- DRAFT 文件 preview/download 仍不开放；发布为 READY 后使用普通文件接口。

### 9.5 删除与恢复 DRAFT 样本

```http
DELETE /api/dataset-samples/{sampleId}/workspace
POST /api/dataset-samples/{sampleId}/workspace/restore
```

- 两个接口都只接受调用方有权限的 DRAFT 样本。
- 删除是软删除，只设置样本的 `deleted/deletedAt`；不删除 Data、Annotation、package 或 MinIO ZIP。
- 恢复会清除删除标记。
- 操作只影响 DRAFT 的物化样本，不影响父 READY。

### 9.6 查询 workspace 审计日志

```http
GET /api/dataset-versions/{datasetVersionId}/workspace/audit-logs?page=1&pageSize=20
GET /api/v2/dataset-versions/{datasetVersionId}/workspace/audit-logs?page=1&pageSize=20
```

- 只允许访问调用方有权限的数据集版本；无权限或版本不存在时，legacy 返回失败 envelope，V2 返回 404 语义。
- `page` 默认 1，`pageSize` 默认 20、最大 100，按 `createdAt DESC, id DESC` 返回。
- 当前记录 `DRAFT_CREATED`、`APPEND_PACKAGE_INIT`、`APPEND_PACKAGE_COMPLETED`、`IMPORT_JOB_SUCCEEDED`、`IMPORT_JOB_FAILED`、`IMPORT_JOB_PARTIAL`、`SAMPLE_DELETED`、`SAMPLE_RESTORED`、`VERSION_PUBLISHED`、`IMPORT_JOB_RETRIED`；`IMPORT_JOB_RETRIED` 通过 `details.retryMode` 区分 `FULL` 和 `INCREMENTAL`。
- 响应字段包括 `id`、`datasetAssetId`、`datasetVersionId`、`parentVersionId`、`operation`、`actorType`、`actorUserId`、`targetType`、`targetId`、`importJobId`、`packageId`、`sampleId`、`details`、`createdAt`。
- 审计日志只追加，不参与 publish、retry、清理或 READY/DRAFT 状态判断。
- 审计响应用于模块二内部回溯，`targetId`、`packageId`、`sampleId`、`details` 等不作为训练、推理或其他模块的稳定集成契约。
- 审计日志不记录也不返回 `storagePath`、bucket、MinIO objectName 或 ZIP offset。

### 9.7 发布 DRAFT 为新 READY

```http
POST /api/dataset-versions/{draftVersionId}/publish
```

发布前校验：

- DRAFT 必须未删除、属于调用方可访问的数据集资产，且 `versionNo` 高于当前 READY。
- 父版本和当前版本必须是同资产的有效 READY。
- DRAFT 下所有 ImportJob 必须为 `SUCCESS` 或 `SUPERSEDED`；`PARTIAL`、`FAILED`、`PENDING`、`RUNNING` 均会阻止 publish。
- 必须存在且仅存在一个顺序为 0 的 PRIMARY package；后续关系必须为连续有序的 APPEND package。
- 所有关联 package 必须未删除、状态为 `READY` 或 `SUPERSEDED`、有存储路径并属于同一资产；`PARTIAL` package 不可 publish，PRIMARY 和 APPEND 都按同一规则校验。
- Sample/Data/Annotation 的 package 引用必须完整且属于该版本关联的 package。
- 至少存在一个未删除样本，未删除样本的 `externalId` 和 `sampleIndex` 不得重复。

发布在同一事务内把 DRAFT 状态改为 READY、设置 `publishedAt` 并更新 `DatasetAsset.currentVersionId`。父 READY、package、MinIO ZIP 和 ImportJob 均不会被复制或修改；软删除样本保留在数据库中，但普通 READY 查询和文件访问不会返回它。发布后 workspace 查询拒绝该版本，普通 READY 查询及 preview/download/VIDEO Range 开始生效。

### 9.8 package 模型

- `dataset_package` 表示一个物理 ZIP，包括归属资产、对象路径、文件信息、manifest 路径、状态和软删除信息。
- `dataset_version_package` 表示某个版本使用哪些物理包，`PRIMARY` 是初始包，`APPEND` 是后续追加包，`packageOrder` 定义稳定顺序。
- READY 创建 DRAFT 时优先复制版本与 package 的关系，复用物理包；ZIP-backed 单模态旧版本没有 package 关系时会补建 PRIMARY package 关系；追加上传再为 DRAFT 增加新的 APPEND package。
- Sample 记录 `createdByPackageId`，Data/Annotation 记录 `packageId`，文件服务据此解析实际 ZIP。
- 旧数据没有 packageId 时继续使用 `DatasetVersion.storagePath`，因此原有已导入版本仍可读取。

### 9.9 查询数据集版本详情

```http
GET /api/dataset-versions/{id}
```

### 9.10 查询数据集版本列表

```http
GET /api/dataset-versions?assetId={assetId}
```

`assetId` 可选。不传时普通用户返回自己可见数据集资产下的全部未删除版本，管理员返回全部未删除版本；此模式没有显式排序和分页。

传 `assetId` 时按 `versionNo` 倒序返回完整版本历史。返回项包含 `versionNo`、`versionLabel`、`status`、`description`、`changeLog`、`parentVersionId`、`fileFingerprint`、`publishedAt` 等字段。

### 9.11 更新数据集版本

```http
PUT /api/dataset-versions/{id}
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `assetId` | string | 否 | 目标资产 ID；普通用户不能修改 |
| `versionLabel` | string | 条件必填 | 展示标签，优先于 `version` |
| `version` | string | 条件必填 | 兼容字段；`versionLabel` 和 `version` 至少传一个 |
| `remark` | string | 否 | 备注 |
| `description` | string | 否 | 版本说明 |
| `changeLog` | string | 否 | 变更说明 |
| `fileName` | string | 否 | 不可修改 |
| `storagePath` | string | 否 | 不可修改 |
| `sizeBytes` | number | 否 | 不可修改 |

`assetId` 对普通用户和管理员都不可修改。数据集版本还关联当前版本指针、package 资产归属和存储路径，不能通过通用版本更新接口迁移到其他资产；需要在目标资产下创建新版本时应使用上传或专用版本创建流程。存储元数据对所有角色都不能通过该接口修改。

模型/数据集版本 CRUD 响应不序列化 `storagePath`。请求中的该字段仍会被绑定，以便按
现有规则拒绝客户端篡改服务端管理的存储元数据。

`remark`、`description`、`changeLog` 会直接使用请求值覆盖，省略时会被更新为 `null`；编辑页应先读取详情并提交完整可编辑字段。

### 9.12 更新数据集版本状态

```http
PATCH /api/dataset-versions/{id}/status
Content-Type: application/json
```

请求体：

```json
{ "status": "READY" }
```

版本状态整体只支持 `DRAFT`、`READY`、`DEPRECATED`、`ARCHIVED`；状态更新接口只允许更新为 `READY`、`DEPRECATED`、`ARCHIVED`。`DRAFT -> READY` 必须已有 `storagePath`、`fileName`、`sizeBytes`。当前版本不能直接改为 `DEPRECATED` 或 `ARCHIVED`，需要先通过当前版本切换接口切到其他 `READY` 版本。

前端注意事项：

- 该接口没有限制只能按固定状态机顺序流转，只限制目标状态和当前版本保护。
- 由于手工创建 DRAFT 时又不能写入存储元数据，按当前公开接口创建的空 DRAFT 通常无法直接变为 READY。
- 工作区不能通过该通用状态接口发布；必须调用 `POST /api/dataset-versions/{draftVersionId}/publish`，由专用服务执行完整性校验和 `currentVersionId` 更新。
- 当前 CORS 已允许 `PATCH`；跨域调用仍需匹配部署配置中的允许来源，并携带有效登录态或 `Authorization`。

### 9.13 删除数据集版本

```http
DELETE /api/dataset-versions/{id}
```

如果数据集版本被训练实验引用，删除会失败。当前版本不能删除。删除成功后会软删除版本，并将对象加入 MinIO 删除任务。

## 10. 训练任务接口

基础路径：`/api/task`

`/api/task` 是当前前端兼容路径，操作训练实验的最新版本。

训练配置页使用以下训练代码资产接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/code/upload` | 上传训练代码 ZIP，生成 `codeAssetId` 和 `codeVersionId` |
| `GET` | `/api/code/version/list` | 查询当前用户可用于训练的 `READY` + `APPROVED` 代码版本 |
| `POST` | `/api/code/version/{codeVersionId}/approve` | 管理员单独批准代码版本；要求当前制品 SHA 对应最新且通过的校验证据 |
| `GET` | `/api/code/version/{codeVersionId}/training-check?trainingProfile=...` | 按训练方案校验或幂等复用证据；真正的新证据进入系统审核模式分流 |

`/api/code/upload` 使用 `multipart/form-data`，字段为 `file`、`codeName`、`version`、`trainingProfile`、`remark`。代码包只支持 `.zip`，包内允许 `.py`、`.json`、`.yaml`、`.yml`、`.txt`、`.md`、`.jsonl`，禁止 `.sh`、`.bash`、`.exe`、`.bat`、`.cmd`、`.dll`、`.so`、`.jar`，并会检查 zip slip 路径、条目数和解压后体积。Windows ZIP 条目中的 `\\` 会按 18.8 规范化为 `/`。结构校验通过后进入 `DIRECT_PASS` 或 `STANDARD_REVIEW` 系统审核模式；后者再按 `MANUAL_ONLY/SHADOW/ENFORCE` 风险模式分流。静态 LOW 或系统直通都不是完整安全审计证明。

`codeName` 会去除首尾空白，并在同一 owner 的未删除代码资产中按不区分大小写保持唯一；不同 owner 可以同名，同一代码资产发布多个版本不受影响。名称冲突在读取和上传 ZIP 前预检，并由数据库唯一索引处理并发竞争；`/api/code/upload` 统一返回 HTTP `409`，响应体仍使用原 `ApiResponse` 结构（`success=false`）。已软删除代码资产不占用名称。

Legacy 代码上传成功结果返回代码资产、版本、文件、大小、训练方案和审核状态等业务
字段，不返回 `storagePath`；后续消费使用 `codeVersionId`、consumer manifest 或内部
制品解析器。

当前启用的训练方案为 `image_text_consistency_fusion_logreg` 和
`yolo_object_detection`；方案入口、数据集类型和运行参数以
`TrainingPlanRegistry` 加载的当前启用定义为准。

### 10.1 创建训练任务

```http
POST /api/task/create
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 否 | 任务名称；不传时使用实验 ID |
| `modelVersionId` | string | 条件 | 兼容模型版本 ID；未传 `baseModelVersionId` 时作为基础模型权重版本使用 |
| `baseModelVersionId` | string | 条件 | 基础模型权重版本 ID；带 `trainingProfile` 时必填，可与 `modelVersionId` 二选一，二者同时传入时必须一致 |
| `datasetVersionId` | string | 是 | 数据集版本 ID |
| `codeVersionId` | string | 是 | 训练代码版本 ID |
| `trainingProfile` | string | 否 | 训练方案 ID；不传走 legacy 本地训练路径，传入时进入 profile/K8s 路径 |
| `hyperParams` | object/string | 条件 | 超参数 JSON；也可使用 `params`。legacy 路径必填，profile 路径不传时按 `{}` 记录 |
| `params` | object/string | 否 | `hyperParams` 的兼容字段 |
| `remark` | string | 否 | 备注 |

后端会校验数据集版本是否存在且当前用户可访问，数据集版本必须为 `READY` 且
`storagePath` 非空。legacy 和 profile 两条路径都会对基础模型执行 READY 消费准入：
校验对象存在、stat 实际大小、完整对象 SHA-256 和模型 ZIP 结构；历史空 SHA 在成功后
原子回填。确定性损坏会把版本降为 `DRAFT` 并清除对应当前指针，临时 MinIO 故障拒绝
本次创建但不降级版本。legacy 还校验模型类型与数据集类型一致，例如
`POINT_CLOUD` 模型只能匹配 `POINT_CLOUD` 数据集，`CV`、`NLP`、`POINT_CLOUD`
之间错配会被拒绝。profile 路径会额外校验代码版本为 `READY` + `APPROVED`、冻结后的
代码资产 `trainingProfile` 与请求一致，并校验数据集类型符合该 profile 要求。模块二在
首个未删除代码版本产生后禁止修改该字段，版本消费清单则返回发布时固化的 profile 快照。

当前代码的额外行为：

- 不带 `trainingProfile` 的 legacy 路径中，`codeVersionId` 仍只校验非空；带 `trainingProfile` 的 profile 路径中，`codeVersionId` 必须指向已准入的代码版本。
- 创建事务提交后会自动启动 `TrainingExecutorRouter`。legacy 任务走 `LocalTrainingRunnerService`；profile 任务要求 K8s 环境可用，并提交 K8s Job。
- 当前本地训练器实际只解析 zip 中的图片和路径包含 `labels/` 的 YOLO `.txt` 标签。NLP、POINT_CLOUD 或其他 CV 格式即使通过类型匹配，也可能在异步训练阶段进入 `failed`。
- `MULTIMODAL` 当前不属于模型或训练任务类型，不能用于创建训练。
- legacy 本地训练器当前有效超参数主要是 `epochs`（1–100，默认 3）和 `lr0`（0.000001–1，默认 0.05）。profile 路径会记录并传递 `hyperParams`，但不能覆盖固定训练命令。
- legacy 和 profile 路径创建阶段都在启动执行器前完成基础模型的完整对象与 ZIP
  结构复验，不依赖调用方预先访问 V2 consumer manifest 或下载接口。
- 前端创建后应轮询 `/api/task/detail`，不能把创建接口返回的 `pending` 当成训练已经成功启动。

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `id` | 训练实验版本 ID |
| `experimentId` | 训练实验 ID |
| `versionNo` | 实验版本号 |
| `name` | 名称 |
| `modelVersionId` | 模型版本 ID |
| `baseModelVersionId` | 基础模型权重版本 ID；当前与 `modelVersionId` 相同 |
| `codeVersionId` | 代码版本 ID |
| `trainingProfile` | 训练方案 ID；legacy 任务为空 |
| `datasetVersionId` | 数据集版本 ID |
| `hyperParams` | 超参数 JSON |
| `status` | 状态，初始为 `pending` |
| `progress` | 进度，初始为 `0` |
| `metrics` | 指标 JSON |
| `runId` | MLflow 运行 ID |
| `logPath` | 日志路径 |
| `outputPath` | 输出路径 |
| `errorMessage` | 错误信息 |
| `startedAt` | 开始时间 |
| `finishedAt` | 结束时间 |
| `remark` | 备注 |
| `ownerUserId` | 归属用户 ID |
| `createdAt` | 创建时间 |
| `updatedAt` | 更新时间 |
| `createTime` | `createdAt` 的字符串兼容字段 |

### 10.2 查询训练任务列表

```http
GET /api/task/list
```

返回当前用户可见实验的最新版本：

```json
{
  "success": true,
  "data": {
    "data": [],
    "total": 0
  },
  "errorMessage": null
}
```

### 10.3 查询训练任务详情

```http
GET /api/task/detail?id={id}
```

`id` 可以是训练实验版本 ID，也可以是训练实验 ID。传实验 ID 时返回该实验最新版本。

### 10.4 停止训练任务

```http
POST /api/task/stop?id={id}
```

`id` 可以是训练实验版本 ID，也可以是训练实验 ID。停止后状态为 `stopped`，进度为 `0`。

注意：当前实现只更新数据库状态，没有中断 `LocalTrainingRunnerService` 已启动的后台线程；后台线程仍可能继续运行并再次写回状态。前端可以展示停止请求结果，但不能据此认定计算进程已被强制终止。

### 10.5 回写训练结果

```http
POST /api/task/result?id={id}
```

`id` 可以是训练实验版本 ID，也可以是训练实验 ID。传实验 ID 时更新最新版本。

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | string | 否 | `pending`、`queued`、`running`、`success`、`failed`、`stopped` |
| `progress` | integer | 否 | `0` 到 `100` |
| `metrics` | object/string | 否 | 训练指标 JSON |
| `runId` | string | 否 | MLflow 或外部执行器运行 ID |
| `logPath` | string | 否 | 日志路径 |
| `outputPath` | string | 否 | 输出路径 |
| `errorMessage` | string | 否 | 错误信息 |
| `startedAt` | string | 否 | ISO-8601 时间 |
| `finishedAt` | string | 否 | ISO-8601 时间 |
| `remark` | string | 否 | 备注 |

前端注意事项：

- `metrics` 和 `hyperParams` 如果传字符串，必须是合法 JSON 文本。
- 未传 `progress` 但传了 `status` 时，后端会按状态生成默认进度：`success=100`、`running=50`，其他状态为 `0`。
- 回写接口依赖普通登录鉴权和资源所有权，没有单独的回调签名字段。

### 10.6 删除训练实验

```http
DELETE /api/task/delete?id={id}
```

`id` 可以是训练实验版本 ID，也可以是训练实验 ID。删除会删除同一 `experimentId` 下全部实验版本。

## 11. 训练实验版本接口

基础路径：`/api/experiments`

`/api/experiments` 提供更明确的实验版本管理接口。

### 11.1 创建实验

```http
POST /api/experiments
```

请求体与 `/api/task/create` 相同。

### 11.2 查询实验版本历史

```http
GET /api/experiments/{experimentId}/versions
```

返回该实验下当前用户可见的全部版本，按 `versionNo` 升序排列。

### 11.3 查询指定实验版本

```http
GET /api/experiments/{experimentId}/versions/{versionNo}
```

### 11.4 创建实验新版本

```http
POST /api/experiments/{experimentId}/versions
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 否 | 新版本名称；不传继承上一版本 |
| `modelVersionId` | string | 否 | 兼容模型版本 ID；不传继承上一版本 |
| `baseModelVersionId` | string | 否 | 基础模型权重版本 ID；不传继承上一版本，可与 `modelVersionId` 二选一，二者同时传入时必须一致 |
| `datasetVersionId` | string | 否 | 数据集版本 ID；不传继承上一版本 |
| `codeVersionId` | string | 否 | 代码版本 ID；不传继承上一版本 |
| `hyperParams` | object/string | 否 | 超参数 JSON；不传继承上一版本 |
| `params` | object/string | 否 | `hyperParams` 的兼容字段 |
| `remark` | string | 否 | 备注；不传继承上一版本 |

创建成功后 `versionNo` 为上一版本加 `1`，状态为 `pending`。新版本会继承上一版本的 `trainingProfile`，该接口不能切换训练方案；如果上一版本是 profile 任务，则新版本的 `codeVersionId` 仍必须指向已准入代码版本，并继续校验代码资产、数据集类型和基础模型权重版本。

### 11.5 更新实验版本超参数

```http
PUT /api/experiments/{experimentId}/versions/{versionNo}/hyper-parameters
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `hyperParams` | object/string | 是 | 超参数 JSON；也可使用 `params` |
| `params` | object/string | 否 | `hyperParams` 的兼容字段 |
| `remark` | string | 否 | 同时更新备注 |

该接口只修改当前实验版本记录，不会自动创建新版本，也不会重新启动训练。需要保留不同参数对应的训练历史时，应使用 `POST /api/experiments/{experimentId}/versions`。

### 11.6 更新实验版本训练结果

```http
PUT /api/experiments/{experimentId}/versions/{versionNo}/result
```

请求体与 `/api/task/result` 相同。

该接口精确定位 `experimentId + versionNo`，比 `/api/task/result?id={experimentId}` 更适合外部执行器回写，避免实验已新增版本后误更新最新版本。

## 12. 通用文件对象接口

基础路径：`/api/files`

通用文件接口主要用于调试或辅助能力。业务流程优先通过模型版本 ID、数据集版本 ID 引用文件。

### 12.1 MinIO 健康检查

```http
GET /api/files/health
```

当前该接口仍经过 `/api/**` 权限拦截，需要有效登录态。它只验证后端能否连接 MinIO，不检查 PostgreSQL、MLflow 或某个具体对象。

成功响应：

```json
{
  "success": true,
  "data": {
    "minio": "ok"
  },
  "errorMessage": null
}
```

### 12.2 上传文件对象

```http
POST /api/files/upload
Content-Type: multipart/form-data
```

表单参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | file | 是 | 文件 |
| `objectName` | string | 否 | 对象名；不传使用原始文件名 |

普通用户上传相对路径时，后端会自动补齐为：

```text
users/{当前用户ID}/files/{objectName}
```

如果普通用户传入的 `objectName` 已经以 `users/{当前用户ID}/` 开头，则保持该用户前缀下的路径。管理员通常可上传到任意合法对象路径，但所有角色都不能通过本接口写入归一化后的 `users/{owner}/codes` 或其任意后代；该命名空间由代码资产服务独占管理。

`objectName` 不能包含控制字符，也不能包含单独的 `.` 或 `..` 路径段。该接口不会创建 `model_asset`、`dataset_asset` 或版本记录，业务模型和数据集不要使用它代替专用上传流程。

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `objectName` | 实际对象名 |
| `size` | 文件大小 |
| `etag` | MinIO ETag |

### 12.3 下载文件对象

```http
GET /api/files/download?objectName={objectName}
```

成功时返回文件流，并设置 `Content-Disposition`。普通用户只能下载 `users/{当前用户ID}/...` 下的对象；管理员可下载全部对象。

前端应将查询参数进行 URL 编码，并使用 Blob 下载。该接口当前依赖 `objectName`，不是基于模型版本 ID 或数据集版本 ID 的稳定业务下载接口。

### 12.4 删除文件对象

```http
DELETE /api/files/delete?objectName={objectName}
```

删除不会同步阻塞删除 MinIO 对象，而是写入删除任务。

所有角色都不能通过本接口删除归一化后的 `users/{owner}/codes` 或其任意后代。`GET /api/files/download` 保持只读兼容，不受该写保护影响；普通 `users/{owner}/files/**` 行为不变。

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `objectName` | 对象名 |
| `minioDeleteQueued` | 是否已加入删除任务 |

### 12.5 MinIO 初始化与异步删除任务

服务启动时 `MinioInitializer` 会在 `ApplicationRunner.run()` 内确保配置的 bucket 存在。默认最多尝试 30 次，初始退避 1000 ms，指数增长且最大 30 秒；网络抖动或 MinIO 暂未就绪会进入该退避重试。以下错误快速失败，不做退避重试：`InvalidBucketName`、`InvalidAccessKeyId`、`SignatureDoesNotMatch`、`AccessDenied`。bucket 参数构造发生在重试循环前，因此非法 bucket 名称会在循环前失败，不会按运行时异常退避重试。

MinIO 删除是异步 best-effort 清理，不是业务删除接口的同步成功条件。删除任务行为：

- `PENDING` 任务每 60 秒批量调度一次，调度本身带数据库分布式锁。
- `PROCESSING` 超过 30 分钟未更新会重置为 `PENDING`。
- 目标对象已不存在时按删除成功处理。
- 单轮默认最多尝试 5 次；进入 `FAILED` 后最多允许 2 次失败重置，每次重置会把 `retryCount` 清 0 并递增 `failedResetCount`。因此同一任务默认最多 15 次删除尝试，超过后保持 `FAILED`，需要人工处理或重新创建删除任务。
- `minio_delete_task` 表和任务 ID 属于内部运维实现，不作为前端或其他模块的稳定契约。

## 13. 普通数据集预览接口

基础路径：`/api/dataset/preview`

普通数据集预览用于 CV/NLP 数据集内容查看。点云数据集仍使用 `/api/dataset/point-cloud/**`。

后端只接受 `datasetVersionId`，不接受客户端直接传 `storagePath` 或 MinIO `objectName`。后端会反查 `dataset_version`、`dataset_asset`，校验当前用户是否有权限访问该数据集版本和对象路径。普通用户只能查看自己的数据集，管理员可查看全部。

只有状态为 `READY` 或 `DEPRECATED` 的版本可预览；`DRAFT` 和 `ARCHIVED` 会被拒绝。

在线预览限制由配置项控制：

```yaml
dataset:
  preview:
    max-zip-entries: 10000
    max-text-bytes: 1048576
    max-image-bytes: 20971520
    max-page-size: 200
```

首版支持范围：

| 类型 | 支持在线预览 | 说明 |
| --- | --- | --- |
| CV zip 内图片 | 是 | `.jpg`、`.jpeg`、`.png`、`.bmp`、`.gif`、`.webp`、`.tif`、`.tiff` |
| CV zip 内标注文本 | 是 | `.txt`、`.json`、`.xml`、`.csv` |
| NLP 文本类文件 | 是 | `.txt`、`.json`、`.jsonl`、`.xml` |
| NLP CSV | 是 | 第一行作为 `columns`，后续行分页返回 |
| PDF / DOCX / XLS / XLSX | 否 | 文件清单中展示，提示下载后查看 |

### 13.1 查询普通数据集文件清单

```http
GET /api/dataset/preview/files?id={datasetVersionId}&page=1&pageSize=100&keyword=&kind=
```

查询参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 数据集版本 ID |
| `page` | number | 否 | 页码，默认 `1` |
| `pageSize` | number | 否 | 每页数量，默认 `100`，最大由 `dataset.preview.max-page-size` 控制 |
| `keyword` | string | 否 | 按文件路径、文件名、扩展名模糊过滤 |
| `kind` | string | 否 | `IMAGE`、`TEXT`、`TABLE`、`UNSUPPORTED` |

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `datasetVersionId` | 数据集版本 ID |
| `type` | `CV` 或 `NLP` |
| `fileName` | 原始数据集文件名 |
| `sourceArchive` | 原始文件是否为 zip |
| `page` / `pageSize` / `total` | 分页信息 |
| `files` | 文件清单 |

前端注意事项：

- zip 文件清单会扫描整个压缩包后再分页，`pageSize` 只控制返回数量，不减少后端扫描成本。
- `total` 是按 `keyword`、`kind` 过滤后的总数。
- zip entry 未声明大小时 `sizeBytes` 可能为 `null`，实际流式读取时仍会执行大小限制。

`files[]` 字段：

| 字段 | 说明 |
| --- | --- |
| `path` | zip 内相对路径；NLP 单文件数据集为 `null` |
| `fileName` | 文件名 |
| `extension` | 小写扩展名 |
| `kind` | `IMAGE`、`TEXT`、`TABLE`、`UNSUPPORTED` |
| `sizeBytes` | 文件大小；zip entry 无大小信息时可为 `null` |
| `previewAllowed` | 是否允许在线预览 |
| `previewUrl` | 可预览文件的后续预览 URL |
| `message` | 不可预览或受限时的提示 |

示例：

```json
{
  "success": true,
  "data": {
    "datasetVersionId": "dataset-ver-xxx",
    "type": "CV",
    "fileName": "images.zip",
    "sourceArchive": true,
    "page": 1,
    "pageSize": 100,
    "total": 2,
    "files": [
      {
        "path": "images/a.png",
        "fileName": "a.png",
        "extension": ".png",
        "kind": "IMAGE",
        "sizeBytes": 12345,
        "previewAllowed": true,
        "previewUrl": "/api/dataset/preview/image?id=dataset-ver-xxx&path=images%2Fa.png",
        "message": null
      },
      {
        "path": "labels/a.txt",
        "fileName": "a.txt",
        "extension": ".txt",
        "kind": "TEXT",
        "sizeBytes": 128,
        "previewAllowed": true,
        "previewUrl": "/api/dataset/preview/content?id=dataset-ver-xxx&path=labels%2Fa.txt",
        "message": null
      }
    ]
  },
  "errorMessage": null
}
```

### 13.2 预览文本或表格内容

```http
GET /api/dataset/preview/content?id={datasetVersionId}&path={zipEntryPath}&page=1&pageSize=100
```

规则：

- `id` 必须是数据集版本 ID。
- zip 数据集的 `path` 建议直接使用 `files[].path`；兼容传入 `\`，后端会规范化为 `/` 后匹配 ZIP entry。
- NLP 单文件数据集可省略 `path`。
- `.json` 会优先按 JSON 格式化；解析失败时按原文本返回。
- `.csv` 使用第一行作为 `columns`，后续数据行按 `page` / `pageSize` 分页返回，`rows` 只包含当前页。
- `.txt` 和 `.jsonl` 按行分页返回，`content` 只包含当前页行内容；`.jsonl` 会忽略空行。
- `.json` 和 `.xml` 保持结构化内容预览，不做分页，`pageable=false`。
- `pageable=true` 时返回 `total` 和 `totalPages`，前端可据此渲染分页器。
- 文本读取超过 `dataset.preview.max-text-bytes` 时返回前 N 字节，`truncated=true`。
- 文本、JSONL 和 CSV 都先按最大文本字节数截断；`truncated=true` 时 `total` 只代表本次可预览内容中的数量，不代表完整文件总行数。

文本响应示例：

```json
{
  "success": true,
  "data": {
    "path": "labels/a.txt",
    "fileName": "a.txt",
    "extension": ".txt",
    "contentType": "TEXT",
    "content": "0 0.5 0.5 1 1",
    "columns": null,
    "rows": null,
    "page": 1,
    "pageSize": 100,
    "pageable": true,
    "total": 1,
    "totalPages": 1,
    "truncated": false,
    "message": null
  },
  "errorMessage": null
}
```

CSV 响应示例：

```json
{
  "success": true,
  "data": {
    "path": "tables/data.csv",
    "fileName": "data.csv",
    "extension": ".csv",
    "contentType": "CSV",
    "content": null,
    "columns": ["name", "value"],
    "rows": [["a", "1"], ["b", "2"]],
    "page": 1,
    "pageSize": 100,
    "pageable": true,
    "total": 2,
    "totalPages": 1,
    "truncated": false,
    "message": null
  },
  "errorMessage": null
}
```

### 13.3 预览 CV 图片

```http
GET /api/dataset/preview/image?id={datasetVersionId}&path={zipEntryPath}
```

规则：

- 仅支持 `CV` zip 数据集内的图片文件。
- `path` 必须来自 `files[].path`。
- 成功时返回图片流，`Content-Disposition` 为 `inline`。
- 单张图片超过 `dataset.preview.max-image-bytes` 时拒绝在线预览。
- 该接口需要登录。若页面只在请求头保存 token，不能直接把 `previewUrl` 填入 `<img src>`；应先带鉴权头请求 Blob，再创建 `URL.createObjectURL(blob)`。

响应头示例：

```http
Content-Type: image/png
Content-Disposition: inline; filename*=UTF-8''a.png
```

### 13.4 安全与前端对接要求

- 前端必须先调用 `/api/dataset/preview/files`，再使用返回的 `previewUrl`。
- 前端不要直接依赖 `storagePath` 或 MinIO 对象路径。
- zip 内路径可以使用 `/` 或 `\`，后端统一规范化为 `/`；规范化后不能是绝对路径、盘符路径，不能包含 `..` 或空字节。
- PDF、DOCX、XLS、XLSX 首版不做在线解析，展示下载提示即可。
- `/image` 成功返回二进制流；参数错误通常返回 HTTP `400` JSON，权限错误按隐藏资源策略返回 HTTP `404` JSON。

## 14. 错误处理与安全规则

常见失败原因：

| 场景 | 典型 `errorMessage` |
| --- | --- |
| 未登录 | `请先登录: ...` |
| 无权访问资源 | `not found or no permission: ...` |
| 模型/训练任务类型非法 | `任务类型仅支持 CV, NLP, POINT_CLOUD, ROBOT` |
| 数据集任务类型非法 | `任务类型仅支持 CV, NLP, POINT_CLOUD, ROBOT, MULTIMODAL` |
| 多模态分组缺失 | `MULTIMODAL 数据集必须使用 sampleGrouping=MANIFEST 或 AUTO_DIRECTORY` |
| 非多模态使用 grouping | `仅 MULTIMODAL 数据集支持 sampleGrouping` |
| AUTO_DIRECTORY 传 manifestPath | `AUTO_DIRECTORY 不允许传 manifestPath` |
| strictManifest 使用范围非法 | `strictManifest 仅 MULTIMODAL + MANIFEST 支持` / `strictManifest 仅在 sampleGrouping=MANIFEST 时可用` |
| strictManifest 发现未声明 ZIP 文件 | `manifest 未声明 ZIP 文件: ...`，ImportJob `errorCode=INVALID_MANIFEST_UNDECLARED_ENTRY` |
| manifest 路径非法 | `manifestPath 非法` |
| 上传会话不存在或越权 | `uploadId invalid or not accessible` |
| 分片缺失 | `分片未上传完成` / `缺少分片: ...` |
| 版本重复 | `model version already exists for asset: ...` / `dataset version already exists for asset: ...` |
| 被训练实验引用 | `model version is referenced by training experiments` / `dataset version is referenced by training experiments` |
| 训练状态非法 | `status only supports pending, queued, running, success, failed, stopped` |
| 进度非法 | `progress must be between 0 and 100` |

安全规则：

- 创建资产和版本时，客户端传入的主键 `id` 不生效，后端会重新生成。
- 普通用户不能创建或修改模型/数据集版本的存储元数据。
- 普通用户只能访问自己 `ownerUserId` 下的资源和 `users/{ownerUserId}/...` 下的文件对象。
- 模型、数据集删除均为软删除；MinIO 对象通过删除任务异步清理。
- 模型版本或数据集版本被训练实验引用时，不允许删除。
- zip 内路径不能包含绝对路径、盘符、`..` 或空字节。

## 15. 点云三维预览接口

基础路径：`/api/dataset/point-cloud`

点云三维渲染由前端 Three.js、`PCDLoader`、`PLYLoader` 完成。后端只提供基于 `datasetVersionId` 的鉴权、预览元信息和点云文件流，不要求前端直接传 `storagePath` 或 MinIO 对象路径。

只有状态为 `READY` 或 `DEPRECATED` 的数据集版本允许点云预览；`DRAFT` 和 `ARCHIVED` 会被拒绝。

在线预览大小限制由配置项控制：

```yaml
point-cloud:
  preview:
    max-size: 209715200
```

默认单个可预览点云文件不超过 `200MB`。该限制只影响在线预览，不影响上传和原始文件下载。

### 15.1 查询点云预览信息

```http
GET /api/dataset/point-cloud/preview?id={datasetVersionId}
```

规则：
- `id` 必须是数据集版本 ID。
- 后端会查询 `dataset_version` 和 `dataset_asset`，并校验当前用户是否可访问该版本。
- 仅允许 `dataset_asset.type=POINT_CLOUD`。
- 原始文件为 `.pcd` 时返回 `format=PCD` 和单文件 `previewUrl`。
- 原始文件为 `.ply` 时返回 `format=PLY` 和单文件 `previewUrl`。
- 原始文件为 `.zip` 时，后端读取 zip 目录，只返回 zip 内 `.pcd` / `.ply` 文件列表，不直接把整个 zip 作为预览文件。
- zip 内单个 `.pcd` / `.ply` 超过预览大小限制时，该文件仍会出现在 `pointCloudFiles` 中，但 `previewAllowed=false`，`previewUrl=null`，并返回提示信息。
- zip entry 未声明大小时 `sizeBytes` 可能为 `null`，此时元信息阶段会暂时允许预览，但文件流接口仍会在实际解压读取超过 `200MB` 时中止。

单文件 `.pcd` 响应示例：

```json
{
  "success": true,
  "data": {
    "datasetVersionId": "dataset-ver-xxx",
    "fileName": "scan.pcd",
    "type": "POINT_CLOUD",
    "format": "PCD",
    "sizeBytes": 52428800,
    "previewSupported": true,
    "previewUrl": "/api/dataset/point-cloud/file?id=dataset-ver-xxx",
    "pointCloudFiles": null,
    "message": null
  },
  "errorMessage": null
}
```

zip 点云包响应示例：

```json
{
  "success": true,
  "data": {
    "datasetVersionId": "dataset-ver-xxx",
    "fileName": "pointcloud.zip",
    "type": "POINT_CLOUD",
    "format": "ZIP",
    "sizeBytes": 104857600,
    "previewSupported": true,
    "previewUrl": null,
    "pointCloudFiles": [
      {
        "path": "clouds/scan1.pcd",
        "fileName": "scan1.pcd",
        "format": "PCD",
        "sizeBytes": 12345678,
        "previewUrl": "/api/dataset/point-cloud/zip-file?id=dataset-ver-xxx&path=clouds%2Fscan1.pcd",
        "previewAllowed": true,
        "message": null
      }
    ],
    "message": "请选择 zip 内的点云文件进行预览"
  },
  "errorMessage": null
}
```

### 15.2 单文件点云流

```http
GET /api/dataset/point-cloud/file?id={datasetVersionId}
```

用于原始数据集文件就是 `.pcd` 或 `.ply` 的场景。成功时返回文件流：

- `Content-Type: application/octet-stream`
- `Content-Disposition: inline; filename*=UTF-8''...`

前端使用方式：

```ts
const metaResponse = await fetch(
  `/api/dataset/point-cloud/preview?id=${encodeURIComponent(datasetVersionId)}`,
  { headers: { Authorization: `Bearer ${token}` } },
);
const meta = await metaResponse.json();
if (!metaResponse.ok || !meta.success || !meta.data.previewSupported) {
  throw new Error(meta.errorMessage || meta.data?.message || '点云不可预览');
}

const fileResponse = await fetch(meta.data.previewUrl, {
  headers: { Authorization: `Bearer ${token}` },
});
if (!fileResponse.ok) {
  const error = await fileResponse.json();
  throw new Error(error.errorMessage || '点云文件读取失败');
}
const buffer = await fileResponse.arrayBuffer();

if (meta.data.format === 'PCD') {
  const points = new PCDLoader().parse(buffer, meta.data.previewUrl);
  scene.add(points);
} else if (meta.data.format === 'PLY') {
  const geometry = new PLYLoader().parse(buffer);
  // 前端根据 geometry 创建 Points 或 Mesh。
}
```

直接调用 `loader.load(previewUrl)` 时不会自动继承业务请求库中的 `Authorization` 拦截器。只有确认浏览器已携带同源有效 Cookie 时才能省略上述显式鉴权请求。

### 15.3 zip 内点云文件流

```http
GET /api/dataset/point-cloud/zip-file?id={datasetVersionId}&path={zipEntryPath}
```

用于原始数据集文件是 `.zip` 的场景。`path` 必须来自 `preview` 接口返回的 `pointCloudFiles[].path`，后端会再次校验：

- 原始数据集版本必须是 `POINT_CLOUD`。
- 原始文件必须是 `.zip`。
- `path` 不能是绝对路径，不能包含盘符，不能包含 `..`，不能包含空字节。
- `path` 对应的 zip entry 必须存在，且必须是 `.pcd` 或 `.ply`。
- 单个 entry 超过 `point-cloud.preview.max-size` 时拒绝在线预览。

成功时同样返回 `application/octet-stream` 文件流，并设置 `Content-Disposition: inline`。

该接口会把目标 zip entry 解压到后端临时文件，再以流方式返回；前端应避免同时并发加载大量大文件。

### 15.4 前端对接建议

前端先调用：

```http
GET /api/dataset/point-cloud/preview?id={datasetVersionId}
```

如果返回 `previewUrl`：
- `format=PCD` 时使用 `PCDLoader` 加载 `previewUrl`。
- `format=PLY` 时使用 `PLYLoader` 加载 `previewUrl`。

如果返回 `pointCloudFiles`：
- 前端展示 zip 内可预览点云文件列表。
- 用户选择某个 `previewAllowed=true` 的文件后，加载该文件的 `previewUrl`。
- `previewAllowed=false` 的文件展示 `message`，提示下载后本地查看。

如果 `previewSupported=false`：
- 前端展示 `message`。
- 可提供普通下载按钮，但业务预览流程不应直接依赖 `storagePath`。

点云文件流接口参数错误通常返回 HTTP `400` JSON；无权限按隐藏资源策略返回 HTTP `404` JSON。前端在调用 `arrayBuffer()` 前应先检查状态码和 `Content-Type`。

### 15.5 错误场景

| 场景 | 典型错误 |
| --- | --- |
| `id` 为空 | `datasetVersionId 不能为空` |
| 数据集版本不存在或越权 | `dataset version not found or no permission` |
| 非 `POINT_CLOUD` 数据集 | `点云预览仅支持 POINT_CLOUD 数据集` |
| 单文件不是 `.ply` / `.pcd` | `仅支持直接预览 .ply 或 .pcd 点云文件` |
| zip 内路径非法 | `zip entry path 非法` |
| zip 内文件不存在 | `zip 内点云文件不存在: ...` |
| 文件超过在线预览大小限制 | `文件过大，请下载后本地查看` |

### 15.6 接口测试示例

```powershell
# 查询单文件或 zip 点云预览信息
curl.exe -H "Authorization: Bearer <token>" `
  "http://localhost:8080/api/dataset/point-cloud/preview?id=<datasetVersionId>"

# 下载单文件 .pcd/.ply 预览流，响应头应为 inline
curl.exe -I -H "Authorization: Bearer <token>" `
  "http://localhost:8080/api/dataset/point-cloud/file?id=<datasetVersionId>"

# 下载 zip 内指定 .pcd/.ply 预览流，path 需要 URL 编码
curl.exe -I -H "Authorization: Bearer <token>" `
  "http://localhost:8080/api/dataset/point-cloud/zip-file?id=<datasetVersionId>&path=clouds%2Fscan1.pcd"

# 非 POINT_CLOUD 数据集调用 preview 应返回 success=false
curl.exe -H "Authorization: Bearer <token>" `
  "http://localhost:8080/api/dataset/point-cloud/preview?id=<cvOrNlpDatasetVersionId>"

# POINT_CLOUD 列表筛选仍使用原接口
curl.exe -H "Authorization: Bearer <token>" `
  "http://localhost:8080/api/dataset/list?type=POINT_CLOUD"
```

## 16. 数据集版本管理说明

本节补充当前数据集版本管理语义，适用于 `/api/dataset/upload/**`、`/api/dataset-assets/**` 和 `/api/dataset-versions/**`。

### 16.1 版本字段语义

| 字段 | 说明 |
| --- | --- |
| `datasetVersionId` / `id` | 数据集版本主键，训练、预览、点云文件流必须使用该字段引用具体版本。 |
| `assetId` | 数据集资产主键；创建新数据集时为空，给已有数据集新增版本时必须传入。 |
| `versionNo` | 后端生成的真实版本序号，同一 `assetId` 下从 `1` 递增。 |
| `versionLabel` | 展示用版本标签；旧字段 `version` 作为兼容别名继续返回。 |
| `description` | 当前版本说明。 |
| `changeLog` | 相比父版本的变更说明。 |
| `parentVersionId` | 当前版本来源版本；不传时默认使用资产当前版本。 |
| `status` | 版本状态：`DRAFT`、`READY`、`DEPRECATED`、`ARCHIVED`。 |
| `currentVersionId` | 数据集资产当前推荐版本。 |

`versionNo` 是唯一可信的版本序号；前端可以展示 `versionLabel` / `version`，但不要用展示标签做训练或预览引用。

### 16.2 上传到已有数据集

`POST /api/dataset/upload/init` 和 `POST /api/dataset/upload/folder` 现在支持以下可选字段：

| 字段 | 说明 |
| --- | --- |
| `assetId` | 传入时表示给已有数据集新增版本；不传时创建新数据集资产。 |
| `versionLabel` | 展示用版本标签；旧字段 `version` 继续兼容为别名。 |
| `description` | 当前版本说明。 |
| `changeLog` | 当前版本变更说明。 |
| `parentVersionId` | 来源版本；不传时使用资产 `currentVersionId`。 |
| `sampleGrouping` | 多模态上传传 `MANIFEST` 或 `AUTO_DIRECTORY`；其他类型不传。 |
| `manifestPath` | 仅 MANIFEST 使用，默认 `manifest.json`；可使用 `/` 或 `\` 并统一规范化为 `/`；AUTO_DIRECTORY 不传。 |
| `strictManifest` | 仅 `MULTIMODAL + MANIFEST` 可传 `true`；默认 `false`。 |

上传完成后：

- 新资产首个版本自动生成 `versionNo=1`，默认标签 `v1`。
- 已有资产新增版本自动生成下一个 `versionNo`，默认标签 `v{versionNo}`。
- CV/NLP/POINT_CLOUD/ROBOT 上传完成后版本为 `READY`，并自动成为该资产的 `currentVersionId`。
- MULTIMODAL 上传完成后版本为 `DRAFT`，创建 `PENDING` ImportJob，不更新 `currentVersionId`。
- `parentVersionId` 必须属于同一资产、未删除且状态为 `READY`。
- MinIO 路径使用 `users/{userId}/datasets/{assetId}/v{versionNo}/{fileName}`。

### 16.3 当前版本和版本历史

`GET /api/dataset/list` 返回每个数据集资产的当前推荐版本。新增字段包括：

| 字段 | 说明 |
| --- | --- |
| `currentVersionId` | 当前推荐版本 ID。 |
| `currentVersionNo` | 当前推荐版本序号。 |
| `currentVersionLabel` | 当前推荐版本展示标签。 |
| `versionStatus` | 当前推荐版本状态。 |
| `versionDescription` | 当前推荐版本说明。 |

兼容字段 `versionId` 和 `version` 分别映射到当前推荐版本 ID 和展示标签。

`/api/dataset/list` 会返回最新 DRAFT 的 `latestDraftVersionId`、`importJobId`、`importStatus`、`importProgress` 和 `importErrorMessage`。新建 MULTIMODAL 资产只有 DRAFT、尚无 READY 当前版本时，`versionId`、`currentVersionId`、`versionStatus` 等当前版本字段仍为 `null`，前端通过新增导入字段展示进度。

`GET /api/dataset-versions?assetId={assetId}` 返回该资产下未删除版本历史，指定 `assetId` 时按 `versionNo` 降序返回。

### 16.4 当前版本切换和状态流转

切换当前推荐版本：

```http
PUT /api/dataset-assets/{assetId}/current-version
Content-Type: application/json

{ "versionId": "dataset-ver-xxx" }
```

只能切换到同一资产下、未删除、`status=READY` 的版本。

更新版本状态：

```http
PATCH /api/dataset-versions/{id}/status
Content-Type: application/json

{ "status": "READY" }
```

状态规则：

- 手工创建版本只允许管理员创建 `DRAFT` 元数据版本，且不能携带 `fileName`、`storagePath`、`sizeBytes`。
- 文件元数据只能由数据集上传流程写入，不能通过数据集版本 CRUD 修改。
- `READY` 版本必须具备 `storagePath`、`fileName`、`sizeBytes`。
- 状态更新接口只允许更新为 `READY`、`DEPRECATED`、`ARCHIVED`，不能把版本改回 `DRAFT`。
- 当前版本不能直接改为 `DEPRECATED` 或 `ARCHIVED`，需先切换 `currentVersionId`。
- 被训练实验引用的版本仍然不能删除。
- 当前版本不能删除。

训练创建和实验新版本创建仍然必须传 `datasetVersionId`，且目标版本必须是 `READY` 并具备存储路径。普通多模态样本查询和文件访问同样只允许未删除的 `READY` 版本；`DRAFT`、`DEPRECATED`、`ARCHIVED` 均不进入该查询语义。

### 16.5 DRAFT 工作区与 READY 不可变规则

- `READY` 是稳定快照，后续 Sample/Data/Annotation 维护不得直接写入 READY。
- 样本维护服务必须先调用 `DatasetVersionLifecycleService.assertMutableDraftVersion(versionId)`。
- 需要稳定快照的内部流程可调用 `assertReadyVersion(versionId)`。
- `POST /api/dataset-versions/{readyVersionId}/draft` 物化复制父 READY 的未删除样本元数据，并复用父版本 package 关系，不复制 ZIP；ZIP-backed 单模态旧版本会先从父 ZIP 建立 PRIMARY package 和 Sample/Data 元数据。
- DRAFT 使用 workspace 专用接口查询、软删除和恢复样本；普通样本接口仍只接受 READY。
- APPEND ZIP 通过独立 package init/complete 接口加入同一 DRAFT，导入成功只扩充工作区，不更新 `currentVersionId`。
- `POST /api/dataset-versions/{draftVersionId}/publish` 完成完整性校验后把工作区原地发布为新 READY，并在事务成功时更新 `currentVersionId`。
- 父 READY 的样本、package 关系和版本状态不会被工作区编辑、APPEND 或 publish 事务修改。
- `/api/dataset/list` 通过 `latestDraftVersionId` 展示最新工作区；发布后该字段不再返回已发布版本，`currentVersionId` 指向新 READY。
- 同一资产只允许一个未删除 DRAFT。已有上传导入 DRAFT 或工作区 DRAFT 时，新建工作区都会被拒绝，避免混淆两种用途。
- 工作区 DRAFT 不能通过通用版本状态 PATCH 直接变为 READY，必须使用独立 publish 接口。
- workspace 样本删除、恢复和 publish 不直接物理删除 package ZIP。
- 当前定时维护每小时扫描失败超过 7 天的 ImportJob，并软删除其 DRAFT。package-backed ImportJob 会先通过 package cleanup planner 做 dry-run 级引用检查，确认没有未删除 DatasetVersion、current READY、父 READY/子 DRAFT 共享 PRIMARY、训练实验、活动 ImportJob 或活动 Sample/Data/Annotation 引用后，才显式写入 `MinioDeleteTask`；legacy 无 package 数据才回退到 `DatasetVersion.storagePath` 的共享路径保护。
- workspace 样本删除、恢复和 publish 不直接物理删除 package ZIP；普通版本删除和失败 DRAFT 清理只负责安全入队，不直接调用 MinIO delete。

## 17. 多模态与工作区数据管理当前范围

当前后端已经提供多模态数据上传和持久化导入能力：

- 数据集类型新增 `MULTIMODAL`，但共享模型/训练任务类型没有新增该值。
- 支持 `MULTIMODAL + MANIFEST/AUTO_DIRECTORY` 分片上传、动态 chunkSize、DRAFT 版本和异步 ImportJob。
- AUTO_DIRECTORY 根据 ZIP 根级样本目录生成内存 `ManifestImportPlan`，支持初始导入与 DRAFT APPEND，不改写 ZIP、不落地生成 manifest。
- 支持可选 `strictManifest` 严格模式；默认 `false`，仅 `MULTIMODAL + MANIFEST` 可启用，未声明普通 ZIP entry 会使 ImportJob 失败并写入结构化错误。
- 已建立 DatasetSample、DatasetSampleData、DatasetAnnotation 和 ImportJob 持久化模型。
- ImportJob 状态接口已经可用，并按数据集资产归属校验权限。
- ImportJob 支持受控 `FAILED -> PENDING` 的 `FULL` 重试；非 `FAILED`、非 `DRAFT`、已有持久样本或同版本存在活动 ImportJob 时会被拒绝，且 FULL 不扩展到 `PARTIAL`。
- 支持 `PARTIAL -> PENDING` 的 `INCREMENTAL` 重试；只重跑 failure row 中未解决样本，复用记录的 `externalId` 和原始 `sampleIndex`。
- 支持 ZIP central directory range 读取、manifest 解析校验及 Sample/Data/Annotation 样本级事务导入；单样本事务失败时样本、Data 和 Annotation 全部回滚，failure row 可靠保留。
- 支持 executorId fencing、heartbeat、PENDING/RUNNING 恢复和卡在 COMPLETING 的上传会话恢复；ImportJob 恢复、上传恢复、MinIO 删除任务和数据集生命周期维护均使用数据库分布式锁，启动恢复路径也走同一加锁入口。
- 导入成功后版本推进为 READY，并按版本号更新 `currentVersionId`；失败后版本保持 DRAFT。
- 定时维护每小时处理失败超过 7 天的 DRAFT；仅当没有其他未删除版本共享其 `storagePath` 时，才排队清理 MinIO 对象。软删除超过 30 天、不是当前版本且无父版本/训练引用的版本会物理删除。
- MinIO 删除任务是有界异步重试：默认单轮 5 次，FAILED 后最多 2 次重置，总计最多 15 次尝试；对象已不存在视为成功。
- 支持 READY 版本的样本分页、样本详情和样本 Data 列表查询，响应不暴露 MinIO/ZIP 定位字段。
- 支持按单个 `externalId` 跨多个 READY DatasetVersion 查询样本、Data 和 Annotation；legacy 和 V2 路径均已提供，响应只包含稳定字段和固定 preview/download 链接。
- 支持通过 ZIP Entry Index 和 MinIO range 读取样本 Data preview/download 及 Annotation download。
- 支持 STORED VIDEO 的单段 HTTP Range preview，DEFLATED VIDEO 仅支持 download。
- 支持 `dataset_package` / `dataset_version_package`，一个版本可按 PRIMARY + APPEND 顺序使用多个物理 ZIP；PRIMARY 和 APPEND package 都支持随 ImportJob 进入 `PARTIAL`，但 `PARTIAL` package 不可 publish。
- 支持基于 READY 创建 DRAFT，并物化继承 Sample/Data/Annotation；复用 package，不复制 ZIP，不改变当前 READY。
- ZIP-backed 单模态旧版本创建 DRAFT 时可从父 ZIP 建立 PRIMARY package 和一文件一样本的 Sample/Data 元数据；可唯一判定的非 ZIP 来源会在新 DRAFT 懒规范化为 RAW，歧义来源返回稳定 blocker。
- 支持向 DRAFT 追加 ZIP，执行样本、数据组件和标注组件 CRUD，预览/下载 DRAFT 的 ZIP/RAW 文件，编辑版本元数据、放弃工作区并发布为新的 READY 快照；多模态 APPEND 使用 `MANIFEST/AUTO_DIRECTORY`，单模态 APPEND 禁止 `sampleGrouping/manifestPath/strictManifest`。
- 支持 workspace append-only 审计日志：记录创建 DRAFT、APPEND init/complete、ImportJob 成功/失败/PARTIAL、样本删除/恢复、publish、FULL retry 和 INCREMENTAL retry；查询接口做 owner 权限校验，不返回存储定位字段。
- 发布后 `currentVersionId` 指向新 READY，父 READY 保持不变，软删除样本不进入正式查询和文件访问。
- 已提供 READY/DRAFT 生命周期断言，统一拒绝对 READY 的直接样本修改。
- MULTIMODAL complete 不执行现有 CV/NLP/POINT_CLOUD/ROBOT zip 白名单和全量解压校验；单模态 APPEND complete 继续执行对应任务类型的 ZIP 安全与文件白名单校验。

### 17.1 后续增强（E0-G 不实现）

- package 清理的前端入口、独立对外 API 和自动批量清理策略。
- 前端对接。

因此，只有 `SUCCESS` 或 `SUPERSEDED` 的 ImportJob/package 才能通过 workspace publish 进入 READY；`PARTIAL` 只保留在 DRAFT 工作区和导入状态语义中，不能进入正式样本查询、文件访问或训练消费语义。
训练直接接入、多模态 batch 组装、训练输入选择、模型/数据集适配策略以及修改共享 `TaskType` 都不属于数据管理后续；模块二只向训练侧稳定交付 READY `datasetVersionId` 和 consumer manifest。

## 18. V2 用户型门面

`/api/v2` 用于普通业务页面，目标是由后端聚合版本、DRAFT、package、ImportJob 和存储细节。原有接口继续保留，前端可渐进迁移。

V2 成功响应直接返回强类型 DTO 或分页对象。请求已经通过鉴权拦截器并进入 V2 Controller 后，业务异常、参数异常和未预期异常使用以下失败响应：

```json
{
  "success": false,
  "errorCode": "DUPLICATE_SAMPLE",
  "errorMessage": "上传内容包含已存在的样本",
  "details": {
    "sampleName": "scene-001"
  },
  "traceId": "7f7f02e0270d4e60bc0de6005dcdb64e"
}
```

上述 V2 错误响应头同时返回 `X-Trace-Id`。未登录时，请求先由
`PermissionInterceptor` 返回 HTTP `401` 和模块一 `Result` 格式，不包含 V2
`errorCode`、`traceId` 或 `X-Trace-Id`。已经登录但缺少 `/api/v2/admin/**`
管理员权限时，管理员拦截器抛出的异常由 V2 异常处理器转换为 HTTP `403` 的
`V2ErrorResponse`，`errorCode=CODE_APPROVAL_FORBIDDEN`，并带 `X-Trace-Id`。V2
不向前端返回 MinIO objectName、ZIP offset、堆栈或内部异常类名。

常用 HTTP 状态：

| 状态 | 含义 |
| --- | --- |
| `400` | 请求参数或 JSON 格式错误 |
| `401` | 未登录；响应体为模块一 `Result` |
| `403` | V2 管理能力拒绝；响应体为 `V2ErrorResponse`，典型错误码为 `CODE_APPROVAL_FORBIDDEN` |
| `404` | 资源不存在或无权限 |
| `409` | 版本、样本或编辑状态冲突 |
| `422` | 目录、Manifest、导入或发布完整性错误 |
| `500` | 未预期服务错误 |

ImportJob 新增 `errorCode` 和结构化错误详情落库字段。MANIFEST 校验详情固定包含
`field/reason`，可定位时增加 `externalId/sampleIndex/path`，非法 JSON 可增加
`line/column`。V2 上传状态、数据集列表、工作区和 ImportJob 状态接口统一把该内容映射到
`userError.details`；不返回对象存储路径、堆栈或内部异常类名。`errorMessage` 只保存
用户可读信息，原始技术异常仅写服务端日志。

### 18.1 数据集列表

```http
GET /api/v2/datasets?type=MULTIMODAL&keyword=&page=1&pageSize=20
```

`current` 的优先级高于 `page`；页码无效时使用 1，`pageSize` 默认 20、最大 200。
`keyword` 与 legacy 数据集列表保持一致，仅按数据集显示名称做不区分大小写的子串搜索。

分页项字段：

| 字段 | 说明 |
| --- | --- |
| `datasetId` | 数据集资产 ID |
| `name` / `type` | 页面展示名称和类型 |
| `currentVersion` | 当前 READY 摘要：`versionId`、`versionLabel`、`versionNo`、`status` |
| `currentVersionFileCount` | 当前 READY 文件数；无当前版本或计数不可用时为 `null` |
| `fileCount` | 当前 READY 文件数；兼容前端旧列名，语义与 `currentVersionFileCount` 一致 |
| `displayStatus` | `EMPTY`、`READY`、`EDITING`、`IMPORTING`、`IMPORT_FAILED` 或 `IMPORT_PARTIAL` |
| `hasDraft` | 是否存在活动 DRAFT |
| `workspaceId` | 活动 DRAFT 对应的版本工作区 ID |
| `workspaceRevision` | 活动工作区当前 revision；没有工作区时为 `null` |
| `importProgress` | 最新导入进度 |
| `publishReadiness` | `{canPublish,evaluatedRevision,blockers[]}`；没有工作区时为 `null` |
| `editability` | 当前 READY 是否可派生工作区，以及历史来源歧义等稳定 blocker |
| `availableActions` | `VIEW`、`PREVIEW`、`CREATE_WORKSPACE`、`OPEN_WORKSPACE`、`ADD_DATA`、`PUBLISH` 的可用子集 |
| `userError` | 导入失败时的结构化用户错误 |

状态优先级：

```text
IMPORTING > IMPORT_PARTIAL / IMPORT_FAILED > EDITING > READY > EMPTY
```

该接口不返回 `storagePath`、`ownerUserId`、`currentVersionId`、`latestDraftVersionId`、`importJobId` 或原始技术错误。

### 18.2 数据集版本工作区

版本工作区可以从资产当前 READY 或显式选择的历史 READY 派生 DRAFT；已有活动 DRAFT
时，仅在基线兼容时幂等返回同一个工作区。它编辑的是“下一版本”，不会修改父 READY。
V2 不再提供 `dataset-edit-sessions` 路由别名，前端必须按后述路径同批迁移。

| 接口 | 说明 |
| --- | --- |
| `POST /api/v2/dataset-uploads/init` | 初始化首次数据集 ZIP 上传；请求字段兼容原上传 init |
| `POST /api/v2/datasets/{datasetId}/workspaces` | 创建或继续该资产的版本工作区；可选请求体携带 `baseVersionId`、`versionLabel` |
| `GET /api/v2/datasets/{datasetId}/version-allocation` | 预检下一内部序号、默认标签及可选请求标签的占用情况 |
| `GET /api/v2/dataset-workspaces/{workspaceId}` | 查询工作区、活动任务和发布 readiness |
| `PATCH /api/v2/dataset-workspaces/{workspaceId}` | 以 Merge Patch 修改版本元数据 |
| `DELETE /api/v2/dataset-workspaces/{workspaceId}` | 放弃工作区；重复调用幂等 |
| `GET /api/v2/dataset-workspaces/{workspaceId}/readiness` | 在指定 revision 上执行完整发布前检查 |
| `POST /api/v2/dataset-workspaces/{workspaceId}/publish` | 在相同锁和 revision 下重新检查并发布 |
| `POST /api/v2/dataset-workspaces/{workspaceId}/file-uploads` | 初始化数据/标注组件的 `CREATE` 或 `REPLACE` 上传 |
| `POST /api/v2/dataset-workspaces/{workspaceId}/package-uploads` | 初始化追加 ZIP 上传 |
| `POST /api/v2/dataset-uploads/{uploadId}/chunks` | 首次、组件和追加 ZIP 上传共用的分片入口；multipart 参数为 `partIndex`、`file` |
| `GET /api/v2/dataset-uploads/{uploadId}` | 查询上传及异步导入的聚合进度 |
| `POST /api/v2/dataset-uploads/{uploadId}/complete` | 完成上传；工作区上传请求体必须带 `expectedWorkspaceRevision` |
| `POST /api/v2/dataset-uploads/{uploadId}/cancel` | 取消上传；工作区上传请求体必须带 `expectedWorkspaceRevision` |
| `GET /api/v2/dataset-versions/{datasetVersionId}/workspace/audit-logs` | 查询 workspace 审计日志；内部回溯用途，不作为训练侧消费契约 |

创建工作区可省略请求体，也可传 `{}`、JSON `null` 或：

```json
{
  "baseVersionId": "dataset-ver-v1",
  "versionLabel": "1.0.3"
}
```

省略 `baseVersionId` 时，仅在新建工作区场景默认使用资产当前 READY。显式基线必须属于
该资产、未删除且为 READY；返回的 `baseVersion`、数据库 `parentVersionId` 和物化的
样本/组件/package 关系都来自所选版本，不会静默回退到当前版本。创建事务还会在内部保存
当时锁定的 `currentVersionId` 作为 head 快照，该内部字段不对前端暴露。

显式标签会先 trim，长度必须为 1–64；显式空白或超长返回
`400 / INVALID_VERSION_LABEL`，大小写比较规则保持现状。未传标签时，后端在资产行锁内按历史最大 `versionNo` 分配下一内部序号；普通软删除版本仍计入历史，只有“软删除且状态为 `ABANDONED`”的工作区审计墓碑会被忽略。后端据此生成默认标签
`v{nextVersionNo}`；若该默认标签已被任一历史版本占用，则继续向后寻找。最终标签会在首次保存时同时写入 `version` 和 `versionLabel`，V2 前端不需要再调用 Legacy PUT。

已有活动 DRAFT 时，省略基线或显式传相同基线可继续；显式传不同基线返回
`409 / WORKSPACE_BASE_CONFLICT`，`details` 包含
`workspaceId/activeBaseVersionId/requestedBaseVersionId`。无标签或与现有工作区一致的
标签会幂等返回原工作区；不同标签返回
`409 / DATASET_VERSION_LABEL_CONFLICT`，`details` 额外包含
`workspaceId/currentVersionLabel`。

基线错误语义：

| HTTP | `errorCode` | 场景 |
| --- | --- | --- |
| `400` | `INVALID_BASE_VERSION_ID` | 显式 `baseVersionId` 为空白 |
| `404` | `DATASET_NOT_FOUND` | 基线不存在、已删除、属于其他资产或无权访问 |
| `409` | `WORKSPACE_BASE_CONFLICT` | 已有活动工作区使用不同基线 |
| `422` | `BASE_VERSION_NOT_READY` | 同资产基线存在但不是 READY |

标签预检示例：

```http
GET /api/v2/datasets/dataset-xxx/version-allocation?versionLabel=1.0.2
```

```json
{
  "nextVersionNo": 3,
  "defaultVersionLabel": "v3",
  "requestedVersionLabel": "1.0.2",
  "requestedVersionLabelAvailable": false,
  "unavailableReason": "DELETED_VERSION_RESERVED"
}
```

`unavailableReason` 只可能为 `ACTIVE_VERSION_EXISTS` 或
`DELETED_VERSION_RESERVED`，不返回已删除版本 ID。不传 `versionLabel` 时仅返回
`nextVersionNo/defaultVersionLabel`；预检只用于界面提示，POST 会在事务和资产行锁内重新校验。READY、DEPRECATED、ARCHIVED 等普通软删除历史版本继续永久占用标签和内部序号，并返回 `DELETED_VERSION_RESERVED`；只有放弃工作区产生的 ABANDONED 审计墓碑会释放原 `versionLabel` 和 `versionNo`。版本列表继续过滤所有软删除记录。

标签占用或并发唯一冲突统一返回 `409 / DATASET_VERSION_LABEL_CONFLICT`：

```json
{
  "errorCode": "DATASET_VERSION_LABEL_CONFLICT",
  "details": {
    "reasonCode": "DELETED_VERSION_RESERVED",
    "requestedVersionLabel": "1.0.2",
    "nextVersionNo": 3,
    "defaultVersionLabel": "v3"
  }
}
```

工作区 DTO 的固定字段为：

| 字段 | 说明 |
| --- | --- |
| `workspaceId` / `datasetId` | 工作区和所属资产 ID |
| `baseVersion` / `targetVersion` | 父 READY 与目标 DRAFT 的版本摘要 |
| `status` / `workspaceRevision` | 生命周期状态和当前并发 revision |
| `sampleCount` | 未删除样本数 |
| `activeOperation` | 当前活动上传/导入摘要 `{type,id,status,progress}`；`type=UPLOAD` 时 `id` 为 `uploadId`，`type=IMPORT` 时 `id` 为 `importJobId`；没有活动任务时为 `null` |
| `publishReadiness` | `{canPublish,evaluatedRevision,blockers[]}` |
| `availableActions` | 当前允许的操作集合 |
| `userError` | 最新 ImportJob 为 `FAILED` 或 `PARTIAL` 时的结构化用户错误；不包含 `importJobId`，其他状态为 `null` |

发布成功直接返回：

| 字段 | 说明 |
| --- | --- |
| `datasetId` | 数据集资产 ID |
| `currentVersion` | `{versionId,versionLabel,versionNo,status}`；`versionLabel` 是创建时实际持久化的标签，不根据 `versionNo` 临时拼接 |
| `publishedAt` | 发布时间 |

工作区内除只读查询外的所有命令都必须携带 `expectedWorkspaceRevision`；Merge Patch 将它作为控制字段置于 patch 对象中。revision 过期返回 `409 / WORKSPACE_REVISION_CONFLICT`，`details` 包含 expected/current revision。初始化、完成、取消、ImportJob 终态和任一资源修改均递增 revision；分片上传本身不递增。

上传或 ZIP 导入活动期间实行单写者模式。除查询、轮询、取消和放弃以外的工作区命令返回 `409 / WORKSPACE_BUSY`。服务端统一按“资产 → 工作区 → 资源/上传任务”顺序加锁。

#### 18.2.1 样本和组件

样本接口：

| 接口 | 说明 |
| --- | --- |
| `GET/POST /api/v2/dataset-workspaces/{workspaceId}/samples` | 分页列出或创建样本 |
| `GET/PATCH/DELETE .../samples/{sampleId}` | 查询、Merge Patch 或软删除样本 |
| `POST .../samples/{sampleId}/restore` | 恢复样本 |

创建样本时 `externalId` 必填，创建后不可修改；`sampleIndex` 由服务端单调分配且不复用。样本 Patch 只接受 `tags`、`metadata` 和控制字段 `expectedWorkspaceRevision`。

数据组件位于 `.../samples/{sampleId}/data`，标注组件位于 `.../samples/{sampleId}/annotations`：

| 操作 | 路径与语义 |
| --- | --- |
| 创建小文本 | `POST .../data` 或 `POST .../annotations` |
| 查询、修改描述、软删除 | `GET/PATCH/DELETE .../{resourceId}` |
| 替换小文本 | `PUT .../{resourceId}/content` |
| 恢复 | `POST .../{resourceId}/restore` |
| 预览/下载 | `GET .../{resourceId}/preview`、`GET .../{resourceId}/download` |

数据 Patch 仅接受 `dataType/sensor/channel/seq/format/fileName/contentType/metadata`；标注 Patch 仅接受 `sampleDataId/annotationType/format/fileName/contentType/metadata`。工作区 Patch 仅接受 `description/changeLog/cvTaskType/annotationFormat`。`sizeBytes/checksum/packageId/ZIP offsets` 由服务端生成，不能通过 Patch 覆盖。仍被有效标注引用的数据组件不能删除，返回 `409 / RESOURCE_IN_USE`。

内联文本上限为 1 MiB，只接受 `.txt/.json/.jsonl/.xml/.csv/.yaml/.yml`，并校验 UTF-8、NUL、扩展名、MIME、format 和基础语法；XML 禁用外部实体，YAML 使用安全解析。大文件和二进制文件通过 `file-uploads` 创建或替换，成功后生成不可变 RAW 对象和 `OVERLAY` 包，不修改原 ZIP。

ZIP 与 RAW 均可在 DRAFT 预览和下载。所有读取执行 owner、工作区、样本和资源归属校验，不返回 `storagePath`、objectName 或 ZIP offset；RAW 数据预览支持 Range。

#### 18.2.2 Readiness、发布与放弃

`publishReadiness` 固定为：

```json
{
  "canPublish": false,
  "evaluatedRevision": 12,
  "blockers": [
    {
      "code": "ACTIVE_UPLOAD",
      "message": "工作区仍有活动上传",
      "resourceType": "UPLOAD",
      "resourceId": "upload-xxx"
    }
  ]
}
```

数据集列表、工作区详情和发布使用同一套 readiness 规则，覆盖空样本、上传/导入状态、包关系与顺序、重复 ID/索引、数据—标注关联、文件描述、版本元数据和版本谱系。所选父版本可以是历史 READY；发布在同一锁和 revision 下重新检查资产 current 是否仍等于工作区创建时的 head 快照。head 漂移返回 `409 / BASE_VERSION_STALE`，不会静默改用新 current；其他业务阻塞返回 `422 / DATASET_NOT_PUBLISHABLE`。列表通过数据库存在性聚合计算等价的 `canPublish`，每种 blocker code 至多返回一项，`resourceType/resourceId` 可以为 `null`；工作区详情和发布复检可返回具体资源 blocker。因此无状态变化时，某 revision 的 `canPublish=true` 保证发布不会再因同一业务规则返回 422。

发布会物理清除草稿内已软删除的样本和组件，解除无引用的工作区独占 `OVERLAY` 包并排队清理对象，然后将 DRAFT 转为不可变 READY。放弃会把工作区转为 `ABANDONED` 并软删除为内部审计墓碑，上传转为 `DISCARDED`、导入转为 `SUPERSEDED`，只清理工作区独占对象并立即释放资产的活动 DRAFT 约束、原版本标签和内部序号。放弃后的目标版本不再出现在版本列表、版本详情或工作区读取中；使用原 `workspaceId` 重复 DELETE 仍幂等返回原 `ABANDONED` 结果。墓碑及已有审计行不会被生命周期清理物理删除。

历史非 ZIP READY 版本在创建 DRAFT 时懒规范化：来源可唯一推导时，仅在新工作区中创建或复用 RAW 主包并补齐资源引用，不修改父 READY；无法唯一推导时返回 `DATASET_WORKSPACE_SOURCE_AMBIGUOUS`，列表会在 `editability.blockers` 中预先展示。

首次上传 init 示例：

```json
{
  "fileName": "dataset.zip",
  "fileSize": 104857600,
  "fileFingerprint": "sha256:...",
  "datasetName": "Road Scenes",
  "type": "MULTIMODAL"
}
```

`MULTIMODAL` 仍只接受 ZIP 压缩包，不增加文件夹直传。未传
`sampleGrouping` 时后端默认使用 `AUTO_DIRECTORY`，普通用户不需要提供
`manifest.json`；高级兼容场景可以显式使用 `MANIFEST`。`strictManifest`
默认 `false`，只有 `MANIFEST` 场景可设为 `true`；启用后未声明的普通
ZIP entry 会以结构化 `userError` 暴露给 V2 调用方。

package APPEND init 请求：

```json
{
  "fileName": "append.zip",
  "fileSize": 104857600,
  "fileFingerprint": "sha256:..."
}
```

单模态 APPEND 必须省略 `sampleGrouping`、`manifestPath` 和 `strictManifest`，并按数据集任务类型校验 ZIP 内容。`MULTIMODAL` APPEND 可继续传 `sampleGrouping=AUTO_DIRECTORY` 或 `MANIFEST`；未传时默认 `AUTO_DIRECTORY`。只有 `MANIFEST` 接受 `manifestPath`，未传时默认 `manifest.json`；`manifestPath` 可使用 `/` 或 `\` 并统一规范化为 `/`；`AUTO_DIRECTORY` 禁止传 `manifestPath`，也不支持 `strictManifest=true`。

上传响应统一返回 `uploadId`、分片进度、`datasetId`、可选
`workspaceId`、`workspaceRevision`、`versionLabel`、`displayStatus`、`importProgress`、
`importJobId` 和 `userError`。`importJobId` 只用于导入状态查询和失败重试；
不返回 `storagePath`、owner ID、内部 package ID、MinIO objectName 或 ZIP
offset。

上传响应的 `displayStatus` 可能为 `UPLOADING`、`PROCESSING`、`UPLOAD_FAILED`、`IMPORTING`、`IMPORT_FAILED`、`IMPORT_PARTIAL` 或 `READY`；它与 18.1 数据集列表的聚合状态集合不同。首次上传完成失败时 `UPLOAD_FAILED` 对应原始 `status=FAILED`，`userError` 返回稳定错误码、用户可读消息和安全阶段信息；保留原 `uploadId` 与分片用于重试。

工作区 DTO 不直接返回 `importJobId`。它只在上传或导入状态为活动状态时返回
`activeOperation`：`type=UPLOAD` 时 `id` 是 `uploadId`，`type=IMPORT` 时 `id`
是 `importJobId`。最新 ImportJob 进入 `FAILED` 或 `PARTIAL` 后，
`activeOperation` 为 `null`，工作区仅通过 `userError` 展示结构化错误，而
`userError` 不包含任务 ID。V2 数据集列表同样不返回 `importJobId`。

因此需要失败重试的调用方必须保留上传完成或上传轮询响应中的 `importJobId`。V2
重试接口为 `POST /api/v2/import-jobs/{importJobId}/retry`，请求体为
`{"mode":"FULL|INCREMENTAL","expectedWorkspaceRevision":12}`，成功后返回
`V2ImportJobStatusDto`；重试结果会返回新的 `workspaceRevision`，活动导入随后可在
工作区的 `activeOperation` 中观察。`PARTIAL` 状态的查询结果返回
`displayStatus=IMPORT_PARTIAL`、`retryable=true`、`retryModes=["INCREMENTAL"]`。

Legacy `/api/dataset-versions/{draftVersionId}/workspace/samples` 和 `/api/dataset-samples/{sampleId}/workspace/**` 暂时保留 `ApiResponse` 包装并标记 deprecated；其删除/恢复命令复用工作区锁和 revision 核心。新前端不得再混用这些接口。

前端迁移最小流程为：从列表读取 `workspaceId/workspaceRevision/publishReadiness`；需要提示标签占用时先调用 `GET .../version-allocation`；没有工作区时把用户标签直接放入 `POST .../workspaces`，不得再调用 Legacy PUT 修改标签；每次命令使用上一响应的新 revision；遇到 revision 或标签冲突重新获取提示和工作区；只根据 `publishReadiness.blockers` 呈现发布状态；发布后以 `currentVersion.versionId` 更新选择，并展示响应中的实际 `currentVersion.versionLabel`。界面文案使用“创建/继续版本工作区”，不再使用“编辑当前版本”。

工作区修改实时持久化，不提供没有实际保存行为的“保存草稿”接口。

### 18.3 统一预览描述

```http
GET /api/v2/dataset-versions/{versionId}/preview
```

示例：

```json
{
  "datasetVersionId": "dataset-ver-xxx",
  "mode": "ARCHIVE",
  "capabilities": ["LIST_FILES", "PREVIEW_CONTENT", "PREVIEW_IMAGE"],
  "links": {
    "items": "/api/dataset/preview/files?id=dataset-ver-xxx",
    "content": "/api/dataset/preview/content?id=dataset-ver-xxx",
    "image": "/api/dataset/preview/image?id=dataset-ver-xxx"
  }
}
```

模式映射：

| 数据集类型 | mode |
| --- | --- |
| `CV` / `NLP` | `ARCHIVE` |
| `POINT_CLOUD` | `POINT_CLOUD` |
| `MULTIMODAL` | `SAMPLE_GALLERY` |

descriptor 只统一入口和能力发现，实际内容仍复用第 6、13、15 章的接口。

### 18.4 数据集消费清单

```http
GET /api/v2/dataset-versions/{versionId}/consumer-manifest?page=1&pageSize=100
```

该接口用于训练、推理、评估或前端等模块稳定消费 READY 数据集版本内容。它只接受未删除、调用方有权限访问、状态为 `READY` 的数据集版本；DRAFT、DEPRECATED 和 ARCHIVED 不作为新的跨模块消费入口。

响应示例：

```json
{
  "datasetVersionId": "dataset-ver-ready",
  "datasetId": "dataset-asset-1",
  "type": "MULTIMODAL",
  "versionLabel": "v2",
  "status": "READY",
  "page": 1,
  "pageSize": 100,
  "totalSamples": 1,
  "samples": [
    {
      "sampleId": "sample-1",
      "externalId": "scene-001",
      "sampleIndex": 0,
      "tags": {},
      "metadata": {},
      "data": [
        {
          "sampleDataId": "data-1",
          "dataType": "IMAGE",
          "sensor": "front",
          "channel": "rgb",
          "seq": 0,
          "format": "jpg",
          "fileName": "front.jpg",
          "sizeBytes": 12345,
          "checksum": "sha256:abc",
          "contentType": "image/jpeg",
          "previewUrl": "/api/dataset-sample-data/data-1/preview",
          "downloadUrl": "/api/dataset-sample-data/data-1/download"
        }
      ],
      "annotations": [
        {
          "annotationId": "ann-1",
          "sampleDataId": "data-1",
          "annotationType": "bbox",
          "format": "json",
          "fileName": "front.json",
          "sizeBytes": 456,
          "checksum": "sha256:def",
          "contentType": "application/json",
          "downloadUrl": "/api/dataset-annotations/ann-1/download"
        }
      ]
    }
  ]
}
```

本接口不返回 `storagePath`、bucket、MinIO objectName、`ownerUserId`、packageId、ZIP offset、CRC 或数据库表结构字段。其他模块不得把 legacy 列表接口中的 `storagePath`、`importJobId`、`latestDraftVersionId` 作为长期契约。

#### 跨版本 externalId 查询

```http
GET /api/v2/dataset-samples/multimodal?externalId=scene-001&datasetVersionIds=version-1&datasetVersionIds=version-2&page=1&pageSize=20
```

该 V2 接口与 legacy `/api/dataset-samples/multimodal` 使用同一查询语义和分页响应，但不包裹 legacy `ApiResponse`。它只接受调用方有权限访问的 `READY`、`deleted=false` DatasetVersion；任一传入 versionId 不存在、无权限、已删除或不是 READY 时，返回 `404 / DATASET_NOT_FOUND`。参数错误返回 `400 / INVALID_REQUEST`。

该接口用于按场景 ID 在多个 READY 版本中查找样本及其数据、标注，不做训练 batch 组装、不做模型匹配，也不改变 TaskType。训练、推理、评估等消费方如需跨版本场景查询，应只依赖返回的 `datasetVersionId`、`sampleId`、`externalId`、`sampleIndex`、`data`、`annotations` 和固定 preview/download 链接，不得依赖存储路径、packageId、ZIP offset 或模块二数据库结构。

### 18.5 模型上传 V2

| 接口 | 说明 |
| --- | --- |
| `POST /api/v2/model-uploads/init` | 保存文件信息和全部模型业务信息 |
| `POST /api/v2/model-uploads/{uploadId}/chunks` | 上传模型分片 |
| `GET /api/v2/model-uploads/{uploadId}` | 查询模型上传进度 |
| `POST /api/v2/model-uploads/{uploadId}/complete` | 只使用路径中的 `uploadId` 完成上传 |

init 请求：

```json
{
  "targetAssetId": null,
  "fileName": "model.zip",
  "fileSize": 104857600,
  "fileFingerprint": "sha256:...",
  "modelName": "YOLO Detector",
  "modelVersion": "v1",
  "taskType": "CV",
  "remark": "baseline",
  "commitInfo": "train: baseline weights",
  "hyperParams": {
    "imageSize": 640
  }
}
```

- `commitInfo` 对所有新上传必填，去除首尾空白后长度为 `1`～`1024`；`hyperParams` 可选且默认 `{}`。
- 新建模型资产时必须提供 `modelName`、`modelVersion`、`taskType` 和 `remark`。
- `taskType` 是上传者声明的业务元数据，只支持 `CV/NLP/POINT_CLOUD/ROBOT`。后端校验枚举、已有资产类型一致性和通用制品安全/完整性，但不从文件名、框架或权重二进制推断真实模态；同一合法包声明为 CV 或 NLP 均可 READY。原 A-MODEL-13 的内容识别失败预期由该声明型契约取代。
- 给已有资产新增版本时传 `targetAssetId`；后端校验资产归属，并把资产名称、类型和备注固化到上传会话。
- 相同文件指纹仅在文件信息、模型业务信息、`commitInfo` 和 `hyperParams` 全部一致时恢复会话。
- complete 不再接收模型名称、版本、类型、备注或目标资产。
- complete 会核对对象实际长度、计算完整 SHA-256，并把成功的 READY 版本自动设为当前版本；完成响应增加 `artifactSha256` 和 `isCurrent`。
- V2 响应不返回 `storagePath`、`ownerUserId` 或 MinIO 信息。

### 18.6 模型版本 V2 消费与完整性

| 接口 | 说明 |
| --- | --- |
| `PUT /api/v2/model-assets/{assetId}/current-version` | 切换到同资产且完整性校验通过的 READY 版本；请求体为 `{"versionId":"..."}` |
| `GET /api/v2/model-versions/{versionId}/consumer-manifest` | 获取稳定模型消费清单 |
| `GET /api/v2/model-versions/{versionId}/download` | 按版本下载模型制品 |
| `GET /api/v2/model-versions/{versionId}/files` | 获取模型 ZIP 文件树 |
| `GET /api/v2/model-versions/{versionId}/files/content?path=...` | 预览允许的模型文本文件 |

`consumer-manifest` 响应示例：

```json
{
  "modelAssetId": "model-asset-8e2b",
  "modelVersionId": "model-ver-9f1a",
  "version": "v2",
  "status": "READY",
  "type": "CV",
  "fileName": "model.zip",
  "sizeBytes": 104857600,
  "artifactSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "commitInfo": "fine-tune: internal dataset",
  "hyperParams": {
    "imageSize": 640
  },
  "isCurrent": true,
  "downloadUrl": "/api/v2/model-versions/model-ver-9f1a/download",
  "filesUrl": "/api/v2/model-versions/model-ver-9f1a/files"
}
```

清单不返回 bucket、`storagePath`、objectName 或签名 URL。切换当前版本、
`consumer-manifest`、文件树和文本内容在交付前执行完整对象长度、SHA-256 与 ZIP
结构校验；历史空摘要在对象有效时原子回填。下载接口先用 stat 校验长度，然后在向客户端
发送的同一条 MinIO 流上计算字节数和 SHA-256，不再预读完整对象，因此每次下载只读取
对象一次。已有摘要时响应携带 `X-Artifact-Sha256`；历史空摘要首次下载不伪造该响应头，
而是在流完整读到 EOF 且校验通过后原子回填摘要。流式校验失败发生在响应已经开始后时，
连接会以不完整下载结束，不能再改写为 JSON 错误。

对象缺失、长度不符、摘要不符或 ZIP 文件树出现重复/非法路径时，能在响应提交前判定的
请求返回 `422 / MODEL_ARTIFACT_INVALID`；确定损坏的版本降为 `DRAFT` 并清除其当前
指针。临时 MinIO 故障返回 `503 / MODEL_STORAGE_UNAVAILABLE`，不改变状态。

### 18.7 训练对接边界

V2 数据集列表和上传门面不直接提供训练动作；训练页面继续通过训练任务接口、模型版本接口、数据集版本接口和训练代码资产接口拿到具体版本 ID。

- legacy 训练仍提交 `modelVersionId`、`datasetVersionId`、`codeVersionId` 和 `hyperParams`。
- profile 训练提交 `baseModelVersionId`（或兼容字段 `modelVersionId`）、`datasetVersionId`、`codeVersionId`、`trainingProfile` 和 `hyperParams`。
- `datasetVersionId` 必须指向未删除、调用方可访问、具有存储路径的 `READY` 版本。
- legacy 路径要求模型与数据集类型匹配；profile 路径要求数据集类型符合该 `trainingProfile` 的固定要求。
- 上述匹配比较的是持久化的模型/数据集元数据；模型上传不推断权重二进制的真实模态。
- 当前 profile 兼容路径要求 `codeVersionId` 指向 `READY` + `APPROVED` 的代码版本，并且冻结后的代码资产 `trainingProfile` 与请求一致；模块二的稳定消费契约以版本中固化的 profile 快照为准。
- 当前启用 profile 包括 `image_text_consistency_fusion_logreg` 和
  `yolo_object_detection`；具体入口、数据集类型及训练模式由当前启用方案定义。
- `MULTIMODAL` 当前不能进入训练任务类型。
- 模块二向训练侧稳定交付 READY `datasetVersionId` 和 consumer manifest；训练侧负责 batch 组装、样本选择和多模态适配。
- 训练侧不得依赖 `storagePath`、MinIO objectName、ZIP offset 或模块二数据库表结构。
- 本地训练器当前实际只支持 CV/YOLO 图片和 `labels/*.txt` 数据。
- legacy 本地训练器主要有效超参数为 `epochs`（1–100，默认 3）和 `lr0`（0.000001–1，默认 0.05）；profile 路径记录并传递 `hyperParams`，但不能覆盖固定训练命令。

### 18.8 V2 代码资产、工作区与版本

V2 代码管理以 `codeAssetId`、`workspaceId` 和不可变 `codeVersionId` 为稳定标识。稳定生命周期为 `CodeAsset -> CodeWorkspace -> CodeVersion -> ValidationRun -> RiskAssessment/RiskFinding -> ApprovalRecord`。成功响应直接返回 DTO、列表或下载流；JSON 失败响应为 `V2ErrorResponse`（`success=false`、`errorCode`、`errorMessage`、`details`、`traceId`），同时返回真实 HTTP 状态和 `X-Trace-Id`，不使用 legacy `ApiResponse`。

普通 V2 代码资产、版本和工作区的读取与编辑均为 owner-only；跨用户访问统一隐藏为 `404 / CODE_ASSET_NOT_FOUND`。管理员身份不会获得普通 owner 接口的隐式跨用户能力，必须使用 `/api/v2/admin/code-assets/**`、`/api/v2/admin/code-workspaces/**`、`/api/v2/admin/code-versions/**` 或审核中心。所有 `/api/v2/admin/**` 路径都在参数绑定和资源查询前检查管理员权限：无权限返回 `403 / CODE_APPROVAL_FORBIDDEN`。

资产接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v2/code-assets` | 创建空代码资产，成功为 `201` |
| `POST` | `/api/v2/code-assets/import` | multipart 导入 ZIP；part 为 JSON `metadata` 和 `file`，成功为 `201` |
| `GET` | `/api/v2/code-assets` | 查询当前用户资产 |
| `GET` | `/api/v2/code-assets/{assetId}` | 查询资产详情 |
| `PATCH` | `/api/v2/code-assets/{assetId}` | 以 `assetRevision` 做资产元数据 CAS 更新 |
| `DELETE` | `/api/v2/code-assets/{assetId}?expectedAssetRevision=...` | 软删除资产，成功为 `204` |
| `GET` | `/api/v2/code-assets/{assetId}/versions` | 查询版本列表 |
| `GET` | `/api/v2/code-assets/{assetId}/workspaces` | 查询打开的工作区 |
| `POST` | `/api/v2/code-assets/{assetId}/workspaces` | 打开工作区，可传 `baseVersionId`，成功为 `201` |

资产名称会去除首尾空白，并在同一 owner 的未删除代码资产范围内按不区分大小写保持唯一。不同 owner 可以同名；同一 `codeAssetId` 下创建多个不可变版本不属于资产重名。该规则同时适用于空资产创建、ZIP 导入以及 owner/管理员重命名；已软删除代码资产释放名称。预检失败或数据库并发唯一冲突统一返回 `409 / CODE_ASSET_NAME_CONFLICT`。导入预检发生在 MinIO 上传前；上传后才发生的并发冲突仍执行原有对象补偿清理。

创建资产或在首个未删除代码版本产生前修改 `trainingProfile` 时，非空值必须能由
`TrainingPlanRegistry` 解析为当前启用方案；未知、已禁用或带不受支持版本的值返回
`422 / CODE_VALIDATION_FAILED`，`details.reasonCode=UNSUPPORTED_TRAINING_PROFILE`，
且不会创建或更新资产。当前启用值包括 `image_text_consistency_fusion_logreg` 和
`yolo_object_detection`。

`trainingProfile` 在该资产还没有任何未删除代码版本时可修改；首个版本产生后，改为不同值或显式置空均返回 `409 / CODE_ASSET_CONFLICT`，`details.reasonCode=TRAINING_PROFILE_IMMUTABLE`。传入与当前值相同的 profile 是幂等 no-op。发布或导入时，`trainingProfile` 会固化到 `CodeVersion`；consumer manifest 返回该版本快照，不回读可变资产元数据。这里描述的是模块二的稳定代码消费契约；包括训练在内的新消费者应通过 `codeVersionId` 使用版本快照。

普通资产删除只允许 owner 调用；管理员可通过显式管理员资产接口执行同一软删除操作。两条路径都在资产行锁内核对 `expectedAssetRevision`。revision 过期返回 `409 / ASSET_REVISION_CONFLICT`；存在 `OPEN` 工作区返回 `409 / OPEN_WORKSPACE_EXISTS`；已有其他模块的持久化引用返回 `409 / CODE_ASSET_IN_USE`。删除只软删除 `code_asset`，保留 `code_version`、ZIP、校验、风险、审批和审计证据；删除后 owner 接口和新的制品解析均不可再使用该资产。删除与发布竞争时二者使用同一资产锁，只允许一个操作在其前置条件下成功。已发布版本不提供物理删除接口，继续通过 `deprecate` 或 `archive` 管理生命周期。

ZIP 导入同时适用于 V2 与委托同一服务的 legacy 上传。ZIP 条目名必须可严格按 UTF-8 解码；Windows 工具写入的 `\\` 会先规范化为 `/`，最终确定性 ZIP 内也只保存 `/`。规范化后仍拒绝控制字符、绝对路径、盘符路径、UNC、`.`、`..`、空路径段、超过 1024 字符的路径、符号链接、加密 ZIP 和压缩限制违规项。`src\\train.py` 与 `src/train.py` 规范化为同一路径时返回 `422`，`details.reasonCode=DUPLICATE_PATH`；文件与目录冲突返回 `TREE_CONFLICT`。路径规范化不修改文件内容、BOM 或换行，制品 SHA-256 仍针对实际保存的完整 ZIP 字节计算。

工作区接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v2/code-workspaces/{workspaceId}` | 工作区摘要 |
| `GET` | `/api/v2/code-workspaces/{workspaceId}/tree?prefix=...` | 虚拟目录树 |
| `GET` | `/api/v2/code-workspaces/{workspaceId}/files/content?path=...` | UTF-8 在线内容 |
| `GET` | `/api/v2/code-workspaces/{workspaceId}/files/metadata?path=...` | 文件元数据、原始内容哈希和工作区 revision |
| `GET` | `/api/v2/code-workspaces/{workspaceId}/files/download?path=...` | 下载单文件 |
| `PUT` | `/api/v2/code-workspaces/{workspaceId}/files?path=...` | 新建或覆盖文件 |
| `POST` | `/api/v2/code-workspaces/{workspaceId}/files/move` | 移动或重命名文件 |
| `DELETE` | `/api/v2/code-workspaces/{workspaceId}/files?path=...` | 删除文件 |
| `POST` | `/api/v2/code-workspaces/{workspaceId}/validate` | 校验当前工作区 |
| `POST` | `/api/v2/code-workspaces/{workspaceId}/publish` | 发布不可变版本，成功为 `201` |
| `POST` | `/api/v2/code-workspaces/{workspaceId}/abandon` | 放弃工作区 |

代码文件只允许 `.py`、`.json`、`.jsonl`、`.yaml`、`.yml`、`.txt`、`.md`；`languageId` 为 `python`、`json`、`yaml`、`markdown` 或 `plaintext`，仅供前端选择 Monaco 高亮器，后端不执行源码。目录是从文件路径推导出的虚拟节点。在线读取和编辑只接受 UTF-8 且最多 `1,048,576` bytes；超限返回 `413 / CODE_CONTENT_TOO_LARGE`，但文件仍可在树中列出并通过下载接口取回。`contentHash` 是原始字节的 SHA-256，不是规范化文本哈希。

`files/metadata` 返回 `path/name/nodeType/extension/languageId/contentType/sizeBytes/previewable/editable/downloadable/reasonCode/contentHash/workspaceRevision/readOnly/deletable`。基础版本的大文件通过受限读取计算原始内容哈希，不经过 1 MiB 在线预览上限；因此大于 1 MiB 的文件仍不可预览或编辑，但前端可以先取 metadata 中的 `contentHash`，再按 CAS 规则安全删除。删除只写入工作区 `DELETE` 墓碑，不修改基础版本 ZIP。

写、移动、删除、校验、发布和放弃请求必须使用 `expectedWorkspaceRevision` 做工作区 CAS；针对已有文件的写、移动、删除还必须携带匹配的 `expectedContentHash`，新建文件则不传内容哈希。冲突返回 `409 / CODE_ASSET_CONFLICT`，典型 reasonCode 为 `WORKSPACE_REVISION_CONFLICT`、`CONTENT_HASH_CONFLICT`、`TARGET_EXISTS` 或 `WORKSPACE_READ_ONLY`。

版本接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v2/code-versions/{versionId}` | 版本详情 |
| `GET` | `/api/v2/code-versions/{versionId}/tree?prefix=...` | 只读虚拟目录树 |
| `GET` | `/api/v2/code-versions/{versionId}/files/content?path=...` | 只读在线内容 |
| `GET` | `/api/v2/code-versions/{versionId}/files/download?path=...` | 下载单文件 |
| `GET` | `/api/v2/code-versions/{versionId}/download` | 下载完整 ZIP |
| `GET` | `/api/v2/code-versions/{versionId}/consumer-manifest` | 稳定消费清单 |
| `POST` | `/api/v2/code-versions/{versionId}/validate` | 复用或生成校验证据；等价通过证据幂等复用 |
| `GET` | `/api/v2/code-versions/{versionId}/risk-assessment` | owner 查询当前风险结论和脱敏 findings |
| `POST` | `/api/v2/code-versions/{versionId}/approval` | 管理员独立审批 |
| `POST` | `/api/v2/code-versions/{versionId}/artifact-upgrade` | 管理员恢复单个历史制品 |
| `POST` | `/api/v2/code-versions/{versionId}/deprecate` | 标记弃用 |
| `POST` | `/api/v2/code-versions/{versionId}/archive` | 归档 |

管理员跨 owner 管理接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v2/admin/code-assets` | 分页查询全部有效代码资产；支持 `ownerUserId`、`keyword`、`trainingProfile` |
| `GET/PATCH/DELETE` | `/api/v2/admin/code-assets/{assetId}` | 查看、CAS 修改或软删除任意 owner 的资产 |
| `GET/POST` | `/api/v2/admin/code-assets/{assetId}/workspaces` | 查询或打开该资产唯一的 OPEN 工作区 |
| `GET` | `/api/v2/admin/code-assets/{assetId}/versions` | 查询该资产的不可变版本 |
| 多方法 | `/api/v2/admin/code-workspaces/{workspaceId}/**` | 镜像普通工作区的树、metadata、内容、下载、文件增删改、校验、发布和放弃能力 |
| 多方法 | `/api/v2/admin/code-versions/{versionId}/**` | 版本详情、树、内容、文件/ZIP 下载、校验、弃用和归档 |

管理员资产列表的 `page` 从 0 开始，`pageSize` 为 1–100，默认
`page=0&pageSize=20&sortBy=UPDATED_AT&sortDirection=DESC`。`sortBy` 支持
`UPDATED_AT/CREATED_AT/NAME/OWNER_USER_ID`。资产项比普通 DTO 多
`ownerUserId`，但仍不返回 `storagePath`、MinIO 参数或下载 URL。

管理员编辑不会转移资产：工作区、发布版本及 `users/{ownerUserId}/codes/**` 对象仍归
原用户所有；管理员与用户共享同一个 OPEN 工作区并使用同一 revision/contentHash CAS。
管理员操作在 append-only 审计中记录实际管理员用户 ID 和 `actorType=ADMIN`。审批、
风险重扫和历史制品恢复继续复用既有显式管理员接口及证据规则。

管理员管理能力不等于代码消费授权。`GET /api/code/version/list` 和内部
`requireApprovedForTraining` 仍严格按当前登录用户过滤；管理员不会在训练候选列表中
看到其他 owner 的版本，也不能直接使用他人的 `codeVersionId` 发起训练。管理员发布的
版本在完成校验和审批后，由原 owner 正常选择训练。

版本状态为 `READY`、`DEPRECATED`、`ARCHIVED`；校验状态为 `NOT_RUN`、`PASSED`、`FAILED`；审批状态为 `PENDING`、`APPROVED`、`REJECTED`、`REVOKED`。风险任务状态为 `QUEUED/RUNNING/COMPLETED/ERROR/CANCELED`，风险等级为 `LOW/MEDIUM/HIGH/UNKNOWN`，分流结论为 `AUTO_APPROVE/MANUAL_REVIEW/BLOCK/DIRECT_PASS`。版本 DTO 追加 `riskAssessmentId/riskStatus/riskLevel/reviewDisposition/riskPolicyVersion`，不追加任何存储路径。

训练代码审核模式由数据库中的系统配置控制，默认值为 `STANDARD_REVIEW`。前端管理页使用以下管理员接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/system/config/get` | 查询当前训练代码审核模式 |
| `POST` | `/api/system/config/update` | 更新训练代码审核模式 |

更新请求示例：

```json
{
  "trainingCodeReviewMode": "DIRECT_PASS"
}
```

查询和更新均使用模块一通用响应结构：

```json
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "trainingCodeReviewMode": "STANDARD_REVIEW",
    "updatedAt": "2026-07-23T01:02:03Z"
  }
}
```

| 系统审核模式 | 行为 |
| --- | --- |
| `DIRECT_PASS` | 新的通过校验证据不运行静态风险扫描、不等待管理员审核，写入 `UNKNOWN + DIRECT_PASS` 风险证据和 `decisionSource=SYSTEM_CONFIG` 的系统批准后直接成为 `APPROVED` |
| `STANDARD_REVIEW` | 保持现有审核链路，继续按 `CODE_ASSET_RISK_MODE` 的 `MANUAL_ONLY/SHADOW/ENFORCE` 行为分流 |
| `MANUAL_ONLY` | 新的通过校验证据不触发自动扫描判定，写入 `UNKNOWN + MANUAL_REVIEW` 证据并进入人工待审；管理员仍可显式重新扫描作为人工判断依据 |

`DIRECT_PASS` 只跳过代码风险扫描和人工决策，不跳过 ZIP 路径/扩展名/入口脚本校验，也不跳过实际对象名、SHA-256、长度和消费时重新读取核对。配置变更只影响此后产生的成功校验证据：不会批量批准既有 `PENDING` 版本，也不会自动撤销此前证据完整的批准。已排队或运行中的扫描允许收尾；切到 `MANUAL_ONLY` 后，最终自动决策会在事务内再次核对配置，因此不得再自动批准、拒绝或撤销既有批准。配置变更写入统一的 `PERMISSION_CHANGE + TRAINING_CODE` 审计记录。两个配置接口均仅超级管理员可用；未登录返回 HTTP `401`，权限不足返回 HTTP `403`。

V2 `validate` 和 legacy `training-check` 都先读取实际对象并核对 objectName、SHA-256 和长度。若当前策略、实际对象证据、版本的 `PASSED` 摘要和最新通过的 validation run 完全一致，响应 `reused=true`，不新增 validation run、不重复排队风险扫描、不写重复审计，也不改变既有 `APPROVED`。只有制品或策略证据确实变化时才创建新校验记录；新证据会把旧审批绑定置为 `PENDING`，通过后进入新的风险分流。失败结果不会自动放行。前端对已经批准且校验策略未变化的版本应隐藏普通“准入校验”按钮，或至少增加二次确认；即使误触等价校验，后端幂等保证状态不变。

风险扫描只做有界、非执行式静态检查，不运行用户代码。当前规则检查 JSON/JSONL/YAML 语法、Python 词法结构、私钥和疑似凭据、动态执行、子进程、网络访问、原生库、危险反序列化、破坏性文件操作、运行期安装、外部依赖源、危险 YAML tag 和长编码载荷。Python 自动准入采用保守策略：只有安全导入白名单内且语法落在扫描器置信边界内的代码才有资格判为 `LOW`；高能力导入、未知导入以及超出语法置信边界的形式统一进入 `MANUAL_REVIEW`，绝不判为 `LOW`。单文件自动扫描上限为 1 MiB、单制品总扫描上限为 16 MiB、finding 最多 200；超过自动分析能力会转人工，不会视为安全。finding 只返回规则、等级、类别、文件路径、行号和安全描述，不保存/返回源码片段或检测到的密钥值。扫描前再次核对实际对象 SHA 和长度。

当系统审核模式为 `STANDARD_REVIEW` 时，内部风险部署模式由 `CODE_ASSET_RISK_MODE` 控制；当前应用配置默认 `ENFORCE`：

| 模式 | 行为 |
| --- | --- |
| `MANUAL_ONLY` | 不运行自动扫描；生成 `UNKNOWN + MANUAL_REVIEW` 证据，由管理员审核。管理员对已批准版本显式 `rescan` 会有意生成新证据并回到 `PENDING` |
| `SHADOW` | 后台扫描并记录 findings，但不自动批准/拒绝，也不改变已有批准；用于观察误报 |
| `ENFORCE` | `LOW + AUTO_APPROVE` 由 `AUTO_POLICY` 自动批准；`MEDIUM/HIGH + MANUAL_REVIEW` 进入人工队列；`BLOCK` 在发布风险摘要的同一事务内先使旧 `APPROVED` 失效，再自动拒绝；扫描异常保持不可自动放行 |

上传/发布/真正的新校验证据会创建风险评估。后台默认每 5 秒在分布式锁下处理，批量大小由 `CODE_ASSET_RISK_BATCH_SIZE` 控制（1–100，默认 20），`RUNNING` 超过 `CODE_ASSET_RISK_STALE_AFTER_SECONDS`（最小 60，默认 600）会回到队列。静态扫描“LOW”只表示当前策略未发现已知信号，不代表代码绝对安全。

owner 风险详情接口返回当前版本 SHA 绑定的 `id/versionId/validationRunId/artifactSha256/riskPolicyVersion/scannerVersion/status/riskLevel/disposition/findingCount/reasonCode/createdAt/startedAt/completedAt/findings`。无当前风险证据或跨用户访问均按 `404 / CODE_ASSET_NOT_FOUND` 隐藏资源存在性。

管理员审核中心：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v2/admin/code-review-tasks` | 分页审核队列；默认 `approvalStatus=PENDING&page=0&pageSize=20` |
| `GET` | `/api/v2/admin/code-review-tasks/{versionId}` | 资产、版本、校验和风险证据详情 |
| `GET` | `/api/v2/admin/code-review-tasks/{versionId}/tree?prefix=...` | 审核用只读目录树 |
| `GET` | `/api/v2/admin/code-review-tasks/{versionId}/files/content?path=...` | 审核用只读内容，仍受 1 MiB 限制 |
| `GET` | `/api/v2/admin/code-review-tasks/{versionId}/findings` | 当前风险 findings |
| `POST` | `/api/v2/admin/code-review-tasks/{versionId}/rescan` | 管理员显式重扫；相同任务已在 `QUEUED/RUNNING` 时幂等返回 |

列表支持 `approvalStatus=PENDING|APPROVED|REJECTED|REVOKED`、`riskLevel=LOW|MEDIUM|HIGH|UNKNOWN`、`ownerUserId`、`keyword`、ISO-8601 `submittedFrom/submittedTo`、`sortBy=SUBMITTED_AT|VERSION|RISK_LEVEL|OWNER_USER_ID`、`sortDirection=ASC|DESC`、从 0 开始的 `page` 和 1–100 的 `pageSize`。非管理员在参数绑定和资源查询前统一返回 `403`；管理员查询不存在的版本返回 `404`。管理员页面的 `rescan` 是有意创建新风险证据的高成本操作，不应作为普通上传者的“准入校验”按钮暴露。

管理员审批请求为：

```json
{
  "decision": "APPROVE",
  "reason": "已人工确认风险调用符合该代码用途",
  "expectedValidationRunId": "code-validation-...",
  "expectedRiskAssessmentId": "code-risk-...",
  "expectedArtifactSha256": "64位十六进制SHA-256",
  "expectedPolicyVersion": "code-risk-policy-v2"
}
```

`APPROVE` 和 `REJECT` 必须携带四个 `expected*` 字段；`expectedPolicyVersion` 指风险策略版本。证据已变化返回 `409 / CODE_ASSET_CONFLICT`，`details.reasonCode=APPROVAL_EVIDENCE_STALE` 或 `RISK_EVIDENCE_STALE`。批准还要求 `READY + PASSED`、最新风险评估已 `COMPLETED` 且非 `BLOCK`，并再次核对实际对象 SHA 和长度。`APPROVE` 的 reason 可选：空值按 `null` 处理，提供时会清除控制字符、把换行和制表符折叠为空格、合并连续空白、trim，并最多保存 1024 个字符；`REJECT`、`REVOKE` 的 reason 必填且使用相同清洗和上限。`REVOKE` 只允许作用于已批准版本且不要求 expected 字段。资产/版本行锁和证据 CAS 防止审核旧证据：相同证据的重复 `APPROVE` 幂等返回首次记录，因此不会用重试请求中的新 reason 覆盖原记录；证据变化或冲突决策返回 `409`。审批响应追加 `decisionSource/riskAssessmentId/approvalPolicyVersion`；自动策略记录的 `decisionSource=AUTO_POLICY`，人工为 `ADMIN`，系统直通为 `SYSTEM_CONFIG`。

`consumer-manifest` 返回 `assetId`、`versionId`、`purpose`、`runtime`、`entryScript`、`trainingType`、`trainingProfile`、`artifactSha256`、`validationRunId`、`validationPolicyVersion`、`approvalRecordId`，并追加 `approvalSource/riskAssessmentId/riskLevel/riskPolicyVersion`；兼容历史批准时新增字段可为空。解析器要求 `READY + PASSED + APPROVED`，核对审批、校验和风险证据绑定，并在每次消费时实际读取对象，精确核对 objectName、数据库 SHA-256 和数据库 size；不一致或读取失败会拒绝交付，典型 reasonCode 为 `STORAGE_REFERENCE_INVALID`、`ARTIFACT_SHA256_MISMATCH`、`ARTIFACT_SIZE_MISMATCH`、`STORAGE_READ_FAILED`。manifest 不暴露 `storagePath`、MinIO 信息或可持久化 URL。

`artifact-upgrade` 只处理一个 `versionId`，管理员鉴权先于资源查询。它仅接受精确匹配旧规则的 legacy 对象路径，复制到唯一的版本级 canonical 对象，按原始字节核对 SHA 和 size，在事务内更新证据并写审计，旧对象进入异步删除；失败会补偿清理新对象。成功后立即重新校验并先保持 `PENDING`：`DIRECT_PASS` 写入系统直通证据后批准；`STANDARD_REVIEW` 再按 `MANUAL_ONLY/SHADOW/ENFORCE` 风险模式分流。对已经是 canonical 且证据一致的版本重试时幂等返回 `upgraded=false`。成功字段仅为 `versionId`、`artifactSha256`、`sizeBytes`、`approvalStatus`、`upgraded`、`validation`，不返回路径。失败使用 `403`、`404`、`409`、`422` 或 `503`；冲突/证据 reasonCode 包括 `ARTIFACT_UPGRADE_APPROVAL_CONFLICT`、`ARTIFACT_UPGRADE_CONFLICT`、`LEGACY_STORAGE_REFERENCE_INVALID`、`CANONICAL_ARTIFACT_EVIDENCE_INVALID`、`CANONICAL_ARTIFACT_EVIDENCE_MISMATCH`、`ARTIFACT_STORAGE_EVIDENCE_INVALID`、`ARTIFACT_COPY_MISMATCH`。

V2 代码顶层错误码：

| HTTP | `errorCode` | 说明 |
| --- | --- | --- |
| `404` | `CODE_ASSET_NOT_FOUND` | 不存在或 owner 不匹配 |
| `403` | `CODE_APPROVAL_FORBIDDEN` | 缺少代码管理、审批或恢复管理员权限 |
| `409` | `CODE_ASSET_NAME_CONFLICT` | 同一 owner 已有去除首尾空白且不区分大小写的同名未删除代码资产 |
| `409` | `CODE_ASSET_CONFLICT` | CAS、生命周期或恢复冲突，细分见 `details.reasonCode` |
| `413` | `CODE_CONTENT_TOO_LARGE` | 超出在线内容上限 |
| `422` | `CODE_VALIDATION_FAILED` | 代码包、证据或消费完整性校验失败 |
| `503` | `CODE_STORAGE_UNAVAILABLE` | 制品存储暂不可用 |
