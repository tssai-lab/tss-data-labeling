> 2026-11-15 更新：当前目录、校验和预览均走真实 HTTP；模板唯一来源为后端同版 YAML／groups.txt，旧 catalog.json 已移除。以下较早实验记录不能作为当前运行事实；详见 ls-config-http.md 和 local-integration-2026-11-15.md。

# LS 原生模板配置页面接入

> 2026-11-15：正式配置页现已改为请求平台后端提供模板、校验、字段建议及预览。下文是最初本地接入记录，涉及浏览器生成预览、能耗 CSV 工作台的描述已被替换。当前调用契约与验收见 [HTTP 配置页](ls-config-http.md)。

日期：2026-11-14。入口：`/#/labeling/templates`。本地开发分支：feat/frontend_LS。

## 范围

保留 LS 原有分类结构，本期仅展示“计算机视觉”和“时序分析”，其他类别后续接入，不新增“平台能耗”等业务分类。能耗数据是时序预览样本。此前三个能耗模板按钮及提取控件的自建配置页已由原生 ConfigPage 替换。

## 源码

基线：LS 1.23.2 / df96d79183156e556d91825d15fa7721f1145e1a。

vendor/label-studio/platform-config 完整接入 CreateProject/Config 的 Config.jsx、TemplatesList.jsx、Template.js、tags.js、colors.js、Config.scss、useConfigResizer.js、EditorResizer.tsx 及样式，复用 ToggleItems。

保留分类模板卡片、可视化/可编辑 XML 切换、标签增删改和颜色配置、字段绑定、控件设置、XML 提示/查找、实时预览、大配置手动更新逻辑、宽度拖拽和保存反馈。

catalog.json 从同版 annotation_templates 的 YAML 读取，包含 11 个原分类、69 份模板定义和原 XML。缩略图来自同版 core/static/templates，保存到 public/ls-template-assets。locale.js 仅翻译展示文案和模板标题，不修改分类键、模板 ID 或预设 XML。原有标签名称由用户配置，不自动替换为平台业务规范。企业版模板仍按上游社区版逻辑不可选。

## 平台适配边界

- Bridge.jsx 替换外围应用的 API context、基础输入、保存操作区和加载提示。去掉埋点；切换和离开确认接平台逻辑。
- nativeConfig.js 提供本地列表、校验、样本和编辑器生命周期。预览仍直接 new LabelStudio，不依赖 Django 或旧 LS 服务。
- Preview 的外围依赖适配到 NativePreview：引用本地容器，隔离异步销毁，预览任务使用独立 ID，避免切样本后恢复旧视野。
- 标签改名时为无 alias 的原标签保留原值作为 alias，新标签生成稳定 ID。选择上游模板时不提前改写预设 XML。
- 源码与共享库均在仓库构建，运行于同文档 Shadow DOM 和 React 18；CodeMirror 提示挂在配置区域内。

## 数据与保存

校验复用 LS Tree 和 ConfigValidator，并检查 XML 格式、重复控件/标签、空标签和可执行属性。本地预览提供图像、文本和时序样本；音频/视频等缺少本地样本时明确显示错误，模板浏览及配置仍保留，不据此宣称全部任务已接入业务。

默认时序曲线是示例。选真实能耗 CSV 后，只有 value_kwh 通道有实际值；模板使用其他通道时提示修改 XML 映射，不把真实能耗伪装成速度、股票或多个传感器通道。用户也应相应配置单位和图例。

站点日 CSV 保留 task_id、来源行和摘要，24 小时值与日值分开，不补零。数据包前三条宽表样本仅存被 Git 忽略的 public/local-energy-preview.csv；来源摘要存忽略的 .qa/energy-source.json，完整归档未加入源码。

模板草稿仅存当前浏览器，formatVersion=2，绑定作用域、模板 ID/版本、配置 schema、XML 和说明，不含样本原文。旧格式不静默解释。它不等于正式项目 specVersion，没有服务端授权、revision 或发布冻结语义。预览答案不保存。

原独立试标示例仍保留用于交互回归。本次未把任意 XML 写入现有 IMAGE_CLASSIFICATION 接口，未改后端业务分支。

## 验证

scripts/qa/ls-native-config-browser.mjs 检查当前两类入口、五个时序模板、无额外能耗分类、图像/XML/可视化预览、自定义配置、保存恢复、未知控件拒绝、真实时序映射、标签 alias 及宽度拖动。旧 ls-template-browser.mjs 转发到此脚本。

截图存忽略的 .qa/ls-native-catalog.png、ls-native-config.png、ls-native-timeseries.png。原时序编辑器回归为 scripts/qa/ls-source-browser.mjs。构建、类型检查及输入/草稿服务测试独立运行。

本轮已通过：源码构建、TypeScript、17 项服务测试、原生配置页浏览器检查及原时序标注页回归。图像和时序预览、标签 alias、宽度拖拽、配置保存恢复已实际操作；没有将其他任务的样本支持或正式业务流转视为已验收。
