#!/usr/bin/env python3
"""Offline MiniRBT-H288 sequence-classification training entrypoint."""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import zipfile
from pathlib import Path
from typing import Any


DATASET_SCHEMA = "tss.dataset.nlp.text-classification/v1"
MODEL_SCHEMA = "tss.model.nlp.bert-sequence-classification/v1"
SPLITS = ("train", "validation", "test")
GPU_MEMORY_LIMIT_ENV = "TSS_GPU_MEMORY_LIMIT_MIB"


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} must contain a JSON object")
    return value


def read_jsonl(path: Path) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number} must be a JSON object")
            record: dict[str, str] = {}
            for field in ("id", "text", "label"):
                item = value.get(field)
                if not isinstance(item, str) or not item.strip():
                    raise ValueError(f"{path}:{line_number} field {field} must be a non-empty string")
                record[field] = item.strip()
            records.append(record)
    if not records:
        raise ValueError(f"{path} has no records")
    return records


def load_dataset_package(data_dir: Path) -> tuple[list[str], dict[str, list[dict[str, str]]]]:
    manifest = read_json(data_dir / "dataset.json")
    if manifest.get("schemaVersion") != DATASET_SCHEMA:
        raise ValueError(f"dataset schemaVersion must be {DATASET_SCHEMA}")
    labels = manifest.get("labels")
    labels_are_valid = (
        isinstance(labels, list)
        and len(labels) >= 2
        and all(isinstance(label, str) and label.strip() for label in labels)
    )
    if not labels_are_valid:
        raise ValueError("dataset labels must contain at least two non-empty strings")
    normalized_labels = [label.strip() for label in labels]
    if len(set(normalized_labels)) != len(normalized_labels):
        raise ValueError("dataset labels must be unique")

    result: dict[str, list[dict[str, str]]] = {}
    seen_ids: set[str] = set()
    for split in SPLITS:
        records = read_jsonl(data_dir / "data" / f"{split}.jsonl")
        for record in records:
            if record["label"] not in normalized_labels:
                raise ValueError(
                    f"{split} record {record['id']} has unknown label {record['label']}"
                )
            if record["id"] in seen_ids:
                raise ValueError(f"duplicate record id across dataset: {record['id']}")
            seen_ids.add(record["id"])
        result[split] = records

    train_labels = {record["label"] for record in result["train"]}
    missing = [label for label in normalized_labels if label not in train_labels]
    if missing:
        raise ValueError(f"training split does not cover labels: {missing}")
    return normalized_labels, result


def limit_records(records: list[dict[str, str]], maximum: int, labels: list[str], split: str) -> list[dict[str, str]]:
    limited = records if maximum <= 0 else records[:maximum]
    if not limited:
        raise ValueError(f"{split} is empty after applying sample limit")
    if split == "train":
        present = {record["label"] for record in limited}
        missing = [label for label in labels if label not in present]
        if missing:
            raise ValueError(f"maxTrainSamples removes training labels: {missing}")
    return limited


def metric_values(y_true: list[int], y_pred: list[int]) -> dict[str, float]:
    from sklearn.metrics import accuracy_score, precision_recall_fscore_support

    precision, recall, f1, _ = precision_recall_fscore_support(
        y_true, y_pred, average="macro", zero_division=0
    )
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
    }


def emit_event(event: dict[str, Any]) -> None:
    print("TSS_EVENT " + json.dumps(event, ensure_ascii=False), flush=True)


def build_training_event_metrics(train_loss: float, validation_metrics: dict[str, float]) -> dict[str, float]:
    """Map MiniRBT metrics to the platform's documented canonical names."""
    return {
        "train_loss": float(train_loss),
        "val_accuracy": float(validation_metrics["accuracy"]),
        "val_precision": float(validation_metrics["precision"]),
        "val_recall": float(validation_metrics["recall"]),
        "val_f1": float(validation_metrics["f1"]),
    }


def resolve_torch_device(torch_module: Any, requested: str) -> Any:
    normalized = requested.strip().lower()
    if normalized == "cpu":
        return torch_module.device("cpu")
    if normalized not in {"0", "cuda:0"}:
        raise ValueError("device must be cpu or the single visible CUDA device 0")
    if not torch_module.cuda.is_available():
        raise RuntimeError("CUDA device 0 was requested but CUDA is unavailable")
    visible_devices = int(torch_module.cuda.device_count())
    if visible_devices != 1:
        raise RuntimeError(
            "single-GPU acceptance requires exactly one visible CUDA device, "
            f"got {visible_devices}"
        )
    torch_module.cuda.set_device(0)
    return torch_module.device("cuda:0")


def apply_gpu_memory_budget(torch_module: Any, requested_device: str, raw_budget: str | None = None) -> int | None:
    """Apply a soft limit to the standard PyTorch process caching allocator."""
    value = os.environ.get(GPU_MEMORY_LIMIT_ENV) if raw_budget is None else raw_budget
    if value is None or not value.strip():
        return None
    if requested_device.strip().lower() == "cpu":
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


def configure_reproducibility(torch_module: Any, seed: int, device: Any) -> None:
    torch_module.manual_seed(seed)
    if getattr(device, "type", str(device).split(":", 1)[0]) != "cuda":
        return
    torch_module.cuda.manual_seed_all(seed)
    cudnn = getattr(getattr(torch_module, "backends", None), "cudnn", None)
    if cudnn is not None:
        cudnn.benchmark = False
        cudnn.deterministic = True


def validate_training_parameters(*, epochs: int, batch_size: int, learning_rate: float, weight_decay: float, max_length: int, seed: int, max_train: int, max_eval: int) -> None:
    ranges = {
        "epochs": (epochs, 1, 20),
        "batchSize": (batch_size, 1, 64),
        "lr": (learning_rate, 0.000001, 0.01),
        "weightDecay": (weight_decay, 0, 1),
        "maxSeqLength": (max_length, 8, 512),
        "seed": (seed, 0, 2147483647),
        "maxTrainSamples": (max_train, 0, 200000),
        "maxEvalSamples": (max_eval, 0, 200000),
    }
    for name, (value, minimum, maximum) in ranges.items():
        if not math.isfinite(value) or value < minimum or value > maximum:
            raise ValueError(f"{name} must be between {minimum} and {maximum}")


def write_predictions(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(
            target,
            fieldnames=["id", "text", "label", "prediction", "confidence", "correct"],
        )
        writer.writeheader()
        writer.writerows(rows)


def zip_model(model_dir: Path, output: Path) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(path for path in model_dir.rglob("*") if path.is_file()):
            archive.write(file, arcname=file.relative_to(model_dir).as_posix())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--params-file", type=Path, required=True)
    parser.add_argument("--device", default="cpu")
    args = parser.parse_args()

    requested_device = args.device.strip().lower()
    if requested_device not in {"cpu", "0", "cuda:0"}:
        raise ValueError("device must be cpu or the single visible CUDA device 0")
    if requested_device != "cpu":
        os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    params = read_json(args.params_file)
    epochs = int(params.get("epochs", 2))
    batch_size = int(params.get("batchSize", 8))
    learning_rate = float(params.get("lr", 0.0001))
    weight_decay = float(params.get("weightDecay", 0.01))
    max_length = int(params.get("maxSeqLength", 64))
    seed = int(params.get("seed", 42))
    max_train = int(params.get("maxTrainSamples", 0))
    max_eval = int(params.get("maxEvalSamples", 0))
    validate_training_parameters(
        epochs=epochs,
        batch_size=batch_size,
        learning_rate=learning_rate,
        weight_decay=weight_decay,
        max_length=max_length,
        seed=seed,
        max_train=max_train,
        max_eval=max_eval,
    )

    random.seed(seed)
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    import torch
    from torch.utils.data import DataLoader, TensorDataset
    from transformers import BertForSequenceClassification, BertTokenizer

    device = resolve_torch_device(torch, requested_device)
    apply_gpu_memory_budget(torch, requested_device)
    configure_reproducibility(torch, seed, device)
    torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
    labels, splits = load_dataset_package(args.data_dir)
    splits["train"] = limit_records(splits["train"], max_train, labels, "train")
    splits["validation"] = limit_records(
        splits["validation"], max_eval, labels, "validation"
    )
    splits["test"] = limit_records(splits["test"], max_eval, labels, "test")
    label_to_id = {label: index for index, label in enumerate(labels)}
    id_to_label = {index: label for label, index in label_to_id.items()}

    tokenizer = BertTokenizer.from_pretrained(args.model_dir, local_files_only=True)
    model = BertForSequenceClassification.from_pretrained(
        args.model_dir,
        num_labels=len(labels),
        label2id=label_to_id,
        id2label=id_to_label,
        local_files_only=True,
    )
    model.to(device)

    def loader(records: list[dict[str, str]], shuffle: bool) -> DataLoader:
        encoded = tokenizer(
            [record["text"] for record in records],
            padding=True,
            truncation=True,
            max_length=max_length,
            return_tensors="pt",
        )
        labels_tensor = torch.tensor(
            [label_to_id[record["label"]] for record in records], dtype=torch.long
        )
        dataset = TensorDataset(
            encoded["input_ids"], encoded["attention_mask"], labels_tensor
        )
        generator = torch.Generator().manual_seed(seed)
        return DataLoader(
            dataset, batch_size=batch_size, shuffle=shuffle, generator=generator
        )

    train_loader = loader(splits["train"], True)
    validation_loader = loader(splits["validation"], False)
    optimizer = torch.optim.AdamW(
        model.parameters(), lr=learning_rate, weight_decay=weight_decay
    )

    def evaluate(records: list[dict[str, str]], data_loader: DataLoader) -> tuple[dict[str, float], list[dict[str, Any]]]:
        model.eval()
        predictions: list[int] = []
        truths: list[int] = []
        confidences: list[float] = []
        with torch.no_grad():
            for input_ids, attention_mask, batch_labels in data_loader:
                outputs = model(
                    input_ids=input_ids.to(device),
                    attention_mask=attention_mask.to(device),
                )
                probabilities = torch.softmax(outputs.logits, dim=-1)
                confidence, predicted = probabilities.max(dim=-1)
                predictions.extend(predicted.cpu().tolist())
                confidences.extend(confidence.cpu().tolist())
                truths.extend(batch_labels.tolist())
        rows = []
        evaluated_rows = zip(
            records, truths, predictions, confidences, strict=True
        )
        for record, truth, predicted, confidence in evaluated_rows:
            rows.append(
                {
                    "id": record["id"],
                    "text": record["text"],
                    "label": id_to_label[truth],
                    "prediction": id_to_label[predicted],
                    "confidence": round(float(confidence), 8),
                    "correct": truth == predicted,
                }
            )
        return metric_values(truths, predictions), rows

    train_loss_history: list[float] = []
    validation_metrics: dict[str, float] = {}
    for epoch in range(1, epochs + 1):
        model.train()
        total_loss = 0.0
        steps = 0
        for input_ids, attention_mask, batch_labels in train_loader:
            optimizer.zero_grad(set_to_none=True)
            outputs = model(
                input_ids=input_ids.to(device),
                attention_mask=attention_mask.to(device),
                labels=batch_labels.to(device),
            )
            outputs.loss.backward()
            optimizer.step()
            total_loss += float(outputs.loss.item())
            steps += 1
        train_loss = total_loss / max(steps, 1)
        train_loss_history.append(train_loss)
        validation_metrics, _ = evaluate(splits["validation"], validation_loader)
        event_metrics = build_training_event_metrics(train_loss, validation_metrics)
        emit_event({"type": "metric", "step": epoch, "metrics": event_metrics})
        emit_event({"type": "progress", "progress": round(epoch * 100 / epochs)})

    validation_metrics, validation_rows = evaluate(
        splits["validation"], validation_loader
    )
    test_metrics, test_rows = evaluate(splits["test"], loader(splits["test"], False))

    args.out_dir.mkdir(parents=True, exist_ok=True)
    saved_model_dir = args.out_dir / "model"
    saved_model_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(saved_model_dir, safe_serialization=False)
    tokenizer.save_pretrained(saved_model_dir)
    (saved_model_dir / "labels.json").write_text(
        json.dumps({"labels": labels}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (saved_model_dir / "model.yaml").write_text(
        f"schemaVersion: {MODEL_SCHEMA}\n"
        "baseModel: hfl/minirbt-h288\n"
        "purpose: sequence-classification\n"
        f"labels: {json.dumps(labels, ensure_ascii=False)}\n",
        encoding="utf-8",
    )
    zip_model(saved_model_dir, args.out_dir / "minirbt_text_classifier.zip")
    write_predictions(args.out_dir / "validation_predictions.csv", validation_rows)
    write_predictions(args.out_dir / "test_predictions.csv", test_rows)
    device_name = (
        torch.cuda.get_device_name(0)
        if getattr(device, "type", str(device).split(":", 1)[0]) == "cuda"
        else "CPU"
    )
    metrics = {
        "task": "chinese_text_classification",
        "model": "MiniRBT-H288",
        "device": str(device),
        "deviceName": device_name,
        "visibleCudaDevices": int(torch.cuda.device_count()) if str(device).startswith("cuda") else 0,
        "epochs": epochs,
        "batchSize": batch_size,
        "learningRate": learning_rate,
        "seed": seed,
        "train": {"loss": train_loss_history[-1], "lossHistory": train_loss_history},
        "validation": validation_metrics,
        "test": test_metrics,
        "samples": {split: len(records) for split, records in splits.items()},
    }
    metrics.update(build_training_event_metrics(train_loss_history[-1], validation_metrics))
    (args.out_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(metrics, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
