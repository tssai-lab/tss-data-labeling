#!/usr/bin/env python3
"""Build the small, offline MiniRBT-H288 acceptance bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path


MODEL_REPOSITORY = "hfl/minirbt-h288"
MODEL_REVISION = "dc4eebb0cf6f9e7094142ac28fbf971517c6a366"
LICENSE_REPOSITORY = "iflytek/MiniRBT"
LICENSE_REVISION = "baef787a1cee4fdc4051083943a460807e7b8a86"
UPSTREAM_FILES = {
    "README.md": "667be60e0b89d63d308d3d1e76f906d056ad4a2c56e6a2f785544098e220f3eb",
    "config.json": "40770b75151e7633503a0c70a25291cb62431ed89f8a091576f0b7526011eeb4",
    "pytorch_model.bin": "691a14f646648bbf772dd0054c78c1a5451e09bc76bc3bdf874bd42bf5ee55b1",
    "vocab.txt": "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
}
LICENSE_SHA256 = "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"
LABELS = ["负面", "正面"]

TRAIN_TEXTS = {
    "正面": [
        "这个产品操作简单，整体体验很好。",
        "页面响应很快，功能也很清楚。",
        "客服回复及时，问题很快解决了。",
        "模型训练顺利完成，结果符合预期。",
        "这次更新提升明显，我愿意继续使用。",
        "数据导入过程稳定，没有遇到错误。",
        "说明文档简洁明了，新手也能看懂。",
        "推理结果展示直观，关键信息很完整。",
        "系统运行稳定，任务提交后很快完成。",
        "资源配置合理，使用起来比较放心。",
        "下载速度不错，文件校验也顺利通过。",
        "训练日志保存完整，排查问题很方便。",
    ],
    "负面": [
        "这个页面经常卡住，体验很差。",
        "任务提交失败，而且没有清楚的提示。",
        "下载速度太慢，等了很久仍未完成。",
        "说明文档不够清楚，我不知道怎么操作。",
        "模型结果不稳定，多次运行差异很大。",
        "上传过程反复中断，文件一直传不上去。",
        "日志内容太少，无法判断失败原因。",
        "按钮点击后没有反应，功能暂时不可用。",
        "资源不足导致任务失败，等待时间很长。",
        "页面显示的数据有误，结果难以确认。",
        "系统突然退出，刚才的配置没有保存。",
        "推理输出缺少关键字段，无法继续使用。",
    ],
}

VALIDATION_TEXTS = {
    "正面": [
        "界面布局清晰，查找任务很方便。",
        "训练完成后产物可以正常下载。",
        "错误提示具体，修正配置后即可运行。",
        "平台功能完整，验收过程比较顺利。",
    ],
    "负面": [
        "列表加载失败，刷新后仍然没有数据。",
        "模型文件无法读取，任务直接报错。",
        "权限提示不明确，不知道该联系谁。",
        "运行时间过长，最后也没有生成结果。",
    ],
}

TEST_TEXTS = {
    "正面": [
        "数据集预览正常，文本内容显示完整。",
        "任务状态更新及时，使用过程很顺畅。",
        "资源限制展示明确，提交前可以确认。",
        "结果页面信息丰富，能够支持验收。",
    ],
    "负面": [
        "系统提示超时，任务一直停在等待状态。",
        "上传后的文件损坏，无法发起训练。",
        "结果页面是空的，看不到任何预测。",
        "日志下载失败，问题现场无法保留。",
    ],
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_verified(url: str, destination: Path, expected_sha256: str) -> None:
    if destination.is_file() and sha256_file(destination) == expected_sha256:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".partial")
    partial.unlink(missing_ok=True)
    try:
        with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as target:
            shutil.copyfileobj(response, target, length=1024 * 1024)
        actual = sha256_file(partial)
        if actual != expected_sha256:
            raise RuntimeError(
                f"SHA-256 mismatch for {destination.name}: expected {expected_sha256}, got {actual}"
            )
        os.replace(partial, destination)
    finally:
        partial.unlink(missing_ok=True)


def deterministic_zip(output: Path, files: dict[str, bytes | Path]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".partial")
    temporary.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for archive_name in sorted(files):
                value = files[archive_name]
                data = value.read_bytes() if isinstance(value, Path) else value
                info = zipfile.ZipInfo(archive_name, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def split_records(split: str, values: dict[str, list[str]]) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    maximum = max(len(values[label]) for label in LABELS)
    for item_index in range(maximum):
        for label in LABELS:
            if item_index >= len(values[label]):
                continue
            records.append(
                {
                    "id": f"{split}-{label}-{item_index + 1:03d}",
                    "text": values[label][item_index],
                    "label": label,
                }
            )
    return records


def jsonl_bytes(records: list[dict[str, str]]) -> bytes:
    return "".join(
        json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        for record in records
    ).encode("utf-8")


def build_dataset_zip(output: Path) -> None:
    manifest = {
        "schemaVersion": "tss.dataset.nlp.text-classification/v1",
        "labels": LABELS,
        "fields": {"id": "id", "text": "text", "label": "label"},
        "splits": {"train": 24, "validation": 8, "test": 8},
        "license": "TSS synthetic acceptance data; project use only",
    }
    deterministic_zip(
        output,
        {
            "dataset.json": json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8"),
            "data/train.jsonl": jsonl_bytes(split_records("train", TRAIN_TEXTS)),
            "data/validation.jsonl": jsonl_bytes(
                split_records("validation", VALIDATION_TEXTS)
            ),
            "data/test.jsonl": jsonl_bytes(split_records("test", TEST_TEXTS)),
        },
    )


def build_model_zip(output: Path, cache: Path) -> None:
    for name, expected in UPSTREAM_FILES.items():
        download_verified(
            f"https://huggingface.co/{MODEL_REPOSITORY}/resolve/{MODEL_REVISION}/{name}",
            cache / name,
            expected,
        )
    download_verified(
        f"https://raw.githubusercontent.com/{LICENSE_REPOSITORY}/{LICENSE_REVISION}/LICENSE",
        cache / "LICENSE",
        LICENSE_SHA256,
    )
    model_manifest = (
        "schemaVersion: tss.model.nlp.bert-sequence-classification/v1\n"
        "baseModel: hfl/minirbt-h288\n"
        f"revision: {MODEL_REVISION}\n"
        "architecture: BertModel\n"
        "purpose: sequence-classification-finetune\n"
        "license: Apache-2.0\n"
    ).encode("utf-8")
    deterministic_zip(
        output,
        {
            # 模型包校验器要求每个文件都有白名单扩展名；保留许可证内容，
            # 仅将归档内的标准 LICENSE 文件命名为 LICENSE.txt。
            "LICENSE.txt": cache / "LICENSE",
            "UPSTREAM_README.md": cache / "README.md",
            "config.json": cache / "config.json",
            "model.yaml": model_manifest,
            "pytorch_model.bin": cache / "pytorch_model.bin",
            "vocab.txt": cache / "vocab.txt",
        },
    )


def build_bundle(output_dir: Path, cache: Path, source_dir: Path) -> dict[str, object]:
    output_dir.mkdir(parents=True, exist_ok=True)
    build_model_zip(output_dir / "minirbt-h288-base.zip", cache)
    build_dataset_zip(output_dir / "minirbt-sentiment-dataset.zip")
    deterministic_zip(output_dir / "minirbt-training-code.zip", {"train.py": source_dir / "train.py"})
    deterministic_zip(output_dir / "minirbt-inference-script.zip", {"infer.py": source_dir / "infer.py"})
    artifacts = []
    for path in sorted(output_dir.glob("*.zip")):
        artifacts.append(
            {"file": path.name, "sizeBytes": path.stat().st_size, "sha256": sha256_file(path)}
        )
    manifest = {
        "schemaVersion": "tss.acceptance.bundle/v1",
        "name": "MiniRBT-H288 Chinese text classification CPU acceptance",
        "upstream": {
            "model": MODEL_REPOSITORY,
            "revision": MODEL_REVISION,
            "license": "Apache-2.0",
            "excluded": ["tf_model.h5"],
        },
        "artifacts": artifacts,
    }
    manifest_path = output_dir / "acceptance-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("dist/minirbt-acceptance"))
    parser.add_argument("--cache-dir", type=Path, default=Path(".cache/minirbt-h288"))
    args = parser.parse_args()
    source_dir = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix="minirbt-acceptance-check-"):
        manifest = build_bundle(args.output_dir.resolve(), args.cache_dir.resolve(), source_dir)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
