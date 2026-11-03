#!/usr/bin/env python3
"""Reference entrypoint for the yolo_object_detection TrainingPlan.

The platform places model/data/code/output in fixed directories and passes every
user parameter through params.json. This script emits the documented TSS_EVENT
lines and produces the artifacts declared in yolo_object_detection-v1.yaml.

进度 / 指标事件协议（重要）：
- 平台会逐行读取本脚本的 stdout，识别以 "TSS_EVENT " 开头、后面跟合法 JSON 的行。
- progress 必须是 0~100 的数值（完成度百分比，不是 epoch 数）。
- 必须 flush（print 加 flush=True），否则 stdout 缓冲会导致事件延迟/丢失，进度条看起来卡住。
- 每次打印必须独占一行，行首必须是 "TSS_EVENT "。
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import shutil
from pathlib import Path
from typing import Any


YOLO_METRIC_NAMES = (
    ("metrics/mAP50(B)", "val_mAP50"),
    ("metrics/mAP50-95(B)", "val_mAP50_95"),
    ("metrics/precision(B)", "val_precision"),
    ("metrics/recall(B)", "val_recall"),
)
GPU_MEMORY_LIMIT_ENV = "TSS_GPU_MEMORY_LIMIT_MIB"


def event(payload: dict) -> None:
    """向平台上报一条 TSS_EVENT 事件（进度 / 指标），打印后立即 flush。"""
    print("TSS_EVENT " + json.dumps(payload, ensure_ascii=False), flush=True)


def finite_number(value: Any) -> float | None:
    try:
        if hasattr(value, "item"):
            value = value.item()
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def apply_gpu_memory_budget(
    torch_module: Any,
    requested_device: str,
    raw_budget: str | None = None,
) -> int | None:
    """Apply the platform's optional per-process PyTorch allocator budget."""
    value = os.environ.get(GPU_MEMORY_LIMIT_ENV) if raw_budget is None else raw_budget
    if value is None or not value.strip():
        return None
    normalized_device = requested_device.strip().lower()
    if normalized_device == "cpu":
        raise ValueError(f"{GPU_MEMORY_LIMIT_ENV} cannot be used with CPU training")
    try:
        budget_mib = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{GPU_MEMORY_LIMIT_ENV} must be a positive integer") from exc
    if budget_mib <= 0:
        raise ValueError(f"{GPU_MEMORY_LIMIT_ENV} must be a positive integer")
    if not torch_module.cuda.is_available() or int(torch_module.cuda.device_count()) != 1:
        raise RuntimeError("GPU memory budget requires exactly one visible CUDA device")
    total_bytes = int(torch_module.cuda.get_device_properties(0).total_memory)
    budget_bytes = budget_mib * 1024 * 1024
    if total_bytes <= 0 or budget_bytes > total_bytes:
        total_mib = total_bytes // (1024 * 1024)
        raise ValueError(
            f"{GPU_MEMORY_LIMIT_ENV}={budget_mib} exceeds visible GPU memory {total_mib} MiB"
        )
    torch_module.cuda.set_per_process_memory_fraction(budget_bytes / total_bytes, 0)
    return budget_mib


def trainer_metric_snapshot(trainer: Any) -> dict[str, float]:
    """Read the completed epoch values exposed by Ultralytics."""
    snapshot: dict[str, float] = {}
    losses = getattr(trainer, "tloss", None)
    if losses is not None:
        try:
            raw_losses = list(losses)
        except TypeError:
            raw_losses = [losses]
        loss_values = [finite_number(value) for value in raw_losses]
        finite_losses = [value for value in loss_values if value is not None]
        if finite_losses:
            snapshot["train_loss"] = sum(finite_losses)

    metrics = getattr(trainer, "metrics", None) or {}
    for source, target in YOLO_METRIC_NAMES:
        value = finite_number(metrics.get(source))
        if value is not None:
            snapshot[target] = value
    return snapshot


def on_epoch_end(trainer: Any, emitted: dict[int, set[str]] | None = None) -> None:
    """Report one completed epoch using the documented step-based protocol."""
    epoch = int(getattr(trainer, "epoch", 0) + 1)
    total = int(getattr(trainer, "epochs", 1) or 1)
    pct = max(0, min(100, round(epoch * 100.0 / total)))
    event({"type": "progress", "progress": pct})
    snapshot = trainer_metric_snapshot(trainer)
    if snapshot:
        event({"type": "metric", "step": epoch, "metrics": snapshot})
        if emitted is not None:
            emitted.setdefault(epoch, set()).update(snapshot)


def result_history(results_csv: Path) -> list[tuple[int, dict[str, float]]]:
    """Convert Ultralytics results.csv rows to platform metric events."""
    if not results_csv.is_file():
        return []
    with results_csv.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))

    history: list[tuple[int, dict[str, float]]] = []
    for step, row in enumerate(rows, start=1):
        metrics: dict[str, float] = {}
        loss_values = [
            finite_number(row.get(name))
            for name in ("train/box_loss", "train/cls_loss", "train/dfl_loss")
        ]
        finite_losses = [value for value in loss_values if value is not None]
        if finite_losses:
            metrics["train_loss"] = sum(finite_losses)
        for source, target in YOLO_METRIC_NAMES:
            value = finite_number(row.get(source))
            if value is not None:
                metrics[target] = value
        if metrics:
            history.append((step, metrics))
    return history


def resolve_data_manifest(data_yaml: Path, data_root: Path, output: Path) -> Path:
    """Pin relative YOLO dataset paths to the platform-mounted dataset root."""
    source_lines = data_yaml.read_text(encoding="utf-8").splitlines()
    content_lines = [
        line
        for line in source_lines
        if not (line == line.lstrip() and line.lstrip().startswith("path:"))
    ]
    resolved = output / "data.resolved.yaml"
    root_literal = json.dumps(str(data_root.resolve()), ensure_ascii=False)
    resolved.write_text(
        "\n".join([f"path: {root_literal}", *content_lines]) + "\n",
        encoding="utf-8",
    )
    return resolved


def package_primary_model(best_pt: Path, output: Path) -> Path:
    """Create the primary model archive required by the training plan."""
    archive = shutil.make_archive(
        str(output / "model"),
        "zip",
        root_dir=best_pt.parent,
        base_dir=best_pt.name,
    )
    return Path(archive)


def ensure_ultralytics_font(config_dir: Path | None = None, font_path: Path | None = None) -> Path:
    """Provide the font Ultralytics expects without opening external network."""
    target_dir = config_dir or (Path.home() / ".config" / "Ultralytics")
    target = target_dir / "Arial.ttf"
    if target.is_file():
        return target
    if font_path is None:
        from matplotlib import font_manager

        font_path = Path(font_manager.findfont("DejaVu Sans", fallback_to_default=True))
    if not font_path.is_file():
        raise FileNotFoundError("offline training font is unavailable in runtime image")
    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(font_path, target)
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--data-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--params-file", required=True)
    parser.add_argument("--device", default="0")
    args = parser.parse_args()

    if os.environ.get(GPU_MEMORY_LIMIT_ENV):
        import torch

        apply_gpu_memory_budget(torch, args.device)
    from ultralytics import YOLO

    params = json.loads(Path(args.params_file).read_text(encoding="utf-8"))
    mode = os.environ.get("TSS_TRAINING_MODE", "FULL_FINETUNE")
    model_path = Path(args.model_dir) / "yolo11n.pt"
    data_yaml = Path(args.data_dir) / "data.yaml"
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    if not data_yaml.is_file():
        raise FileNotFoundError(f"missing required dataset manifest: {data_yaml}")
    if mode != "FROM_SCRATCH" and not model_path.is_file():
        raise FileNotFoundError(f"missing required base model: {model_path}")
    resolved_data_yaml = resolve_data_manifest(data_yaml, Path(args.data_dir), output)
    ensure_ultralytics_font()

    event({"type": "progress", "progress": 2})
    model = YOLO(str(model_path) if mode != "FROM_SCRATCH" else "yolo11n.yaml")
    emitted: dict[int, set[str]] = {}
    # on_fit_epoch_end 在验证完成后触发，此时 train/val 指标属于同一个已完成 epoch。
    model.add_callback(
        "on_fit_epoch_end", lambda trainer: on_epoch_end(trainer, emitted)
    )
    event({"type": "progress", "progress": 8})
    result = model.train(
        data=str(resolved_data_yaml),
        epochs=int(params.get("epochs", 3)),
        batch=int(params.get("batch", 4)),
        imgsz=int(params.get("imgsz", 640)),
        lr0=float(params.get("learningRate", 0.001)),
        device=args.device,
        project=str(output / "ultralytics"),
        name="run",
        exist_ok=True,
        verbose=True,
    )
    event({"type": "progress", "progress": 90})
    run_dir = Path(result.save_dir)
    best_pt = output / "best.pt"
    published_pt = output / "yolo11n.pt"
    shutil.copy2(run_dir / "weights" / "best.pt", best_pt)
    shutil.copy2(best_pt, published_pt)
    shutil.copy2(run_dir / "weights" / "last.pt", output / "last.pt")
    package_primary_model(published_pt, output)
    metrics = {"trainingMode": mode, "epochs": int(params.get("epochs", 3))}
    results_csv = run_dir / "results.csv"
    history = result_history(results_csv)
    if history:
        metrics["resultsCsv"] = str(results_csv.name)
        for step, epoch_metrics in history:
            missing = {
                key: value
                for key, value in epoch_metrics.items()
                if key not in emitted.get(step, set())
            }
            if missing:
                event({"type": "metric", "step": step, "metrics": missing})
        metrics.update(history[-1][1])
    (output / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    event({"type": "progress", "progress": 100})


if __name__ == "__main__":
    main()
