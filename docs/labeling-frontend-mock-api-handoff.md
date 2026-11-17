# 前端接口接入与剩余缺口交接

更新：2026-11-17。当前前端已按 backend 分支 `f68b8a8442c48db3088f8485e7813728e1c69ef0`（PR #37）更新。
本文件替代 11 月 16 日的缺口清单；旧版“ACCEPT 状态错误”“selectionId 不能消费”“导出/能耗接口未实现”等结论已失效。
依据：[最新接口契约](https://github.com/tssai-lab/tss-data-labeling/blob/f68b8a8442c48db3088f8485e7813728e1c69ef0/docs/labeling-v1.1/04-api.md)及同版本 Controller / Service 实现。

## 1. 正式页面现在使用哪些接口

统一前缀 `/api/labeling/v1`，统一登录令牌只放 Authorization 头；写操作使用 Idempotency-Key。后端错误原样显示，不转成演示成功。

| 功能 | 正式接口 | 前端实现 |
|---|---|---|
| 项目创建/规范 | /projects、/projects/{id}/spec-versions | index.tsx / SpecVersions.tsx；支持图片和站点日能耗 |
| 服务端模板 | GET /task-templates | 创建项目只开放服务器已注册且与本地可信编辑器匹配的模板 |
| 导入/续传 | 原 imports、chunks、complete、retry、samples | 复用分片上传；能耗 ZIP 使用固定 CSV 结构，不向后端发送任意 importerKey |
| 导入历史 | GET /projects/{id}/imports | 导入步骤展开服务器历史，恢复真实批次 |
| 固定范围 | POST /projects/{id}/result-selections，GET /result-selections/{id}/items、/statistics | 明确保存 sampleId/resultId/reviewRevision；批量审核可预览全部筛选结果 |
| 原子审核 | POST /projects/{id}/reviews | 用 REVIEW selectionId + action/reason；不再由结果 ID 猜 submissionId 后调用旧 sample-reviews |
| 通用修订 | POST /projects/{id}/results/{resultId}/revisions | 携带 sampleId/specVersionId/baseReviewRevision/resultFormat/result/reason；图片和能耗均可修订，保存后另行确认 |
| 人工再发布 | POST /projects/{id}/package-plans，POST /package-plans/{id}/publish | PUBLISH selectionId + referenceMode；SELECTED 引用真正的候选答案，支持人工/AI/OWNER/集成来源 |
| AI 执行 | POST /projects/{id}/ai-executions | PUBLISH selectionId；Agent 按任务类型查询，不向模型 Worker 边界发送人类凭据 |
| AI 重试 | POST /ai-executions/{id}/retries | 使用原执行 baseRevision，页面入口重试全部失败/取消范围，产生新 execution |
| AI 查询/分档 | 原 executions/results/confidence-policies | 查询不模拟推进；终态快照一致后才展示可审核答案；策略不自动确认 |
| 服务器历史 | GET /projects/{id}/rounds、/ai-executions、/package-plans | 不再要求记住所有编号；历史按服务端游标分页 |
| 多来源集成 | GET /aggregation-algorithms，POST 项目 /aggregations，GET /aggregations/{id}、/items | 明确勾选每个样本的多个 HUMAN/AI 原始候选；每参与者一票；派生答案不投票，建议另行审核 |
| 最终成果 | POST /projects/{id}/final-snapshots | FINALIZE selectionId，不再仅发送 sampleIds 固化浏览器未见的新答案 |
| 正式导出 | POST 项目 /exports，GET /exports/{id}、/download | 从勾选的冻结快照生成真实异步 ZIP；图片 classification-csv-zip，能耗 energy-jsonl-zip；带授权下载、过期/失败提示 |
| 项目状态 | POST /projects/{id}/pause、/resume、/archive | 原因、baseRevision、幂等键；归档后阻止结果写操作，仍可导出既有成果 |
| 草稿删除 | DELETE /projects/{id} | 仅 DRAFT 显示删除；发送原因和版本；以服务端回执为准，从列表刷新移除 |
| 本人恢复 | GET /me/submissions、/me/operation-receipts | “我的任务”展开查看本人提交与服务器操作回执；复制执行、集成、导出编号恢复查询 |
| 能耗授权输入 | owner/assignment 的 /samples/{id}/data | 编辑器只读取有权样本的结构化输入，不通过 CSV 文件地址取整份源数据 |
| 能耗草稿与提交 | 原 assignments/drafts、submissions | annotation-record-v1；inputHash 校验；所有样本保存完成后整包提交；响应丢失可恢复原提交键 |

真实适配器：`src/services/labelingOwner.ts`、`labelingStructured.ts`、`labelingTasks.ts`、`labeling.ts`。
真实页面：`src/pages/labeling/Live*.tsx`、`EnergyAssignment.tsx`、`ProjectHistory.tsx`、`ProjectManagement.tsx`、`OperationHistory.tsx`。

## 2. 新契约的关键区别

- 审核使用 selectionId 或 items[{sampleId,resultId,baseReviewRevision}]，不把 resultId 填入旧 submissionId 字段。
- 选择创建时 items[].reviewRevision；显式审核请求使用 baseReviewRevision。两个名字不能混用。
- 发布和 AI：selectionId 与 samples 互斥。新页面用 selectionId + referenceMode=NONE/SELECTED，并明确允许重新处理已确认样本。
- 首次发布仍可用 batchId；分包预览 packages 在发布前可以为空，展示 packageSampleCounts。
- 恢复计划后核对 projectId 和 specVersionId，不能把其他项目计划混进当前发布入口。
- AI 重试不使用演示 selectionId 字段，使用 baseRevision；不覆盖原执行成功结果。
- 集成不是旧演示 inputs/parameters 请求，而是每个样本明确 resultIds[]；返回建议不表示 ACCEPTED。
- 导出是项目 exports 作业，消费 finalSnapshotIds[]；不是 /final-snapshots/{id}/export，也不是浏览器拼 CSV/ZIP。
- includeOriginals 目前必须 false。ZIP 下载携带同一登录授权；不将 token 或后端返回的任意 URL 放进公开链接。
- 网络响应不确定时重放原键与原请求；确定的 410 范围过期或审核版本冲突后，重新获取范围再提交。
- 能耗标签取任务冻结规范；内置候选标签只作为创建首版规范时的可编辑起点，不替代专家语义确认。
- 能耗完整结果保留 annotations 点/区间、时区和输入摘要；人工自评不写入模型 confidence。

## 3. 仍未实现的后端能力

根据上述版本 04-api.md 第 15 节，以下能力仍无正式业务契约或实现，前端没有伪装为真实结果：

| 范围 | 当前边界 |
|---|---|
| 质检计划、金标准、统计快照 | 无已确认接口；独立试标演示中的统计不用于正式验收 |
| 能耗完整小时/日资产与历史基线 | 当前为站点日 CSV 首轮，context=NOT_PROVIDED；不推断 30 天规则 |
| 原始图片/源数据打包 | includeOriginals=true 被后端拒绝；正式导出仅结果和 manifest |
| 真实模型质量及业务专家验收 | 创建/查询真实 execution 不等于已有可用模型或通过模型质量验收 |
| 第三方旧编辑器服务 | 不恢复 editor-sessions/editor-sync，不让浏览器持有 Worker/LS 凭据 |

## 4. 保留的演示代码是什么

为了历史开发回归，独立的 /labeling/demo、/labeling/demo/energy 路由及其中的流程演示仍保留。
`labelingWorkflowDemo.mjs`、`labelingGapDemo.mjs`、`labelingEnergyDemo.mjs` 的内存/本地数据只供独立演示与离线测试，
正式项目页不调用这些模拟客户端。演示合同和新服务合同并不完全相同，不能将开关直接切成“真实”来替换适配器。

`labelingWorkflow.ts` 中 workflowBackendReady=false 仅针对旧演示客户端，不表示 backend 新接口未实现。
`labelingOwner.ts` 保留的旧 samples / sample-reviews 适配和来源转换帮助函数是兼容接口回归代码；正式页面已迁移至 selectionId / CAS。
`labelingProjectActions.ts` 的旧“本地预览删除”已移除，正式页面调用真实 DELETE。

本地 localStorage 仍用于上传续传、草稿/提交恢复和已知编号书签；它们是客户端恢复状态，不能作为后端完成记录。
正式删除、审核、导出和模型进度均以服务器响应为准。

## 5. 本地验收入口

入口：http://127.0.0.1:8001/#/labeling

1. 项目列表：草稿可删除；暂停、恢复、归档要求填写原因，状态由服务器读回。
2. 创建图片或能耗项目：模板从服务器加载，保存规范、上传 ZIP、确认分包并发布。
3. 结果工作台：选具体答案确认；修订后状态回到待审核；版本变化不会静默采用另一份答案。
4. 再发布/AI：勾选结果可作为本轮固定参考；AI 终态失败可新建重试。
5. 来源审核与集成：明确选择独立参与者答案，计算建议后再审核采用。
6. 成果与导出：保存已确认答案为快照、勾选快照、创建作业，待服务器成功后下载 ZIP。
7. 我的任务：读取本人提交和服务器回执；能耗逐样本保存并完成后整包交回。

自动检查只使用离线测试和构建，不在共享服务器创建项目、发起 AI、删除数据或执行数据库迁移。
服务端仓库已实现不等于联调服务器已部署同一版本；页面如出现 404/版本错误，会保留真实错误供联调，不自动退回假接口。

## 6. 本次自动检查

- 360 项离线测试通过，包含固定范围消费、审核 CAS、响应丢失重放、AI 重试、导出请求、能耗输入/结果与 JSONB 键序兼容。
- TypeScript 检查、生产构建、变更文件 Biome 检查及治理检查通过（治理检查保留 5 条既有提示，无错误或警告）。
- 页面视觉与真实服务器写入由用户手动验收；本次没有运行浏览器自动化或操作共享业务数据。本记录对应合并前的本地验证，后续 PR 与合并状态以 GitHub 记录为准。
