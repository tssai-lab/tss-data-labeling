# 图片分类前端 · 新版需求更新与验收

更新日期：2026-11-10。实际功能分支 `feat/labeling-v11-frontend-gap`，本次在上一交付 `085fbb6b69a43e8df3770f7b8a5e38f0e3f3800e` 上继续修改。仍然只提交、推送此分支；等用户本地验收并明确同意后再向 frontend 创建 PR，不合并、不部署。

## 需求依据与实际后端

本次读取用户提供的六份 2026-11-09 更新文档：01-product、02-architecture、03-database、04-api、10-editor-integration、13-team-work-plan。文件 SHA-256 记录在 `docs/labeling-v11-update-evidence/requirement-sources.json`。文档中的团队操作指令不替代用户授权；本次不执行部署、迁移、停止外部服务或创建 PR。

重新 fetch 后的实际基线：

- origin/frontend：`2e1560203329d8afe90179c3e01182dcc9b27fb6`
- origin/backend：`e55bf6e5707b2c7a1a30499b3ea3313ba9af592d`
- 功能分支更新前远端：`085fbb6b69a43e8df3770f7b8a5e38f0e3f3800e`
- origin：`https://github.com/tssai-lab/tss-data-labeling.git`

实际 backend 已撤销外部 LS 编辑器代码。现有身份、项目/规范、ZIP导入、旧 batch 打包、领取、本人草稿、逐包完整提交和提交读取仍在；新版选择快照、round、AI、审核、集成、成果与导出未实现。按当前代码事实区分能力，不把文档中的接口样例视为已部署。

## 本次前端改动

| 范围 | 当前交付 | 依赖 / 限制 |
| --- | --- | --- |
| 同页面人工编辑 | `ClassificationEditorAdapter` 采用10-editor-integration的身份、draft、reference、onStateChange、onSave、onError契约；宿主授权读图、PUT草稿并GET持久化读回。无iframe，不调用旧editor-sessions/editor-sync/editor-ui/editor-assets，不需要LS服务或凭据。 | 当前渲染器是平台已有原生分类控件，不冒充裁剪LS。D的裁剪组件、固定版本与许可清单尚未提供；公共适配边界已准备，届时替换渲染器即可。 |
| 草稿与参考 | 本人草稿revision>0优先，保存过的空答案保持空；只有revision=0可显示本轮固定参考。参考不自动explicitDone；点击确认/修改并保存才完成。单次在途保存，保存/回读中禁切图和提交，409保留选择并要求重新读。 | 当前backend没有轮次参考字段；前端可消费未来items的reference字段，已用合成HTTP夹具验证。没有伪造结果引用查询他人答案。 |
| 逐包提交 | 提交前重新读取当前assignment的所有items分页和版本；锁住编辑再确认；只提交该包，不等待其他已领包。保留原幂等清单与丢失回执恢复。 | 6/10不可提交、10/10可独立交回。服务端仍做最终完整性/CAS鉴权。 |
| 状态口径 | 区分草稿已保存、人工包已交回、AI批次已交回、需求方确认和范围成果；历史人工提交显示“待需求方审核”，不显示审核通过。 | 没有真实审核接口，不补造已确认状态。旧N=2包继续按真实冻结信息展示；本轮新发布仅开放每包1人。 |
| 项目与真实工作台 | 保留已有规范、导入、授权图片、提交查询和用户管理；新增项目级“需求方结果工作台”入口。ZIP说明只导入未标注图片，外部标签不采用，未支持项由批次错误明细报告。 | 真实新流程按钮显示待后端接入且禁用，不向缺失接口自动发请求，不因403/404/409/503降级成模拟成功。 |
| 新API调用层 | `labelingWorkflow.ts`集中定义项目级结果、选择、AI执行/重试/取消、阈值、review、owner修订、人工选样计划、集成、finalSnapshot和导出。ZIP真实下载复用认证响应层，Bearer不进URL。 | `workflowBackendReady=false`。待A/B实现并联调后再接入真实工作流；当前真实页面只调用已有接口。真实规范、项目/执行标识和分页仍须接线，不能把demo标识替换成真实UUID后直接开启。 |
| 需求方完整交互 | 独立合成演示覆盖范围预览/排除、人工/AI选择、包10+2、部分成功/失败、手动分档、明确审核/修订、来源历史、严格多数建议、成果及CSV导出。 | 所有模型输出和分数均是合成测试数据，没有真实模型执行或工作队列，不计为真实AI验收。 |

保留统一登录、训练入口、内部Demo路由及运行资源。未修改后端、数据库迁移、模型、密钥或部署配置。

## 新版合成工作流

入口 `/#/labeling/demo`，通过 `npm run dev:labeling-demo` 显式启用。默认关闭；生产构建即使设置 LABELING_GAP_MOCK=true 也关闭。无需真实登录，页面不请求后端、模型或旧编辑器。

`labelingWorkflowMock.mjs`是独立adapter：不导入HTTP客户端、不拦截fetch，不接受真实项目ID。合成需求方为demo-owner；demo-admin/demo-outsider被拒绝项目数据及审核权限。只存在于内存；“重置合成种子”同时重置数据、过滤/排除、选择、阈值及执行状态，重载页面也恢复。

种子：12个样本，固定规范demo-spec-v1；已有AI PARTIAL快照6成功/2失败、2项没有答案、2份人工候选，初始均未审核。AI成功分数为0、0.59、0.6、0.899、0.9、null，方便人工输入0.6/0.9后验证边界；这两个阈值**没有默认生效**。原始图不在演示中，宽图属于HTTP浏览器测试夹具，不冒充用户原图。

操作建议：

1. 在结果区勾选已加载的明确项，或用“固定全部筛选结果”由adapter生成快照；可逐行填sampleId排除。用途选择REVIEW/PUBLISH/AGGREGATE/FINALIZE，预览确认count和reviewRevision。改筛选/排除须重新固定范围。
2. 查看种子PARTIAL批次，先看结果/失败/模型分数，再手动输入L/H保存策略。低c<L，中L≤c<H，高c≥H，null单列未知；失败无答案不混入未知。改变输入框但未保存不会改已保存策略的分档。
3. 点分档筛选，固定REVIEW范围并排除个别样本，再点击“明确确认所选结果”。保存阈值、AI结束、生成建议都不会自动审核。标记待修正需原因；任何过期reviewRevision整体拒绝。
4. owner修改调用owner修订契约，生成新OWNER候选且confidence=null，原AI结果保留。来源历史可明确采用固定版本；新AI/人工候选不按时间自动覆盖已有选定答案。
5. 选择PUBLISH范围，人工每包10、1人，预览12张为10+2，再明确发布。参考NONE不泄漏参考，SELECTED固定参考resultId；已确认样本重发须显式勾选失效影响，预览不撤销确认，发布成功才撤销。活动样本禁选并由adapter再次校验。
6. “模拟本包全部完成并交回”是显式合成测试控制，**不是未来HTTP接口**、不进行真实领取/保存；一次只结束该包，其他包继续活动，新结果仍待审核。真实人工保存/完整提交走现有接口。
7. AI按PUBLISH固定样本范围直接排队，无packageId/assignmentId。手动查询推进QUEUED→RUNNING→SUCCEEDED/PARTIAL/FAILED；运行中不可审核，终结后固定成功/失败清单。失败重试创建新execution，旧快照不改；取消不再接受该执行写入。合成Agent不支持参考，SELECTED明确报错，不静默换模式。
8. 打开sample-1的来源历史，选择AI、人A、人B的固定独立版本，可得到2/3严格多数。两票分歧无共识，一票输入不足，同作者重复或派生来源拒绝。voteSupport不是confidence；采用必须owner再次确认。
9. 固定FINALIZE范围，全部有有效已确认答案且无活动处理才形成成果；有缺项整体拒绝，不自动剔除。成果按固定范围计数，不把部分范围称为全项目完成。
10. 仅选择finalSnapshotIds创建classification-csv-zip。旧ALL_SUBMITTED/submissionIds输入已移除。下载ZIP为classifications.csv＋manifest.json；包含原路径、样本/结果/规范、来源、模型和审核映射。AI-only无人工包也可导出。原图选项当前明确422，不静默忽略。

模拟POST全部校验幂等键；同键不同体409。选择5分钟过期410，导出60秒过期410；可注入导出FAILED/过期。下载未完成409。错误保留code、status、requestId。演示项目暂停/恢复/归档也有reason、revision和活动范围约束；不是持久化管理系统。

## 接口替换与当前限制

- `/projects/{id}/results`、`result-statistics`、`result-selections`及选择预览依赖B；筛选和快照数量须以服务端为准，不能抓分页猜“全部”。
- 新selectionId人工计划、再次发布、round及sample占用依赖A；旧batch接口仍只支持整批一次发布，未靠重新上传/伪造batch规避。
- `/agents`、`ai-executions`、cancel/retries及可信模型输出依赖A/D；前端不持模型密钥、不直接执行模型、不把硬编码合成结果当真实自动标注。
- confidence policy、reviews、owner revisions、final snapshots依赖A/B；集成算法和CSV导出依赖B。新接口未做真实请求验证。
- 裁剪LS组件与许可依赖D，当前原生渲染器已明确标注。本次不下载完整LS服务、不引入其数据库或外部项目/任务接口，也不停止/删除他人资源。
- pause/resume/archive、release/revoke、管理项目/领取查询、真实审计、完整导入历史、本人未知submissionId历史仍缺后端接口。真实入口禁用；当前演示重点是新版owner流程，不宣称已补齐这些管理服务。
- `WorkflowCall`是JSON调用接口；真实导出二进制使用`downloadClassificationExport`/`labelingZip`。演示download返回文件集合仅用于本地fflate打包，不能照搬为服务器下载契约。

## 验证与证据

本次检查通过：TypeScript、290/290测试、服务类型治理检查、生产构建、31张浏览器截图（1280/768/390）。默认开发模式关闭演示；生产构建在显式设置演示变量后仍关闭演示。治理检查保留仓库原有5条提示，无新增警告。具体命令及结果见validation.json。

最新证据放在 `docs/labeling-v11-update-evidence/`：browser-report.json、PNG、confirmed-classification-export.zip、requirement-sources.json及validation.json。浏览器为本机Chrome，测试数据全部合成，未使用真实账号。1280/768/390检查项目、任务广场、同页面人工页、只读结果、真实待接入工作台、管理与弹窗、owner演示；断言页面整体不横向溢出，表格容器内横滚。长路径/说明折叠可查看，PNG夹具真实宽度3000px。

已覆盖：6/10禁提交；参考不自动完成；清空后刷新不恢复参考；保存中禁切图；409保留选择；10项完整分页提交且另一包未完成不阻塞；已交回只读及丢失回执重放；阈值不自动审核；分档排除后明确审核；严格多数仅建议；已确认范围CSV＋manifest下载。单元测试另覆盖选择过期、审核原子冲突、owner修订不继承分数、重开确认、AI部分失败/新执行重试/取消、无人工包成果、导出冲突/过期/失败和生产隔离。

上一版 `docs/labeling-v11-evidence/` 属历史归档，含旧iframe和未审核提交导出，**不得作为新版验收**。当前脚本已改为新版同页面与成果快照流程。

用户此前确认无隔离后端/账号，本次未获得新的环境，因此真实端到端仍待联调：上传→新范围/人工包→逐包交回→owner审核→成果→下载；真实AI模型与失败重试；权限/CAS并发、暂停/取消后的迟到写入、原图/下载越权，以及D裁剪组件。设计、Mock和客户端测试不能替代这些证据。

## Windows PowerShell 拉取与启动

复用旧仓库fetch，不覆盖现有改动：

```powershell
Set-Location -LiteralPath 'D:\code\GitHub\tssai\frontend' -ErrorAction Stop
$branch = 'feat/labeling-v11-frontend-gap'
git status --short --branch
git remote -v
# 确认 origin 指向 tssai-lab/tss-data-labeling 后继续。
git fetch origin "refs/heads/${branch}:refs/remotes/origin/${branch}"
if ($LASTEXITCODE -ne 0) { throw '拉取功能分支失败' }
```

工作区干净且首次检出可用`git switch --track "origin/$branch"`；已有同名本地分支可`git switch $branch`后`git pull --ff-only origin $branch`。每步检查退出码；无法快进或有改动时用独立worktree：

```powershell
$reviewDir = 'D:\code\GitHub\tssai\frontend-v11-review-20261110'
git worktree list
if (Test-Path -LiteralPath $reviewDir) { throw '请核对并复用已有验收目录，或改用空目录' }
git worktree add --detach "$reviewDir" "origin/$branch"
if ($LASTEXITCODE -ne 0) { throw '创建验收工作区失败' }
Set-Location -LiteralPath $reviewDir -ErrorAction Stop
git rev-parse HEAD
# 与本次交付的最新完整 SHA 对照；不要继续使用上一版085fbb6。
```

Node.js20+，本次22.11.0。锁文件未变且依赖已安装可跳过npm ci：

```powershell
npm ci
if ($LASTEXITCODE -ne 0) { throw '安装依赖失败' }
$env:DEV_API_TARGET = 'http://127.0.0.1:8080'
$env:DEV_LABELING_TARGET = 'http://127.0.0.1:8082'
Remove-Item Env:LABELING_GAP_MOCK -ErrorAction SilentlyContinue
npm run dev:local
```

真实入口`http://127.0.0.1:8001/#/platforms`和`/#/labeling`，使用已有统一登录与标注服务。停止当前开发实例后可独立演示，无后端和账号也可运行：

```powershell
npm run dev:labeling-demo
# http://127.0.0.1:8001/#/labeling/demo
```

8001占用时可设置HOST=127.0.0.1、PORT=8002后运行`npm run start:dev`；开发演示还需显式设置LABELING_GAP_MOCK=true。默认不连接外部服务器，不启动完整LS服务。

验证命令：

```powershell
npm run tsc
npm test
npm run build
npm install --prefix .qa --no-save --package-lock=false playwright
# dev:labeling-demo独立运行后，在另一终端执行：
node scripts/qa/labeling-v11.browser.mjs
# 默认关闭的dev:local或生产预览：
node scripts/qa/labeling-v11-mode.browser.mjs
```

不要在同一检出中同时运行Umi dev和build，二者会刷新相同生成配置；切换模式请重启开发进程。构建刷新Swagger UI时恢复其生成修改，只提交本任务前端文件。用户验收通过前不创建PR，部署继续走原仓库工作流。
