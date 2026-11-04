# 标注服务本地联调

## 接口

本地地址默认为 `http://127.0.0.1:8082`。身份与项目接口：

- `GET /api/labeling/v1/health`：检查服务进程。
- `GET /api/labeling/v1/me`：验证统一登录并建立/读取人工 actor。
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

创建项目和规范版本接口要求唯一 `Idempotency-Key`。相同用户、操作、键和请求会返回已创建的资源；复用同键提交不同请求会返回409。创建项目示例返回 `201`，响应体为 `{ "data": { ... }, "requestId": "..." }`。上传、领取、Label Studio、草稿提交与导出接口仍未实现；不要据此认为全链路已经完成。
