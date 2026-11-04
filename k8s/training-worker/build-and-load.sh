#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER_NAME="${CLUSTER_NAME:-tss-training}"
KIND="${KIND:-${ROOT_DIR}/.tools/bin/kind}"
LOAD_TO_KIND="${TRAINING_WORKER_LOAD_TO_KIND:-true}"

# 合同要求:预置标准化基础训练镜像不少于 2 套,覆盖 CV 与 NLP 两类场景。
# CV 通用镜像覆盖图像分类、目标检测等;NLP 通用镜像覆盖文本/表格/图文一致性等。
# 长尾依赖由层2(requirements.txt 派生镜像)处理,不在此构建。
CV_IMAGE="${TSS_CV_WORKER_IMAGE:-tss-cv-worker:local}"
NLP_IMAGE="${TSS_NLP_WORKER_IMAGE:-tss-nlp-worker:local}"
# Legacy CPU worker(保留作为 fallback,默认不构建,需要时 BUILD_LEGACY=true)
LEGACY_IMAGE="${TRAINING_WORKER_IMAGE:-tss-training-worker:local}"
BUILD_LEGACY="${BUILD_LEGACY:-false}"

WORKER_DIR="${ROOT_DIR}/k8s/training-worker"

build_and_load() {
  local image="$1"
  local dockerfile="$2"
  echo "构建训练 Worker 镜像: ${image} (${dockerfile})"
  docker build -f "${WORKER_DIR}/${dockerfile}" -t "${image}" "${WORKER_DIR}"
  if [[ "${LOAD_TO_KIND}" == "true" ]] \
    && [[ -x "${KIND}" ]] \
    && "${KIND}" get clusters 2>/dev/null | grep -Fxq "${CLUSTER_NAME}"; then
    echo "加载镜像到 kind 集群: ${CLUSTER_NAME}"
    "${KIND}" load docker-image "${image}" --name "${CLUSTER_NAME}"
  else
    echo "跳过加载镜像到 kind；派生镜像将通过共享仓库分发"
  fi
}

build_and_load "${CV_IMAGE}" "Dockerfile.cv"
build_and_load "${NLP_IMAGE}" "Dockerfile.nlp"

if [[ "${BUILD_LEGACY}" == "true" ]]; then
  BASE_IMAGE="${TRAINING_WORKER_BASE_IMAGE:-python:3.11-slim}"
  INSTALL_PYTORCH_CPU="${TRAINING_WORKER_INSTALL_PYTORCH_CPU:-true}"
  echo "构建 Legacy 训练 Worker 镜像: ${LEGACY_IMAGE}"
  docker build \
    --build-arg "TRAINING_WORKER_BASE_IMAGE=${BASE_IMAGE}" \
    --build-arg "INSTALL_PYTORCH_CPU=${INSTALL_PYTORCH_CPU}" \
    -t "${LEGACY_IMAGE}" \
    "${WORKER_DIR}"
  if [[ "${LOAD_TO_KIND}" == "true" ]] \
    && [[ -x "${KIND}" ]] \
    && "${KIND}" get clusters 2>/dev/null | grep -Fxq "${CLUSTER_NAME}"; then
    "${KIND}" load docker-image "${LEGACY_IMAGE}" --name "${CLUSTER_NAME}"
  fi
fi

echo "训练 Worker 镜像就绪: ${CV_IMAGE} / ${NLP_IMAGE}"
