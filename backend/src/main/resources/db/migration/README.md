# Flyway 迁移脚本说明

本目录存放模块二后端数据库的 Flyway 版本化迁移脚本。文件名形如
`V版本号__说明.sql`，Flyway 会按版本号顺序执行，并在数据库中记录每个脚本的
checksum。

注意：如果某个 `V*.sql` 已经在任一环境执行过，不建议再直接修改该 SQL 文件，
即使只是补注释也会改变 checksum，后续应用启动时可能触发 Flyway 校验失败。
需要补充说明时，优先维护本文档；确实要修改已执行脚本时，需要同步处理
Flyway repair 或重新基线化。

## 当前迁移总览

当前已使用的最高迁移版本为 `V60`。V1-V30 是本分支已有的连续基础链路；
服务器分支已使用 V31-V40（其中 V35 当前无迁移文件），V41 在该服务器迁移链之后
补充模型版本完整性与生命周期结构，V42 增加训练代码审核模式配置及其显式审批证据。
合并后的全部已用迁移均应保留。

| 版本 | 主要职责 | 当前定位 |
| --- | --- | --- |
| V1 | 模块二核心表 | 基础迁移 |
| V2 | 外键、唯一约束和值域约束 | 数据完整性 |
| V3 | 训练结果回调字段 | 训练模块结构 |
| V4 | 软删除和 MinIO 删除任务 | 通用基础能力 |
| V5 | 扩展点云和机器人任务类型 | 类型约束演进 |
| V6 | 训练实验 MLflow `run_id` | 训练模块结构 |
| V7 | 数据集 READY/DRAFT 版本体系 | 数据集版本生命周期 |
| V8 | 多模态样本、数据、标注和导入任务 | 多模态基础结构 |
| V9 | 导入恢复和清理查询索引 | 性能与恢复能力 |
| V10 | 数据包及版本包关联 | append 和来源追踪 |
| V11 | 初始上传与 append 上传用途 | append 上传流程 |
| V12 | `AUTO_DIRECTORY` 分组模式 | 自动生成导入计划 |
| V13 | 代码资产和训练方案字段 | profile 训练代码管理 |
| V14 | 代码版本准入状态 | 训练代码准入 |
| V15 | 训练实验 MLflow experiment 跟踪 | 训练实验追踪 |
| V16 | V2 ImportJob 错误字段 | V2 导入错误展示 |
| V17 | V2 模型上传会话字段 | V2 模型上传 |
| V18 | 单资产 active DRAFT 唯一约束 | 数据集版本一致性 |
| V19 | 模型版本生命周期字段 | 模型版本管理 |
| V20 | 推理模块结构 | 推理脚本和任务 |
| V21 | 数据集版本文件数和目录索引 | 数据集列表性能 |
| V22 | 后台调度锁 | 多实例定时任务互斥 |
| V23 | MinIO 删除任务失败重置计数 | 删除任务恢复 |
| V24 | DRAFT 资产 ID 唯一索引 | DRAFT 约束补强 |
| V25 | 上传会话 strictManifest 字段 | 多模态 MANIFEST 严格模式 |
| V26 | dataset workspace 审计日志 | 工作区内部回溯 |
| V27 | ImportJob PARTIAL 和失败样本表 | 增量 retry |
| V28 | 上传会话 DISCARDED 状态 | 草稿放弃与清理 |
| V29 | 代码工作区、校验、审批和审计结构 | 代码资产管理基础 |
| V30 | 代码风险评估、自动决策证据和待审核索引 | 代码风险分流基础 |
| V31 | 训练产出模型结构（服务器分支已有） | 训练结果模型入库 |
| V32 | 训练计划运行规格快照（服务器分支已有） | 训练输入与运行证据 |
| V33 | 训练输出证据（服务器分支已有） | 训练结果证据 |
| V34 | 训练模式（服务器分支已有） | 训练任务配置 |
| V35 | 当前无迁移文件 | 版本号空缺 |
| V36 | 计算服务器（服务器分支已有） | 算力资源管理 |
| V37 | 服务器指标快照（服务器分支已有） | 算力监控 |
| V38 | 任务队列字段（服务器分支已有） | 训练调度 |
| V39 | 服务器指标历史（服务器分支已有） | 算力监控历史 |
| V40 | 计算服务器容量（服务器分支已有） | 算力容量管理 |
| V41 | 模型当前版本、制品摘要和提交/超参数元数据 | 模型版本完整性与生命周期 |
| V42 | 训练代码审核模式和系统直通证据 | 代码资产准入配置 |
| V43 | 数据集版本工作区 | 数据集可变草稿与发布 |
| V44 | 资产名称与模型复验标记 | 名称唯一性和模型消费准入 |
| V45 | 管理员训练代码管理审计 | 跨 owner 管理操作审计 |
| V56 | Kubernetes Pod/Job 配额与 Job TTL | 超级管理员资源策略 |
| V57 | 在线训练方案单表、不可变版本与单活动版本约束 | 训练方案 YAML 管理 |
| V58 | 资产规范证据 | 训练候选准入证据 |
| V59 | 增加 OTHER 资产类型 | 未分类目录 |
| V60 | 演示资产标记 `is_demo` | 全局共享只读资产 |

其中服务器迁移链从 `V31__training_produced_model.sql` 开始，V31-V40 已存在于
待合并的服务器分支。服务器的
`V32__training_plan_run_spec_snapshot.sql` 已创建 `model_version.artifact_sha256`
及同名格式约束，因此本次迁移使用 V41，并兼容复用服务器 V32 已有的列与约束。

部分旧约束会被后续迁移替换，但这不表示旧迁移可以删除：

- V5 扩展 V2 定义的任务类型约束。
- V8 再次扩展数据集相关类型约束，加入 `MULTIMODAL`；V5 对
  `model_asset.type` 的扩展仍然有效。
- V12 将 V8 中仅允许 `MANIFEST` 的 `sample_grouping` 约束扩展为同时允许
  `MANIFEST` 和 `AUTO_DIRECTORY`。

这些脚本已经形成有序迁移历史。对于可能已执行过迁移的数据库，不得删除、重命名、
调整版本号或修改已有 SQL 内容。需要变更数据库结构时，应新增下一个版本的迁移脚本。

## V1__module2_core_schema.sql

创建模块二核心业务表和基础索引。

- `model_asset`：模型资产表，保存模型名称、任务类型、备注、归属用户、创建/更新时间。
- `model_version`：模型版本表，保存版本号、原始文件名、MinIO 存储路径、文件大小、归属用户。
- `model_upload_session`：模型分片上传会话表，保存文件指纹、文件名、分片大小、总分片数、上传状态等。
- `model_upload_chunk`：模型分片记录表，保存每个分片在 MinIO 中的临时对象路径、大小、etag。
- `dataset_asset`：数据集资产表，保存数据集名称、数据集类型、CV 子任务、标注格式、备注、归属用户。
- `dataset_version`：数据集版本表，保存数据集版本号、原始文件名、MinIO 存储路径、大小、CV 元数据等。
- `dataset_upload_session`：数据集分片上传会话表，保存数据集名称、版本、任务类型、上传状态等。
- `dataset_upload_chunk`：数据集分片记录表，保存每个数据集分片的临时对象路径和大小。
- `training_experiment_version`：训练实验版本表，保存实验版本、模型版本、代码版本、数据集版本、超参数和状态。
- 创建上传指纹、上传状态、分片会话、训练实验 ID 等常用查询索引。

## V2__module2_constraints.sql

为 V1 创建的表补充唯一约束、外键约束和值域校验。

- 增加唯一索引，例如同一个模型/数据集资产下版本号唯一，上传分片 `(upload_id, part_index)` 唯一。
- 增加模型、数据集、上传会话、训练实验之间的外键关系。
- 限制模型资产和数据集资产的 `type` 取值。
- 限制 CV 子任务 `cv_task_type` 的允许值。
- 限制标注格式 `annotation_format` 的允许值。
- 限制模型/数据集上传会话状态只能是 `UPLOADING`、`COMPLETING`、`COMPLETED`。
- 限制训练实验状态只能是 `pending`、`queued`、`running`、`success`、`failed`、`stopped`。

## V3__training_result_callback_fields.sql

为训练实验版本表增加训练结果回写相关字段。

- 增加 `progress`：训练进度。
- 增加 `metrics_json`：训练指标 JSON。
- 增加 `log_path`：训练日志路径。
- 增加 `output_path`：训练输出路径。
- 增加 `error_message`：失败原因。
- 增加 `started_at`、`finished_at`：训练开始和结束时间。
- 对历史数据回填 `progress`：`success` 为 `100`，`running` 为 `50`，其他状态为 `0`。
- 增加 `progress` 校验，确保取值为空或位于 `0` 到 `100`。

## V4__soft_delete_and_minio_delete_task.sql

增加软删除能力和 MinIO 对象异步删除任务表。

- 为 `model_asset`、`model_version`、`dataset_asset`、`dataset_version` 增加 `deleted` 和 `deleted_at`。
- 为上述软删除字段增加索引，用于列表和详情接口过滤已删除数据。
- 创建 `minio_delete_task` 表，用来记录待删除、处理中、成功、失败的 MinIO 对象删除任务。
- 为删除任务的状态、对象路径、来源业务增加索引。
- 限制删除任务状态只能是 `PENDING`、`PROCESSING`、`SUCCESS`、`FAILED`。
- 限制重试次数不能为负，最大重试次数必须大于 0，且当前重试次数不能超过最大重试次数。

## V5__extend_task_types_point_cloud.sql

扩展任务类型约束，支持点云和机器人类型。

- 删除并重建 `model_asset.type` 的 check 约束，使模型资产类型允许 `CV`、`NLP`、`POINT_CLOUD`、`ROBOT`。
- 删除并重建 `dataset_asset.type` 的 check 约束，使数据集资产类型允许同样四种类型。
- 删除并重建 `dataset_upload_session.task_type` 的 check 约束，使数据集上传会话允许同样四种类型。
- 该迁移用于配合 Java 侧 `TaskType` 枚举扩展；否则后端即使允许 `POINT_CLOUD`，数据库仍会因为旧约束拒绝写入。

## V7__dataset_version_enterprise_versioning.sql

数据集版本管理企业化改造。
- 为 `dataset_version` 增加 `version_no`、`version_label`、`description`、`change_log`、`parent_version_id`、`status`、`file_fingerprint`、`published_at`、`created_by`。
- 为 `dataset_asset` 增加 `current_version_id`，用于标记当前推荐版本。
- 为历史数据按 `asset_id` 和创建时间回填连续 `version_no`，并将旧 `version` 回填为 `version_label`。
- 将历史版本状态回填为 `READY`，并将每个资产当前版本指向未删除且 `version_no` 最大的版本。
- 增加 `(asset_id, version_no)` 唯一索引、版本状态约束、父版本外键和当前版本外键。
- 为 `dataset_upload_session` 增加版本说明和父版本字段，支持上传过程中保留版本元数据。

## V21__dataset_version_file_count_and_catalog_indexes.sql

为数据集版本增加持久化文件数，并补充数据集列表查询索引。

- 为 `dataset_version` 增加 nullable `file_count`，旧版本可在首次列表命中时懒计算回填。
- 为 `dataset_asset` 增加 owner/type/deleted/time 维度列表索引，支持普通用户数据集列表分页。
- 为 `dataset_asset` 增加 admin/type/deleted/time 维度列表索引，支持管理员数据集列表分页。

## V25__dataset_upload_session_strict_manifest.sql

为 `dataset_upload_session` 增加 `strict_manifest`，默认 `false`。

- 仅 `MULTIMODAL + MANIFEST` 上传会话可保存为 `true`。
- `true` 时 ImportJob 会把 manifest 未声明的普通 ZIP entry 判定为结构化失败。
- `false` 保持既有兼容行为，未声明 entry 仍只进入 warnings。

## V26__dataset_workspace_audit_log.sql

为 dataset workspace 增加 append-only 审计日志表。

- 创建 `dataset_workspace_audit_log`。
- 记录关键操作、actor、目标对象、ImportJob/package/sample 关联和安全 `details`。
- 不记录 `storagePath`、bucket、MinIO objectName 或 ZIP offset。
- 审计日志仅用于模块二内部回溯，不参与 READY/DRAFT、publish、retry 或清理状态判断。

## V27__import_job_partial_retry.sql

为 ImportJob 增加 PARTIAL 状态和增量 retry 所需的失败样本明细。

- 重新创建 `ck_import_job_status`，允许 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`PARTIAL`、`SUPERSEDED`；其中 `SUPERSEDED` 继续保留在数据库约束内。
- 创建 `import_job_sample_failure`，记录失败样本的 `external_id`、原始 `sample_index`、失败状态、错误码、错误信息、重试次数和时间字段。
- failure row 状态限定为 `FAILED`、`RETRYING`、`RESOLVED`。
- 增量 retry 只依赖 failure row 中保存的 `external_id` 和 `sample_index`，避免重新解析顺序导致样本序号漂移。

## V28__dataset_upload_session_discarded_status.sql

扩展数据集上传会话状态约束，允许工作区放弃流程持久化 `DISCARDED`。

- 保留 `UPLOADING`、`COMPLETING`、`COMPLETED` 三个既有状态。
- 新增 `DISCARDED`，用于草稿放弃后阻止会话继续上传，同时保留会话审计链。

## V29__code_asset_workspace_validation_approval.sql

建立代码资产工作区、文件增量、校验、审批和审计持久化基础。

- 为 `code_asset` 增加用途、运行时、入口脚本、训练类型和乐观锁版本字段。
- 为 `code_version` 增加制品哈希、校验状态、策略版本和生命周期时间字段，并补充资产外键及状态约束。
- 创建 `code_workspace` 和 `code_workspace_file_delta`，通过部分唯一索引保证每个资产最多一个未删除的 OPEN 工作区。
- 创建 `code_validation_run`、`code_approval_record` 和 `code_asset_audit_log`，并通过 PostgreSQL trigger 拒绝审计日志 UPDATE/DELETE。
- 将历史 APPROVED 状态保存为 `LEGACY_APPROVAL_IMPORTED` 记录后重置为 PENDING，要求按新流程重新校验和审批。

## V30__code_risk_assessment_and_review_queue.sql

建立代码静态风险评估、自动策略决策和管理员待审核队列所需的持久化基础。

- 创建 `code_risk_assessment`，将扫描结果绑定到代码版本、校验记录、制品 SHA-256、风险策略和扫描器版本。
- 创建 `code_risk_finding`，只保存规则、严重度、分类、文件位置和安全描述，不保存源码片段、凭据、存储路径或下载地址。
- 为 `code_approval_record` 增加 `AUTO_POLICY`、`ADMIN`、`LEGACY` 决策来源，以及风险评估和审批策略证据；系统策略可以在没有用户 reviewer 的情况下自动批准或阻断。
- 为 `code_version` 增加最新风险评估摘要，并增加 PENDING 管理员审核队列索引。
- 为代码资产审计日志增加 `USER`、`SYSTEM` actor 类型；SYSTEM actor 不伪造用户 ID，USER actor 仍必须绑定真实用户。
- 历史审批记录保持原决定，仅标记为 `LEGACY` 来源，不自动生成风险结果或自动批准历史 PENDING 版本。

## V41__model_version_integrity_and_current.sql

补齐模型版本的当前指针、制品完整性证据和业务元数据。

- 为 `model_asset` 增加 `current_version_id`、外键和索引，并为每个资产回填最新的有效 `READY` 版本。
- 为 `model_version` 增加 `artifact_sha256`、`commit_info` 和 `hyper_params`；历史摘要保持可空，由首次消费校验原子回填。
- 为 `model_upload_session` 增加 `commit_info` 和 `hyper_params`，使断点续传和幂等恢复能够核对完整业务元数据。
- 将缺少存储路径、文件名或正数文件大小的历史 `READY` 版本降为 `DRAFT`。
- 增加 `READY` 制品元数据约束、SHA-256 小写十六进制格式约束，并限制模型版本及上传会话的 `hyper_params` 必须为 JSON 对象。

## V43__training_code_review_mode.sql

增加面向前端管理页的训练代码审核模式，并为无需人工审核的直通模式保留可追踪证据。

- 创建单例 `platform_system_config`，默认 `training_code_review_mode=STANDARD_REVIEW`；V63 起允许 `DIRECT_PASS`、`STANDARD_REVIEW` 或 `MANUAL_ONLY`。
- `STANDARD_REVIEW` 保持现有 `CODE_ASSET_RISK_MODE` 风险扫描、自动策略和管理员审核流程。
- `DIRECT_PASS` 仍要求 ZIP 结构、固定入口、实际对象名、SHA-256 和长度校验通过；只跳过静态风险扫描及人工审核。
- `MANUAL_ONLY` 保留不可变基础校验，但新版本不触发自动扫描判定，统一进入管理员人工待审；管理员仍可显式重新扫描获取辅助证据。
- 扩展风险分流约束以支持 `DIRECT_PASS`，并扩展审批来源约束以支持 `SYSTEM_CONFIG`。
- 系统直通批准必须绑定 validation run、制品 SHA、显式 direct-pass risk assessment 和固定审批策略版本，不能伪造管理员 reviewer。

## V44__dataset_version_workspace.sql

将数据集 DRAFT 完整化为带显式并发控制的版本工作区，并补齐 RAW 文件覆盖能力。

- 为 `dataset_version` 增加 `workspace_revision`、`updated_at`，并允许工作区以 `ABANDONED` 终态释放活动 DRAFT。
- 为 `dataset_package` 增加 `storage_kind=ZIP|RAW`，为版本包关系增加 `OVERLAY`。
- 为数据、标注组件增加软删除和更新时间字段，并将数据组件唯一约束改为仅约束未删除记录。
- 扩展 `dataset_upload_session`，记录工作区、目标资源、`CREATE|REPLACE` 操作、初始化 revision 以及创建资源需要的描述字段。
- 增加 `WORKSPACE_FILE` 上传 purpose，支持数据/标注组件的大文件创建或替换；分片接口保持复用，完成和取消通过工作区 revision CAS。

## V45__asset_name_and_model_attestation.sql

统一模型/数据集资产名称约束，并增加模型 READY 消费复验标记。

- 为模型、数据集资产增加 `normalized_name`，由 trigger 按去除首尾空白并转小写维护。
- 为两类资产增加新写入非空白约束，以及 owner 范围内、仅针对未删除资产的规范化名称
  唯一索引；数据库唯一索引负责串行化快速重复提交。
- 历史重名数据不被迁移强制删除或改名：每组保留一个确定性代表占用规范化名称，其余
  历史行保持可读，后续改名或重新启用时必须满足新规则。
- 为 `model_version` 增加最近一次完整结构复验的 SHA-256 与时间字段，供 READY 训练、
  推理和 V2 清单/文件消费记录准入证据；单次读取下载只校验并按需回填主 SHA 字段，
  不把字节流校验冒充为 ZIP 结构复验。

## V46__admin_code_asset_management.sql

为显式 `/api/v2/admin/code-*` 跨 owner 管理补充可区分的审计身份。

- 扩展 `code_asset_audit_log.actor_type` 约束，允许 `ADMIN` 与真实
  `actor_user_id` 组合。
- `USER` 和 `ADMIN` actor 均必须绑定用户 ID；`SYSTEM` actor 仍必须保持用户 ID
  为空。
- 不改变审计表 append-only trigger，也不修改代码资产、工作区、版本或对象存储的
  owner。

## V52__code_asset_names_and_abandoned_workspace_tombstones.sql

补齐代码资产名称唯一性，并释放已放弃数据集工作区占用的版本号和版本标签。

- 为代码资产增加 `normalized_name`、非空白约束、维护 trigger 及 owner 范围内的部分唯一索引。
- 保留历史重名代码资产；每组仅由一个确定性代表占用规范化名称，新写入必须满足唯一性。
- 将 `ABANDONED` 数据集版本转为审计墓碑，并允许其版本号和标签被后续正式版本复用。

## V53__dataset_upload_completion_failure.sql

为首次数据集上传增加可重试的完成失败状态。

- `dataset_upload_session` 增加 `FAILED`，并增加稳定错误码、用户消息、安全 JSON 详情和
  失败时间字段。
- 约束 `FAILED` 必须具有完整错误记录，其他状态不得遗留失败字段；重试抢占必须在同一
  SQL 更新中清空旧错误。
- 不删除失败会话的分片；工作区组件和 APPEND 上传继续使用原有生命周期。

## V57__online_training_plan_definition.sql

- 新增 `training_plan_definition` 单表，只保存在线发布的 v2 方案；classpath 内置 v1
  方案继续只读，不迁移进数据库。
- `(plan_id, plan_version)` 唯一，原始 YAML 和精确字节 SHA-256 发布后不可原地修改。
- PostgreSQL 部分唯一索引保证同一 `plan_id` 最多一条 `ACTIVE` 记录。
- 状态约束要求停用操作同时保存操作者和时间；活动记录不得残留停用证据。
- 数据库再次限制 v2 Schema、256 KiB UTF-8 YAML 存储上限和 SHA-256 格式；完整
  YAML 语义、安全策略和资产规范仍由 I-1 同源校验器负责。

## V58__artifact_spec_evidence.sql

- 为模型/数据集版本和各自上传会话增加可空的服务端资产规范证据。
- 历史数据不回填；无法从真实内容唯一证明的资产保持 NULL。
- 增加规范 ID 格式约束，以及 READY 版本按 owner/spec 查询的部分索引。

## V59__add_other_asset_category.sql

- 仅重建模型资产、数据集资产和数据集上传会话的既有类型 CHECK，并增加 OTHER。
- 不增加或删除字段，不修改历史数据；现有 CV/NLP/点云/机器人/LeRobot/多模态值保持有效。
- OTHER 只是未分类目录，不放宽上传安全校验，不自动产生 `artifact_spec_id` 或训练资格。

## V60__add_is_demo.sql

- 为 `dataset_asset`、`model_asset`、`code_asset`、`inference_script_asset` 四张资产表增加
  `is_demo BOOLEAN NOT NULL DEFAULT FALSE`。
- 演示资产由超级管理员通过 `PUT /api/v2/admin/demo-assets/{type}/{assetId}` 标记；标记时
  资产及其所有版本的 `owner_user_id` 迁移到系统账户 `0`，对所有用户可见、只读。
- 版本表不增加 `is_demo`，靠 `owner_user_id = 0` 达成查询与访问放行。

## 维护规则

- 已执行的版本化迁移只读维护，不修改文件内容或 checksum。
- 新增迁移前先检查当前最大版本，并使用下一个可用版本号。
- 不通过删除旧迁移来“清理”被后续迁移替换的约束。
- 如果需要为全新部署压缩历史，应单独设计 baseline，并明确区分已有数据库和全新
  数据库；不能直接替换现有迁移链路。
- 应用使用 Hibernate `validate` 时，实体字段变化必须先有对应 Flyway 迁移。
