# 可信任务插件注册机制（第一版）

更新：2026-11-21。

插件是随平台源码构建、随服务发布的可信代码。前端和后端分别登记实现，后端目录决定哪些模板可以创建项目。此版本没有用户上传 JAR、JavaScript 或 ZIP 后直接执行的能力。

## 通用类型与业务模板

创建项目提供 **图像标注**（`COMPUTER_VISION`）、**时序标注**（`TIME_SERIES`）、**结构化数据标注**（`STRUCTURED`）和 **半结构化数据标注**（`SEMI_STRUCTURED`）四个入口。图像和时序分别使用 `computer-vision@1.0.0`、`time-series@1.0.0`，共用原生编辑器及 `label-studio-v1` 结果契约。新增表格与文档使用独立的版本化编辑器及记录结果契约，详见 [记录插件](10-record-data-plugins.md)。

整图分类、画框、多边形、异常点、异常区间等通过项目的“标注设置”选择模板或配置控件。能耗、温度、电流是时序业务内容，不再各占一个创建入口。标签、说明、数据字段和坐标/时间单位属于模板与数据映射；新业务优先复用现有控件、CSV/JSON 输入、校验和导出，只有通用能力无法表达的新交互或数据结构才考虑扩展插件。

数据适配不等于新的任务插件。当前通用入口支持原生 JSON、CSV 文件引用、CSV 按行/长表分组及图片资源生成样本；不会自动把任意宽表解释为曲线。旧站点日宽表专用导入器和业务校验仍随历史插件保留，不能将其任意载入一个新时序项目或静默修改历史输入格式。

## 已安装契约与历史兼容

| 插件 ID | 版本 | 任务类型 | 编辑器键 | 默认结果格式 | 用途 |
|---|---|---|---|---|---|
| computer-vision | 1.0.0 | COMPUTER_VISION | label-studio-editor | label-studio-v1 | 新建图像项目 |
| time-series | 1.0.0 | TIME_SERIES | label-studio-editor | label-studio-v1 | 新建时序项目 |
| structured-records | 1.0.0 | STRUCTURED | structured-records-editor | structured-records-result-v1 | 表格行/单元格标注 |
| semi-structured-documents | 1.0.0 | SEMI_STRUCTURED | semi-structured-documents-editor | semi-structured-documents-result-v1 | 文档节点/实体/关系标注 |
| image-classification | 1.0.0 | IMAGE_CLASSIFICATION | image-classification-editor | image-classification-v1 | 历史分类项目及旧客户端兼容 |
| energy-station-day | 1.0.0 | TIMESERIES_ANNOTATION | energy-station-day-editor | annotation-record-v1 | 历史能耗项目及旧客户端兼容 |

通用 LS 能力继续使用平台现有配置、原生资源、结果与导出适配。图片和站点日插件复用已有解析器与校验规则，未改变历史结果格式。

## 使用流程

1. 需求方创建项目，选择图像、时序、结构化或半结构化类型；平台匹配后端已启用且前端已安装的通用插件。
2. 平台保存项目的插件 ID 和版本，使用通用输入适配导入未标注数据；需要时配置 CSV 字段和分组。
3. 需求方配置标签和说明，保存规范版本；规范固定插件版本、输入/结果 schema 和摘要。
4. 发布时选择人工或 AI。人工按包领取，宿主按 `editorKey` 打开编辑器；AI 按范围执行，通过已有 Worker 接口读取输入并回报结果。
5. 插件校验单样本草稿与完成条件，平台校验整包是否齐全、版本是否一致及操作者权限。
6. 需求方核查、修订或再次发布，确认后固定成果快照，再由该快照对应版本的插件导出。

插件登记不会自动接入 AI 模型。模型或规则 Agent 的部署、任务能力匹配及质量验收仍走现有 AI 执行流程。

## 插件接口和平台边界

后端接口：`labeling-service/src/main/java/com/tss/labeling/plugins/TaskPlugin.java`。

插件实现以下能力：

- `descriptor()`：稳定 ID、版本、输入/结果 schema、编辑器键、能力就绪状态。
- `parseArchive()`、`validateImport()`：解析并校验自身格式；路径与压缩包边界继续受平台限制。
- `persistInputs()`：在平台保存样本之后、同一数据库事务内写入插件所需的结构化输入。
- `readInput()`：返回已授权范围内的输入；调用前平台验证 owner、assignment 或 Worker 租约。
- `validateResult()`：统一校验草稿与完成结果；`done=false` 不能被当成完成提交。
- `exportFormats()`、`export()`：编码所选冻结成果，平台负责权限、去重、ZIP、校验摘要、作业状态和下载。

项目权限、样本归属、任务领取、CAS 草稿版本、整包提交、审核、幂等和审计不属于插件可替换范围。插件必须保持校验和完成规则一致，不能自动确认 AI 或参考答案。

| 实现位置 | 作用 |
|---|---|
| 后端 `plugins/TaskPluginRegistry.java` | 版本索引、默认插件、能力目录和冻结契约检查 |
| 后端 `plugins/ImageClassificationPlugin.java` | 图片分类导入、读取、结果校验和 CSV 导出 |
| 后端 `plugins/EnergyStationDayPlugin.java`、`EnergyResultRules.java` | 站点日 CSV、结构化输入、时序结果及完成校验、JSONL 导出 |
| 后端 `plugins/BuiltInPlugins.java`、`NativeTaskPlugin.java` | 通用图像/时序的原生编辑器适配注册 |
| 前端 `src/services/labelingTemplates.mjs` | 可信插件描述、精确版本匹配及服务器能力联合检查 |
| 前端 `ServerWorkspace.tsx`、`assignment.tsx`、`TaskEditorHost.tsx` | 项目模板选择、领取工作区和编辑器装配 |

后端路径相对 `labeling-service/src/main/java/com/tss/labeling/`；前端页面路径相对 `src/pages/labeling/`。

## 注册和能力查询

后端通过 Spring 的 `TaskPlugin` Bean 集合构造 `TaskPluginRegistry`。重复 `ID@版本`、同任务类型多个默认版本、声明导出能力却未提供导出格式，会在启动阶段报错。

现有 `GET /api/labeling/v1/task-templates` 返回插件列表。每项包含：

```json
{
  "templateId": "energy-station-day",
  "templateVersion": "1.0.0",
  "name": "站点日能耗标注",
  "taskType": "TIMESERIES_ANNOTATION",
  "editorKey": "energy-station-day-editor",
  "inputFormat": "energy-station-day-input-v1",
  "resultFormat": "annotation-record-v1",
  "inputSchemaId": "energy-station-day-input@1.0.0",
  "resultSchemaId": "energy-station-day-result@1.0.0",
  "enabled": true,
  "creationAvailable": false,
  "defaultVersion": true,
  "capabilities": {
    "importer": true,
    "inputReader": true,
    "resultValidator": true,
    "completion": true,
    "exporter": true
  },
  "exportFormats": ["energy-jsonl-zip"]
}
```

实际响应还包含 `schemaDigest`。`enabled` 只有在插件启用且五项必要能力全部就绪时才为真。前端同时检查本地是否安装对应版本和编辑器，不认识的版本保留为不可选项，不能拿别的编辑器回退。

`creationAvailable` 独立表达当前创建界面是否开放该契约：四个通用数据类型在能力就绪时为 true；历史分类和能耗为 false，但其 `enabled` 仍可为 true，以继续编辑和导出已有任务。旧客户端明确指定原 ID/版本的创建请求继续兼容；目录返回六个已安装契约，创建界面展示四个通用类型。`GET /label-configs/task-types` 同样列出四类。记录插件新增 V28 成果审核来源冻结字段，不改历史 schemaDigest 或项目绑定。

前端可信描述在 `src/services/labelingTemplates.mjs`，组件映射在 `TaskEditorHost.tsx`，领取工作区按可信 `editorKey` 选择。服务返回的键不作为远程 URL 或动态 import 表达式。

## 项目与历史版本

- 创建项目提交明确的 `taskType`、`templateId`、`templateVersion`；省略插件字段的历史客户端沿用该任务类型的默认版本。
- V24 给 `project` 和 `import_batch` 增加插件版本绑定，并为已有四种任务回填 1.0.0。
- V24 同时将草稿表写死的三种结果格式约束改为非空、长度受限的格式标识；具体允许的格式及内容仍由冻结插件校验，不是允许任意 JSON 直接提交。
- 新规范继续冻结插件版本、输入/结果 schema ID 和契约摘要。轮次、assignment、草稿、提交和成果通过规范引用同一版本。
- 修改规范创建新版本，不覆盖旧规范。项目后续导入采用当前绑定；已经创建的导入批次始终用批次自己的版本处理。
- 已有数据项目升级到不兼容的输入 schema 时拒绝请求，返回 `PLUGIN_INPUT_MIGRATION_REQUIRED`，需要新项目或明确的数据迁移。
- 读取历史版本时只查精确 `ID@版本`。未安装版本返回 `TEMPLATE_UNAVAILABLE`；契约摘要或 schema 不一致返回 `TEMPLATE_MISMATCH`。
- 导出根据所选成果的冻结插件版本执行。多个插件版本的成果需要分别导出，不隐式使用最新编码器。

## 成果导出接口

以下路径均以 `/api/labeling/v1` 为前缀，权限仍由平台验证：

- `GET /final-snapshots/{snapshotId}/plugin-export-formats`：查询该冻结成果版本支持的插件导出格式。
- `GET /projects/{projectId}/exports?snapshotId=...&limit=20&cursor=...`：分页读取本项目导出作业，可按成果快照筛选。
- `POST /projects/{projectId}/exports`：提交 `finalSnapshotIds`、插件提供的 `format`、`includeOriginals: false`，并携带 `Idempotency-Key`。
- `GET /exports/{exportId}`、`GET /exports/{exportId}/download`：查询作业与下载成功生成的 ZIP。

图片分类使用 `classification-csv-zip`，站点日能耗使用 `energy-jsonl-zip`。现有原生 LS 成果继续沿用其源格式导出链路。

## 接入一个新插件

先检查是否能用现有图像/时序模板和输入映射完成；新增行业、标签集或阈值规则不直接等于新增插件。以下步骤仅用于确实需要扩展通用能力的情况。

1. 在后端增加一个 `TaskPlugin` 实现，声明完整描述与能力状态；以 `@Component` 或 `@Bean` 注册。尚未具备的能力应明确为 false。
2. 实现自己的解析、输入、结果/完成校验和导出；不要增加到平台服务里的任务类型 `if/switch`。
3. 在前端可信描述表登记相同版本，登记组件与工作区；组件只通过平台接口读写草稿，不自行保存身份或提交整包。
4. 增加格式转换、越权、版本不匹配、未就绪、重复注册及完整流程测试，再一同发布前后端。
5. 升级插件新增版本描述，并保留仍被历史规范引用的旧实现。不要直接修改旧版本的 schema 含义。

新插件最少交付：插件 ID/版本、输入和结果 schema、导入正反例、可信编辑器组件、服务端结果及完成规则、导出格式和完整流程测试。第一版的接口是 `TaskPlugin`，不需要另行实现一套尚不存在的 `ImportAdapter` 或 `TaskValidator` 注册系统。

## 部署和升级

前后端需要配套发布：只有后端目录存在而前端未安装对应编辑器时，该模板不可选择；只有前端描述而后端未登记时，也不能创建项目。

后端新增迁移为 `V24__pin_project_and_import_plugins.sql`。按现有发布流程备份并由 Flyway 升级，保留 V1—V23 原脚本及校验和。V14 → V24 的本地升级测试通过，不表示目标服务器已升级或完成恢复演练。已有任务依赖的插件旧版本需要随发布包保留。

部署后检查模板接口、创建项目返回的版本、历史项目读取，以及图片/能耗各一条保存提交和导出流程。详细发布步骤见 [RELEASE.md](../../labeling-service/RELEASE.md)。

## 验证

`TaskPluginRegistryTest` 验证重复注册、能力就绪、冻结版本及旧摘要兼容；`MigrationUpgradeTest` 验证 V14 → V24 后旧结果、草稿与成果仍保留；`ImportIntegrationTest` 包含图片与能耗的导入、授权、保存、提交、核查、导出以及在途批次版本固定。

前端包含注册表、服务器能力与本地版本联合判断、创建项目发送精确版本、历史项目流程和插件成果导出测试。

真实数据测试通过 `-Dlabeling.energy.dataset=目录` 启用，读取目录中的 `manifest.json` 和 `sample/station_day_tasks_2k.csv`：全量验证 2,000 条样本导入及按每包 10 条发布 200 个任务包；首包验证保存、提交、核查及 JSONL 导出。测试使用临时本地数据库，不写入部署环境。它不替代浏览器操作、专家标签语义或真实 AI 模型质量验收。

最新测试数量、真实样例结果、迁移验证和跳过/阻塞项统一维护在 [STATUS.md](09-status.md)，不在本指南重复维护。对应历史阶段记录见 [归档索引](archive/README.md)；不能把原生编辑器可选测试跳过或历史版本通过记作当前全套通过。

从后端仓库根目录执行能耗数据测试，目录参数指向包含 `manifest.json` 的数据集目录：

```bash
mvn -o -f labeling-service/pom.xml \
  -Dlabeling.energy.dataset='/绝对路径/energy_kwh_anonymized_20260901-20261013_v1.0.0' test
```

`-o` 使用本机 Maven 缓存，依赖未缓存时会失败。原生编辑器测试还需完整的 Python 依赖环境，并通过 `-Dls.test.python=/绝对路径/python` 显式启用；不应把跳过记录算成通过。测试记录是上述工作区的本次结果，后续代码变更后应重新验证。

## 2026-11-25 记录插件扩展

结构化、半结构化的格式、校验、结果契约、样例及配套发布步骤详见 [10-record-data-plugins.md](10-record-data-plugins.md)。
