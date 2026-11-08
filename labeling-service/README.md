# 标注服务本地联调

## 接口

本地地址默认为 `http://127.0.0.1:8082`。身份与项目接口：

- `GET /api/labeling/v1/health`：检查服务进程。
- `GET /api/labeling/v1/me`：验证统一登录并建立/读取人工 actor。
- `GET /api/labeling/v1/admin/users?limit=20&offset=0`：标注管理员查看已建立映射的用户。
- `PATCH /api/labeling/v1/admin/users/{actorId}`：标注管理员修改他人状态或管理员权限，需 reason。
- `POST /api/labeling/v1/projects`：创建项目，调用者自动成为 owner。
- `GET /api/labeling/v1/projects?scope=owned&limit=20`：列出自己的项目。
- `GET /api/labeling/v1/projects/{projectId}`：读取自己的项目和规范版本。
- `POST /api/labeling/v1/projects/{projectId}/spec-versions`：创建标签规范版本。

项目业务ID在数据库中是 UUID，API传规范UUID字符串。统一身份ID不转成UUID，按身份服务原值保存。

## 身份验证

标注服务把请求中的 `Authorization` 头转交给统一登录后端的 `/api/user/current-user` 验证。默认训练后端地址为 `http://127.0.0.1:8080`，其他环境设置 `LABELING_IDENTITY_BASE_URL`。标注服务不能单独验证训练后端的进程内会话；统一身份服务不可用时，业务请求失败关闭并返回503。

数据库和迁移账号使用外部环境变量配置：`LABELING_DATABASE_URL`、`LABELING_DATABASE_USERNAME`、`LABELING_DATABASE_PASSWORD`、`LABELING_MIGRATION_USERNAME`、`LABELING_MIGRATION_PASSWORD`。凭据不要提交到代码库。

## 示例数据

开发库迁移完成后，可从已登录账号的 `/api/user/current-user` 响应取得自己的 `data.id`，再运行本机示例脚本。该脚本会为这个真实身份创建一个图像分类项目与标签规范；不创建密码或伪造登录用户。

```bash
PGPASSWORD="$LABELING_DATABASE_PASSWORD" psql \
  -h 127.0.0.1 -p 5433 -U "$LABELING_DATABASE_USERNAME" -d tss_labeling \
  -v identity_user_id='统一身份接口返回的data.id' \
  -v display_name='本机联调用户' \
  -f src/main/resources/db/dev-seed.sql
```

示例项目ID固定为 `fa2336cf-6baf-4ff1-8f64-fc1fb886bdf1`。脚本仅供开发数据库使用，不作为生产数据迁移执行。

## 示例调用

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8082/api/labeling/v1/me

curl -X POST http://127.0.0.1:8082/api/labeling/v1/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: create-demo-project-001" \
  -H "Content-Type: application/json" \
  -d '{"name":"表面缺陷标注","description":"接口联调项目","taskType":"IMAGE_CLASSIFICATION"}'
```

创建项目和规范版本接口要求唯一 `Idempotency-Key`。相同用户、操作、键和请求会返回已创建的资源；复用同键提交不同请求会返回409。创建项目示例返回 `201`，响应体为 `{ "data": { ... }, "requestId": "..." }`。

## 图片导入

本功能分支增加 ZIP 分片上传、持久化解析、失败重试、JPEG/PNG 样本分页和 owner 授权原图。接口细则见 [图片导入 API](../docs/labeling-v1.1/08-image-import-api.md)，宋雷鸣可运行的调用示例见 [examples/README.md](examples/README.md)。

启动前将 `LABELING_STORAGE_ROOT` 指向独立的可写持久化目录，数据库与文件目录需要共同备份。Flyway 新增 V3 迁移，不改已发布的 V1/V2；迁移账户需有 DDL 权限，服务仍使用 tss_labeling_app。不要在共享库中手工重跑建表脚本。

```powershell
# Java 17 + Maven，在 labeling-service 目录
mvn test
# 安装了 Node 18+ 时启用真实 HTTP 调用示例测试
$env:LABELING_NODE_E2E = 'true'
mvn test
mvn package
```

集成测试自行启动临时 PostgreSQL 和身份服务桩，不连接现有业务数据库。Linux 必须以普通用户运行。正式联调仍需用真实统一登录和选定的数据集复验。领取、平台分类草稿与提交已有本地实现；Label Studio 和导出仍未实现，导入通过不能代表标注全链路已经完成。

## 任务包与领取（本地已实现，需部署 V4）

- `POST /api/labeling/v1/projects/{projectId}/package-plans`：owner 创建打包预览，默认每包 10 张、每张 1 人。
- `POST /api/labeling/v1/package-plans/{planId}/publish`：owner 原子发布计划并开放项目。
- `GET /api/labeling/v1/package-plans/{planId}`：owner 查看计划和包摘要。
- `GET /api/labeling/v1/task-pool`：查看可领取包简介。
- `POST /api/labeling/v1/packages/{packageId}/claims`：领取一个席位，支持幂等重试。
- `GET /api/labeling/v1/assignments?scope=mine`：本人领取列表。
- `GET /api/labeling/v1/assignments/{assignmentId}/items`：读取自己包内样本及规范。
- `GET /api/labeling/v1/assignments/{assignmentId}/samples/{sampleId}/file`：授权读取包内图片。

POST 均需 Idempotency-Key。完整字段、上限和错误码见 `../docs/labeling-v1.1/04-api.md` 的任务包首轮实现。
任务包阶段不包含结果保存；后续 V5 已补上平台草稿与提交，见下一节。放弃/回收领取、Label Studio 和导出仍未实现。

## 草稿与正式提交（V5，本地实现）

- `PUT /api/labeling/v1/assignments/{assignmentId}/drafts/{sampleId}`：版本校验后保存本人图片分类草稿。
- `GET /api/labeling/v1/assignments/{assignmentId}/drafts/{sampleId}`：刷新后读回；包内 items 也带草稿。
- `POST /api/labeling/v1/assignments/{assignmentId}/submissions`：携带幂等键与整包样本版本清单，冻结个人提交。
- `GET /api/labeling/v1/submissions/{submissionId}`：提交人/owner 查看冻结结果。
- `GET /api/labeling/v1/projects/{projectId}/submissions`：owner 分页查看提交。

当前 resultFormat 为 image-classification-v1，结果为 {"labelId":"规范标签ID"}。
Label Studio 原始 JSON 需适配器校验转换，尚未接入 editor-sync；导出与验收尚未实现。
调用示例、冲突处理和权限边界见接口文档末尾“草稿与提交首轮实现”。
