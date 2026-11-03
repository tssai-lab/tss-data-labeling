# TSS AI Platform Backend 技术设计文档

> 维护提示（2026-09-08）：本文保留早期模块设计与接口示例，不应单独作为当前交付验收或新装依据。当前代码入口与不可破坏的边界见 [代码维护说明](code-maintenance-guide.md)；运行配置以 `application.yml` 和部署版本为准。数据库已经采用 Flyway + `ddl-auto=validate`。

## 1. 文档目的

本文档用于规范 TSS AI Platform 后端的系统设计、接口约定与数据库结构，作为后续前后端联调、部署运维、功能扩展和测试验收的基准说明。

当前后端基于 Java 17、Spring Boot 3.5.14、PostgreSQL、MinIO 实现，主要提供用户与权限、模型管理、数据集管理、分片上传、训练任务与实验版本管理等能力。

模块二对其他模块的稳定对外契约单独维护在：

```text
backend/doc/module2-external-contract.md
```

后续前端、训练执行模块、推理模块与模块二联调时，以该契约中的稳定 ID、字段和接口边界为准。

模块二详细测试方案位于：

```text
backend/doc/module2-test-plan.md
```

模块二测试执行细节手册位于：

```text
backend/doc/module2-test-execution-guide.md
```

模块二测试结果报告位于：

```text
backend/doc/module2-test-report.md
```

## 2. 技术栈

| 类别 | 选型 |
| --- | --- |
| 运行环境 | Java 17 |
| Web 框架 | Spring Boot 3.5.14 |
| ORM | Spring Data JPA / Hibernate 6 |
| 用户模块数据访问 | MyBatis-Plus 3.5.5 |
| 鉴权 | Sa-Token 1.37.0 |
| 密码加密 | BCrypt |
| 数据库 | PostgreSQL 16 |
| 本地开发数据库 | H2，`dev` profile |
| 对象存储 | MinIO 8.5.7 Java SDK |
| 构建工具 | Maven Wrapper |

## 3. 系统架构

生产部署推荐结构：

```text
浏览器
  |
  v
Nginx :80/:443
  |-- 前端静态资源 dist
  |
  `-- /api/* -> Spring Boot 127.0.0.1:8080
                  |-- PostgreSQL 127.0.0.1:5432
                  `-- MinIO 127.0.0.1:9010
```

部署原则：

- 前端通过 Nginx 对外提供访问。
- 后端 Spring Boot 不建议直接暴露公网端口，统一由 Nginx 反向代理 `/api/`。
- PostgreSQL 不对公网开放。
- MinIO API 默认仅供后端访问；MinIO Console 不建议长期公网开放。
- 前端请求使用相对路径 `/api/...`，不要写死 `127.0.0.1:8080` 或公网 IP。

## 4. 配置约定

默认配置位于 `src/main/resources/application.yml`。

| 配置项 | 说明 |
| --- | --- |
| `server.port` | 后端服务端口，默认 `8080` |
| `spring.datasource.url` | PostgreSQL JDBC 地址 |
| `spring.datasource.username` | PostgreSQL 用户名 |
| `spring.datasource.password` | PostgreSQL 密码 |
| `spring.jpa.hibernate.ddl-auto` | 当前为 `validate`，只校验实体与表结构；变更由 Flyway 执行 |
| `minio.endpoint` | MinIO API 地址，生产通常为 `http://127.0.0.1:9010` |
| `minio.access-key` | MinIO access key |
| `minio.secret-key` | MinIO secret key |
| `minio.bucket` | 业务对象桶，默认 `models` |
| `spring.servlet.multipart.max-file-size` | 单个 multipart 文件大小上限，当前 `64MB` |
| `spring.servlet.multipart.max-request-size` | 单个 multipart 请求大小上限，当前 `64MB` |

生产环境建议通过环境变量覆盖敏感信息：

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/tss
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your-password
SPRING_PROFILES_ACTIVE=default
```

MinIO 当前未使用环境变量占位，若需要进一步规范，可后续将 `minio.access-key` 与 `minio.secret-key` 改为 `${MINIO_ACCESS_KEY:admin}` 等形式。

## 5. 统一响应格式

### 5.1 通用业务响应

模型、数据集、上传、训练实验等模块使用 `ApiResponse<T>`：

```json
{
  "success": true,
  "data": {},
  "errorMessage": null
}
```

失败响应：

```json
{
  "success": false,
  "data": null,
  "errorMessage": "错误原因"
}
```

### 5.2 用户模块响应

用户、角色、操作日志模块使用 `Result<T>`：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

常用状态码：

| code | 含义 |
| --- | --- |
| `200` | 成功 |
| `400` | 业务失败 |
| `401` | 未登录或 Token 失效 |
| `403` | 无权限 |
| `500` | 服务端异常 |

## 6. 鉴权与权限规则

用户模块基于 Sa-Token。请求头规范：

```http
Authorization: Bearer <token>
```

公开接口：

| 接口 | 说明 |
| --- | --- |
| `POST /api/user/register/username` | 用户名注册 |
| `POST /api/user/register/mobile` | 手机号注册 |
| `POST /api/user/sms/code` | 获取短信验证码 |
| `POST /api/user/forget/password` | 忘记密码 |
| `POST /api/user/login` | 登录 |
| `GET /api/files/health` | MinIO 健康检查 |

当前纳入登录鉴权的业务路径：

```text
/api/user/**
/api/log/**
/api/model/**
/api/model-assets/**
/api/model-versions/**
/api/dataset/**
/api/dataset-assets/**
/api/dataset-versions/**
/api/task/**
/api/experiments/**
/api/files/**
```

登录后用户可访问：

| 接口 | 说明 |
| --- | --- |
| `GET /api/user/current-user` | 获取当前用户 |
| `POST /api/user/logout` | 退出登录 |

管理员接口规则：

- `/api/user/**` 下非公开、非自助接口默认需要管理员权限。
- 管理员角色为 `roleId = 1` 或 `roleId = 2`。
- 普通用户角色为 `roleId = 3`。
- 模块二资源通过 `owner_user_id` 做数据隔离，普通用户只能访问自己的模型、数据集、训练实验和文件对象。
- 管理员可跨用户访问全部模块二资源。

注意：`controller/AuthController` 中的 `/api/login/account`、`/api/currentUser`、`/api/login/outLogin` 是 Ant Design Pro 演示兼容接口，不等同于正式 Sa-Token 登录链路。

## 7. 接口设计

所有生产接口建议统一经 Nginx 暴露为：

```text
http://域名或服务器IP/api/...
```

### 7.1 健康检查与文件对象接口

基础路径：`/api/files`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `GET` | `/health` | 检查 MinIO 连通性 | 无 |
| `POST` | `/upload` | 上传单文件到 MinIO | multipart: `file`, 可选 `objectName` |
| `GET` | `/download` | 下载 MinIO 对象 | query: `objectName` |
| `DELETE` | `/delete` | 删除 MinIO 对象 | query: `objectName` |

`/health` 为公开接口；其余 `/api/files/**` 接口需要 `Authorization: Bearer <token>`。普通用户上传相对 `objectName` 时，后端会自动补齐为 `users/{userId}/files/{objectName}`；若已传入 `users/{userId}/...` 前缀则保持原样。下载和删除时普通用户只能访问自己 `users/{userId}/` 前缀下的对象，管理员不受此前缀限制。

上传示例：

```bash
curl -X POST http://127.0.0.1:8080/api/files/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@test.txt" \
  -F "objectName=smoke/test.txt"
```

下载示例：

```bash
curl -H "Authorization: Bearer <token>" \
  -o test.txt "http://127.0.0.1:8080/api/files/download?objectName=users/1/files/smoke/test.txt"
```

### 7.2 用户与权限接口

基础路径：`/api/user`

| 方法 | 路径 | 说明 | 请求体或参数 |
| --- | --- | --- | --- |
| `POST` | `/register/username` | 用户名注册 | `UserRegisterDTO` |
| `POST` | `/register/mobile` | 手机号注册 | `UserRegisterDTO`，含 `mobile`、`smsCode` |
| `POST` | `/sms/code` | 获取短信验证码 | `SmsCodeDTO` |
| `POST` | `/login` | 登录 | `LoginDTO` |
| `POST` | `/logout` | 退出登录 | Header Token |
| `POST` | `/forget/password` | 忘记密码 | `ForgetPasswordDTO` |
| `GET` | `/current-user` | 当前用户信息 | Header Token |
| `GET` | `/list` | 用户列表 | 管理员 |
| `POST` | `/add` | 新增用户 | 管理员，`User` |
| `POST` | `/reset-password` | 重置密码 | 管理员，`ResetPasswordDTO` |
| `DELETE` | `/delete/{userId}` | 软删除用户 | 管理员 |

用户名注册请求：

```json
{
  "username": "testuser01",
  "password": "test123",
  "confirmPassword": "test123",
  "roleId": 3
}
```

登录请求：

```json
{
  "type": "account",
  "username": "testuser01",
  "password": "test123"
}
```

登录响应中的 `data.token` 用于后续接口：

```http
Authorization: Bearer <token>
```

字段校验：

| 字段 | 规则 |
| --- | --- |
| `username` | 6-20 字符 |
| `password` | 6-16 位字母、数字或下划线 |
| `mobile` | 中国大陆手机号格式 `^1[3-9]\d{9}$` |
| `roleId` | 默认为 `3` |

### 7.3 操作日志接口

基础路径：`/api/log`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/record` | 记录操作日志 |
| `GET` | `/list` | 查询操作日志 |

### 7.4 模型资产接口

基础路径：`/api/model-assets`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/` | 创建模型资产 |
| `GET` | `/` | 查询模型资产列表 |
| `GET` | `/{id}` | 查询模型资产详情 |
| `PUT` | `/{id}` | 更新模型资产 |
| `DELETE` | `/{id}` | 删除模型资产、版本及 MinIO 对象 |

`ModelAsset` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 可空，后端自动生成 `model-asset-*` |
| `name` | string | 模型名称，必填 |
| `type` | string | 任务类型，支持 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` |
| `remark` | string | 备注 |

### 7.5 模型版本接口

基础路径：`/api/model-versions`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `POST` | `/` | 创建模型版本 | `ModelVersion` |
| `GET` | `/` | 查询模型版本列表 | 可选 query: `assetId` |
| `GET` | `/{id}` | 查询模型版本详情 | path: `id` |
| `PUT` | `/{id}` | 更新模型版本 | `ModelVersion` |
| `DELETE` | `/{id}` | 删除模型版本及 MinIO 对象 | path: `id` |

普通用户创建或更新模型版本时不能直接写入或修改 `storagePath`、`fileName`、`sizeBytes`，这些存储元数据只能由上传服务生成；管理员可维护这些字段。普通用户也不能把版本改挂到其他 `assetId`。

### 7.6 模型业务接口

基础路径：`/api/model`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `GET` | `/list` | 模型列表，聚合资产与版本信息 | 可选 query: `type`, `keyword`, `page`/`current`, `pageSize` |
| `GET` | `/detail` | 模型详情 | query: `id`，模型版本 ID |
| `GET` | `/code-files` | 查询模型压缩包中的代码文件列表 | query: `id` |
| `GET` | `/previewCode` | 预览模型代码文件内容 | query: `id`, `path` |
| `DELETE` | `/delete` | 删除模型版本及其对象文件 | query: `id` |

### 7.7 模型分片上传接口

基础路径：`/api/model/upload`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `POST` | `/init` | 初始化上传会话 | `UploadInitRequest` |
| `POST` | `/chunk` | 上传分片 | multipart: `uploadId`, `partIndex`, `file` |
| `GET` | `/progress` | 查询上传进度 | query: `uploadId` |
| `POST` | `/complete` | 合并分片并生成模型资产/版本 | `UploadCompleteRequest` |

上传规则：

- 固定分片大小：5MB。
- `partIndex` 从 `0` 开始。
- 非末尾分片大小必须等于 5MB。
- 状态值：`UPLOADING`、`COMPLETING`、`COMPLETED`。
- 相同 `fileFingerprint` 且状态为 `UPLOADING` 时，会复用最近的上传会话。
- `/complete` 会先把会话原子切换为 `COMPLETING`，避免并发重复合并；若最终对象已写入但数据库记录保存失败，会尽力删除刚生成的 MinIO 对象。
- 初始化时 `fileName` 必须是 `.zip`，`fileSize` 必须大于 0。
- 完成上传时 `uploadId`、`modelName`、`version`、`type`、`remark` 都不能为空，`type` 支持 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT`。
- 合并后的模型 zip 必须至少包含一个文件；zip 内路径不能是绝对路径、盘符路径，不能包含 `..` 或空字节。
- 模型 zip 条目数上限为 `100000`，解压后总体积上限为 `50GB`。

初始化请求：

```json
{
  "fileName": "resnet50.zip",
  "fileSize": 104857600,
  "fileFingerprint": "sha256-or-md5"
}
```

完成请求：

```json
{
  "uploadId": "model-upload-xxx",
  "modelName": "resnet50",
  "version": "v1.0.0",
  "type": "CV",
  "remark": "baseline"
}
```

### 7.8 数据集资产接口

基础路径：`/api/dataset-assets`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/` | 创建数据集资产 |
| `GET` | `/` | 查询数据集资产列表 |
| `GET` | `/{id}` | 查询数据集资产详情 |
| `PUT` | `/{id}` | 更新数据集资产 |
| `DELETE` | `/{id}` | 删除数据集资产、版本及 MinIO 对象 |

`DatasetAsset` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 可空，后端自动生成 `dataset-asset-*` |
| `name` | string | 数据集名称，必填 |
| `type` | string | 任务类型，支持 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT` |
| `cvTaskType` | string | CV 子任务类型，非 CV 为 `null` |
| `annotationFormat` | string | CV 标注格式，非 CV 为 `null` |
| `remark` | string | 备注 |

### 7.9 数据集版本接口

基础路径：`/api/dataset-versions`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `POST` | `/` | 创建数据集版本 | `DatasetVersion` |
| `GET` | `/` | 查询数据集版本列表 | 可选 query: `assetId` |
| `GET` | `/{id}` | 查询数据集版本详情 | path: `id` |
| `PUT` | `/{id}` | 更新数据集版本 | `DatasetVersion` |
| `DELETE` | `/{id}` | 删除数据集版本及 MinIO 对象 | path: `id` |

普通用户创建或更新数据集版本时不能直接写入或修改 `storagePath`、`fileName`、`sizeBytes`，这些存储元数据只能由上传服务生成；管理员可维护这些字段。普通用户也不能把版本改挂到其他 `assetId`。

### 7.10 数据集业务接口

基础路径：`/api/dataset`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `GET` | `/list` | 查询数据集列表，默认返回每个资产最新版本 | 可选 query: `type`, `keyword`, `page`/`current`, `pageSize` |

`type` 支持 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT`。

### 7.11 数据集分片上传接口

基础路径：`/api/dataset/upload`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `POST` | `/init` | 初始化上传会话 | `DatasetUploadInitRequest` |
| `POST` | `/chunk` | 上传分片 | multipart: `uploadId`, `partIndex`, `file` |
| `GET` | `/progress` | 查询上传进度 | query: `uploadId` |
| `POST` | `/complete` | 合并分片并生成数据集资产/版本 | `DatasetUploadCompleteRequest` |
| `POST` | `/folder` | 上传 CV 文件夹并自动打包 | multipart: `datasetName`, `version`, `type`, `cvTaskType`, `annotationFormat`, `remark`, `files`, `paths` |

上传会话状态值为 `UPLOADING`、`COMPLETING`、`COMPLETED`。`/complete` 会先把会话原子切换为 `COMPLETING`，避免并发重复合并；若最终对象已写入但数据库记录保存失败，会尽力删除刚生成的 MinIO 对象。

初始化请求：

```json
{
  "fileName": "imagenet-subset.zip",
  "fileSize": 10737418240,
  "fileFingerprint": "sha256-or-md5",
  "datasetName": "imagenet-subset",
  "version": "v1",
  "type": "CV",
  "cvTaskType": "IMAGE_CLASSIFICATION",
  "annotationFormat": "FOLDER_CLASSIFICATION",
  "remark": "sample data"
}
```

完成请求：

```json
{
  "uploadId": "dataset-upload-xxx"
}
```

数据集格式规则：

- `type` 支持 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT`；当前数据集上传格式完整实现 `CV`、`NLP`、`POINT_CLOUD`。
- `CV` 仅支持 zip 或 `/folder` 文件夹上传，必须至少包含一个图片文件。
- `CV` 额外支持 `cvTaskType`：`IMAGE_CLASSIFICATION`、`OBJECT_DETECTION`、`SEMANTIC_SEGMENTATION`、`INSTANCE_SEGMENTATION`、`UNLABELED`、`OTHER`，默认 `UNLABELED`。
- `CV` 额外支持 `annotationFormat`：`NONE`、`FOLDER_CLASSIFICATION`、`CSV`、`YOLO`、`COCO`、`VOC`、`MASK`、`LABELME`、`OTHER`，默认 `NONE`。
- `CV` 图片后缀支持：`.jpg`、`.jpeg`、`.png`、`.bmp`、`.gif`、`.webp`、`.tif`、`.tiff`。
- `CV` 标注文件按 `annotationFormat` 白名单校验：`NONE/FOLDER_CLASSIFICATION/MASK` 只允许图片；`CSV` 允许 `.csv`；`YOLO` 允许 `.txt/.yaml/.yml`；`COCO/LABELME` 允许 `.json`；`VOC` 允许 `.xml`；`OTHER` 允许 `.txt/.json/.xml/.csv/.yaml/.yml`。其中 `CSV/YOLO/COCO/VOC/LABELME` 必须至少包含一个对应标注文件。
- `NLP` 支持单文件或 zip，允许格式：`.txt`、`.json`、`.jsonl`、`.csv`、`.xlsx`、`.xls`、`.pdf`、`.docx`、`.xml`。
- `NLP` zip 内只能包含上述 NLP 白名单格式文件。
- `POINT_CLOUD` 支持单文件 `.ply`、`.pcd`，也支持 zip；zip 内至少包含一个 `.ply` 或 `.pcd`，且只允许 `.ply`、`.pcd`、`.txt`、`.json`、`.yaml`、`.yml`。
- `POINT_CLOUD` 的 `cvTaskType` 和 `annotationFormat` 允许为空并会保存为 `null`。
- 数据集 zip 会校验路径安全，不允许绝对路径、盘符、`..` 或空字节；条目数上限为 `100000`，解压后总体积上限为 `50GB`。

文件夹上传规则：

- `files` 与 `paths` 数量必须一致。
- `paths` 只允许相对路径，不允许绝对路径或盘符路径。
- `files` 中至少要有一个图片文件，其他文件仍按 `annotationFormat` 白名单校验。
- 后端会将文件夹内容压缩为 zip 后上传 MinIO，最终文件名形如 `{datasetName}-{version}-folder.zip`。

### 7.12 训练任务接口

基础路径：`/api/task`

| 方法 | 路径 | 说明 | 参数 |
| --- | --- | --- | --- |
| `POST` | `/create` | 创建训练任务，等价于创建实验首个版本 | `CreateTrainingExperimentRequest` |
| `GET` | `/list` | 查询训练任务列表，返回每个实验最新版本 | 无 |
| `GET` | `/detail` | 查询任务详情 | query: `id`，支持版本 ID 或实验 ID |
| `POST` | `/stop` | 停止任务 | query: `id` |
| `DELETE` | `/delete` | 删除实验及其版本 | query: `id` |

创建请求：

```json
{
  "name": "resnet50-train",
  "modelVersionId": "model-ver-xxx",
  "codeVersionId": "code-v1",
  "datasetVersionId": "dataset-ver-xxx",
  "hyperParams": {
    "batchSize": 32,
    "learningRate": 0.001
  },
  "remark": "first run"
}
```

约束：

- `modelVersionId` 与 `datasetVersionId` 必须存在。
- 模型资产类型与数据集资产类型必须一致，例如同为 `CV`。
- 训练任务状态当前由后端元数据维护，创建后初始值为 `pending`；兼容展示 `running`、`success`、`failed`、`stopped`。
- `progress` 为展示字段：`success` 为 100，`running` 为 50，`pending/failed/stopped` 为 0。

### 7.13 实验版本接口

基础路径：`/api/experiments`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/` | 创建实验首个版本 |
| `GET` | `/{experimentId}/versions` | 查询实验版本历史 |
| `GET` | `/{experimentId}/versions/{versionNo}` | 查询指定版本 |
| `POST` | `/{experimentId}/versions` | 创建实验新版本 |
| `PUT` | `/{experimentId}/versions/{versionNo}/hyper-parameters` | 更新指定版本超参数 |

创建新版本请求：

```json
{
  "name": "resnet50-train-v2",
  "modelVersionId": "model-ver-xxx",
  "codeVersionId": "code-v2",
  "datasetVersionId": "dataset-ver-xxx",
  "hyperParams": {
    "batchSize": 64
  },
  "remark": "tune batch size"
}
```

## 8. 数据库设计

### 8.1 建表策略

- 用户模块表由 `src/main/resources/db/module1-schema-postgresql.sql` 初始化。
- 模型、数据集、上传会话、训练实验版本等表由 `src/main/resources/db/migration` 中的 Flyway 迁移维护；当前 `ddl-auto=validate`，Hibernate 不自动改表。已部署 SQL 不可重写，新增结构通过新的版本化迁移交付。
- PostgreSQL 主库名建议为 `tss`。

### 8.2 用户与权限表

#### roles

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `SERIAL` | PK | 角色 ID |
| `role_name` | `VARCHAR(50)` | not null | 角色名称 |
| `description` | `VARCHAR(255)` | nullable | 角色描述 |
| `created_at` | `TIMESTAMP` | default now | 创建时间 |

初始数据：

| id | role_name | description |
| --- | --- | --- |
| 1 | `super_admin` | Super administrator |
| 2 | `admin` | Administrator |
| 3 | `user` | Normal user |

#### users

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `SERIAL` | PK | 用户 ID |
| `username` | `VARCHAR(100)` | unique | 用户名 |
| `email` | `VARCHAR(100)` | nullable | 邮箱 |
| `password` | `VARCHAR(255)` | not null | BCrypt 密码摘要 |
| `role_id` | `INTEGER` | FK -> `roles.id` | 角色 ID |
| `status` | `BOOLEAN` | default true | 启用状态 |
| `created_at` | `TIMESTAMP` | default now | 创建时间 |
| `updated_at` | `TIMESTAMP` | default now | 更新时间 |
| `deleted_at` | `TIMESTAMP` | nullable | 软删除时间 |
| `mobile` | `VARCHAR(20)` | unique | 手机号 |

#### operation_logs

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `SERIAL` | PK | 日志 ID |
| `user_id` | `INTEGER` | nullable | 操作用户 ID |
| `user_name` | `VARCHAR(100)` | nullable | 操作用户名 |
| `operation_type` | `VARCHAR(50)` | nullable | 操作类型，如 add/delete/reset |
| `operation_obj` | `VARCHAR(100)` | nullable | 操作对象 |
| `ip_address` | `VARCHAR(100)` | nullable | 客户端 IP |
| `operation_time` | `TIMESTAMP` | default now | 操作时间 |
| `remarks` | `TEXT` | nullable | 备注 |
| `status` | `VARCHAR(30)` | nullable | 操作状态 |

### 8.3 模型表

#### model_asset

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(64)` | PK | 模型资产 ID |
| `name` | `VARCHAR(255)` | not null | 模型名称 |
| `type` | `VARCHAR(64)` | nullable | 任务类型，`CV`/`NLP`/`POINT_CLOUD`/`ROBOT` |
| `remark` | `VARCHAR(1024)` | nullable | 备注 |
| `owner_user_id` | `INTEGER` | nullable, index recommended | 资源归属用户 ID |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |
| `updated_at` | `TIMESTAMP` | nullable | 更新时间 |

#### model_version

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(64)` | PK | 模型版本 ID |
| `asset_id` | `VARCHAR(64)` | not null | 所属模型资产 ID |
| `version` | `VARCHAR(64)` | not null | 版本号 |
| `file_name` | `VARCHAR(255)` | nullable | 原始文件名 |
| `storage_path` | `VARCHAR(1024)` | nullable | MinIO 对象路径 |
| `size_bytes` | `BIGINT` | nullable | 文件大小 |
| `owner_user_id` | `INTEGER` | nullable, index recommended | 资源归属用户 ID |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |

#### model_upload_session

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(96)` | PK | 上传会话 ID |
| `file_fingerprint` | `VARCHAR(512)` | index | 文件指纹 |
| `file_name` | `VARCHAR(255)` | not null | 文件名 |
| `file_size` | `BIGINT` | not null | 文件大小 |
| `chunk_size` | `INTEGER` | not null | 分片大小 |
| `total_chunks` | `INTEGER` | not null | 总分片数 |
| `status` | `VARCHAR(32)` | not null, index | `UPLOADING`/`COMPLETING`/`COMPLETED` |
| `storage_path` | `VARCHAR(1024)` | nullable | 合并后对象路径 |
| `asset_id` | `VARCHAR(64)` | nullable | 生成的模型资产 ID |
| `version_id` | `VARCHAR(64)` | nullable | 生成的模型版本 ID |
| `owner_user_id` | `INTEGER` | nullable, index recommended | 上传会话归属用户 ID |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |
| `updated_at` | `TIMESTAMP` | nullable | 更新时间 |

#### model_upload_chunk

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(96)` | PK | 分片记录 ID |
| `upload_id` | `VARCHAR(96)` | not null, index | 上传会话 ID |
| `part_index` | `INTEGER` | not null | 分片序号，从 0 开始 |
| `object_name` | `VARCHAR(1024)` | not null | 临时分片对象名 |
| `size_bytes` | `BIGINT` | nullable | 分片大小 |
| `etag` | `VARCHAR(255)` | nullable | MinIO ETag |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |

唯一约束：`uk_model_upload_chunk_part(upload_id, part_index)`。

### 8.4 数据集表

#### dataset_asset

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(64)` | PK | 数据集资产 ID |
| `name` | `VARCHAR(255)` | not null | 数据集名称 |
| `type` | `VARCHAR(64)` | nullable | 任务类型，`CV`/`NLP`/`POINT_CLOUD`/`ROBOT` |
| `cv_task_type` | `VARCHAR(64)` | nullable | CV 子任务类型 |
| `annotation_format` | `VARCHAR(64)` | nullable | CV 标注格式 |
| `remark` | `VARCHAR(1024)` | nullable | 备注 |
| `owner_user_id` | `INTEGER` | nullable, index recommended | 资源归属用户 ID |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |
| `updated_at` | `TIMESTAMP` | nullable | 更新时间 |

#### dataset_version

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(64)` | PK | 数据集版本 ID |
| `asset_id` | `VARCHAR(64)` | not null | 所属数据集资产 ID |
| `version` | `VARCHAR(64)` | not null | 版本号 |
| `file_name` | `VARCHAR(255)` | nullable | 原始文件名 |
| `storage_path` | `VARCHAR(1024)` | nullable | MinIO 对象路径 |
| `size_bytes` | `BIGINT` | nullable | 文件大小 |
| `cv_task_type` | `VARCHAR(64)` | nullable | CV 子任务类型 |
| `annotation_format` | `VARCHAR(64)` | nullable | CV 标注格式 |
| `remark` | `VARCHAR(1024)` | nullable | 备注 |
| `owner_user_id` | `INTEGER` | nullable, index recommended | 资源归属用户 ID |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |

#### dataset_upload_session

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(96)` | PK | 上传会话 ID |
| `file_fingerprint` | `VARCHAR(512)` | index | 文件指纹 |
| `file_name` | `VARCHAR(255)` | not null | 文件名 |
| `file_size` | `BIGINT` | not null | 文件大小 |
| `chunk_size` | `INTEGER` | not null | 分片大小 |
| `total_chunks` | `INTEGER` | not null | 总分片数 |
| `dataset_name` | `VARCHAR(255)` | not null | 数据集名称 |
| `dataset_version` | `VARCHAR(64)` | not null | 数据集版本号 |
| `task_type` | `VARCHAR(16)` | not null | 任务类型，`CV`/`NLP`/`POINT_CLOUD`/`ROBOT` |
| `cv_task_type` | `VARCHAR(64)` | nullable | CV 子任务类型 |
| `annotation_format` | `VARCHAR(64)` | nullable | CV 标注格式 |
| `remark` | `VARCHAR(1024)` | nullable | 备注 |
| `status` | `VARCHAR(32)` | not null, index | `UPLOADING`/`COMPLETING`/`COMPLETED` |
| `storage_path` | `VARCHAR(1024)` | nullable | 合并后对象路径 |
| `asset_id` | `VARCHAR(64)` | nullable | 生成的数据集资产 ID |
| `version_id` | `VARCHAR(64)` | nullable | 生成的数据集版本 ID |
| `owner_user_id` | `INTEGER` | nullable, index recommended | 上传会话归属用户 ID |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |
| `updated_at` | `TIMESTAMP` | nullable | 更新时间 |

#### dataset_upload_chunk

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(96)` | PK | 分片记录 ID |
| `upload_id` | `VARCHAR(96)` | not null, index | 上传会话 ID |
| `part_index` | `INTEGER` | not null | 分片序号，从 0 开始 |
| `object_name` | `VARCHAR(1024)` | not null | 临时分片对象名 |
| `size_bytes` | `BIGINT` | nullable | 分片大小 |
| `etag` | `VARCHAR(255)` | nullable | MinIO ETag |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |

唯一约束：`uk_dataset_upload_chunk_part(upload_id, part_index)`。

### 8.5 训练实验版本表

#### training_experiment_version

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(64)` | PK | 实验版本 ID |
| `experiment_id` | `VARCHAR(64)` | not null, index | 实验 ID |
| `version_no` | `INTEGER` | not null | 实验版本号 |
| `name` | `VARCHAR(255)` | nullable | 实验名称 |
| `model_version_id` | `VARCHAR(64)` | nullable | 模型版本 ID |
| `code_version_id` | `VARCHAR(128)` | not null | 代码版本 ID |
| `dataset_version_id` | `VARCHAR(64)` | not null | 数据集版本 ID |
| `hyper_params_json` | `TEXT` | nullable | 超参数 JSON |
| `status` | `VARCHAR(32)` | nullable | 任务状态 |
| `remark` | `VARCHAR(1024)` | nullable | 备注 |
| `owner_user_id` | `INTEGER` | nullable, index recommended | 实验归属用户 ID |
| `created_at` | `TIMESTAMP` | nullable | 创建时间 |
| `updated_at` | `TIMESTAMP` | nullable | 更新时间 |

唯一约束：`uk_training_experiment_version(experiment_id, version_no)`。

## 9. 对象存储设计

MinIO bucket 默认：

```text
models
```

对象路径由服务层生成并写入数据库字段：

| 业务 | 数据库字段 | 说明 |
| --- | --- | --- |
| 模型版本文件 | `model_version.storage_path` | `users/{userId}/models/{modelName}/{version}/{fileName}` |
| 数据集版本文件 | `dataset_version.storage_path` | `users/{userId}/datasets/{assetId}/{version}/{fileName}` |
| 模型上传分片 | `model_upload_chunk.object_name` | `users/{userId}/models/_uploads/{uploadId}/part-{index}` |
| 数据集上传分片 | `dataset_upload_chunk.object_name` | `users/{userId}/datasets/_uploads/{uploadId}/part-{index}` |
| 通用文件对象 | 请求返回 `objectName` | 相对路径默认归一化为 `users/{userId}/files/{objectName}`；普通用户也可显式传入自己的 `users/{userId}/...` 前缀 |

MinIO 使用一个后端服务账号连接。不同业务用户的数据隔离不依赖 MinIO 多账号，而依赖：

- 数据库 `owner_user_id`。
- 对象路径前缀 `users/{userId}/`。
- 后端接口在查询、下载、删除前做权限校验。

删除行为：

- 删除模型业务版本 `/api/model/delete`、模型资产 `/api/model-assets/{id}` 或模型版本 `/api/model-versions/{id}` 时，会同步删除对应 MinIO 对象；若 MinIO 删除失败，会返回失败并保留数据库记录。
- 删除数据集资产或数据集版本时，会先删除 MinIO 对象；若 MinIO 删除失败，会返回失败并保留数据库记录。
- 上传完成后会清理临时分片对象和分片记录。

## 10. 核心业务流程

### 10.1 登录鉴权流程

```text
用户注册 -> 用户登录 -> 后端生成 Sa-Token -> 前端保存 token
  -> 后续请求携带 Authorization: Bearer <token>
  -> PermissionInterceptor 校验登录态和管理员权限
```

### 10.2 模型分片上传流程

```text
POST /api/model/upload/init
  -> 返回 uploadId、chunkSize、totalChunks
POST /api/model/upload/chunk
  -> 上传 partIndex = 0..n
GET /api/model/upload/progress
  -> 前端断点续传依据
POST /api/model/upload/complete
  -> MinIO compose 合并对象
  -> 写入 model_asset、model_version
  -> 清理临时分片
```

### 10.3 数据集分片上传流程

```text
POST /api/dataset/upload/init
  -> 返回 uploadId、chunkSize、totalChunks
POST /api/dataset/upload/chunk
  -> 上传 partIndex = 0..n
POST /api/dataset/upload/complete
  -> 合并对象
  -> 写入 dataset_asset、dataset_version
  -> 清理临时分片
```

### 10.4 训练任务创建流程

```text
选择模型版本 -> 选择数据集版本 -> 校验模型/数据集任务类型一致
  -> 创建 training_experiment_version(version_no=1)
  -> 状态初始化为 pending
  -> 前端展示任务列表与详情
```

类型匹配按资产 `type` 精确比较：`POINT_CLOUD` 模型可匹配 `POINT_CLOUD` 数据集；`CV`、`NLP`、`POINT_CLOUD` 之间的交叉错配仍会被拒绝。

## 11. 部署与验证

后端构建：

```bash
cd /opt/tss-platform/backend
./mvnw clean package -DskipTests
```

后端启动：

```bash
nohup java -jar target/tss-backend-1.0.0.jar > backend.log 2>&1 &
```

健康检查：

```bash
curl http://127.0.0.1:8080/api/files/health
```

期望响应：

```json
{
  "success": true,
  "data": {
    "minio": "ok"
  },
  "errorMessage": null
}
```

Nginx 反代验证：

```bash
curl http://服务器IP/api/files/health
```

## 12. 测试建议

最低功能性测试清单：

- 后端启动：日志出现 `Tomcat started on port 8080` 与 `Started TssPlatformApplication`。
- MinIO 健康：`GET /api/files/health` 返回 `minio=ok`。
- 用户注册：`POST /api/user/register/username` 成功或返回用户已存在。
- 用户登录：`POST /api/user/login` 返回 token。
- 鉴权接口：携带 token 调用 `GET /api/user/current-user`。
- 文件对象：上传、下载、删除 `/api/files/*`。
- 模型上传：初始化、分片、进度、完成。
- 数据集上传：初始化、分片、进度、完成。
- 训练任务：创建、列表、详情、停止、删除。

## 13. 后续优化建议

- 统一 `ApiResponse` 与 `Result` 两套响应模型，减少前端适配成本。
- 将 MinIO 账号密码改为环境变量配置。
- 已采用 Flyway；后续变更必须新增迁移并验证升级路径，不得切回 `ddl-auto=update` 绕过结构校验。
- 为模型、数据集、训练实验等关键操作补充统一审计日志。
- 生产环境启用 HTTPS，并通过 Nginx 控制上传大小与超时时间。
- 为上传会话增加过期清理任务，定期删除长期未完成的临时分片。
