#!/usr/bin/env python3
"""Generic TSS training worker driven only by an immutable TrainingRunSpec."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path, PurePosixPath
from typing import Any

try:
    from minio import Minio
except ImportError:  # Allows the standard-library unit tests to import this module.
    Minio = None  # type: ignore[assignment]

try:
    import fcntl
except ImportError:  # pragma: no cover - production worker images are Linux.
    fcntl = None


WORKSPACE = Path("/workspace/job")
MODEL_DIR = WORKSPACE / "model"
DATA_DIR = WORKSPACE / "data"
CODE_DIR = WORKSPACE / "code"
CONFIG_DIR = WORKSPACE / "config"
OUTPUT_DIR = WORKSPACE / "output"
PARAMS_FILE = CONFIG_DIR / "params.json"
RUN_SPEC_SCHEMA = "tss.training.run-spec/v1"
TRAINING_MODES = {
    "FROM_SCRATCH",
    "FULL_FINETUNE",
    "PEFT",
    "PREFERENCE_OPTIMIZATION",
}
EVENT_PREFIX = "TSS_EVENT "
MAX_ARCHIVE_ENTRIES = 200_000
MAX_EXPANDED_BYTES = 200 * 1024 * 1024 * 1024
CALLBACK_ATTEMPTS = 4
LOG_HANDLE = None
LAST_PROGRESS = 0


class WorkerError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def env(name: str, default: str = "") -> str:
    value = os.environ.get(name, default)
    return value.strip() if value else default


def truthy_env(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def log(message: str) -> None:
    text = str(message)
    print(text, flush=True)
    if LOG_HANDLE is not None:
        LOG_HANDLE.write(text + "\n")
        LOG_HANDLE.flush()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_endpoint(endpoint: str) -> tuple[str, bool]:
    secure = endpoint.startswith("https://")
    endpoint = endpoint.removeprefix("http://").removeprefix("https://")
    return endpoint.split("/", 1)[0], secure


def load_run_spec() -> tuple[dict[str, Any], str]:
    raw = os.environ.get("RUN_SPEC_JSON", "")
    expected_sha256 = env("RUN_SPEC_SHA256")
    if not raw:
        raise WorkerError("RUN_SPEC_MISSING", "RUN_SPEC_JSON is required")
    if len(expected_sha256) != 64:
        raise WorkerError("RUN_SPEC_INVALID", "RUN_SPEC_SHA256 is invalid")
    actual_sha256 = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    if not _constant_time_equal(actual_sha256, expected_sha256):
        raise WorkerError("RUN_SPEC_DIGEST_MISMATCH", "RunSpec SHA-256 mismatch")
    try:
        spec = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise WorkerError("RUN_SPEC_INVALID", f"RunSpec is not valid JSON: {exc}") from exc
    validate_run_spec(spec)
    return spec, actual_sha256


def validate_run_spec(spec: dict[str, Any]) -> None:
    if not isinstance(spec, dict) or spec.get("schemaVersion") != RUN_SPEC_SCHEMA:
        raise WorkerError("RUN_SPEC_INVALID", "unsupported RunSpec schemaVersion")
    training_id = spec.get("trainingId")
    if not isinstance(training_id, str) or not training_id:
        raise WorkerError("RUN_SPEC_INVALID", "trainingId is required")
    if env("TRAINING_ID") and env("TRAINING_ID") != training_id:
        raise WorkerError("RUN_SPEC_INVALID", "TRAINING_ID does not match RunSpec")
    if spec.get("trainingMode") not in TRAINING_MODES:
        raise WorkerError("RUN_SPEC_INVALID", "trainingMode is invalid")
    # RunSpec is a cross-platform JSON contract. Keep its paths POSIX even when
    # the worker module is imported by a Windows unit test.
    expected_workspace = {
        "modelDir": "/workspace/job/model",
        "dataDir": "/workspace/job/data",
        "codeDir": "/workspace/job/code",
        "configDir": "/workspace/job/config",
        "outputDir": "/workspace/job/output",
        "paramsFile": "/workspace/job/config/params.json",
    }
    if spec.get("workspace") != expected_workspace:
        raise WorkerError("RUN_SPEC_INVALID", "RunSpec workspace is not the fixed TSS workspace")
    inputs = spec.get("inputs")
    if not isinstance(inputs, dict) or set(inputs) != {"model", "dataset", "code"}:
        raise WorkerError("RUN_SPEC_INVALID", "RunSpec inputs must contain model, dataset and code")
    for name in ("model", "dataset", "code"):
        validate_input_spec(name, inputs[name])
    execution = spec.get("execution")
    argv = execution.get("argv") if isinstance(execution, dict) else None
    if not isinstance(argv, list) or not 2 <= len(argv) <= 128:
        raise WorkerError("RUN_SPEC_INVALID", "execution.argv is invalid")
    if argv[0] not in {"python", "python3"}:
        raise WorkerError("ENTRYPOINT_INVALID", "only python/python3 interpreter is allowed")
    expected_entrypoint = CODE_DIR / inputs["code"]["entrypoint"]
    if Path(argv[1]) != expected_entrypoint:
        raise WorkerError("ENTRYPOINT_INVALID", "argv entrypoint does not match approved code entrypoint")
    if execution.get("workingDirectory") != "/workspace/job/code":
        raise WorkerError("ENTRYPOINT_INVALID", "workingDirectory must be the code directory")
    for argument in argv:
        if not isinstance(argument, str) or "\0" in argument or len(argument) > 1024:
            raise WorkerError("ENTRYPOINT_INVALID", "execution argv contains an invalid argument")
    if not isinstance(spec.get("parameters"), dict):
        raise WorkerError("PARAMETER_INVALID", "parameters must be a JSON object")
    outputs = spec.get("outputs")
    artifacts = outputs.get("artifacts") if isinstance(outputs, dict) else None
    if not isinstance(artifacts, list) or not artifacts:
        raise WorkerError("RUN_SPEC_INVALID", "outputs.artifacts is required")
    if sum(1 for artifact in artifacts if artifact.get("publishAsModel") is True) != 1:
        raise WorkerError("RUN_SPEC_INVALID", "exactly one output must publish as model")


def validate_input_spec(name: str, artifact: Any) -> None:
    if not isinstance(artifact, dict):
        raise WorkerError("RUN_SPEC_INVALID", f"inputs.{name} must be an object")
    for field in ("versionId", "objectName", "sha256", "sizeBytes", "format", "fileName"):
        if artifact.get(field) in (None, ""):
            raise WorkerError("RUN_SPEC_INVALID", f"inputs.{name}.{field} is required")
    if not isinstance(artifact.get("sha256"), str) or len(artifact["sha256"]) != 64:
        raise WorkerError("RUN_SPEC_INVALID", f"inputs.{name}.sha256 is invalid")
    if not isinstance(artifact.get("sizeBytes"), int) or artifact["sizeBytes"] <= 0:
        raise WorkerError("RUN_SPEC_INVALID", f"inputs.{name}.sizeBytes is invalid")
    if not isinstance(artifact.get("archive"), bool):
        raise WorkerError("RUN_SPEC_INVALID", f"inputs.{name}.archive is invalid")
    required_entries = artifact.get("requiredEntries")
    if not isinstance(required_entries, list):
        raise WorkerError("RUN_SPEC_INVALID", f"inputs.{name}.requiredEntries is invalid")
    safe_relative_path(artifact["fileName"], f"inputs.{name}.fileName")
    for entry in required_entries:
        safe_relative_path(entry, f"inputs.{name}.requiredEntries")
    if name == "code":
        requirements = artifact.get("requirements", [])
        if not isinstance(requirements, list) or not all(
            isinstance(item, str) and item and len(item) <= 512 for item in requirements
        ):
            raise WorkerError("RUN_SPEC_INVALID", "inputs.code.requirements is invalid")
        requirements_sha256 = artifact.get("requirementsSha256")
        if requirements and (not isinstance(requirements_sha256, str) or len(requirements_sha256) != 64):
            raise WorkerError("RUN_SPEC_INVALID", "inputs.code.requirementsSha256 is invalid")
        if not requirements and requirements_sha256 is not None:
            raise WorkerError("RUN_SPEC_INVALID", "empty requirements must not declare requirementsSha256")


def _constant_time_equal(left: str, right: str) -> bool:
    return hmac.compare_digest(left, right)


class CallbackReporter:
    def __init__(self, training_id: str):
        self.training_id = training_id
        self.url = env("BACKEND_CALLBACK_URL")
        self.token = env("INTERNAL_CALLBACK_TOKEN")
        self.progress = 0
        self.last_sent_at = 0.0

    def report(self, status: str, progress: int, *, force: bool = False, required: bool = False, **extra: Any) -> bool:
        global LAST_PROGRESS
        normalized = max(self.progress, max(0, min(100, int(progress))))
        if status == "success":
            normalized = 100
        now = time.monotonic()
        if not force and normalized == self.progress and now - self.last_sent_at < 5:
            return True
        self.progress = normalized
        LAST_PROGRESS = max(LAST_PROGRESS, normalized)
        payload: dict[str, Any] = {"status": status, "progress": normalized}
        payload.update({key: value for key, value in extra.items() if value is not None})
        if not self.url:
            if required:
                raise WorkerError("CALLBACK_FAILED", "BACKEND_CALLBACK_URL is missing")
            log("BACKEND_CALLBACK_URL is missing; progress callback skipped")
            return False
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        last_error: Exception | None = None
        for attempt in range(CALLBACK_ATTEMPTS):
            request = urllib.request.Request(
                self.url,
                data=data,
                headers={"Content-Type": "application/json", "X-Internal-Token": self.token},
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    self.last_sent_at = time.monotonic()
                    log(f"callback status={status} progress={normalized} http={response.status}")
                    return True
            except Exception as exc:  # Retry transient network and HTTP errors uniformly.
                last_error = exc
                if attempt + 1 < CALLBACK_ATTEMPTS:
                    time.sleep(2 ** attempt)
        message = f"callback failed after {CALLBACK_ATTEMPTS} attempts: {last_error}"
        if required:
            raise WorkerError("CALLBACK_RETRY_EXHAUSTED", message)
        log(message)
        return False

    def stage(self, progress: int, stage: str, message: str) -> None:
        self.report("running", progress, force=True, remark=f"{stage}: {message}")


class MlflowLogger:
    def __init__(self, spec: dict[str, Any]):
        self.spec = spec
        self.base_url = env("MLFLOW_TRACKING_URI").rstrip("/")
        self.experiment_name = env("MLFLOW_EXPERIMENT_NAME", "TSSAI-K8s-Training")
        self.run_id: str | None = None
        self.experiment_id: str | None = None

    def start(self) -> None:
        if not self.base_url:
            return
        try:
            self.experiment_id = self._ensure_experiment()
            response = self._post("/api/2.0/mlflow/runs/create", {
                "experiment_id": self.experiment_id,
                "start_time": int(time.time() * 1000),
                "tags": [
                    {"key": "tss.training_id", "value": self.spec["trainingId"]},
                    {"key": "tss.training_plan", "value": self.spec["plan"]["id"]},
                    {"key": "tss.training_plan_version", "value": self.spec["plan"]["version"]},
                    {"key": "tss.training_mode", "value": self.spec["trainingMode"]},
                ],
            })
            self.run_id = str(response["run"]["info"]["run_id"])
            self._post("/api/2.0/mlflow/runs/log-batch", {
                "run_id": self.run_id,
                "params": [
                    {"key": key, "value": str(value)[:6000]}
                    for key, value in self.spec["parameters"].items()
                ],
            })
            log(f"MLflow runId={self.run_id}")
        except Exception as exc:
            log(f"MLflow start failed; training continues: {exc}")
            self.run_id = None

    def finish(self, success: bool) -> None:
        if not self.run_id:
            return
        try:
            self._post("/api/2.0/mlflow/runs/update", {
                "run_id": self.run_id,
                "status": "FINISHED" if success else "FAILED",
                "end_time": int(time.time() * 1000),
            })
        except Exception as exc:
            log(f"MLflow finish failed: {exc}")

    def log_metric_history(self, records: list[dict[str, Any]]) -> None:
        """把按 step 累积的过程指标整批写入 MLflow（best effort，失败不影响训练）。

        records: [{"step": int, "key": str, "value": float}, ...]，供前端按 step 画曲线。
        """
        if not self.run_id or not records:
            return
        try:
            timestamp = int(time.time() * 1000)
            metrics = [
                {
                    "key": record["key"],
                    "value": float(record["value"]),
                    "timestamp": timestamp,
                    "step": int(record["step"]),
                }
                for record in records
            ]
            self._post("/api/2.0/mlflow/runs/log-batch", {"run_id": self.run_id, "metrics": metrics})
            log(f"MLflow log metric history: {len(metrics)} points")
        except Exception as exc:
            log(f"MLflow metric history write failed; training continues: {exc}")

    def _ensure_experiment(self) -> str:
        try:
            path = "/api/2.0/mlflow/experiments/get-by-name?experiment_name=" + urllib.parse.quote(self.experiment_name)
            with urllib.request.urlopen(self.base_url + path, timeout=20) as response:
                payload = json.loads(response.read().decode())
                return str(payload["experiment"]["experiment_id"])
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise
        return str(self._post("/api/2.0/mlflow/experiments/create", {"name": self.experiment_name})["experiment_id"])

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        request = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode())


def download_verified(client: Any, bucket: str, artifact: dict[str, Any], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    response = client.get_object(bucket, artifact["objectName"])
    digest = hashlib.sha256()
    size = 0
    try:
        with destination.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                digest.update(chunk)
                size += len(chunk)
    finally:
        response.close()
        response.release_conn()
    if size != artifact["sizeBytes"]:
        raise WorkerError("INPUT_SIZE_MISMATCH", f"{artifact['versionId']} size mismatch")
    if not _constant_time_equal(digest.hexdigest(), artifact["sha256"]):
        raise WorkerError("INPUT_DIGEST_MISMATCH", f"{artifact['versionId']} SHA-256 mismatch")
    log(f"downloaded and verified {artifact['versionId']} sha256={digest.hexdigest()}")


def safe_relative_path(value: str, field: str) -> PurePosixPath:
    if not isinstance(value, str):
        raise WorkerError("RUN_SPEC_INVALID", f"{field} must be a string")
    normalized = value.replace("\\", "/")
    path = PurePosixPath(normalized)
    if not normalized or path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise WorkerError("RUN_SPEC_INVALID", f"{field} is not a safe relative path")
    return path


def safe_extract_zip(archive_path: Path, destination: Path, compressed_size: int) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    expanded_limit = min(MAX_EXPANDED_BYTES, max(1024 * 1024 * 1024, compressed_size * 100))
    with zipfile.ZipFile(archive_path) as archive:
        members = archive.infolist()
        if len(members) > MAX_ARCHIVE_ENTRIES:
            raise WorkerError("INPUT_ARCHIVE_INVALID", "archive has too many entries")
        expanded = 0
        for member in members:
            path = safe_relative_path(member.filename, "archive entry")
            unix_mode = member.external_attr >> 16
            if unix_mode & 0o170000 == 0o120000:
                raise WorkerError("INPUT_ARCHIVE_INVALID", f"symbolic link is forbidden: {member.filename}")
            expanded += member.file_size
            if expanded > expanded_limit:
                raise WorkerError("INPUT_ARCHIVE_INVALID", "archive expanded size exceeds limit")
            target = destination.joinpath(*path.parts)
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)


def materialize_input(
    client: Any,
    bucket: str,
    name: str,
    artifact: dict[str, Any],
    destination: Path,
    temp_dir: Path,
) -> None:
    downloaded = temp_dir / f"{name}.download"
    download_verified(client, bucket, artifact, downloaded)
    destination.mkdir(parents=True, exist_ok=True)
    if artifact["archive"]:
        safe_extract_zip(downloaded, destination, artifact["sizeBytes"])
    else:
        relative = safe_relative_path(artifact["fileName"], f"inputs.{name}.fileName")
        target = destination.joinpath(*relative.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(downloaded, target)
    for required in artifact["requiredEntries"]:
        relative = safe_relative_path(required, f"inputs.{name}.requiredEntries")
        target = destination.joinpath(*relative.parts)
        if not target.exists():
            raise WorkerError("INPUT_REQUIRED_ENTRY_MISSING", f"inputs.{name} is missing {required}")


def validate_cached_model(artifact: dict[str, Any]) -> None:
    if not MODEL_DIR.is_dir() or MODEL_DIR.is_symlink():
        raise WorkerError("MODEL_CACHE_INVALID", "model cache mount is missing")
    if next((path for path in MODEL_DIR.rglob("*") if path.is_file()), None) is None:
        raise WorkerError("MODEL_CACHE_INVALID", "model cache mount is empty")

    required_entries = list(artifact["requiredEntries"])
    if not artifact["archive"]:
        required_entries.append(artifact["fileName"])
    for required in required_entries:
        relative = safe_relative_path(required, "inputs.model.requiredEntries")
        target = MODEL_DIR.joinpath(*relative.parts)
        if target.is_symlink() or not target.exists():
            raise WorkerError(
                "INPUT_REQUIRED_ENTRY_MISSING",
                f"cached model is missing {required}",
            )


def acquire_model_cache_read_lock():
    if not truthy_env("MODEL_CACHE_ENABLED"):
        return None
    raw_path = env("MODEL_CACHE_LOCK_PATH")
    lock_path = Path(raw_path)
    if not raw_path or not lock_path.is_absolute() or not lock_path.is_file():
        raise WorkerError("MODEL_CACHE_INVALID", "model cache lock mount is missing")
    handle = lock_path.open("rb")
    try:
        if fcntl is not None:
            fcntl.flock(handle.fileno(), fcntl.LOCK_SH)
        log("model cache read lock acquired")
        return handle
    except Exception:
        handle.close()
        raise


def release_model_cache_read_lock(handle) -> None:
    if handle is None:
        return
    try:
        if fcntl is not None:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    finally:
        handle.close()


def write_parameters(spec: dict[str, Any]) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    PARAMS_FILE.write_text(
        json.dumps(spec["parameters"], ensure_ascii=False, sort_keys=True, indent=2),
        encoding="utf-8",
    )


def install_user_requirements() -> None:
    """Install code/requirements.txt into the worker user site-packages when present.

    Used when the platform cannot build a derived image (no Docker/registry on
    the backend host). Runs as the worker user (10001) and installs into the
    user site so the user's entry script can import the declared dependencies.
    """
    req_file = CODE_DIR / "requirements.txt"
    if not req_file.is_file():
        return
    log("installing user requirements.txt")
    pip_index = env("PIP_INDEX_URL", "https://pypi.tuna.tsinghua.edu.cn/simple")
    host = urllib.parse.urlparse(pip_index).hostname or ""
    cmd = [sys.executable, "-m", "pip", "install", "--user", "--no-cache-dir",
           "--disable-pip-version-check", "-r", str(req_file), "-i", pip_index]
    if host:
        cmd += ["--trusted-host", host]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.stdout:
        log(result.stdout)
    if result.returncode != 0:
        log(f"pip install stderr: {result.stderr}")
        raise WorkerError("REQUIREMENTS_INSTALL_FAILED",
                          f"pip install requirements.txt failed: {result.stderr}")
    log("user requirements installed")


def execute_training(spec: dict[str, Any], reporter: CallbackReporter) -> tuple[int, list[dict[str, Any]], dict[str, Any]]:
    argv = training_entry_argv(spec)
    entrypoint = Path(argv[1])
    if not entrypoint.is_file():
        raise WorkerError("ENTRYPOINT_INVALID", f"approved entrypoint is missing: {entrypoint}")
    # 按 step 累积指标历史，供训练结束后写入 MLflow 绘制过程曲线。
    # 事件可用 {"type":"metric","step":<epoch>,"metrics":{...}} 指定 step；不指定则自动递增。
    metric_history: list[dict[str, Any]] = []
    auto_step = 0
    log(f"execute argv={json.dumps(argv, ensure_ascii=False)}")
    child_env = os.environ.copy()
    child_env.update({
        "TSS_TRAINING_ID": spec["trainingId"],
        "TSS_TRAINING_MODE": spec["trainingMode"],
        "TSS_MODEL_DIR": str(MODEL_DIR),
        "TSS_DATA_DIR": str(DATA_DIR),
        "TSS_OUTPUT_DIR": str(OUTPUT_DIR),
        "TSS_PARAMS_FILE": str(PARAMS_FILE),
    })
    process = subprocess.Popen(
        argv,
        cwd=spec["execution"]["workingDirectory"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
        shell=False,
        env=child_env,
    )
    assert process.stdout is not None
    for raw_line in process.stdout:
        line = raw_line.rstrip("\n")
        log(line)
        event = parse_training_event(line)
        if event is None:
            continue
        if event.get("type") == "progress" and isinstance(event.get("progress"), (int, float)):
            mapped = 45 + int(max(0, min(100, event["progress"])) * 0.4)
            reporter.report("running", mapped)
        if event.get("type") == "metric" and isinstance(event.get("metrics"), dict):
            step = event.get("step")
            if isinstance(step, (int, float)) and not isinstance(step, bool):
                current_step = int(step)
            else:
                auto_step += 1
                current_step = auto_step
            for key, value in event["metrics"].items():
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    metric_history.append({"step": current_step, "key": key, "value": float(value)})
    # 最终值 = 每个 key 最后一个 step 的值（与旧 event_metrics.update 语义一致）
    event_metrics: dict[str, Any] = {}
    for record in metric_history:
        event_metrics[record["key"]] = record["value"]
    return process.wait(), metric_history, event_metrics


def validate_selected_gpu(spec: dict[str, Any]) -> None:
    """核对 DRA 只暴露了任务选中的那张物理卡。"""
    selected_uuid = spec.get("resources", {}).get("gpuUuid")
    env_uuid = env("TSS_SELECTED_GPU_UUID")
    if not selected_uuid:
        if env_uuid:
            raise WorkerError("GPU_IDENTITY_INVALID", "CPU task received a selected GPU UUID")
        return
    if env_uuid != selected_uuid:
        raise WorkerError("GPU_IDENTITY_INVALID", "selected GPU UUID differs from RunSpec")
    completed = subprocess.run(
        ["nvidia-smi", "--query-gpu=uuid", "--format=csv,noheader"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    visible = {line.strip() for line in completed.stdout.splitlines() if line.strip()}
    if completed.returncode != 0 or visible != {selected_uuid}:
        raise WorkerError(
            "GPU_IDENTITY_INVALID",
            f"expected only {selected_uuid}, visible={sorted(visible)}",
        )
    log(
        f"selected physical GPU: host-index={env('TSS_HOST_GPU_INDEX', '?')}, "
        f"uuid={selected_uuid}"
    )


def training_entry_argv(spec: dict[str, Any]) -> list[str]:
    """在训练脚本同一进程里设置 PyTorch CUDA 显存软预算。"""
    argv = list(spec["execution"]["argv"])
    budget = spec.get("resources", {}).get("gpuMemoryLimitMiB")
    raw_budget = env("TSS_GPU_MEMORY_LIMIT_MIB")
    if budget is None:
        if raw_budget:
            raise WorkerError("GPU_BUDGET_INVALID", "RunSpec has no GPU memory budget")
        return argv
    if not isinstance(budget, int) or isinstance(budget, bool) or budget <= 0:
        raise WorkerError("GPU_BUDGET_INVALID", "GPU memory budget must be a positive integer")
    if raw_budget != str(budget):
        raise WorkerError("GPU_BUDGET_INVALID", "GPU memory budget differs from RunSpec")
    wrapper = CONFIG_DIR / "tss_cuda_entrypoint.py"
    wrapper.write_text(
        """import runpy
import sys
import torch
from pathlib import Path

budget_mib = int(sys.argv[1])
entry_file = sys.argv[2]
if not torch.cuda.is_available():
    raise RuntimeError("selected GPU is not available to PyTorch")
total_mib = torch.cuda.get_device_properties(0).total_memory / (1024 * 1024)
fraction = min(1.0, budget_mib / total_mib)
torch.cuda.set_per_process_memory_fraction(fraction, 0)
print(
    f"TSS CUDA soft budget applied: requestedMiB={budget_mib}, "
    f"deviceTotalMiB={total_mib:.0f}, fraction={fraction:.6f}",
    flush=True,
)
# 模拟直接执行用户脚本，保证同目录模块仍能正常 import。
sys.path[0] = str(Path(entry_file).resolve().parent)
sys.argv = [entry_file, *sys.argv[3:]]
runpy.run_path(entry_file, run_name="__main__")
""",
        encoding="utf-8",
    )
    return [sys.executable, str(wrapper), str(budget), argv[1], *argv[2:]]


def parse_training_event(line: str) -> dict[str, Any] | None:
    if not line.startswith(EVENT_PREFIX):
        return None
    try:
        event = json.loads(line[len(EVENT_PREFIX):])
        return event if isinstance(event, dict) else None
    except json.JSONDecodeError:
        return None


def resolve_output_path(relative: str) -> Path:
    path = safe_relative_path(relative, "output artifact path")
    target = OUTPUT_DIR.joinpath(*path.parts)
    try:
        target.resolve().relative_to(OUTPUT_DIR.resolve())
    except ValueError as exc:
        raise WorkerError("OUTPUT_INVALID", f"output escapes workspace: {relative}") from exc
    return target


def upload_outputs(client: Any, bucket: str, spec: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    uploaded: list[dict[str, Any]] = []
    primary = None
    log_artifact = None
    prefix = f"training-results/{spec['trainingId']}/artifacts"
    for declared in spec["outputs"]["artifacts"]:
        local_path = resolve_output_path(declared["path"])
        if not local_path.is_file():
            if declared.get("required"):
                raise WorkerError("OUTPUT_MISSING", f"required output is missing: {declared['path']}")
            continue
        size = local_path.stat().st_size
        if size <= 0:
            raise WorkerError("OUTPUT_INVALID", f"output is empty: {declared['path']}")
        object_name = f"{prefix}/{declared['path']}"
        content_type = output_content_type(declared["format"])
        client.fput_object(bucket, object_name, str(local_path), content_type=content_type)
        artifact = {
            "path": declared["path"],
            "objectName": object_name,
            "role": declared["role"],
            "format": declared["format"],
            "sha256": sha256_file(local_path),
            "sizeBytes": size,
        }
        uploaded.append(artifact)
        if declared.get("publishAsModel"):
            primary = artifact
        if declared["role"] == "LOG":
            log_artifact = artifact
    if primary is None:
        raise WorkerError("OUTPUT_MISSING", "primary model output is missing")
    if log_artifact is None:
        raise WorkerError("OUTPUT_MISSING", "training log output is missing")
    return uploaded, primary, log_artifact


def materialize_declared_packages(spec: dict[str, Any]) -> None:
    for artifact in spec["outputs"]["artifacts"]:
        packaging = artifact.get("packaging")
        if packaging is None:
            continue
        if packaging.get("type") != "ZIP_SINGLE_FILE":
            raise WorkerError("OUTPUT_INVALID", "unsupported output packaging type")
        source = resolve_output_path(packaging["sourcePath"])
        target = resolve_output_path(artifact["path"])
        entry_name = safe_relative_path(packaging["entryName"], "output packaging entryName")
        if not source.is_file() or source.stat().st_size <= 0:
            raise WorkerError("OUTPUT_MISSING", f"packaging source is missing: {packaging['sourcePath']}")
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(source, arcname=str(entry_name))


def output_content_type(format_name: str) -> str:
    return {
        "JSON": "application/json",
        "CSV": "text/csv",
        "TEXT": "text/plain",
        "SKLEARN_PICKLE_ZIP": "application/zip",
    }.get(format_name, "application/octet-stream")


def load_metrics(spec: dict[str, Any], event_metrics: dict[str, Any]) -> dict[str, Any]:
    metrics_path = resolve_output_path(spec["outputs"]["metricsPath"])
    if not metrics_path.is_file():
        raise WorkerError("OUTPUT_MISSING", f"metrics file is missing: {metrics_path}")
    try:
        metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise WorkerError("OUTPUT_INVALID", f"metrics JSON is invalid: {exc}") from exc
    if not isinstance(metrics, dict):
        raise WorkerError("OUTPUT_INVALID", "metrics JSON must be an object")
    flat = flatten_scalars(metrics)
    flat.update(flatten_scalars(event_metrics))
    flat["trainingPlan"] = spec["plan"]["id"]
    flat["trainingPlanVersion"] = spec["plan"]["version"]
    return flat


def flatten_scalars(value: dict[str, Any], prefix: str = "") -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, item in value.items():
        name = f"{prefix}.{key}" if prefix else str(key)
        if isinstance(item, dict):
            result.update(flatten_scalars(item, name))
        elif item is None or isinstance(item, (str, int, float, bool)):
            result[name] = item
    return result


def legacy_model_callback(primary: dict[str, Any], local_path: Path) -> dict[str, Any]:
    model_file_name = local_path.name
    digest = primary["sha256"]
    if zipfile.is_zipfile(local_path):
        with zipfile.ZipFile(local_path) as archive:
            files = [entry for entry in archive.infolist() if not entry.is_dir()]
            if not files:
                raise WorkerError("OUTPUT_INVALID", "published model ZIP must contain at least one file")
            # Legacy single-file archives expose the inner model digest. Generic
            # formats such as HuggingFace contain several files and use the
            # immutable archive digest recorded in training-output.json.
            if len(files) == 1:
                model_file_name = files[0].filename
                inner_digest = hashlib.sha256()
                with archive.open(files[0]) as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        inner_digest.update(chunk)
                digest = inner_digest.hexdigest()
    return {
        "fileName": local_path.name,
        "objectName": primary["objectName"],
        "modelFileName": model_file_name,
        "format": primary["format"],
        "sha256": digest,
        "sizeBytes": primary["sizeBytes"],
    }


def put_json(client: Any, bucket: str, object_name: str, payload: dict[str, Any]) -> tuple[str, int]:
    data = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    client.put_object(bucket, object_name, BytesIO(data), len(data), content_type="application/json")
    return hashlib.sha256(data).hexdigest(), len(data)


def prepare_workspace(spec: dict[str, Any]) -> Path:
    for directory in (MODEL_DIR, DATA_DIR, CODE_DIR, CONFIG_DIR, OUTPUT_DIR):
        directory.mkdir(parents=True, exist_ok=True)
    log_relative = spec["outputs"]["logPath"]
    return resolve_output_path(log_relative)


def run() -> None:
    global LOG_HANDLE
    started_at = utc_now()
    spec, run_spec_sha256 = load_run_spec()
    training_id = spec["trainingId"]
    reporter = CallbackReporter(training_id)
    log_path = prepare_workspace(spec)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    LOG_HANDLE = log_path.open("a", encoding="utf-8")
    log(f"TSS generic training worker start trainingId={training_id} runSpecSha256={run_spec_sha256}")
    validate_selected_gpu(spec)

    if Minio is None:
        raise WorkerError("WORKER_DEPENDENCY_MISSING", "minio package is not installed")
    host, secure = parse_endpoint(env("MINIO_ENDPOINT", "http://tss-minio:9000"))
    client = Minio(
        host,
        access_key=env("MINIO_ACCESS_KEY"),
        secret_key=env("MINIO_SECRET_KEY"),
        secure=secure,
    )
    bucket = env("MINIO_BUCKET", "models")
    temp_dir = WORKSPACE / ".downloads"
    temp_dir.mkdir(parents=True, exist_ok=True)
    mlflow = MlflowLogger(spec)
    model_cache_lock = None

    try:
        model_cache_lock = acquire_model_cache_read_lock()
        reporter.stage(5, "prepare", "RunSpec verified")
        if truthy_env("MODEL_CACHE_ENABLED"):
            validate_cached_model(spec["inputs"]["model"])
            reporter.stage(15, "model", "base model reused from physical-node cache")
        else:
            materialize_input(client, bucket, "model", spec["inputs"]["model"], MODEL_DIR, temp_dir)
            reporter.stage(15, "model", "base model downloaded and verified")
        materialize_input(client, bucket, "dataset", spec["inputs"]["dataset"], DATA_DIR, temp_dir)
        reporter.stage(28, "dataset", "dataset downloaded and verified")
        materialize_input(client, bucket, "code", spec["inputs"]["code"], CODE_DIR, temp_dir)
        reporter.stage(40, "code", "approved code downloaded and verified")
        install_user_requirements()
        write_parameters(spec)
        mlflow.start()
        reporter.report(
            "running",
            45,
            force=True,
            runId=mlflow.run_id,
            mlflowExperimentId=mlflow.experiment_id,
            mlflowTrackingUri=mlflow.base_url or None,
        )
        exit_code, metric_history, event_metrics = execute_training(spec, reporter)
        # 训练结束后把按 step 累积的过程指标写入 MLflow（best effort，失败不影响训练）
        mlflow.log_metric_history(metric_history)
        if exit_code != 0:
            raise WorkerError("PROCESS_FAILED", f"training process exited with code {exit_code}")
        reporter.stage(86, "validate", "training process completed; validating outputs")
        materialize_declared_packages(spec)
        metrics = load_metrics(spec, event_metrics)
        uploaded, primary, log_artifact = upload_outputs(client, bucket, spec)
        reporter.stage(96, "upload", "required outputs uploaded")
        finished_at = utc_now()
        output = {
            "schemaVersion": "tss.training.output/v1",
            "trainingId": training_id,
            "planId": spec["plan"]["id"],
            "planVersion": spec["plan"]["version"],
            "status": "success",
            "progress": 100,
            "startedAt": started_at,
            "finishedAt": finished_at,
            "exitCode": exit_code,
            "metrics": metrics,
            "artifacts": uploaded,
            "primaryModel": primary,
            "log": log_artifact,
            "inputDigests": {
                "model": spec["inputs"]["model"]["sha256"],
                "dataset": spec["inputs"]["dataset"]["sha256"],
                "code": spec["inputs"]["code"]["sha256"],
            },
        }
        output_object = f"training-results/{training_id}/training-output.json"
        output_sha256, output_size = put_json(client, bucket, output_object, output)
        mlflow.finish(True)
        reporter.report(
            "success",
            100,
            force=True,
            required=True,
            metrics=metrics,
            logPath=f"minio://{log_artifact['objectName']}",
            outputPath=f"minio://training-results/{training_id}/artifacts/",
            runId=mlflow.run_id,
            mlflowExperimentId=mlflow.experiment_id,
            mlflowTrackingUri=mlflow.base_url or None,
            modelArtifact=legacy_model_callback(primary, resolve_output_path(primary["path"])),
            trainingOutput=output,
            trainingOutputObjectName=output_object,
            trainingOutputSha256=output_sha256,
            trainingOutputSizeBytes=output_size,
            startedAt=started_at,
            finishedAt=finished_at,
        )
        log("training completed")
    except Exception:
        mlflow.finish(False)
        raise
    finally:
        release_model_cache_read_lock(model_cache_lock)
        shutil.rmtree(temp_dir, ignore_errors=True)


def main() -> None:
    global LOG_HANDLE
    started_at = utc_now()
    try:
        run()
    except Exception as exc:
        code = exc.code if isinstance(exc, WorkerError) else "WORKER_FAILED"
        log(f"training failed code={code}: {exc}")
        training_id = env("TRAINING_ID", "unknown")
        reporter = CallbackReporter(training_id)
        reporter.progress = LAST_PROGRESS
        try:
            reporter.report(
                "failed",
                reporter.progress,
                force=True,
                required=True,
                errorMessage=f"{code}: {exc}",
                startedAt=started_at,
                finishedAt=utc_now(),
            )
        except Exception as callback_error:
            log(f"failure callback also failed: {callback_error}")
        sys.exit(1)
    finally:
        if LOG_HANDLE is not None:
            LOG_HANDLE.close()
            LOG_HANDLE = None


if __name__ == "__main__":
    main()
