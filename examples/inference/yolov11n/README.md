# YOLOv11n 推理脚本

前端上传脚本 ZIP 时使用：

- `scriptName`: `yolov11n-infer`
- `version`: `v1`
- `runtime`: `PYTHON3`
- `entryFile`: `infer.py`
- `paramsSchemaJson`:

```json
{
  "type": "object",
  "properties": {
    "conf": { "type": "number", "default": 0.25 },
    "iou": { "type": "number", "default": 0.7 },
    "imgsz": { "type": "integer", "default": 640 },
    "max_det": { "type": "integer", "default": 300 },
    "device": { "type": "string", "default": "cpu" },
    "modelFile": { "type": "string" }
  }
}
```

创建推理任务时的 `params` 示例：

```json
{
  "conf": 0.25,
  "iou": 0.7,
  "imgsz": 640,
  "max_det": 300,
  "device": "cpu"
}
```

模型 ZIP 里建议包含一个 `.pt` 文件，例如：

```text
yolo11n.pt
```

如果是训练产出的模型，也可以是：

```text
best.pt
```

注意：当前推理 worker 镜像需要包含 `ultralytics`、`torch`、`opencv-python-headless` 才能运行真实 YOLO 推理。
