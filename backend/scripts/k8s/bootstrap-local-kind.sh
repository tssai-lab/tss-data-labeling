#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TOOLS_DIR="${ROOT_DIR}/.tools/bin"
KIND="${KIND:-${TOOLS_DIR}/kind}"
KUBECTL="${KUBECTL:-${TOOLS_DIR}/kubectl}"
CLUSTER_NAME="${CLUSTER_NAME:-tss-training}"
KUBECONFIG_PATH="${KUBECONFIG_PATH:-${ROOT_DIR}/k8s/.kube/config}"
BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-8080}"
MINIO_HOST_PORT="${MINIO_HOST_PORT:-9010}"
MLFLOW_HOST_PORT="${MLFLOW_HOST_PORT:-5000}"
MODEL_CACHE_HOST_PATH="${MODEL_CACHE_HOST_PATH:-}"
MODEL_CACHE_NODE_PATH="${MODEL_CACHE_NODE_PATH:-/var/lib/tss-platform/model-cache}"
KIND_CONFIG="${ROOT_DIR}/k8s/kind/cluster.yaml"

require_executable() {
  local executable="$1"
  if [[ ! -x "${executable}" ]]; then
    echo "Required executable not found: ${executable}" >&2
    exit 1
  fi
}

require_executable "${KIND}"
require_executable "${KUBECTL}"

validate_absolute_cache_path() {
  local name="$1"
  local value="$2"
  local segment
  local -a segments

  if [[ ! $value =~ ^/[A-Za-z0-9._/-]+$ || $value == / || $value == *"//"* ]]; then
    echo "$name must be a safe absolute path without whitespace." >&2
    exit 1
  fi
  IFS='/' read -r -a segments <<< "$value"
  for segment in "${segments[@]}"; do
    if [[ $segment == . || $segment == .. ]]; then
      echo "$name must not contain dot segments." >&2
      exit 1
    fi
  done
}

if [[ -n $MODEL_CACHE_HOST_PATH ]]; then
  validate_absolute_cache_path MODEL_CACHE_HOST_PATH "$MODEL_CACHE_HOST_PATH"
  validate_absolute_cache_path MODEL_CACHE_NODE_PATH "$MODEL_CACHE_NODE_PATH"
  if [[ ! -d $MODEL_CACHE_HOST_PATH ]]; then
    echo "MODEL_CACHE_HOST_PATH does not exist: $MODEL_CACHE_HOST_PATH" >&2
    exit 1
  fi
  MODEL_CACHE_HOST_PATH="$(cd "$MODEL_CACHE_HOST_PATH" && pwd -P)"
  cache_sentinel="${MODEL_CACHE_HOST_PATH}/.tss-model-cache-root"
  if [[ -L $cache_sentinel || ! -f $cache_sentinel ]]; then
    echo "Physical model cache sentinel is missing: $cache_sentinel" >&2
    exit 1
  fi

  rendered_kind_config="$(mktemp)"
  trap 'rm -f "$rendered_kind_config"' EXIT
  awk \
    -v host_path="$MODEL_CACHE_HOST_PATH" \
    -v node_path="$MODEL_CACHE_NODE_PATH" \
    '
      /# __TSS_MODEL_CACHE_EXTRA_MOUNT__/ {
        print "    extraMounts:"
        print "      - hostPath: \"" host_path "\""
        print "        containerPath: \"" node_path "\""
        next
      }
      { print }
    ' "$KIND_CONFIG" > "$rendered_kind_config"
  if ! grep -Fq "containerPath: \"$MODEL_CACHE_NODE_PATH\"" "$rendered_kind_config"; then
    echo "kind config is missing the model cache extraMount marker." >&2
    exit 1
  fi
  KIND_CONFIG="$rendered_kind_config"
fi

verify_model_cache_mounts() {
  if [[ -z $MODEL_CACHE_HOST_PATH ]]; then
    return
  fi

  local found_node=false
  local mount_destination
  local mount_found
  local mount_source
  local node_container

  while IFS= read -r node_container; do
    [[ -n $node_container ]] || continue
    found_node=true
    mount_found=false
    while read -r mount_source mount_destination; do
      if [[ $mount_source == "$MODEL_CACHE_HOST_PATH" \
        && $mount_destination == "$MODEL_CACHE_NODE_PATH" ]]; then
        mount_found=true
        break
      fi
    done < <(
      docker inspect "$node_container" \
        --format '{{range .Mounts}}{{println .Source .Destination}}{{end}}'
    )
    if [[ $mount_found != true ]]; then
      echo "Kind node $node_container is missing the physical model cache mount." >&2
      echo "Automatic cluster recreation is disabled because it interrupts active jobs." >&2
      echo "During an approved maintenance window run:" >&2
      echo "  $KIND delete cluster --name $CLUSTER_NAME" >&2
      echo "Then rerun this bootstrap script." >&2
      exit 1
    fi
  done < <("${KIND}" get nodes --name "${CLUSTER_NAME}")

  if [[ $found_node != true ]]; then
    echo "No kind nodes were found for cluster $CLUSTER_NAME." >&2
    exit 1
  fi
}

mkdir -p "$(dirname "${KUBECONFIG_PATH}")"

if "${KIND}" get clusters | grep -Fxq "${CLUSTER_NAME}"; then
  verify_model_cache_mounts
  "${KIND}" export kubeconfig \
    --name "${CLUSTER_NAME}" \
    --kubeconfig "${KUBECONFIG_PATH}"
else
  "${KIND}" create cluster \
    --name "${CLUSTER_NAME}" \
    --config "${KIND_CONFIG}" \
    --kubeconfig "${KUBECONFIG_PATH}" \
    --wait 5m
  verify_model_cache_mounts
fi

export KUBECONFIG="${KUBECONFIG_PATH}"

"${KUBECTL}" wait --for=condition=Ready node --all --timeout=180s
if [[ -n $MODEL_CACHE_HOST_PATH ]]; then
  "${KUBECTL}" label node --all tss.ai/model-cache-ready=true --overwrite
else
  "${KUBECTL}" label node --all tss.ai/model-cache-ready- >/dev/null 2>&1 || true
fi
"${KUBECTL}" apply -f "${ROOT_DIR}/k8s/base/training-namespace.yaml"
"${KUBECTL}" apply -f "${ROOT_DIR}/k8s/base/training-resource-policy.yaml"
"${KUBECTL}" apply -f "${ROOT_DIR}/k8s/base/training-service-account.yaml"

HOST_GATEWAY="$(
  docker network inspect kind \
    --format '{{range .IPAM.Config}}{{if .Gateway}}{{.Gateway}}{{println}}{{end}}{{end}}' \
  | head -1 | tr -d '[:space:]'
)"
if [[ -z "${HOST_GATEWAY}" ]]; then
  HOST_GATEWAY="$(docker network inspect kind --format '{{(index .IPAM.Config 1).Gateway}}' 2>/dev/null | tr -d '[:space:]')"
fi
if [[ -z "${HOST_GATEWAY}" ]]; then
  echo "Unable to determine Docker kind network gateway" >&2
  exit 1
fi

sed \
  -e "s/__HOST_GATEWAY__/${HOST_GATEWAY}/g" \
  -e "s/__BACKEND_HOST_PORT__/${BACKEND_HOST_PORT}/g" \
  -e "s/__MINIO_HOST_PORT__/${MINIO_HOST_PORT}/g" \
  -e "s/__MLFLOW_HOST_PORT__/${MLFLOW_HOST_PORT}/g" \
  "${ROOT_DIR}/k8s/local/host-services.template.yaml" \
  | "${KUBECTL}" apply -f -

echo "Cluster: ${CLUSTER_NAME}"
echo "Kubeconfig: ${KUBECONFIG_PATH}"
echo "Host gateway: ${HOST_GATEWAY}"
"${KUBECTL}" get nodes -o wide
"${KUBECTL}" get resourcequota,limitrange,service -n tss-training

WORKER_BUILD_SCRIPT="${ROOT_DIR}/k8s/training-worker/build-and-load.sh"
if [[ "${SKIP_WORKER_BUILD:-false}" != "true" && -x "${WORKER_BUILD_SCRIPT}" ]]; then
  echo "构建并加载训练 Worker 镜像..."
  CLUSTER_NAME="${CLUSTER_NAME}" KIND="${KIND}" \
    TRAINING_WORKER_IMAGE="${TRAINING_WORKER_IMAGE:-tss-training-worker:local}" \
    bash "${WORKER_BUILD_SCRIPT}" || echo "WARN: 训练 Worker 镜像构建/加载失败，Job 可能 ImagePullBackOff"
fi
