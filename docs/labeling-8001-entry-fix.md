# 8001 标注入口修复（2026-11-07）

## 原因

frontend PR #5 已合并，最新统一前端部署到宿主机 `/var/www`，由 80 端口提供。
8001 来自独立的 `tss-trunk-8001-frontend-1` 容器，仍提供 11 月 3 日镜像中的旧静态文件；其 `/api/` 全部转发到训练后端，缺少标注专用路径。
因此 80 端口部署成功并不代表 8001 更新，8001 登录令牌请求 `/api/labeling/v1/me` 会进入错误后端并返回 404。

## 修复

运行 `scripts/connect-labeling-8001.sh`，仅重建 frontend 服务：

- 持久化只读挂载 `/var/www` 到容器静态目录，两处入口共享每次 Actions 更新的同一套文件。
- 持久化挂载专用 Nginx 配置，优先将 `/api/labeling/` 转发到现有标注服务 8083。
- 保留 8001 现有登录/训练后端、Demo API 和数据库。
- 不改变标注服务身份来源；现有 systemd 配置已将统一身份指向 8001。
- 不复制密码或令牌到仓库，不迁移数据库，不修改现有用户权限。

脚本固定当前部署路径与 Docker 子网，先备份 Compose 并检查配置；激活失败时恢复原 Compose 并重建 frontend。备份记录在服务器 `/opt/tss-trunk-8001/backups/labeling-entry-*`。

## 接口契约核对

新版 04-api.md 的 owner DRAFT/OPEN 图片预览、ARCHIVED 只读、PAUSED 禁止读图与当前前端一致；保存、编辑器同步和提交仍未实现，不能启用虚假保存按钮。
`/me` 返回的 canCreateProject/canAnnotate/labelingAdmin 是标注独立权限，不从训练 roleId 推导。
403 表示标注权限被拒绝，保留统一登录；401 才清除失效令牌。
管理员 PATCH 成功明确允许 data=null；当前工作台尚无管理修改页面，后续接入此接口时需单独兼容空成功响应，项目等需要对象的读取仍需校验非空数据。
任务包与用户管理接口已进入 backend，但本次入口修复不声称已完成这些页面。

## 验证

比较 8001 返回的 index.html 与 `/var/www/index.html` 摘要；使用 8001 登录令牌请求同端口标注 `/me` 和本人项目列表；检查静态脚本、旧 Demo API 和训练当前用户接口。浏览器使用 `http://47.111.225.144:8001/#/platforms`，点击“数据标注平台”进入 `#/labeling`。

现场验证结果：上述接口均返回 200；8001 与 80 首页内容一致，主脚本可读取且包含 `/labeling` 路由。原 admin01 登录已验证可用，先前关于密码无效的结论仅对应另一套 80 端口训练登录接口。浏览器控制工具仍报错，未把接口验证宣称为点击/视觉验收。

`deploy-labeling-frontend.sh` 现增加 8001 首页摘要和标注 API 错误包装校验；失败会沿用入口回滚逻辑，避免只验证 80 端口就报告统一前端发布成功。

## 回滚

从脚本输出的备份目录恢复 `/opt/tss-trunk-8001/compose.yml`；如备份含 frontend-labeling.conf 也恢复；在该目录执行：

```sh
docker compose --env-file runtime.conf up -d --no-deps frontend
```

只读挂载切回镜像内部静态资源，训练后端、数据库和 Demo 服务不重建。
