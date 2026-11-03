#!/usr/bin/env python3
"""TSS Platform inference worker.

The backend owns authorization and task state. This worker only prepares an
isolated workspace, runs the uploaded Python entry file, uploads artifacts, and
reports status back through the internal callback API.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from contextlib import contextmanager
from io import BytesIO
from pathlib import Path, PurePosixPath

from minio import Minio

try:
    import fcntl
except ImportError:  # pragma: no cover - production worker images are Linux.
    fcntl = None

WORKSPACE = Path("/workspace/job")
MODEL_DIR = WORKSPACE / "model"
SCRIPT_DIR = WORKSPACE / "script"
INPUT_DIR = WORKSPACE / "input"
OUTPUT_DIR = WORKSPACE / "output"
LOG_FILE = WORKSPACE / "infer.log"
DEFAULT_PIP_INDEX_URL = "https://pypi.tuna.tsinghua.edu.cn/simple"

CACHE_SCHEMA_VERSION = 1
CACHE_KEY_PATTERN = re.compile(r"[0-9a-f]{64}")
CACHE_DOWNLOAD_CHUNK_BYTES = 1024 * 1024
CACHE_MAX_ZIP_ENTRIES = 10_000
MODEL_CACHE_RESULT_PREFIX = "MODEL_CACHE_RESULT_JSON="
_LOCAL_LOCKS: dict[str, threading.RLock] = {}
_LOCAL_LOCKS_GUARD = threading.Lock()


def env(name: str, default: str = "") -> str:
    value = os.environ.get(name, default)
    return value.strip() if value else default


def log(message: str) -> None:
    line = f"{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} {message}"
    print(line, flush=True)
    LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
    with LOG_FILE.open("a", encoding="utf-8") as file:
        file.write(line + "\n")


def parse_endpoint(endpoint: str) -> tuple[str, bool]:
    secure = endpoint.startswith("https://")
    endpoint = endpoint.replace("http://", "").replace("https://", "")
    if "/" in endpoint:
        endpoint = endpoint.split("/", 1)[0]
    return endpoint, secure


def minio_client() -> tuple[Minio, str]:
    endpoint, secure = parse_endpoint(env("MINIO_ENDPOINT"))
    bucket = env("MINIO_BUCKET", "models")
    client = Minio(
        endpoint,
        access_key=env("MINIO_ACCESS_KEY"),
        secret_key=env("MINIO_SECRET_KEY"),
        secure=secure,
    )
    return client, bucket


def download_object(client: Minio, bucket: str, object_name: str) -> bytes:
    if not object_name:
        raise ValueError("object name is empty")
    log(f"download minio object: {bucket}/{object_name}")
    response = client.get_object(bucket, object_name)
    try:
        return response.read()
    finally:
        response.close()
        response.release_conn()


def required_cache_int(name: str, minimum: int) -> int:
    raw = env(name)
    try:
        value = int(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return value


def validate_cache_root() -> Path:
    raw_root = env("MODEL_CACHE_ROOT")
    root = Path(raw_root)
    if not raw_root or not root.is_absolute() or root == Path("/"):
        raise ValueError("MODEL_CACHE_ROOT must be a non-root absolute path")
    sentinel = root / ".tss-model-cache-root"
    if sentinel.is_symlink() or not sentinel.is_file():
        raise RuntimeError(f"physical model cache sentinel is missing: {sentinel}")

    return root


def validate_cache_root_and_key() -> tuple[Path, str]:
    root = validate_cache_root()

    key = env("MODEL_CACHE_KEY").lower()
    expected_sha256 = env("MODEL_EXPECTED_SHA256").lower()
    if not CACHE_KEY_PATTERN.fullmatch(key) or key != expected_sha256:
        raise ValueError("MODEL_CACHE_KEY must equal the attested SHA-256")
    return root, key


@contextmanager
def cache_file_lock(path: Path, *, exclusive: bool, blocking: bool = True):
    path.parent.mkdir(parents=True, exist_ok=True)
    if fcntl is None:
        if exclusive:
            path.touch(exist_ok=True)
        elif not path.is_file():
            raise FileNotFoundError(f"model cache lock does not exist: {path}")
        lock_key = str(path.absolute()).lower()
        with _LOCAL_LOCKS_GUARD:
            local_lock = _LOCAL_LOCKS.setdefault(lock_key, threading.RLock())
        acquired = local_lock.acquire(blocking=blocking)
        try:
            yield acquired
        finally:
            if acquired:
                local_lock.release()
        return

    mode = "a+b" if exclusive else "rb"
    with path.open(mode) as handle:
        operation = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        if not blocking:
            operation |= fcntl.LOCK_NB
        try:
            fcntl.flock(handle.fileno(), operation)
        except BlockingIOError:
            yield False
            return
        try:
            yield True
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def download_object_to_file(
    client: Minio,
    bucket: str,
    object_name: str,
    target: Path,
    expected_sha256: str,
    expected_size: int,
) -> int:
    if not object_name:
        raise ValueError("object name is empty")
    log(f"stream model object from minio: {bucket}/{object_name}")
    target.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    downloaded = 0
    response = client.get_object(bucket, object_name)
    try:
        with target.open("xb") as output:
            while True:
                chunk = response.read(CACHE_DOWNLOAD_CHUNK_BYTES)
                if not chunk:
                    break
                downloaded += len(chunk)
                if downloaded > expected_size:
                    raise ValueError(
                        f"model artifact size exceeds expected {expected_size} bytes"
                    )
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
    finally:
        response.close()
        response.release_conn()

    if downloaded != expected_size:
        raise ValueError(
            f"model artifact size mismatch: expected {expected_size}, got {downloaded}"
        )
    actual_sha256 = digest.hexdigest()
    if actual_sha256 != expected_sha256:
        raise ValueError(
            f"model artifact SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}"
        )
    return downloaded


def remove_cache_path(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink(missing_ok=True)
    elif path.exists():
        shutil.rmtree(path)


def directory_size(path: Path) -> int:
    if path.is_symlink():
        raise ValueError(f"cache path must not be a symlink: {path}")
    if not path.exists():
        return 0
    total = 0
    for child in path.rglob("*"):
        if child.is_symlink():
            raise ValueError(f"cache entry must not contain symlinks: {child}")
        if child.is_file():
            total += child.stat().st_size
    return total


def cache_entry_is_valid(entry: Path, key: str, expected_size: int) -> bool:
    marker = entry / ".complete.json"
    data_dir = entry / "data"
    try:
        if entry.is_symlink() or marker.is_symlink() or data_dir.is_symlink():
            return False
        if not entry.is_dir() or not marker.is_file() or not data_dir.is_dir():
            return False
        payload = json.loads(marker.read_text(encoding="utf-8"))
        if (
            payload.get("schemaVersion") != CACHE_SCHEMA_VERSION
            or payload.get("sha256") != key
            or payload.get("artifactSizeBytes") != expected_size
        ):
            return False
        expected_data_size = payload.get("dataSizeBytes")
        return (
            isinstance(expected_data_size, int)
            and expected_data_size > 0
            and directory_size(data_dir) == expected_data_size
        )
    except (OSError, ValueError, json.JSONDecodeError):
        return False


def write_cache_marker(
    entry: Path,
    *,
    key: str,
    artifact_size: int,
    data_size: int,
    storage_path: str,
) -> None:
    marker_tmp = entry / ".complete.json.tmp"
    marker = entry / ".complete.json"
    payload = {
        "schemaVersion": CACHE_SCHEMA_VERSION,
        "sha256": key,
        "artifactSizeBytes": artifact_size,
        "dataSizeBytes": data_size,
        "storagePath": storage_path,
        "createdAtEpochSeconds": int(time.time()),
    }
    with marker_tmp.open("x", encoding="utf-8") as output:
        json.dump(payload, output, ensure_ascii=False, sort_keys=True)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(marker_tmp, marker)


def cache_usage(entries_dir: Path) -> int:
    total = 0
    if not entries_dir.exists():
        return total
    for entry in entries_dir.iterdir():
        total += directory_size(entry)
    return total


def read_cache_marker(entry: Path, key: str) -> dict:
    marker = entry / ".complete.json"
    if marker.is_symlink() or not marker.is_file() or marker.stat().st_size > 64 * 1024:
        return {}
    try:
        payload = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    if payload.get("schemaVersion") != CACHE_SCHEMA_VERSION or payload.get("sha256") != key:
        return {}
    return payload


def inspect_model_cache(root: Path) -> dict:
    entries_dir = root / "entries"
    locks_dir = root / "locks"
    entries_dir.mkdir(parents=True, exist_ok=True)
    locks_dir.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []

    with cache_file_lock(root / "capacity.lock", exclusive=True) as acquired:
        if not acquired:  # pragma: no cover - blocking locks always acquire.
            raise RuntimeError("failed to acquire model cache capacity lock")
        for entry in sorted(entries_dir.iterdir(), key=lambda path: path.name):
            key = entry.name.lower()
            if not CACHE_KEY_PATTERN.fullmatch(key) or entry.is_symlink() or not entry.is_dir():
                continue
            marker = read_cache_marker(entry, key)
            lock_path = locks_dir / f"{key}.lock"
            with cache_file_lock(lock_path, exclusive=True, blocking=False) as lock_acquired:
                in_use = not lock_acquired
            try:
                size_bytes = directory_size(entry)
                last_used = int((entry / ".complete.json").stat().st_mtime)
            except (OSError, ValueError):
                size_bytes = 0
                last_used = 0
            entries.append({
                "sha256": key,
                "storagePath": str(marker.get("storagePath", "")),
                "artifactSizeBytes": int(marker.get("artifactSizeBytes", 0) or 0),
                "dataSizeBytes": int(marker.get("dataSizeBytes", 0) or 0),
                "diskSizeBytes": size_bytes,
                "createdAtEpochSeconds": int(marker.get("createdAtEpochSeconds", 0) or 0),
                "lastUsedAtEpochSeconds": last_used,
                "inUse": in_use,
                "valid": bool(marker),
            })

        used_bytes = cache_usage(entries_dir)
        disk = shutil.disk_usage(root)

    return {
        "usedBytes": used_bytes,
        "diskFreeBytes": disk.free,
        "diskTotalBytes": disk.total,
        "entries": entries,
    }


def clear_model_cache(root: Path, command: dict) -> dict:
    clear_all = command.get("clearAll") is True
    raw_keys = command.get("keys", [])
    if not isinstance(raw_keys, list) or len(raw_keys) > 1000:
        raise ValueError("keys must be an array with at most 1000 entries")
    keys: list[str] = []
    for raw_key in raw_keys:
        key = str(raw_key).strip().lower()
        if not CACHE_KEY_PATTERN.fullmatch(key):
            raise ValueError("every cache key must be a SHA-256 digest")
        if key not in keys:
            keys.append(key)
    if clear_all == bool(keys):
        raise ValueError("choose either clearAll or one or more keys")

    entries_dir = root / "entries"
    locks_dir = root / "locks"
    entries_dir.mkdir(parents=True, exist_ok=True)
    locks_dir.mkdir(parents=True, exist_ok=True)
    if clear_all:
        keys = sorted(
            path.name.lower()
            for path in entries_dir.iterdir()
            if CACHE_KEY_PATTERN.fullmatch(path.name.lower())
        )

    cleared: list[str] = []
    in_use: list[str] = []
    not_found: list[str] = []
    with cache_file_lock(root / "capacity.lock", exclusive=True) as acquired:
        if not acquired:  # pragma: no cover - blocking locks always acquire.
            raise RuntimeError("failed to acquire model cache capacity lock")
        for key in keys:
            entry = entries_dir / key
            if entry.is_symlink() or not entry.exists():
                not_found.append(key)
                continue
            with cache_file_lock(
                locks_dir / f"{key}.lock", exclusive=True, blocking=False
            ) as entry_acquired:
                if not entry_acquired:
                    in_use.append(key)
                    continue
                remove_cache_path(entry)
                cleared.append(key)

    return {"cleared": cleared, "inUse": in_use, "notFound": not_found}


def manage_model_cache() -> dict:
    root = validate_cache_root()
    raw_command = env("MODEL_CACHE_COMMAND_JSON", '{"action":"inspect"}')
    if len(raw_command.encode("utf-8")) > 64 * 1024:
        raise ValueError("MODEL_CACHE_COMMAND_JSON is too large")
    try:
        command = json.loads(raw_command)
    except json.JSONDecodeError as exc:
        raise ValueError("MODEL_CACHE_COMMAND_JSON is invalid") from exc
    if not isinstance(command, dict):
        raise ValueError("MODEL_CACHE_COMMAND_JSON must be an object")
    action = command.get("action")
    if action == "inspect":
        return inspect_model_cache(root)
    if action == "clear":
        return clear_model_cache(root, command)
    raise ValueError("unsupported model cache action")


def ensure_cache_capacity(
    root: Path,
    *,
    protected_key: str,
    projected_entry_bytes: int,
    required_working_bytes: int,
    max_bytes: int,
    min_free_bytes: int,
    grace_seconds: int,
) -> None:
    entries_dir = root / "entries"
    locks_dir = root / "locks"
    entries_dir.mkdir(parents=True, exist_ok=True)
    locks_dir.mkdir(parents=True, exist_ok=True)

    def capacity_state() -> tuple[bool, int, int]:
        used = cache_usage(entries_dir)
        free = shutil.disk_usage(root).free
        fits = (
            used + projected_entry_bytes <= max_bytes
            and free >= required_working_bytes + min_free_bytes
        )
        return fits, used, free

    fits, used, free = capacity_state()
    if fits:
        return

    now = time.time()
    candidates: list[tuple[float, Path]] = []
    for entry in entries_dir.iterdir():
        if entry.name == protected_key or not CACHE_KEY_PATTERN.fullmatch(entry.name):
            continue
        marker = entry / ".complete.json"
        try:
            last_used = marker.stat().st_mtime if marker.is_file() else entry.stat().st_mtime
        except OSError:
            last_used = 0
        candidates.append((last_used, entry))

    for last_used, entry in sorted(candidates, key=lambda item: item[0]):
        if now - last_used < grace_seconds:
            continue
        lock_path = locks_dir / f"{entry.name}.lock"
        with cache_file_lock(lock_path, exclusive=True, blocking=False) as acquired:
            if not acquired:
                continue
            log(f"evict model cache entry: {entry.name}")
            remove_cache_path(entry)
        fits, used, free = capacity_state()
        if fits:
            return

    raise RuntimeError(
        "insufficient model cache capacity: "
        f"used={used}, projected={projected_entry_bytes}, free={free}, "
        f"max={max_bytes}, minFree={min_free_bytes}, working={required_working_bytes}"
    )


def inspect_cache_zip(archive_path: Path, max_expanded_bytes: int) -> int:
    total_size = 0
    file_count = 0
    names: set[str] = set()
    with zipfile.ZipFile(archive_path) as archive:
        for member in archive.infolist():
            name = normalize_zip_name(member.filename)
            if name.endswith("/"):
                continue
            file_count += 1
            if file_count > CACHE_MAX_ZIP_ENTRIES:
                raise ValueError(
                    f"model archive contains more than {CACHE_MAX_ZIP_ENTRIES} files"
                )
            if not is_safe_zip_member(name):
                raise ValueError(f"unsafe zip path: {name}")
            unix_mode = (member.external_attr >> 16) & 0o170000
            if stat.S_ISLNK(unix_mode):
                raise ValueError(f"model archive contains a symbolic link: {name}")
            if name in names:
                raise ValueError(f"model archive contains duplicate path: {name}")
            names.add(name)
            total_size += member.file_size
            if total_size > max_expanded_bytes:
                raise ValueError(
                    f"model archive expands beyond cache limit {max_expanded_bytes}"
                )
    if file_count == 0 or total_size <= 0:
        raise ValueError("model archive contains no non-empty files")
    return total_size


def extract_cache_zip(archive_path: Path, destination: Path, expected_size: int) -> int:
    written_total = 0
    destination.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(archive_path) as archive:
        for member in archive.infolist():
            name = normalize_zip_name(member.filename)
            if name.endswith("/"):
                continue
            if not is_safe_zip_member(name):
                raise ValueError(f"unsafe zip path: {name}")
            unix_mode = (member.external_attr >> 16) & 0o170000
            if stat.S_ISLNK(unix_mode):
                raise ValueError(f"model archive contains a symbolic link: {name}")
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            member_written = 0
            with archive.open(member, "r") as source, target.open("xb") as output:
                while True:
                    chunk = source.read(CACHE_DOWNLOAD_CHUNK_BYTES)
                    if not chunk:
                        break
                    member_written += len(chunk)
                    written_total += len(chunk)
                    if written_total > expected_size:
                        raise ValueError("model archive expanded size changed during extraction")
                    output.write(chunk)
            if member_written != member.file_size:
                raise ValueError(f"model archive member size mismatch: {name}")
    if written_total != expected_size:
        raise ValueError(
            f"model archive expanded size mismatch: expected {expected_size}, got {written_total}"
        )
    return written_total


def cache_object_file_name(object_name: str) -> str:
    normalized = normalize_zip_name(object_name)
    file_name = normalized.rsplit("/", 1)[-1]
    if not file_name or file_name in {".", ".."} or "\x00" in file_name:
        raise ValueError("model object has an unsafe file name")
    return file_name


def prune_stale_cache_temp(root: Path, max_age_seconds: int = 24 * 60 * 60) -> None:
    temp_dir = root / "tmp"
    temp_dir.mkdir(parents=True, exist_ok=True)
    threshold = time.time() - max_age_seconds
    for path in temp_dir.iterdir():
        try:
            if path.stat().st_mtime < threshold:
                remove_cache_path(path)
        except OSError:
            continue


def prepare_model_cache(client: Minio, bucket: str) -> Path:
    root, key = validate_cache_root_and_key()
    expected_size = required_cache_int("MODEL_EXPECTED_SIZE_BYTES", 1)
    max_bytes = required_cache_int("MODEL_CACHE_MAX_BYTES", 1)
    min_free_bytes = required_cache_int("MODEL_CACHE_MIN_FREE_BYTES", 0)
    grace_seconds = max(int_env("MODEL_CACHE_EVICTION_GRACE_SECONDS", 600), 0)
    if expected_size > max_bytes:
        raise ValueError("model artifact is larger than MODEL_CACHE_MAX_BYTES")

    storage_path = env("MODEL_STORAGE_PATH")
    if not storage_path:
        raise ValueError("MODEL_STORAGE_PATH is empty")

    entries_dir = root / "entries"
    locks_dir = root / "locks"
    temp_dir = root / "tmp"
    entries_dir.mkdir(parents=True, exist_ok=True)
    locks_dir.mkdir(parents=True, exist_ok=True)
    temp_dir.mkdir(parents=True, exist_ok=True)
    prune_stale_cache_temp(root)

    entry = entries_dir / key
    lock_path = locks_dir / f"{key}.lock"
    capacity_lock_path = root / "capacity.lock"
    unique = f"{key}.{os.getpid()}.{uuid.uuid4().hex}"
    archive_path = temp_dir / f"{unique}.artifact"
    temp_entry = temp_dir / f"{unique}.entry"

    with (
        cache_file_lock(capacity_lock_path, exclusive=True) as capacity_acquired,
        cache_file_lock(lock_path, exclusive=True) as acquired,
    ):
        if not acquired:  # pragma: no cover - blocking locks always acquire.
            raise RuntimeError(f"failed to acquire model cache lock: {key}")
        if not capacity_acquired:  # pragma: no cover - blocking locks always acquire.
            raise RuntimeError("failed to acquire model cache capacity lock")

        if cache_entry_is_valid(entry, key, expected_size):
            marker = entry / ".complete.json"
            os.utime(marker, None)
            log(f"model cache hit: {key}")
            try:
                ensure_cache_capacity(
                    root,
                    protected_key=key,
                    projected_entry_bytes=0,
                    required_working_bytes=0,
                    max_bytes=max_bytes,
                    min_free_bytes=min_free_bytes,
                    grace_seconds=grace_seconds,
                )
            except RuntimeError as exc:
                log(f"model cache cleanup deferred: {exc}")
            return entry / "data"

        if entry.exists() or entry.is_symlink():
            log(f"remove incomplete model cache entry: {key}")
            remove_cache_path(entry)

        try:
            ensure_cache_capacity(
                root,
                protected_key=key,
                projected_entry_bytes=expected_size,
                required_working_bytes=expected_size,
                max_bytes=max_bytes,
                min_free_bytes=min_free_bytes,
                grace_seconds=grace_seconds,
            )
            download_object_to_file(
                client,
                bucket,
                storage_path,
                archive_path,
                key,
                expected_size,
            )

            temp_entry.mkdir(parents=False, exist_ok=False)
            data_dir = temp_entry / "data"
            if storage_path.lower().endswith(".zip"):
                expanded_size = inspect_cache_zip(archive_path, max_bytes)
                ensure_cache_capacity(
                    root,
                    protected_key=key,
                    projected_entry_bytes=expanded_size,
                    required_working_bytes=expanded_size,
                    max_bytes=max_bytes,
                    min_free_bytes=min_free_bytes,
                    grace_seconds=grace_seconds,
                )
                data_size = extract_cache_zip(archive_path, data_dir, expanded_size)
            else:
                data_dir.mkdir(parents=False, exist_ok=False)
                target = data_dir / cache_object_file_name(storage_path)
                os.replace(archive_path, target)
                data_size = expected_size

            write_cache_marker(
                temp_entry,
                key=key,
                artifact_size=expected_size,
                data_size=data_size,
                storage_path=storage_path,
            )
            os.replace(temp_entry, entry)
            log(f"model cache populated: {key}, dataBytes={data_size}")
            return entry / "data"
        finally:
            remove_cache_path(archive_path)
            remove_cache_path(temp_entry)


@contextmanager
def model_cache_read_lock():
    if not truthy_env("MODEL_CACHE_ENABLED", False):
        yield
        return
    lock_path_value = env("MODEL_CACHE_LOCK_PATH")
    if not lock_path_value:
        raise ValueError("MODEL_CACHE_LOCK_PATH is empty")
    lock_path = Path(lock_path_value)
    with cache_file_lock(lock_path, exclusive=False) as acquired:
        if not acquired:  # pragma: no cover - blocking locks always acquire.
            raise RuntimeError("failed to acquire model cache read lock")
        log("model cache read lock acquired")
        yield


def normalize_zip_name(name: str) -> str:
    return (name or "").replace("\\", "/")


def is_safe_zip_member(name: str) -> bool:
    normalized = normalize_zip_name(name)
    if not normalized or normalized.endswith("/"):
        return False
    path = PurePosixPath(normalized)
    if path.is_absolute():
        return False
    return ".." not in path.parts and "\x00" not in normalized


def safe_extract_zip(data: bytes, dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(BytesIO(data)) as archive:
        for member in archive.infolist():
            name = normalize_zip_name(member.filename)
            if name.endswith("/"):
                continue
            if not is_safe_zip_member(name):
                raise ValueError(f"unsafe zip path: {name}")
            target = dest / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member, "r") as src, target.open("wb") as dst:
                dst.write(src.read())


def materialize_object(data: bytes, object_name: str, dest: Path) -> Path:
    dest.mkdir(parents=True, exist_ok=True)
    if object_name.lower().endswith(".zip"):
        safe_extract_zip(data, dest)
        return dest
    file_name = object_name.rsplit("/", 1)[-1] or "input.bin"
    target = dest / file_name
    target.write_bytes(data)
    return target


def truthy_env(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off"}


def int_env(name: str, default: int) -> int:
    raw = os.environ.get(name, "")
    try:
        return int(raw.strip()) if raw.strip() else default
    except ValueError:
        return default


def append_process_output(output: str | None) -> None:
    if not output:
        return
    with LOG_FILE.open("a", encoding="utf-8") as file:
        file.write(output)
        if not output.endswith("\n"):
            file.write("\n")


def install_script_requirements() -> None:
    if not truthy_env("INFERENCE_INSTALL_REQUIREMENTS", True):
        log("script requirements installation disabled")
        return

    requirements_name = env("INFERENCE_REQUIREMENTS_FILE", "requirements.txt")
    requirements_path = SCRIPT_DIR / requirements_name
    if not requirements_path.exists() or not requirements_path.is_file():
        log(f"script requirements not found: {requirements_name}")
        return

    timeout_seconds = int_env("INFERENCE_PIP_TIMEOUT_SECONDS", 1800)
    retries = int_env("INFERENCE_PIP_RETRIES", 3)
    index_url = env("INFERENCE_PIP_INDEX_URL", DEFAULT_PIP_INDEX_URL)
    extra_index_url = env("INFERENCE_PIP_EXTRA_INDEX_URL")
    trusted_host = env("INFERENCE_PIP_TRUSTED_HOST", "pypi.tuna.tsinghua.edu.cn")

    command = [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--user",
        "--no-cache-dir",
        "--timeout",
        str(max(timeout_seconds, 60)),
        "--retries",
        str(max(retries, 0)),
        "--prefer-binary",
        "-r",
        str(requirements_path),
    ]
    if index_url:
        command.extend(["-i", index_url])
    if extra_index_url:
        command.extend(["--extra-index-url", extra_index_url])
    if trusted_host:
        command.extend(["--trusted-host", trusted_host])

    log(f"install script requirements: {requirements_name}")
    completed = subprocess.run(
        command,
        cwd=str(SCRIPT_DIR),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        timeout=timeout_seconds,
    )
    append_process_output(completed.stdout)
    if completed.returncode != 0:
        raise RuntimeError(f"pip install failed with exit code {completed.returncode}")


def prepare_workspace(client: Minio, bucket: str) -> Path:
    WORKSPACE.mkdir(parents=True, exist_ok=True)
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    SCRIPT_DIR.mkdir(parents=True, exist_ok=True)
    INPUT_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    model_path = env("MODEL_STORAGE_PATH")
    script_path = env("SCRIPT_STORAGE_PATH")
    input_mode = env("INPUT_MODE")
    dataset_path = env("DATASET_STORAGE_PATH")
    input_object = env("INPUT_OBJECT_NAME")

    if truthy_env("MODEL_CACHE_ENABLED", False):
        if not MODEL_DIR.is_dir() or next(MODEL_DIR.iterdir(), None) is None:
            raise RuntimeError("model cache mount is missing or empty")
        log("use read-only physical-node model cache")
    else:
        materialize_object(
            download_object(client, bucket, model_path), model_path, MODEL_DIR
        )
    safe_extract_zip(download_object(client, bucket, script_path), SCRIPT_DIR)
    if input_mode == "DATASET_VERSION":
        return materialize_object(download_object(client, bucket, dataset_path), dataset_path, INPUT_DIR)
    return materialize_object(download_object(client, bucket, input_object), input_object, INPUT_DIR)


def callback(
    status: str,
    progress: int,
    result=None,
    error_message: str | None = None,
    log_path: str | None = None,
    output_path: str | None = None,
) -> None:
    url = env("BACKEND_CALLBACK_URL")
    token = env("INTERNAL_CALLBACK_TOKEN")
    if not url:
        log("BACKEND_CALLBACK_URL is empty, skip callback")
        return
    payload: dict = {"status": status, "progress": progress}
    if result is not None:
        payload["result"] = result
    if error_message:
        payload["errorMessage"] = error_message
    if log_path:
        payload["logPath"] = log_path
    if output_path:
        payload["outputPath"] = output_path
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "X-Internal-Token": token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            log(f"callback success: status={status}, http={response.status}")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"callback failed HTTP {exc.code}: {body}") from exc


def upload_file(client: Minio, bucket: str, object_name: str, path: Path, content_type: str) -> str:
    client.fput_object(bucket, object_name, str(path), content_type=content_type)
    return object_name


def upload_outputs(client: Minio, bucket: str, task_id: str) -> tuple[str, str]:
    base = env("OUTPUT_OBJECT_PREFIX", f"inference-results/{task_id}").strip().strip("/")
    log_object = upload_file(client, bucket, f"{base}/infer.log", LOG_FILE, "text/plain")
    for path in OUTPUT_DIR.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(OUTPUT_DIR).as_posix()
        upload_file(client, bucket, f"{base}/outputs/{rel}", path, "application/octet-stream")
    return f"minio://{log_object}", f"minio://{base}/outputs/"


def read_result() -> dict:
    result_file = OUTPUT_DIR / "result.json"
    if not result_file.exists():
        return {}
    try:
        return json.loads(result_file.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"rawResultError": str(exc)}


def run_user_script(input_path: Path) -> None:
    entry_file = env("SCRIPT_ENTRY_FILE")
    entry_path = SCRIPT_DIR / entry_file
    if not entry_path.exists() or not entry_path.is_file():
        raise FileNotFoundError(f"entryFile not found: {entry_file}")
    child_env = os.environ.copy()
    user_base = Path.home() / ".local"
    child_env["PATH"] = str(user_base / "bin") + os.pathsep + child_env.get("PATH", "")
    child_env.update(
        {
            "MODEL_DIR": str(MODEL_DIR),
            "INPUT_PATH": str(input_path),
            "OUTPUT_DIR": str(OUTPUT_DIR),
            "PARAMS_JSON": env("PARAMS_JSON", "{}"),
            "TASK_ID": env("INFERENCE_TASK_ID"),
            "INPUT_MODE": env("INPUT_MODE"),
        }
    )
    command = gpu_entry_command(entry_path)
    log(f"run inference script: {' '.join(command)}")
    completed = subprocess.run(
        command,
        cwd=str(SCRIPT_DIR),
        env=child_env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    with LOG_FILE.open("a", encoding="utf-8") as file:
        file.write(completed.stdout or "")
    if completed.returncode != 0:
        raise RuntimeError(f"inference script failed with exit code {completed.returncode}")


def validate_selected_gpu() -> None:
    """核对 DRA 实际暴露的卡，避免页面选择与容器拿到的设备不一致。"""
    selected_uuid = env("TSS_SELECTED_GPU_UUID")
    if not selected_uuid:
        return
    completed = subprocess.run(
        ["nvidia-smi", "--query-gpu=uuid", "--format=csv,noheader"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    visible_uuids = {line.strip() for line in completed.stdout.splitlines() if line.strip()}
    if completed.returncode != 0 or visible_uuids != {selected_uuid}:
        raise RuntimeError(
            f"selected GPU must be the only visible device: expected={selected_uuid}, "
            f"visible={sorted(visible_uuids)}"
        )
    log(
        f"selected physical GPU: host-index={env('TSS_HOST_GPU_INDEX', '?')}, uuid={selected_uuid}"
    )


def gpu_entry_command(entry_path: Path) -> list[str]:
    """GPU 显存预算必须在用户脚本同一进程里设置，父进程设置不会生效。"""
    raw_budget = env("TSS_GPU_MEMORY_LIMIT_MIB")
    if not raw_budget:
        return [sys.executable, str(entry_path)]
    budget_mib = int(raw_budget)
    if budget_mib <= 0:
        raise ValueError("TSS_GPU_MEMORY_LIMIT_MIB must be a positive integer")
    wrapper = WORKSPACE / ".tss_cuda_entrypoint.py"
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
    return [sys.executable, str(wrapper), str(budget_mib), str(entry_path)]


def main() -> int:
    mode = env("INFERENCE_WORKER_MODE")
    if mode == "manage-model-cache":
        try:
            result = manage_model_cache()
            print(MODEL_CACHE_RESULT_PREFIX + json.dumps(result, separators=(",", ":")), flush=True)
            return 0
        except Exception as exc:
            print(f"model cache management failed: {type(exc).__name__}: {exc}", flush=True)
            return 1

    client, bucket = minio_client()
    if mode == "prepare-model-cache":
        try:
            prepare_model_cache(client, bucket)
            return 0
        except Exception as exc:
            log(f"model cache initialization failed: {type(exc).__name__}: {exc}")
            return 1

    task_id = env("INFERENCE_TASK_ID")
    log_path = None
    output_path = None
    try:
        with model_cache_read_lock():
            validate_selected_gpu()
            callback("running", 10)
            input_path = prepare_workspace(client, bucket)
            callback("running", 35)
            install_script_requirements()
            callback("running", 55)
            run_user_script(input_path)
        callback("running", 85)
        result = read_result()
        log_path, output_path = upload_outputs(client, bucket, task_id)
        callback("success", 100, result=result, log_path=log_path, output_path=output_path)
        return 0
    except Exception as exc:
        log(f"inference failed: {type(exc).__name__}: {exc}")
        try:
            if task_id:
                log_path, output_path = upload_outputs(client, bucket, task_id)
        except Exception as upload_exc:
            log(f"failed to upload failure artifacts: {upload_exc}")
        callback(
            "failed",
            0,
            error_message=f"{type(exc).__name__}: {exc}",
            log_path=log_path,
            output_path=output_path,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
