#!/usr/bin/env bash
set -Eeuo pipefail

# Compatibility entry point for the existing Main node. New nodes should set
# TSS_NODE_CONFIG and call bootstrap-node-backend.sh directly.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export TSS_NODE_ID="${TSS_NODE_ID:-main}"
export TSS_CLUSTER_NAME="${TSS_CLUSTER_NAME:-main-kubeadm}"
export TSS_DEPLOYMENT_SMOKE_NODE="${TSS_DEPLOYMENT_SMOKE_NODE:-k8s-master}"
export TSS_K8S_KUBECONFIG="${TSS_K8S_KUBECONFIG:-/opt/tss-platform/k8s/.kube/admin.conf}"
export TSS_K8S_VERIFY_SCRIPT="${TSS_K8S_VERIFY_SCRIPT:-/opt/tss-platform/backend/scripts/k8s/verify-kubeadm.sh}"
export TSS_PLATFORM_DIR="${TSS_PLATFORM_DIR:-/opt/tss-platform}"
export TSS_MLFLOW_IMAGE="${TSS_MLFLOW_IMAGE:-tss-platform-mlflow:local}"
export TSS_TRAINING_WORKER_IMAGE="${TSS_TRAINING_WORKER_IMAGE:-tss-training-worker:local}"
export TSS_INFERENCE_WORKER_IMAGE="${TSS_INFERENCE_WORKER_IMAGE:-tss-inference-worker-main-cpu:local}"

exec "${script_dir}/bootstrap-node-backend.sh" "$@"
