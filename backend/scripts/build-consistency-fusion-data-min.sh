#!/usr/bin/env bash
# 从 consistency_test_data.zip 拆出 fusion_logreg 最小数据包（9 个预计算分数 JSONL）
set -euo pipefail

SOURCE_ZIP="${1:-/opt/consistency_test_data.zip}"
TARGET_ZIP="${2:-/opt/consistency_test_fusion_data_min.zip}"

if [[ ! -f "${SOURCE_ZIP}" ]]; then
  echo "缺少源数据包: ${SOURCE_ZIP}" >&2
  exit 1
fi

if [[ -f "${TARGET_ZIP}" ]]; then
  echo "目标已存在，跳过生成: ${TARGET_ZIP}" >&2
  exit 0
fi

python3 - "${SOURCE_ZIP}" "${TARGET_ZIP}" <<'PY'
import sys
import zipfile
from pathlib import PurePosixPath

source_zip, target_zip = sys.argv[1], sys.argv[2]
required = [
    "data/global_ultra_easy_v2_refreshed_v1_retrain_train_scores.jsonl",
    "data/region_ultra_easy_v2_refreshed_v1_train_scores_v1.jsonl",
    "data/entity_det_ultra_easy_v2_refreshed_v1_train_scores_ocr.jsonl",
    "data/global_ultra_easy_v2_refreshed_v1_retrain_val_scores.jsonl",
    "data/region_ultra_easy_v2_refreshed_v1_val_scores_v1.jsonl",
    "data/entity_det_ultra_easy_v2_refreshed_v1_val_scores_ocr.jsonl",
    "data/global_ultra_easy_v2_refreshed_v1_retrain_test_scores.jsonl",
    "data/region_ultra_easy_v2_refreshed_v1_test_scores_v1.jsonl",
    "data/entity_det_ultra_easy_v2_refreshed_v1_test_scores_ocr.jsonl",
]
required_set = set(required)

with zipfile.ZipFile(source_zip, "r") as src, zipfile.ZipFile(target_zip, "w", zipfile.ZIP_DEFLATED) as dst:
    index = {name.replace("\\", "/"): name for name in src.namelist()}
    missing = [name for name in required if name not in index]
    if missing:
        raise SystemExit("源包缺少文件: " + ", ".join(missing))
    for out_name in required:
        src_name = index[out_name]
        dst.writestr(out_name, src.read(src_name))
        print(f"added {out_name} ({PurePosixPath(out_name).name})")

print(f"generated {target_zip}")
PY

ls -lh "${TARGET_ZIP}"
python3 - <<PY
import zipfile
with zipfile.ZipFile("${TARGET_ZIP}") as z:
    names = sorted(n.replace('\\\\','/') for n in z.namelist())
    print('entries', len(names))
    for n in names:
        print(' ', n)
PY
