# TSS AI Platform Backend

TSS AI Platform 后端服务，基于 **Java 17 + Spring Boot 3.5.14** 构建，为前端提供 `/api/**` 接口。

当前后端包含两部分主要能力：

- **模块一：用户与权限**
  用户注册、登录、Sa-Token 鉴权、用户管理、操作日志。

- **模块二：数据资产标准化管理**
  模型资产管理、数据集资产管理、版本管理、分片上传、MinIO 对象存储、训练实验版本元数据、训练结果回写元数据。

模块一代码位于 `com.tss.platform.module1`。模块二当前仍位于顶层包 `com.tss.platform.controller/service/repository/entity/dto/model` 等目录，尚未迁移到独立 `module2` 包。

模块二对外契约和审查文档可参考：

```text
doc/module2-external-contract.md
doc/training-plan-yaml-guide.md
../doc1/
```

## 技术栈

| 类别 | 当前实现 |
| --- | --- |
| Java | JDK 17 |
| Web 框架 | Spring Boot 3.5.14 |
| ORM | Spring Data JPA / Hibernate 6 |
| 数据库迁移 | Flyway |
| 模块一数据访问 | MyBatis-Plus 3.5.5 |
| 登录鉴权 | Sa-Token 1.37.0 |
| 密码加密 | BCrypt |
| 默认数据库 | PostgreSQL |
| 本地开发数据库 | H2 file database，`dev` profile |
| 对象存储 | MinIO Java SDK 8.5.7 |
| 构建工具 | Maven Wrapper，Windows 使用 `mvnw.cmd` |

## 目录结构

```text
backend/
|-- pom.xml
|-- mvnw.cmd
|-- src/main/
|   |-- java/com/tss/platform/
|   |   |-- TssPlatformApplication.java
|   |   |-- config/                  # CORS、MinIO、拦截器注册
|   |   |-- module1/                 # 用户、角色、鉴权、日志
|   |   |-- controller/              # 模块二 REST API
|   |   |-- dto/                     # 模块二请求/响应对象
|   |   |-- entity/                  # 模块二 JPA 实体
|   |   |-- model/                   # 模块二枚举和旧模型
|   |   |-- repository/              # 模块二 JPA Repository
|   |   `-- service/                 # 模型、数据集、训练实验业务逻辑
|   `-- resources/
|       |-- application.yml          # 默认 PostgreSQL + MinIO 配置
|       |-- application-dev.yml      # H2 本地开发配置
|       `-- db/
|           |-- module1-schema-postgresql.sql
|           `-- migration/           # 模块二 Flyway 迁移
```

## 环境要求

- JDK 17 可用。
- PostgreSQL 可用。
- MinIO 可用。
- 后端默认监听端口：`8080`。

推荐本地容器名：

```text
PostgreSQL: tss-postgres
MinIO:      minio-tss
```

## 配置说明

生产/默认 profile 不再在 `application.yml` 中写明文数据库和 MinIO 密码。启动前必须提供以下环境变量：

| 变量 | 是否必填 | 说明 |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | 可选 | 默认 `jdbc:postgresql://127.0.0.1:5432/tss` |
| `SPRING_DATASOURCE_USERNAME` | 必填 | PostgreSQL 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 必填 | PostgreSQL 密码 |
| `MINIO_ENDPOINT` | 可选 | 默认 `http://127.0.0.1:9010` |
| `MINIO_ACCESS_KEY` | 必填 | MinIO access key |
| `MINIO_SECRET_KEY` | 必填 | MinIO secret key |
| `MINIO_BUCKET` | 可选 | 默认 `models` |
| `SPRING_PROFILES_ACTIVE` | 可选 | 默认 `default`；本地 H2 可设为 `dev` |

当前默认配置要点：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
  jpa:
    hibernate:
      ddl-auto: validate
```

说明：

- 模块二表结构由 Flyway 迁移脚本管理，不再依赖 `ddl-auto=update`。
- Hibernate 只做 `validate`，用于校验实体与数据库结构是否一致。
- `application-dev.yml` 为本地开发保留 MinIO 默认账号占位：`admin/password123`，不要用于生产环境。

PowerShell 本地启动示例：

```powershell
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="postgres"
$env:MINIO_ACCESS_KEY="admin"
$env:MINIO_SECRET_KEY="password123"
.\mvnw.cmd spring-boot:run
```

使用 H2 dev profile：

```powershell
$env:MINIO_ACCESS_KEY="admin"
$env:MINIO_SECRET_KEY="password123"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

## 数据库初始化

### 模块一

模块一使用 MyBatis-Plus，不由 Flyway 管理。首次使用 PostgreSQL 时需要手动执行：

```powershell
Get-Content E:\resource\TSSAIPlatform\backend\src\main\resources\db\module1-schema-postgresql.sql | docker exec -i tss-postgres psql -U postgres -d tss
```

该脚本创建：

```text
roles
users
operation_logs
```

### 模块二

模块二由 Flyway 自动执行 `src/main/resources/db/migration` 下的迁移脚本：

| 脚本 | 作用 |
| --- | --- |
| `V1__module2_core_schema.sql` | 创建模块二核心表、索引 |
| `V2__module2_constraints.sql` | 增加唯一索引、外键、枚举 CHECK 约束 |
| `V3__training_result_callback_fields.sql` | 增加训练结果回写字段 |

模块二核心表：

```text
model_asset
model_version
model_upload_session
model_upload_chunk
dataset_asset
dataset_version
dataset_upload_session
dataset_upload_chunk
training_experiment_version
```

重要约束：

- `model_version(asset_id, version)` 唯一。
- `dataset_version(asset_id, version)` 唯一。
- 模型/数据集版本通过外键关联资产表。
- 训练实验版本通过外键关联模型版本和数据集版本。
- `type` 只允许 `CV` / `NLP`。
- 上传状态只允许 `UPLOADING` / `COMPLETING` / `COMPLETED`。
- 训练状态只允许 `pending` / `queued` / `running` / `success` / `failed` / `stopped`。
- 训练进度 `progress` 范围为 `0..100`。

如果历史库中已有重复版本号、非法枚举值或孤儿引用，Flyway 迁移会失败。需要先清理脏数据，再启动新版本。

## MinIO 启动示例

本地没有 MinIO 时，可在仓库根目录执行。数据目录统一放在仓库上一级，
避免被项目构建或清理操作影响：

```powershell
docker run -d --name minio-tss `
  -p 9010:9000 -p 9011:9001 `
  -e MINIO_ROOT_USER=admin `
  -e MINIO_ROOT_PASSWORD=password123 `
  -v "$(Resolve-Path ..\minio-data):/data" `
  minio/minio server /data --console-address ":9001"
```

使用本机安装的 MinIO 时：

```powershell
$env:MINIO_ROOT_USER="admin"
$env:MINIO_ROOT_PASSWORD="password123"
minio.exe server D:\work\minio-data --address ":9010" --console-address ":9011"
```

MinIO 控制台：

```text
http://127.0.0.1:9011
```

后端启动时会检查并创建 `MINIO_BUCKET` 对应的 bucket，默认 bucket 为 `models`。

## 构建和启动

进入后端目录：

```powershell
cd E:\resource\TSSAIPlatform\backend
```

编译：

```powershell
.\mvnw.cmd -DskipTests compile
```

完整打包：

```powershell
.\mvnw.cmd clean package -DskipTests
```

如果仅需验证打包流程且本地已有 Spring Boot 重打包产物被占用，可跳过 repackage：

```powershell
.\mvnw.cmd -DskipTests "-Dspring-boot.repackage.skip=true" package
```

启动：

```powershell
.\mvnw.cmd spring-boot:run
```

运行 jar：

```powershell
java -jar target\tss-backend-1.0.0.jar
```

## 鉴权说明

当前 `WebConfig` 对以下路径启用权限拦截：

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

公开接口：

```text
POST /api/user/register/username
POST /api/user/register/mobile
POST /api/user/sms/code
POST /api/user/forget/password
POST /api/user/login
GET  /api/files/health
```

其余受保护接口需要请求头：

```text
Authorization: Bearer <token>
```

模块二按 `owner_user_id` 做数据隔离。普通用户只能访问自己的模型、数据集、上传会话、训练实验和文件对象；管理员角色 `roleId=1/2` 可访问全部资源。

MinIO 使用一个后端服务账号连接，业务隔离依赖数据库 `owner_user_id` 和对象路径前缀：

```text
users/{userId}/models/...
users/{userId}/datasets/...
users/{userId}/files/...
```

## 主要接口概览

### 模块一：用户、鉴权、日志

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/user/register/username` | POST | 用户名注册 |
| `/api/user/register/mobile` | POST | 手机号注册 |
| `/api/user/sms/code` | POST | 发送短信验证码，当前为内存模拟 |
| `/api/user/login` | POST | 账号或手机号登录 |
| `/api/user/logout` | POST | 退出登录 |
| `/api/user/current-user` | GET | 当前登录用户 |
| `/api/user/list` | GET | 用户列表 |
| `/api/user/add` | POST | 管理员新增用户 |
| `/api/user/reset-password` | POST | 管理员重置密码 |
| `/api/user/delete/{userId}` | DELETE | 软删除用户 |
| `/api/log/record` | POST | 记录操作日志 |
| `/api/log/list` | GET | 查询操作日志 |

### 演示登录兼容接口

以下接口位于 `AuthController`，仅用于兼容旧 Ant Design Pro 登录流程，不是正式模块一鉴权链路：

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/login/account` | POST | 演示账号登录 |
| `/api/currentUser` | GET | 演示当前用户 |
| `/api/login/outLogin` | POST | 演示退出 |

前端应逐步切换到 `/api/user/login`、`/api/user/current-user`、`/api/user/logout`。

### 模块二：MinIO 文件对象

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/files/health` | GET | 检查 MinIO 连接 |
| `/api/files/upload` | POST | 上传单个对象 |
| `/api/files/download?objectName=...` | GET | 下载对象 |
| `/api/files/delete?objectName=...` | DELETE | 删除对象 |

### 模块二：模型管理

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/model/upload/init` | POST | 初始化模型分片上传 |
| `/api/model/upload/chunk` | POST | 上传模型分片 |
| `/api/model/upload/progress` | GET | 查询模型上传进度 |
| `/api/model/upload/complete` | POST | 合并分片并创建模型资产/版本 |
| `/api/model/list` | GET | 模型版本列表 |
| `/api/model/detail?id=...` | GET | 模型详情 |
| `/api/model/code-files?id=...` | GET | 列出 ZIP 内可预览代码文件 |
| `/api/model/previewCode?id=...&path=...` | GET | 预览 ZIP 内文本代码 |
| `/api/model/delete?id=...` | DELETE | 删除模型版本并尝试删除 MinIO 文件 |
| `/api/model-assets` | GET/POST | 模型资产列表/创建 |
| `/api/model-assets/{id}` | GET/PUT/DELETE | 模型资产详情、更新、删除 |
| `/api/model-versions` | GET/POST | 模型版本列表/创建 |
| `/api/model-versions/{id}` | GET/PUT/DELETE | 模型版本详情、更新、删除 |

模型上传规则：

- 模型文件仅支持 `.zip`。
- `type` 支持 `CV` 和 `NLP`。
- 分片大小由后端固定为 5MiB。
- 同一模型资产下版本号唯一。
- 删除模型资产/版本前会检查是否被训练实验引用；被引用时拒绝删除。

### 模块二：数据集管理

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/dataset/list` | GET | 数据集聚合列表 |
| `/api/dataset/upload/init` | POST | 初始化数据集分片上传 |
| `/api/dataset/upload/chunk` | POST | 上传数据集分片 |
| `/api/dataset/upload/progress` | GET | 查询数据集上传进度 |
| `/api/dataset/upload/complete` | POST | 合并分片并创建数据集资产/版本 |
| `/api/dataset/upload/folder` | POST | 上传 CV 图片文件夹，后端打包为 ZIP |
| `/api/dataset-assets` | GET/POST | 数据集资产列表/创建 |
| `/api/dataset-assets/{id}` | GET/PUT/DELETE | 数据集资产详情、更新、删除 |
| `/api/dataset-versions` | GET/POST | 数据集版本列表/创建 |
| `/api/dataset-versions/{id}` | GET/PUT/DELETE | 数据集版本详情、更新、删除 |

数据集上传规则：

- `CV` 支持 ZIP 或 `/api/dataset/upload/folder` 文件夹上传，内容必须包含图片文件。
- `NLP` 支持 `.txt`、`.json`、`.jsonl`、`.csv`、`.xlsx`、`.xls`、`.pdf`、`.docx`、`.xml`，也支持仅包含这些文件的 ZIP。
- `cvTaskType` 支持 `IMAGE_CLASSIFICATION`、`OBJECT_DETECTION`、`SEMANTIC_SEGMENTATION`、`INSTANCE_SEGMENTATION`、`UNLABELED`、`OTHER`。
- `annotationFormat` 支持 `NONE`、`FOLDER_CLASSIFICATION`、`CSV`、`YOLO`、`COCO`、`VOC`、`MASK`、`LABELME`、`OTHER`。
- 同一数据集资产下版本号唯一。
- 删除数据集资产/版本前会检查是否被训练实验引用；被引用时拒绝删除。

### 模块二：训练任务与实验版本

当前训练模块管理实验元数据和结果回写元数据，不调度真实训练作业。

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/task/create` | POST | 创建训练任务，生成实验版本 `versionNo=1` |
| `/api/task/list` | GET | 训练任务列表，每个实验返回最新版本 |
| `/api/task/detail?id=...` | GET | 查询训练任务详情 |
| `/api/task/stop?id=...` | POST | 将任务状态改为 `stopped` |
| `/api/task/result?id=...` | POST | 按训练版本 ID 或 experimentId 回写训练结果 |
| `/api/task/delete?id=...` | DELETE | 删除一个实验下的所有版本 |
| `/api/experiments` | POST | 创建训练实验，等价于 `/api/task/create` |
| `/api/experiments/{experimentId}/versions` | GET | 查看实验版本历史 |
| `/api/experiments/{experimentId}/versions` | POST | 创建实验新版本 |
| `/api/experiments/{experimentId}/versions/{versionNo}` | GET | 查看指定实验版本 |
| `/api/experiments/{experimentId}/versions/{versionNo}/hyper-parameters` | PUT | 修改指定版本超参数 |
| `/api/experiments/{experimentId}/versions/{versionNo}/result` | PUT | 回写指定实验版本训练结果 |

训练结果回写字段：

```json
{
  "status": "running",
  "progress": 50,
  "metrics": {
    "accuracy": 0.91
  },
  "logPath": "users/1/training/exp-xxx/log.txt",
  "outputPath": "users/1/training/exp-xxx/output/",
  "errorMessage": null,
  "startedAt": "2026-05-11T10:00:00Z",
  "finishedAt": null,
  "remark": "optional"
}
```

状态只允许：

```text
pending, queued, running, success, failed, stopped
```

## 分片上传说明

模型和数据集分片上传流程一致：

1. 调用 `init` 获取 `uploadId`、`chunkSize`、`totalChunks`、`uploadedPartIndexes`。
2. 前端按 `chunkSize` 切片，只上传缺失分片。
3. 中断后重新调用 `init` 或 `progress`，根据已上传分片继续上传。
4. 全部分片上传完成后调用 `complete`。
5. 后端使用 MinIO `composeObject` 合并最终文件，落库资产/版本记录，并清理临时分片。

默认单片大小为 5MiB。`application.yml` 中的 `64MB` multipart 限制只约束单个 HTTP 请求，不限制模型或数据集总文件大小。

MinIO 对象路径约定：

| 类型 | 路径 |
| --- | --- |
| 模型临时分片 | `users/{userId}/models/_uploads/{uploadId}/part-{index}` |
| 模型最终文件 | `users/{userId}/models/{assetId}/{version}/{fileName}` |
| 数据集临时分片 | `users/{userId}/datasets/_uploads/{uploadId}/part-{index}` |
| 数据集最终文件 | `users/{userId}/datasets/{assetId}/{version}/{fileName}` |
| 通用文件对象 | `users/{userId}/files/{objectName}` |

## 常见问题

### 启动时报缺少环境变量

默认 profile 下数据库用户名、密码和 MinIO 密钥没有默认值。请先设置：

```powershell
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="postgres"
$env:MINIO_ACCESS_KEY="admin"
$env:MINIO_SECRET_KEY="password123"
```

### Flyway 迁移失败

常见原因：

- 历史数据中同一资产下存在重复版本号。
- `type`、`status`、`cvTaskType`、`annotationFormat` 存在非法值。
- 训练实验引用了不存在的模型版本或数据集版本。

需要先清理数据库脏数据，再重新启动。

### package 阶段提示 jar 无法重命名

如果报错类似：

```text
Unable to rename target\tss-backend-1.0.0.jar to target\tss-backend-1.0.0.jar.original
```

通常是旧 jar 或 `spring-boot:run` 进程仍在运行。先停止后端进程，再执行：

```powershell
.\mvnw.cmd clean package -DskipTests
```

仅验证普通 jar 打包时也可使用：

```powershell
.\mvnw.cmd -DskipTests "-Dspring-boot.repackage.skip=true" package
```

### MinIO 连接失败

确认 MinIO API 端口是 `9010`，并且环境变量中的账号密码正确。可访问：

```text
GET http://127.0.0.1:8080/api/files/health
```

### 大文件超过 64MB 是否会失败

模型和数据集上传走 5MiB 分片。只要单个分片请求小于 multipart 限制，总文件可以远大于 64MB。

## 当前边界

- 模块一短信验证码为内存模拟，服务重启后缓存会丢失。
- 模块二训练任务当前管理实验、版本、状态、超参数和结果回写元数据，尚未接入真实训练调度器。
- 训练结果回写保存指标、日志路径、输出路径等元数据，不负责生成或上传训练产物。
- 旧数据如果 `owner_user_id` 为空，普通用户默认不可见，管理员可见；需要访问旧数据时应补齐归属字段。
- 模块二业务审计仍可继续增强，例如模型/数据集上传、删除、训练状态变更等操作日志。
