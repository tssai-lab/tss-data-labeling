# 自定义训练方案 YAML 使用手册

适用版本：`tss.training.plan/v2`  
适用角色：平台超级管理员  
当前设备范围：CPU、单张 NVIDIA GPU
手册版本：2026-08-27

> 本手册中的“必须”来自当前后端校验器；“建议”用于提高可维护性，不会伪装成强制规则。字段发生变化时，以平台下载的同版本模板和预览结果为准。

## 1. 先理解三个独立功能

1. **模型上传**只负责保存和校验模型资产。
2. **数据集上传**只负责保存和校验数据资产。
3. **训练方案 YAML**在发起训练时规定：可以选择哪些模型/数据集规范、运行什么训练代码、使用什么资源、收集什么输出。

上传模型或数据集时不绑定某个训练方案。一个资产可以先入库，等以后有兼容方案时再训练。

## 2. 当前能力边界

- 在线方案只接受 `tss.training.plan/v2`。
- CPU runtime 的 `gpuCount` 必须为 `0`；NVIDIA GPU runtime 当前只能填写 `1`，多卡和分布式训练尚未开放。
- 当前可下载并通过同源校验器的模板只有 `cv-cpu-v2`。
- 当前 CV 模板接受：
  - 模型规范 `model.cv.hf-image/v1`
  - 数据集规范 `dataset.cv.imagefolder/v1`
- NLP 模型规范尚未达到 `TRAINING_READY`，因此暂不提供会被后端拒绝的 NLP 模板。
- YAML 不能上传脚本、注册新镜像仓库、关闭代码审核、申请特权容器或绕过平台安全策略。

## 3. 使用前检查

开始前同时满足以下条件：

1. 使用超级管理员账号；普通管理员和普通用户无发布权限。
2. 需要使用的模型、数据集和训练代码已经上传；训练代码已经审核通过。
3. CPU 计算节点带有 `tss.ai/node-pool=cpu` 标签；GPU 计算节点额外带有
   `tss.ai/accelerator=nvidia` 标签，并已安装平台锁定的 RuntimeClass 和
   NVIDIA Device Plugin。
4. `tss-training-worker` ServiceAccount 已配置私有镜像 `imagePullSecrets`，或模板中的不可变镜像已存在于目标节点。
5. 节点磁盘、内存和 CPU 余量满足模板中的 resource profile。

第 4 项很重要：镜像曾经在节点上运行成功，不代表磁盘清理后还能从私有仓库重新拉取。

## 4. 五分钟快速开始

1. 打开“系统管理 → 训练方案管理”。
2. 下载“CV 图像分类 / CPU”模板。
3. 至少修改：
   - `id`：新的方案 ID；
   - `version`：从 `v1` 开始，后续变更使用 `v2`、`v3`；
   - `displayName` 和 `description`；
   - 必要时修改训练参数默认值。
4. 不确定时不要修改 `runtimes.image`、`inputs.*.acceptedSpecIds`、`outputs` 和 `security`。
5. 选择 YAML 文件，点击“预览并校验”。
6. 逐项处理错误；警告允许继续，但要确认影响。
7. 核对页面显示的 SHA-256、变更和风险，再点击发布。
8. 到“发起训练”选择新方案和兼容资产，先运行小数据、少轮数的 CPU 任务。

## 5. YAML 字段不是通用语言标准

YAML 只规定“键和值怎么写”，字段名由本平台定义。`schemaVersion`、`inputs`、`runtimes` 等不是全世界通用字段，也不能自由改名。

| 字段 | 作用 | 建议谁修改 |
|---|---|---|
| `schemaVersion` | 平台字段契约版本 | 不修改 |
| `id` | 方案稳定标识 | 超级管理员 |
| `version` | 不可变版本号 | 每次发布新内容时递增 |
| `displayName` / `description` | 页面名称和说明 | 可修改 |
| `category` | 导航分类：CV/NLP/OTHER | 有明确业务依据时修改 |
| `trainingModes` | 允许的训练方式 | 训练代码维护者确认后修改 |
| `execution` | Python 入口和参数 | 训练代码维护者确认后修改 |
| `inputs` | 可接受的资产规范 | 平台新增并验证规范后修改 |
| `parameters` | 用户发起训练时填写的参数 | 可按代码真实参数修改 |
| `runtimes` | 镜像和资源档位 | 平台运维确认后修改 |
| `outputs` | 必须产生的模型、指标和日志 | 训练代码维护者确认后修改 |
| `security` | 容器安全边界 | 不得降低 |

## 6. execution 占位符

入口必须是相对路径下的 `.py` 文件。参数中只允许：

- `${MODEL_DIR}`：所选模型在容器中的只读目录；
- `${DATA_DIR}`：所选数据集目录；
- `${CODE_DIR}`：审核通过的训练代码目录；
- `${OUTPUT_DIR}`：训练输出目录；
- `${PARAMS_FILE}`：平台生成的参数文件；
- `${DEVICE}`：本次运行设备。

禁止绝对路径、`..` 目录穿越和未知占位符。

## 7. 输入规范

`acceptedSpecIds` 是平台已注册的文件规范白名单，不是任意标签。它决定哪些已验证资产能出现在训练选择列表中，后端创建任务时还会再次校验。

不要为了让某个资产“看起来可选”而填入未知 specId。若资产尚未通过对应内容规范，应修正或重新校验资产，而不是放宽方案。

## 8. 参数规则

参数名必须以小写字母开头，只能使用字母、数字和下划线。类型支持：

- `INTEGER`：整数；
- `NUMBER`：数值；
- `STRING`：文本；
- `BOOLEAN`：布尔值。

数值参数可设置 `minimum`、`maximum`；字符串和布尔值不能设置数值范围。`defaultValue` 必须符合类型和范围。相同参数名不能重复。

## 9. runtime 与资源安全

在线 v2 方案当前强制：

- 只允许平台批准的 CV/NLP worker 仓库；
- 镜像必须使用 `@sha256:<64位十六进制>`，不能使用 `latest`；
- `productionDigestRequired: true`；
- `imagePullPolicy` 只能是 `Always` 或 `IfNotPresent`；
- CPU 上限不超过 8 核、内存上限不超过 32 GiB、临时磁盘不超过 50 GiB；
- CPU runtime：`gpuCount: 0`，`nodeSelector` 为空或仅为
  `tss.ai/node-pool: cpu`；
- NVIDIA GPU runtime：`gpuCount: 1`，`nodeSelector` 必须且只能为
  `tss.ai/accelerator: nvidia`；
- 当前不接受多卡请求；共享实验室主机在每次真实任务前仍需确认所有由
  Kubernetes 暴露的 GPU 都没有被主机侧进程占用。

模板中的 digest 是不可变版本。更换镜像必须先由平台流水线构建、扫描和 smoke，再取得新 digest，不能手写一个不存在的摘要。

## 10. 输出规则

- `metricsPath` 和 `logPath` 必须是安全相对路径。
- `artifacts` 中应明确主模型、指标和日志。
- 作为新模型发布的主模型必须设置 `publishAsModel: true` 和已注册的 `publishedModelSpecId`。
- 训练代码必须实际生成所有 `required: true` 的输出；仅在 YAML 中声明不会自动生成文件。
- 指标协议固定为 `TSS_EVENT_JSONL_V1`。

## 11. 安全字段

首版应保持模板值：

```yaml
security:
  networkPolicy: PLATFORM_SERVICES_ONLY
  runAsNonRoot: true
  allowPrivilegeEscalation: false
  automountServiceAccountToken: false
  maxRuntimeSeconds: 28800
```

在线 YAML 不能把非 root、权限提升、ServiceAccount Token 等保护关闭。

## 12. 预览、发布、重复和并发

1. 预览不会写数据库，也不会改变当前活动方案。
2. 页面记录上传文件的精确 SHA-256；发布时后端重新计算，内容改变则返回冲突。
3. 同一 `id + version + SHA` 重复发布是幂等操作。
4. 同一 `id + version` 但内容不同会被拒绝；请提高版本号。
5. 同一方案只能有一个活动在线版本；新版本发布后旧在线版本转为停用。
6. 发布锁和数据库唯一约束共同处理重复点击和并发请求。
7. 内置方案只读，不能通过在线页面覆盖。

## 13. 停用和恢复

- 停用只影响后续新建任务，不取消已运行或已完成任务。
- 历史 YAML、SHA、发布人和时间保留，不做物理删除。
- 已停用版本可以用完全相同的 YAML 和 SHA 再次发布恢复。
- 回滚优先发布经过验证的更高版本；不要直接改数据库。

## 14. 常见错误

| 错误码 | 含义 | 处理方式 |
|---|---|---|
| `YAML_EMPTY` | 文件为空 | 重新选择非空 YAML |
| `YAML_TOO_LARGE` | 超过 256 KiB | 删除无关内容，不要在 YAML 内嵌资产 |
| `YAML_BOM_NOT_ALLOWED` | 带 UTF-8 BOM | 使用 UTF-8 无 BOM 保存 |
| `YAML_INVALID_UTF8` | 编码非法 | 转为有效 UTF-8 |
| `YAML_MULTIPLE_DOCUMENTS` | 包含多个 YAML 文档 | 每个文件只保留一个方案 |
| `YAML_ALIAS_NOT_ALLOWED` | 使用锚点或别名 | 展开为普通字段 |
| `YAML_TAG_NOT_ALLOWED` | 使用标签或指令 | 删除自定义标签/指令 |
| `YAML_PARSE_ERROR` | 语法、重复键或未知字段 | 按页面行列和字段路径修正 |
| `PLAN_SCHEMA_UNSUPPORTED` | Schema 版本不支持 | 使用 `tss.training.plan/v2` |
| `PLAN_FIELD_INVALID` | 字段值或组合不合法 | 按字段路径修正，不要猜测 |
| `PLAN_SPEC_UNKNOWN` | specId 未注册 | 联系平台开发登记并实现校验器 |
| `PLAN_SPEC_KIND_MISMATCH` | 模型/数据集规范放错位置 | 将 specId 放回正确输入类型 |
| `PLAN_SPEC_NOT_TRAINING_READY` | 规范只能存储，尚不能训练 | 等待对应训练能力完成 |
| `PLAN_SECURITY_POLICY_VIOLATION` | 镜像、资源或安全配置越界 | 恢复模板安全值或走平台变更流程 |
| `TRAINING_PLAN_SHA_MISMATCH` | 预览后文件发生改变 | 重新预览当前文件 |
| `TRAINING_PLAN_VERSION_CONFLICT` | 同版本已有不同内容 | 提高 `version` |

## 15. 管理接口（供平台开发联调）

- `GET /api/admin/training-plans`
- `GET /api/admin/training-plans/{planId}/{version}`
- `GET /api/admin/training-plans/templates/cv-cpu-v2`
- `POST /api/admin/training-plans/preview`
- `POST /api/admin/training-plans/publish?expectedSha256=...`
- `POST /api/admin/training-plans/{planId}/{version}/disable`

所有接口只允许超级管理员。普通用户不要直接调用管理接口。

## 16. 上线前最小验收

1. 无效 YAML 能显示稳定错误码和字段路径。
2. 普通管理员访问返回 403，未登录返回 401。
3. 同文件重复发布不产生重复记录，同版本不同内容返回冲突。
4. 停用后不再出现在新任务活动方案中，重启后状态保持。
5. 使用小型 CV 模型、ImageFolder 数据和已审核代码跑通一条 CPU 任务。
6. 记录 Job/Pod 所在节点、日志、指标、输出模型和推理结果。
7. 确认私有镜像在节点无本地副本时仍能凭 `imagePullSecrets` 拉取。

未完成第 7 项时，不能因为节点上刚好有缓存镜像就判定可上线。
