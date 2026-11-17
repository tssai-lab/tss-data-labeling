# 图片分类审核工作台 · 本地验收

日期：2026-11-11。仓库为`tssai-lab/tss-data-labeling`，分支`feat/labeling-v11-frontend-gap`，本地/远端起点均为`56ca3af7d771d1938d32752c147c9ef29ceb1dcc`。开始时工作区干净，未发现适用的AGENTS.md。首次交付仅保留本地改动，随后用户批准提交并推送至本分支；不创建PR、合并或部署。用户原验收目录未改动。

## 运行

```powershell
Set-Location -LiteralPath 'D:\ASUS\Documents\codex\tssai\labeling-v11'
npm run dev:labeling-demo
```

入口：`http://127.0.0.1:8001/#/labeling/demo`。默认`npm run dev:local`关闭演示，生产构建即使设置LABELING_GAP_MOCK=true也关闭。依赖锁文件未变，本机依赖已安装。其他目录验收时，先保留该目录本地改动，再获取本分支的最新提交。

首次打开显示12个样本的全范围统计，先加载6行；“加载更多”加载后续页。顶部统计来自同一筛选的statistics，不用已加载行数冒充总数。右上角为合成身份，demo-admin/demo-outsider不会获得owner权限。

## 页面与操作

- 结果与审核：左侧行点击只切换当前预览，复选框单独控制批量范围；按来源、审核状态及是否有答案筛选。原路径可展开复制。
- 中栏按sampleId读取12张本地生成的SVG合成零件图；不会下载真实样本或使用附图中的照片。上一张/下一张只沿当前筛选下的已加载列表切换，缺图显示占位。
- 右栏显示冻结规范标签、来源/模型、AI置信度、候选版本及审核状态。AI的0显示0，null显示未知；人工/需求方/集成候选不显示模型分数。
- 确认采用弹窗固定sampleId、resultId、baseReviewRevision；多候选未选定时禁用确认，引导查看来源历史。历史选择只更新预览，不自动审核。
- 标记待修正必须有原因，允许无答案；修订只能从冻结标签选项选择，生成新的OWNER候选，保持待审核，原AI候选及其分数留在历史。
- 保存采用同步锁防重复；失败保留表单，409显示刷新、核对版本并重新确认提示。成功刷新列表、详情与同筛选统计。原因按样本分别保留。
- “批量操作与固定范围预览”保留PUBLISH/REVIEW/FINALIZE快照、排除、预览及显式确认；批量勾选仅固定已加载明确项，“全部筛选结果”由adapter生成快照。
- 范围发布、AI进度与分档、来源与集成、成果导出保留既有工作流。“选择发布范围/选择成果范围”回到固定范围面板；演示重置、项目状态和能耗入口在“演示控制与项目管理”。

真实页面`/#/labeling/projects/:projectId/workbench`使用既有GET project、me、submissions及本机已知plan查询；包数/人数仅为真实查询已加载范围，未知分母不伪造。保留提交只读详情链接。AI、审核、修订、再次发布、集成及导出待后端接入并禁用；请求失败显示实际code/requestId，不回退到Mock。

## 验证

```powershell
npm run tsc
npm test
npm run lint:governance
# 显式演示运行时另开终端：
node scripts/qa/labeling-review.browser.mjs
# 先停止dev，再构建：
npm run build
```

此仓库开发启动会刷新输出目录，dev、build和生产preview请顺序运行。构建产生的两个受控Swagger文件已恢复，未纳入本次改动。

最终结果见[labeling-review-evidence/validation.json](labeling-review-evidence/validation.json)：`npm run tsc`通过，`npm test`共303项全部通过，`npm run lint:governance`通过（保留原有5条info），`npm run build`通过。改动源码与QA脚本的Biome检查、`git diff --check`均通过。

浏览器覆盖1586px桌面、390px手机、来源/状态筛选、分页和切图、勾选与预览分离、缺图占位、无答案待修正、重复点击仅一次审核、确认/修订/历史选择、失败保留、活动处理中禁用、多候选显式选择、固定范围成果ZIP及真实页面只读/403。另完成8轮演示/真实夹具页面缩放，共80次390/768/1586px切换，未产生页面异常。五页签使用Ant Design按钮和CSS横向滚动，支持方向键、Home/End切换，避免反复缩放触发导航尺寸测量的更新循环。

[桌面实现截图](labeling-review-evidence/review-desktop-1586.png)、[手机截图](labeling-review-evidence/review-mobile-390.png)、[真实工作台截图（夹具）](labeling-review-evidence/real-workbench-1586.png)。浏览器脚本使用本机已有`.qa/node_modules/playwright`和Chrome；应用本身仍沿用原有依赖，未改锁文件。

`review-confirmed.zip`仅含两个已确认的合成结果；不是全项目完成。真实工作台截图由合成HTTP响应夹具产生，不是实际后端联调；未使用真实账号、原图或模型。

## 改动文件清单

| 文件 | 用途 |
| --- | --- |
| src/pages/labeling/WorkflowDemo.tsx | 五页签、筛选范围、固定候选审核、历史与修订宿主、保留原流程 |
| src/pages/labeling/ReviewWorkbench.tsx | 样本列表、合成图预览、审核详情三栏组件 |
| src/pages/labeling/reviewWorkbench.less | 三栏比例、白色卡片、蓝灰背景、移动堆叠与共享真实页风格 |
| src/pages/labeling/reviewWorkbench.less.d.ts | 沿用本仓库Less类型声明约定 |
| src/pages/labeling/ProjectWorkflow.tsx | 真实页页头、提示、卡片及禁用能力 |
| src/pages/labeling/ProjectSubmissions.tsx | 真实已加载提交/包汇总与未知人数说明，保留详情 |
| public/labeling-demo/sample-01.svg 至 sample-12.svg | 12个固定sampleId的本地合成图 |
| scripts/qa/labeling-review.browser.mjs | 本次界面及工作流浏览器验收；替代旧长页的owner验收步骤 |
| scripts/qa/labeling-review-mode.browser.mjs | 默认/生产模式禁用检查 |
| docs/labeling-review-local-acceptance.md | 本说明 |
| docs/labeling-review-evidence/ | 桌面/手机/真实页截图、结果报告、合成导出及完整文件清单 |

逐文件清单见[labeling-review-evidence/changed-files.json](labeling-review-evidence/changed-files.json)。本轮最终源码只修改上述3个既有TSX文件，其余为新增组件、样式、合成图和验收材料。

gapDemo.tsx入口、路由、开关、服务契约、后端和锁文件未修改。既有图片/能耗/统一登录与训练入口保留。旧版浏览器脚本中的长页owner步骤不作为本次界面验收，使用新的review脚本。
