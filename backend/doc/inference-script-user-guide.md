# 自定义推理脚本编写手册

适用平台：TSS AI 训练平台
正式可视化：CV 目标检测、CV 分类、表格分类、NLP 文本分类、通用 JSON
手册版本：2026-08-27

## 1. 先说结论

推理脚本的业务字段可以自定义，但不是“写什么都能自动画图”。

- 必须：脚本正常退出，并在 `OUTPUT_DIR/result.json` 写 JSON 对象；
- 推荐：使用 `tss.inference.result/v1` 标准字段；
- 标准字段匹配时，平台展示专用图片、表格、置信度和分布图；
- 未匹配时，任务仍可成功，但页面只展示通用 JSON 和文件下载；
- 历史脚本继续兼容，平台不会因新增协议批量判失败。

## 2. ZIP 包最小结构

```text
inference-script.zip
├── infer.py
└── requirements.txt      # 可选
```

上传时填写：脚本名称、版本、运行环境、入口文件和参数 Schema。入口必须存在且不能使用绝对路径或 `..`。

不要把模型、输入数据、密码、Token 或云密钥打进脚本 ZIP。

## 3. 平台提供的环境变量

| 变量 | 含义 |
|---|---|
| `MODEL_DIR` | 本次模型目录（只读） |
| `INPUT_PATH` | 单文件或已展开的数据集输入路径 |
| `OUTPUT_DIR` | 本次输出目录 |
| `PARAMS_JSON` | 页面提交的脚本参数 JSON |
| `TASK_ID` | 推理任务 ID |
| `INPUT_MODE` | `SINGLE_OBJECT` 或 `DATASET_VERSION` |

脚本只读 `MODEL_DIR` 和 `INPUT_PATH`，所有新文件都写进 `OUTPUT_DIR`。

## 4. 最小入口

```python
import json
import os
from pathlib import Path

model_dir = Path(os.environ["MODEL_DIR"])
input_path = Path(os.environ["INPUT_PATH"])
output_dir = Path(os.environ["OUTPUT_DIR"])
params = json.loads(os.environ.get("PARAMS_JSON", "{}"))
output_dir.mkdir(parents=True, exist_ok=True)

# 加载模型并推理……

result = {
    "schemaVersion": "tss.inference.result/v1",
    "ok": True,
    "view": "unknown",
    "taskId": os.environ.get("TASK_ID"),
    "inputMode": os.environ.get("INPUT_MODE"),
    "summary": "推理完成"
}
(output_dir / "result.json").write_text(
    json.dumps(result, ensure_ascii=False, indent=2),
    encoding="utf-8"
)
```

## 5. 公共结果字段

| 字段 | 类型 | 规则 |
|---|---|---|
| `schemaVersion` | string | 推荐固定为 `tss.inference.result/v1` |
| `ok` | boolean | 业务结果是否生成成功 |
| `view` | string | 明确选择可视化类型 |
| `taskId` | string/null | 建议回写 `TASK_ID` |
| `inputMode` | string/null | 建议回写 `INPUT_MODE` |
| `summary` | string | 一句话摘要，可选 |
| `artifacts` | object | 相对 `OUTPUT_DIR` 的产物路径，可选 |
| `extra` | object | 业务自定义扩展字段，可选 |

自定义字段优先放入 `extra`，避免未来与公共字段重名。

## 6. 已正式支持的 view

| view | 当前效果 | 是否正式可用 |
|---|---|---|
| `image_detection` | 原图、标注图、框列表、类别、置信度 | 是 |
| `image_classification` | 预测样例、Top-K、分类指标/分布 | 是 |
| `table_classification` | 表格预测、概率和分类指标 | 是 |
| `text_classification` | 原始文本、真值、预测、置信度、Top-K、分布 | 是 |
| `unknown` | 通用 JSON 和产物下载 | 是，作为降级 |

`image_segmentation`、`image_keypoints`、`image_ocr`、`text_generation`、`text_retrieval`、`pointcloud`、`multimodal` 等名称已预留，但当前没有正式专用视图；使用时只保证通用降级，不应写进验收承诺。

## 7. CV 目标检测标准结构

```json
{
  "schemaVersion": "tss.inference.result/v1",
  "ok": true,
  "view": "image_detection",
  "imageCount": 1,
  "totalDetections": 1,
  "images": [
    {
      "image": "input.jpg",
      "width": 640,
      "height": 480,
      "inputPreview": {
        "kind": "image",
        "name": "input.jpg",
        "path": "previews/input-0.jpg"
      },
      "annotatedImage": "annotated/input.jpg",
      "labelFile": "labels/input.json",
      "detections": [
        {
          "classId": 0,
          "className": "person",
          "confidence": 0.93,
          "bbox": {"x1": 10, "y1": 20, "x2": 120, "y2": 220}
        }
      ]
    }
  ]
}
```

规则：

- 所有路径必须相对 `OUTPUT_DIR`，不能写 `/workspace/...`；
- `confidence` 使用 0～1；
- bbox 固定为 `x1/y1/x2/y2`；
- 零目标时也要保留 `detections: []`、`annotatedImage` 和 `inputPreview`，页面会正常显示“0 个目标”；
- 原图预览建议最长边不超过 512 像素、JPEG/PNG、小于 20 MiB。

## 8. NLP 文本分类标准结构

```json
{
  "schemaVersion": "tss.inference.result/v1",
  "ok": true,
  "view": "text_classification",
  "sampleCount": 1,
  "accuracy": 1.0,
  "precision": 1.0,
  "recall": 1.0,
  "f1": 1.0,
  "predictionsPreview": [
    {
      "index": 0,
      "inputPreview": {
        "kind": "text",
        "name": "sample-0.txt",
        "text": "短文本可直接放这里",
        "summary": "短文本可直接放这里"
      },
      "label": "正面",
      "prediction": "正面",
      "confidence": 0.97,
      "correct": true,
      "topKRecords": [
        {"label": "正面", "confidence": 0.97},
        {"label": "负面", "confidence": 0.03}
      ]
    }
  ],
  "labelCounts": {"正面": 1},
  "predictionCounts": {"正面": 1},
  "artifacts": {"predictionsJsonl": "predictions.jsonl"}
}
```

长文本不要全部塞进 `result.json`。建议：

```json
{
  "inputPreview": {
    "kind": "text",
    "name": "sample-12.txt",
    "summary": "前 120 字摘要……",
    "path": "previews/text/12.txt",
    "truncated": true
  }
}
```

页面按需读取文本；单个预览超过 2 MiB 时只提供下载。结果预览建议最多 50 条，完整结果写 JSONL/CSV 产物。

## 9. 原始输入“箱子”协议

`inputPreview` 是每条预测的统一原始输入容器：

| kind | 字段 | 页面行为 |
|---|---|---|
| `image` | `name`、`path` | 缩略图，点击放大，可下载 |
| `text` | `name`、`text`/`summary`/`path` | 摘要，点击弹窗，可下载 |
| `file` | `name`、`path` | 文件名和下载按钮 |

安全要求：

- `path` 只能指向本次 `OUTPUT_DIR` 下的相对文件；
- 禁止 `..`、反斜杠、绝对路径、外部 URL 和其他用户对象路径；
- 预览失败只降级当前一条，不应让整次推理失败；
- 输入本身敏感时，不要把完整内容复制进 `result.json`。

## 10. 产物和错误处理

平台会上传 `OUTPUT_DIR` 下全部文件。建议至少包含：

```text
OUTPUT_DIR/
├── result.json
├── predictions.jsonl
├── previews/
└── annotated/            # CV 可选
```

失败时：

1. 把便于用户理解的简短原因写到 stdout/stderr；
2. 让进程以非 0 退出；
3. 不把完整栈、密钥或输入隐私写入 `result.json`；
4. 不要捕获异常后强行返回成功。

如果 `result.json` 缺失，worker 会回传空对象；如果 JSON 损坏，会返回 `rawResultError`。这两种情况都无法得到正式可视化。

## 11. 参数 Schema

上传脚本时的参数 Schema 用于限制页面输入，例如：

```json
{
  "type": "object",
  "properties": {
    "threshold": {"type": "number", "minimum": 0, "maximum": 1, "default": 0.5},
    "batchSize": {"type": "integer", "minimum": 1, "maximum": 128, "default": 8}
  },
  "additionalProperties": false
}
```

脚本仍需自行处理缺省值和类型异常，不能假设所有历史调用者都传了最新字段。

## 12. 上线前最小验收

- [ ] 单文件和数据集两种输入至少验证一种正式路径；
- [ ] `result.json` 是 UTF-8 JSON 对象；
- [ ] `view` 与字段结构一致；
- [ ] 图片、短文本、长文本和普通文件按协议降级；
- [ ] 零检测结果不会显示“未识别”；
- [ ] 所有路径位于 `OUTPUT_DIR` 且无越权；
- [ ] 结果、日志和产物能由浏览器下载；
- [ ] 错误参数、缺模型、损坏输入和脚本异常均可定位；
- [ ] Job/Pod TTL 清理后，持久化结果仍可查看；
- [ ] 其他用户不能读取本任务预览或产物。
