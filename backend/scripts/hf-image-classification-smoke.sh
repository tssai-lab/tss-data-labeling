#!/usr/bin/env bash
set -euo pipefail

PLATFORM_URL="${PLATFORM_URL:-http://127.0.0.1:8080}"
MODEL_ZIP="${MODEL_ZIP:-}"
DATASET_ZIP="${DATASET_ZIP:-}"
CODE_ZIP="${CODE_ZIP:-}"
TRAIN_TIMEOUT_SECONDS="${TRAIN_TIMEOUT_SECONDS:-3600}"
APPROVAL_TIMEOUT_SECONDS="${APPROVAL_TIMEOUT_SECONDS:-120}"
POLL_SECONDS="${POLL_SECONDS:-5}"
KEEP_WORKDIR="${KEEP_WORKDIR:-false}"

PLAN_ID="hf_image_classification"
PLAN_VERSION="v1"
RESOURCE_PROFILE_ID="cpu-hf-small"
WORKDIR="$(mktemp -d /tmp/tss-hf-smoke-XXXXXX)"

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
'
}

require_api_success() {
  local json="$1"
  local label="$2"
  if [[ "$(json_get "${json}" "success")" != "true" ]]; then
    echo "${label} failed:" >&2
    echo "${json}" >&2
    exit 1
  fi
}

require_result_success() {
  local json="$1"
  local label="$2"
  if [[ "$(json_get "${json}" "code")" != "200" ]]; then
    echo "${label} failed:" >&2
    echo "${json}" >&2
    exit 1
  fi
}

require_file() {
  local file="$1"
  local label="$2"
  if [[ -z "${file}" || ! -f "${file}" ]]; then
    echo "${label} does not exist: ${file:-<empty>}" >&2
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

require_file "${MODEL_ZIP}" "MODEL_ZIP"
require_file "${DATASET_ZIP}" "DATASET_ZIP"
require_file "${CODE_ZIP}" "CODE_ZIP"

echo "[1/8] Registering an isolated smoke-test user"
USERNAME="hfsmoke$(date +%s)"
PASSWORD="HfSmoke_123"
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

echo "[2/8] Uploading the HuggingFace base model"
MODEL_SIZE="$(stat -c '%s' "${MODEL_ZIP}")"
MODEL_FINGERPRINT="$(sha256sum "${MODEL_ZIP}" | awk '{print $1}')"
MODEL_INIT="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/model/upload/init" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"fileName\":\"hf-beans-base-model.zip\",
    \"fileSize\":${MODEL_SIZE},
    \"fileFingerprint\":\"${MODEL_FINGERPRINT}\",
    \"commitInfo\":\"Generic HuggingFace image-classification smoke test\"
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
    \"modelName\":\"HF Beans Smoke ${USERNAME}\",
    \"version\":\"v1\",
    \"type\":\"CV\",
    \"remark\":\"Generic HuggingFace image-classification smoke test\",
    \"commitInfo\":\"Generic HuggingFace image-classification smoke test\"
  }")"
require_api_success "${MODEL_COMPLETE}" "Complete model upload"
MODEL_VERSION_ID="$(json_get "${MODEL_COMPLETE}" "data.id")"

echo "[3/8] Uploading the ImageFolder dataset"
DATASET_SIZE="$(stat -c '%s' "${DATASET_ZIP}")"
DATASET_FINGERPRINT="$(sha256sum "${DATASET_ZIP}" | awk '{print $1}')"
DATASET_INIT="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/dataset/upload/init" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"fileName\":\"beans-image-folder.zip\",
    \"fileSize\":${DATASET_SIZE},
    \"fileFingerprint\":\"${DATASET_FINGERPRINT}\",
    \"datasetName\":\"Beans ImageFolder ${USERNAME}\",
    \"version\":\"v1\",
    \"versionLabel\":\"v1\",
    \"type\":\"CV\",
    \"cvTaskType\":\"IMAGE_CLASSIFICATION\",
    \"annotationFormat\":\"FOLDER_CLASSIFICATION\",
    \"remark\":\"Generic HuggingFace image-classification smoke test\"
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

echo "[4/8] Uploading the selected training code"
CODE_UPLOAD="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/code/upload" \
  -H "Authorization: Bearer ${TOKEN}" \
  -F "file=@${CODE_ZIP};filename=hf-beans-training.zip" \
  -F "codeName=HF Beans Training ${USERNAME}" \
  -F "version=v1" \
  -F "trainingProfile=${PLAN_ID}" \
  -F "remark=Generic HuggingFace image-classification smoke test")"
require_api_success "${CODE_UPLOAD}" "Upload training code"
CODE_VERSION_ID="$(json_get "${CODE_UPLOAD}" "data.codeVersionId")"

echo "[5/8] Waiting for automatic code admission"
deadline=$((SECONDS + APPROVAL_TIMEOUT_SECONDS))
CODE_CHECK=""
CODE_PASSED="false"
while (( SECONDS < deadline )); do
  CODE_CHECK="$(curl -fsS \
    "${PLATFORM_URL}/api/code/version/${CODE_VERSION_ID}/training-check?trainingProfile=${PLAN_ID}" \
    -H "Authorization: Bearer ${TOKEN}")"
  require_api_success "${CODE_CHECK}" "Check training code"
  CODE_PASSED="$(json_get "${CODE_CHECK}" "data.passed")"
  APPROVAL_STATUS="$(json_get "${CODE_CHECK}" "data.approvalStatus")"
  echo "Code admission: passed=${CODE_PASSED}, approval=${APPROVAL_STATUS}"
  if [[ "${CODE_PASSED}" == "true" && "${APPROVAL_STATUS}" == "APPROVED" ]]; then
    break
  fi
  if [[ "${APPROVAL_STATUS}" == "REJECTED" ]]; then
    break
  fi
  sleep "${POLL_SECONDS}"
done
if [[ "${CODE_PASSED}" != "true" || "${APPROVAL_STATUS}" != "APPROVED" ]]; then
  echo "Training code was not admitted:" >&2
  echo "${CODE_CHECK}" >&2
  exit 1
fi

echo "[6/8] Creating the generic training task"
TRAIN_CREATE="$(curl -fsS -X POST \
  "${PLATFORM_URL}/api/task/create" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\":\"HF Beans Closed Loop ${USERNAME}\",
    \"baseModelVersionId\":\"${MODEL_VERSION_ID}\",
    \"datasetVersionId\":\"${DATASET_VERSION_ID}\",
    \"codeVersionId\":\"${CODE_VERSION_ID}\",
    \"planId\":\"${PLAN_ID}\",
    \"planVersion\":\"${PLAN_VERSION}\",
    \"trainingMode\":\"FULL_FINETUNE\",
    \"resourceProfileId\":\"${RESOURCE_PROFILE_ID}\",
    \"params\":{
      \"epochs\":1,
      \"batchSize\":4,
      \"lr\":0.00003,
      \"weightDecay\":0.01,
      \"maxTrainSamples\":30,
      \"maxEvalSamples\":15
    },
    \"remark\":\"Generic HuggingFace image-classification smoke test\"
  }")"
require_api_success "${TRAIN_CREATE}" "Create training task"
TRAINING_ID="$(json_get "${TRAIN_CREATE}" "data.id")"
EXPERIMENT_ID="$(json_get "${TRAIN_CREATE}" "data.experimentId")"

echo "[7/8] Waiting for the Kubernetes Worker"
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
  if [[ "${TRAIN_STATUS}" == "success" \
    || "${TRAIN_STATUS}" == "failed" \
    || "${TRAIN_STATUS}" == "stopped" ]]; then
    break
  fi
  sleep "${POLL_SECONDS}"
done
if [[ "${TRAIN_STATUS}" != "success" ]]; then
  echo "Training did not succeed:" >&2
  echo "${TRAIN_DETAIL}" >&2
  exit 1
fi

echo "[8/8] Verifying the published model"
deadline=$((SECONDS + APPROVAL_TIMEOUT_SECONDS))
PRODUCED_MODEL_VERSION_ID=""
MODEL_PUBLISH_STATUS=""
while (( SECONDS < deadline )); do
  TRAIN_DETAIL="$(curl -fsS \
    "${PLATFORM_URL}/api/task/detail?id=${TRAINING_ID}" \
    -H "Authorization: Bearer ${TOKEN}")"
  require_api_success "${TRAIN_DETAIL}" "Read published training model"
  PRODUCED_MODEL_VERSION_ID="$(json_get "${TRAIN_DETAIL}" "data.producedModelVersionId")"
  MODEL_PUBLISH_STATUS="$(json_get "${TRAIN_DETAIL}" "data.modelPublishStatus")"
  echo "Model publication: status=${MODEL_PUBLISH_STATUS:-<empty>}, version=${PRODUCED_MODEL_VERSION_ID:-<empty>}"
  if [[ "${MODEL_PUBLISH_STATUS}" == "PUBLISHED" && -n "${PRODUCED_MODEL_VERSION_ID}" ]]; then
    break
  fi
  if [[ "${MODEL_PUBLISH_STATUS}" == "FAILED" ]]; then
    break
  fi
  sleep "${POLL_SECONDS}"
done
if [[ "${MODEL_PUBLISH_STATUS}" != "PUBLISHED" || -z "${PRODUCED_MODEL_VERSION_ID}" ]]; then
  echo "Training succeeded but model publication did not complete:" >&2
  echo "${TRAIN_DETAIL}" >&2
  exit 1
fi

echo
echo "========== HF IMAGE CLASSIFICATION LOOP PASSED =========="
echo "User ID: ${USER_ID}"
echo "Base model version: ${MODEL_VERSION_ID}"
echo "Dataset version: ${DATASET_VERSION_ID}"
echo "Code version: ${CODE_VERSION_ID}"
echo "Training ID: ${TRAINING_ID}"
echo "Experiment ID: ${EXPERIMENT_ID}"
echo "Training status: ${TRAIN_STATUS}"
echo "Produced model version: ${PRODUCED_MODEL_VERSION_ID}"
