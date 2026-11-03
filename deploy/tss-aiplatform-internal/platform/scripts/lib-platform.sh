#!/usr/bin/env bash
set -Eeuo pipefail

platform_scripts_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
platform_root="$(cd "${platform_scripts_root}/.." && pwd)"
internal_root="$(cd "${platform_root}/.." && pwd)"

# shellcheck source=../../scripts/lib.sh
source "${internal_root}/scripts/lib.sh"

validate_port() {
  local name="$1"
  local value="${!name:-}"
  [[ $value =~ ^[0-9]{4,5}$ && $value -ge 1024 && $value -le 65535 ]] \
    || die "$name must be an unprivileged TCP port: $value"
}

load_platform_config() {
  local node_config="${1:-}"
  local platform_config="${2:-}"
  if [[ $(id -u) -eq 0 ]]; then
    local protected_file protected_owner protected_mode
    for protected_file in "$node_config" "$platform_config"; do
      [[ -f $protected_file && ! -L $protected_file ]] \
        || die "root configuration is absent or symbolic: $protected_file"
      protected_owner="$(stat -c '%u' "$protected_file")"
      protected_mode="$(stat -c '%a' "$protected_file")"
      [[ $protected_owner == 0 && $protected_mode =~ ^[0-7]{3,4}$ \
        && $((8#$protected_mode & 0022)) -eq 0 ]] \
        || die "root configuration must be root-owned and not group/world-writable: $protected_file"
    done
  fi
  load_internal_config "$node_config"
  [[ -f $platform_config ]] || die "platform config does not exist: $platform_config"

  set -a
  # This is a root-administered, non-secret environment overlay.
  # shellcheck disable=SC1090
  source "$platform_config"
  set +a

  # Optional for backwards-compatible CPU-only platform configurations.
  TSS_ENABLE_GPU_WORKER="${TSS_ENABLE_GPU_WORKER:-false}"
  TSS_K8S_CLIENT_MODE="${TSS_K8S_CLIENT_MODE:-fabric8}"
  TSS_EXACT_GPU_SELECTION_ENABLED="${TSS_EXACT_GPU_SELECTION_ENABLED:-false}"
  TSS_GPU_DEVICE_CLASS_NAME="${TSS_GPU_DEVICE_CLASS_NAME:-gpu.nvidia.com}"
  TSS_GPU_INFERENCE_WORKER_IMAGE="${TSS_GPU_INFERENCE_WORKER_IMAGE:-crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-inference-worker-gpu@sha256:7354987c572dfca03f027442cbd034483f4c915eecf1e4df139e01b35b33c114}"

  local name
  for name in \
    TSS_PLATFORM_ENVIRONMENT TSS_PLATFORM_ROOT TSS_REPOSITORY_ROOT \
    TSS_PLATFORM_HOSTNAME TSS_PLATFORM_BIND_IP TSS_PLATFORM_WORKER_IP \
    TSS_PLATFORM_WORKER_NODE \
    TSS_BACKEND_PORT TSS_POSTGRES_PORT TSS_REDIS_PORT TSS_MINIO_API_PORT \
    TSS_MINIO_CONSOLE_PORT TSS_MLFLOW_PORT \
    TSS_CORS_ALLOWED_ORIGIN_PATTERNS TSS_KUBECTL_PATH \
    TSS_ADMIN_KUBECONFIG TSS_MIN_ROOT_FREE_GIB TSS_MIN_PLATFORM_FREE_GIB \
    TSS_POSTGRES_IMAGE TSS_MINIO_IMAGE TSS_REDIS_IMAGE TSS_MLFLOW_IMAGE TSS_BACKEND_IMAGE; do
    require_var "$name"
  done

  [[ $TSS_CLUSTER_ID == tss-aiplatform-internal ]] \
    || die "refusing non-internal cluster: $TSS_CLUSTER_ID"
  has_role control-plane || die "platform services may run only on the reviewed control plane"
  [[ $TSS_PLATFORM_ENVIRONMENT == tss-aiplatform-internal ]] \
    || die "refusing platform environment: $TSS_PLATFORM_ENVIRONMENT"
  [[ $TSS_PLATFORM_BIND_IP == "$TSS_NODE_IP" ]] \
    || die "platform bind IP must equal the reviewed control-plane node IP"
  validate_dns_label TSS_PLATFORM_HOSTNAME
  validate_ipv4 TSS_PLATFORM_BIND_IP
  validate_ipv4 TSS_PLATFORM_WORKER_IP
  [[ $TSS_PLATFORM_WORKER_IP != "$TSS_PLATFORM_BIND_IP" ]] \
    || die "worker and control-plane IPs must differ"
  validate_dns_label TSS_PLATFORM_WORKER_NODE
  validate_bool TSS_ENABLE_GPU_WORKER
  validate_bool TSS_EXACT_GPU_SELECTION_ENABLED
  [[ $TSS_K8S_CLIENT_MODE == fabric8 || $TSS_K8S_CLIENT_MODE == kubectl ]] \
    || die "TSS_K8S_CLIENT_MODE must be fabric8 or kubectl"
  [[ $TSS_GPU_DEVICE_CLASS_NAME =~ ^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$ ]] \
    || die "TSS_GPU_DEVICE_CLASS_NAME is invalid"
  if [[ $TSS_EXACT_GPU_SELECTION_ENABLED == true ]]; then
    [[ $TSS_ENABLE_GPU_WORKER == true ]] \
      || die "exact GPU selection requires TSS_ENABLE_GPU_WORKER=true"
    [[ $TSS_K8S_CLIENT_MODE == kubectl ]] \
      || die "exact GPU selection requires TSS_K8S_CLIENT_MODE=kubectl"
    [[ $TSS_GPU_INFERENCE_WORKER_IMAGE =~ @sha256:[0-9a-f]{64}$ ]] \
      || die "exact GPU selection requires an immutable GPU inference image digest"
    grep -F "|${TSS_GPU_INFERENCE_WORKER_IMAGE}|gpu-inference|" \
      "${internal_root}/reproducible/gpu-runtime-images.lock" >/dev/null \
      || die "GPU inference image is absent from the committed runtime lock"
  fi

  for name in TSS_PLATFORM_ROOT TSS_REPOSITORY_ROOT TSS_KUBECTL_PATH TSS_ADMIN_KUBECONFIG; do
    validate_path "$name"
  done
  [[ $TSS_PLATFORM_ROOT == "${TSS_PROJECT_ROOT}/platform" ]] \
    || die "TSS_PLATFORM_ROOT must be the dedicated project platform directory"
  [[ $TSS_REPOSITORY_ROOT == "$TSS_PROJECT_ROOT"/* ]] \
    || die "TSS_REPOSITORY_ROOT must remain below TSS_PROJECT_ROOT"
  [[ $TSS_REPOSITORY_ROOT != "$TSS_PLATFORM_ROOT" \
    && $TSS_REPOSITORY_ROOT != "$TSS_PLATFORM_ROOT"/* ]] \
    || die "repository and mutable platform data must be separate"

  local -A seen_ports=()
  for name in TSS_BACKEND_PORT TSS_POSTGRES_PORT TSS_REDIS_PORT TSS_MINIO_API_PORT \
    TSS_MINIO_CONSOLE_PORT TSS_MLFLOW_PORT; do
    validate_port "$name"
    [[ -z ${seen_ports[${!name}]:-} ]] || die "duplicate platform port: ${!name}"
    seen_ports[${!name}]="$name"
  done
  [[ $TSS_MIN_ROOT_FREE_GIB =~ ^[1-9][0-9]*$ \
    && $TSS_MIN_PLATFORM_FREE_GIB =~ ^[1-9][0-9]*$ ]] \
    || die "free-space gates must be positive integer GiB values"
  (( TSS_MIN_ROOT_FREE_GIB >= 10 )) \
    || die "root free-space gate must remain at least 10 GiB"

  for name in TSS_POSTGRES_IMAGE TSS_MINIO_IMAGE TSS_REDIS_IMAGE TSS_MLFLOW_IMAGE TSS_BACKEND_IMAGE; do
    [[ ${!name} == tss-aiplatform-internal/* && ${!name} != *:latest ]] \
      || die "$name must be a project-specific immutable alias"
  done
  [[ $TSS_CORS_ALLOWED_ORIGIN_PATTERNS != *REPLACE* \
    && $TSS_PLATFORM_BIND_IP != *REPLACE* ]] \
    || die "platform config still contains REPLACE placeholders"
}

load_platform_secrets() {
  local secrets_file="${1:-}"
  [[ -f $secrets_file ]] || die "platform secrets do not exist: $secrets_file"
  [[ $secrets_file == "${TSS_PLATFORM_ROOT}/config/platform.secrets.env" ]] \
    || die "platform secrets must use the dedicated protected path"
  local owner mode
  owner="$(stat -c '%u' "$secrets_file")"
  mode="$(stat -c '%a' "$secrets_file")"
  [[ $owner == 0 && $mode == 600 ]] \
    || die "platform secrets must be owned by root with mode 600"

  set -a
  # The file is generated locally, root-owned and never committed.
  # shellcheck disable=SC1090
  source "$secrets_file"
  set +a
  local name
  for name in TSS_POSTGRES_DB TSS_POSTGRES_USER TSS_POSTGRES_PASSWORD \
    TSS_MINIO_ROOT_USER TSS_MINIO_ROOT_PASSWORD TSS_INTERNAL_CALLBACK_TOKEN; do
    require_var "$name"
    [[ ${!name} != *$'\n'* && ${!name} != *$'\r'* ]] \
      || die "$name contains a newline"
  done
  [[ $TSS_POSTGRES_DB =~ ^[a-z][a-z0-9_]{0,31}$ \
    && $TSS_POSTGRES_USER =~ ^[a-z][a-z0-9_]{0,31}$ ]] \
    || die "PostgreSQL database/user identifiers are invalid"
  (( ${#TSS_POSTGRES_PASSWORD} >= 32 \
    && ${#TSS_MINIO_ROOT_PASSWORD} >= 32 \
    && ${#TSS_INTERNAL_CALLBACK_TOKEN} >= 48 )) \
    || die "generated platform secrets are shorter than the security contract"
}

require_control_plane_identity() {
  [[ $(hostname -s) == "$TSS_PLATFORM_HOSTNAME" ]] \
    || die "current host does not match the reviewed physical control-plane hostname"
  local actual_ip
  actual_ip="$(ip -4 -o addr show scope global | awk -v wanted="$TSS_NODE_IP" '$4 ~ ("^" wanted "/") {print wanted; exit}')"
  [[ $actual_ip == "$TSS_NODE_IP" ]] \
    || die "reviewed control-plane IP is not present on this host"
  [[ $(findmnt -n -o UUID --target "$TSS_STORAGE_MOUNT_POINT") == "$TSS_EXPECTED_STORAGE_UUID" ]] \
    || die "project filesystem UUID does not match the reviewed node configuration"
}

require_space_gates() {
  local root_free_gib platform_free_gib
  root_free_gib="$(df -Pk / | awk 'NR==2 {print int($4 / 1024 / 1024)}')"
  platform_free_gib="$(df -Pk "$TSS_STORAGE_MOUNT_POINT" | awk 'NR==2 {print int($4 / 1024 / 1024)}')"
  (( root_free_gib >= TSS_MIN_ROOT_FREE_GIB )) \
    || die "root filesystem has ${root_free_gib} GiB free; need ${TSS_MIN_ROOT_FREE_GIB} GiB"
  (( platform_free_gib >= TSS_MIN_PLATFORM_FREE_GIB )) \
    || die "platform filesystem has ${platform_free_gib} GiB free; need ${TSS_MIN_PLATFORM_FREE_GIB} GiB"
}

require_apply_confirmation() {
  local mode="$1"
  local flag="${2:-}"
  local confirmed_node="${3:-}"
  if [[ $mode == --apply ]]; then
    [[ $flag == --confirm-node && $confirmed_node == "$TSS_NODE_NAME" ]] \
      || die "--apply requires --confirm-node ${TSS_NODE_NAME}"
  else
    [[ -z $flag && -z $confirmed_node ]] \
      || die "confirmation arguments are accepted only with --apply"
  fi
}
