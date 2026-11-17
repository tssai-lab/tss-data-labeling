# 可修改的 Label Studio 编辑器源码

来源：HumanSignal/label-studio，正式标签 1.23.2，提交 df96d79183156e556d91825d15fa7721f1145e1a。

本目录纳入上游 web/libs/editor、core、ui、app-common 的源码及资源，并保留依赖清单、yarn.lock、LICENSE、NOTICE。测试目录及 coverage 不纳入；并非完整 LS 服务。研究源码仍位于工作区相邻的 label-studio 仓库。

## 构建

前端根目录的 `npm run build` 会先执行 `build:ls-editor`，从此目录源码生成 public/ls-editor，再构建平台。`npm run dev:local` 同样先构建编辑器。修改源码后执行 `npm run build:ls-editor` 并刷新实验页面。

先在平台根目录执行 `npm ci`、`npm run install:ls-editor`。后者固定 Yarn Classic 1.22.22，按本目录 yarn.lock 安装，禁用依赖安装脚本。依赖不提交。CI 与 Docker 均显式安装此目录依赖；本地仍可用 LS_DEPS_ROOT 指定依赖缓存，或使用研究目录缓存。正式构建不要求存在工作区旁边的研究仓库。

实际编译入口为 src/ls-editor-runtime/index.js，编译规则为 scripts/build-ls-editor.cjs。生成的 public/ls-editor 被 Git 忽略；它们是本工程编译产物，不是源码来源。

前端页面直接执行 `new LabelStudio(container, options)`，不使用 iframe 或 postMessage。构造函数来自本工程纳入源码的构建入口。React 18 编辑器与 React 19 平台各自维护根；编辑器容器和专属样式位于当前页面的 ShadowRoot 中，它不是独立页面。保留完整编辑器能力作初始实验，因此当前开发包较大，尚未做正式体积优化。

## 对上游源码的修改

- libs/editor/src/LabelStudio.tsx：公开 ready Promise，供页面等待初始化、处理错误及安全销毁。
- libs/editor/src/core/Hotkey.ts：使用 composedPath 识别真实输入元素，仅处理编辑器内部快捷键；LabelStudio 为容器添加相应标记。

- libs/editor/src/stores/TaskStore.js：任务 ID 接受数字或字符串，以保存平台 sampleId。
- libs/editor/src/stores/AppStore.js：任务历史 ID 同样支持字符串。
- stores/CustomButton.ts 与 AppStore：增加 draftOnly 动作选项，草稿保存不推进队列或清除编辑状态，默认 false 保持原行为。
- Settings 与 InstructionsModal：弹窗在当前编辑器 DOM 内渲染，适配 Shadow DOM。
- 当前时序工作台中文化：编辑器工具栏、侧栏、区域分组/排序、说明、设置和备注控件直接修改源码中的显示文案；快捷键配置只修改 description，不修改动作键或按键。React 18 入口使用 Ant Design 中文 locale；TimeAgo 和历史时间使用 date-fns 中文 locale。内部 panel name、模板名称、alias、结果字段保持原值。

其余集成修改放在平台入口、模板生成和构建脚本中。整日初始视野通过 XML `overviewWidth="100%"` 配置，未重写曲线绘制。

## 许可和范围

### 模板配置源码

`platform-config/` 完整接入相同版本 CreateProject/Config 的 ConfigPage、TemplatesList、Template、控件定义和样式、宽度拖动、ToggleItems 及 Palette。已替换此前提取组件的实验实现。原 YAML 保留69份定义，平台开放2类别17份社区预设，没有增加能耗分类。平台只适配外围 context、数据/校验/保存、路由离开逻辑及预览实例生命周期，并汉化显示文案。模板现在通过平台 HTTP 从后端同版 annotation_templates YAML 与 groups.txt 读取；旧前端 catalog.json 已移除。缩略图来自同版 core/static/templates。具体边界见 docs/ls-template-workspace.md；不依赖上游 Django 或旧 LS 服务。

保留上游 LICENSE/NOTICE 及文件声明；上游 package.json 和 LICENSE 的声明存在差异，不以 package.json 的单个字段替代实际文件声明。

当前已验证原生时序人工闭环、真实能耗24点和23,177样本导入、图像画框保存及跨规范成果。完整源码可编译不代表所有音频、视频、模型、导入或导出功能已验收。没有引入 Django、数据库、LS 项目/任务 API 或共享 Token。

当前仅验证单个活动实例。弹窗、下拉等可能使用 document.body portal 的控件尚需专项适配；不能将时序测试视为所有控件的 Shadow DOM 或全局快捷键兼容证明。

原生时序工具接入和逐项验证见 `docs/ls-upstream-timeseries-behavior.md`；目前设置和规范弹窗已适配，不能推广为其他全部 portal 已验证。

## 2026-11-15 源码逐文件核对

与固定提交的 web/libs 对照，纳入范围1,739个文件内容一致、37个修改，另增1个工具文案文件，忽略行尾差异。详细清单在工作区 .tools/ls-source-audit.json；统计不包含未纳入的上游测试目录，也不表示复制了完整 LS 产品。

按用户要求，TimeSeriesVisualizer.jsx、TimeSeries.jsx、TimeSeries/helpers.js、utils/data.js 已恢复为 LS 1.23.2 原文件。缺测处理、CSV 解析、概览刻度、下采样和缩放不做平台行为修改。摘要见 timeseries-upstream-baseline.json；检查命令 node scripts/qa/check-ls-timeseries-upstream.cjs。

正式业务页面的 LocalEditor 使用 interfaces 选择面板、隐藏原 LS 提交按钮并由平台保存草稿／完成／整包提交；规范说明通过原生 instruction 入口查看。平台补充的时序范围按钮和 adjustTimeseriesView 辅助实现已移除，使用上游原生概览交互。外围任务布局不是 LS 原站 DataManager 整页。

构建默认 production，压缩器不启动并行子进程；`npm run dev:local` 使用显式 development 构建。输出 `editor.<contenthash>.js` 和 manifest.json，页面以 no-store 读取清单，避免稳定文件名导致旧编辑器缓存。`npm run check:ls-artifact -- dist` 校验正式产物、模式及 SHA-256。生产编辑器约10.5 MiB；已通过实际生产静态页的时序及图像复查，仍需控制完整编辑器包体积。

2026-11-15 布局收尾：共享 Tooltip 在 Shadow DOM 内挂到同根并换算定位，修复提示脱离样式的问题；普通文档行为保持原样。布局、原生工具及切换／保存回归见 docs/ls-workspace-layout.md。
