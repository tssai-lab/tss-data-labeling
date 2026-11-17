# 标注前端接口更新与本地验收（2026-11-15）

本轮直接修改 `D:\code\GitHub\tssai`，基于 frontend `2fee8a0`，尚未提交、推送或发起 PR。后续经用户验收通过后再 PR 到 frontend 并合并。

核对依据是 backend `09dddbc02e1ffa1a27534fbe2f4aa52a3b7449f3` 的 [需求目录](https://github.com/tssai-lab/tss-data-labeling/tree/09dddbc02e1ffa1a27534fbe2f4aa52a3b7449f3/docs/labeling-v1.1)、[04-api.md](https://github.com/tssai-lab/tss-data-labeling/blob/09dddbc02e1ffa1a27534fbe2f4aa52a3b7449f3/docs/labeling-v1.1/04-api.md) 及对应 Controller、Service、V10/V14 迁移。文档中目标接口与现有实现不一致时使用现有实现，未把设计方案当成已上线功能。

## 本轮接入

| 页面 | 真实接口及行为 |
| --- | --- |
| 结果与审核 | 项目 results、result-statistics、样本 results 历史；明确规范版本，支持包、轮次、执行、来源、审核状态、有无答案、策略及分档筛选；统计标为实时统计 |
| 来源选择 | 多候选不自动采用“最新”；历史完整分页查找所选 resultId，再解析 submissionId/executionId；校验规范、样本、审核版本、进行中状态及人工当前修订 |
| 固定范围预览 | 创建 result-selections，分别读取其固定 items 和 statistics；校验数量、到期、游标和重复样本；选择/筛选改变时失效 |
| 范围发布 | 显式 samples[] 分包计划、selectionHash 确认发布；计划参数改变后重新预览；成功发布回执保留。每样本一人，可附带人工原始冻结提交参考 |
| 人工轮次 | 查询已知 roundId、相同包新轮次、按当前 revision 取消轮次；需求方按已知 assignmentId/revision 撤销领取 |
| 人工提交与修订 | 真实提交列表、提交原文、提交审核状态；逐样本 ACCEPT/MODIFY/REJECT/CORRECTION、整份接受/退回/待修正；完整分页读取审核审计 |
| 标注人页面 | 优先按 roundId 领取，按领取 revision 放弃；任务分页校验 roundId；按实际 referenceResult 嵌套结构展示参考，参考不自动完成；结束的领取只读；冻结提交页显示审核结论 |
| AI | 可用 Agent 列表、显式 samples[] 创建执行、按已知编号查询及恢复新建执行链接、可见页轮询、取消执行、终态结果分页、阈值策略和分档跳转审核；运行中仅显示暂存进度 |
| Agent 管理 | 标注管理员注册图片分类 Agent，按真实返回的 agentId/actorId 展示回执；Worker 密钥不写入浏览器持久存储 |
| 成果 | 明确勾选已确认样本创建 final-snapshots，列表/详情和冻结原始 JSON 下载。JSON 不含图片，不冒充 CSV/ZIP 服务端导出包 |
| 能耗试标 | 最小技术完成条件改为一个合法主标签且其余标注合法；备注、自评、异常定位不擅自作为专家强制规则。现有开发演示继续独立，生产环境关闭 |

写操作使用原有统一登录与标注服务封装，没有失败后切换假成功。页面操作保留相同请求的幂等键和已准备内容，响应丢失后在当前页面重试时重放原请求；同步锁阻止双击重复发送。页面刷新后的通用待确认操作尚无服务端查询接口，不宣称跨设备恢复；原有人工整包提交的持久恢复逻辑保留。

## 必须区分的后端限制

1. **逐样本接受的源码缺陷。** [OutcomeService.reviewOne](https://github.com/tssai-lab/tss-data-labeling/blob/09dddbc02e1ffa1a27534fbe2f4aa52a3b7449f3/labeling-service/src/main/java/com/tss/labeling/review/OutcomeService.java) 接收 `action=ACCEPT`，并直接把 action 写进 `sample_review.status`；[V10](https://github.com/tssai-lab/tss-data-labeling/blob/09dddbc02e1ffa1a27534fbe2f4aa52a3b7449f3/labeling-service/src/main/resources/db/migration/V10__create_outcome.sql) 的状态约束仅允许 `UNREVIEWED/PENDING/NEEDS_REVISION/ACCEPTED`。按该源码及迁移推断，接受会触发服务端约束失败。前端保留 `ACCEPT` 请求并展示醒目提示和真实错误，未篡改请求为不受支持的 `ACCEPTED`，未修改后端。人工可使用独立提交审核接口，其同步状态明确使用 `ACCEPTED`；AI 统一接受完整链路须后端修复后联调。本轮未向真实服务器发送测试审核。
2. 现有 sample-reviews 按来源写入，不支持 resultId、selectionId 或 baseReviewRevision 的原子比较。前端发送前预检并复核当前来源，不能替代服务端原子并发控制。旧人工答案被修订为不同标签时拒绝静默接受。
3. 固定选择快照目前只有创建和预览；没有消费 selectionId 的发布、审核或成果接口。当前写操作均使用明确勾选的 samples/items/sampleIds。全部匹配的固定预览不会悄悄变成当前页批量操作。
4. referenceResultId 实际要求 **submissionId**，且读取的是原始人工提交。AI 答案、需求方修订不能伪装成此类参考。界面标明参考原始提交。
5. 项目轮次列表、项目 AI 执行列表、AI retry、集成、CSV/ZIP 导出、真实能耗模板注册/导入/授权数据读取等接口仍未实现。已知编号查询或明确开发演示不等同于完整历史。未创建这些假路由，未调用 Worker 内部接口。
6. 服务端快照保存的是其实际返回的来源、审核版本、答案及源文件摘要，未自行补造不存在的结果 ID 或导出状态。

## 自动验证与边界

- `npm test`：337 个测试通过（原 317 个，加 20 个真实接口/离线组件回归）。
- `npm run tsc`、`npm run lint:governance`、本轮修改的 26 个代码文件 Biome 检查、`npm run build` 均通过；治理脚本有 5 个既有 info 提示，没有新增警告或错误。构建生成的 Swagger 资源副本已恢复，未混入产品改动。
- 用例执行真实 TS 模块和页面处理函数，仅替换 React hook、Ant 控件及明确的网络/浏览器边界；覆盖精确字段、跨页来源定位、版本漂移、未解决多候选、来源绑定、未知置信度、固定范围数量、发布预览失效、回执保留、幂等重放、同步锁、AI 快照不匹配及冻结提交修订。
- 未使用真实账户向服务器创建项目、执行 AI、审核、撤销或生成成果；不声称已完成线上写入联调。服务器是否已部署此 backend SHA 未验证。
- 按用户之前的选择完成代码与自动检查，页面视觉及真实账户联调由用户手动验收；没有绕过浏览器访问限制。
- `.env.local`、本地日志及缓存参考源码均未加入提交；后端、数据库迁移、服务器部署未修改。

## 手动验收顺序

1. 打开本地项目列表，进入已发布图片分类项目工作台。选择规范，核对结果筛选与实时统计；翻页后勾选范围，查看原图和多候选来源历史。
2. 在“人工提交与修订”选择真实提交：核对原始标签，修改并接受一张，核对审计、结果列表、冻结提交页的审核结论；确认原始提交未被覆盖。整份接受会采用原始答案，弹窗须明确提示覆盖逐样本修订。
3. 勾选部分样本，进入“范围发布与轮次”，修改包大小验证旧预览失效，再发布并核对每包轮次编号。到任务广场按该轮领取，参考答案须独立确认保存；不同轮次不应领取错包。
4. 在“我的任务”填写原因后放弃未提交领取，刷新核对席位；已经交回的领取不能放弃。需求方取消轮次保留已提交结果，按旧 revision 操作必须显示冲突。
5. 选择已部署的 Agent 创建小范围 AI 执行，保存执行链接并刷新验证查询。核对运行中暂存、部分失败、未知置信度和取消；终态阈值策略只筛选、不自动接受。当前后端统一接受缺陷须先修复才能验收 AI 到最终成果全链路。
6. 勾选已确认且与当前采用答案一致的样本，创建成果快照；再次发布后旧快照应保持冻结，JSON 下载内容与服务器快照一致。正式 CSV/ZIP 导出仍显示待接入。
7. 核对窄屏列表、三栏工作台、抽屉及所有确认弹窗。能耗演示只验收本地技术契约，不报告真实数据识别准确率或正式业务规则验收。
