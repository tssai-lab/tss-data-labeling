#!/usr/bin/env python3
"""Offline MiniRBT text-classification inference entrypoint for TSS."""

from __future__ import annotations

import json
import os
import traceback
from collections import Counter
from pathlib import Path
from typing import Any

INLINE_TEXT_PREVIEW_CHARS = 160
MAX_TEXT_PREVIEW_BYTES = 2 * 1024 * 1024
RESULT_PREVIEW_LIMIT = 50


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_params() -> dict[str, Any]:
    raw = os.environ.get("PARAMS_JSON", "").strip()
    if not raw:
        return {}
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("PARAMS_JSON must be a JSON object")
    return value


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict) or not isinstance(value.get("text"), str):
                raise ValueError(f"{path}:{line_number} must contain an object with text")
            text = value["text"].strip()
            if not text:
                raise ValueError(f"{path}:{line_number} text is empty")
            records.append(
                {
                    "id": value.get("id", f"line-{line_number}"),
                    "text": text,
                    "label": value.get("label"),
                }
            )
    if not records:
        raise ValueError(f"{path} has no text records")
    return records


def load_records(input_path: Path, split: str) -> list[dict[str, Any]]:
    if input_path.is_dir():
        split_file = input_path / "data" / f"{split}.jsonl"
        if split_file.is_file():
            return read_jsonl(split_file)
        candidates = sorted(input_path.rglob("*.jsonl"))
        if not candidates:
            raise FileNotFoundError(f"no JSONL input found under {input_path}")
        return read_jsonl(candidates[0])
    if input_path.suffix.lower() == ".jsonl":
        return read_jsonl(input_path)
    if input_path.suffix.lower() == ".txt":
        records = []
        for index, line in enumerate(input_path.read_text(encoding="utf-8").splitlines(), start=1):
            text = line.strip()
            if text:
                records.append({"id": f"line-{index}", "text": text, "label": None})
        if not records:
            raise ValueError("text input has no non-empty lines")
        return records
    if input_path.suffix.lower() == ".json":
        value = json.loads(input_path.read_text(encoding="utf-8"))
        values = value if isinstance(value, list) else [value]
        if not all(isinstance(item, dict) and isinstance(item.get("text"), str) for item in values):
            raise ValueError("JSON input must be an object or object list containing text")
        records = [
            {"id": item.get("id", f"row-{index}"), "text": item["text"].strip(), "label": item.get("label")}
            for index, item in enumerate(values, start=1)
            if item["text"].strip()
        ]
        if not records:
            raise ValueError("JSON input has no non-empty text records")
        return records
    raise ValueError("input must be a dataset directory or UTF-8 txt/json/jsonl file")


def find_model_root(model_dir: Path) -> Path:
    candidates = [model_dir] + sorted(path for path in model_dir.rglob("*") if path.is_dir())
    for candidate in candidates:
        has_weight = (candidate / "pytorch_model.bin").is_file() or any(
            candidate.glob("*.safetensors")
        )
        if (candidate / "config.json").is_file() and (candidate / "vocab.txt").is_file() and has_weight:
            return candidate
    raise FileNotFoundError("model archive does not contain config.json, vocab.txt and model weights")


def validate_inference_parameters(
    *, split: str, max_texts: int, batch_size: int, max_length: int
) -> None:
    if max_texts < 1 or max_texts > 5000:
        raise ValueError("maxTexts must be between 1 and 5000")
    if batch_size < 1 or batch_size > 256:
        raise ValueError("batchSize must be between 1 and 256")
    if max_length < 8 or max_length > 512:
        raise ValueError("maxSeqLength must be between 8 and 512")
    if (
        not split
        or len(split) > 64
        or any(not (character.isalnum() or character in "_-") for character in split)
    ):
        raise ValueError("split must contain only letters, numbers, underscore or hyphen")


def build_prediction_previews(
    predictions: list[dict[str, Any]], output_dir: Path
) -> list[dict[str, Any]]:
    """Keep small texts inline and place long originals in a bounded task-output box."""
    previews: list[dict[str, Any]] = []
    for position, prediction in enumerate(predictions[:RESULT_PREVIEW_LIMIT]):
        preview = dict(prediction)
        text = str(preview.pop("text", ""))
        name = f"原始文本 #{position + 1}"
        if len(text) <= INLINE_TEXT_PREVIEW_CHARS:
            preview["text"] = text
            preview["inputPreview"] = {
                "kind": "text",
                "name": name,
                "summary": text,
                "text": text,
                "truncated": False,
            }
        else:
            relative_path = Path("previews") / "text" / f"{position}.txt"
            absolute_path = output_dir / relative_path
            absolute_path.parent.mkdir(parents=True, exist_ok=True)
            stored_text, content_truncated = truncate_utf8_preview(text)
            absolute_path.write_text(stored_text, encoding="utf-8")
            preview["inputPreview"] = {
                "kind": "text",
                "name": name,
                "summary": text[:INLINE_TEXT_PREVIEW_CHARS] + "…",
                "path": relative_path.as_posix(),
                "truncated": True,
                "contentTruncated": content_truncated,
            }
        previews.append(preview)
    return previews


def truncate_utf8_preview(
    text: str, max_bytes: int = MAX_TEXT_PREVIEW_BYTES
) -> tuple[str, bool]:
    encoded = text.encode("utf-8")
    if len(encoded) <= max_bytes:
        return text, False
    return encoded[:max_bytes].decode("utf-8", errors="ignore"), True


def main() -> int:
    model_dir = Path(os.environ.get("MODEL_DIR", "/workspace/job/model"))
    input_path = Path(os.environ.get("INPUT_PATH", "/workspace/job/input"))
    output_dir = Path(os.environ.get("OUTPUT_DIR", "/workspace/job/output"))
    output_dir.mkdir(parents=True, exist_ok=True)
    params = read_params()
    split = str(params.get("split", "test"))
    max_texts = int(params.get("maxTexts", 200))
    batch_size = int(params.get("batchSize", 16))
    max_length = int(params.get("maxSeqLength", 64))
    validate_inference_parameters(
        split=split,
        max_texts=max_texts,
        batch_size=batch_size,
        max_length=max_length,
    )
    records = load_records(input_path, split)[:max_texts]

    import torch
    from sklearn.metrics import accuracy_score, precision_recall_fscore_support
    from transformers import BertForSequenceClassification, BertTokenizer

    root = find_model_root(model_dir)
    tokenizer = BertTokenizer.from_pretrained(root, local_files_only=True)
    model = BertForSequenceClassification.from_pretrained(root, local_files_only=True)
    model.eval()
    id_to_label = {
        int(key): str(value) for key, value in getattr(model.config, "id2label", {}).items()
    }
    predictions: list[dict[str, Any]] = []
    with torch.no_grad():
        for start in range(0, len(records), batch_size):
            batch = records[start : start + batch_size]
            encoded = tokenizer(
                [record["text"] for record in batch],
                padding=True,
                truncation=True,
                max_length=max_length,
                return_tensors="pt",
            )
            probabilities = torch.softmax(model(**encoded).logits, dim=-1)
            top_scores, top_ids = probabilities.topk(min(3, probabilities.shape[-1]), dim=-1)
            for record, scores, ids in zip(batch, top_scores.tolist(), top_ids.tolist(), strict=True):
                predicted = id_to_label.get(ids[0], str(ids[0]))
                truth = record.get("label")
                predictions.append(
                    {
                        "index": len(predictions),
                        "id": record.get("id"),
                        "text": record["text"],
                        "label": truth,
                        "prediction": predicted,
                        "confidence": round(float(scores[0]), 8),
                        "correct": truth == predicted if isinstance(truth, str) else None,
                        "topKRecords": [
                            {"label": id_to_label.get(label_id, str(label_id)), "confidence": round(float(score), 8)}
                            for score, label_id in zip(scores, ids, strict=True)
                        ],
                    }
                )

    with (output_dir / "predictions.jsonl").open("w", encoding="utf-8") as target:
        for prediction in predictions:
            target.write(json.dumps(prediction, ensure_ascii=False) + "\n")
    labeled = [record for record in predictions if isinstance(record.get("label"), str)]
    metrics: dict[str, float | None] = {
        "accuracy": None,
        "precision": None,
        "recall": None,
        "f1": None,
    }
    if labeled:
        y_true = [record["label"] for record in labeled]
        y_pred = [record["prediction"] for record in labeled]
        precision, recall, f1, _ = precision_recall_fscore_support(
            y_true, y_pred, average="macro", zero_division=0
        )
        metrics = {
            "accuracy": float(accuracy_score(y_true, y_pred)),
            "precision": float(precision),
            "recall": float(recall),
            "f1": float(f1),
        }
    first = predictions[0]
    prediction_previews = build_prediction_previews(predictions, output_dir)
    result = {
        "schemaVersion": "tss.inference.result/v1",
        "ok": True,
        "view": "text_classification",
        "taskId": os.environ.get("TASK_ID"),
        "inputMode": os.environ.get("INPUT_MODE"),
        "model": "MiniRBT-H288",
        "text": first["text"][:INLINE_TEXT_PREVIEW_CHARS],
        "label": first["prediction"],
        "score": first["confidence"],
        "sampleCount": len(predictions),
        **metrics,
        "labelCounts": dict(Counter(record["label"] for record in labeled)),
        "predictionCounts": dict(Counter(record["prediction"] for record in predictions)),
        "predictionsPreview": prediction_previews,
        "artifacts": {"predictionsJsonl": "predictions.jsonl"},
    }
    write_json(output_dir / "result.json", result)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        output_dir = Path(os.environ.get("OUTPUT_DIR", "/workspace/job/output"))
        write_json(
            output_dir / "result.json",
            {
                "schemaVersion": "tss.inference.result/v1",
                "ok": False,
                "view": "unknown",
                "error": f"{type(exc).__name__}: {exc}",
                "traceback": traceback.format_exc(limit=8),
            },
        )
        raise
