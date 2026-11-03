# 六项代码治理 SOP（2026-09-09）

基线：前端 c260dd1 / codex/frontend-read-errors，后端 bab0447 / codex/backend-code-governance；工作区均干净。只在治理分支修改，不部署、不碰另一工作区的交付安装器。

用户要求：六项先全部修改，再统一测试。修改期间保存用例和源码等价证据，但不提前运行测试。最后执行前端单元/类型/构建/隔离页面，后端在内网机械盘隔离副本测试，禁止连接业务数据库、占 GPU 或修改服务。

| 项目 | 实施方案 | 完成后的验证 |
| --- | --- | --- |
| 1 上传错误回退 | 仅无业务错误码的 404/405/501 允许 Legacy；校验真实回执，未知状态不假报完成；超时只查询状态、不自动再次 complete | 权限、限流、超时、5xx、空/畸形/错会话回执、并发完成、FAILED/DISCARDED |
| 2 读取失败 | 模型消费清单/文件错误向上传递；训练历史与模型详情明确错误、GET 重试、切换时隔离迟到响应；README 错误不冒充空文件 | 正常/空/失败/重试/迟到响应；不重复写入 |
| 3 前端大页 | 发起训练拆成按步骤的展示模块；训练详情拆展示与产物列表；数据集详情拆版本规则；本人代码详情拆展示与只读加载职责 | 保持导出、提交参数和按钮回调；类型/构建、相关控件回归 |
| 4 后端职责与重复 | 工作区资源共用字段/标注归属规则，分离输出映射；ImportJob 分离结果映射及错误分类，原事务编排不拆 | 原工作区/上传/导入测试；并发、失败、归属、异常码 |
| 5 类型约束 | 已治理服务不再整体排除检查；按选定模块收紧 any 和 Effect 依赖，保留明确的历史模块边界，增加可执行检查入口 | lint + setup 后 tsc；不能靠关闭新规则或新增 any 糊过去 |
| 6 错误分类 | 新产生的解析错误使用明确类别，展示文案独立；旧无类别异常走显式 Legacy 适配，不让新逻辑依赖英文句子 | 文案改变仍同类别；历史错误码/用户文案保持；未知错误保守分类 |

## A：已确认事实

- 上轮只读复现：dataset init/chunk/complete 对 403、500、timeout 均发第二次 Legacy 请求；空 complete 回执被拼成 COMPLETED。
- 模型上传已有 isLegacyEndpointUnavailable，数据集没有一致使用；任务历史失败清空列表。服务目录被 Biome 整体排除。
- 现有后端短事务、CAS、对象补偿、权限与数据库约束必须保留；不能按文件长短拆事务。

## B：采用的默认方案

- 遵循已整理模型/代码服务的兼容策略；网络不确定时不重放写请求。可选元数据失败允许主体可读，但必须有可见错误。
- 页面先抽职责明确的展示/纯规则/读取状态，不引入新状态框架。类型规则分模块收紧，不强迫整仓改造。
- 保留旧异常构造器和 Legacy 分类作为兼容边界；新生产路径显式提供分类。

## C：不在本轮擅自改变

权限、数据库结构、任务调度、格式白名单、外部 API 字段及部署架构均不改。真实集成条件不足必须记录未测试，不降低保护门宣称通过。

## 状态

- 六项主要实现已完成：上传回执/兼容保护、读取错误可见、四页职责拆分、后端共用规则/映射、类型检查入口、显式导入错误分类。
- 原上传校验错误码为 INVALID_UPLOAD_REQUEST，原工作区校验为 INVALID_REQUEST；共用逻辑但保留错误码差异。
- 服务目录已纳入检查；历史 any 文件逐个列为 warning，已治理服务与新增读取 Hook 为 error，并接入原 TypeScript CI。不是声明全仓无 any。
- 六项先完成修改，再统一验证；反向审查补充的边界测试也已复跑通过。当前为未提交的治理分支候选，不表示远端或线上已经更新。

## 预期行为—实现—测试对应

| 预期行为 | 主要维护入口 | 测试证据 |
| --- | --- | --- |
| 明确拒绝不重发，未知回执不假报成功，完成超时只查询 | `src/services/dataset.ts`、`datasetUploadResponse.ts` | `sixIssueReadUpload.test.mjs`：业务错误、取消、403/429/5xx、超时、缺字段、错会话、错误状态、分片与进度边界 |
| 真空数据和读取失败分开；重试是 GET，不重放写操作 | `model.ts`、`modelReadResponse.ts`、`useExperimentVersions.ts`、模型详情、`ZipReadmePanel` | 新服务单测；`six-issue-read-check.js` 11 项，包括失败保留、重试、真实空值、旧请求晚返回、部分失败 |
| 拆页不改提交契约及按钮回调 | `useTrainingCreate.tsx`、`TrainingCreateSteps.tsx`、`TrainingArtifactsList.tsx`、`datasetVersionPresentation.tsx`、`useOwnerCodeDetailReads.tsx` | 原相关测试保留，代码浏览并发测试更新读取模块路径；`six-issue-steps-check.js` 15 项，六步展示、刷新、代码来源切换、资源输入与确认单位换算 |
| 公共字段/标注校验只有一套，但历史错误码不变 | 后端 `DatasetWorkspaceResourcePolicy`、`DatasetWorkspaceResourceMapper`、`ImportSampleMapper` | 后端新增 `SixIssueGovernanceTest` 与原工作区/上传/导入相关测试；详见后端同名阶段记录 |
| 已治理模块严格检查，历史类型债务不再增长 | `biome.json`、`check-service-type-baseline.mjs`、`service-type-baseline.json`、`frontend-typecheck.yml` | 治理 lint 通过、setup 后 tsc 零错误；47 个服务文件扫描，17 个历史文件共 146 个显式 any 作为逐文件不可增长基线 |
| 导入错误类别不随提示文字或用户路径改变 | 后端 `ManifestFailureKind`、`ImportFailureMapper`、解析入口；旧构造器独立 Legacy 适配 | 新增显式类别、中文/误导关键词文案、循环 cause、畸形 JSON、空单模态包等测试；原错误契约测试通过 |

## 2026-09-09 最终验证

| 验证 | 结果及证据位置 |
| --- | --- |
| 前端 `npm run postinstall`（max setup）→ `npm run tsc` | 成功，类型零错误；工作区根 `.cache/six-final-postinstall.log`、`six-final-tsc.log` |
| 前端 `npm test` | **208 通过、0 失败、0 跳过**；`.cache/six-final-tests.log` |
| 前端 `npm run build` | 生产构建成功；`.cache/six-final-build.log` |
| `npm run lint:governance` / `npm run biome:lint` | 均通过；治理范围零警告、5 项风格提示；全量仍有 **166 个历史警告、12 项提示**，不能称为全量零告警。日志 `six-governance-lint.log` / `six-biome-final.log` |
| 新隔离浏览器场景 | 读取 11 + 六步展示 15 = **26 项断言通过** |
| 原隔离浏览器回归 | 读取错误 25、代码分页 19、对比详情 26、审批 27、管理员资产详情 24、代码文件 10，共 **131 项断言通过**；根 `.cache/six-*-check.log` |
| 浏览器总范围 | **157 项本地断言通过**，不是 157 个业务按钮，也不是所有页面完成真实验收。夹具挂载真实 React 组件，服务回执受控，禁止真实写入 |
| 后端内网隔离 Maven 测试 | **1376 项中 1374 通过、0 失败/错误、2 个真实集群测试跳过**；另有 10 个 Testcontainers 套件明确未运行。最终完成时间 16:11:11 +08:00 |

浏览器资源配置截图 `output/playwright/six-confirm.png` 已查看，实际画面是第 5 步资源配置（文件名不代表确认页）。前端生产构建、测试日志和截图均为本地证据，不加入产品资源或提交包。原 Ant Design 弃用/布局提示仍存在，未据此宣称控制台零告警。

后端只在 seu5090 项目机械盘 `/srv/tss-AIplatform/staging/fabric8-smoke/six-issues-20260909` 测试，限制约 1 核 CPU、总内存 2 GiB，离线依赖缓存。结果为 `backend-tests.log` 与 `backend/target/surefire-reports`。没有占用 GPU、连接业务数据库、拉取容器镜像、修改配置或重启服务；没有在外网验收集群执行测试。

## 反向审查及处理记录

1. 第二遍逐项检查上传 API、回执 DTO、错误包装与页面消费链：补充嵌套业务错误和取消请求识别；完成结果必须有资产标识，不能用展示状态当持久化完成状态。完成请求状态不明时允许读取对账，不增加写入重试。
2. 检查相同组件切换资产/实验的迟到响应：历史与模型代码预览使用请求序号/取消标志；真实空 README 允许为空，不再伪装成错误或把错误伪装成空文件。本轮并未重构所有资产详情的根加载流程。
3. 后端检查原调用顺序、事务/CAS/补偿及归属规则：只移动纯映射/输入策略；上传与资源接口各自原错误码保留。新增异常 cause 循环保护；解析入口补齐显式分类，旧文本分类只留在 Legacy 兼容边界。
4. 删除已无引用的私有列表拼接函数与 `_shortId`，收窄展示模块内部导出；不删除已有测试，不为缩短文件继续拆散事务或训练提交编排。
5. 统一测试首次发现抽取后的 `toJson` 静态导入遗漏，已修复。后端隔离副本缺原有示例夹具导致 3 项失败，补齐现有夹具后复跑；未删测试。浏览器夹具文字定位和 MiB/GiB 期望错误按实际页面修正，没有修改产品单位来迎合测试。
6. 最终后端补充边界测试后复跑上述 1376 项；前端最终复跑 setup、类型、208 项测试和生产构建。测试后仅补文档及 Java 尾部空白整理，无业务逻辑变化。

## 假设、依据及影响

- **假设：兼容回退仅用于端点不支持。** 依据为项目已有模型/代码服务模式，采用无业务错误码的 404/405/501 判断。影响：权限、限流与不确定写入失败不再尝试旧写接口；若旧部署返回其它非标准“不支持”状态，需要修复对应接口或明确契约，不能恢复无条件重试。
- **假设：部分读取失败不必抹掉成功读取的主体。** 依据为前几轮错误可见治理模式。影响：相同对象可保留旧值但必须显示持续错误；更换对象不能继续显示上一个对象可操作的数据，重试只读取。
- **假设：本轮优先行为兼容，渐进收紧类型。** 依据为用户“不影响业务”及最小改动要求。影响：旧 any 和少量长编排仍保留，但逐文件设不可增长基线；新增/已治理模块严格检查，不把全仓技术债宣称清零。
- 无需新增业务选择：没有改变权限、数据库结构、资源调度、公开 API 字段或部署架构。旧异常构造器保留；新错误分类明确化，未知类别保守按原通用错误处理。

## 未验证范围与发布建议

- 10 个真实 PostgreSQL/MinIO/Redis 等 Testcontainers 套件未执行，原因是服务器根盘 99%、容器依赖镜像缺失；不能把本轮内存仓库/Mock 并发用例当成真实数据库锁或对象存储一致性证明。完整套件名单见后端 SOP。
- 未执行真实登录权限、实际上传/发布/弃用/删除、训练/推理与 GPU 资源分配。本地页面覆盖了本轮读取、重试及步骤配置控件，**没有覆盖全站每个按钮或真实训练提交**。
- 本轮六项实现及隔离回归已完成；不等于所有历史页面请求竞争、所有长文件和告警已消除。不要为追求清零扩大本次发布范围。
- 可以作为独立治理提交候选；**不建议立即替换交付版**。先在资源允许的独立测试数据库/对象存储补齐集成套件，再做内网最小真实上传—训练—结果读取回归，之后按原分支流程合并。当前未推送、未合并、未部署。

## 本地复验入口

```powershell
npm run postinstall
npm run tsc
npm test
npm run lint:governance
npm run biome:lint
npm run build
node scripts/qa/six-issue-harness.mjs
# 第二个终端，使用已安装的 Playwright CLI；只连接本地夹具。
playwright-cli -s=six-local open 'http://127.0.0.1:18894/'
playwright-cli -s=six-local run-code --filename scripts/qa/six-issue-read-check.js
playwright-cli -s=six-local run-code --filename scripts/qa/six-issue-steps-check.js
playwright-cli -s=six-local close
```
