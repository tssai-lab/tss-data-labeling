# 模板配置页 HTTP 接入

更新：2026-11-15。仅本地，前端 feat/frontend_LS、后端 feat/backend_LS；未推送、合并、部署。

## 当前行为

保留 LS ConfigPage、模板卡片、可视化/XML 编辑和原生预览。callApi 由平台鉴权请求适配器实现，不再从浏览器内目录返回模板、伪造字段建议或生成正式页面预览数据。XML 语法/编辑器本地检查保留，后端校验不能绕过。

| 操作 | 路径（统一前缀 /api/labeling/v1） |
|---|---|
| 创建项目的任务类别 | GET /label-configs/task-types |
| 模板目录 | GET /label-configs/templates，可选 category |
| 字段建议 | GET /label-configs/data-summary，可选 projectId、sampleId |
| XML 校验 | POST /label-configs/validate |
| 获取预览数据 | POST /label-configs/sample-task |
| 示例图片 | GET /label-configs/preview-image |
| 正式保存 | 原有 POST /projects/{projectId}/spec-versions |

validate/sample-task 请求为 labelConfig、projectId、sampleId、taskType。项目内以项目实际类别为准，不相信浏览器传来的类别；sampleId 必须属于该项目且账号具备访问权限。未选择样本时由后端 LS 源码生成示例，UI 明确显示“示例数据预览”；实际项目样本显示“项目样本预览”，配置不匹配时拒绝，不替换为示例。

sample-task 返回 sample_task、resources、isExample，以及配置内示例的 annotations／predictions。引用资源由平台鉴权接口读取；后端生成的小型 CSV 可直接随预览响应返回内容。浏览器将内容或授权下载的资源转为临时 blob URL 供编辑器显示，不把临时 URL 写入正式规范或结果。字段建议来自项目级摘要（scope=project），包括全量字段出现次数、公共字段及按规范区分的结果／草稿标签统计；预览样本字段单独返回。

模板目录从同版上游 YAML 与 groups.txt 唯一来源读取并缓存，返回内容版本与来源摘要；前后端旧 JSON 目录已移除。API 仅开放 12 个计算机视觉、5 个时序社区预设。前端不在接口失败后回退本地目录。独立开发工作台也使用 HTTP 示例预览，原站点日 CSV 本地读取入口移除；真实数据在项目内上传与预览。本地草稿仅为开发工具，不等于正式规范保存。

## 源码与处理

后端从 LS core/label_config.py 提取配置校验和示例生成，移除 Django 应用依赖并接平台权限/资源路径。即时校验与预览使用本地管理的工作进程及配置解析缓存；保存规范时仍解析并冻结配置、完成规则及导出能力。源码来源、修改及摘要见后端 ls-source-adapter/SOURCE.md。

前端配置请求集中在 src/services/labelingConfig.ts，编辑器运行时通过注入的 request 调用。页面离开会取消未完成的配置请求，较旧配置返回不覆盖当前预览。模板获取失败显示原因和重试入口。

## 验证与边界

- 后端全量 60 项：59 通过、1 跳过；Python 源码 31 项通过。
- 17 个社区模板的后端示例生成检查通过，不等于 17 个模板的业务闭环均验收。
- 本地真实登录后，模板、字段建议、校验、预览、示例图片均记录到成功 HTTP 请求；12/5 分类计数及企业版隐藏通过，示例图片实际解码通过。
- 原生时序项目重新跑通导入、配置、发布领取、标注提交、需求方确认、完成和实际 JSON 下载；pageerror 与失败的标注请求均为空。
- 配置页脚本 scripts/qa/ls-native-config-browser.mjs 已更新为真实登录/HTTP，覆盖 12/5 模板、后端 CSV 示例、XML/可视化、别名、面板拖动、自定义配置和草稿恢复，无 pageerror。
- TypeScript、编辑器源码构建、整站前端生产构建通过；后端打包通过。构建仅生成本地文件，未部署。
- 浏览器证据位于工作区 .tools/native-tests/config-http-flow.json、config-http-catalog.log、config-http-browser.log。

本轮联调请求限定 localhost。多轮／部分重标／跨规范成果有后端集成回归；图像 CSV 生成和画框保存有真实页面证据。原始能耗 ZIP 临时文件已不存在，完整真实数据容量尚未重新验收。AI、独立质检及所有复杂控件组合继续独立推进。详情见后端 docs/labeling-v1.1/20-generic-ls-mechanisms.md。
