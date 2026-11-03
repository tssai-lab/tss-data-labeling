# 模块一 API 接口文档

本文档描述模块一当前后端代码实际提供的 API，范围包括用户注册登录、用户管理、角色查询、操作日志和系统日志接口。模块一代码主要位于 `com.tss.platform.module1` 包下，接口统一返回 `Result<T>`。

## 1. 通用约定

### 1.1 基础信息

| 项目 | 说明 |
| --- | --- |
| 默认服务地址 | `http://localhost:8080` |
| 请求编码 | UTF-8 |
| JSON 请求头 | `Content-Type: application/json` |
| 鉴权请求头 | `Authorization: Bearer <token>` |

### 1.2 统一响应格式

模块一接口统一返回 `Result<T>`：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

常见 `code`：

| code | 含义 |
| --- | --- |
| `200` | 成功 |
| `400` | 业务失败 |
| `401` | 未登录 |
| `403` | 无权限 |
| `500` | 服务端错误 |

说明：
- Controller 内部业务失败通常仍返回 HTTP 200，但响应体 `code=400`。
- 被 `PermissionInterceptor` 拦截的未登录/无权限请求会分别返回 HTTP 401/403，并携带模块一 `Result` 响应体。

### 1.3 角色约定

数据库初始化脚本 `src/main/resources/db/module1-schema-postgresql.sql` 默认创建三个角色：

| roleId | roleName | 说明 |
| --- | --- | --- |
| `1` | `super_admin` | 超级管理员 |
| `2` | `admin` | 普通管理员 |
| `3` | `user` | 普通用户 |

当前用户接口 `/api/user/current-user` 会将角色转换为：

| roleId | role |
| --- | --- |
| `1` | `super_admin` |
| `2` | `normal_admin` |
| `3` | `user` |

系统用户管理接口 `/api/system/user/*` 接收的 `role` 字段由 `UserRoleUtil.parseRoleId` 解析：

| 请求值 | 解析结果 |
| --- | --- |
| `super_admin` / `超级管理员` / `超管` | `1` |
| `normal_admin` / `普通管理员` | `2` |
| 其他值或空值 | `3` |

状态字段 `status` 在 `/api/system/user/*` 中按字符串解析：`enabled` 或 `启用` 为启用，其余值为禁用；空值默认启用。

### 1.4 鉴权现状

`PermissionInterceptor` 中定义了以下公开接口：

```text
POST /api/user/register/username
POST /api/user/register/mobile
POST /api/user/sms/code
POST /api/user/forget/password
POST /api/user/login
```

`/api/user/current-user` 和 `/api/user/logout` 需要登录，但不要求管理员。

除上述接口外，`PermissionInterceptor` 逻辑上将 `/api/user/**`、`/api/log/**`、`/api/role/**`、`/api/system/user/**`、`/api/system/log/**` 识别为管理员接口，管理员定义为 `roleId=1` 或 `roleId=2`。

代码现状需要注意：`WebConfig.addInterceptors` 当前实际只注册了 `/api/user/**` 和 `/api/log/**` 等路径，没有注册 `/api/role/**`、`/api/system/user/**`、`/api/system/log/**`。因此在未补充 `WebConfig` 前，这三组接口不会被 `PermissionInterceptor` 自动拦截；其中部分接口内部会调用 `StpUtil` 做角色判断，但列表类接口本身没有显式鉴权。

## 2. 用户注册、登录与当前用户接口

基础路径：`/api/user`

### 2.1 用户名注册

```http
POST /api/user/register/username
```

公开接口，无需登录。

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 用户名，6-20 位字符，不能使用完整的 11 位手机号格式 |
| `password` | string | 是 | 密码，正则 `^\w{6,16}$` |
| `confirmPassword` | string | 是 | 确认密码，必须与 `password` 一致 |

请求示例：

```json
{
  "username": "testuser",
  "password": "password123",
  "confirmPassword": "password123"
}
```

处理规则：
- 新用户默认 `roleId=3`、`status=true`。
- 密码使用 BCrypt 加密保存。
- 邮箱默认生成 `{username}@default.com`。
- 如果用户名是完整的 11 位手机号格式，返回“用户名不能使用手机号格式”。
- 如果存在同名未删除用户，返回用户名已存在。
- 如果存在同名软删除用户，会恢复该账号。

成功响应：

```json
{
  "code": 200,
  "message": "注册成功",
  "data": null
}
```

### 2.2 发送短信验证码

```http
POST /api/user/sms/code
```

公开接口，无需登录。

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mobile` | string | 是 | 手机号，正则 `^1[3-9]\d{9}$` |
| `purpose` | string | 否 | `LOGIN_REGISTER`（默认，登录/注册）或 `RESET_PASSWORD`（重置密码） |

请求示例：

```json
{
  "mobile": "13900000000",
  "purpose": "LOGIN_REGISTER"
}
```

规则：
- 验证码为 6 位数字。
- 正式阿里云模式由号码认证服务生成并校验验证码；平台只在内存保存 300 秒“已发码”标记和一次性消费状态。
- 同一手机号 60 秒内只能发送一次。
- 正式短信供应商每天最多成功发送 50 条；旧配置中的 `0` 也按 50 条处理，避免意外关闭费用保护。
- 正式环境 `sms.provider` 默认 `disabled`，未配置时明确返回“短信服务尚未开通”。
- 只有隔离的本机 `dev` 配置允许 `sms.provider=local` 且 `sms.expose-code=true`；阿里云和 Main 禁止返回或记录明文验证码。

开发模式响应示例：

```json
{
  "code": 200,
  "message": "验证码发送成功",
  "data": {
    "code": "123456",
    "expireSeconds": 300
  }
}
```

### 2.3 手机号注册

```http
POST /api/user/register/mobile
```

公开接口，无需登录。

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mobile` | string | 是 | 手机号 |
| `smsCode` | string | 是 | 短信验证码 |
| `password` | string | 是 | 密码，正则 `^\w{6,16}$` |
| `confirmPassword` | string | 是 | 确认密码 |
| `username` | string | 是 | 用户名，6-20 位字符，不能使用完整的 11 位手机号格式 |

请求示例：

```json
{
  "mobile": "13900000000",
  "smsCode": "123456",
  "username": "testuser",
  "password": "password123",
  "confirmPassword": "password123"
}
```

处理规则：
- 验证码校验通过且注册成功后会被消费。
- 新用户默认 `roleId=3`、`status=true`。
- 如果手机号或用户名已存在，会返回对应业务错误。
- 如果匹配到软删除用户，会恢复该用户。

成功响应 `data`：

```json
{
  "username": "testuser",
  "mobile": "13900000000"
}
```

### 2.4 登录

```http
POST /api/user/login
```

公开接口，无需登录。

账号密码登录请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 是 | 固定为 `account` |
| `username` | string | 是 | 登录标识；输入完整的 11 位手机号时按手机号匹配，否则按用户名匹配 |
| `password` | string | 是 | 密码 |

请求示例：

```json
{
  "type": "account",
  "username": "admin",
  "password": "password123"
}
```

手机号验证码登录请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 是 | 固定为 `mobile` |
| `mobile` | string | 是 | 手机号 |
| `smsCode` | string | 是 | 短信验证码 |

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `userId` | 用户 ID |
| `username` | 用户名 |
| `mobile` | 手机号 |
| `roleId` | 角色 ID |
| `status` | 是否启用 |
| `token` | Sa-Token token 值 |

响应示例：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "userId": 1,
    "username": "admin",
    "mobile": null,
    "roleId": 1,
    "status": true,
    "token": "6db8..."
  }
}
```

### 2.5 退出登录

```http
POST /api/user/logout
```

需要登录。后端调用 `StpUtil.logout()`，成功后返回：

```json
{
  "code": 200,
  "message": "退出登录成功",
  "data": null
}
```

### 2.6 获取当前用户

```http
GET /api/user/current-user
```

需要登录。

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `id` | 用户 ID |
| `username` | 用户名 |
| `mobile` | 手机号 |
| `roleId` | 角色 ID |
| `role` | `super_admin`、`normal_admin` 或 `user` |
| `status` | 是否启用 |

响应示例：

```json
{
  "code": 200,
  "message": "获取当前用户成功",
  "data": {
    "id": 1,
    "username": "admin",
    "mobile": null,
    "roleId": 1,
    "role": "super_admin",
    "status": true
  }
}
```

### 2.7 忘记密码

```http
POST /api/user/forget/password
```

公开接口，无需登录。

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mobile` | string | 是 | 手机号 |
| `smsCode` | string | 是 | 验证码 |
| `newPassword` | string | 是 | 新密码，正则 `^\w{6,16}$` |

请求示例：

```json
{
  "mobile": "13900000000",
  "smsCode": "123456",
  "newPassword": "newpass123"
}
```

成功后更新该手机号对应用户的 BCrypt 密码，并消费验证码。

## 3. 管理端用户接口

基础路径：`/api/user`

这些接口在当前拦截器配置下属于管理员接口，除 `current-user` 和 `logout` 外，`/api/user/**` 的非公开接口需要 `roleId=1` 或 `roleId=2`。

### 3.1 新增用户

```http
POST /api/user/add
```

请求体为 `User` 实体字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 用户名 |
| `password` | string | 是 | 明文密码，后端 BCrypt 加密 |
| `email` | string | 否 | 邮箱 |
| `roleId` | integer | 否 | 角色 ID |
| `mobile` | string | 否 | 手机号 |

处理规则：
- 后端会把 `status` 设置为 `true`。
- 设置 `createdAt`、`updatedAt` 为当前时间。

### 3.2 重置用户密码

```http
POST /api/user/reset-password
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | integer | 是 | 用户 ID |
| `newPassword` | string | 是 | 新密码，正则 `^\w{6,16}$` |

### 3.3 查询用户列表

```http
GET /api/user/list
```

返回 `users` 与 `roles` 关联后的列表。当前 Mapper 使用：

```sql
SELECT u.*, r.role_name
FROM users u
JOIN roles r ON u.role_id = r.id
WHERE u.deleted_at IS NULL
```

注意：当前查询返回 `u.*`，其中包含 `password` 字段，前端或后续接口层应避免展示该字段。

### 3.4 分页查询用户

```http
POST /api/user/page
```

请求体 `UserQueryDTO`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `page` | integer | 否 | 页码，默认 `1` |
| `size` | integer | 否 | 每页数量，默认 `10` |
| `username` | string | 否 | 用户名筛选 |
| `mobile` | string | 否 | 手机号筛选 |
| `roleId` | integer | 否 | 角色筛选 |
| `status` | boolean | 否 | 状态筛选 |

响应 `data` 为 `PageResultDTO<Map<String,Object>>`：

| 字段 | 说明 |
| --- | --- |
| `records` | 当前页列表 |
| `total` | 总数 |
| `page` | 当前页 |
| `size` | 每页数量 |

代码现状提醒：`UserMapper.selectUserPage` 当前只有方法声明，未在该文件中看到 `@Select` 注解或 XML 绑定；如果运行环境未补充 Mapper XML，该接口会触发 MyBatis `Invalid bound statement`。

### 3.5 查询用户详情

```http
GET /api/user/detail/{userId}
```

返回指定未删除用户详情，Mapper 查询同样包含 `u.*` 和 `role_name`。

### 3.6 更新用户

```http
PUT /api/user/update
```

请求体 `UserUpdateDTO`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | integer | 是 | 用户 ID |
| `username` | string | 否 | 用户名 |
| `mobile` | string | 否 | 手机号 |
| `email` | string | 否 | 邮箱 |
| `roleId` | integer | 否 | 角色 ID |
| `status` | boolean | 否 | 启用状态 |

### 3.7 切换用户状态

```http
PUT /api/user/status/{userId}?status={status}
```

查询参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | boolean | 是 | `true` 启用，`false` 禁用 |

### 3.8 删除用户

```http
DELETE /api/user/delete/{userId}
```

软删除用户，将 `deletedAt` 设置为当前时间。

### 3.9 晋升普通管理员

```http
POST /api/user/promote-to-admin
```

接口内部要求当前登录用户 `roleId=1`，即仅超级管理员可操作。

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | integer | 是 | 目标用户 ID |

规则：
- 目标用户必须存在且未删除。
- 目标用户当前必须是普通用户 `roleId=3`。
- 成功后目标用户 `roleId` 更新为 `2`。

## 4. 系统用户管理接口

基础路径：`/api/system/user`

这组接口面向系统管理页面，参数命名更贴近前端展示，例如使用 `phone`、`role`、`enabled`。

权限规则：`WebConfig` 会把 `/api/system/user/**` 交给统一权限拦截器；用户管理写操作还会在 Controller 内按当前登录人和目标用户角色做二次校验。

### 4.1 查询系统用户列表

```http
GET /api/system/user/list
```

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `list` | 用户列表 |
| `total` | 列表数量 |

列表项由 `/api/user/list` 同源查询转换而来，额外兼容字段：
- `phone`：由 `mobile` 复制。
- `role`：转换为前端展示角色文本，如 `超管`、`普通管理员`、`普通用户`。
- `createdAt`：由 `created_at` 复制。

安全说明：用户列表使用后端白名单字段，不返回密码哈希等敏感信息。

### 4.2 新增系统用户

```http
POST /api/system/user/add
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 用户名 |
| `phone` | string | 是 | 手机号，保存到 `mobile` |
| `role` | string | 否 | 角色，按 `UserRoleUtil.parseRoleId` 解析，默认普通用户 |
| `status` | string | 否 | `enabled`/`启用` 为启用，其他为禁用；空值默认启用 |

请求示例：

```json
{
  "username": "worker01",
  "phone": "13900000000",
  "role": "普通用户",
  "status": "enabled"
}
```

处理规则：
- 后端为每次新增或恢复账号生成独立的 16 位随机临时密码，使用 BCrypt 加密保存。
- 明文临时密码只在本次成功响应的 `data.temporaryPassword` 中返回，不写日志、不保存明文；调用方应立即安全交付给用户。
- 邮箱默认 `{username}@default.com`。
- 超级管理员可以创建普通管理员或普通用户，但当前代码不支持创建或提升为超级管理员。
- 普通管理员只能创建普通用户。
- 如用户名或手机号对应软删除账号，后端会尝试恢复该账号。

成功响应示例：

```json
{
  "code": 200,
  "message": "新增用户成功",
  "data": {
    "temporaryPassword": "一次性随机临时密码"
  }
}
```

### 4.3 编辑系统用户

```http
PUT /api/system/user/edit
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | integer | 是 | 用户 ID |
| `username` | string | 否 | 新用户名 |
| `phone` | string | 否 | 新手机号 |
| `role` | string | 否 | 新角色 |
| `status` | string | 否 | 新状态 |

权限规则：
- 普通管理员只能编辑普通用户。
- 普通管理员不能分配管理员角色。
- 超级管理员不支持把普通账号提升为超级管理员。

### 4.4 删除系统用户

```http
DELETE /api/system/user/delete?id={userId}
```

规则：
- 不能删除当前登录账号。
- 普通管理员只能删除普通用户。
- 非超级管理员不能删除管理员账号。
- 删除为软删除。

### 4.5 切换系统用户状态

```http
PUT /api/system/user/toggleStatus
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | integer | 是 | 用户 ID |
| `status` | string | 是 | `enabled`/`启用` 为启用，其他为禁用 |

普通管理员只能切换普通用户状态。

### 4.6 检查用户名是否可用

```http
POST /api/system/user/checkUsername
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 用户名 |

响应 `data`：

```json
{
  "available": true
}
```

只检查未删除用户是否占用该用户名。

### 4.7 用户级 API 功能策略

基础路径：`/api/system/user/{userId}/api-policies`。只有超级管理员可以读取和修改；超级管理员账号本身不能被用户级策略锁定。

系统固定管理以下六个稳定功能组：

| 功能组 | 范围 |
| --- | --- |
| `MODEL_ASSET` | 模型资产 |
| `DATASET_ASSET` | 数据集资产 |
| `TRAINING_DEFINITION` | 训练代码与训练方案 |
| `TRAINING_TASK` | 训练任务 |
| `INFERENCE_TASK` | 推理任务 |
| `SYSTEM_ADMIN_AUDIT` | 系统管理与审计 |

无策略记录表示继承角色默认权限：允许继续进入原有角色和对象权限校验、并发不限。用户级策略只能收紧原有权限，不能给角色越权。

```http
GET /api/system/user/{userId}/api-policies
PUT /api/system/user/{userId}/api-policies/{featureGroup}
DELETE /api/system/user/{userId}/api-policies/{featureGroup}?version={version}
```

`PUT` 请求体：

```json
{
  "enabled": false,
  "maxConcurrentRequests": 2,
  "version": 0
}
```

- `enabled=false` 时，新进入该功能组的请求统一返回 HTTP 403；已运行的训练或推理任务不会被删除。
- `maxConcurrentRequests` 留空表示不限流，填写时必须大于等于 1；达到上限返回 HTTP 429。
- `version` 是乐观锁版本。并发修改冲突返回 HTTP 409，客户端必须刷新后重试，不能静默覆盖。
- `DELETE` 删除用户覆盖记录并恢复继承，数据库即时读取，无需重启、重新部署或重新登录。
- 策略修改成功和失败均写入权限变更审计；成功记录包含修改前后值，不记录令牌或密码。
- 策略数据库或共享 Redis 计数器不可用时返回 HTTP 503，不静默放行。

## 5. 角色接口

基础路径：`/api/role`

权限规则：`WebConfig` 已统一拦截 `/api/role/**`，这组接口仅管理员可访问。

### 5.1 查询角色列表

```http
GET /api/role/list
```

响应 `data` 为 `Role[]`：

| 字段 | 说明 |
| --- | --- |
| `id` | 角色 ID |
| `roleName` | 角色名 |
| `description` | 描述 |
| `createdAt` | 创建时间 |

### 5.2 查询角色详情

```http
GET /api/role/detail/{roleId}
```

如果角色不存在，返回 `code=400`、`message=角色不存在`。

### 5.3 查询角色选项

```http
GET /api/role/options
```

响应 `data` 为 Map，key 为角色 ID，value 为角色名：

```json
{
  "1": "super_admin",
  "2": "admin",
  "3": "user"
}
```

## 6. 操作日志接口

基础路径：`/api/log`

当前 `WebConfig` 已注册 `/api/log/**`，除登录放行接口外，`PermissionInterceptor` 会要求管理员权限。

操作类型枚举：

| 值 | 说明 |
| --- | --- |
| `1` | 新增 |
| `2` | 删除 |
| `3` | 修改 |
| `4` | 重置 |
| `5` | 登录 |
| `6` | 退出 |

操作对象枚举：

| 值 | 说明 |
| --- | --- |
| `users` | 用户 |
| `roles` | 角色 |
| `logs` | 日志 |

### 6.1 记录操作日志

```http
POST /api/log/record
```

请求体 `OperationLog`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | integer | 否 | 用户 ID |
| `userName` | string | 否 | 用户名 |
| `operationType` | string | 否 | 操作类型 |
| `operationObj` | string | 否 | 操作对象 |
| `ipAddress` | string | 否 | IP 地址 |
| `remarks` | string | 否 | 备注 |
| `status` | string | 否 | 状态，例如 `SUCCESS`、`FAIL` |

后端会覆盖设置 `operationTime` 为当前时间。

### 6.2 查询全部日志

```http
GET /api/log/list
```

返回 `operation_logs` 表全部记录。

### 6.3 分页查询日志

```http
POST /api/log/query
```

请求体 `OperationLogQueryDTO`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `page` | integer | 否 | 页码，默认 `1` |
| `size` | integer | 否 | 每页数量，默认 `10` |
| `userId` | integer | 否 | 用户 ID |
| `operationType` | string | 否 | 操作类型 |
| `operationObj` | string | 否 | 操作对象 |
| `status` | string | 否 | 状态 |
| `startTime` | string | 否 | 开始时间，反序列化为 `LocalDateTime` |
| `endTime` | string | 否 | 结束时间，反序列化为 `LocalDateTime` |

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `records` | 当前页日志 |
| `total` | 总数 |
| `page` | 当前页 |
| `size` | 每页数量 |

### 6.4 查询日志类型字典

```http
GET /api/log/types
```

响应 `data`：

```json
{
  "1": "新增",
  "2": "删除",
  "3": "修改",
  "4": "重置",
  "5": "登录",
  "6": "退出"
}
```

### 6.5 查询日志对象字典

```http
GET /api/log/objects
```

响应 `data`：

```json
{
  "users": "用户",
  "roles": "角色",
  "logs": "日志"
}
```

## 7. 系统日志接口

基础路径：`/api/system/log`

代码现状：`WebConfig` 当前未注册 `/api/system/log/**` 到 `PermissionInterceptor`。业务上建议仅管理员访问。

### 7.1 查询系统日志列表

```http
GET /api/system/log/list
```

查询参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `pageNum` | integer | 否 | 页码，默认 `1` |
| `pageSize` | integer | 否 | 每页数量，默认 `10` |
| `operationType` | string | 否 | 操作类型 |
| `operationObj` | string | 否 | 操作对象 |
| `status` | string | 否 | 状态 |

响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `list` | 当前页日志 |
| `total` | 总数 |
| `pageNum` | 当前页 |
| `pageSize` | 每页数量 |

后端按 `operationTime` 倒序排序。

### 7.2 查询系统日志类型选项

```http
GET /api/system/log/types
```

响应 `data` 为数组：

```json
[
  { "key": "1", "label": "新增" },
  { "key": "2", "label": "删除" },
  { "key": "3", "label": "修改" },
  { "key": "4", "label": "重置" },
  { "key": "5", "label": "登录" },
  { "key": "6", "label": "退出" }
]
```

### 7.3 查询系统日志对象选项

```http
GET /api/system/log/objects
```

响应 `data` 为数组：

```json
[
  { "key": "users", "label": "用户" },
  { "key": "roles", "label": "角色" },
  { "key": "logs", "label": "日志" }
]
```

## 8. 字段模型

### 8.1 User

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | integer | 用户 ID，自增 |
| `username` | string | 用户名 |
| `email` | string | 邮箱 |
| `password` | string | BCrypt 加密密码 |
| `roleId` | integer | 角色 ID |
| `status` | boolean | 是否启用 |
| `createdAt` | string | 创建时间 |
| `updatedAt` | string | 更新时间 |
| `deletedAt` | string | 软删除时间，`null` 表示未删除 |
| `mobile` | string | 手机号 |

### 8.2 Role

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | integer | 角色 ID |
| `roleName` | string | 角色名 |
| `description` | string | 描述 |
| `createdAt` | string | 创建时间 |

### 8.3 OperationLog

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | integer | 日志 ID |
| `userId` | integer | 用户 ID |
| `userName` | string | 用户名 |
| `operationType` | string | 操作类型 |
| `operationObj` | string | 操作对象 |
| `ipAddress` | string | IP 地址 |
| `operationTime` | string | 操作时间 |
| `remarks` | string | 备注 |
| `status` | string | 状态 |

## 9. 常见错误场景

| 场景 | 典型响应 |
| --- | --- |
| 未登录访问受保护接口 | HTTP 401，`{"code":401,"message":"请先登录","data":null}` |
| 普通用户访问管理员接口 | HTTP 403，`{"code":403,"message":"无权限访问，仅管理员可操作","data":null}` |
| 登录类型不正确 | `code=400`，提示使用 `account` 或 `mobile` |
| 账号密码错误 | `code=400`，提示用户名/手机号或密码错误 |
| 账号被禁用 | `code=400`，提示账号已被禁用 |
| 验证码错误或过期 | `code=400`，提示验证码错误或已过期 |
| 用户名重复 | `code=400`，提示用户名已存在 |
| 手机号重复 | `code=400`，提示手机号已注册或已被使用 |
| 删除当前登录账号 | `code=400`，提示不能删除当前登录账号 |
| 普通管理员操作管理员账号 | `code=400`，提示普通管理员只能操作普通用户 |

## 10. curl 测试示例

```bash
# 发送验证码，开发模式会返回 code
curl -X POST http://127.0.0.1:8080/api/user/sms/code \
  -H "Content-Type: application/json" \
  -d '{"mobile":"13900000000"}'

# 用户名登录
curl -X POST http://127.0.0.1:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"type":"account","username":"admin","password":"password123"}'

# 查询当前用户
curl http://127.0.0.1:8080/api/user/current-user \
  -H "Authorization: Bearer <token>"

# 查询用户列表
curl http://127.0.0.1:8080/api/user/list \
  -H "Authorization: Bearer <token>"

# 查询日志
curl -X POST http://127.0.0.1:8080/api/log/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"page":1,"size":10}'
```
