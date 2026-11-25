# 结构化与半结构化数据插件

更新：2026-11-25。依据《东南大学-数据标注平台产品需求文档 V1.0》4.1、4.2、4.8 和 AC-02/AC-03 补齐两类数据。图像、时序及其历史插件继续保留。

## 插件契约

| 类型 | 插件 ID@版本 | 编辑器键 | 输入 / 结果格式 |
|---|---|---|---|
| STRUCTURED | structured-records@1.0.0 | structured-records-editor | structured-records-input-v1 / structured-records-result-v1 |
| SEMI_STRUCTURED | semi-structured-documents@1.0.0 | semi-structured-documents-editor | semi-structured-documents-input-v1 / semi-structured-documents-result-v1 |

后端实现 `TaskPlugin`，由 `BuiltInPlugins` 注册；前端可信描述位于 `recordData.mjs`，通过现有注册表与服务端目录联合判断可用性。新项目共四类；旧项目按冻结版本读取，不改旧插件摘要。

插件复用平台的导入续传、项目隔离、规范版本、任务打包、领取、CAS 草稿、整包提交、审核修订、成果快照及导出权限。表格和树形界面是独立可信组件，不执行上传的脚本。新类型当前使用人工发布；未登记这两类的 AI 模型。

## 导入规则与操作

1. 在“我的项目 → 创建项目”选择“结构化数据标注”或“半结构化数据标注”。
2. 在数据管理中上传 ZIP。表格可包含 CSV/XLSX/Parquet，文档可包含 JSON/JSONL/XML/YAML/YML。
3. 解析完成后查看错误报告，预览前 100 条记录。有效记录生成独立样本，错误行不生成样本；错误报告包含源文件及行号。不要把已报错的行数当作成功数。
4. 在标注设置中填写标签（每行 `标识|名称`）、说明、JSON Schema 与可选字段映射，保存为新规范版本。
5. 发布人工任务，领取后完成整条记录分类；按需选择字段/单元格/节点批量赋标签，文档可标文本实体与实体关系。
6. 保存每条记录，再提交完整任务包。需求方核查后固定成果，选择插件导出格式并下载 ZIP。

格式约定：

- CSV 为 UTF-8，自动识别逗号、分号和制表符。表头非空且唯一，必须每行列数一致。保留文本原值，包括 `001`、空字符串与 `0`，不推断数字，以免破坏主键；数值字符串可用 Schema 的 pattern 校验。
- XLSX 按各工作表逐行导入，首行为表头，保留空值、零值和负数。公式行报错，要求先转静态值；不执行公式或外部链接。日期/时间以可交换的字符串表示。
- Parquet 按行导入，保留字段、空值及数值；不支持 JSON 无法表达的二进制值，错误文件会报告原因。
- JSON 对象或标量生成一条记录；顶层数组按元素拆分。JSONL 每个非空行一条记录，坏行单独报告；不允许重复键或非有限数值。
- YAML 多文档按文档拆分；使用安全加载器，拒绝重复键、对象构造、锚点及别名。
- XML 每个文件一条文档；树映射包含 `tag/attributes/text/tail/children`，保留原始 XML 文本。禁止 DTD、实体和外部资源。
- 每批最多 10,000 条记录、解析后规范数据总量最多 64 MiB；原归档还受统一 ZIP 展开和文件大小限制。单条节点最多 20,000、嵌套最多 64 层、单个文本值最多 1 Mi 字符。大数据集应分批导入。

示例数据与完整操作脚本见 `examples/record-data/README.md`。

## 规范与服务端校验

`POST /api/labeling/v1/projects/{projectId}/spec-versions`：

```json
{
  "taskType": "SEMI_STRUCTURED",
  "templateId": "semi-structured-documents",
  "templateVersion": "1.0.0",
  "schemaVersion": "semi-structured-documents-result-v1",
  "labels": [{"id":"normal","name":"正常"},{"id":"person","name":"人名"}],
  "instructions": "选择记录分类；按需标出客户姓名。",
  "editorConfig": {
    "fieldMapping": {"name":"/customer/name"},
    "schema": {"type":"object","required":["name"],"properties":{"name":{"type":"string","minLength":1}}},
    "blockOnErrors": false
  }
}
```

Schema 使用 JSON Schema Draft 2020-12；仅允许本地 `$ref`/`$dynamicRef`，不下载外部 Schema。字段映射是校验字段名到源数据 JSON Pointer 的映射，只影响校验投影，不修改源值和标注路径。`{}` 表示不加规则。默认校验有问题时需填写问题说明方可完成；开启严格模式后有校验错误的记录禁止完成。系统不自动修正源数据。

新增只读校验接口：

- Owner：`GET /projects/{projectId}/samples/{sampleId}/record-validation?specVersionId=...`
- 标注员：`GET /assignments/{assignmentId}/samples/{sampleId}/record-validation`

均返回 `issues: [{path,message}]`。前者验证项目所有权及规范归属，后者使用领取时的冻结规范。映射后的问题路径指向校验投影，原始节点标注仍使用原路径。

输入沿用样本授权的 `/data` 接口：返回 `sampleId/inputHash/inputFormat/schemaId/sourcePath/sourceRow/record`，可选 `sheet/raw/columns`。来源还包含原文件资源标识与 SHA-256。

草稿保存仍为 `PUT /assignments/{assignmentId}/drafts/{sampleId}`，请求包含 `baseRevision/inputHash/explicitDone/resultFormat/result`。结果示例：

```json
{
  "labelId":"normal",
  "nodes":[{"path":"/customer/name","labelId":"person"}],
  "entities":[{"id":"e1","path":"/customer/name","start":0,"end":2,"labelId":"person"}],
  "relations":[],
  "notes":""
}
```

路径使用 RFC 6901 JSON Pointer（根节点为空，`~` 转义 `~0`，`/` 转义 `~1`）。实体范围为 UTF-16 码元、左闭右开，与浏览器文本选择一致，不能截断代理对。关系 `from/to` 必须指向本结果内已存在的不同实体；关系也使用规范标签。表格插件只允许行和节点标注，不接受实体关系结果。

服务端在保存、提交、审核修订时重新验证标签、路径、实体边界、关系及完成条件。空对象只可作为未完成草稿；完成结果必须有整条记录标签。每种标注列表最多 1,000 项，备注最多 10,000 字符。校验失败返回 422，CAS 冲突返回 409。

## 导出与原始数据回读

| 类型 | 导出格式 |
|---|---|
| 结构化 | structured-csv-zip、structured-jsonl-zip、structured-parquet-zip |
| 半结构化 | documents-jsonl-sidecar-zip |

仍使用 `/final-snapshots/{id}/plugin-export-formats`、`POST /projects/{id}/exports`、`GET /exports/{id}`、`GET /exports/{id}/download`。

- 结构化保留原列顺序及值，追加 `label/annotator/reviewer/updated_at/sampleId/annotations`。遇到同名原列时为新增列加 `_`，实际名称写在 `annotation-columns.json` 中。CSV 对公式前缀作转义；JSONL 和侧车保留原值。Parquet 只有混合类型列使用 JSON 字符串编码，并在该列元数据声明 `tss.encoding=json`，消费者据此解码。
- 半结构化包含 `documents.jsonl`、`annotations.jsonl` 和原始来源文件 `original-{资源ID}.{扩展名}`。侧车的 `source.originalArchivePath` 指向原文件，`sourceHash` 为原文件 SHA-256，`annotations` 记录节点/实体/关系，`sampleId/inputHash/sourceRow` 用于回读匹配。JSON 数组拆分记录共享同一原文件，保留其完整字节与排版。
- 所有类型的 `annotations.jsonl` 都包含原始记录、标注、规范、来源和审核信息，ZIP `manifest.json` 列出文件摘要与大小。平台继续提供下载 ZIP 的 SHA-256。
- 新增 V28 在成果条目中冻结 annotator/reviewer/updated_at；后续审核变化不会改写旧成果。历史成果该字段为 NULL，不猜测补写。
- 沿用单次导出 64 MiB 限制，过大时需缩小成果范围。

## 发布与验证

前后端分别上传其对应分支并配套发布，先确认两边目录及组件一致。V28 是新增迁移，不能修改既有 V1—V27。新增 openpyxl/et-xmlfile/pyarrow 的 Linux Python 3.11 wheels 已加入现有哈希锁与离线发布链路；发布前的 runtime_check 同时核对 XLSX、Parquet 和规则校验。

后端：`RecordWorkflowIntegrationTest` 覆盖两个插件的导入、授权、规范、校验、CAS 草稿、提交、审核、冻结和全部导出；`test_record_codec.py` 覆盖各输入格式与异常。前端：插件注册、四种创建入口、字段路径、草稿归属和实际编辑器交互测试。

本地验证与 GitHub CI/目标服务器部署分别记录；本地通过不代表线上已经具备新入口。音频、视频和点云未在该文档一期数据范围内，本次未新增这些插件。
