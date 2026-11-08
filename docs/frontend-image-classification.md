# 图片分类前端联调

当前分支 `feat/labeling-classification-submissions`，PR 目标 `frontend`。原图片导入和 8001 入口修复均已合入。

## 入口和范围

平台选择页“数据标注平台”进入 `/labeling`；原 Demo 保留 `/label-projects`。
接口前缀 `/api/labeling/v1`，使用现有统一登录 Token，所有归属与权限由后端检查。

已接入身份、本人项目、项目创建、分类规范、ZIP 校验与分片上传、解析进度/错误/重试、样本分页及 owner 授权预览。
新增任务包预览/发布、任务广场/领取、本人任务、领取内授权读图、分类草稿保存/读回、整包提交、项目结果列表和冻结提交详情。具体语义与验收见 [草稿与提交接入说明](frontend-classification-submissions.md)。

只接受首轮图片分类的 JPEG/PNG ZIP。整包增量计算 SHA-256，按服务器分片大小发送，兼容当前 HTTP 入口。
分类写入固定 `image-classification-v1` 与稳定 labelId；Label Studio 会话/同步、任务回收和导出尚未接入。

## 本地联调

```powershell
npm ci
$env:DEV_API_TARGET = 'http://47.111.225.144:8001'
$env:DEV_LABELING_TARGET = 'http://47.111.225.144:8001'
npm run dev:local
```

登录与标注请求使用同一套 8001 身份。数据库和图片持久化由后端负责，前端不配置数据库密码。
8001 已共享自动部署的 `/var/www` 静态资源，并单独转发标注 API；服务器配置与回滚见 [入口修复说明](labeling-8001-entry-fix.md)。

## 恢复与限制

本次页面会话内，同 ZIP 上传异常后查询 receivedBytes 续传。刷新后，最近批次按 actor/project 保存在当前浏览器，也可通过详情 URL 的 batch 参数找回。
后端尚无项目批次列表与按摘要恢复上传接口，因此不承诺跨浏览器或刷新后的 ZIP 断点续传。可以用已知批次编号找回已完成导入。

草稿保存失败后必须显式读回确认。整包提交在发出前保存 actor/assignment/幂等键/版本清单，回执丢失后可使用原请求确认；已提交结果只读。服务端始终执行最终授权、成员与版本校验。

## 验证

2026-11-08 在 8001 建立独立合成图片联调项目，两个已有账号验证上传、分页、授权图片摘要、分类读回、版本冲突、独立提交、幂等重放、提交后禁止写入、跨标注员读取拒绝、owner 查看两份结果。无凭据验收记录见 [验证记录](frontend-classification-verification.json)。
这是服务器接口验证；前端类型、单元测试与构建独立验证，不冒充浏览器点击验收。

## 发布

功能分支推送后创建 PR 到 frontend，合并后由现有 Actions 更新共享静态资源。页面代码在 PR 合并并部署前不会自动出现在服务器。部署脚本同时检查 80 和 8001 入口，保留统一登录、训练及原 Demo。
