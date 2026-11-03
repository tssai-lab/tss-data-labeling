#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

metrics_complete() {
  local metrics_file="$1"
  for metric_name in \
    DCGM_FI_DEV_GPU_UTIL DCGM_FI_DEV_FB_USED DCGM_FI_DEV_GPU_TEMP; do
    grep -E "^${metric_name}\\{" "$metrics_file" >/dev/null \
      || return 1
  done
  grep -E '^DCGM_FI_DEV_FB_(TOTAL|FREE)\{' "$metrics_file" >/dev/null
}

usage() {
  echo "usage: $0 --self-test" >&2
  echo "usage: $0 --check /path/to/control.env /path/to/platform.env" >&2
  echo "       $0 --apply /path/to/control.env /path/to/platform.env --confirm-node NODE" >&2
  exit 2
}

mode="${1:-}"
if [[ $mode == --self-test ]]; then
  metrics_file="$(mktemp)"
  trap 'rm -f "$metrics_file"' EXIT
  printf '%s\n' \
    'DCGM_FI_DEV_GPU_UTIL{gpu="0"} 0' \
    'DCGM_FI_DEV_FB_USED{gpu="0"} 29' \
    'DCGM_FI_DEV_FB_FREE{gpu="0"} 16347' \
    'DCGM_FI_DEV_GPU_TEMP{gpu="0"} 39' >"$metrics_file"
  metrics_complete "$metrics_file" \
    || die "complete DCGM metrics must pass"
  sed -i '/DCGM_FI_DEV_GPU_TEMP/d' "$metrics_file"
  if metrics_complete "$metrics_file"; then
    die "incomplete DCGM metrics must fail"
  fi
  echo "DCGM Exporter metric parser self-test passed"
  exit 0
fi

node_config="${2:-}"
platform_config="${3:-}"
confirmation_flag="${4:-}"
confirmation_node="${5:-}"
[[ $mode == --check || $mode == --apply ]] || usage
[[ -n $node_config && -n $platform_config ]] || usage
load_platform_config "$node_config" "$platform_config"
require_apply_confirmation "$mode" "$confirmation_flag" "$confirmation_node"
[[ $EUID -eq 0 ]] || die "DCGM Exporter installation must run as root"
require_control_plane_identity

if [[ $TSS_ENABLE_GPU_WORKER != true ]]; then
  echo "SKIP: DCGM Exporter installation is disabled by TSS_ENABLE_GPU_WORKER=false"
  exit 0
fi

for command_name in awk curl flock grep mktemp seq sleep sort; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done
[[ -x $TSS_KUBECTL_PATH ]] || die "kubectl is not executable: $TSS_KUBECTL_PATH"
[[ -r $TSS_ADMIN_KUBECONFIG ]] \
  || die "admin kubeconfig is not readable: $TSS_ADMIN_KUBECONFIG"

manifest="${internal_root}/manifests/dcgm-exporter.yaml"
[[ -f $manifest && ! -L $manifest ]] \
  || die "versioned DCGM Exporter manifest is absent"
expected_image="nvcr.io/nvidia/k8s/dcgm-exporter:${TSS_DCGM_EXPORTER_VERSION}"
expected_image_digest="$(awk -v image="$expected_image" \
  '$1 == "image" && $2 == image {print $3}' "${internal_root}/artifacts.lock")"
[[ $expected_image_digest =~ ^sha256:[0-9a-f]{64}$ ]] \
  || die "DCGM Exporter image digest is absent from artifacts.lock"
[[ $(grep -Fc "          image: ${expected_image}" "$manifest") -eq 1 ]] \
  || die "DCGM Exporter manifest image differs from the version contract"
grep -F '        tss.ai/accelerator: nvidia' "$manifest" >/dev/null \
  || die "DCGM Exporter must target only reviewed NVIDIA nodes"
grep -F '        - key: node-role.kubernetes.io/control-plane' "$manifest" >/dev/null \
  || die "DCGM Exporter must tolerate the reviewed GPU control plane"
grep -F '          imagePullPolicy: Never' "$manifest" >/dev/null \
  || die "DCGM Exporter must use the offline project image"
grep -F '            - --address=$(HOST_IP):9400' "$manifest" >/dev/null \
  || die "DCGM Exporter must bind only to the node InternalIP"

admin=("$TSS_KUBECTL_PATH" --kubeconfig "$TSS_ADMIN_KUBECONFIG" --request-timeout=15s)
api_server="$("${admin[@]}" config view --raw --minify -o jsonpath='{.clusters[0].cluster.server}')"
[[ $api_server == "https://${TSS_CONTROL_PLANE_ENDPOINT}" ]] \
  || die "admin kubeconfig points to an unexpected API server: $api_server"
mapfile -t nodes < <("${admin[@]}" get nodes \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort)
[[ ${#nodes[@]} -eq 2 && " ${nodes[*]} " == *" ${TSS_NODE_NAME} "* \
  && " ${nodes[*]} " == *" ${TSS_PLATFORM_WORKER_NODE} "* ]] \
  || die "DCGM Exporter target is not the reviewed two-node internal cluster"
[[ " ${nodes[*]} " != *" k8s-master "* && " ${nodes[*]} " != *" k8s-node1 "* ]] \
  || die "refusing a kubeconfig that resembles the Main/Second cluster"

gpu_nodes=("$TSS_PLATFORM_WORKER_NODE")
if has_role gpu; then
  gpu_nodes+=("$TSS_NODE_NAME")
  control_taints="$("${admin[@]}" get node "$TSS_NODE_NAME" \
    -o jsonpath='{range .spec.taints[*]}{.key}:{.effect}{"\n"}{end}')"
  grep -Fx 'node-role.kubernetes.io/control-plane:NoSchedule' \
    <<<"$control_taints" >/dev/null \
    || die "GPU control plane must retain its NoSchedule taint"
fi
mapfile -t gpu_nodes < <(printf '%s\n' "${gpu_nodes[@]}" | sort -u)
gpu_ips=()
for gpu_node in "${gpu_nodes[@]}"; do
  ready="$("${admin[@]}" get node "$gpu_node" \
    -o jsonpath='{range .status.conditions[?(@.type=="Ready")]}{.status}{end}')"
  [[ $ready == True ]] || die "GPU node is not Ready: $gpu_node"
  accelerator="$("${admin[@]}" get node "$gpu_node" \
    -o jsonpath='{.metadata.labels.tss\.ai/accelerator}' 2>/dev/null || true)"
  [[ $accelerator == nvidia ]] \
    || die "GPU node does not have the reviewed NVIDIA label: $gpu_node"
  gpu_count="$("${admin[@]}" get node "$gpu_node" \
    -o jsonpath='{.status.allocatable.nvidia\.com/gpu}' 2>/dev/null || true)"
  [[ $gpu_count =~ ^[1-9][0-9]*$ ]] \
    || die "GPU node does not advertise nvidia.com/gpu: $gpu_node"
  gpu_ip="$("${admin[@]}" get node "$gpu_node" \
    -o jsonpath='{range .status.addresses[?(@.type=="InternalIP")]}{.address}{end}')"
  if [[ $gpu_node == "$TSS_PLATFORM_WORKER_NODE" ]]; then
    [[ $gpu_ip == "$TSS_PLATFORM_WORKER_IP" ]] \
      || die "GPU Worker InternalIP differs from the reviewed platform configuration"
  else
    [[ $gpu_ip == "$TSS_NODE_IP" ]] \
      || die "GPU control-plane InternalIP differs from the reviewed node configuration"
  fi
  gpu_ips+=("$gpu_ip")
done
existing_image="$("${admin[@]}" -n kube-system get daemonset tss-dcgm-exporter \
  -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
[[ -z $existing_image || $existing_image == "$expected_image" ]] \
  || die "an unmanaged DCGM Exporter image already exists: $existing_image"

"${admin[@]}" apply --dry-run=server -f "$manifest" >/dev/null
if [[ $mode == --check ]]; then
  echo "PASS: DCGM Exporter artifact, GPU node identity and server dry-run passed without writes"
  exit 0
fi

exec 9>/run/lock/tss-aiplatform-dcgm-exporter.lock
flock -n 9 || die "another DCGM Exporter installation is running"
"${admin[@]}" apply -f "$manifest" >/dev/null
if ! "${admin[@]}" -n kube-system rollout status \
  daemonset/tss-dcgm-exporter --timeout=180s; then
  "${admin[@]}" -n kube-system get daemonset,pod \
    -l app.kubernetes.io/name=dcgm-exporter -o wide >&2 || true
  "${admin[@]}" -n kube-system logs daemonset/tss-dcgm-exporter \
    --tail=100 >&2 || true
  die "DCGM Exporter rollout failed; resources were preserved for diagnosis"
fi

metrics_file="$(mktemp)"
trap 'rm -f "$metrics_file"' EXIT
for index in "${!gpu_nodes[@]}"; do
  metrics_ready=false
  for _attempt in $(seq 1 30); do
    if curl --connect-timeout 2 --max-time 5 --fail --silent \
      "http://${gpu_ips[$index]}:9400/metrics" >"$metrics_file" \
      && metrics_complete "$metrics_file"; then
      metrics_ready=true
      break
    fi
    sleep 5
  done
  [[ $metrics_ready == true ]] \
    || die "DCGM Exporter endpoint did not expose required GPU metrics: ${gpu_nodes[$index]}; resources were preserved for diagnosis"
done
echo "PASS: DCGM Exporter reports GPU utilization, memory and temperature from ${gpu_nodes[*]}"
