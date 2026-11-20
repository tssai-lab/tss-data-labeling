# TSS AI Platform 与数据标注服务

本仓库工作区包含训练平台、统一入口/平台选择、手动标注 Demo，以及新数据标注业务服务。具体代码目录和是否包含某个功能，以当前检出的分支和文件为准；不要把某一功能分支的源码状态误当成服务器部署状态。

新标注后端代码位于 `labeling-service/`，当前本地 `labeling-backend` 分支 HEAD 为 `b8a3040`。它包含身份/项目、图片与能耗输入、人工任务、AI 执行接口、需求方审核、集成建议、成果快照和导出实现。概览见 [标注服务说明](labeling-service/README.md)，产品/技术资料索引见 [V1.1 文档总览](docs/labeling-v1.1/README.md)。

多人协作时，先确认团队约定的目标分支，再检出相应分支；常见目标分支包括 `backend`、`frontend`、`labeling-backend` 和 `frontend-local-integration`。这些分支的代码范围及提交进度可能不同，合并前应比较 commit 和文件差异，不要仅凭分支名称推断两者已经同步。

**状态边界：** 本 README 说明当前检出的代码布局，不承诺 GitHub 分支、服务器、数据库迁移或 CI 与本工作区一致。上线情况应查看对应 workflow、服务器部署 revision 和数据库 Flyway 历史。

## 功能目录

- `backend/`：统一身份、训练等既有后端能力。
- `frontend/`：统一前端及平台入口（以当前分支实际文件为准）。
- `labeling-service/`：独立的新标注业务后端及 Flyway 迁移。
- `manual-label/`：旧手动标注演示服务，属于兼容/演示代码，不代表新标注平台业务实现。
- `docs/labeling-v1.1/`：新标注平台产品、架构、接口、编辑器和进度文档。

## 配套运行

各模块的开发和运行方式分别见 `backend/README.md`、`frontend/README.md`、`labeling-service/README.md` 和 `manual-label/README.md`（文件是否存在以当前分支为准）。仓库不包含本机数据库、用户上传数据、运行凭据、依赖目录及构建产物。文中 `/home/leriger/` 绝对路径描述维护者环境，其他机器应按实际配置替换。

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
