# 图片分类草稿与整包提交（2026-11-08）

分支 `feat/labeling-classification-submissions`，目标 `frontend`。
依据 backend 的 04-api.md“草稿与提交首轮实现”，同时核对 ResultController/ResultService 和 TaskController/TaskService。

## 页面

- `/labeling`：本人项目、创建项目、分类规范、导入和 owner 预览。
- 项目详情：READY 批次生成打包预览，确认发布；查看项目下各人的冻结提交。
- `/labeling/tasks`：图片分类任务广场，查看剩余席位并领取。
- `/labeling/assignments`：本人领取列表。
- `/labeling/assignments/:assignmentId`：全部分页样本、冻结规范、授权图片、草稿保存/读回、整包提交。
- `/labeling/submissions/:submissionId`：只读提交快照，以绑定规范的稳定 labelId 显示。

原 Demo、训练和统一登录入口保留。

## 保存与提交

保存格式固定 `image-classification-v1`，result 为 `{labelId}`，清空为 `{}` 且 explicitDone=false；不发送标签显示名或 Label Studio 原始 JSON。
草稿 GET 成功才允许编辑，PUT 使用该份草稿的 revision。任何保存失败或不匹配回执都会锁定再次保存，用户需显式读回服务器草稿后继续；409 不自动覆盖。

任务包加载完整 cursor 链。只有每个样本都保存有效标签，才能生成整包版本清单。未保存选择阻止提交；服务端仍负责最终版本与整包完整性校验。
POST 提交前把 actor、assignment、原始幂等键与版本清单写入 sessionStorage；回执丢失后保留同一份请求，刷新可点击“确认本次提交”。不换键生成第二份提交。版本冲突或完整性失败后需重新加载整包，不能用新版本悄悄覆盖原版本清单。

提交成功后前端锁定编辑并显示独立 submissionId。草稿已保存不等于任务已提交；多人席位不互相覆盖。选择其他图片和导航离开前会提醒未保存选择。

## 暂未接入

Label Studio 受控会话及 editor-sync、任务释放/回收、导出尚无完整后端能力；本次只启用平台直接分类写入。不并行启动外部编辑器同步写入。
用户管理不属于本轮分类主流程。403 保留统一登录；401 清理失效登录。
项目仍缺服务端批次列表；可用详情 URL 或当前浏览器保存的最近批次编号恢复图片浏览。

## 联调验收

使用 8001 原统一登录和标注转发，数据库由后端连接。
1. owner 建项目、保存包含稳定标签 ID 的规范，导入两张 JPEG/PNG。
2. 每包两图、两人生成预览并发布；两位账号各领取一个席位。
3. 各自选择不同分类并保存，刷新读回仍一致；旧 baseRevision 应返回 409。
4. 完成全包后提交，保存回执包含 submissionId；同键同清单返回同一 ID。
5. 已提交任务只读；owner 可分别查看两人的提交快照，其他标注员不能读取对方结果。
6. 回归登录、8001 标注身份、训练入口、原 Demo 和上传。

若服务器尚未部署 V5 与结果控制器，界面显示真实接口错误；不回退为假保存。前端合并与服务器端到端验收分别记录。

## 本次验证结果

- TypeScript、Biome 与生产构建通过。
- 当前仓库 272 项测试全部通过，新增 10 项分类与任务客户端测试；执行时排除用户的嵌套克隆，避免重复计数。
- 服务器双账号验证通过，记录见 frontend-classification-verification.json。联调数据是独立项目与两张合成图片。
- 尚未完成浏览器点击/视觉验收；本次代码须通过 PR 合并与部署后才进入线上页面。
