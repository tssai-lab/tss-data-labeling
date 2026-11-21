> 最新核验（2026-11-15）：工作区已提供原始 ZIP；556,248小时行→23,177样本、约74秒映射及真实24点标注恢复已通过。下文旧“文件不存在／全量未验收”说明已由本条取代。完整当前状态见 [平台全流程与源码差异](21-platform-flow-and-ls-differences.md)。

# LS 通用机制接入与本地验收

日期：2026-11-15。前端 `feat/frontend_LS`，后端 `feat/backend_LS`。仅本地开发，不推送、合并或部署。

## 本次范围与实现

| 用户要求 | 当前实现 | 主要代码 |
| --- | --- | --- |
| 上游模板单一来源 | 固定 LS 1.23.2 的原始 YAML 与 groups.txt；沿用上游读取函数，后端首次加载后缓存。前后端旧 JSON 目录已移除；API 返回来源摘要和内容版本 | `ls-source-adapter/annotation_templates`、`ls_backend_templates.py`、`TemplateCatalog` |
| 项目级摘要 | 导入事务内维护每个任务的字段索引；读取一致数据库快照，统计字段次数、公共字段、已提交结果类型／标签及当前有效草稿。按规范另给统计，草稿覆盖或提交不重复计数 | V19、`ProjectSummaryService` |
| 配置校验 | 保留原 LS schema、BadgerFish 解析、Choices 修正、名字／绑定、SDK 属性验证；项目类别和资源绑定接平台上下文 | `ls_backend_config.py`、`TaskRegistry`、`NativeTaskService` |
| 示例生成 | 恢复上游生成主体及 data_examples；保留 XML 内 data、annotations、predictions。增加本地资源包装、时间格式兼容；示例标记 isExample，不进入业务样本 | `ls_backend_samples.py`、`platform_preview.py` |
| 通用输入 | 原生任务 JSON；CSV／图片独立资源；CSV 按分组生成多通道时序，或每行生成任务；图片选择生成任务。支持相对路径、数组／嵌套绑定、valueList、缺测、来源和幂等／内容去重 | `NativeInputParser`、`CsvTaskMappingService`、`NativeTaskService` |
| 分阶段结果验证 | SDK 控件／结果／关系模型，加平台真实任务动态标签、条件／按区域／按项必填、图片实际尺寸、坐标、RLE 与时序边界。DRAFT、COMPLETE、SUBMIT 分开 | `platform_result.py`、`NativeResultValidator`、`ResultService` |
| 能力和版本 | 规范冻结模板 YAML 版本、XML 摘要、插件 ls-native-2、控件、输入格式、完成阶段、源码版本、导出格式和兼容版本。未知／未开放控件及企业版模板拒绝，原生 AI 明确禁用 | `TaskRegistry` |
| 多轮和跨规范 | 只发布选中样本；参考不自动完成。兼容参考可载入，不兼容参考使用原规范只读查看；原提交与修订／新轮次独立。确认成果可跨规范选择，每个样本只能选一份 | `TaskService`、`OutcomeService`、V20 |
| 多格式交付 | 固定确认成果异步导出；同规范保留平铺结构，跨规范按版本分目录，格式取各规范交集。原输入和引用资源摘要复核，随包 XML／资源／来源清单 | `SourceExportService`、SDK Converter |

公共流程继续使用平台项目、批次、发布、领取、结果、核查和成果模型。没有为能耗、分类或检测另写独立后端；模板组合 LS 对象和控件。

## 实际 HTTP 流程

1. 创建项目后分片上传 ZIP：`POST /projects/{project}/imports`、`PUT /imports/{batch}/chunks`、`POST /imports/{batch}/complete`。原 ZIP 和解析资源存配置目录，业务记录存 PostgreSQL。
2. 读取 `/projects/{project}/input-resources` 与 `/input-samples`。任务 JSON 直接成为样本；CSV／图片不会因同在 ZIP 中就自动混成领取样本。
3. 原始 CSV 调用 `POST /projects/{project}/input-resources/{resource}/task-mapping`，图片调用 `POST /projects/{project}/imports/{batch}/tasks`，都生成原生 `{data, meta}` 任务。
4. 配置页面通过 `/label-configs/templates`、`/data-summary`、`/validate`、`/sample-task` 获取目录、项目摘要、XML 校验和预览。无真实样本时使用明确标识的后端示例，有样本时读取授权样本。
5. 保存 `/projects/{project}/spec-versions`，冻结 XML 和能力版本。发布前按所选样本验证实际字段与资源。
6. 发布计划 → 领取 → `/assignments/{assignment}/samples/{sample}/task` 返回编辑器上下文；资源请求验证当前项目或领取关系。页面用仓库内源码构建的 `new LabelStudio(...)`。
7. `PUT /assignments/{assignment}/drafts/{sample}` 保存未完成草稿或标记完成，后端按阶段校验；整包提交再次执行 SUBMIT 校验及 revision 检查。
8. 需求方核查、修订、确认或选择部分重标。固定 `/projects/{project}/final-snapshots` 后选择适用格式导出；完成项目要求有效确认成果覆盖已发布样本，之后只读。

完整接口前缀为 `/api/labeling/v1`。Java 调用同仓库内受控 Python 工作进程，无独立 LS Web 服务，也不允许用户上传可执行插件。

## CSV 两种方式

时序方式继续使用 `timeColumn/channelColumns/groupColumns/timeType/timezone/units/fieldName`；增加 `mode: "timeseries"` 和可选 `delimiter`。分组生成一个原生任务，空数值保持 null，负值不改写，重复分组时间报错。

逐行方式示例：

```json
{
  "mode": "rows",
  "columnMapping": {"image": "photo_path", "text": "description", "options": "options_json"},
  "resourceFields": ["image"],
  "jsonFields": ["options"],
  "delimiter": ","
}
```

映射键是任务 data 字段，值是 CSV 来源列。引用资源按 CSV 所在目录解析，生成任务前规范为同包根相对路径；拒绝越界、不存在的资源和远程地址。JSON 字段可保存数组或对象；引用资源字段也支持路径数组。元数据保留 CSV 资源 ID、摘要、源行号及映射。同批次重新生成完全相同任务不会新增副本；同一幂等键不同内容报冲突。不同导入批次各自保留来源，不自动按内容跨批次合并；新幂等键再次上传会创建独立批次，不能将本机制称为跨批次全局去重。

CSV 导入识别逗号、分号和制表符，映射页面显示实际识别结果。UTF-8 BOM、引号与缺测保留。

## 与原 LS 的有意差异

- 原 LS 项目原地修改配置，已有答案可能阻止删除标签；平台保存不可变新规范版本，旧答案仍用旧规范解释。跨规范不直接拼成同一 XML。
- 摘要计数来自历史提交和有效草稿，另按规范区分；不将需求方修订伪装成另一份人工提交，也不把参考结果作为新完成结果。
- 图片和 CSV 使用平台受控本地资源；不沿用 LS 上传目录、用户 token 或自动下载外网资源。示例与实际业务数据分开。
- XML、原生 JSON 保留原结构；COCO／YOLO 处理嵌套字段时，仅在转换任务副本中投影为 SDK 所需直接键，源任务不变。
- 能力按当前登记控件及实际配置开放。保留其他上游代码不表示所有 LS 类别、企业功能、远程标签服务、AI 或导出格式已经可用。

完整源码来源、许可证和修改记录见 `labeling-service/ls-source-adapter/SOURCE.md`，原文件摘要可用于升级比对。

## 验收证据与边界

后端测试日志：工作区 `.tools/groups12-backend-tests.log`；源码回归 `.tools/groups12-source-tests.log`；前端类型、源码构建及生产构建日志使用同名前缀。最终数量以本日志和下方前端验收记录为准。

已验证的关键用例：

- 项目多字段交集／次数；草稿替换、提交后的标签统计及项目权限。
- 动态标签与 alias、条件必填、每区域必填、跨对象同 ID 隔离、关系端点、分类、多通道、分数时间、区间和未完成草稿。
- 图片实际尺寸、越界坐标、数组／valueList、嵌套相对资源、RLE 尺寸及损坏长度；同名资源授权隔离。
- 部分重标、旧提交保持不变、参考不算完成、不同规范确认成果、分页不漏规范、跨规范分组导出及项目完成。
- JSON、JSON_MIN、CSV、TSV，以及适用检测配置的 COCO／YOLO 实际转换、下载解包、资源与来源清单。
- 浏览器真实时序闭环、CSV 图片生成任务／真实画框保存、模板两类别和成果双宽度。
- 跨规范页面实际勾选、冻结和下载；解包检查两份 XML、两个任务、正确确认标签和来源清单，无 blob 地址。

仍不能以这些测试代替所有控件组合与生产容量验收。用户早先提供的微信临时能耗 ZIP 在本次联调时已不存在，尚未重新完成该原始文件的 23,177 组全量验收；不得把小型测试数据称为该数据集。AI、独立质检和第三组生产运维能力另行推进。
