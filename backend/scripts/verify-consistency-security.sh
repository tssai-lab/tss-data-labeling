#!/usr/bin/env bash
# Worker/K8s 安全边界负向测试（不依赖真实 K8s Job，直接校验 Worker 脚本行为）
set -euo pipefail

ROOT_DIR="/opt/tss-platform"
WORKER_TRAIN="${ROOT_DIR}/k8s/training-worker/train.py"
REPORT_FILE="${1:-/tmp/consistency-security-report.txt}"

pass=0
fail=0
results=()

record() {
  local name="$1"
  local status="$2"
  local detail="$3"
  results+=("| ${name} | ${status} | ${detail} |")
  if [[ "${status}" == "PASS" ]]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
  fi
}

run_python() {
  python3 - "$@" <<'PY'
import importlib.util
import io
import sys
import types
import zipfile
from pathlib import Path

# train.py 顶层依赖 minio，静态校验场景无需真实包
if "minio" not in sys.modules:
    minio_mod = types.ModuleType("minio")
    minio_mod.Minio = object
    sys.modules["minio"] = minio_mod

worker_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("train_worker", worker_path)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

case = sys.argv[2]

if case == "hyperparams_not_used":
    source = worker_path.read_text(encoding="utf-8")
    ok = "HYPER_PARAMS_JSON" not in source and "hyper_params" not in source.lower()
    if ok:
        cmd = mod.PROFILES["image_text_consistency_fusion_logreg"]["command"]
        expected = [
            "python",
            "scripts/training/train_fusion_baseline.py",
            "--data-dir", "data",
            "--model", "logreg",
            "--out-dir", "outputs/fusion_baseline_logreg",
        ]
        ok = cmd == expected
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if case == "missing_train_script":
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("README.txt", "no train script")
    data = buf.getvalue()
    dest = Path("/tmp/consistency-security-code-missing")
    import shutil
    if dest.exists():
        shutil.rmtree(dest)
    mod.safe_extract_zip(data, dest)
    script = dest / "scripts/training/train_fusion_baseline.py"
    print("PASS" if not script.exists() else "FAIL")
    sys.exit(0 if not script.exists() else 1)

if case == "zip_path_traversal":
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("../../etc/passwd", "evil")
    data = buf.getvalue()
    dest = Path("/tmp/consistency-security-traversal")
    import shutil
    if dest.exists():
        shutil.rmtree(dest)
    try:
        mod.safe_extract_zip(data, dest)
        print("FAIL")
        sys.exit(1)
    except ValueError as e:
        ok = "不安全路径" in str(e)
        print("PASS" if ok else "FAIL")
        sys.exit(0 if ok else 1)

if case == "data_zip_missing_data_dir":
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("labels.csv", "id,label\n1,0")
    data = buf.getvalue()
    dest = Path("/tmp/consistency-security-data-missing")
    import shutil
    if dest.exists():
        shutil.rmtree(dest)
    mod.safe_extract_zip(data, dest)
    ok = not (dest / "data").exists()
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

print("FAIL")
sys.exit(1)
PY
}

main() {
  [[ -f "${WORKER_TRAIN}" ]] || { echo "缺少 Worker 脚本: ${WORKER_TRAIN}" >&2; exit 1; }

  cases=(
    "hyperparams_not_used:hyperParams 不能覆盖固定命令"
    "missing_train_script:code.zip 缺少 train 脚本"
    "zip_path_traversal:zip 路径穿越"
    "data_zip_missing_data_dir:data.zip 缺少 data/"
  )

  for item in "${cases[@]}"; do
    case_id="${item%%:*}"
    case_name="${item#*:}"
    if run_python "${WORKER_TRAIN}" "${case_id}" >/dev/null 2>&1; then
      record "${case_name}" "PASS" "Worker 脚本按预期拒绝或隔离"
    else
      record "${case_name}" "FAIL" "未观察到预期安全行为"
    fi
  done

  {
    echo "# Consistency Security Negative Tests"
    echo
    echo "时间: $(date -Iseconds)"
    echo "Worker: ${WORKER_TRAIN}"
    echo
    echo "| 测试项 | 结果 | 说明 |"
    echo "| --- | --- | --- |"
    for row in "${results[@]}"; do
      echo "${row}"
    done
    echo
    echo "汇总: PASS=${pass}, FAIL=${fail}"
  } | tee "${REPORT_FILE}"

  if [[ "${fail}" -gt 0 ]]; then
    exit 1
  fi
}

main "$@"
