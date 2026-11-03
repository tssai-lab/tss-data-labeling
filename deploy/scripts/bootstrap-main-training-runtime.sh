#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export TSS_NODE_ID="${TSS_NODE_ID:-main}"
export TSS_CLUSTER_NAME="${TSS_CLUSTER_NAME:-tss-training}"
export TSS_PLATFORM_DIR="${TSS_PLATFORM_DIR:-/opt/tss-platform}"
export TSS_BACKEND_HOST_PORT="${TSS_BACKEND_HOST_PORT:-18080}"
export TSS_MINIO_HOST_PORT="${TSS_MINIO_HOST_PORT:-9010}"
export TSS_MLFLOW_HOST_PORT="${TSS_MLFLOW_HOST_PORT:-15000}"
export TSS_RUNTIME_IMAGE_SOURCE="${TSS_RUNTIME_IMAGE_SOURCE:-build}"
export TSS_RUNTIME_IMAGE_TAG="${TSS_RUNTIME_IMAGE_TAG:-local}"
export TSS_MLFLOW_IMAGE="${TSS_MLFLOW_IMAGE:-tss-platform-mlflow:local}"
export TSS_TRAINING_WORKER_IMAGE="${TSS_TRAINING_WORKER_IMAGE:-tss-training-worker:local}"
export TSS_INFERENCE_WORKER_IMAGE="${TSS_INFERENCE_WORKER_IMAGE:-tss-inference-worker-main-cpu:local}"

exec "${script_dir}/bootstrap-node-training-runtime.sh" "$@"
