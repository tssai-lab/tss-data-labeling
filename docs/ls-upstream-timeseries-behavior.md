# 时序行为保持 LS 上游原样

更新：2026-11-15。依据用户明确要求，撤回对 LS 缺测、概览时间轴和缩放保护的行为改造。

## 当前规则

LS 配置仍需声明数据字段、时间列和通道，这是上游 XML 的字段绑定。平台不要求普通导入先填写另一套映射表。仅需要合并原始明细、分组或调整字段时，用户才选择高级整理。

CSV 资源列表提供：

- 文件作为一个样本：创建引用原 CSV 的原生任务，由 LS 控件读取文件；普通时序文件不必先转换为内联数组。
- 每行一个样本：保留 CSV 列名生成任务，不要求填写字段重命名或分组。
- 高级整理：按需选择分组、时间、通道或资源字段，继续使用已有业务接口；它不改变 LS 绘制代码。

这对应上游导入中 `file_as_tasks_list=false/true` 的两种任务粒度。分组整理是平台可选工具，不是所有用户的必经阶段。上传引用资源的授权和平台保存／审核机制继续负责各自业务。

## 原样恢复的源码

以下四个文件从固定提交 `df96d79183156e556d91825d15fa7721f1145e1a` 的 LS 1.23.2 复制恢复，逐字节一致：

1. `libs/editor/src/components/TimeSeries/TimeSeriesVisualizer.jsx`
2. `libs/editor/src/tags/object/TimeSeries.jsx`
3. `libs/editor/src/tags/object/TimeSeries/helpers.js`
4. `libs/editor/src/utils/data.js`

恢复范围包括：CSV 空单元格解析、缺测过滤及连线、概览时间刻度、下采样和自动缩放。当前按 LS 原实现运行，不另外修复或改写上游在这些位置的行为。

平台追加的“放大时间范围／缩小时间范围／显示全部（全天）”按钮及 `adjustTimeseriesView` 辅助实现已移除，包括旧实验页；通过 LS 原生概览交互操作时间范围。汉化、UUID、样式边界、权限和业务保存属于独立接入，不用于修改上述数据行为。

源码摘要保存在 `vendor/label-studio/timeseries-upstream-baseline.json`，检查命令：

```text
node scripts/qa/check-ls-timeseries-upstream.cjs
```

后续开发继续遵守本次边界，不再以“缺测优化”或“缩放保护”为由自行偏离这份上游源码。

## 本地验证

`scripts/qa/ls-upstream-timeseries-browser.cjs` 检查：

- 一份 CSV 直接成为一个曲线样本，不填写高级映射。
- CSV 原文件由 LS 读取，空单元格按上游行为处理；测试输入 `3,空,5` 得到 `3,0,5`。
- 新任务可配置、发布领取、标注保存，正式页无平台附加缩放按钮。
- CSV 任务清单一键按行生成两个样本，不进入高级映射。

原人工流程和布局回归仍使用对应脚本。最新运行证据见工作区 `.tools/ls-upstream-*.log` 与 `.tools/native-tests/ls-upstream-regression.json`；不把历史“缺测显示断点”的验收描述继续视为当前行为。

## 布局与坐标：2026-11-15 核查

右侧面板布局代码只作汉化，constants.ts、SidePanels.scss 和 App.scss 的几何定义使用上游原文。默认面板宽 320px、高度配置 300px；原版允许拖动、合并、调整大小，保存为浏览器 localStorage.panelState。XML 决定控件顺序、曲线高度和 overviewWidth。不同 XML、窗口宽度、浏览器缩放及已保存布局不能直接比较像素位置。

此前 Shadow DOM 只转换 :root，遗漏 body 上的原字体变量。本次补齐 body 到 :host 的作用域转换、.root 容器及原 Figtree 字体注册。组件样式仍隔离，document 只登记字体。浏览器实测 14px、Figtree、默认右面板 320px；1280/1700 两种宽度无横向溢出。平台导航、样本切换及业务提交区仍是平台外围，不等于 LS 原站整页。

横轴读取 timeColumn；省略则显示点序号。测试中的 0、0.25、1.5、2.5 是测试数据本身，不自动代表小时。日期字符串通过 timeFormat 解析，timeDisplayFormat 控制显示。纵轴读取 Channel.column，units 标明数据单位；多通道可有左右不同纵轴。实际数据到来后按其真实时间和单位配置，不把测试坐标设为固定业务坐标。

## LS 原版导入入口及字段含义

原版 `web/apps/labelstudio/src/pages/CreateProject/Import/Import.jsx` 中有 `Treat CSV/TSV as`，选择 `List of tasks` 或 `Time Series or Whole Text File`。因此两种解释来自 LS；当前平台把入口放在 ZIP 资源列表，LS 放在上传弹窗。平台仍以 ZIP 分块上传为主，上传界面及支持的直接上传文件并未完全复制 LS。

“普通导入”只是无需自定义分组或重命名的导入，不是第三种格式。CSV 按行读取时，每行是任务、列名是 data 的字段名。整份 CSV 是曲线时，data 保存文件引用，TimeSeries 读文件内的时间列和通道。

字段就是数据里的名称。例如 station,date,hour,power。分组是把 station、date 相同的行合并成一个曲线样本，hour 和 power 保留为点序列。映射是把实际列名接到模板所需的字段/通道上；已经一致时，不必另填表单。XML 读取已经形成的任务，不会自动推断业务要求按站点日合并。

项目创建者在数据页决定文件解释方式，在标注设置选择模板和调整绑定；领取标注的人使用已经冻结的规范，不需要逐样本填映射。

## 当前确实通过 XML 指定来源

```xml
<View>
  <TimeSeries name="curve" value="$csv" valueType="url" timeColumn="hour">
    <Channel column="power" units="kW" />
  </TimeSeries>
  <TimeSeriesLabels name="events" toName="curve"><Label value="异常" /></TimeSeriesLabels>
</View>
```

任务的 `data.csv` 是 CSV 引用，文件列为 hour,power。这只是功率示例；新数据如果表示能耗，应使用其真实单位，不默认都是 kW。

NativeEditor 请求任务上下文，读取授权资源接口的 Blob，把临时 URL 写入当前任务副本的 data.csv，然后执行 `new LabelStudio(container, {config, task, ...})`。LS 原生 TimeSeries 按 XML 读取 CSV。后端原始引用不替换为 Blob URL。

| HTTP 接口（前缀 /api/labeling/v1） | 用途 |
|---|---|
| POST /projects/{id}/imports；PUT /imports/{batch}/chunks；POST /imports/{batch}/complete | ZIP 分块上传和解析 |
| POST /projects/{id}/imports/{batch}/tasks | 从资源引用创建原生任务 |
| POST /projects/{id}/input-resources/{resource}/task-mapping | 按行导入或可选高级整理 |
| GET /label-configs/templates、/data-summary | 原模板目录与项目字段摘要 |
| POST /label-configs/validate、/sample-task | 后端验证 XML、取得真实样本或标明的示例 |
| POST /projects/{id}/spec-versions | 冻结规范版本 |
| GET /assignments/{id}/samples/{sample}/task | 获取当前领取人的任务、XML 及授权资源地址 |
| GET /assignments/{id}/samples/{sample}/native-resources/{resource} | 读取该样本资源 |

项目预览使用对应项目样本接口；后续发布、领取、草稿、提交、核查及成果接口见后端 API 与全流程说明。

## YAML、项目 XML 与任务插件的技术区别

YAML 是模板目录中的名称、分类、示例和 XML 起点。后端 TemplateCatalog 经 LsSourceAdapter 读取同版原始 YAML 并缓存，通过 HTTP 返回。选择模板后修改并保存的 XML 属于项目规范，不再等同原始 YAML。

“任务插件”是开发者登记的输入、编辑、验证、完成和导出协作能力，不是用户上传的执行文件，也不是一份 YAML 自己执行所有流程。

当前 TaskRegistry 已登记 TIME_SERIES、COMPUTER_VISION 和兼容图片分类。freeze 固化 templateVersion、pluginVersion、editorSourceVersion、converterSourceVersion、configSha256、parsedConfig、exportFormats、capabilities；当前公共引擎版本为 ls-native-2，契约版本 2。

| 部分 | 当前实现 |
|---|---|
| 输入与资源 | ArchiveParser、NativeTaskService、CsvTaskMappingService；任务与 input_resource 分开保存 |
| 编辑器 | 仓库内 LS 前端源码构建，业务页面直接实例化 |
| 配置/结果验证 | LsSourceAdapter 调用仓库内改造的 LS Python；按草稿、完成、提交分阶段处理 |
| 完成规则 | LS XML 规则和平台任务包状态共同约束 |
| 导出 | SourceExportService 从固定成果调用 SDK Converter，格式依配置生成 |
| AI | 当前声明 aiAvailable=false，没有宣称已有真实执行能力 |

边界：这是 LS 公共引擎的代码登记和版本化快照，不是可安装的动态插件框架。inputFormats 是能力声明，不能当作每个任务已有独立完备 JSON Schema、验证器标识和跨版本迁移的证明；这些要求仍需继续补齐。不是为每个模板各写一套后端。

## 本次清理及未完成项

删除早期独立试标页、受限 validateTimeseriesConfig 和失效实验样式；删除 IndexedDB 模拟项目、领取、浏览器成果导出页面及专属服务/测试。移除三份已被替代的离线/iframe 阶段说明。正式流程使用 HTTP 后端；旧图片兼容流程及有用回归测试保留。后端未发现可仅凭名称删除的旧业务服务，保留迁移和图片兼容，新增构建产物/日志忽略规则。

编辑器和配置页的帮助入口改为平台同源 `/#/labeling/help`，有实际说明内容，不虚构正式域名。许可证和来源声明继续保留。

本地人工主链可用，尚需新真实数据验收、服务端分页和大数据整理异步化、能力 schema/兼容策略完善、并发与失败恢复和生产构建专项验收。AI 未接真实执行。清理及新数据验收后可准备同名开发分支的推送；询问推送时间不等于明确授权推送。


## 2026-11-15 实例管理收敛

预览与正式标注使用同一个实例管理适配器 editorSession.mjs。首次创建 LabelStudio；相同页面中更新配置或任务时，按原版 Preview 的顺序调用 resetState、assignTask、assignConfig、initializeStore；离开该页面时销毁。答案在 storageInitialized 后延后一轮创建，再恢复当前样本结果与只读状态。正式任务切换不再通过 sampleId 强制重新挂载；重新读取任务和参考切换通过显式 refreshKey 刷新输入。

实例租约仅处理不同 React 根之间的页面交接，不再用于每次样本切换重新创建实例。配置编辑器中的 XML/预览数据变化复用实例；切换规范版本或配置页面作用域仍可重新挂载配置页。完整配置校验使用后端 HTTP；额外实例化脱离编辑器的 MST 配置树已经移除。这些 MST 状态模型与 AI 推理模型不同。

本轮证据：实例复用/重置顺序/答案恢复/只读更新/销毁次数测试通过；类型检查、编辑器源码构建通过；四个时序核心文件仍与固定上游字节一致。浏览器级切换验证与最终真实数据完整录像尚未完成，不将此前中断录像视作验收通过。本轮末本地相关服务未运行，未自动重建整套运行环境。


## 真实小样本录像验收完成（2026-11-15）

使用两个真实站点日及两张 VisA 图像，完整录制人工流程至确认、JSON/CSV 或 JSON/COCO 下载和项目完成。浏览器异常为零，草稿重新读取恢复通过；导出资源与抽样输入的 SHA-256 一致，结果来源、版本、坐标与样本数量检查通过。完整视频和验收说明位于工作区 .tools/acceptance-recording/2026-11-15，时长约 3 分 40 秒。此条更新前文“浏览器验证和录像尚未完成”的阶段状态；不扩大为全部模板或生产验收通过。
