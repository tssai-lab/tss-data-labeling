"""Standalone model adapters. Read a request file and write candidates as JSON."""

import argparse
from contextlib import redirect_stdout
from functools import lru_cache

from energy_evidence import DEFAULT_RULES, day_candidates, energy_candidates, select_energy_candidates
import itertools
import json
import math
from pathlib import Path
import sys


def result(binding, kind, value):
    if kind == "choices":
        labels = value["choices"]
    else:
        labels = value[kind]
    if not labels or any(label not in binding["labels"] for label in labels):
        raise ValueError("Prediction contains a label outside the project configuration")
    return {
        "from_name": binding["fromName"], "to_name": binding["toName"],
        "type": kind, "value": value,
    }


def image_box(binding, label, box):
    x1, y1, x2, y2 = box
    if not (0 <= x1 < x2 <= 100 and 0 <= y1 < y2 <= 100):
        raise ValueError("Invalid normalized bounding box")
    return result(binding, "rectanglelabels", dict(
        x=x1, y=y1, width=x2-x1, height=y2-y1, rotation=0,
        rectanglelabels=[label],
    ))


@lru_cache(maxsize=1)
def yolo_model(weights):
    from ultralytics import YOLO

    return YOLO(weights)


def yolo(request):
    binding = request["binding"]
    mapping = request["classMap"]
    predictions = yolo_model(request["weights"]).predict(
        source=request["image"], device=request.get("device", "cpu"), verbose=False,
    )
    prediction, = predictions
    mode = request["mode"]
    if mode == "classification":
        label = mapping.get(prediction.names[prediction.probs.top1])
        return [result(binding, "choices", {"choices": [label]})] if label else []

    if mode not in {"detection", "segmentation", "pose"}:
        raise ValueError("Unsupported YOLO mode")
    candidates = []
    for index, box in enumerate(prediction.boxes):
        label = mapping.get(prediction.names[int(box.cls.item())])
        if mode == "pose":
            for point, name, visible in zip(
                prediction.keypoints.xyn[index].tolist(), request["keypointLabels"],
                prediction.keypoints.conf[index].tolist(), strict=True,
            ):
                if visible >= request["keypointThreshold"]:
                    candidates.append(result(binding, "keypointlabels", {
                        "x": 100 * point[0], "y": 100 * point[1], "width": 0.5,
                        "keypointlabels": [name],
                    }))
        elif label and mode == "segmentation":
            polygon = prediction.masks.xyn[index]
            if len(polygon) >= 3:
                candidates.append(result(binding, "polygonlabels", {
                    "points": [[100 * float(x), 100 * float(y)] for x, y in polygon],
                    "polygonlabels": [label],
                }))
        elif label:
            candidates.append(image_box(binding, label, [100 * x for x in box.xyxyn[0].tolist()]))
    return candidates


def grounded(request):
    import torch
    from PIL import Image
    from transformers import AutoModelForZeroShotObjectDetection, AutoProcessor

    binding = request["binding"]
    prompts = request["prompts"]
    image = Image.open(request["image"]).convert("RGB")
    device = request.get("device", "cpu")
    processor = AutoProcessor.from_pretrained(request["weights"], local_files_only=True)
    detector = AutoModelForZeroShotObjectDetection.from_pretrained(
        request["weights"], local_files_only=True,
    ).to(device).eval()
    sam = None
    if request.get("samWeights"):
        from ultralytics import SAM

        sam = SAM(request["samWeights"])
    candidates = []
    for prompt, label in prompts.items():
        phrase = prompt.strip().rstrip(".") + "."
        inputs = processor(images=image, text=phrase, return_tensors="pt").to(device)
        with torch.inference_mode():
            output = detector(**inputs)
        detections, = processor.post_process_grounded_object_detection(
            output, inputs.input_ids, threshold=request["boxThreshold"],
            target_sizes=[(image.height, image.width)],
        )
        for box in detections["boxes"].tolist():
            clipped = [
                min(max(x, 0), limit)
                for x, limit in zip(box, [image.width, image.height] * 2)
            ]
            x1, y1, x2, y2 = clipped
            if x1 >= x2 or y1 >= y2:
                continue
            if sam is None:
                candidates.append(image_box(binding, label, [
                    100*x1/image.width, 100*y1/image.height,
                    100*x2/image.width, 100*y2/image.height,
                ]))
                continue
            segmented, = sam.predict(image, bboxes=[clipped], device=device, verbose=False)
            if segmented.masks is None:
                continue
            for polygon in segmented.masks.xyn:
                if len(polygon) >= 3:
                    candidates.append(result(binding, "polygonlabels", {
                        "points": [[100 * float(x), 100 * float(y)] for x, y in polygon],
                        "polygonlabels": [label],
                    }))
    return candidates


@lru_cache(maxsize=1)
def prompted_model(weights, context_length, projector):
    from llama_cpp import Llama

    options = {"model_path": weights, "n_ctx": context_length, "verbose": False}
    if projector is not None:
        from llama_cpp.llama_chat_format import Qwen3VLChatHandler

        options["chat_handler"] = Qwen3VLChatHandler(clip_model_path=projector)
    return Llama(**options)


def energy_result(binding, times, selected, daily_label=None, daily_evidence=None):
    regions = prompted_intervals(binding, times, selected)
    daily = binding.get("daily")
    if not daily:
        return regions

    details = []
    for candidate in selected:
        label = candidate["label"]
        start, end = candidate["start"], candidate["end"]
        evidence = candidate["evidence"]
        interval = f"{start:02d}:00" if start == end else f"{start:02d}:00–{end:02d}:00"
        if "zeroHours" in evidence:
            basis = f"连续 {evidence['zeroHours']} 个小时值为 0 kWh"
        elif "missingHours" in evidence:
            basis = f"缺少 {evidence['missingHours']} 个小时值"
        else:
            basis = f"实测 {evidence['valueKwh']:.4g} kWh，比较阈值 {evidence['thresholdKwh']:.4g} kWh"
        details.append(f"{interval} {label}：{basis}")
    if daily_label == "待复核":
        summary = "日级状态待人工复核。"
        if daily_evidence:
            summary += " 可核查的日级依据：" + "；".join(
                f"{label}：{reason}" for label, reason in daily_evidence.items()
            ) + "。"
    else:
        summary = f"日级候选为「{daily_label}」：{daily_evidence[daily_label]}。"
    if details:
        summary += " 模型选中以下有数值依据的候选：" + "；".join(details) + "。"
    else:
        summary += " 本轮未选出有数值依据的区间候选。"
    return series_result(binding, regions, daily_label, summary)


def series_result(binding, regions, daily_label, rationale):
    daily = binding["daily"]
    if daily_label is None and daily.get("required", False):
        raise ValueError("Model did not return a required daily label")
    if not isinstance(rationale, str) or not rationale.strip():
        raise ValueError("Model did not provide a judgment rationale")
    day = [result(daily, "choices", {"choices": [daily_label]})] if daily_label is not None else []
    rationale = {
        "from_name": binding["rationale"],
        "to_name": binding["toName"],
        "type": "textarea",
        "value": {"text": [rationale.strip()]},
    }
    return [*day, *regions, rationale]


def prompted(request):
    binding = request["binding"]
    label_schema = {"type": "string", "enum": binding["labels"]}
    is_series = request.get("mode") == "timeseries"
    sample = request.get("sample")
    grounded_energy = is_series and "legacyEnergyRules" in request
    if is_series:
        if request.get("image"):
            raise ValueError("Time-series mode requires numeric input")
        times, values = request["times"], request["values"]
        if (not times or len(times) != len(values)
                or any(type(t) not in (int, float) or not math.isfinite(t) for t in times)
                or any(a >= b for a, b in zip(times, times[1:]))
                or any(v is not None and (type(v) not in (int, float) or not math.isfinite(v)) for v in values)):
            raise ValueError("Expected finite, increasing timestamps and numeric or null values")
        sample = {"times": times, "values": values, "context": sample}
        item_schema = {
            "type": "object",
            "properties": {
                "label": label_schema, "start": {"type": "number"}, "end": {"type": "number"},
            },
            "required": ["label", "start", "end"], "additionalProperties": False,
        }
        properties = {"intervals": {"type": "array", "items": item_schema}}
        instruction = (
            "Apply the project guidelines to the supplied readings and historical statistics. "
            "Return intervals using existing timestamps only, with start at or before end. "
            "A point uses the same start and end. Return an empty intervals array when there is "
            "insufficient evidence for any configured interval label. Treat null readings as missing. "
            "Do not invent missing readings, historical statistics, or operating causes."
        )
        if grounded_energy:
            rules = {**DEFAULT_RULES, **request["legacyEnergyRules"]}
            numeric_events = energy_candidates(times, values, request.get("sample"), rules)
            evidence = {key: event for key, event in numeric_events.items() if event["label"] in binding["labels"]}
            sample["eligibleCandidates"] = evidence
            sample["energyRules"] = rules
            properties = {"selectedIds": {
                "type": "array",
                "items": {"type": "string", "enum": list(evidence)} if evidence else {"type": "string"},
                **({} if evidence else {"maxItems": 0}),
            }}
            instruction = (
                "Select review candidates using only the provided eligibleCandidates IDs. "
                "The listed labels and hours already have numeric evidence. Do not generate new intervals "
                "or change boundaries. Treat energyRules as the frozen numeric criteria. "
                "Return an empty selectedIds array if no candidate should be reviewed."
            )
            daily = binding.get("daily")
            if daily:
                if "待复核" not in daily["labels"]:
                    raise ValueError("Legacy energy specification requires a day review label")
                daily_evidence = day_candidates(values, request.get("sample"), numeric_events, daily["labels"], rules)
                daily_labels = list(daily_evidence)
                daily_labels.append("待复核")
                sample["dailyEvidence"] = daily_evidence
                properties["dailyLabel"] = {"type": "string", "enum": daily_labels}
                instruction += (
                    " Also select one dailyLabel from the allowed values. Prefer the supported daily label "
                    "when its evidence is sufficient. Use 待复核 when evidence is insufficient or conflicting."
                )
                if not evidence and len(daily_labels) == 1:
                    return energy_result(binding, times, [], "待复核", daily_evidence)
            elif not evidence:
                return []
        else:
            sample.update(request.get("seriesMetadata", {}))
            daily = binding.get("daily")
            if daily:
                daily_label = {"type": "string", "enum": daily["labels"]}
                properties["dailyLabel"] = daily_label if daily.get("required") else {
                    "anyOf": [daily_label, {"type": "null"}],
                }
                properties["rationale"] = {"type": "string"}
                instruction += (
                    " Give the dailyLabel using the configured daily labels, and write the rationale "
                    "in the language of the guidelines and labels. Cite the supplied readings, hours "
                    "or statistics that support the daily label and any selected intervals. "
                    "If evidence is insufficient, explain why and use a configured review label if available."
                )
                if not daily.get("required"):
                    instruction += " If no daily label is supported and there is no review label, return null for dailyLabel."
    else:
        properties = {"label": label_schema}
        instruction = "Select one configured label. Return JSON with a single label field."
    prompt = {
        "task": instruction, "labels": binding["labels"],
        "guidelines": request["guidelines"], "sample": sample,
    }
    if is_series and not grounded_energy and binding.get("daily"):
        prompt["dailyLabels"] = binding["daily"]["labels"]
    if request.get("batchNotes"):
        prompt["task"] += " Apply the project guidelines as the annotation criteria. Use batchNotes only where consistent with those criteria."
        prompt["batchNotes"] = request["batchNotes"]
    content = json.dumps(prompt, ensure_ascii=False)
    if request.get("image"):
        import base64
        import mimetypes
        image = Path(request["image"])
        mime = mimetypes.guess_type(image)[0]
        if mime not in {"image/jpeg", "image/png"}:
            raise ValueError("Expected a JPEG or PNG image")
        encoded = base64.b64encode(image.read_bytes()).decode("ascii")
        content = [
            {"type": "text", "text": content},
            {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{encoded}"}},
        ]
    completion = {
        "messages": [{"role": "user", "content": content}],
        "temperature": 0,
        "response_format": {"type": "json_object", "schema": {
            "type": "object", "properties": properties,
            "required": list(properties), "additionalProperties": False,
        }},
    }
    if request.get("runtimeUrl"):
        from urllib.parse import urlsplit
        from urllib.request import Request, urlopen

        endpoint = urlsplit(request["runtimeUrl"])
        if (endpoint.scheme != "http" or endpoint.hostname not in {"127.0.0.1", "::1"}
                or endpoint.username or endpoint.path not in {"", "/"}
                or endpoint.query or endpoint.fragment):
            raise ValueError("Expected a loopback llama-server origin")
        body = json.dumps(completion, ensure_ascii=False, allow_nan=False).encode("utf-8")
        with urlopen(Request(
            request["runtimeUrl"].rstrip("/") + "/v1/chat/completions", body,
            {"Content-Type": "application/json"},
        ), timeout=180) as response_stream:
            response = json.load(response_stream)
    else:
        model = prompted_model(request["weights"], request.get("contextLength", 4096),
                               request["projector"] if request.get("image") else None)
        response = model.create_chat_completion(**completion)
    output = json.loads(response["choices"][0]["message"]["content"])
    if grounded_energy:
        selected = select_energy_candidates(evidence, output["selectedIds"])
        if binding.get("daily"):
            daily_label = output["dailyLabel"]
            if daily_label not in daily_labels:
                raise ValueError("Model selected a daily label without sufficient evidence")
            return energy_result(binding, times, selected, daily_label, daily_evidence)
        return energy_result(binding, times, selected)
    if is_series:
        regions = prompted_intervals(binding, times, output["intervals"])
        if binding.get("daily"):
            return series_result(binding, regions, output["dailyLabel"], output["rationale"])
        return regions
    return [result(binding, "choices", {"choices": [output["label"]]})]


def prompted_intervals(binding, times, predictions):
    if not isinstance(predictions, list):
        raise ValueError("Model intervals must be an array")
    timestamps = set(times)
    candidates = []
    seen = set()
    for item in predictions:
        if not isinstance(item, dict):
            raise ValueError("Model interval must be an object")
        start, end, label = item.get("start"), item.get("end"), item.get("label")
        if (type(start) not in (int, float) or type(end) not in (int, float)
                or start not in timestamps or end not in timestamps or start > end):
            raise ValueError("Interval must use ordered timestamps from the sample")
        if not isinstance(label, str) or label not in binding["labels"]:
            raise ValueError("Model returned an interval label outside the project configuration")
        key = (start, end, label)
        if key in seen:
            continue
        seen.add(key)
        candidates.append(result(binding, "timeserieslabels", {
            "start": start, "end": end, "instant": start == end,
            "timeserieslabels": [label],
        }))
    return candidates

def intervals(binding, times, labels):
    candidates = []
    position = 0
    for label, group in itertools.groupby(labels):
        length = sum(1 for _ in group)
        end = position + length - 1
        if label is not None:
            candidates.append(result(binding, "timeserieslabels", {
                "start": times[position], "end": times[end], "instant": length == 1,
                "timeserieslabels": [label],
            }))
        position = end + 1
    return candidates


def timeseries(request):
    import torch
    from torch import nn

    binding = request["binding"]
    times, rows = request["times"], request["values"]
    if not times or len(times) != len(rows) or not all(map(math.isfinite, times)):
        raise ValueError("Expected one numeric timestamp for each observation")
    if any(a >= b for a, b in zip(times, times[1:])):
        raise ValueError("Timestamps must increase strictly")
    checkpoint = torch.load(request["weights"], map_location="cpu", weights_only=True)
    labels = checkpoint["labels"]
    if any(label is not None and label not in binding["labels"] for label in labels):
        raise ValueError("LSTM checkpoint labels do not match the project")
    values = torch.tensor(rows, dtype=torch.float32)
    if values.ndim != 2 or values.shape[1] != checkpoint["inputSize"] or not torch.isfinite(values).all():
        raise ValueError("Expected a finite time-by-channel matrix matching the checkpoint")
    mean = torch.tensor(checkpoint["mean"])
    scale = torch.tensor(checkpoint["scale"])
    if mean.shape != values.shape[1:] or scale.shape != mean.shape or not (
        torch.isfinite(mean).all() and torch.isfinite(scale).all() and (scale > 0).all()
    ):
        raise ValueError("Invalid checkpoint normalization")
    lstm = nn.LSTM(checkpoint["inputSize"], checkpoint["hiddenSize"], batch_first=True)
    classifier = nn.Linear(checkpoint["hiddenSize"], len(labels))
    lstm.load_state_dict(checkpoint["lstm"])
    classifier.load_state_dict(checkpoint["classifier"])
    lstm.eval()
    classifier.eval()
    with torch.inference_mode():
        features, _ = lstm(((values - mean) / scale).unsqueeze(0))
        classes = classifier(features)[0].argmax(dim=-1).tolist()
    return intervals(binding, times, [labels[index] for index in classes])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", choices=["yolo", "grounded", "prompted", "timeseries"])
    parser.add_argument("request", type=Path)
    args = parser.parse_args()
    request = json.loads(args.request.read_text(encoding="utf-8-sig"))
    infer = {"yolo": yolo, "grounded": grounded, "prompted": prompted, "timeseries": timeseries}
    with redirect_stdout(sys.stderr):
        candidates = infer[args.model](request)
    if args.model in {"yolo", "grounded"}:
        from PIL import Image

        with Image.open(request["image"]) as image:
            width, height = image.size
        for candidate in candidates:
            if candidate["type"] != "choices":
                candidate.update(original_width=width, original_height=height)
    for index, candidate in enumerate(candidates):
        candidate["id"] = f"prediction-{index}"
    print(json.dumps({"annotations": candidates, "confidence": None}, ensure_ascii=False, allow_nan=False))


if __name__ == "__main__":
    main()
