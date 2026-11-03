#!/usr/bin/env bash
# 写入 consistency_test 种子 code/data 资产到 MinIO 与 PostgreSQL。
# 数据包说明见 backend/doc/training-profile-security.md。
# fusion_logreg 最小 data 包（9 个 JSONL，不修改本脚本使用的全量包）：
#   backend/scripts/build-consistency-fusion-data-min.sh
set -euo pipefail

ROOT_DIR="/opt/tss-platform"
CODE_ZIP="/opt/consistency_test_code.zip"
DATA_ZIP="/opt/consistency_test_data.zip"
SOURCE_ZIP="/opt/consistency_test.zip"
MINIO_DATA_ROOT="${ROOT_DIR}/minio-data/models"

OWNER_USER_ID=1
CODE_ASSET_ID="code-asset-consistency-test"
CODE_VERSION_ID="code-ver-consistency-test-v1"
DATASET_ASSET_ID="dataset-asset-consistency-test-data"
DATASET_VERSION_ID="dataset-ver-consistency-test-data-v1"
TRAINING_PROFILE="image_text_consistency_fusion_logreg"
CODE_OBJECT="users/${OWNER_USER_ID}/codes/${CODE_ASSET_ID}/v1/consistency_test_code.zip"
DATA_OBJECT="users/${OWNER_USER_ID}/datasets/${DATASET_ASSET_ID}/v1/consistency_test_data.zip"

build_data_zip() {
  if [[ -f "${DATA_ZIP}" ]]; then
    echo "已存在数据包: ${DATA_ZIP}"
    return
  fi
  python3 - <<'PY'
import zipfile
from pathlib import Path
source = Path("/opt/consistency_test.zip")
target = Path("/opt/consistency_test_data.zip")
allowed = ("consistency_test/data/", "consistency_test/dataset/")
with zipfile.ZipFile(source, "r") as src, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as dst:
    for info in src.infolist():
        name = info.filename.replace("\\", "/")
        if not any(name.startswith(p) for p in allowed):
            continue
        if name.endswith("/"):
            continue
        out_name = name.replace("consistency_test/", "", 1)
        dst.writestr(out_name, src.read(info.filename))
print(f"generated {target} size={target.stat().st_size}")
PY
}

copy_to_minio_data() {
  local object_path="$1"
  local file_path="$2"
  local dest="${MINIO_DATA_ROOT}/${object_path}"
  mkdir -p "$(dirname "${dest}")"
  cp -f "${file_path}" "${dest}"
  echo "已写入 MinIO 数据目录: ${dest}"
}

run_sql() {
  docker exec -i tss-postgres psql -U postgres -d tss -v ON_ERROR_STOP=1 <<SQL
INSERT INTO code_asset (id, name, training_profile, remark, owner_user_id, created_at, updated_at, deleted)
VALUES (
  '${CODE_ASSET_ID}',
  'consistency_test_code',
  '${TRAINING_PROFILE}',
  'Seeded from consistency_test_code.zip',
  ${OWNER_USER_ID},
  NOW(), NOW(), FALSE
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  training_profile = EXCLUDED.training_profile,
  remark = EXCLUDED.remark,
  updated_at = EXCLUDED.updated_at,
  deleted = FALSE;

INSERT INTO code_version (id, asset_id, version, file_name, storage_path, size_bytes, status, approval_status, owner_user_id, created_at, deleted)
VALUES (
  '${CODE_VERSION_ID}',
  '${CODE_ASSET_ID}',
  'v1',
  'consistency_test_code.zip',
  '${CODE_OBJECT}',
  $(stat -c%s "${CODE_ZIP}"),
  'READY',
  'APPROVED',
  ${OWNER_USER_ID},
  NOW(),
  FALSE
)
ON CONFLICT (id) DO UPDATE SET
  storage_path = EXCLUDED.storage_path,
  size_bytes = EXCLUDED.size_bytes,
  status = 'READY',
  approval_status = 'APPROVED',
  deleted = FALSE;

INSERT INTO dataset_asset (id, name, type, remark, owner_user_id, created_at, updated_at, deleted, current_version_id)
VALUES (
  '${DATASET_ASSET_ID}',
  'consistency_test_data',
  'NLP',
  'Seeded from consistency_test_data.zip',
  ${OWNER_USER_ID},
  NOW(), NOW(), FALSE,
  NULL
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  type = EXCLUDED.type,
  remark = EXCLUDED.remark,
  updated_at = EXCLUDED.updated_at,
  deleted = FALSE;

INSERT INTO dataset_version (
  id, asset_id, version, version_no, version_label, file_name, storage_path, size_bytes,
  status, owner_user_id, created_at, deleted
)
VALUES (
  '${DATASET_VERSION_ID}',
  '${DATASET_ASSET_ID}',
  'v1',
  1,
  'v1',
  'consistency_test_data.zip',
  '${DATA_OBJECT}',
  $(stat -c%s "${DATA_ZIP}"),
  'READY',
  ${OWNER_USER_ID},
  NOW(),
  FALSE
)
ON CONFLICT (id) DO UPDATE SET
  storage_path = EXCLUDED.storage_path,
  size_bytes = EXCLUDED.size_bytes,
  status = 'READY',
  deleted = FALSE;

UPDATE dataset_asset
SET current_version_id = '${DATASET_VERSION_ID}', updated_at = NOW()
WHERE id = '${DATASET_ASSET_ID}';
SQL
}

main() {
  [[ -f "${CODE_ZIP}" ]] || { echo "缺少 ${CODE_ZIP}" >&2; exit 1; }
  [[ -f "${SOURCE_ZIP}" ]] || { echo "缺少 ${SOURCE_ZIP}" >&2; exit 1; }
  build_data_zip
  copy_to_minio_data "${CODE_OBJECT}" "${CODE_ZIP}"
  copy_to_minio_data "${DATA_OBJECT}" "${DATA_ZIP}"
  run_sql
  cat <<EOF

=== 种子数据已写入 ===
codeVersionId      = ${CODE_VERSION_ID}
datasetVersionId   = ${DATASET_VERSION_ID}
trainingProfile    = ${TRAINING_PROFILE}
code storage_path  = ${CODE_OBJECT}
data storage_path  = ${DATA_OBJECT}
EOF
}

main "$@"
