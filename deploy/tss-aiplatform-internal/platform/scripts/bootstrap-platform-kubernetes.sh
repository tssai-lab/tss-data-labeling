#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

mode="${1:-}"
node_config="${2:-}"
platform_config="${3:-}"
confirmation_flag="${4:-}"
confirmation_node="${5:-}"
[[ $mode == --check || $mode == --apply ]] \
  || die "usage: $0 --check /path/to/node.env /path/to/platform.env; $0 --apply ... --confirm-node NODE"
load_platform_config "$node_config" "$platform_config"
require_apply_confirmation "$mode" "$confirmation_flag" "$confirmation_node"
[[ $(id -u) -eq 0 ]] || die "Kubernetes platform preparation must run as root"
require_control_plane_identity
require_space_gates
[[ -x $TSS_KUBECTL_PATH ]] || die "kubectl is not executable: $TSS_KUBECTL_PATH"
[[ -r $TSS_ADMIN_KUBECONFIG ]] || die "admin kubeconfig is unavailable"
[[ -f ${TSS_REPOSITORY_ROOT}/k8s/base/training-namespace.yaml \
  && -f ${TSS_REPOSITORY_ROOT}/k8s/base/training-resource-policy.yaml \
  && -f ${TSS_REPOSITORY_ROOT}/k8s/base/training-service-account.yaml \
  && -f ${TSS_REPOSITORY_ROOT}/k8s/local/host-services.template.yaml \
  && -f ${TSS_REPOSITORY_ROOT}/deploy/tss-aiplatform-internal/platform/k8s/backend-access.yaml ]] \
  || die "repository does not contain the complete reviewed platform manifests"

admin=("$TSS_KUBECTL_PATH" --kubeconfig "$TSS_ADMIN_KUBECONFIG" --request-timeout=15s)
api_server="$("${admin[@]}" config view --raw --minify -o jsonpath='{.clusters[0].cluster.server}')"
[[ $api_server == "https://${TSS_CONTROL_PLANE_ENDPOINT}" ]] \
  || die "admin kubeconfig points to an unexpected API server: $api_server"
mapfile -t nodes < <("${admin[@]}" get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort)
[[ ${#nodes[@]} -eq 2 \
  && " ${nodes[*]} " == *" ${TSS_NODE_NAME} "* \
  && " ${nodes[*]} " == *" ${TSS_PLATFORM_WORKER_NODE} "* ]] \
  || die "cluster nodes do not match the reviewed two-node internal cluster"
[[ " ${nodes[*]} " != *" k8s-master "* && " ${nodes[*]} " != *" k8s-node1 "* ]] \
  || die "refusing a kubeconfig that resembles the Main/Second cluster"

metrics_installer="${TSS_REPOSITORY_ROOT}/deploy/tss-aiplatform-internal/scripts/install-metrics-server.sh"
[[ -x $metrics_installer ]] || die "Metrics Server installer is not executable"
if [[ $mode == --check ]]; then
  KUBECTL="$TSS_KUBECTL_PATH" KUBECONFIG="$TSS_ADMIN_KUBECONFIG" \
    "$metrics_installer" --check "$node_config"
else
  KUBECTL="$TSS_KUBECTL_PATH" KUBECONFIG="$TSS_ADMIN_KUBECONFIG" \
    "$metrics_installer" --apply "$node_config" \
      --confirm-node "$TSS_NODE_NAME"
fi

host_manifest="$(mktemp)"
trap 'rm -f "$host_manifest"' EXIT
sed \
  -e "s|__HOST_GATEWAY__|${TSS_PLATFORM_BIND_IP}|g" \
  -e "s|__BACKEND_HOST_PORT__|${TSS_BACKEND_PORT}|g" \
  -e "s|__MINIO_HOST_PORT__|${TSS_MINIO_API_PORT}|g" \
  -e "s|__MLFLOW_HOST_PORT__|${TSS_MLFLOW_PORT}|g" \
  "${TSS_REPOSITORY_ROOT}/k8s/local/host-services.template.yaml" >"$host_manifest"
grep -F '__' "$host_manifest" >/dev/null \
  && die "rendered host service manifest contains an unresolved placeholder"

for manifest in \
  "${TSS_REPOSITORY_ROOT}/k8s/base/training-namespace.yaml" \
  "${TSS_REPOSITORY_ROOT}/k8s/base/training-resource-policy.yaml" \
  "${TSS_REPOSITORY_ROOT}/k8s/base/training-service-account.yaml" \
  "${TSS_REPOSITORY_ROOT}/deploy/tss-aiplatform-internal/platform/k8s/backend-access.yaml" \
  "$host_manifest"; do
  "${admin[@]}" apply --dry-run=client -f "$manifest" >/dev/null
done
if [[ $mode == --check ]]; then
  "${script_dir}/install-gpu-worker.sh" --check "$node_config" "$platform_config"
  "${script_dir}/install-dcgm-exporter.sh" --check "$node_config" "$platform_config"
  echo "PASS: internal Kubernetes platform manifests and target identity passed without writes"
  exit 0
fi

exec 9>/run/lock/tss-aiplatform-platform-kubernetes.lock
flock -n 9 || die "another internal platform Kubernetes preparation is running"
install -d -m 0700 -o root -g root "${TSS_PLATFORM_ROOT}/config"
"${admin[@]}" apply -f "${TSS_REPOSITORY_ROOT}/k8s/base/training-namespace.yaml" >/dev/null
"${admin[@]}" apply -f "${TSS_REPOSITORY_ROOT}/k8s/base/training-resource-policy.yaml" >/dev/null
"${admin[@]}" apply -f "${TSS_REPOSITORY_ROOT}/k8s/base/training-service-account.yaml" >/dev/null
"${admin[@]}" apply -f "${TSS_REPOSITORY_ROOT}/deploy/tss-aiplatform-internal/platform/k8s/backend-access.yaml" >/dev/null
"${admin[@]}" apply -f "$host_manifest" >/dev/null
"${script_dir}/install-gpu-worker.sh" --apply "$node_config" "$platform_config" \
  --confirm-node "$TSS_NODE_NAME"
"${script_dir}/install-dcgm-exporter.sh" --apply "$node_config" "$platform_config" \
  --confirm-node "$TSS_NODE_NAME"

token_b64=''
ca_b64=''
for _attempt in $(seq 1 30); do
  token_b64="$("${admin[@]}" -n tss-training get secret tss-backend-access-token -o jsonpath='{.data.token}' 2>/dev/null || true)"
  ca_b64="$("${admin[@]}" -n tss-training get secret tss-backend-access-token -o jsonpath='{.data.ca\.crt}' 2>/dev/null || true)"
  [[ -n $token_b64 && -n $ca_b64 ]] && break
  sleep 1
done
[[ -n $token_b64 && -n $ca_b64 ]] || die "service-account token controller did not populate the access Secret"
token="$(printf '%s' "$token_b64" | base64 -d)"
[[ $token =~ ^[A-Za-z0-9._-]+$ && ${#token} -ge 32 ]] || die "generated service-account token is invalid"

kubeconfig="${TSS_PLATFORM_ROOT}/config/backend.kubeconfig"
tmp_kubeconfig="$(mktemp "${TSS_PLATFORM_ROOT}/config/.backend.kubeconfig.XXXXXX")"
trap 'rm -f "$host_manifest" "$tmp_kubeconfig"' EXIT
umask 077
{
  printf 'apiVersion: v1\nkind: Config\n'
  printf 'clusters:\n- name: tss-aiplatform-internal\n  cluster:\n'
  printf '    server: %s\n    certificate-authority-data: %s\n' "$api_server" "$ca_b64"
  printf 'users:\n- name: tss-backend\n  user:\n    token: %s\n' "$token"
  printf 'contexts:\n- name: tss-aiplatform-internal-backend\n  context:\n'
  printf '    cluster: tss-aiplatform-internal\n    namespace: tss-training\n    user: tss-backend\n'
  printf 'current-context: tss-aiplatform-internal-backend\n'
} >"$tmp_kubeconfig"
chown root:root "$tmp_kubeconfig"
chmod 0640 "$tmp_kubeconfig"
mv "$tmp_kubeconfig" "$kubeconfig"
trap 'rm -f "$host_manifest"' EXIT

KUBECTL="$TSS_KUBECTL_PATH" KUBECONFIG="$kubeconfig" \
  "${script_dir}/verify-internal-kubeadm.sh"
echo "PASS: internal Kubernetes resources and restricted backend credential are ready"
