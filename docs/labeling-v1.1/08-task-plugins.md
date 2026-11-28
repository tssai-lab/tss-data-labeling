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

## 标注员提供方与结果集成策略

任务类型描述数据及标注结果契约；标注员提供方描述谁以何种执行方式产出结果；集成策略描述如何读取多个结果并生成可审核的建议。这三者是独立扩展点，新增模型、人工团队或投票算法不应因此复制一份数据插件。

### 执行能力契约注册

任务插件通过 `TaskCapabilityRegistry` 声明稳定的执行能力契约：`data.import`、`data.read`、`result.validate`、`result.completion` 和 `result.export`。每项包含契约版本、是否为创建项目所必需、当前就绪状态；旧版 `capabilities` 布尔对象继续返回，供旧前端兼容。插件目录将能力清单与模板、编辑器和导出格式一起返回。必需能力未就绪时，目录将模板标为不可创建，并列出缺失能力；历史读取仍按冻结的模板版本解析。

新增数据类型仍实现平台可信的 `TaskPlugin` 接口，并在前后端注册同一 `templateId@version`。接口承载实际导入、读取、校验、完成判断和导出处理；能力清单说明哪些处理可用，不会从声明自动生成执行代码。增加新的能力类别时，应先定义稳定 ID、版本和调用契约，再由插件提供实现并注册，不能只在 UI 声明为就绪。

当前内置提供方如下：

| 提供方 ID@版本 | 标注员类型 | 执行模式 | 说明 |
|---|---|---|---|
| `human-pool@1.0.0` | `HUMAN` | `HUMAN_POOL` | 人工领取任务包、保存草稿并提交 |
| `ai-agent-worker@1.0.0` | `MODEL` | `AI_AGENT` | Worker 执行；具体 Agent、模型版本和能力由 Agent 配置决定 |

后端通过 `AnnotatorProvider` Spring Bean 注册，并由 `AnnotatorProviderRegistry` 按提供方版本索引、按执行模式分派。注册表拒绝重复的提供方版本和重复执行模式。`GET /api/labeling/v1/annotator-providers` 返回已安装的提供方描述；Agent 列表还返回其 `providerId`、`providerVersion`、`annotatorType` 和能力声明。发布前可选择纯人工、纯 AI 或人工 + AI，并分别设置每样本人工人数和本轮最多 10 个 Agent。确认发布时，在同一轮创建人工席位和 AI 执行单元；共用 `round_item` 冻结范围及样本占用。只有人工席位与所有 AI 执行都结束后才释放占用。已关闭的混合轮次不能重新打开；取消混合轮次前需先结束 AI 执行。

人工提交和 AI 执行会冻结 `annotatorProviderId`、`annotatorProviderVersion`、`annotatorType`；AI 来源另带 Agent 身份与模型版本。结果候选查询、成果快照和插件导出均保留这些来源字段。V30 为历史人工和 AI 来源回填各自的内置提供方版本；历史成果快照按其原始提交或执行来源回填，不改答案内容。

结果集成策略通过 `ResultAggregationStrategy` Spring Bean 注册。策略需声明稳定且带版本的 `algorithmId`、适用任务类型、结果格式、最低独立来源数和可接受的来源类型，并返回建议结果及可追溯证据。`ResultAggregationRegistry` 校验唯一 ID；创建集成时会根据冻结规范的任务类型和结果格式精确解析策略。平台仍负责检查结果是否属于该样本和规范、来源参与者是否重复、修订/集成结果是否被误当作原始票数，并保存建议与证据。

当前 `majority-vote-v1` 是 `IMAGE_CLASSIFICATION` / `image-classification-v1` 的严格多数策略。算法目录 `GET /api/labeling/v1/aggregation-algorithms?taskType=IMAGE_CLASSIFICATION` 从注册表返回策略描述和参数 Schema；省略 `taskType` 时返回全部已安装策略。每次运行将经过策略自身校验的参数冻结到集成作业。增加通用图像、时序或其他策略时，应按结果契约新增实现并注册，不要在 `AggregationService` 追加任务类型分支。策略只生成待审核建议，不自动接受结果。现有数据库存储的算法 ID 不变。

前端根据当前规范的任务类型读取策略目录，展示策略的最低独立来源数和说明；候选结果按通用 JSON 呈现。新增任务插件的集成策略注册后即可被工作台发现，不需要在工作台增加任务类型分支。

这些注册表是随服务发布的可信代码，不支持用户上传代码运行。将来配置人工团队、设置来源权重或自定义模型重试策略时，仍需继续扩展相应的轮次、答案或租约语义。

## 扩展点现状与边界

扩展点优先解决“新增一种受支持能力时，不改平台公共生命周期”；它们不承诺业务人员无需开发即可接入任意格式、任意流程或任意模型。

| 领域 | 当前实现 | 增加能力时的做法与边界 |
|---|---|---|
| 前后端插件契约 | 后端模板目录返回模板版本、输入/结果 Schema、编辑器键、能力状态和导出格式；前端运行时只解析本地可信注册项，并在不匹配时禁用模板。 | 新增插件仍要同时登记前后端并随版本发布。当前门禁是运行时目录匹配；尚无跨两个独立仓库的 CI 构建检查。不得用远程组件地址或“找不到就回退到默认编辑器”。 |
| 导入映射 | 插件负责安全解析；记录插件支持字段映射及 Schema 校验，原生 CSV 映射支持行记录和时序分组。 | 列名别名、字段映射、分组和约束适合配置；新文件语法、不同样本切分语义或不兼容输入结构仍由可信解析器代码处理。当前没有任意可执行转换脚本，也不应把用户表达式当代码运行。 |
| 工作流阶段 | 发布计划冻结标注员清单、分配策略和 `workflowSnapshot`；当前内置 `standard-annotation@1.0.0` 固定“标注 → 需求方审核 → 成果交付”，标注阶段可并行包含人工与多个 AI。 | 新的串行阶段、专家角色和分支条件需要增加经过校验的工作流版本及状态迁移实现；不能只改 JSON 就宣称平台支持新流程。旧计划继续按已冻结快照解释。 |
| 结果集成 | `ResultAggregationStrategy` 按算法 ID、任务类型、结果格式和来源类型注册，参数经策略校验后冻结；集成只生成待审核建议。 | 为框、字段、文本或时序新增专用策略，并提供对齐/冲突证据；不能把分类多数票泛化套用到所有结果。 |
| 用量记录 | `usage_event` 记录人工提交、AI 执行和成果下载等来源事件，具有幂等来源键；当前不计算费用。 | 用量事件是未来计费的数据基础，不表示当前采用按量计费。价格、套餐、席位、额度和结算应作为独立模块，待商业规则确定后再做。 |
| AI Worker | Agent 声明任务能力；执行统一使用领取、租约、心跳、幂等终结和失败子集重试。租约时长通过 `labeling.ai.worker-lease-seconds` 配置，默认 120 秒，允许 10–3600 秒。 | 单 Agent 的输入/输出和错误码保持统一；模型适配器负责模型专属协议。当前未提供任意限流/自动重试策略编排；如需要，先定义重试条件、上限与计量语义，再扩展版本化策略。 |

插件契约检查是前后端配套发布的运行时门禁，不是远程插件加载：本地注册表负责确认编辑器已安装，服务端目录负责确认能力可用，模板绑定则固定精确版本和 Schema。任一项不匹配都应停止创建或编辑，并显示明确原因。由于前后端在独立 Git 仓库，跨仓库 CI 检查需要统一流水线或额外的契约制品；当前不把它描述成已经完成。

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
| 后端 `annotators/AnnotatorProviderRegistry.java` | 人工任务池与 AI Worker 执行提供方目录 |
| 后端 `review/ResultAggregationRegistry.java` | 按稳定算法 ID 注册结果集成策略 |
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

后端迁移包含 `V24__pin_project_and_import_plugins.sql` 至 `V35__freeze_execution_policy_snapshots.sql`。V28 冻结成果审核来源，V29 支持同一发布组并行发布。发布计划冻结提供方 ID/版本/数量、当前支持的开放领取规则及工作流阶段快照；旧执行模式字段继续供旧客户端与历史计划读取。V35 同时为集成作业保存经过算法校验的参数。V34 只追加人工提交、AI 执行结果和成果下载的用量事实事件，不计算费用，也不预设未来采用按量、订阅、套餐、席位或组合计费。价格与合同权益应作为独立能力设计。按现有发布流程备份并由 Flyway 升级，保留 V1—V23 原脚本及校验和。V14 → V35 的迁移升级测试需覆盖既有答案与成果快照；本地编译不表示目标服务器已升级或完成恢复演练。已有任务依赖的插件旧版本需要随发布包保留。

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


## 图片格式扩展

图片分类和 COMPUTER_VISION 共用 `ImageFormats` 与导入层，支持 JPG/JPEG、PNG、TIFF/TIF、静态 WebP、BMP，不按扩展名单独注册任务插件。新格式使用原文件摘要和原始 MIME 类型保存输入，通过授权文件接口提供同尺寸 PNG。TIFF 多页标识、预览元数据、导出关联及限制统一见 [接口契约](04-api.md) 第 3 节。

解码依赖 `com.twelvemonkeys.imageio:imageio-webp` 和 `imageio-tiff` 3.15.0，通过 Maven 打入 Spring Boot 可执行 JAR，不需要服务器额外安装本地解码器。数据库新增 V36 扩展图片 MIME 约束；历史迁移保持不变。
