# 源码来源与修改记录

- 上游：HumanSignal/label-studio-sdk，Git tag `2.1.2`。
- 固定提交：`c20c899aaaa302740dacd46a47e5774bb677561f`。
- 本地研究基线：Label Studio `1.23.2`；其后端 `data_export/models.py` 使用 SDK 的 `Converter`。
- 复制范围：`src/label_studio_sdk/converter/`、`src/label_studio_sdk/_extensions/label_studio_tools/core/`。
- 许可证：Apache-2.0，原文见 `LICENSE.label-studio-sdk`。保留原文件中的版权和第三方说明。
- `upstream-files.sha256.json` 记录复制前的原始 SHA-256。SDK 的包元数据版本与 Git tag 不一致时，以这里的固定提交为准。

## 平台修改

1. `__init__.py`：最小源码包初始化，避免载入生成的 SDK HTTP 客户端，也不再吞掉依赖导入错误。
2. `converter/converter.py`：数组文件读取所需 `ijson` 延迟导入；平台使用原生逐任务文件迭代，不依赖该分支。明确 UTF-8 读取，排序任务文件以保证顺序。YOLO 使用平台样本 ID 命名，避免同名图片覆盖。COCO 保留输入资源引用，避免导出内部临时文件路径。
3. `core/utils/io.py`：JWT 检测依赖延迟导入；用平台提供的文件清单替换 LS 服务下载入口，HTTP 读取明确拒绝。
4. `converter/utils.py`：旧 `download` 入口改用相同的受限资源清单，不推断 LS 上传目录、不下载远程资源。
5. 新增 `adapter.py`、`platform_resources.py`：版本化调用协议、基础 XML 检查、格式启用范围、离线文件解析。

上游其余转换器源码保留供后续适配，不代表所有格式已获平台启用或完成验收。Python 源码和模板 XML 由开发者管理，用户不能上传执行 Python 插件。

## LS 后端机制及当前差异（2026-11-15）

基线 Label Studio `1.23.2`，提交 `df96d79183156e556d91825d15fa7721f1145e1a`。许可证见 `LICENSE.label-studio`、`NOTICE.label-studio`。完整原文件摘要见 `label-studio-files.sha256.json`。

| 文件／机制 | 保留的上游源码 | 平台修改及原因 |
| --- | --- | --- |
| `annotation_templates/` | 完整 YAML 与 `groups.txt` | 唯一运行时模板内容来源，不另存前后端 JSON 目录。HTTP 仅开放时序、视觉社区模板；原始 YAML 摘要、提交和内容版本可追溯。 |
| `ls_backend_templates.py` | `projects/api.py:read_templates_and_groups` | 注入本地目录、增加稳定 ID 和内容摘要；Java `TemplateCatalog` 首次读取后缓存，同 LS 进程内缓存，升级源码后重启刷新。 |
| `ls_backend_config.py` | `core/label_config.py` 的解析、Choices 修正、schema、名称／绑定与 SDK 属性校验 | 仅移除 Django／DRF 框架耦合；保留 `xmljson`，安全 XML 解析，本地固定 schema。异常转为平台 422。 |
| `ls_backend_samples.py` | 原始示例生成、XML 内示例、注释内 annotations／predictions、NumPy／pandas 时间序列生成 | 注入本地设置与数据文件，不再用缩减版标准库生成器。 |
| `platform_preview.py` | 包装上述生成器，保留任务／答案／预测结构 | `%Q`／`%s`／`%L` 补齐；图片和曲线使用受控本地资源，明确 `isExample=true`，不写入样本或领取流程。支持数组字段和 Repeater 示例资源映射。 |
| SDK `label_interface/` | 控件模型、结果／关系验证、schema 与属性校验 | Python 3.9 兼容：`control_tags._try_parse_json` 返回类型 `dict | None` 改为 `Optional[dict]`，无业务语义变化。`_legacy` 仅复制所需异常定义和初始化文件。 |
| `platform_result.py` | 调用 SDK `validate_region/validate_relation`；遵循编辑器 Required／Visibility 机制 | 服务端增加实际任务动态标签、条件／按区域必填、可信图片尺寸、几何边界和 RLE 长度验证。草稿可未完成；完成和整包提交严格校验。原答案保持原生结构。 |
| `ProjectSummaryService` | 移植 `ProjectSummary` 字段及计数语义 | 适配 PostgreSQL 业务表：导入事务内维护字段索引；从一致数据库快照统计提交和有效草稿，草稿覆盖及提交后不重复计数。按规范分别统计，不用预览样本代替项目摘要。 |

上游项目原地修改配置时会阻止删除已使用标签／控件；平台创建不可变新规范版本，允许新版本改变标签，旧提交仍绑定旧规范。兼容参考按解析后的控件结构判断，不兼容参考只读展示，均不自动完成。本差异由 `NativeWorkflowIntegrationTest.partialReworkKeepsHistoryAndMultiSpecOutcomeExportsSeparateConfigs` 覆盖。上游当前字段一致性强制校验也处于注释状态；平台在实际预览和发布时验证所选样本，不以全项目公共字段阻断不同批次。

原生 JSON 导出保留嵌套字段；COCO／YOLO 转换前在私有任务副本中将输入绑定投影为 SDK 所需的直接键，源任务不变。导出只能读取已冻结确认成果，各规范分组保存 XML、资源和来源清单。

Python 由 Java 管理本地固定工作进程（交互与导出分别限流），每次请求使用独立临时目录；无网络监听、无 Django 应用，不读取用户可执行代码。标准输入只传受控任务目录，超时终止并替换进程；服务关闭时回收。`validate_config` 的解析缓存返回深拷贝，避免请求间状态串用。

验证：`tests/test_adapter.py`、`tests/test_platform_result.py` 和 Java 本地集成测试。尚未开放的格式或类别不因保留上游文件而自动开放。AI 能力明确为未接入。
