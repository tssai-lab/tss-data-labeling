# MiniRBT-H288 中文文本分类 CPU / 单卡 GPU 验收代码

本目录生成一套小型、离线、可复现的 NLP 验收资产。目标是验证“上传 → 训练 → 指标/日志 → 结果模型 → 推理 → 原文与预测展示”，不承诺生产精度或吞吐。

## 1. 上游与体积

- 基础模型：`hfl/minirbt-h288`
- 固定 revision：`dc4eebb0cf6f9e7094142ac28fbf971517c6a366`
- 许可：Apache-2.0
- 仅保留 PyTorch 的 `pytorch_model.bin`、`config.json`、`vocab.txt` 和许可/说明；明确不下载 74 MB 的 `tf_model.h5`。
- 数据集是本项目自建的 40 条二分类中文短文本，不复制外部数据集。

## 2. 生成验收文件

在仓库根目录运行：

```bash
python examples/acceptance/minirbt_text_classification/prepare.py \
  --output-dir dist/minirbt-acceptance
```

生成：

- `minirbt-h288-base.zip`：上传为 NLP 模型；
- `minirbt-sentiment-dataset.zip`：上传为 NLP 数据集；
- `minirbt-training-code.zip`：上传为训练代码，入口 `train.py`；统一选择 `minirbt_text_classification`，再在资源步骤选择 CPU 或单卡 GPU。`minirbt_text_classification_gpu-v1.yaml` 只保留为历史验收依据，不应再发布；
- `minirbt-inference-script.zip`：上传为推理脚本，入口 `infer.py`；
- `acceptance-manifest.json`：记录每个文件的大小和 SHA-256。

准备脚本固定上游 revision 并逐文件校验 SHA-256；下载或校验失败时不生成正式包。

## 3. 数据与模型契约

模型 ZIP 根目录：

```text
model.yaml
config.json
pytorch_model.bin
vocab.txt
LICENSE.txt
UPSTREAM_README.md
```

数据集 ZIP 根目录：

```text
dataset.json
data/train.jsonl
data/validation.jsonl
data/test.jsonl
```

每条 JSONL 为：

```json
{"id":"test-正面-001","text":"数据集预览正常，文本内容显示完整。","label":"正面"}
```

训练脚本会失败关闭：缺少三段数据、空文本、未知标签、重复 ID、单类别训练集、离线模型文件缺失均不会静默降级。

## 4. 建议验收参数

```json
{
  "epochs": 2,
  "batchSize": 8,
  "lr": 0.0001,
  "weightDecay": 0.01,
  "maxSeqLength": 64,
  "seed": 42,
  "maxTrainSamples": 0,
  "maxEvalSamples": 0
}
```

推理参数：

```json
{"split":"test","maxTexts":20,"batchSize":8,"maxSeqLength":64}
```

## 5. 验收边界

1. 训练和推理都必须离线加载模型，运行 Pod 不从 Hugging Face 下载文件。
2. 训练结果至少包含 loss、accuracy、precision、recall、F1、验证/测试预测和结果模型 ZIP。
3. 推理结果必须包含原始文本、预测标签、置信度和可选真值。前 50 条短文本
   直接随结果展示；超过 160 字的文本写入当前任务输出的 `previews/text/`
   输入预览目录，结果中只保存摘要和相对路径。单条弹窗预览最多 2 MiB，
   超出时明确标记截断，避免大段文本挤占结果 JSON 和节点磁盘。
4. Job/Pod 被 TTL 清理后，训练日志、指标和结果模型仍应能从对象存储查看或下载。
5. GPU 模式只允许平台传入设备 `0`，并要求容器内恰好只暴露一张 CUDA GPU；CUDA 不可用、看见多张卡或请求其他编号都会失败关闭。
6. 该小包只证明 CPU 或单卡 GPU 功能闭环；不承诺精度、吞吐、并发、多卡或大数据性能。
