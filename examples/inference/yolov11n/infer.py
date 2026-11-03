#!/usr/bin/env python3
"""YOLOv11n inference entry script for TSS Platform.

Expected platform environment variables:
- MODEL_DIR: directory containing uploaded model files, e.g. yolo11n.pt or best.pt
- INPUT_PATH: a single image file or a directory containing images
- OUTPUT_DIR: directory where this script writes result.json and artifacts
- PARAMS_JSON: free JSON params, e.g. {"conf":0.25,"iou":0.7,"imgsz":640}
- TASK_ID / INPUT_MODE: optional context values

The script requires the worker image to provide:
  ultralytics, torch, opencv-python-headless
"""

from __future__ import annotations

import json
import os
import sys
import traceback
from pathlib import Path
from typing import Any


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff"}
MODEL_EXTENSIONS = {".pt", ".onnx", ".engine", ".torchscript"}
INPUT_PREVIEW_LIMIT = 20
INPUT_PREVIEW_MAX_EDGE = 512
INPUT_PREVIEW_JPEG_QUALITY = 85


def read_json_env(name: str, default: dict[str, Any]) -> dict[str, Any]:
    raw = os.environ.get(name, "")
    if not raw.strip():
        return default
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError(f"{name} must be a JSON object")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def preview_files(root: Path, limit: int = 80) -> list[str]:
    if not root.exists():
        return []
    files = sorted(p for p in root.rglob("*") if p.is_file())
    return [str(p.relative_to(root)) for p in files[:limit]]


def find_model_file(model_dir: Path, requested: str | None = None) -> Path:
    if requested:
        candidate = model_dir / requested
        if candidate.exists() and candidate.is_file():
            return candidate
        matches = [p for p in model_dir.rglob(requested) if p.is_file()]
        if matches:
            return sorted(matches)[0]
        raise FileNotFoundError(f"requested model file not found: {requested}")

    candidates = [
        p
        for p in model_dir.rglob("*")
        if p.is_file() and p.suffix.lower() in MODEL_EXTENSIONS
    ]
    if not candidates:
        file_preview = preview_files(model_dir, limit=40)
        preview_text = f" Files found: {file_preview}" if file_preview else " No files found after extraction."
        raise FileNotFoundError(
            "No YOLO model file found in MODEL_DIR. "
            "Upload a model zip containing yolo11n.pt, yolov11n.pt, best.pt, or an ONNX/exported model."
            + preview_text
        )

    preferred_names = ("best.pt", "last.pt", "yolo11n.pt", "yolov11n.pt", "yolo11n.onnx", "yolov11n.onnx")
    lower_to_path = {p.name.lower(): p for p in candidates}
    for name in preferred_names:
        if name in lower_to_path:
            return lower_to_path[name]
    return sorted(candidates)[0]


def collect_images(input_path: Path) -> list[Path]:
    if input_path.is_file():
        return [input_path] if input_path.suffix.lower() in IMAGE_EXTENSIONS else []
    if input_path.is_dir():
        return sorted(
            p for p in input_path.rglob("*") if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
        )
    return []


def build_input_preview(
    image_path: Path,
    output_dir: Path,
    position: int,
) -> dict[str, Any] | None:
    """Write one bounded, metadata-free preview without affecting inference success."""
    if position < 0 or position >= INPUT_PREVIEW_LIMIT:
        return None

    relative_path = Path("previews") / "images" / f"{position:04d}.jpg"
    target_path = output_dir / relative_path
    try:
        from PIL import Image, ImageOps

        target_path.parent.mkdir(parents=True, exist_ok=True)
        with Image.open(image_path) as source:
            oriented = ImageOps.exif_transpose(source)
            try:
                if oriented.mode in {"RGBA", "LA"} or "transparency" in oriented.info:
                    rgba = oriented.convert("RGBA")
                    try:
                        preview = Image.new("RGB", rgba.size, "white")
                        preview.paste(rgba, mask=rgba.getchannel("A"))
                    finally:
                        rgba.close()
                else:
                    preview = oriented.convert("RGB")
                try:
                    preview.thumbnail(
                        (INPUT_PREVIEW_MAX_EDGE, INPUT_PREVIEW_MAX_EDGE),
                        Image.Resampling.LANCZOS,
                    )
                    preview.save(
                        target_path,
                        format="JPEG",
                        quality=INPUT_PREVIEW_JPEG_QUALITY,
                        optimize=True,
                    )
                finally:
                    preview.close()
            finally:
                if oriented is not source:
                    oriented.close()
        return {
            "kind": "image",
            "path": relative_path.as_posix(),
            "name": image_path.name,
        }
    except Exception:
        # Preview is optional. A corrupt image or missing Pillow must not turn a
        # successful prediction into a failed inference task.
        try:
            target_path.unlink(missing_ok=True)
        except OSError:
            pass
        return None


def box_to_record(box: Any, names: dict[int, str]) -> dict[str, Any]:
    cls_id = int(box.cls[0].item()) if box.cls is not None else -1
    conf = float(box.conf[0].item()) if box.conf is not None else 0.0
    xyxy = [float(v) for v in box.xyxy[0].tolist()]
    return {
        "classId": cls_id,
        "className": names.get(cls_id, str(cls_id)),
        "confidence": round(conf, 6),
        "bbox": {
            "x1": round(xyxy[0], 3),
            "y1": round(xyxy[1], 3),
            "x2": round(xyxy[2], 3),
            "y2": round(xyxy[3], 3),
        },
    }


def main() -> int:
    model_dir = Path(os.environ.get("MODEL_DIR", "/workspace/job/model"))
    input_path = Path(os.environ.get("INPUT_PATH", "/workspace/job/input"))
    output_dir = Path(os.environ.get("OUTPUT_DIR", "/workspace/job/output"))
    result_path = output_dir / "result.json"
    annotated_dir = output_dir / "annotated"
    labels_dir = output_dir / "labels"

    output_dir.mkdir(parents=True, exist_ok=True)
    annotated_dir.mkdir(parents=True, exist_ok=True)
    labels_dir.mkdir(parents=True, exist_ok=True)

    params = read_json_env("PARAMS_JSON", {})
    conf = float(params.get("conf", 0.25))
    iou = float(params.get("iou", 0.7))
    imgsz = int(params.get("imgsz", 640))
    max_det = int(params.get("max_det", 300))
    device = params.get("device")
    model_file = find_model_file(model_dir, params.get("modelFile"))
    images = collect_images(input_path)

    if not images:
        raise FileNotFoundError(
            f"No image files found under INPUT_PATH={input_path}. "
            f"Supported extensions: {sorted(IMAGE_EXTENSIONS)}"
        )

    try:
        from ultralytics import YOLO
    except Exception as exc:
        write_json(
            result_path,
            {
                "ok": False,
                "error": "Missing dependency: ultralytics",
                "detail": str(exc),
                "installHint": "Add ultralytics, torch and opencv-python-headless to k8s/inference-worker/requirements.txt and rebuild the worker image.",
            },
        )
        raise

    model = YOLO(str(model_file))
    names = getattr(model, "names", {}) or {}
    if isinstance(names, list):
        names = {idx: name for idx, name in enumerate(names)}

    image_results: list[dict[str, Any]] = []
    total_detections = 0

    for image_position, image in enumerate(images):
        kwargs = {
            "source": str(image),
            "conf": conf,
            "iou": iou,
            "imgsz": imgsz,
            "max_det": max_det,
            "verbose": False,
        }
        if device:
            kwargs["device"] = device

        predictions = model.predict(**kwargs)
        input_preview = build_input_preview(image, output_dir, image_position)
        for idx, prediction in enumerate(predictions):
            boxes = prediction.boxes or []
            detections = [box_to_record(box, names) for box in boxes]
            total_detections += len(detections)

            stem = image.stem if len(predictions) == 1 else f"{image.stem}-{idx}"
            annotated_path = annotated_dir / f"{stem}.jpg"
            label_path = labels_dir / f"{stem}.json"

            prediction.save(filename=str(annotated_path))
            write_json(label_path, {"image": str(image), "detections": detections})

            image_result = {
                "image": str(image),
                "width": int(getattr(prediction, "orig_shape", [0, 0])[1]),
                "height": int(getattr(prediction, "orig_shape", [0, 0])[0]),
                "detections": detections,
                "annotatedImage": str(annotated_path.relative_to(output_dir)),
                "labelFile": str(label_path.relative_to(output_dir)),
            }
            if input_preview is not None:
                image_result["inputPreview"] = input_preview
            image_results.append(image_result)

    write_json(
        result_path,
        {
            "schemaVersion": "tss.inference.result/v1",
            "ok": True,
            "view": "image_detection",
            "taskId": os.environ.get("TASK_ID"),
            "inputMode": os.environ.get("INPUT_MODE"),
            "modelFile": str(model_file),
            "imageCount": len(images),
            "totalDetections": total_detections,
            "params": {
                "conf": conf,
                "iou": iou,
                "imgsz": imgsz,
                "max_det": max_det,
                "device": device,
            },
            "images": image_results,
        },
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        output_dir = Path(os.environ.get("OUTPUT_DIR", "/workspace/job/output"))
        result_path = output_dir / "result.json"
        if not result_path.exists():
            model_dir = Path(os.environ.get("MODEL_DIR", "/workspace/job/model"))
            input_path = Path(os.environ.get("INPUT_PATH", "/workspace/job/input"))
            write_json(
                result_path,
                {
                    "schemaVersion": "tss.inference.result/v1",
                    "ok": False,
                    "view": "unknown",
                    "error": f"{type(exc).__name__}: {exc}",
                    "modelDir": str(model_dir),
                    "modelDirFiles": preview_files(model_dir),
                    "inputPath": str(input_path),
                    "inputFiles": preview_files(input_path) if input_path.is_dir() else ([input_path.name] if input_path.exists() else []),
                    "traceback": traceback.format_exc(limit=8),
                },
            )
        print(traceback.format_exc(), file=sys.stderr)
        raise
