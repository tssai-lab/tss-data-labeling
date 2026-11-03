# 模块一对接文档：用户权限与模块二资源隔离

## 1. 文档目的

本文档用于模块一与模块二联调，说明当前后端已经如何接入模块一登录鉴权，以及模块一需要稳定提供哪些登录态信息。

当前模块二已经把模型、数据集、上传会话、训练实验接入用户归属控制。普通用户只能访问自己的资源，管理员可以访问全部资源。

## 2. 模块一需要提供的能力

模块二依赖模块一的 Sa-Token 登录态，不单独维护用户体系。

登录成功后，模块一需要保证：

```java
StpUtil.login(user.getId());
StpUtil.getTokenSession().set("roleId", user.getRoleId());
StpUtil.getTokenSession().set("username", user.getUsername());
```

模块二会通过以下方式获取当前用户：

```java
Integer userId = StpUtil.getLoginIdAsInt();
Integer roleId = (Integer) StpUtil.getTokenSession().get("roleId");
```

角色约定：

| roleId | 角色 | 模块二权限 |
| --- | --- | --- |
| 1 | super_admin | 管理员，可访问全部模型、数据集、实验、文件 |
| 2 | admin | 管理员，可访问全部模型、数据集、实验、文件 |
| 3 | user | 普通用户，只能访问自己的资源 |

前端调用模块二接口时必须带登录 token：

```http
Authorization: Bearer <token>
```

## 3. 当前已接入鉴权的接口

拦截器位置：

```text
backend/src/main/java/com/tss/platform/module1/interceptor/PermissionInterceptor.java
```

注册位置：

```text
backend/src/main/java/com/tss/platform/config/WebConfig.java
```

当前已接入登录鉴权的路径：

```java
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

公开路径：

```text
POST /api/user/register/username
POST /api/user/register/mobile
POST /api/user/sms/code
POST /api/user/forget/password
POST /api/user/login
GET  /api/files/health
```

说明：`/api/files/health` 用于 MinIO 健康检查，不要求登录；其他 `/api/files/**` 接口已要求登录。

## 4. 模块二已新增资源归属字段

模块二相关表已新增字段：

```text
owner_user_id
```

覆盖实体：

```text
ModelAsset
ModelVersion
ModelUploadSession
DatasetAsset
DatasetVersion
DatasetUploadSession
TrainingExperimentVersion
```

对应业务：

| 资源 | owner 写入时机 |
| --- | --- |
| 模型资产 | 创建模型或模型上传完成时 |
| 模型版本 | 模型上传完成或创建版本时 |
| 模型上传会话 | 初始化上传会话时 |
| 数据集资产 | 创建数据集或数据集上传完成时 |
| 数据集版本 | 数据集上传完成或创建版本时 |
| 数据集上传会话 | 初始化上传会话时 |
| 训练实验版本 | 创建训练实验或实验版本时 |

如果服务器配置为：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
```

部署后 Hibernate 会自动给数据库加列。旧数据的 `owner_user_id` 为空，普通用户默认不可见，管理员可见。

## 5. 资源访问规则

统一规则：

| 操作 | 普通用户 | 管理员 |
| --- | --- | --- |
| 查询列表 | 只返回 `owner_user_id = 当前用户ID` 的数据 | 返回全部 |
| 查询详情 | 只能查自己的数据 | 可查全部 |
| 删除资源 | 只能删自己的数据 | 可删全部 |
| 上传资源 | 自动写入当前用户 ID | 自动写入当前管理员 ID |
| 下载文件 | 只能下载自己路径下的文件 | 可下载全部 |
| 创建训练实验 | 只能选择自己有权限访问的模型和数据集 | 可选择全部 |

模块二新增了统一辅助类：

```text
backend/src/main/java/com/tss/platform/security/AuthContext.java
```

核心逻辑：

```java
currentUserId()      // 当前登录用户 ID
isAdmin()            // roleId 为 1 或 2
canAccessOwner(id)   // 管理员或资源归属当前用户
requireOwnerAccess() // 无权限时抛出异常
```

## 6. MinIO 文件隔离规则

当前 MinIO 仍然使用一个服务端账号连接，不需要为每个业务用户创建 MinIO 账号。

用户隔离通过数据库 owner 字段和对象路径共同实现：

```text
users/{userId}/models/...
users/{userId}/datasets/...
users/{userId}/files/...
```

普通用户访问 `/api/files/download`、`/api/files/delete` 时，只允许访问自己前缀下的对象：

```text
users/{当前用户ID}/
```

管理员不受此前缀限制。

注意：前端不要直接暴露 MinIO 原始地址给普通用户下载，应通过后端 `/api/files/**` 或业务接口完成权限校验后再访问。

## 7. CV/NLP 文件格式校验

数据集上传已按任务类型做格式校验。

CV 数据集：

```text
只支持 .zip，或通过 /api/dataset/upload/folder 上传文件夹
zip / 文件夹内必须至少包含一个图片文件
图片后缀支持 .jpg、.jpeg、.png、.bmp、.gif、.webp、.tif、.tiff
支持 cvTaskType，默认 UNLABELED
支持 annotationFormat，默认 NONE
```

CV 文件夹上传：

```text
至少包含一个图片文件
标注文件按 annotationFormat 白名单校验
会打包为 zip 后写入 MinIO
```

CV 标注格式规则：

```text
NONE / FOLDER_CLASSIFICATION / MASK：只允许图片
CSV：允许 .csv，且必须包含标注文件
YOLO：允许 .txt、.yaml、.yml，且必须包含标注文件
COCO / LABELME：允许 .json，且必须包含标注文件
VOC：允许 .xml，且必须包含标注文件
OTHER：允许 .txt、.json、.xml、.csv、.yaml、.yml
```

NLP 数据集：

```text
支持 .txt、.json、.jsonl、.csv、.xlsx、.xls、.pdf、.docx、.xml
也支持 .zip
zip 内必须包含 .txt、.json、.jsonl、.csv、.xlsx、.xls、.pdf、.docx 或 .xml 文件
```

模型上传：

```text
模型文件只支持 .zip
完成上传时必须提供模型类型 type 和说明 remark
```

## 8. 联调测试建议

建议模块一和模块二按下面顺序联调。

### 8.1 登录并获取 token

```bash
curl -X POST http://127.0.0.1:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"type":"account","username":"testuser01","password":"test123"}'
```

确认响应里包含：

```json
{
  "userId": 1,
  "roleId": 3,
  "token": "xxx"
}
```

### 8.2 验证模块二接口必须登录

不带 token 访问：

```bash
curl http://127.0.0.1:8080/api/model/list
```

预期：返回请先登录或鉴权失败。

带 token 访问：

```bash
curl http://127.0.0.1:8080/api/model/list \
  -H "Authorization: Bearer <token>"
```

预期：正常返回当前用户可见的模型列表。

### 8.3 验证用户隔离

建议准备两个普通用户 A、B：

1. 用户 A 登录并上传模型/数据集。
2. 用户 B 登录后查询模型/数据集列表。
3. 用户 B 不应看到用户 A 上传的数据。
4. 用户 B 使用用户 A 的资源 ID 查询详情或删除，应该失败。
5. 管理员登录后可以看到 A、B 的全部资源。

### 8.4 验证训练实验隔离

普通用户创建训练实验时，只能使用自己有权限访问的模型版本和数据集版本。

建议测试：

```text
用户 A 创建模型和数据集
用户 B 尝试用 A 的模型/数据集创建训练实验
预期：失败
管理员尝试使用任意用户的模型/数据集创建训练实验
预期：成功
```

### 8.5 验证文件格式

CV：

```text
上传非 zip 文件，应失败
上传 zip 但内部没有图片，应失败
上传包含图片的 zip，应成功
```

NLP：

```text
上传 .txt/.json/.jsonl/.csv/.xlsx/.xls/.pdf/.docx/.xml，应成功
上传非法后缀，应失败
上传 zip 但内部没有 NLP 白名单格式文件，应失败
```

## 9. 模块一需要重点确认

模块一联调时请重点确认：

1. 登录成功后 `StpUtil.getLoginIdAsInt()` 能稳定拿到用户 ID。
2. TokenSession 中一定写入 `roleId`。
3. `roleId=1/2` 作为管理员，`roleId=3` 作为普通用户。
4. 前端所有模块二接口请求都携带 `Authorization: Bearer <token>`。
5. 如果将来角色体系扩展，需要同步调整模块二 `AuthContext.isAdmin()` 的判断逻辑。

## 10. 当前仍建议后续完善的点

当前版本可交付模块一联调，但仍建议后续继续完善：

| 项目 | 说明 |
| --- | --- |
| 操作审计 | 模型、数据集、训练实验的上传、删除、状态变更还可以统一接入审计日志 |
| 日志检索 | `/api/log/list` 建议支持用户、时间范围、操作类型、状态分页查询 |
| 权限矩阵 | 当前主要按 `roleId` 和资源 owner 判断，后续可扩展为表结构化权限 |
| 旧数据迁移 | 旧资源 `owner_user_id` 为空，若需要普通用户可见，需要补数据 |
| 错误码统一 | 模块二部分接口使用 `ApiResponse`，模块一使用 `Result`，后续可统一响应格式 |

## 11. 结论

模块二目前已经完成与模块一登录鉴权体系的基础对接，可以交付模块一进行联调。

联调核心不是 MinIO 多账号，而是：

```text
Sa-Token 当前登录用户 ID
TokenSession.roleId
数据库 owner_user_id
后端接口按 owner_user_id 过滤
MinIO 对象路径按 users/{userId}/ 隔离
```

模块一只要保证登录 token、用户 ID 和 `roleId` 稳定可用，模块二即可完成普通用户资源隔离和管理员全量访问。
