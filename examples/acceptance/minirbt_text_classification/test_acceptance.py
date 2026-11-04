import importlib.util
import json
import tempfile
import unittest
from unittest import mock
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def load_module(name: str, file_name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / file_name)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


train = load_module("minirbt_acceptance_train", "train.py")
infer = load_module("minirbt_acceptance_infer", "infer.py")
prepare = load_module("minirbt_acceptance_prepare", "prepare.py")


class DatasetContractTest(unittest.TestCase):
    def write_package(self, root: Path, *, labels=None, rows=None):
        labels = labels or ["负面", "正面"]
        rows = rows or {
            "train": [
                {"id": "train-n", "text": "体验很差", "label": "负面"},
                {"id": "train-p", "text": "体验很好", "label": "正面"},
            ],
            "validation": [{"id": "val-p", "text": "运行顺利", "label": "正面"}],
            "test": [{"id": "test-n", "text": "任务失败", "label": "负面"}],
        }
        (root / "data").mkdir(parents=True)
        (root / "dataset.json").write_text(
            json.dumps({"schemaVersion": train.DATASET_SCHEMA, "labels": labels}, ensure_ascii=False),
            encoding="utf-8",
        )
        for split, records in rows.items():
            (root / "data" / f"{split}.jsonl").write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )

    def test_accepts_complete_three_split_dataset(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write_package(root)
            labels, splits = train.load_dataset_package(root)
            self.assertEqual(["负面", "正面"], labels)
            self.assertEqual({"train", "validation", "test"}, set(splits))

    def test_rejects_empty_text_unknown_label_duplicate_id_and_single_class_training(self):
        cases = []
        base = {
            "train": [
                {"id": "a", "text": "差", "label": "负面"},
                {"id": "b", "text": "好", "label": "正面"},
            ],
            "validation": [{"id": "c", "text": "一般", "label": "正面"}],
            "test": [{"id": "d", "text": "不好", "label": "负面"}],
        }
        empty = {key: [dict(item) for item in value] for key, value in base.items()}
        empty["test"][0]["text"] = " "
        cases.append(empty)
        unknown = {key: [dict(item) for item in value] for key, value in base.items()}
        unknown["test"][0]["label"] = "未知"
        cases.append(unknown)
        duplicate = {key: [dict(item) for item in value] for key, value in base.items()}
        duplicate["test"][0]["id"] = "a"
        cases.append(duplicate)
        single_class = {key: [dict(item) for item in value] for key, value in base.items()}
        single_class["train"] = [single_class["train"][0]]
        cases.append(single_class)
        for rows in cases:
            with self.subTest(rows=rows), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.write_package(root, rows=rows)
                with self.assertRaises(ValueError):
                    train.load_dataset_package(root)

    def test_inference_reads_dataset_split_and_plain_text(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write_package(root)
            self.assertEqual("任务失败", infer.load_records(root, "test")[0]["text"])
            plain = root / "input.txt"
            plain.write_text("第一条\n\n第二条\n", encoding="utf-8")
            self.assertEqual(2, len(infer.load_records(plain, "test")))

    def test_generated_dataset_has_expected_small_complete_shape(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "dataset.zip"
            prepare.build_dataset_zip(output)
            import zipfile

            with zipfile.ZipFile(output) as archive:
                self.assertEqual(
                    {
                        "dataset.json",
                        "data/train.jsonl",
                        "data/validation.jsonl",
                        "data/test.jsonl",
                    },
                    set(archive.namelist()),
                )
                train_rows = [
                    json.loads(line)
                    for line in archive.read("data/train.jsonl").decode("utf-8").splitlines()
                ]
                self.assertEqual({"负面", "正面"}, {row["label"] for row in train_rows[:2]})

    def test_generated_model_archive_uses_platform_accepted_file_names(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cache = root / "cache"
            output = root / "model.zip"

            def fake_download(_url, target, _expected_sha256):
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"acceptance-fixture")

            with mock.patch.object(prepare, "download_verified", side_effect=fake_download):
                prepare.build_model_zip(output, cache)

            import zipfile

            with zipfile.ZipFile(output) as archive:
                names = set(archive.namelist())
            self.assertEqual(
                {
                    "LICENSE.txt",
                    "UPSTREAM_README.md",
                    "config.json",
                    "model.yaml",
                    "pytorch_model.bin",
                    "vocab.txt",
                },
                names,
            )
            self.assertTrue(all(Path(name).suffix for name in names))

    def test_rejects_invalid_training_and_inference_limits(self):
        with self.assertRaisesRegex(ValueError, "batchSize"):
            train.validate_training_parameters(
                epochs=2,
                batch_size=0,
                learning_rate=0.0001,
                weight_decay=0.01,
                max_length=64,
                seed=42,
                max_train=0,
                max_eval=0,
            )
        with tempfile.TemporaryDirectory() as temp:
            empty_json = Path(temp) / "empty.json"
            empty_json.write_text('[{"text":" "}]', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "no non-empty text"):
                infer.load_records(empty_json, "test")
        with self.assertRaisesRegex(ValueError, "split"):
            infer.validate_inference_parameters(
                split="../../secret", max_texts=10, batch_size=8, max_length=64
            )

    def test_training_metrics_use_documented_visualization_names(self):
        metrics = train.build_training_event_metrics(
            0.25,
            {"accuracy": 0.9, "precision": 0.8, "recall": 0.7, "f1": 0.75},
        )

        self.assertEqual(
            {
                "train_loss": 0.25,
                "val_accuracy": 0.9,
                "val_precision": 0.8,
                "val_recall": 0.7,
                "val_f1": 0.75,
            },
            metrics,
        )
        self.assertFalse(any(key.startswith("validation_") for key in metrics))

    def test_training_device_accepts_cpu_and_one_visible_cuda_device(self):
        class FakeDevice:
            def __init__(self, value):
                self.value = value
                self.type = value.split(":", 1)[0]

            def __str__(self):
                return self.value

        class FakeCuda:
            def __init__(self):
                self.available = True
                self.count = 1
                self.selected = None

            def is_available(self):
                return self.available

            def device_count(self):
                return self.count

            def set_device(self, index):
                self.selected = index

        class FakeTorch:
            def __init__(self):
                self.cuda = FakeCuda()

            @staticmethod
            def device(value):
                return FakeDevice(value)

        fake_torch = FakeTorch()
        self.assertEqual("cpu", str(train.resolve_torch_device(fake_torch, "cpu")))
        self.assertEqual("cuda:0", str(train.resolve_torch_device(fake_torch, "0")))
        self.assertEqual(0, fake_torch.cuda.selected)

    def test_training_device_rejects_missing_multiple_or_unapproved_cuda_devices(self):
        class FakeCuda:
            available = False
            count = 0

            def is_available(self):
                return self.available

            def device_count(self):
                return self.count

            @staticmethod
            def set_device(_index):
                raise AssertionError("unavailable or ambiguous CUDA must not be selected")

        class FakeTorch:
            cuda = FakeCuda()

            @staticmethod
            def device(value):
                return value

        with self.assertRaisesRegex(RuntimeError, "CUDA is unavailable"):
            train.resolve_torch_device(FakeTorch(), "0")
        FakeTorch.cuda.available = True
        FakeTorch.cuda.count = 2
        with self.assertRaisesRegex(RuntimeError, "exactly one visible CUDA device"):
            train.resolve_torch_device(FakeTorch(), "0")
        with self.assertRaisesRegex(ValueError, "device must be cpu"):
            train.resolve_torch_device(FakeTorch(), "1")

    def test_training_gpu_memory_budget_is_soft_limited_per_process(self):
        class Properties:
            total_memory = 32 * 1024 * 1024 * 1024

        class FakeCuda:
            applied = None

            @staticmethod
            def is_available():
                return True

            @staticmethod
            def device_count():
                return 1

            @staticmethod
            def get_device_properties(_index):
                return Properties()

            def set_per_process_memory_fraction(self, fraction, index):
                self.applied = (fraction, index)

        fake_torch = type("FakeTorch", (), {"cuda": FakeCuda()})()

        result = train.apply_gpu_memory_budget(fake_torch, "cuda:0", "8192")

        self.assertEqual(8192, result)
        self.assertEqual((0.25, 0), fake_torch.cuda.applied)

    def test_training_gpu_memory_budget_rejects_more_than_the_visible_card(self):
        class Properties:
            total_memory = 16 * 1024 * 1024 * 1024

        class FakeCuda:
            @staticmethod
            def is_available():
                return True

            @staticmethod
            def device_count():
                return 1

            @staticmethod
            def get_device_properties(_index):
                return Properties()

            @staticmethod
            def set_per_process_memory_fraction(_fraction, _index):
                raise AssertionError("oversized budget must not be applied")

        fake_torch = type("FakeTorch", (), {"cuda": FakeCuda()})()
        with self.assertRaisesRegex(ValueError, "exceeds visible GPU memory"):
            train.apply_gpu_memory_budget(fake_torch, "0", "32768")

    def test_long_inference_text_is_kept_in_bounded_task_output_preview_box(self):
        short_text = "短文本"
        long_text = "长" * (infer.INLINE_TEXT_PREVIEW_CHARS + 1)
        predictions = [
            {"index": 0, "text": short_text, "prediction": "正面"},
            {"index": 1, "text": long_text, "prediction": "负面"},
        ]
        with tempfile.TemporaryDirectory() as temp:
            output_dir = Path(temp)
            previews = infer.build_prediction_previews(predictions, output_dir)

            self.assertEqual(short_text, previews[0]["text"])
            self.assertEqual(short_text, previews[0]["inputPreview"]["text"])
            self.assertNotIn("text", previews[1])
            self.assertEqual("previews/text/1.txt", previews[1]["inputPreview"]["path"])
            self.assertEqual(
                long_text,
                (output_dir / "previews" / "text" / "1.txt").read_text(encoding="utf-8"),
            )

    def test_text_preview_byte_limit_does_not_split_utf8_character(self):
        stored, truncated = infer.truncate_utf8_preview("测试文本", max_bytes=7)
        self.assertEqual("测试", stored)
        self.assertTrue(truncated)


if __name__ == "__main__":
    unittest.main()
