#!/usr/bin/env python3
"""Seed consistency_test code/data assets into MinIO and PostgreSQL.

See backend/doc/training-profile-security.md for profile data requirements.
For fusion_logreg minimal data (9 score JSONL only):
    backend/scripts/build-consistency-fusion-data-min.sh
"""

from __future__ import annotations

import os
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path

try:
    from minio import Minio
except ImportError:
    print("请先安装 minio: pip install minio", file=sys.stderr)
    sys.exit(1)

ROOT = Path("/opt/tss-platform")
SOURCE_ZIP = Path("/opt/consistency_test.zip")
CODE_ZIP = Path("/opt/consistency_test_code.zip")
DATA_ZIP = Path("/opt/consistency_test_data.zip")

OWNER_USER_ID = 1
CODE_ASSET_ID = "code-asset-consistency-test"
CODE_VERSION_ID = "code-ver-consistency-test-v1"
DATASET_ASSET_ID = "dataset-asset-consistency-test-data"
DATASET_VERSION_ID = "dataset-ver-consistency-test-data-v1"
TRAINING_PROFILE = "image_text_consistency_fusion_logreg"

CODE_OBJECT = f"users/{OWNER_USER_ID}/codes/{CODE_ASSET_ID}/v1/consistency_test_code.zip"
DATA_OBJECT = f"users/{OWNER_USER_ID}/datasets/{DATASET_ASSET_ID}/v1/consistency_test_data.zip"


def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env


def build_data_zip() -> None:
    if DATA_ZIP.exists():
        print(f"已存在数据包: {DATA_ZIP}")
        return
    if not SOURCE_ZIP.exists():
        raise FileNotFoundError(f"缺少源包: {SOURCE_ZIP}")
    print(f"从 {SOURCE_ZIP} 生成 {DATA_ZIP} ...")
    allowed_prefixes = ("consistency_test/data/", "consistency_test/dataset/")
    with zipfile.ZipFile(SOURCE_ZIP, "r") as src, zipfile.ZipFile(DATA_ZIP, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename.replace("\\", "/")
            if not any(name.startswith(prefix) for prefix in allowed_prefixes):
                continue
            if name.endswith("/"):
                continue
            target = name.replace("consistency_test/", "", 1)
            data = src.read(info.filename)
            dst.writestr(target, data)
    print(f"数据包已生成: {DATA_ZIP} ({DATA_ZIP.stat().st_size} bytes)")


def upload_file(client: Minio, bucket: str, object_name: str, file_path: Path) -> None:
    print(f"上传 MinIO: {bucket}/{object_name} <- {file_path}")
    client.fput_object(bucket, object_name, str(file_path), content_type="application/zip")


def sql_escape(value: str) -> str:
    return value.replace("'", "''")


def run_sql(sql: str, env: dict[str, str]) -> None:
    db_url = env.get("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/tss")
    user = env.get("SPRING_DATASOURCE_USERNAME", "postgres")
    password = env.get("SPRING_DATASOURCE_PASSWORD", "password123")
    host_port_db = db_url.split("jdbc:postgresql://", 1)[1]
    if "/" not in host_port_db:
        raise ValueError(f"无法解析数据库 URL: {db_url}")
    host_port, db_name = host_port_db.split("/", 1)
    if ":" in host_port:
        host, port = host_port.split(":", 1)
    else:
        host, port = host_port, "5432"

    cmd = [
        "psql",
        "-h", host,
        "-p", port,
        "-U", user,
        "-d", db_name,
        "-v", "ON_ERROR_STOP=1",
        "-c", sql,
    ]
    env_copy = os.environ.copy()
    env_copy["PGPASSWORD"] = password
    print("执行 SQL ...")
    subprocess.run(cmd, check=True, env=env_copy)


def main() -> None:
    env = load_env(ROOT / ".env.backend")
    endpoint = env.get("MINIO_ENDPOINT", "http://127.0.0.1:9010").replace("http://", "").replace("https://", "")
    secure = env.get("MINIO_ENDPOINT", "http://127.0.0.1:9010").startswith("https")
    access_key = env.get("MINIO_ACCESS_KEY", "minioadmin")
    secret_key = env.get("MINIO_SECRET_KEY", "password123")
    bucket = env.get("MINIO_BUCKET", "models")

    if not CODE_ZIP.exists():
        raise FileNotFoundError(f"缺少代码包: {CODE_ZIP}")
    build_data_zip()

    client = Minio(endpoint, access_key=access_key, secret_key=secret_key, secure=secure)
    upload_file(client, bucket, CODE_OBJECT, CODE_ZIP)
    upload_file(client, bucket, DATA_OBJECT, DATA_ZIP)

    now = datetime.now(timezone.utc).isoformat()
    code_size = CODE_ZIP.stat().st_size
    data_size = DATA_ZIP.stat().st_size

    sql = f"""
    INSERT INTO code_asset (id, name, training_profile, remark, owner_user_id, created_at, updated_at, deleted)
    VALUES (
      '{CODE_ASSET_ID}',
      'consistency_test_code',
      '{TRAINING_PROFILE}',
      'Seeded from consistency_test_code.zip',
      {OWNER_USER_ID},
      '{now}', '{now}', FALSE
    )
    ON CONFLICT (id) DO UPDATE SET
      name = EXCLUDED.name,
      training_profile = EXCLUDED.training_profile,
      remark = EXCLUDED.remark,
      updated_at = EXCLUDED.updated_at,
      deleted = FALSE;

    INSERT INTO code_version (id, asset_id, version, file_name, storage_path, size_bytes, status, approval_status, owner_user_id, created_at, deleted)
    VALUES (
      '{CODE_VERSION_ID}',
      '{CODE_ASSET_ID}',
      'v1',
      'consistency_test_code.zip',
      '{sql_escape(CODE_OBJECT)}',
      {code_size},
      'READY',
      'APPROVED',
      {OWNER_USER_ID},
      '{now}',
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
      '{DATASET_ASSET_ID}',
      'consistency_test_data',
      'NLP',
      'Seeded from consistency_test_data.zip',
      {OWNER_USER_ID},
      '{now}', '{now}', FALSE,
      '{DATASET_VERSION_ID}'
    )
    ON CONFLICT (id) DO UPDATE SET
      name = EXCLUDED.name,
      type = EXCLUDED.type,
      remark = EXCLUDED.remark,
      updated_at = EXCLUDED.updated_at,
      current_version_id = EXCLUDED.current_version_id,
      deleted = FALSE;

    INSERT INTO dataset_version (
      id, asset_id, version, version_no, version_label, file_name, storage_path, size_bytes,
      status, owner_user_id, created_at, deleted
    )
    VALUES (
      '{DATASET_VERSION_ID}',
      '{DATASET_ASSET_ID}',
      'v1',
      1,
      'v1',
      'consistency_test_data.zip',
      '{sql_escape(DATA_OBJECT)}',
      {data_size},
      'READY',
      {OWNER_USER_ID},
      '{now}',
      FALSE
    )
    ON CONFLICT (id) DO UPDATE SET
      storage_path = EXCLUDED.storage_path,
      size_bytes = EXCLUDED.size_bytes,
      status = 'READY',
      deleted = FALSE;
    """
    run_sql(sql, env)

    print("\n=== 种子数据已写入 ===")
    print(f"codeVersionId      = {CODE_VERSION_ID}")
    print(f"datasetVersionId   = {DATASET_VERSION_ID}")
    print(f"trainingProfile    = {TRAINING_PROFILE}")
    print(f"code storage_path  = {CODE_OBJECT}")
    print(f"data storage_path  = {DATA_OBJECT}")


if __name__ == "__main__":
    main()
