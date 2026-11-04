# tss-data-labeling：后端分支

本分支 `backend` 保存 2026-11-03 本地 Trunk 工作区的源码快照，包含尚未提交到原仓库的修改；不代表生产环境已验收。

两个分支分别保存前端与后端，请克隆到两个目录，不要把两条分支相互合并：

```bash
git clone --branch frontend --single-branch https://github.com/tssai-lab/tss-data-labeling.git frontend
git clone --branch backend --single-branch https://github.com/tssai-lab/tss-data-labeling.git backend
```

## 当前功能范围

- 统一登录、平台选择页与训练平台：现有源码。
- 数据标注平台 Demo：已有人工标注页面与独立 Python 服务。
- 新数据标注平台：目前只有待接入入口与 V1.1 产品、架构、数据库、接口和分工设计，尚未完成新业务服务。最近的数据库审查建议尚未全部落实到设计稿或建表脚本。
- 后端分支 `docs/labeling-v1.1/README.md` 为新标注平台文档索引。

## 配套运行

前端按下方开发说明安装依赖并配置代理；训练后端按后端分支 `backend/README.md` 配置数据库和依赖；Demo 按后端分支 `manual-label/README.md` 启动。仓库不包含本机数据库、用户上传数据、运行凭据、依赖目录及构建产物。文中 `/home/leriger/` 绝对路径描述原维护者环境，其他机器应按实际配置替换。

# TSSAIPlatform

后端功能修改入口、影响范围和必跑测试见[代码维护索引](backend/doc/代码维护索引.md)。执行证据和未验证范围见[代码治理 SOP](backend/doc/code-governance-sop.md)。

## 本地测试账号

拉取代码不会同步其他人的数据库。首次部署时，先按 [后端说明](backend/README.md) 配置 PostgreSQL、MinIO 等依赖并启动项目，再手动执行测试账号初始化脚本。

以下命令从本目录（`Trunk`）执行，适用于本地数据库容器名为 `tss-trunk-postgres`、数据库用户和库名均为 `tss` 的环境：

```bash
docker exec -i tss-trunk-postgres psql -v ON_ERROR_STOP=1 -U tss -d tss < backend/scripts/init-local-test-users.sql
```

其他环境请替换容器名、数据库用户名和库名。直接使用 PostgreSQL 客户端时：

```bash
psql -h 127.0.0.1 -p 5433 -U tss -d tss -v ON_ERROR_STOP=1 -f backend/scripts/init-local-test-users.sql
```

脚本要求事先完成 `backend/src/main/resources/db/module1-schema-postgresql.sql` 中的用户表和角色初始化；这是后端首次部署步骤的一部分。

| 角色 | 用户名 | 初始密码 |
| --- | --- | --- |
| 普通用户 | `user01` | `123456` |
| 普通用户 | `user02` | `123456` |
| 管理员 | `admin01` | `123456` |

脚本仅创建不存在的账号，可以重复执行；已有账号的密码、角色、禁用状态或删除状态均不变。因此已有同名账号时，仍需使用该账号原来的密码。

这些固定密码账号仅用于本地测试。脚本不会随应用启动自动执行，不要在正式数据库执行。


## 统一工作空间

统一登录和平台选择页面保留，训练服务位于后端 `backend/`。

### 本机登录会话保持

本机启动脚本 `/home/leriger/tss-trunk-local/start-backend.sh` 已设置 `AUTH_SESSION_STORE=redis`，会话保存在专用Redis（6380），后端重启后未过期的登录继续有效。令牌有效期仍为24小时；浏览器勾选自动登录才会跨浏览器会话保留令牌。首次从内存模式切换需重新登录。其他环境默认配置和服务器部署未修改。

## 整体规划与当前进度

[项目整体设计与开发规划](项目整体设计与开发规划.md)说明统一入口与训练平台的代码位置。

## 本地手动标注演示模块

2026-11-02 从 GitHub demo 分支恢复手动标注。统一平台选择页提供训练平台和数据标注平台入口。前端页面为 `src/pages/label-projects` 和 `src/pages/manual-label`，后端位于后端 Trunk 的 `manual-label/`。启动脚本为 `/home/leriger/tss-trunk-local/start-manual-label.sh`；本地数据不与服务器共享。
