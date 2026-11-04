#!/usr/bin/env python3
"""Inference script for image-text consistency fusion_model.pkl.

Platform environment variables:
- MODEL_DIR: directory containing fusion_model.pkl, often extracted from a model zip
- INPUT_PATH: JSON/JSONL/CSV file, or a dataset directory containing score JSONL files
- OUTPUT_DIR: directory where result.json and prediction artifacts are written
- PARAMS_JSON: optional JSON object, e.g. {"threshold": 0.5, "split": "test"}

Supported input formats:
1. Feature rows: JSON object/list, JSONL, or CSV with columns matching model["features"].
2. Raw score dataset directory: contains data/global_*.jsonl, data/region_*.jsonl, data/entity_*.jsonl.
"""

from __future__ import annotations

import csv
import json
import os
import pickle
import sys
import traceback
from pathlib import Path
from typing import Any


SCORE_FILES = {
    "train": {
        "global": "global_ultra_easy_v2_refreshed_v1_retrain_train_scores.jsonl",
        "region": "region_ultra_easy_v2_refreshed_v1_train_scores_v1.jsonl",
        "entity": "entity_det_ultra_easy_v2_refreshed_v1_train_scores_ocr.jsonl",
    },
    "val": {
        "global": "global_ultra_easy_v2_refreshed_v1_retrain_val_scores.jsonl",
        "region": "region_ultra_easy_v2_refreshed_v1_val_scores_v1.jsonl",
        "entity": "entity_det_ultra_easy_v2_refreshed_v1_val_scores_ocr.jsonl",
    },
    "test": {
        "global": "global_ultra_easy_v2_refreshed_v1_retrain_test_scores.jsonl",
        "region": "region_ultra_easy_v2_refreshed_v1_test_scores_v1.jsonl",
        "entity": "entity_det_ultra_easy_v2_refreshed_v1_test_scores_ocr.jsonl",
    },
}


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def read_params() -> dict[str, Any]:
    raw = os.environ.get("PARAMS_JSON", "").strip()
    if not raw:
        return {}
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("PARAMS_JSON must be a JSON object")
    return value


def find_model_file(model_dir: Path, requested: str | None = None) -> Path:
    if requested:
        candidate = model_dir / requested
        if candidate.is_file():
            return candidate
        matches = sorted(p for p in model_dir.rglob(requested) if p.is_file())
        if matches:
            return matches[0]
        raise FileNotFoundError(f"requested model file not found: {requested}")
    preferred = sorted(model_dir.rglob("fusion_model.pkl"))
    if preferred:
        return preferred[0]
    candidates = sorted(list(model_dir.rglob("*.pkl")) + list(model_dir.rglob("*.joblib")))
    if not candidates:
        files = [str(p.relative_to(model_dir)) for p in sorted(model_dir.rglob("*")) if p.is_file()]
        raise FileNotFoundError(f"No fusion_model.pkl found in MODEL_DIR. Files: {files[:80]}")
    return candidates[0]


def load_pickle_model(path: Path) -> dict[str, Any]:
    with path.open("rb") as file:
        bundle = pickle.load(file)
    if not isinstance(bundle, dict) or "model" not in bundle:
        raise ValueError("fusion model pickle must be a dict containing key 'model'")
    features = bundle.get("features")
    if not isinstance(features, list) or not all(isinstance(v, str) for v in features):
        raise ValueError("fusion model pickle must contain string list key 'features'")
    return bundle


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line_no, line in enumerate(file, start=1):
            line = line.strip()
            if not line:
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"{path} line {line_no} must be a JSON object")
            rows.append(value)
    return rows


def read_json_file(path: Path) -> list[dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(value, dict) and isinstance(value.get("rows"), list):
        value = value["rows"]
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list) and all(isinstance(item, dict) for item in value):
        return value
    raise ValueError(f"{path} must be a JSON object, object list, or {{rows:[...]}}")


def read_csv_file(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        return [dict(row) for row in csv.DictReader(file)]


def flatten_scalar_features(row: dict[str, Any], prefix: str) -> dict[str, Any]:
    out: dict[str, Any] = {"pair_id": row["pair_id"]}
    if "label" in row:
        out["label"] = row["label"]

    def visit(value: Any, name: str) -> None:
        if isinstance(value, bool):
            out[name] = int(value)
        elif isinstance(value, (int, float)) and not isinstance(value, bool):
            out[name] = value
        elif isinstance(value, dict):
            for key, child in value.items():
                visit(child, f"{name}_{key}")

    for key, value in row.items():
        if key in {"pair_id", "label", "image_path", "entities", "entities_typed", "per_entity"}:
            continue
        visit(value, f"{prefix}_{key}")
    return out


def data_dir_for(input_path: Path) -> Path:
    if (input_path / "data").is_dir():
        return input_path / "data"
    if input_path.name == "data" and input_path.is_dir():
        return input_path
    return input_path


def load_raw_score_split(input_path: Path, split: str) -> list[dict[str, Any]]:
    data_dir = data_dir_for(input_path)
    sources = SCORE_FILES.get(split)
    if not sources:
        raise ValueError(f"unsupported split: {split}; expected one of {sorted(SCORE_FILES)}")
    frames: list[dict[str, dict[str, Any]]] = []
    for source, filename in sources.items():
        path = data_dir / filename
        if not path.exists():
            raise FileNotFoundError(f"missing score file for split={split}: {path}")
        items = {}
        for row in read_jsonl(path):
            flat = flatten_scalar_features(row, source)
            label = flat.get("label")
            key = f"{flat['pair_id']}\t{label}"
            items[key] = flat
        frames.append(items)
    common_keys = set(frames[0])
    for frame in frames[1:]:
        common_keys &= set(frame)
    rows: list[dict[str, Any]] = []
    for key in sorted(common_keys):
        merged: dict[str, Any] = {}
        for frame in frames:
            for name, value in frame[key].items():
                if name not in merged:
                    merged[name] = value
        rows.append(merged)
    return rows


def find_input_files(input_path: Path) -> list[Path]:
    if input_path.is_file():
        return [input_path]
    files = []
    for ext in ("*.jsonl", "*.json", "*.csv"):
        files.extend(sorted(input_path.rglob(ext)))
    ignored_names = {"metrics.json", "pairs_binary_ultra_easy_v2_stats.json"}
    return [p for p in files if p.name not in ignored_names]


def load_feature_rows(input_path: Path, params: dict[str, Any]) -> list[dict[str, Any]]:
    input_kind = str(params.get("inputKind", "auto")).lower()
    split = str(params.get("split", "test"))
    if input_kind in {"raw_scores", "dataset"}:
        return load_raw_score_split(input_path, split)
    if input_kind == "auto" and input_path.is_dir():
        try:
            return load_raw_score_split(input_path, split)
        except FileNotFoundError:
            pass

    rows: list[dict[str, Any]] = []
    for path in find_input_files(input_path):
        suffix = path.suffix.lower()
        if suffix == ".jsonl":
            rows.extend(read_jsonl(path))
        elif suffix == ".json":
            rows.extend(read_json_file(path))
        elif suffix == ".csv":
            rows.extend(read_csv_file(path))
    if not rows:
        raise FileNotFoundError(f"No JSON/JSONL/CSV feature rows found under {input_path}")
    return rows


def numeric_or_none(value: Any) -> float | None:
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        return float(int(value))
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(str(value).strip())
    except ValueError:
        return None


def predict_positive_probability(model: Any, frame: Any) -> list[float]:
    clf = getattr(model, "named_steps", {}).get("clf") if hasattr(model, "named_steps") else model
    if hasattr(model, "predict_proba") and hasattr(clf, "predict_proba"):
        return [float(v) for v in model.predict_proba(frame)[:, 1]]
    if hasattr(model, "decision_function"):
        import numpy as np

        scores = model.decision_function(frame)
        return [float(v) for v in (1.0 / (1.0 + np.exp(-scores)))]
    return [float(v) for v in model.predict(frame)]


def main() -> int:
    try:
        import pandas as pd
    except Exception as exc:
        raise RuntimeError("Missing dependency: pandas is required for fusion_model inference") from exc
    try:
        import sklearn  # noqa: F401
    except Exception as exc:
        raise RuntimeError("Missing dependency: scikit-learn is required to load fusion_model.pkl") from exc

    model_dir = Path(os.environ.get("MODEL_DIR", "/workspace/job/model"))
    input_path = Path(os.environ.get("INPUT_PATH", "/workspace/job/input"))
    output_dir = Path(os.environ.get("OUTPUT_DIR", "/workspace/job/output"))
    output_dir.mkdir(parents=True, exist_ok=True)

    params = read_params()
    model_file = find_model_file(model_dir, params.get("modelFile"))
    bundle = load_pickle_model(model_file)
    model = bundle["model"]
    features: list[str] = bundle["features"]
    threshold = float(params.get("threshold", bundle.get("threshold", 0.5)))
    max_rows = int(params.get("maxRows", 0) or 0)
    id_field = str(params.get("idField", "pair_id"))

    rows = load_feature_rows(input_path, params)
    if max_rows > 0:
        rows = rows[:max_rows]

    matrix = []
    missing_by_feature = {feature: 0 for feature in features}
    for row in rows:
        values = []
        for feature in features:
            value = numeric_or_none(row.get(feature))
            if value is None:
                missing_by_feature[feature] += 1
            values.append(value)
        matrix.append(values)

    frame = pd.DataFrame(matrix, columns=features)
    probabilities = predict_positive_probability(model, frame)
    predictions = [1 if prob >= threshold else 0 for prob in probabilities]

    records = []
    for idx, row in enumerate(rows):
        label = row.get("label")
        record = {
            "index": idx,
            "id": row.get(id_field, row.get("pair_id", idx)),
            "probability": round(probabilities[idx], 8),
            "prediction": predictions[idx],
        }
        if label is not None:
            record["label"] = int(float(label))
            record["correct"] = int(record["label"] == predictions[idx])
        records.append(record)

    csv_path = output_dir / "predictions.csv"
    jsonl_path = output_dir / "predictions.jsonl"
    with csv_path.open("w", encoding="utf-8", newline="") as file:
        fieldnames = ["index", "id", "probability", "prediction", "label", "correct"]
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        for record in records:
            writer.writerow({key: record.get(key, "") for key in fieldnames})
    with jsonl_path.open("w", encoding="utf-8") as file:
        for record in records:
            file.write(json.dumps(record, ensure_ascii=False) + "\n")

    positives = sum(predictions)
    labeled = [r for r in records if "correct" in r]
    result = {
        "schemaVersion": "tss.inference.result/v1",
        "ok": True,
        "view": "table_classification",
        "taskId": os.environ.get("TASK_ID"),
        "inputMode": os.environ.get("INPUT_MODE"),
        "modelFile": str(model_file),
        "modelType": type(model).__name__,
        "featureCount": len(features),
        "rowCount": len(records),
        "threshold": threshold,
        "positiveCount": positives,
        "negativeCount": len(records) - positives,
        "accuracy": round(sum(r["correct"] for r in labeled) / len(labeled), 8) if labeled else None,
        "missingFeatureCount": sum(1 for value in missing_by_feature.values() if value > 0),
        "topMissingFeatures": [
            {"feature": feature, "missingRows": count}
            for feature, count in sorted(missing_by_feature.items(), key=lambda item: item[1], reverse=True)[:10]
            if count > 0
        ],
        "artifacts": {
            "predictionsCsv": "predictions.csv",
            "predictionsJsonl": "predictions.jsonl",
        },
        "preview": records[:20],
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
                "ok": False,
                "error": f"{type(exc).__name__}: {exc}",
                "traceback": traceback.format_exc(limit=8),
            },
        )
        print(traceback.format_exc(), file=sys.stderr)
        raise
