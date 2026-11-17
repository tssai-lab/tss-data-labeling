# 2026-11-10 backend 新文档再次对齐

依据：[固定版本文档](https://github.com/tssai-lab/tss-data-labeling/tree/fafefcaebd7cbb8fd4dec0cbea7a3576bb9703e2/docs/labeling-v1.1)。backend为`fafefcaebd7cbb8fd4dec0cbea7a3576bb9703e2`，frontend仍为`2e1560203329d8afe90179c3e01182dcc9b27fb6`。相对上次backend仅9份文档变化，服务代码未变。本次在功能分支`1e05cccc41b7e9cdafe40980e1125969eed3b023`继续更新。全部文档摘要见新证据目录的requirement-sources.json。

文档中的团队操作与提交/部署说明不替代用户授权。本次继续用户授权的原分支更新与推送；等待用户验收后明确同意才创建PR，不部署、不迁移、不访问共享服务、不提交受限数据。

## 新范围和实际依赖

图片分类保留为回归，不能代表全部验收通过。14新增站点日能耗、可信任务插件、受限输入切片、时序Agent、固定质检抽样、专家金标准、双人盲标及一致性。会话未取得文档记录的Linux能耗数据包；539站点、2000样例、23177站点日及原包摘要核对均不是本次独立复算的证据。分支只生成10条合成站点日，不复制真实包、任务结果或原始MD全文。

| 新要求 | 本次前端交付 | 尚待真实接入/验收 |
| --- | --- | --- |
| 可信模板与编辑器注册 | 内置image-classification@1.0.0和energy-station-day@1.0.0，TaskEditorHost按固定editorKey选择已构建组件；未知模板/历史缺失版本停止读图和编辑；不加载远程代码/XML/schema。 | A模板API、冻结schema摘要；D裁剪组件与版本/许可。 |
| 项目模板选择 | 真实创建项目展示模板，图片兼容旧请求，能耗禁用并说明依赖。 | 后端导入、校验、完成和导出全部就绪后才允许新建能耗；不能直接去掉disabled。 |
| 受限样本输入 | taskInputs集中声明文档的template、assignment data、owner data路径，编码业务ID，不信任任意dataUrl。当前不自动调用未实现接口。 | A/B授权切片、稳定UUID、inputHash和上下文；不把整份共享CSV发浏览器。 |
| 时序列表和编辑 | 独立合成试标页，10站点日而非240小时任务。24点kWh、UTC+8、层级/统计口径、独立日值/差异、零与缺测断线、历史不足；缩小小时范围、点选/拖选、点与半开区间回显及删除重建。 | B宽/长表导入和受限上下文；D正式时序编辑器。当前原生组件不是裁剪LS，不宣称LS原生格式兼容。 |
| 格式与完成校验 | annotation-record-v1内部草案；稳定逻辑绑定、类型、标签、唯一项、时间边界、未知字段/版本校验。空草稿可保存；试标完成需主标签、自评、异常依据/定位。人工自评不写AI confidence。 | A/D独立后端校验；专家确认主次标签、自评及异常正式规则。11标签只是候选。 |
| 保存/完整交包 | 合成宿主验证revision/inputHash，保存后读取，专用sessionStorage刷新恢复，清空保持空；9/10禁交，10/10独立交回且只读。真实分类保持原API和分页完整性。 | 后端目前仍只接受图片格式；真实时序CAS、越权、暂停/过期迟到写入待验证。 |
| owner与成果 | 独立owner修订产生OWNER新候选，不伪造assignment、不继承confidence、不覆盖原答案；明确确认后固定成果，旧快照不变。 | A真实审核/finalSnapshot/多轮占用；能耗owner部分再发布和真实AI链路待接入。 |
| 可追溯导出 | 合成ZIP含完整JSONL、辅助CSV和manifest；保留点/区间、inputHash、模板/schema、task_id源映射、来源/审核；自评与confidence分列，实际计算文件SHA-256，不含原始资产。 | B正式能耗适配，A确认快照；真实下载权限、过期及原始数据往返。 |
| 通用质检 | 与能耗格式独立的固定母体/种子/清单：非金标准20%抽检、全母体10%双标；输入修改不改变既有预览。实际覆盖与确认金标准均为0。 | A/B尚未给质检API完整契约；不捏造路由、不执行质检发包、不造金标准。 |
| 双标与kappa | owner展示独立合成主标签配对数/排除数/观察与期望一致率/kappa；同人、引用、重复配对排除；无样本或期望=1不可计算；真实双标发布禁用。 | 两位真实人员的盲标席位/权限、固定统计快照和完整提交；历史N=2不等于新版质检验收。普通分类仍1人。 |
| 规则/模型 | 明确BASELINE_INSUFFICIENT，不填造30天历史，不自动给全零故障标签或confidence。 | B固定历史特征、D真实RULE_BASED/模型Agent及版本/证据/未知分数。Mock不替代模型或业务质量。 |

## 同时修正04的契约偏差

- 选择purpose仅PUBLISH/REVIEW/FINALIZE；集成使用独立多resultId输入，删除单结果AGGREGATE用途。
- REVIEW固定reviewAction，消费动作匹配；NEEDS_REVISION允许无答案且要求原因，ACCEPT要求合法候选。
- 选择有效期30分钟；最终预览读固定items/statistics；未知筛选拒绝，不静默忽略。
- SELECTED参考可部分为空，只固定已有resultId；Agent不支持参考仍拒绝，不静默降级。
- 统计字段对齐acceptedCount、needsRevisionCount、hasResultCount、noResultCount、activeCount。

## 运行与验证

本次检查通过：TypeScript、303/303测试、28个变更源码/脚本的Biome检查、仓库治理基线、生产构建、37张浏览器截图（1280/768/390）以及默认/生产双入口隔离。真实能耗数据及端到端验收未完成。逐项记录见[validation.json](labeling-v11-template-evidence/validation.json)，归档另经数量、SHA-256及坐标核验。

沿用[拉取与运行说明](labeling-v11-frontend-acceptance.md)。依赖锁文件未变，已有安装可跳过npm ci。

```powershell
npm run dev:labeling-demo
# 图片：http://127.0.0.1:8001/#/labeling/demo
# 能耗：http://127.0.0.1:8001/#/labeling/demo/energy
```

本页sessionStorage仅保存合成状态；“重置能耗合成数据”只清专用键。存储不可用会提示内存模式。不要上传真实能耗包或把生产数据放入演示缓存。原生试标组件及候选规范不作为正式完成验收。

```powershell
npm run tsc
npm test
npm run lint:governance
# 已启动显式演示后，另一个终端：
node scripts/qa/labeling-v11.browser.mjs
node scripts/qa/labeling-energy.browser.mjs
# 停止dev后再build，避免Umi生成配置互相刷新：
npm run build
```

最新证据在`docs/labeling-v11-template-evidence/`；旧update-evidence保留为上一提交记录。默认/生产隔离脚本同时检查两个演示入口，真实403保留令牌且不降级Mock。当前设计与Mock均不能替代真实数据、模型、双人、金标准及专家验收。
