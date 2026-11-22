"""Standalone model adapters. Read a request file and write candidates as JSON."""

import argparse
from contextlib import redirect_stdout
from functools import lru_cache
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


def prompted(request):
    binding = request["binding"]
    label_schema = {"type": "string", "enum": binding["labels"]}
    is_series = request.get("mode") == "timeseries"
    sample = request.get("sample")
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
            "Return intervals using existing timestamps only. Use an empty intervals array "
            "when the sample lacks evidence for any configured label."
        )
    else:
        properties = {"label": label_schema}
        instruction = "Select one configured label. Return JSON with a single label field."
    content = json.dumps({
        "task": instruction, "labels": binding["labels"],
        "guidelines": request["guidelines"], "sample": sample,
    }, ensure_ascii=False)
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
    if is_series:
        return prompted_intervals(binding, times, output["intervals"])
    return [result(binding, "choices", {"choices": [output["label"]]})]


def prompted_intervals(binding, times, predictions):
    candidates = []
    seen = set()
    for item in predictions:
        start, end, label = item["start"], item["end"], item["label"]
        if (type(start) not in (int, float) or type(end) not in (int, float)
                or start not in times or end not in times or start > end):
            raise ValueError("Interval must use ordered timestamps from the sample")
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
