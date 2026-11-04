# fusion_model.pkl 推理脚本

这个目录用于测试训练产物 `fusion_model.pkl`。脚本入口是 `infer.py`。

## 模型包要求

模型 ZIP 中需要包含：

```text
fusion_model.pkl
```

也支持放在子目录中，例如：

```text
artifacts/fusion_model.pkl
metrics.json
```

## 输入格式

支持三类输入：

- 单文件 JSONL：每行一个样本对象，字段名与模型的 `features` 对齐。
- 单文件 JSON/CSV：JSON 对象、对象数组、`{"rows":[...]}` 或 CSV 表格。
- 数据集版本目录：包含训练数据里的三组 score 文件，例如 `data/global_*_test_scores.jsonl`、`data/region_*_test_scores_v1.jsonl`、`data/entity_*_test_scores_ocr.jsonl`，脚本会自动按 `pair_id + label` 合并。

最小测试输入见 `sample_input.jsonl`。缺失的特征会交给模型里的 `SimpleImputer` 处理。

## 推理参数

```json
{
  "threshold": 0.5,
  "split": "test",
  "inputKind": "auto",
  "maxRows": 0,
  "idField": "pair_id"
}
```

- `threshold`：概率阈值，默认使用模型包里的阈值。
- `split`：当输入是完整 score 数据集目录时使用，可选 `train`、`val`、`test`。
- `inputKind`：`auto`、`raw_scores` 或 `features`。
- `maxRows`：大于 0 时只推理前 N 行，适合快速冒烟测试。
- `idField`：结果中作为样本 ID 的字段名。

## 输出

脚本会写出：

```text
result.json
predictions.csv
predictions.jsonl
```

`result.json` 中包含总行数、正负预测数量、可选准确率、缺失特征统计和前 20 条预览。
