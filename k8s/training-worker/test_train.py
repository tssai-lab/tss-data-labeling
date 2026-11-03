import hashlib
import importlib.util
import json
import os
import tempfile
import types
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("train.py")
SPEC = importlib.util.spec_from_file_location("tss_training_worker", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(worker)


def run_spec():
    artifact = {
        "versionId": "version-1",
        "objectName": "users/1/artifact.zip",
        "sha256": "a" * 64,
        "sizeBytes": 10,
        "format": "ZIP",
        "fileName": "artifact.zip",
        "archive": True,
        "requiredEntries": [],
    }
    code = dict(artifact)
    code.update({
        "entrypoint": "train.py",
        "approvalEvidenceId": "approval-1",
        "requiredEntries": ["train.py"],
    })
    return {
        "schemaVersion": "tss.training.run-spec/v1",
        "trainingId": "train-1",
        "createdAt": "2026-07-21T12:00:00Z",
        "plan": {"id": "test_plan", "version": "v1"},
        "trainingMode": "FROM_SCRATCH",
        "inputs": {"model": dict(artifact), "dataset": dict(artifact), "code": code},
        "execution": {
            "argv": ["python", "/workspace/job/code/train.py"],
            "workingDirectory": "/workspace/job/code",
        },
        "parameters": {"epochs": 1},
        "runtime": {
            "variantId": "cpu",
            "deviceType": "CPU",
            "image": "worker:test",
            "imagePullPolicy": "IfNotPresent",
        },
        "resources": {
            "profileId": "cpu-small",
            "cpuRequest": "1",
            "cpuLimit": "2",
            "memoryRequest": "1Gi",
            "memoryLimit": "2Gi",
            "ephemeralStorageLimit": "4Gi",
            "gpuCount": 0,
            "nodeSelector": {},
        },
        "workspace": {
            "modelDir": "/workspace/job/model",
            "dataDir": "/workspace/job/data",
            "codeDir": "/workspace/job/code",
            "configDir": "/workspace/job/config",
            "outputDir": "/workspace/job/output",
            "paramsFile": "/workspace/job/config/params.json",
        },
        "outputs": {
            "progressProtocol": "TSS_EVENT_JSONL_V1",
            "metricsPath": "metrics.json",
            "logPath": "train.log",
            "artifacts": [
                {
                    "path": "best.zip",
                    "role": "PRIMARY_MODEL",
                    "required": True,
                    "format": "MODEL_ZIP",
                    "publishAsModel": True,
                    "packaging": {
                        "type": "ZIP_SINGLE_FILE",
                        "sourcePath": "best.bin",
                        "entryName": "best.bin",
                    },
                },
                {
                    "path": "metrics.json",
                    "role": "METRICS",
                    "required": True,
                    "format": "JSON",
                    "publishAsModel": False,
                },
                {
                    "path": "train.log",
                    "role": "LOG",
                    "required": True,
                    "format": "TEXT",
                    "publishAsModel": False,
                },
            ],
        },
        "security": {
            "networkPolicy": "PLATFORM_SERVICES_ONLY",
            "runAsNonRoot": True,
            "allowPrivilegeEscalation": False,
            "automountServiceAccountToken": False,
            "maxRuntimeSeconds": 3600,
        },
    }


class GenericTrainingWorkerTest(unittest.TestCase):
    def test_put_json_returns_digest_of_exact_uploaded_bytes(self):
        class Client:
            def put_object(self, bucket, object_name, stream, size, content_type=None):
                self.bucket = bucket
                self.object_name = object_name
                self.data = stream.read()
                self.size = size
                self.content_type = content_type

        client = Client()
        digest, size = worker.put_json(client, "models", "output.json", {"b": 2, "a": 1})

        self.assertEqual(b'{"a":1,"b":2}', client.data)
        self.assertEqual(len(client.data), size)
        self.assertEqual(hashlib.sha256(client.data).hexdigest(), digest)

    def test_load_run_spec_verifies_exact_json_digest(self):
        raw = json.dumps(run_spec(), separators=(",", ":"))
        digest = hashlib.sha256(raw.encode()).hexdigest()
        with patch.dict(os.environ, {
            "TRAINING_ID": "train-1",
            "RUN_SPEC_JSON": raw,
            "RUN_SPEC_SHA256": digest,
        }, clear=False):
            loaded, actual = worker.load_run_spec()
        self.assertEqual("train-1", loaded["trainingId"])
        self.assertEqual(digest, actual)

    def test_load_run_spec_rejects_tampering(self):
        raw = json.dumps(run_spec(), separators=(",", ":"))
        with patch.dict(os.environ, {
            "RUN_SPEC_JSON": raw + " ",
            "RUN_SPEC_SHA256": hashlib.sha256(raw.encode()).hexdigest(),
        }, clear=False):
            with self.assertRaisesRegex(worker.WorkerError, "SHA-256 mismatch"):
                worker.load_run_spec()

    def test_safe_extract_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            archive_path = Path(temp) / "bad.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("../escape.txt", "forbidden")
            with self.assertRaises(worker.WorkerError):
                worker.safe_extract_zip(archive_path, Path(temp) / "out", archive_path.stat().st_size)
            self.assertFalse((Path(temp) / "escape.txt").exists())

    def test_declared_zip_packaging_is_generic(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            (output / "best.bin").write_bytes(b"trained-model")
            with patch.object(worker, "OUTPUT_DIR", output):
                worker.materialize_declared_packages(run_spec())
            with zipfile.ZipFile(output / "best.zip") as archive:
                self.assertEqual(["best.bin"], archive.namelist())
                self.assertEqual(b"trained-model", archive.read("best.bin"))

    def test_multifile_model_archive_uses_archive_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            model_zip = Path(temp) / "hf-model.zip"
            with zipfile.ZipFile(model_zip, "w") as archive:
                archive.writestr("config.json", "{}")
                archive.writestr("model.safetensors", b"weights")
            archive_digest = worker.sha256_file(model_zip)

            callback = worker.legacy_model_callback(
                {
                    "sha256": archive_digest,
                    "objectName": "training-results/train-1/artifacts/hf-model.zip",
                    "format": "HF_MODEL_ARCHIVE",
                    "sizeBytes": model_zip.stat().st_size,
                },
                model_zip,
            )

            self.assertEqual("hf-model.zip", callback["modelFileName"])
            self.assertEqual(archive_digest, callback["sha256"])

    def test_progress_event_is_data_not_code(self):
        event = worker.parse_training_event(
            'TSS_EVENT {"type":"progress","progress":42,"message":"epoch"}'
        )
        self.assertEqual(42, event["progress"])
        self.assertIsNone(worker.parse_training_event("ordinary stdout"))
        self.assertIsNone(worker.parse_training_event("TSS_EVENT not-json"))

    def test_cached_archive_model_validates_required_entries_without_download(self):
        with tempfile.TemporaryDirectory() as temp:
            model_dir = Path(temp) / "model"
            model_dir.mkdir()
            (model_dir / "config.json").write_text("{}", encoding="utf-8")
            artifact = run_spec()["inputs"]["model"]
            artifact["requiredEntries"] = ["config.json"]

            with patch.object(worker, "MODEL_DIR", model_dir):
                worker.validate_cached_model(artifact)

    def test_cached_model_rejects_missing_required_entry(self):
        with tempfile.TemporaryDirectory() as temp:
            model_dir = Path(temp) / "model"
            model_dir.mkdir()
            (model_dir / "weights.bin").write_bytes(b"cached")
            artifact = run_spec()["inputs"]["model"]
            artifact["requiredEntries"] = ["config.json"]

            with patch.object(worker, "MODEL_DIR", model_dir):
                with self.assertRaisesRegex(worker.WorkerError, "missing config.json"):
                    worker.validate_cached_model(artifact)

    def test_cache_lock_is_required_and_released(self):
        with tempfile.TemporaryDirectory() as temp:
            lock_path = Path(temp) / "model.lock"
            lock_path.touch()
            environment = {
                "MODEL_CACHE_ENABLED": "true",
                "MODEL_CACHE_LOCK_PATH": str(lock_path),
            }
            with patch.dict(os.environ, environment, clear=True):
                handle = worker.acquire_model_cache_read_lock()
                self.assertIsNotNone(handle)
                self.assertFalse(handle.closed)
                worker.release_model_cache_read_lock(handle)
                self.assertTrue(handle.closed)

    def test_cache_lock_rejects_missing_mount(self):
        with patch.dict(os.environ, {"MODEL_CACHE_ENABLED": "true"}, clear=True):
            with self.assertRaisesRegex(worker.WorkerError, "lock mount is missing"):
                worker.acquire_model_cache_read_lock()

    def test_gpu_budget_wraps_training_in_same_python_process(self):
        spec = run_spec()
        spec["resources"].update({
            "gpuUuid": "GPU-selected",
            "gpuMemoryLimitMiB": 4096,
        })
        with tempfile.TemporaryDirectory() as temp:
            config_dir = Path(temp)
            with (
                patch.dict(os.environ, {"TSS_GPU_MEMORY_LIMIT_MIB": "4096"}, clear=True),
                patch.object(worker, "CONFIG_DIR", config_dir),
            ):
                command = worker.training_entry_argv(spec)

            wrapper = Path(command[1])
            self.assertEqual("4096", command[2])
            self.assertEqual("/workspace/job/code/train.py", command[3])
            self.assertIn("torch.cuda.set_per_process_memory_fraction", wrapper.read_text())
            self.assertIn("sys.path[0] = str(Path(entry_file).resolve().parent)", wrapper.read_text())
            self.assertIn("TSS CUDA soft budget applied", wrapper.read_text())

    def test_selected_gpu_must_be_the_only_visible_device(self):
        spec = run_spec()
        spec["resources"]["gpuUuid"] = "GPU-selected"
        result = types.SimpleNamespace(returncode=0, stdout="GPU-selected\nGPU-other\n")
        with (
            patch.dict(os.environ, {"TSS_SELECTED_GPU_UUID": "GPU-selected"}, clear=True),
            patch.object(worker.subprocess, "run", return_value=result),
            self.assertRaisesRegex(worker.WorkerError, "expected only GPU-selected"),
        ):
            worker.validate_selected_gpu(spec)


if __name__ == "__main__":
    unittest.main()
