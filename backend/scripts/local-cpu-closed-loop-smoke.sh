#!/usr/bin/env bash
set -euo pipefail

PLATFORM_URL="${PLATFORM_URL:-http://127.0.0.1:8080}"
MODEL_PT="${MODEL_PT:-}"
TEST_IMAGE="${TEST_IMAGE:-}"
TRAIN_TIMEOUT_SECONDS="${TRAIN_TIMEOUT_SECONDS:-300}"
INFERENCE_TIMEOUT_SECONDS="${INFERENCE_TIMEOUT_SECONDS:-180}"
POLL_SECONDS="${POLL_SECONDS:-2}"
KEEP_WORKDIR="${KEEP_WORKDIR:-false}"
RUN_INFERENCE="${RUN_INFERENCE:-false}"

WORKDIR="$(mktemp -d /tmp/tss-cpu-smoke-XXXXXX)"

cleanup() {
  if [[ "${KEEP_WORKDIR}" == "true" ]]; then
    echo "Work directory retained: ${WORKDIR}"
  else
    rm -rf "${WORKDIR}"
  fi
}
trap cleanup EXIT

json_get() {
  local json="$1"
  local path="$2"
  JSON_INPUT="${json}" JSON_PATH="${path}" python3 -c '
import json
import os
import sys

value = json.loads(os.environ["JSON_INPUT"])
for part in os.environ["JSON_PATH"].split("."):
    if part:
        value = value[part]
if value is None:
    print("")
elif isinstance(value, bool):
    print("true" if value else "false")
elif isinstance(value, (dict, list)):
    print(json.dumps(value, ensure_ascii=False))
else:
    print(value)
' || {
    echo "Failed to read JSON path '${path}' from response:" >&2
    echo "${json}" >&2
    return 1
  }
}

require_api_success() {
  local json="$1"
  local label="$2"
  local success
  success="$(json_get "${json}" "success")"
  if [[ "${success}" != "true" ]]; then
    echo "${label} failed:" >&2
    echo "${json}" >&2
    exit 1
  fi
}

require_result_success() {
  local json="$1"
  local label="$2"
  local code
  code="$(json_get "${json}" "code")"
  if [[ "${code}" != "200" ]]; then
    echo "${label} failed:" >&2
    echo "${json}" >&2
    exit 1
  fi
}

require_file() {
  local file="$1"
  if [[ ! -f "${file}" ]]; then
    echo "Required file does not exist: ${file}" >&2
    exit 1
  fi
}

upload_chunks() {
  local upload_url="$1"
  local upload_id="$2"
  local file="$3"
  local part_dir="$4"

  mkdir -p "${part_dir}"
  split -b 5242880 -d -a 5 "${file}" "${part_dir}/part-"

  local part
  local index=0
  while IFS= read -r part; do
    local response
    response="$(curl -fsS -X POST \
      "${upload_url}?uploadId=${upload_id}&partIndex=${index}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -F "file=@${part}")"
    require_api_success "${response}" "Upload chunk ${index}"
    index=$((index + 1))
  done < <(find "${part_dir}" -maxdepth 1 -type f -name 'part-*' | sort)
}

echo "[1/7] Checking platform service"
curl -sS "${PLATFORM_URL}/api/user/current-user" >/dev/null

USERNAME="cpu$(date +%s)"
PASSWORD="CpuDemo_123"

echo "[2/7] Registering and logging in as ${USERNAME}"
REGISTER_RESPONSE="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/user/register/username" \
  -H "Content-Type: application/json" \
  -d "{
    \"username\":\"${USERNAME}\",
    \"password\":\"${PASSWORD}\",
    \"confirmPassword\":\"${PASSWORD}\"
  }")"
require_result_success "${REGISTER_RESPONSE}" "Register user"

LOGIN_RESPONSE="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/user/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"type\":\"account\",
    \"username\":\"${USERNAME}\",
    \"password\":\"${PASSWORD}\"
  }")"
require_result_success "${LOGIN_RESPONSE}" "Login"

TOKEN="$(json_get "${LOGIN_RESPONSE}" "data.token")"
USER_ID="$(json_get "${LOGIN_RESPONSE}" "data.userId")"
if [[ -z "${TOKEN}" || -z "${USER_ID}" ]]; then
  echo "Login did not return token or userId" >&2
  exit 1
fi

echo "[3/7] Preparing model and YOLO dataset archives"
mkdir -p "${WORKDIR}/model" "${WORKDIR}/dataset/images" "${WORKDIR}/dataset/labels"
if [[ -n "${MODEL_PT}" ]]; then
  require_file "${MODEL_PT}"
  cp "${MODEL_PT}" "${WORKDIR}/model/model.pt"
else
  printf 'TSS local CPU training smoke model placeholder\n' >"${WORKDIR}/model/model.pt"
fi

if [[ -n "${TEST_IMAGE}" ]]; then
  require_file "${TEST_IMAGE}"
  cp "${TEST_IMAGE}" "${WORKDIR}/dataset/images/sample.jpg"
else
  printf '%s' \
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=' \
    | base64 -d >"${WORKDIR}/dataset/images/sample.png"
fi
printf '0 0.5 0.5 0.5 0.5\n' >"${WORKDIR}/dataset/labels/sample.txt"

(
  cd "${WORKDIR}/model"
  jar cfM "${WORKDIR}/cpu-yolo-model.zip" model.pt
)
(
  cd "${WORKDIR}/dataset"
  jar cfM "${WORKDIR}/cpu-yolo-dataset.zip" images labels
)

MODEL_ZIP="${WORKDIR}/cpu-yolo-model.zip"
DATASET_ZIP="${WORKDIR}/cpu-yolo-dataset.zip"

echo "[4/7] Uploading model"
MODEL_SIZE="$(stat -c '%s' "${MODEL_ZIP}")"
MODEL_FINGERPRINT="$(sha256sum "${MODEL_ZIP}" | awk '{print $1}')"
MODEL_INIT="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/model/upload/init" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"fileName\":\"cpu-yolo-model.zip\",
    \"fileSize\":${MODEL_SIZE},
    \"fileFingerprint\":\"${MODEL_FINGERPRINT}\"
  }")"
require_api_success "${MODEL_INIT}" "Initialize model upload"
MODEL_UPLOAD_ID="$(json_get "${MODEL_INIT}" "data.uploadId")"

upload_chunks \
  "${PLATFORM_URL}/api/model/upload/chunk" \
  "${MODEL_UPLOAD_ID}" \
  "${MODEL_ZIP}" \
  "${WORKDIR}/model-parts"

MODEL_COMPLETE="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/model/upload/complete" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"uploadId\":\"${MODEL_UPLOAD_ID}\",
    \"modelName\":\"CPU YOLO Demo ${USERNAME}\",
    \"version\":\"v1\",
    \"type\":\"CV\",
    \"remark\":\"Local CPU closed-loop smoke test\"
  }")"
require_api_success "${MODEL_COMPLETE}" "Complete model upload"
MODEL_VERSION_ID="$(json_get "${MODEL_COMPLETE}" "data.id")"

echo "[5/7] Uploading dataset"
DATASET_SIZE="$(stat -c '%s' "${DATASET_ZIP}")"
DATASET_FINGERPRINT="$(sha256sum "${DATASET_ZIP}" | awk '{print $1}')"
DATASET_INIT="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/dataset/upload/init" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"fileName\":\"cpu-yolo-dataset.zip\",
    \"fileSize\":${DATASET_SIZE},
    \"fileFingerprint\":\"${DATASET_FINGERPRINT}\",
    \"datasetName\":\"CPU YOLO Dataset ${USERNAME}\",
    \"version\":\"v1\",
    \"versionLabel\":\"v1\",
    \"type\":\"CV\",
    \"cvTaskType\":\"OBJECT_DETECTION\",
    \"annotationFormat\":\"YOLO\",
    \"remark\":\"Local CPU closed-loop smoke test\"
  }")"
require_api_success "${DATASET_INIT}" "Initialize dataset upload"
DATASET_UPLOAD_ID="$(json_get "${DATASET_INIT}" "data.uploadId")"

upload_chunks \
  "${PLATFORM_URL}/api/dataset/upload/chunk" \
  "${DATASET_UPLOAD_ID}" \
  "${DATASET_ZIP}" \
  "${WORKDIR}/dataset-parts"

DATASET_COMPLETE="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/dataset/upload/complete" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"uploadId\":\"${DATASET_UPLOAD_ID}\"}")"
require_api_success "${DATASET_COMPLETE}" "Complete dataset upload"
DATASET_VERSION_ID="$(json_get "${DATASET_COMPLETE}" "data.datasetVersionId")"

echo "[6/7] Creating CPU training task"
TRAIN_CREATE="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/task/create" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\":\"CPU Closed Loop ${USERNAME}\",
    \"modelVersionId\":\"${MODEL_VERSION_ID}\",
    \"codeVersionId\":\"cpu-demo-code-v1\",
    \"datasetVersionId\":\"${DATASET_VERSION_ID}\",
    \"hyperParams\":{\"epochs\":3,\"lr0\":0.05,\"device\":\"cpu\"},
    \"remark\":\"Local CPU closed-loop smoke test\"
  }")"
require_api_success "${TRAIN_CREATE}" "Create training task"

TRAINING_ID="$(json_get "${TRAIN_CREATE}" "data.id")"
EXPERIMENT_ID="$(json_get "${TRAIN_CREATE}" "data.experimentId")"
VERSION_NO="$(json_get "${TRAIN_CREATE}" "data.versionNo")"

echo "[7/7] Waiting for training result"
deadline=$((SECONDS + TRAIN_TIMEOUT_SECONDS))
TRAIN_STATUS=""
TRAIN_DETAIL=""
while (( SECONDS < deadline )); do
  TRAIN_DETAIL="$(curl -fsS \
    "${PLATFORM_URL}/api/task/detail?id=${TRAINING_ID}" \
    -H "Authorization: Bearer ${TOKEN}")"
  require_api_success "${TRAIN_DETAIL}" "Read training task"
  TRAIN_STATUS="$(json_get "${TRAIN_DETAIL}" "data.status")"
  TRAIN_PROGRESS="$(json_get "${TRAIN_DETAIL}" "data.progress")"
  echo "Training status=${TRAIN_STATUS}, progress=${TRAIN_PROGRESS}%"
  if [[ "${TRAIN_STATUS}" == "success" || "${TRAIN_STATUS}" == "failed" ]]; then
    break
  fi
  sleep "${POLL_SECONDS}"
done

if [[ "${TRAIN_STATUS}" != "success" ]]; then
  echo "Training did not succeed:" >&2
  echo "${TRAIN_DETAIL}" >&2
  exit 1
fi

TRAIN_OUTPUT="$(json_get "${TRAIN_DETAIL}" "data.outputPath")"
TRAIN_METRICS="$(json_get "${TRAIN_DETAIL}" "data.metrics")"

if [[ "${RUN_INFERENCE}" == "true" ]]; then
  echo "[optional] Preparing and uploading integrated inference script"
  mkdir -p "${WORKDIR}/inference-script"
  cat >"${WORKDIR}/inference-script/infer.py" <<'PY'
import json
import os
from pathlib import Path

model_dir = Path(os.environ["MODEL_DIR"])
input_path = Path(os.environ["INPUT_PATH"])
output_dir = Path(os.environ["OUTPUT_DIR"])
output_dir.mkdir(parents=True, exist_ok=True)

model_files = [path for path in model_dir.rglob("*") if path.is_file()]
input_files = (
    [path for path in input_path.rglob("*") if path.is_file()]
    if input_path.is_dir()
    else [input_path]
)
result = {
    "smoke": "passed",
    "modelFileCount": len(model_files),
    "inputFileCount": len(input_files),
}
(output_dir / "result.json").write_text(json.dumps(result), encoding="utf-8")
PY
  (
    cd "${WORKDIR}/inference-script"
    jar cfM "${WORKDIR}/cpu-inference-script.zip" infer.py
  )

  SCRIPT_UPLOAD="$(curl -fsS -X POST \
    "${PLATFORM_URL}/api/inference/scripts/upload" \
    -H "Authorization: Bearer ${TOKEN}" \
    -F "file=@${WORKDIR}/cpu-inference-script.zip" \
    -F "scriptName=CPU Inference Smoke ${USERNAME}" \
    -F "version=v1" \
    -F "runtime=PYTHON3" \
    -F "entryFile=infer.py" \
    -F 'paramsSchemaJson={"type":"object"}' \
    -F "remark=Integrated inference closed-loop smoke test")"
  require_api_success "${SCRIPT_UPLOAD}" "Upload inference script"
  SCRIPT_VERSION_ID="$(json_get "${SCRIPT_UPLOAD}" "data.scriptVersionId")"

  echo "[optional] Creating integrated Kubernetes inference task"
  INFERENCE_CREATE="$(curl -fsS -X POST \
    "${PLATFORM_URL}/api/inference/tasks" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\":\"CPU Inference Smoke ${USERNAME}\",
      \"modelVersionId\":\"${MODEL_VERSION_ID}\",
      \"scriptVersionId\":\"${SCRIPT_VERSION_ID}\",
      \"inputMode\":\"DATASET_VERSION\",
      \"datasetVersionId\":\"${DATASET_VERSION_ID}\",
      \"params\":{\"smoke\":true},
      \"remark\":\"Integrated inference closed-loop smoke test\"
    }")"
  require_api_success "${INFERENCE_CREATE}" "Create inference task"
  INFERENCE_TASK_ID="$(json_get "${INFERENCE_CREATE}" "data.id")"

  inference_deadline=$((SECONDS + INFERENCE_TIMEOUT_SECONDS))
  INFERENCE_STATUS=""
  INFERENCE_DETAIL=""
  while (( SECONDS < inference_deadline )); do
    INFERENCE_DETAIL="$(curl -fsS \
      "${PLATFORM_URL}/api/inference/tasks/${INFERENCE_TASK_ID}" \
      -H "Authorization: Bearer ${TOKEN}")"
    require_api_success "${INFERENCE_DETAIL}" "Read inference task"
    INFERENCE_STATUS="$(json_get "${INFERENCE_DETAIL}" "data.status")"
    INFERENCE_PROGRESS="$(json_get "${INFERENCE_DETAIL}" "data.progress")"
    echo "Inference status=${INFERENCE_STATUS}, progress=${INFERENCE_PROGRESS}%"
    if [[ "${INFERENCE_STATUS}" == "success" || "${INFERENCE_STATUS}" == "failed" ]]; then
      break
    fi
    sleep "${POLL_SECONDS}"
  done

  if [[ "${INFERENCE_STATUS}" != "success" ]]; then
    echo "Inference did not succeed:" >&2
    echo "${INFERENCE_DETAIL}" >&2
    exit 1
  fi

  INFERENCE_RESULT="$(curl -fsS \
    "${PLATFORM_URL}/api/inference/tasks/${INFERENCE_TASK_ID}/result" \
    -H "Authorization: Bearer ${TOKEN}")"
  require_api_success "${INFERENCE_RESULT}" "Read inference result"
  INFERENCE_SMOKE="$(json_get "${INFERENCE_RESULT}" "data.result.smoke")"
  if [[ "${INFERENCE_SMOKE}" != "passed" ]]; then
    echo "Inference result did not contain the expected smoke marker:" >&2
    echo "${INFERENCE_RESULT}" >&2
    exit 1
  fi
fi

echo
echo "========== CPU TRAINING LOOP PASSED =========="
echo "User ID: ${USER_ID}"
echo "Model version: ${MODEL_VERSION_ID}"
echo "Dataset version: ${DATASET_VERSION_ID}"
echo "Training ID: ${TRAINING_ID}"
echo "Training experiment: ${EXPERIMENT_ID}"
echo "Experiment version: ${VERSION_NO}"
echo "Training status: ${TRAIN_STATUS}"
echo "Training output: ${TRAIN_OUTPUT}"
echo "Training metrics: ${TRAIN_METRICS}"
if [[ "${RUN_INFERENCE}" == "true" ]]; then
  echo "Inference script version: ${SCRIPT_VERSION_ID}"
  echo "Inference task: ${INFERENCE_TASK_ID}"
  echo "Inference status: ${INFERENCE_STATUS}"
  echo "Inference result marker: ${INFERENCE_SMOKE}"
fi
