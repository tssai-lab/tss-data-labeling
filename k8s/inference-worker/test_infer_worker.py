from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import threading
import time
import types
import unittest
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

try:
    import minio  # noqa: F401
except ModuleNotFoundError:
    minio_stub = types.ModuleType("minio")

    class Minio:  # pragma: no cover - import-only test stub.
        pass

    minio_stub.Minio = Minio
    sys.modules["minio"] = minio_stub

import infer_worker


class FakeResponse(io.BytesIO):
    def __init__(self, payload: bytes, read_delay: float = 0):
        super().__init__(payload)
        self.read_delay = read_delay

    def read(self, size: int = -1) -> bytes:
        if self.read_delay:
            time.sleep(self.read_delay)
        return super().read(size)

    def release_conn(self) -> None:
        return None


class FakeMinio:
    def __init__(self, payload: bytes, read_delay: float = 0):
        self.payload = payload
        self.read_delay = read_delay
        self.calls = 0
        self._lock = threading.Lock()

    def get_object(self, bucket: str, object_name: str) -> FakeResponse:
        del bucket, object_name
        with self._lock:
            self.calls += 1
        return FakeResponse(self.payload, self.read_delay)


def model_zip() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("weights/model.bin", b"weight-data")
        archive.writestr("config.json", b'{"format":"test"}')
    return buffer.getvalue()


class ModelCacheTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "model-cache"
        self.root.mkdir()
        (self.root / ".tss-model-cache-root").write_text("tss-model-cache-v1\n")
        self.log_patch = patch.object(
            infer_worker,
            "LOG_FILE",
            Path(self.temp.name) / "infer.log",
        )
        self.log_patch.start()
        self.addCleanup(self.log_patch.stop)

    def cache_env(self, payload: bytes, digest: str | None = None) -> dict[str, str]:
        cache_key = digest or hashlib.sha256(payload).hexdigest()
        return {
            "MODEL_CACHE_ROOT": str(self.root),
            "MODEL_CACHE_KEY": cache_key,
            "MODEL_EXPECTED_SHA256": cache_key,
            "MODEL_EXPECTED_SIZE_BYTES": str(len(payload)),
            "MODEL_CACHE_MAX_BYTES": str(10 * 1024 * 1024),
            "MODEL_CACHE_MIN_FREE_BYTES": "0",
            "MODEL_CACHE_EVICTION_GRACE_SECONDS": "0",
            "MODEL_STORAGE_PATH": "users/7/models/model.zip",
        }

    def test_cache_miss_then_hit_downloads_once(self) -> None:
        payload = model_zip()
        client = FakeMinio(payload)
        with patch.dict(os.environ, self.cache_env(payload), clear=True):
            first = infer_worker.prepare_model_cache(client, "models")
            second = infer_worker.prepare_model_cache(client, "models")

        self.assertEqual(first, second)
        self.assertEqual(client.calls, 1)
        self.assertEqual((first / "weights/model.bin").read_bytes(), b"weight-data")
        self.assertTrue((first.parent / ".complete.json").is_file())

    def test_digest_mismatch_removes_partial_files(self) -> None:
        payload = model_zip()
        wrong_digest = "0" * 64
        client = FakeMinio(payload)
        with patch.dict(
            os.environ,
            self.cache_env(payload, digest=wrong_digest),
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                infer_worker.prepare_model_cache(client, "models")

        self.assertFalse((self.root / "entries" / wrong_digest).exists())
        self.assertEqual(list((self.root / "tmp").iterdir()), [])

    def test_incomplete_entry_is_replaced(self) -> None:
        payload = model_zip()
        digest = hashlib.sha256(payload).hexdigest()
        client = FakeMinio(payload)
        with patch.dict(os.environ, self.cache_env(payload), clear=True):
            data_dir = infer_worker.prepare_model_cache(client, "models")
            (data_dir.parent / ".complete.json").unlink()
            rebuilt = infer_worker.prepare_model_cache(client, "models")

        self.assertEqual(client.calls, 2)
        self.assertEqual((rebuilt / "weights/model.bin").read_bytes(), b"weight-data")
        self.assertTrue((rebuilt.parent / ".complete.json").is_file())
        self.assertEqual(rebuilt.parent.name, digest)

    def test_concurrent_requests_for_same_digest_download_once(self) -> None:
        payload = model_zip()
        client = FakeMinio(payload, read_delay=0.05)
        environment = self.cache_env(payload)

        def prepare() -> Path:
            return infer_worker.prepare_model_cache(client, "models")

        with patch.dict(os.environ, environment, clear=True):
            with ThreadPoolExecutor(max_workers=2) as executor:
                results = list(executor.map(lambda _: prepare(), range(2)))

        self.assertEqual(results[0], results[1])
        self.assertEqual(client.calls, 1)


    def test_zip_traversal_is_rejected_without_publishing_entry(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(
            buffer,
            "w",
            compression=zipfile.ZIP_DEFLATED,
        ) as archive:
            archive.writestr("../escape.bin", b"not-allowed")
        payload = buffer.getvalue()
        digest = hashlib.sha256(payload).hexdigest()
        client = FakeMinio(payload)

        with patch.dict(os.environ, self.cache_env(payload), clear=True):
            with self.assertRaisesRegex(ValueError, "unsafe zip path"):
                infer_worker.prepare_model_cache(client, "models")

        self.assertFalse((self.root / "entries" / digest).exists())
        self.assertEqual(list((self.root / "tmp").iterdir()), [])

    def test_zip_expansion_beyond_cache_limit_is_rejected(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(
            buffer,
            "w",
            compression=zipfile.ZIP_DEFLATED,
        ) as archive:
            archive.writestr("weights/model.bin", b"A" * 4096)
        payload = buffer.getvalue()
        environment = self.cache_env(payload)
        environment["MODEL_CACHE_MAX_BYTES"] = str(len(payload) + 64)
        digest = hashlib.sha256(payload).hexdigest()
        client = FakeMinio(payload)

        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(ValueError, "expands beyond cache limit"):
                infer_worker.prepare_model_cache(client, "models")

        self.assertFalse((self.root / "entries" / digest).exists())
        self.assertEqual(list((self.root / "tmp").iterdir()), [])

    def test_main_worker_skips_model_download_when_cache_is_mounted(self) -> None:
        workspace = Path(self.temp.name) / "workspace"
        model_dir = workspace / "model"
        script_dir = workspace / "script"
        input_dir = workspace / "input"
        output_dir = workspace / "output"
        model_dir.mkdir(parents=True)
        (model_dir / "weights.bin").write_bytes(b"cached")

        script_buffer = io.BytesIO()
        with zipfile.ZipFile(script_buffer, "w") as archive:
            archive.writestr("infer.py", "print('ok')\n")

        environment = {
            "MODEL_CACHE_ENABLED": "true",
            "MODEL_STORAGE_PATH": "users/7/models/model.zip",
            "SCRIPT_STORAGE_PATH": "users/7/scripts/script.zip",
            "INPUT_MODE": "SINGLE_OBJECT",
            "INPUT_OBJECT_NAME": "users/7/files/input.bin",
        }
        with (
            patch.dict(os.environ, environment, clear=True),
            patch.object(infer_worker, "WORKSPACE", workspace),
            patch.object(infer_worker, "MODEL_DIR", model_dir),
            patch.object(infer_worker, "SCRIPT_DIR", script_dir),
            patch.object(infer_worker, "INPUT_DIR", input_dir),
            patch.object(infer_worker, "OUTPUT_DIR", output_dir),
            patch.object(
                infer_worker,
                "download_object",
                side_effect=[script_buffer.getvalue(), b"input"],
            ) as download,
        ):
            input_path = infer_worker.prepare_workspace(object(), "models")

        self.assertEqual(input_path.read_bytes(), b"input")
        self.assertEqual(download.call_count, 2)
        downloaded_names = [call.args[2] for call in download.call_args_list]
        self.assertEqual(
            downloaded_names,
            ["users/7/scripts/script.zip", "users/7/files/input.bin"],
        )

    def test_admin_inspect_reports_capacity_and_entry(self) -> None:
        payload = model_zip()
        digest = hashlib.sha256(payload).hexdigest()
        with patch.dict(os.environ, self.cache_env(payload), clear=True):
            infer_worker.prepare_model_cache(FakeMinio(payload), "models")
            result = infer_worker.inspect_model_cache(self.root)

        self.assertGreater(result["usedBytes"], 0)
        self.assertGreater(result["diskFreeBytes"], 0)
        self.assertEqual(digest, result["entries"][0]["sha256"])
        self.assertTrue(result["entries"][0]["valid"])
        self.assertFalse(result["entries"][0]["inUse"])

    def test_admin_can_clear_selected_entry_without_touching_other_entry(self) -> None:
        first_payload = model_zip()
        second_buffer = io.BytesIO()
        with zipfile.ZipFile(second_buffer, "w") as archive:
            archive.writestr("weights/second.bin", b"second")
        second_payload = second_buffer.getvalue()
        first_key = hashlib.sha256(first_payload).hexdigest()
        second_key = hashlib.sha256(second_payload).hexdigest()

        with patch.dict(os.environ, self.cache_env(first_payload), clear=True):
            infer_worker.prepare_model_cache(FakeMinio(first_payload), "models")
        with patch.dict(os.environ, self.cache_env(second_payload), clear=True):
            infer_worker.prepare_model_cache(FakeMinio(second_payload), "models")
            result = infer_worker.clear_model_cache(
                self.root,
                {"action": "clear", "clearAll": False, "keys": [first_key]},
            )

        self.assertEqual([first_key], result["cleared"])
        self.assertFalse((self.root / "entries" / first_key).exists())
        self.assertTrue((self.root / "entries" / second_key).exists())

    def test_admin_clear_skips_entry_while_worker_holds_read_lock(self) -> None:
        payload = model_zip()
        key = hashlib.sha256(payload).hexdigest()
        environment = self.cache_env(payload)
        with patch.dict(os.environ, environment, clear=True):
            infer_worker.prepare_model_cache(FakeMinio(payload), "models")
            entered = threading.Event()
            release = threading.Event()

            def hold_read_lock() -> None:
                lock_path = self.root / "locks" / f"{key}.lock"
                with infer_worker.cache_file_lock(lock_path, exclusive=False):
                    entered.set()
                    release.wait(timeout=5)

            holder = threading.Thread(target=hold_read_lock)
            holder.start()
            self.assertTrue(entered.wait(timeout=2))
            try:
                result = infer_worker.clear_model_cache(
                    self.root,
                    {"action": "clear", "clearAll": False, "keys": [key]},
                )
            finally:
                release.set()
                holder.join(timeout=2)

        self.assertEqual([key], result["inUse"])
        self.assertTrue((self.root / "entries" / key).exists())

    def test_admin_clear_rejects_invalid_key_and_ambiguous_scope(self) -> None:
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            infer_worker.clear_model_cache(
                self.root,
                {"action": "clear", "clearAll": False, "keys": ["../escape"]},
            )
        with self.assertRaisesRegex(ValueError, "either clearAll"):
            infer_worker.clear_model_cache(
                self.root,
                {"action": "clear", "clearAll": True, "keys": ["a" * 64]},
            )

    def test_admin_mode_does_not_create_minio_client(self) -> None:
        command = json.dumps({"action": "inspect"})
        with (
            patch.dict(os.environ, {
                "INFERENCE_WORKER_MODE": "manage-model-cache",
                "MODEL_CACHE_ROOT": str(self.root),
                "MODEL_CACHE_COMMAND_JSON": command,
            }, clear=True),
            patch.object(infer_worker, "minio_client", side_effect=AssertionError("unexpected")),
        ):
            self.assertEqual(0, infer_worker.main())

    def test_gpu_budget_runs_user_script_through_cuda_wrapper(self) -> None:
        workspace = Path(self.temp.name) / "workspace"
        workspace.mkdir()
        entry = workspace / "infer.py"
        entry.write_text("print('ok')\n", encoding="utf-8")
        with (
            patch.dict(os.environ, {"TSS_GPU_MEMORY_LIMIT_MIB": "4096"}, clear=True),
            patch.object(infer_worker, "WORKSPACE", workspace),
        ):
            command = infer_worker.gpu_entry_command(entry)

        wrapper = Path(command[1])
        self.assertEqual("4096", command[2])
        self.assertEqual(str(entry), command[3])
        self.assertIn("torch.cuda.set_per_process_memory_fraction", wrapper.read_text())
        self.assertIn("sys.path[0] = str(Path(entry_file).resolve().parent)", wrapper.read_text())
        self.assertIn("TSS CUDA soft budget applied", wrapper.read_text())

    def test_selected_gpu_uuid_must_be_visible(self) -> None:
        result = types.SimpleNamespace(returncode=0, stdout="GPU-other\n")
        with (
            patch.dict(os.environ, {"TSS_SELECTED_GPU_UUID": "GPU-selected"}, clear=True),
            patch.object(infer_worker.subprocess, "run", return_value=result),
            self.assertRaisesRegex(RuntimeError, "only visible device"),
        ):
            infer_worker.validate_selected_gpu()

if __name__ == "__main__":
    unittest.main()
