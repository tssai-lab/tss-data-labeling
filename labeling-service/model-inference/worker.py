"""Consume labeling executions using a fixed model configuration."""

import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID
from xml.etree import ElementTree

import prelabel


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def label_binding(control, target_name, tag):
    aliases = {}
    for option in control.iter(tag):
        label = option.attrib["value"]
        alias = option.get("alias") or label
        if aliases.setdefault(label, alias) != alias:
            raise ValueError("A project label has multiple result aliases")
    if not aliases or any(label.startswith("$") for label in aliases):
        raise ValueError("Expected static project labels")
    return {
        "fromName": control.attrib["name"], "toName": target_name,
        "labels": list(aliases), "labelAliases": aliases,
    }


def binding_for(config, mode):
    root = ElementTree.fromstring(config["labelConfig"])
    control_tag = {"image": "Choices", "pcb": "RectangleLabels", "timeseries": "TimeSeriesLabels"}[mode]
    controls = list(root.iter(control_tag))
    if len(controls) != 1:
        raise ValueError("Expected one matching annotation control")
    control = controls[0]
    object_tag = "TimeSeries" if mode == "timeseries" else "Image"
    targets = [node for node in root.iter(object_tag) if node.get("name") == control.attrib["toName"]]
    if len(targets) != 1:
        raise ValueError("Expected one bound data object")
    target = targets[0]
    source = target.attrib["value"]
    if not source.startswith("$"):
        raise ValueError("Expected a data-bound input")
    tag = "Choice" if mode == "image" else "Label"
    binding = label_binding(control, target.attrib["name"], tag)
    if mode == "timeseries":
        daily = root.find(".//Choices[@name='energy_daily']")
        rationale = root.find(".//TextArea[@name='energy_rationale']")
        if ((daily is not None and rationale is not None) or
                (binding["fromName"] == "energy_events" and (daily is not None or rationale is not None))):
            if (daily is None or rationale is None
                    or daily.get("toName") != binding["toName"]
                    or rationale.get("toName") != binding["toName"]):
                raise ValueError("Energy day and rationale controls must bind to the same curve")
            binding["daily"] = label_binding(daily, binding["toName"], "Choice")
            binding["daily"]["required"] = daily.get("required") == "true"
            binding["rationale"] = rationale.attrib["name"]
    return binding, source[1:], target


class Worker:
    def __init__(self, base, key, model):
        address = urlsplit(base)
        if address.scheme not in {"http", "https"} or not address.netloc or address.path not in {"", "/"} or address.query or address.fragment or address.username:
            raise ValueError("Expected an internal HTTP(S) origin")
        self.base = base.rstrip("/") + "/internal/labeling/v1"
        self.key = key
        self.model = model

    def request(self, method, path, body=None, query=None, key=None):
        headers = {"X-Worker-Key": self.key}
        data = None
        if body is not None:
            data = json.dumps(body, allow_nan=False).encode()
            headers["Content-Type"] = "application/json"
        if key:
            headers["Idempotency-Key"] = key
        url = self.base + path + ("?" + urlencode(query) if query else "")
        return build_opener(NoRedirect()).open(Request(url, data, headers, method=method), timeout=30)

    def call(self, method, path, body=None, query=None, key=None):
        with self.request(method, path, body, query, key) as response:
            return None if response.status == 204 else json.load(response)

    def infer(self, path, lease, execution):
        input_data = self.call("GET", path + "/data", query=lease)
        mode = self.model["mode"]
        config = execution["editorConfig"]
        binding, source, target = binding_for(config, mode)
        task_data = input_data["task"]["data"]
        request = dict(self.model["options"], binding=binding)
        spec_instructions = (execution.get("specInstructions") or "").strip()
        batch_notes = (execution.get("instructions") or "").strip()
        request["guidelines"] = spec_instructions or batch_notes or request.get("guidelines", "")
        if spec_instructions and batch_notes:
            request["batchNotes"] = batch_notes
        if mode == "timeseries":
            columns = task_data[source]
            channel, = target.findall("Channel")
            request.update(mode="timeseries", times=columns[target.attrib["timeColumn"]],
                           values=columns[channel.attrib["column"]])
            context = dict(task_data.get("history_summary") or {})
            if "daily_kwh" in task_data:
                context["dailyKwh"] = task_data["daily_kwh"]
            request["sample"] = context
            if binding["fromName"] == "energy_events" and config.get("timeseriesPrelabelMode") != "instructions":
                request["legacyEnergyRules"] = config.get("energyRules", {})
            else:
                request["seriesMetadata"] = {
                    "timeColumn": target.attrib["timeColumn"],
                    "measurementColumn": channel.attrib["column"],
                    "units": channel.get("units"),
                }
                for key in ("station", "date", "context"):
                    if key in task_data:
                        context[key] = task_data[key]
            return prelabel.prompted(request)

        resource, = [item for item in input_data["resources"] if item["field"] == source]
        suffix = {"image/jpeg": ".jpg", "image/png": ".png"}.get(resource["mediaType"])
        if suffix is None:
            raise ValueError("Expected JPEG or PNG")
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / ("input" + suffix)
            resource_id = UUID(resource["resourceId"])
            with self.request("GET", f"{path}/resources/{resource_id}", query=lease) as response:
                with image.open("wb") as output:
                    shutil.copyfileobj(response, output)
            request["image"] = str(image)
            if mode == "image":
                request.pop("mode", None)
                return prelabel.prompted(request)

            from PIL import Image

            project_labels = set(binding["labels"])
            request["classMap"] = {
                name: label for name, label in request["classMap"].items() if label in project_labels
            }
            if not request["classMap"]:
                raise ValueError("No model classes map to the project labels")
            candidates = prelabel.yolo(dict(request, mode="detection"))
            with Image.open(image) as opened:
                width, height = opened.size
            for candidate in candidates:
                candidate.update(original_width=width, original_height=height)
            return candidates

    def execute(self, execution):
        execution_id = UUID(execution["executionId"])
        path = f"/executions/{execution_id}"
        lease = {"generation": execution["generation"]}
        stop = threading.Event()
        lost = threading.Event()

        def heartbeat():
            while not stop.wait(30):
                try:
                    self.call("POST", path + "/heartbeat", lease)
                except OSError:
                    lost.set()
                    return

        thread = threading.Thread(target=heartbeat, daemon=True)
        thread.start()
        try:
            cursor = None
            while not lost.is_set():
                query = dict(lease, limit=20)
                if cursor:
                    query["cursor"] = cursor
                page = self.call("GET", path + "/items", query=query)
                for item in page["items"]:
                    if lost.is_set():
                        return
                    if item.get("outputStatus") in {"SUCCEEDED", "FAILED"}:
                        continue
                    sample_id = UUID(item["sampleId"])
                    output_path = f"{path}/outputs/{sample_id}"
                    try:
                        candidates = self.infer(f"{path}/samples/{sample_id}", lease, execution)
                        for index, candidate in enumerate(candidates):
                            candidate["id"] = f"prediction-{index}"
                        if lost.is_set():
                            return
                        result = {"result": candidates}
                        if not candidates:
                            result["emptyReason"] = "模型未生成有依据的标注候选"
                        self.call("PUT", output_path, dict(lease, status="SUCCEEDED", result=result))
                    except HTTPError as error:
                        if error.code != 422:
                            raise
                        self.fail_item(output_path, lease, "MODEL_RESULT_INCOMPATIBLE")
                    except (ValueError, KeyError, TypeError, RuntimeError, ImportError, OSError) as error:
                        if isinstance(error, OSError) and not isinstance(error, FileNotFoundError):
                            raise
                        if lost.is_set():
                            return
                        print(f"Sample {sample_id} failed ({type(error).__name__})", file=sys.stderr)
                        self.fail_item(output_path, lease, "MODEL_INFERENCE_FAILED")
                cursor = page.get("nextCursor")
                if cursor is None and not lost.is_set():
                    self.call("POST", path + "/finish", lease, key=f"finish-{execution_id}-{lease['generation']}")
                    return
        finally:
            stop.set()
            thread.join(timeout=1)

    def fail_item(self, path, lease, code):
        self.call("PUT", path, dict(lease, status="FAILED", errorCode=code,
                                    errorMessage="Model inference or candidate validation failed"))

    def run_once(self):
        execution = self.call("POST", "/executions/claim")
        if execution is None:
            return False
        self.execute(execution)
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8-sig"))
    worker = Worker(config["baseUrl"], os.environ["LABELING_MODEL_WORKER_KEY"], config["model"])
    if args.once:
        worker.run_once()
        return
    while True:
        try:
            if not worker.run_once():
                time.sleep(3)
        except OSError as error:
            print(f"Worker request failed ({type(error).__name__})", file=sys.stderr)
            time.sleep(5)


if __name__ == "__main__":
    main()
