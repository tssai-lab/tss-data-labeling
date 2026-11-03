# YOLO 目标检测训练代码包

压缩本目录中的 `train.py` 和 `requirements.txt` 为 ZIP 后上传为训练代码。

基础模型 ZIP 必须包含 `yolo11n.pt`；数据集 ZIP 必须包含 `data.yaml`、图片目录和 YOLO 标签目录。创建任务时选择 `yolo_object_detection` 方案。

## 训练与推理镜像

### 训练镜像

| 镜像 | 存放位置 | 对应训练方案 |
|---|---|---|
| `tss-cv-worker` | 阿里云 | `yolo_object_detection`、`hf_image_classification` |
| `tss-nlp-worker` | 阿里云 | `image_text_consistency_fusion_logreg` |

### 推理镜像

| 镜像 | 存放位置 | 用途 |
|---|---|---|
| `tss-inference-worker-cpu` | GitHub 容器仓库（ghcr.io） | CPU 推理 |

---

## 训练进度与指标上报（如何实现）

**平台靠训练代码向 stdout 打印 `TSS_EVENT 事件` 来感知训练过程**。实现「训练过程中动态进度」和「训练过程中指标记录」，只需要在你的训练代码里按下面的协议打印事件行。

本目录的 `train.py` 模板已内置 `event()` 辅助函数和每 epoch 上报逻辑，可直接使用；如果你自己写训练代码，请遵循以下协议。

### 0. 事件辅助函数（统一封装）

```python
def event(payload: dict) -> None:
    """向平台上报一条 TSS_EVENT 事件，打印后立即 flush。"""
    print("TSS_EVENT " + json.dumps(payload, ensure_ascii=False), flush=True)
```

> ⚠️ 三条铁律，违反会导致进度/指标出不来：
> 1. **独占一行**，行首必须是 `TSS_EVENT `（含末尾空格），后面是合法 JSON；
> 2. **必须 flush**（`print(..., flush=True)`）——训练容器 stdout 是管道而非终端，不 flush 会被缓冲，事件直到训练结束才一次到达，表现为"进度卡住/指标全无"；
> 3. 数值必须是**数值类型**（int/float），字符串、对象等会被忽略。

### 1. 训练过程中动态进度（进度条实时推进）

**在训练循环里，每个 epoch（或周期性）打印一条进度事件：**

```python
for epoch in range(1, epochs + 1):
    # ... 训练一个 epoch ...
    progress = round(epoch * 100 / epochs)      # 完成度百分比 0~100
    event({"type": "progress", "progress": progress})
```

**要求：**
- `progress` 必须是 **0~100 的完成度数值**，不是 epoch 序号（传 1、2、3 这种会被映射成接近 45 的同一个值，进度条看不出变化）；
- 建议训练开始前报 `progress: 0`、结束后报 `progress: 100`。

**平台如何显示（映射关系）：**
- 你上报的 0~100 会被 worker 映射到 **45%~85%** 这段区间（`mapped = 45 + progress * 0.4`），即"训练真正跑起来"的那段；
- 前后 **0~45%（准备阶段）** 和 **85%~100%（校验/上传）** 由 worker 自动上报，无需你处理；
- 自动上报的固定节点（写死在 `k8s/training-worker/train.py` 的 `run()`）：`5`准备 → `15`下载模型 → `28`下载数据集 → `40`下载代码 → `45`开始训练 → `86`校验 → `96`上传 → `100`成功。

### 2. 训练过程中指标记录（指标可视化）

有两种方式，**推荐 TSS_EVENT 指标事件**（能记录训练过程、画出曲线）；`metrics.json` 方式只能记录最终值。

#### 方式一：TSS_EVENT 指标事件（推荐，支持训练过程曲线）

**每个 epoch 上报一次，务必带 `step`：**

```python
event({"type": "metric", "step": epoch, "metrics": {"train_loss": 0.32, "val_mAP50": 0.81}})
```

**要求：**
- **`step` 表示当前训练步数（如 epoch 序号），强烈建议传** —— 平台据此绘制"指标随训练过程变化"的折线曲线（如 loss 随 epoch 下降）；
- 不带 `step` 时，该指标只会被记录为最终值（末值单点，无曲线）；
- `metrics` 里的值必须是数值（int/float）。

**实现后的效果：** 每个 epoch 都上报且带 step，训练完成后平台把每个 step 的指标写入 MLflow，任务详情页的指标面板即可显示训练过程曲线。

> ⚠️ 曲线依赖 worker 按 step 累积并写入 MLflow（`k8s/training-worker/train.py` 的 `execute_training`）。若部署的 worker 版本较旧，只会展示最终值。

#### 方式二：写入 metrics.json（仅最终值）

把最终指标写入 RunSpec 声明的 metrics 输出路径（如 `metrics.json`）：

```python
metrics = {"train_loss": 0.32, "val_mAP50": 0.81, "val_mAP50_95": 0.55, "epochs": 10}
(output / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False), encoding="utf-8")
```

**注意：** 这里写的是**最终值**，训练成功后平台展示末值（柱状图 / 摘要卡片），**没有训练过程曲线**；可与 TSS_EVENT 指标事件配合使用（最终值 + 曲线）。

#### 标准指标名（平台可视化优先识别）

```
train_loss  val_loss  test_loss
train_accuracy  val_accuracy  test_accuracy
train_precision val_precision test_precision
train_recall    val_recall    test_recall
train_f1        val_f1        test_f1
val_mAP50  val_mAP50_95
```

也兼容常见别名：`loss`（对应 train_loss）、`accuracy`（对应 val_accuracy）、`mAP50` / `val/mAP50`（对应 val_mAP50）、`mAP50-95` / `val/mAP50-95`（对应 val_mAP50_95）等。**指标名对不上时前端不会显示。**

#### 展示规则小结

| 上报方式 | 展示结果 |
|---|---|
| TSS_EVENT metric 事件 + `step` | 训练过程折线曲线（训练完成后显示，末值取最后一个 step） |
| TSS_EVENT metric 事件，不带 `step` | 最终值单点（末值柱状图） |
| `metrics.json` | 最终值（末值柱状图 / 摘要卡片） |
| 都不上报 | 无指标展示 |

---

## 参考示例

本目录 `train.py` 模板的做法（`on_epoch_end` 回调里，每个 epoch 上报进度 + 指标）：

```python
def on_epoch_end(trainer) -> None:
    epoch = int(getattr(trainer, "epoch", 0) + 1)
    total = int(getattr(trainer, "epochs", 1) or 1)
    event({"type": "progress", "progress": round(epoch * 100.0 / total)})
    snapshot = { "train_loss": ..., "val_mAP50": ... }   # 从 trainer.metrics 取数值
    event({"type": "metric", "step": epoch, "metrics": snapshot})
```

> 注意：要出曲线，metric 事件必须带 `step`。如果模板里的 metric 事件没带 `step`，只有末值展示。
