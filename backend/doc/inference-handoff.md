# 训练产物 → 推理验证交接文档

> 目的：供新对话快速理解当前 K8s 训练链路、`fusion_model.pkl` 产物格式，以及如何离线验证「训练产物能否用于推理」。  
> **本文档仅交接说明，不包含业务代码改动。**

---

## 1. 当前代码状态

### Git 分支与最新 commit

| 仓库 | 分支 | HEAD |
|------|------|------|
| 后端（`/opt/tss-platform`） | `backend` | `63a041a3b3c5768bc246d8d30661826d7e465ef7` |
| 前端（`/opt/tss-platform/frontend`） | `frontend-dev` | `40e7dc9ae7fd9be67961bdc0d9f76274308101d5` |

### 与训练链路相关的关键 commit

| Hash | 说明 |
|------|------|
| `c5cc5b9` | `feat(training): split base model weights from training code` — 三类资产拆分、`baseModelVersionId`、模型权重 ZIP 校验、K8s env、Worker 解压权重 |
| `2bc52d8` | `chore(k8s): increase training job quota and reduce ttl` — `jobs.batch` 20→50，Job TTL 86400→3600 |
| `a474ac8` | `feat(training): log k8s worker metrics to mlflow` — Worker REST 写 MLflow |
| `0775f8c`（frontend） | `feat(task): split base model dataset and code steps` — `/task/create` 四步重构 |
| `08b2e4d`（frontend） | `fix(task): render mlflow metrics and artifact downloads` — 产物 Blob 下载、MLflow 指标映射 |
| `5b17ff7` / `63a041a` | 推理模块骨架（`/api/inference/*`、推理 K8s Job）— **与 fusion_model.pkl 离线验证是不同话题，见第 10 节** |

### 未提交 / 无关改动

当前工作区**仍有未提交**的 module1 文件（与训练/推理验证无关，**不要带入下一轮 commit**）：

- `backend/src/main/java/com/tss/platform/module1/dto/LogItemVO.java`（untracked）
- `backend/src/main/java/com/tss/platform/module1/dto/LogListQueryDTO.java`（untracked）
- `backend/src/main/java/com/tss/platform/module1/util/OperationLogConverter.java`（untracked）
- `backend/src/main/java/com/tss/platform/module1/controller/SystemLogController.java` 等（若存在 modified，亦未纳入训练 commit）

前端子目录 `frontend/` 在 monorepo 根 git 中显示为 untracked（前端独立仓库已 push）。

### 本地运行环境（2026-07-01 实测）

- 后端：`java -jar backend/target/tss-backend-1.0.0.jar`，端口 `8080`
- 登录：`admin` / `password123`
- MinIO 数据目录：`/opt/tss-platform/minio-data/models/`
- K8s：kind 集群 `tss-training`，Worker 镜像 `tss-training-worker:local`
- Flyway：开发库存在 V16 历史与源码版本号漂移，启动时常用 `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`

---

## 2. 当前训练架构

### `/task/create` 四步流程（已废弃「代码模型包」用户概念）

| Step | 内容 | 关键字段 |
|------|------|----------|
| **1** | 上传或选择**基础模型权重** | `baseModelVersionId`（落库 `model_version_id`；兼容旧字段 `modelVersionId`） |
| **2** | 上传或选择**训练数据集** | `datasetVersionId` |
| **3** | **训练配置 + 训练代码** | `trainingProfile`、`hyperParams`、`codeVersionId`（须 `training-check` 通过） |
| **4** | 确认并提交 **K8s 训练** | `POST /api/task/create` |

四类输入已拆分：

1. **基础模型权重** — `model_asset` / `model_version`，`ModelWeightZipValidator`
2. **训练数据集** — `dataset_asset` / `dataset_version`
3. **训练代码** — `code_asset` / `code_version`，`CodeModelZipValidator` + `training-check`
4. **训练方案** — `trainingProfile`（当前仅一个）

文档详见：`backend/doc/training-profile-security.md`

### CreateTask 请求体示例

```json
{
  "name": "fusion-k8s-split-flow",
  "trainingProfile": "image_text_consistency_fusion_logreg",
  "baseModelVersionId": "model-ver-ef52b5f49daa45f7afc4a7c98d02a4ce",
  "datasetVersionId": "dataset-ver-consistency-test-data-v1",
  "codeVersionId": "code-ver-consistency-test-v1",
  "hyperParams": {
    "model": "logreg",
    "threshold": 0.5,
    "outputDir": "outputs/fusion_baseline_logreg"
  }
}
```

### K8s Job 关键 env（节选）

```
TRAINING_ID
TRAINING_PROFILE=image_text_consistency_fusion_logreg
BASE_MODEL_VERSION_ID / MODEL_VERSION_ID
CODE_VERSION_ID / DATASET_VERSION_ID
MODEL_STORAGE_PATH / CODE_STORAGE_PATH / DATASET_STORAGE_PATH
HYPER_PARAMS_JSON
MINIO_* / MLFLOW_* / BACKEND_CALLBACK_URL
```

Worker 会下载基础模型权重 ZIP 到 `/workspace/job/model`，但 **fusion profile 不加载这些权重**（见第 3 节）。

---

## 3. 当前可用 trainingProfile

### 唯一 profile

| 字段 | 值 |
|------|-----|
| 内部 ID | `image_text_consistency_fusion_logreg` |
| 前端展示名 | **图文一致性基线训练** |
| 数据集类型要求 | `NLP` |
| 代码入口（training-check 强制） | `scripts/training/train_fusion_baseline.py` |

### Worker 固定命令（不可被 hyperParams 覆盖）

```bash
python scripts/training/train_fusion_baseline.py \
  --data-dir data \
  --model logreg \
  --out-dir outputs/fusion_baseline_logreg
```

定义位置：

- 后端：YAML 训练方案（`TrainingPlanRegistry` 加载，见 `training-plans/`）
- Worker：`k8s/training-worker/train.py` 中 `PROFILE_COMMANDS`

### 重要行为说明

| 项 | 行为 |
|----|------|
| 基础模型权重 | 创建任务**必填** `baseModelVersionId`；Worker **下载并解压**到 `/workspace/job/model` |
| 权重是否参与训练 | **否**。日志：`当前训练方案 image_text_consistency_fusion_logreg 不自动加载基础模型权重` |
| 训练真正使用的模型 | 由 score jsonl **现场训练**得到的 sklearn Pipeline，序列化为 **`fusion_model.pkl`** |
| hyperParams | 仅记录/预留，**不能**改 Worker 命令或脚本路径 |

---

## 4. 成功训练任务样例

以下为本环境**最近一次成功训练**（2026-06-30，split-flow 验证后仍有多次同配置成功；取最新 taskId）。

| 字段 | 值 |
|------|-----|
| **taskId** | `train-ver-332edb1190aa461aa3f0cf96199e2865` |
| **baseModelVersionId** | `model-ver-ef52b5f49daa45f7afc4a7c98d02a4ce` |
| **datasetVersionId** | `dataset-ver-consistency-test-data-v1` |
| **codeVersionId** | `code-ver-consistency-test-v1` |
| **trainingProfile** | `image_text_consistency_fusion_logreg` |
| **runId** | `36812efe460f44f09b98f60d5894c57b` |
| **outputPath** | `minio://training-results/train-ver-332edb1190aa461aa3f0cf96199e2865/artifacts/` |
| **test_accuracy** | `0.9418238993710691` |
| **test_f1** | `0.9429012345679012` |
| **test_roc_auc** | `0.9784754250386399` |
| **status** | `success` |

**同配置早期样例**（split-flow E2E）：`train-ver-8ba74de72cd14b0a85b16715a3cb2715`（指标相同）。

### K8s Job 名称

命名规则（`KubernetesJobNaming.jobNameForTraining`）：

```
tss-train-{sanitized-training-id}
```

本 taskId 对应 Job 名应为：

```
tss-train-train-ver-332edb1190aa461aa3f0cf96199e2865
```

> **注意**：`job-ttl-seconds-after-finished` 已改为 **3600**（1 小时），旧 Job/Pod 可能已被 K8s 自动清理，需用 MinIO 产物或 `/api/task/detail` 追溯。

---

## 5. 训练产物

### MinIO 对象列表（profile 定义 + Worker 实际上传）

成功训练后，`models` bucket（默认）下会有：

| 文件 | 说明 |
|------|------|
| `fusion_model.pkl` | sklearn Pipeline + features + threshold（**推理核心**） |
| `metrics.json` | train/val/test 全量指标与 feature 列表 |
| `val_predictions.csv` | 验证集预测 |
| `test_predictions.csv` | 测试集预测（可用于对照） |
| `train.log` | Worker  stdout 摘要（路径不在 `artifacts/` 下） |

### 路径格式

```
training-results/{taskId}/artifacts/{fileName}
training-results/{taskId}/train.log
```

本地磁盘等价路径（开发环境）：

```
/opt/tss-platform/minio-data/models/training-results/{taskId}/artifacts/
```

Worker 上传逻辑见 `k8s/training-worker/train.py` 中 `output_prefix = f"training-results/{training_id}/artifacts"`。

### 下载接口

```
GET /api/files/download?objectName=training-results/{taskId}/artifacts/fusion_model.pkl
Authorization: Bearer {token}
```

**注意**：

- `objectName` **不要**带 `minio://` 前缀
- 也不要带 bucket 名（如 `models/`），除非你的 `FileObjectController` 规范化逻辑明确要求
- 前端详情页会将 `minio://training-results/.../artifacts/` 转为 `training-results/.../artifacts/{file}` 再下载（见 `task/detail/[id].tsx` 中 `minioPathToObjectName`）

示例：

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/api/files/download?objectName=training-results/train-ver-332edb1190aa461aa3f0cf96199e2865/artifacts/fusion_model.pkl" \
  -o fusion_model.pkl
```

---

## 6. fusion_model.pkl 的格式

### 来源（训练脚本写入逻辑）

代码包内 `scripts/training/train_fusion_baseline.py` 第 196–197 行：

```python
pickle.dump({"model": model, "features": features, "threshold": args.threshold}, f)
```

其中：

- `model`：`sklearn.pipeline.Pipeline`，默认 `logreg` 时为 `[SimpleImputer, StandardScaler, LogisticRegression]`
- `features`：字符串列表，由 train/val/test 合并后的 DataFrame 列名推导（排除 `pair_id`、`label`）
- `threshold`：默认 `0.5`，用于 `(prob >= threshold)` 得到二分类 pred

### 实际加载验证（已执行，非猜测）

**使用的 taskId**：`train-ver-332edb1190aa461aa3f0cf96199e2865`  
**加载方式**：`tss-training-worker:local` 镜像内 `python3` + `pickle.loads`  
**本地文件**：`/opt/tss-platform/minio-data/models/training-results/train-ver-332edb1190aa461aa3f0cf96199e2865/artifacts/fusion_model.pkl`

```
keys: ['features', 'model', 'threshold']
threshold: 0.5
num_features: 58
features 示例（前 8 个）:
  global_global_prob, global_global_pred,
  region_num_entities, region_region_visible_count,
  region_region_visible_ratio, region_region_conflict_count,
  region_region_avg_confidence, region_region_peak_similarity
model: sklearn.pipeline.Pipeline
pipeline_steps: ['imputer', 'scaler', 'clf']
clf: sklearn.linear_model.LogisticRegression
```

`metrics.json` 中 `features` 数组与 pickle 内 `features` 一致（58 维），详见同目录 `metrics.json`。

### test_predictions.csv 格式（对照用）

列：`pair_id`, `label`, `prob`, `pred`（1272 行）

```
pair_id,label,prob,pred
pos_001829,1,0.0330611033451089,0
neg_001829,0,0.0123376017879788,0
```

推理验证时：`prob = predict_proba(X)[:, 1]`，`pred = (prob >= threshold).astype(int)`。

---

## 7. 推理需要什么输入

### 模型类型（务必理解）

- **不是**图像端到端模型，**不是** YOLO/CV 检测器
- 是 **基于预计算 score 的 sklearn 融合二分类器**（LogReg + 58 维手工/统计特征）
- 输入是 **JSONL 分数文件**，不是原始图片

### 数据流（与训练相同）

训练脚本 `load_split(data_dir, split)` 读取 3 路 JSONL，按 `pair_id` + `label` inner merge：

| 来源 | 文件名（test split 示例） |
|------|---------------------------|
| global | `global_ultra_easy_v2_refreshed_v1_retrain_test_scores.jsonl` |
| region | `region_ultra_easy_v2_refreshed_v1_test_scores_v1.jsonl` |
| entity | `entity_det_ultra_easy_v2_refreshed_v1_test_scores_ocr.jsonl` |

每行 JSON 经 `flatten_scalar_features(row, prefix)` 展平为数值列，前缀分别为 `global_`、`region_`、`entity_`。

合并后得到 DataFrame，**推理时取 `features` 列子集**送入 Pipeline。

### 推理特征构造要点

1. 必须有与训练相同的 **58 个 feature 名**（以 pickle 内 `features` 为准）
2. 缺列会导致 imputer 填 median，但分布漂移会影响精度
3. 需要 `pair_id` 用于与 `test_predictions.csv` 对齐
4. `label` 仅用于评估，在线推理时可省略

### predict API（sklearn Pipeline）

```python
# model 为 pickle["model"]
prob = model.predict_proba(X[features])[:, 1]
pred = (prob >= threshold).astype(int)
```

其中 `X` 为合并后的 feature DataFrame，列顺序须包含 `features` 全部列。

---

## 8. 推理验证建议（下一轮对话最小步骤）

目标：**证明 `fusion_model.pkl` 能对 test split 复现 `test_predictions.csv`**。

### 步骤

| 步骤 | 动作 |
|------|------|
| A | 下载 `fusion_model.pkl`（API 或直接读 MinIO 本地目录） |
| B | 准备 test 三路 score jsonl（数据集 `dataset-ver-consistency-test-data-v1` 解压后的 `data/*.jsonl`） |
| C | `pickle.load` 得到 `{model, features, threshold}` |
| D | 复用 `load_split(data_dir, "test")` 逻辑构造 DataFrame |
| E | `prob = predict_proba(...)`，`pred = (prob >= threshold)` |
| F | 与 `test_predictions.csv` 按 `pair_id` merge |
| G | 断言 `prob`/`pred` 最大误差 ≈ 0（浮点容忍 1e-6） |

### 可运行 Python 草稿（离线，非推理服务）

```python
#!/usr/bin/env python3
"""Verify fusion_model.pkl against test_predictions.csv (offline)."""
import json
import pickle
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.pipeline import Pipeline  # noqa: F401 — needed for unpickle

# --- paths ---
TASK_ID = "train-ver-332edb1190aa461aa3f0cf96199e2865"
ARTIFACT_DIR = Path(f"/opt/tss-platform/minio-data/models/training-results/{TASK_ID}/artifacts")
DATA_DIR = Path("/path/to/unzipped/dataset/data")  # 解压 consistency_test_data.zip 后的 data/

# --- load artifact ---
with (ARTIFACT_DIR / "fusion_model.pkl").open("rb") as f:
    bundle = pickle.load(f)
model: Pipeline = bundle["model"]
features: list[str] = bundle["features"]
threshold: float = bundle["threshold"]

# --- copy load_split / flatten_scalar_features from train_fusion_baseline.py ---
SCORE_FILES = {
    "test": {
        "global": "global_ultra_easy_v2_refreshed_v1_retrain_test_scores.jsonl",
        "region": "region_ultra_easy_v2_refreshed_v1_test_scores_v1.jsonl",
        "entity": "entity_det_ultra_easy_v2_refreshed_v1_test_scores_ocr.jsonl",
    }
}

def read_jsonl(path: Path):
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows

def flatten_scalar_features(row, prefix):
    out = {"pair_id": row["pair_id"]}
    if "label" in row:
        out["label"] = row["label"]
    def visit(value, name):
        if isinstance(value, bool):
            out[name] = int(value)
        elif isinstance(value, (int, float)) and not isinstance(value, bool):
            out[name] = value
        elif isinstance(value, dict):
            for k, child in value.items():
                visit(child, f"{name}_{k}")
    for k, v in row.items():
        if k in {"pair_id", "label", "image_path", "entities", "entities_typed", "per_entity"}:
            continue
        visit(v, f"{prefix}_{k}")
    return out

def load_split(data_dir: Path, split: str) -> pd.DataFrame:
    frames = []
    for source, filename in SCORE_FILES[split].items():
        rows = [flatten_scalar_features(r, source) for r in read_jsonl(data_dir / filename)]
        frames.append(pd.DataFrame(rows))
    merged = frames[0]
    for df in frames[1:]:
        overlap = [c for c in df.columns if c in merged.columns and c not in {"pair_id", "label"}]
        if overlap:
            df = df.drop(columns=overlap)
        merged = merged.merge(df, on=["pair_id", "label"], how="inner")
    return merged

# --- infer ---
test_df = load_split(DATA_DIR, "test")
X = test_df[features]
prob = model.predict_proba(X)[:, 1]
pred = (prob >= threshold).astype(int)

got = pd.DataFrame({"pair_id": test_df["pair_id"], "prob": prob, "pred": pred})
expect = pd.read_csv(ARTIFACT_DIR / "test_predictions.csv")
cmp = got.merge(expect, on="pair_id", suffixes=("_got", "_exp"))

max_prob_err = (cmp["prob_got"] - cmp["prob_exp"]).abs().max()
mismatch_pred = (cmp["pred_got"] != cmp["pred_exp"]).sum()
print("rows", len(cmp), "max_prob_err", max_prob_err, "pred_mismatch", mismatch_pred)
assert max_prob_err < 1e-5 and mismatch_pred == 0, "predictions differ from training artifact"
print("OK: fusion_model.pkl reproduces test_predictions.csv")
```

**推荐运行环境**：`tss-training-worker:local` 镜像（已含 sklearn/pandas/numpy）。

---

## 9. 相关代码文件清单

| 优先级 | 路径 | 说明 |
|--------|------|------|
| ★★★ | `k8s/training-worker/train.py` | Worker：下载 code/data/model、固定命令、上传产物、MLflow REST、回调 |
| ★★★ | 代码包内 `scripts/training/train_fusion_baseline.py` | 特征工程、训练、写 pkl/csv/json（种子包：`consistency_test_code.zip`） |
| ★★☆ | `backend/.../training/plan/TrainingPlanRegistry.java` | YAML 训练方案注册表（plan 白名单、命令、产物） |
| ★★☆ | `backend/.../service/TrainingExperimentService.java` | CreateTask、`baseModelVersionId`、启动训练 |
| ★★☆ | `backend/.../training/KubernetesTrainingExecutor.java` | 提交 K8s Job、注入 modelVersion |
| ★★☆ | `backend/.../training/KubernetesJobManifestBuilder.java` | Job YAML + env |
| ★★☆ | `backend/.../controller/FileObjectController.java` | `GET /api/files/download` |
| ★★☆ | `frontend/src/pages/task/detail/[id].tsx` | 详情页：产物列表、MinIO 路径转换、下载 |
| ★★☆ | `frontend/src/pages/task/create/index.tsx` | 四步创建向导 |
| ★☆☆ | `frontend/src/components/TrainingMetricsPanel/index.tsx` | MLflow 指标展示 |
| ★☆☆ | `backend/.../service/MlflowTrackingService.java` | 后端 MLflow（本地训练路径；K8s Worker 用 REST） |
| 参考 | `backend/doc/training-profile-security.md` | 三类资产拆分、权重 ZIP 规则 |
| 参考 | `backend/.../controller/InferenceTaskController.java` | 已有推理任务 API（**尚未对接 fusion_model.pkl**） |
| 参考 | `backend/.../inference/*.java` | 推理 K8s Executor（与 fusion 离线验证独立） |

---

## 10. 已知限制

1. **`fusion_model.pkl` 仅适用于 fusion_logreg 特征推理**  
   输入必须是 global/region/entity 三路 score jsonl 合并后的 58 维特征，**不能**直接输入图片字节流。

2. **不是 YOLO / 检测 / 图像模型**  
   若要做图像或 YOLO 推理，需要新的 `trainingProfile` + `inferenceProfile` + 不同产物格式。

3. **基础模型权重链路已打通，但 fusion profile 不加载**  
   Worker 仅解压到 `/workspace/job/model`；当前训练不读取该目录。二次训练需新 profile 显式加载。

4. **`.pkl` / `.joblib` 当前仅存储，Worker 不反序列化权重包**  
   若未来 profile 要 `pickle.load` 权重，需单独安全评估。

5. **在线推理 API 尚未绑定 fusion_model.pkl**  
   平台已有 `/api/inference/tasks` 与推理 K8s Worker 骨架（commit `5b17ff7`），但 **未**实现「上传 fusion_model.pkl → 对 score jsonl 预测」的端到端路径。下一轮验证建议先做 **离线 Python 对照**，再决定是否扩展 Inference 模块。

6. **K8s Job TTL = 3600s**  
   训练完成 1 小时后 Job/Pod 可能被删；追溯训练过程需靠 MinIO 产物 + DB `/api/task/detail` + MLflow runId。

7. **pickle 版本/环境**  
   `fusion_model.pkl` 含 sklearn Pipeline，推理环境 sklearn 版本应与训练 Worker 镜像一致（`tss-training-worker:local`）。

---

## 附录：快速 API 查询

```bash
# 登录
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"type":"account","username":"admin","password":"password123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 任务详情
curl -s "http://127.0.0.1:8080/api/task/detail?id=train-ver-332edb1190aa461aa3f0cf96199e2865" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

---

**文档路径**：`backend/doc/inference-handoff.md`  
**状态**：已创建，**未 commit**（待确认）。
