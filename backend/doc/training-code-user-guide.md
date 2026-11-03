# 自定义训练代码编写手册

适用平台：TSS AI 训练平台
适用设备：当前正式 CPU 环境
手册版本：2026-08-27

## 1. 这份代码在平台里做什么

模型、数据集、训练代码和训练方案是四个独立资产：

1. 模型和数据集负责保存输入文件；
2. 训练代码负责真正执行训练；
3. 训练方案 YAML 规定入口、参数、兼容资产、运行镜像和必须输出；
4. 发起训练时，用户才把四者组合起来。

训练代码不负责登录、下载 MinIO 文件、上传结果或创建 Kubernetes Job。平台 worker 会完成这些工作。

## 2. ZIP 包最小结构

```text
training-code.zip
├── train.py              # 训练方案 execution.entrypoint 指向它
└── requirements.txt      # 可选；只写运行镜像没有预装的依赖
```

必须：

- ZIP 中存在训练方案声明的入口文件；
- 入口为安全相对路径，不能使用绝对路径或 `..`；
- 代码审核通过后才能用于正式训练；
- 不把模型、数据集、密码、Token 或云密钥打进代码包。

建议优先把稳定依赖放入固定训练镜像。`requirements.txt` 适合少量补充依赖；现场无外网时，临时安装可能失败。

## 3. 平台提供的输入

训练方案会把下列占位符替换成容器内真实路径：

| YAML 占位符 | 训练进程环境变量 | 含义 |
|---|---|---|
| `${MODEL_DIR}` | `TSS_MODEL_DIR` | 只读基础模型目录 |
| `${DATA_DIR}` | `TSS_DATA_DIR` | 数据集目录 |
| `${CODE_DIR}` | 无需自行设置 | 已审核训练代码目录 |
| `${OUTPUT_DIR}` | `TSS_OUTPUT_DIR` | 所有结果文件的根目录 |
| `${PARAMS_FILE}` | `TSS_PARAMS_FILE` | 平台生成的 JSON 参数文件 |
| `${DEVICE}` | 由 argv 传入 | 当前运行设备，如 `cpu` |

另外可读取：

- `TSS_TRAINING_ID`：训练任务 ID；
- `TSS_TRAINING_MODE`：训练方式；
- `TSS_PARAMS_FILE`：JSON 对象，字段来自发起训练页面。

不要写死 `/workspace/...`。优先使用训练方案传入的命令行参数；环境变量只作为辅助。

## 4. 一个最小入口

```python
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--model-dir", type=Path, required=True)
parser.add_argument("--data-dir", type=Path, required=True)
parser.add_argument("--out-dir", type=Path, required=True)
parser.add_argument("--params-file", type=Path, required=True)
parser.add_argument("--device", default="cpu")
args = parser.parse_args()

params = json.loads(args.params_file.read_text(encoding="utf-8"))
args.out_dir.mkdir(parents=True, exist_ok=True)

# 在这里加载模型、读取数据并训练。
# 必须根据 YAML outputs 生成真实模型、metrics.json 和 train.log。
```

## 5. 训练进度协议（必须按格式输出）

平台只识别独占一行、以 `TSS_EVENT ` 开头的 JSON：

```python
def event(payload: dict) -> None:
    print("TSS_EVENT " + json.dumps(payload, ensure_ascii=False), flush=True)

event({"type": "progress", "progress": 0})
event({"type": "progress", "progress": 50})
event({"type": "progress", "progress": 100})
```

规则：

- `progress` 是 0～100 的完成比例，不是 epoch 编号；
- 必须 `flush=True`，否则页面可能长时间不更新；
- 非法 JSON 或错误前缀会被当作普通日志，不会更新进度；
- worker 会把脚本的 0～100 映射到整项任务的 45%～85%，准备和上传阶段由平台负责。

## 6. 训练过程指标（推荐）

每个 epoch 输出一次，并带 `step`：

```python
event({
    "type": "metric",
    "step": epoch,
    "metrics": {
        "train_loss": 0.32,
        "val_accuracy": 0.91
    }
})
```

必须：`metrics` 的过程值只能是有限数值，布尔、字符串、数组和对象不会作为曲线点写入。

推荐指标名：

| 通用 | CV 检测 | NLP 分类 |
|---|---|---|
| `train_loss`、`val_loss`、`test_loss` | `val_mAP50`、`val_mAP50_95` | `val_accuracy`、`val_precision`、`val_recall`、`val_f1` |

平台目前允许自定义指标名，但训练结果对比只能稳定识别双方都使用的同名指标。因此新代码应优先使用上表名称。

## 7. 最终指标文件（必须）

训练代码还必须在 YAML 的 `outputs.metricsPath` 写入 JSON 对象，例如：

```json
{
  "train_loss": 0.32,
  "val_accuracy": 0.91,
  "epochs": 3
}
```

- 嵌套对象会被平台展平成点号名称；
- 最终文件可含字符串等说明字段，但 MLflow 曲线只接受数值；
- 同名的 `TSS_EVENT` 末值会覆盖 `metrics.json` 中的值；
- 不要写 NaN、Infinity 或无效 JSON。

## 8. 输出文件（严格以 YAML 为准）

训练方案 `outputs.artifacts` 是唯一清单。示例：

```text
OUTPUT_DIR/
├── model.zip
├── metrics.json
└── train.log
```

必须：

- 所有 `required: true` 文件存在且非空；
- 文件相对路径和 YAML 完全一致；
- 主模型必须是可再次上传和推理加载的完整包；
- 日志不能包含密码、Token、MinIO 密钥或完整敏感数据；
- 代码不能自行伪造 `training-output.json`。最终 `tss.training.output/v1` 由平台 worker 按真实文件哈希生成。

## 9. 成功、失败和回滚

- 入口退出码非 0：任务失败；
- 缺少必需输出、空文件、无效指标 JSON：训练进程即使退出 0，任务仍失败；
- 上传部分产物后失败：任务不会标记成功，失败日志会持久化；
- Job/Pod 按 TTL 清理后，已持久化的日志和成功产物仍可查看；
- 不要捕获异常后强行 `exit 0`，否则会掩盖根因并在输出校验阶段再次失败。

## 10. 本地最小自检

1. 用临时目录模拟 model/data/output/params；
2. 运行与 YAML `execution` 完全相同的命令；
3. 检查退出码为 0；
4. 检查每条 `TSS_EVENT` 都是单行合法 JSON；
5. 检查所有必需文件存在、非空、路径一致；
6. 检查 `metrics.json` 可解析且没有 NaN/Infinity；
7. 用小数据和 1～2 个 epoch 先验收，再增加规模。

## 11. 常见问题

| 现象 | 常见原因 | 处理 |
|---|---|---|
| 页面进度不动 | 没有 `flush`、前缀错误 | 使用统一 `event()` |
| 没有曲线 | metric 没带 `step` 或值不是数值 | 每轮上报数值和 step |
| 训练结束却失败 | 必需输出缺失/为空/路径不一致 | 对照 YAML artifacts |
| 找不到文件 | 写死容器路径 | 使用 argv/平台占位符 |
| 安装依赖失败 | 节点无外网或版本冲突 | 固定到训练镜像并重新 smoke |
| 对比页没有同一指标 | 两份代码命名不同 | 使用推荐指标名 |

## 12. 上线清单

- [ ] 代码 ZIP 不含资产和密钥；
- [ ] 入口、参数和 YAML 一致；
- [ ] 小数据 CPU 任务成功；
- [ ] 进度、曲线、最终指标都可见；
- [ ] 模型、指标、日志均可下载；
- [ ] 结果模型可被真实推理脚本加载；
- [ ] 故意失败一次后，日志能说明原因。
