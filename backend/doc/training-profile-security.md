# 训练 Profile 安全边界与代码审核

## 三类资产拆分（推荐流程）

「**代码模型包**」是向严谨版本拆分的**过渡方案**。新流程将训练输入拆为三类独立资产：

| 资产 | 元数据 | 上传接口 | 校验 |
| --- | --- | --- | --- |
| **基础模型权重** | `model_asset` / `model_version`；API 接受 `baseModelVersionId`，落库 `model_version_id` | `POST /api/model/upload/*` | `ModelWeightZipValidator`（白名单扩展名，禁止脚本） |
| **训练数据集** | `dataset_asset` / `dataset_version`；`datasetVersionId` | `POST /api/dataset/upload/*` | 数据集类型/结构校验 |
| **训练代码** | `code_asset` / `code_version`；`codeVersionId` | `POST /api/code/upload` | `CodeModelZipValidator` + `training-check` 准入 |

**兼容**：CreateTask 请求体同时支持 `baseModelVersionId` 与旧字段 `modelVersionId`（同值）；若两者同时传且不一致则拒绝。

**训练行为**：

- 当前唯一 profile `image_text_consistency_fusion_logreg` **创建任务时要求选择** `baseModelVersionId`（基础模型权重版本），但 Worker **暂时不会**自动加载 `/workspace/job/model` 下的任何权重文件。
- 权重输入链路（上传校验 → MinIO 存储 → K8s env → Worker 下载/安全解压）已打通，用于后续二次训练 profile；**真正读取并加载权重**需新增独立 `trainingProfile`，在 Worker 内显式实现从 `/workspace/job/model` 读取的逻辑，并单独做安全评估。
- Worker **必须**下载 `MODEL_STORAGE_PATH` 对应 ZIP，安全解压到 `/workspace/job/model`，并打日志说明不自动加载。
- 固定训练命令不可被 `hyperParams` 覆盖；不读取 manifest 作为入口；不执行权重 ZIP 内任何文件。

**训练方案文案**：后端字段仍为 `trainingProfile`（`image_text_consistency_fusion_logreg`），用户可见文案为「训练方案 / 图文一致性基线训练」。

### CreateTask 请求体示例

```json
{
  "name": "fusion-k8s-split-flow",
  "trainingProfile": "image_text_consistency_fusion_logreg",
  "baseModelVersionId": "model-ver-xxxxxxxx",
  "datasetVersionId": "dataset-ver-xxxxxxxx",
  "codeVersionId": "code-ver-consistency-test-v1",
  "hyperParams": {
    "model": "logreg",
    "threshold": 0.5,
    "outputDir": "outputs/fusion_baseline_logreg"
  }
}
```

### K8s Job env（`KubernetesJobManifestBuilder`）

| 变量 | 说明 |
| --- | --- |
| `TRAINING_ID` | 训练版本 ID |
| `TRAINING_PROFILE` | 训练方案 |
| `CODE_VERSION_ID` | 训练代码版本 |
| `DATASET_VERSION_ID` | 数据集版本 |
| `BASE_MODEL_VERSION_ID` | 基础模型权重版本（与 `MODEL_VERSION_ID` 同值） |
| `MODEL_VERSION_ID` | 落库模型版本 ID |
| `MODEL_STORAGE_PATH` | MinIO 对象路径 |
| `CODE_STORAGE_PATH` | 训练代码 MinIO 路径 |
| `DATASET_STORAGE_PATH` | 数据集 MinIO 路径 |
| `HYPER_PARAMS_JSON` | 超参数 JSON |
| `MINIO_*` / `MLFLOW_*` / `BACKEND_CALLBACK_URL` / `INTERNAL_CALLBACK_TOKEN` | 基础设施 |

### 模型权重 ZIP 校验规则（`ModelWeightZipValidator`）

**允许**：`.pt` `.pth` `.onnx` `.pkl` `.joblib` `.yaml` `.yml` `.json` `.txt` `.md`

**禁止**：`.py` `.sh` `.bash` `.exe` `.bat` `.cmd` `.dll` `.so` `.jar`

**其他**：路径穿越拒绝；无扩展名拒绝；条目数 ≤ 10000；解压总体积 ≤ 50GB；单文件 ≤ 2GB；不能为空。

**任务类型边界**：模型上传的 `taskType`/`type` 是上传者声明的元数据。校验器负责通用
ZIP 安全、扩展名、大小和制品完整性，不根据文件名、框架特征或权重二进制推断实际
CV/NLP/POINT_CLOUD/ROBOT 模态。现有资产仍要求新版本声明与资产类型一致，训练任务仍按
持久化的模型与数据集元数据执行类型匹配。原 A-MODEL-13 的权重内容识别预期不再适用。

**训练代码 ZIP**（`CodeModelZipValidator`）不再允许 `.pt/.pth/.onnx/.pkl/.joblib`；权重应放入独立模型权重包。

**`.pkl` / `.joblib` 安全说明**：当前平台仅将其作为**模型权重文件存储**（上传校验 + MinIO 持久化 + Worker 解压到 `/workspace/job/model`）。Worker **不会**对 `.pkl` / `.joblib` 执行 `pickle.load`、`joblib.load` 或任何反序列化操作。若后续某个 `trainingProfile` 需要在训练脚本中加载它们，必须单独做安全评估（反序列化风险、来源可信性、沙箱隔离等），并在该 profile 的 Worker 逻辑中显式实现。

### `image_text_consistency_fusion_logreg` 与基础模型权重

| 阶段 | 行为 |
| --- | --- |
| 创建任务 | **必填** `baseModelVersionId`；通过 `ModelWeightZipValidator` 校验后落库 |
| Worker 运行时 | 下载并安全解压到 `/workspace/job/model`；日志明确「不自动加载基础模型权重」 |
| 训练脚本 | 仍执行固定命令，**不读取** `/workspace/job/model` |
| 后续二次训练 | 需新增 `trainingProfile`，在 Worker/脚本中显式加载 `/workspace/job/model` 下文件 |

即：本 profile 的权重选择是**元数据与链路预埋**，不是当前训练算法的输入。

### `/task/create` 四步向导

1. **基础模型权重**：选择已有或上传 zip
2. **训练数据集**：选择已有或上传 zip
3. **训练配置 + 训练代码**：hyperParams、选择/上传代码包并 `training-check`
4. **确认并提交 K8s 训练**

### `/api/task/detail` 展示字段

- `baseModelVersionId` / `modelVersionId`（基础模型权重）
- `codeVersionId`（训练代码）
- `datasetVersionId`（数据集）
- `trainingProfile`（训练方案）
- `runId` / MLflow 指标
- `outputPath`（产物路径）

---

## 代码模型包（过渡概念，已不推荐）

早期阶段曾将训练脚本、模型权重与配置文件统一称为**代码模型包**（code-model zip）。该概念仍存在于部分历史页面与种子数据说明中，**新任务请使用三类资产拆分流程**。

过渡期 `POST /api/code/upload` 曾允许权重扩展名；现已从训练代码包白名单移除，权重须走模型上传接口。

## 当前定位

TSS 平台**不是**开放式任意代码执行平台。带 `codeVersionId` 的 profile 训练仅允许：

1. 平台预注册的 `trainingProfile`（当前：`image_text_consistency_fusion_logreg`）
2. Worker 内硬编码的白名单命令（不可通过 `hyperParams` 覆盖）
3. 经管理员审核（`approval_status = APPROVED`）或自动准入校验通过的训练代码版本
4. 已通过 `ModelWeightZipValidator` 的基础模型权重版本（profile 训练必填）

## approval_status（平台级）

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 上传后默认状态，**不可**用于创建训练任务 |
| `APPROVED` | 准入校验通过，可用于 profile 训练 |
| `REJECTED` | 预留，本阶段无 reject API |

### 准入校验接口（training-check）

```
GET /api/code/version/{codeVersionId}/training-check?trainingProfile=image_text_consistency_fusion_logreg
```

后端按顺序检查：

1. codeVersion 存在
2. `status = READY`
3. `storagePath` 非空
4. `trainingProfile` 受支持
5. `codeAsset.trainingProfile` 与传入 profile 匹配
6. ZIP 通过 `CodeModelZipValidator` 路径/扩展名校验
7. ZIP 包含固定入口脚本（fusion profile 要求 `scripts/training/train_fusion_baseline.py`）

全部通过时返回 `passed=true`，任一不通过则返回 `passed=false` 和 `reasons[]`。真正的新通过证据随后进入系统审核模式分流：`DIRECT_PASS` 写入系统直通证据并自动成为 `APPROVED`；`STANDARD_REVIEW` 保持当前风险扫描和管理员审核方式。完全等价的既有校验证据会幂等复用，不重复改变审批状态。

**注意**：`training-check` 仅适用于**训练代码包**，不适用于基础模型权重包。

### DIRECT_PASS ≠ 代码安全审计

**重要**：`DIRECT_PASS` 下的 `APPROVED` 只代表**结构、元数据、固定入口和实际制品完整性检查通过，并按系统配置免除代码审核**，**不代表**已完成代码安全审计。`STANDARD_REVIEW` 仍执行现有风险分流。

### 校验时机

`TrainingExperimentService.createExperiment` / `createVersion` 中：

- profile 训练：`baseModelVersionId` 必填 → `validateBaseModelVersion` → `requireApprovedForTraining`
- 未通过时返回明确错误，且**不会**创建 K8s Job

## K8s Worker MLflow logging（观测能力）

K8s Worker（`k8s/training-worker/train.py`）在 Pod 内通过 MLflow REST API 直写。

- Worker 下载并解压：代码 → `/workspace/job/code`；数据 → `/workspace/job/data`；**模型权重 → `/workspace/job/model`**
- 日志示例：`当前训练方案 image_text_consistency_fusion_logreg 不自动加载基础模型权重`
- MLflow 失败不得影响训练成败

## 负向测试

### JUnit

- `TrainingCodeVersionSecurityTest`：PENDING 拒绝、缺失 baseModelVersionId、base/model 冲突、profile 不匹配、数据集不存在
- `CodeModelZipValidatorTest`：训练代码包拒绝 `.pt` 权重、拒绝 shell/无扩展名/路径穿越
- `ModelWeightZipValidatorTest`：权重包允许 `.pt`、拒绝 `.py`/不支持扩展名

### Worker 脚本（`verify-consistency-security.sh`）

- hyperParams 不能覆盖固定命令
- code.zip 缺少 train 脚本
- zip 路径穿越
- data.zip 缺少 data/

## 前端入口

- **`/task/create`**：K8s 训练主入口，四步向导（基础模型权重 → 数据集 → 配置+训练代码 → 提交）
- **`/task/consistency-upload`**：开发/演示用上传页（过渡）

## Profile 数据包要求

### `image_text_consistency_fusion_logreg`

- 创建任务时**必须**选择 `baseModelVersionId`，但 Worker **暂时不自动加载** `/workspace/job/model` 下权重（链路已打通，算法未使用）
- 数据包仅需 `data/*.jsonl`（9 个预计算分数文件）
- 固定命令：`python scripts/training/train_fusion_baseline.py --data-dir data --model logreg --out-dir outputs/fusion_baseline_logreg`
- 二次训练（加载权重）需后续新增 profile，显式读取 `/workspace/job/model`
