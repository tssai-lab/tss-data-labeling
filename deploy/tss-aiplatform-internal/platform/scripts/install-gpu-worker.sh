#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

render_plugin_manifest() {
  local source_manifest="$1"
  awk '
    $0 == "    spec:" {
      print
      print "      runtimeClassName: nvidia"
      print "      nodeSelector:"
      print "        tss.ai/accelerator: nvidia"
      next
    }
    $0 == "        effect: NoSchedule" {
      print
      print "      - key: node-role.kubernetes.io/control-plane"
      print "        operator: Exists"
      print "        effect: NoSchedule"
      next
    }
    $0 == "      - image: nvcr.io/nvidia/k8s-device-plugin:v0.17.1" {
      print
      print "        imagePullPolicy: Never"
      next
    }
    $0 == "            value: \"false\"" {
      print "            value: \"true\""
      next
    }
    { print }
  ' "$source_manifest"
}

usage() {
  echo "usage: $0 --self-test" >&2
  echo "usage: $0 --check /path/to/control.env /path/to/platform.env" >&2
  echo "       $0 --apply /path/to/control.env /path/to/platform.env --confirm-node NODE" >&2
  exit 2
}

mode="${1:-}"
if [[ $mode == --self-test ]]; then
  source_manifest="${internal_root}/manifests/nvidia-device-plugin.yml"
  rendered_manifest="$(mktemp)"
  trap 'rm -f "$rendered_manifest"' EXIT
  render_plugin_manifest "$source_manifest" >"$rendered_manifest"
  [[ $(grep -Fc '      runtimeClassName: nvidia' "$rendered_manifest") -eq 1 ]]
  [[ $(grep -Fc '      - key: node-role.kubernetes.io/control-plane' "$rendered_manifest") -eq 1 ]]
  [[ $(grep -Fc '        tss.ai/accelerator: nvidia' "$rendered_manifest") -eq 1 ]]
  [[ $(grep -Fc '        imagePullPolicy: Never' "$rendered_manifest") -eq 1 ]]
  [[ $(grep -Fc '            value: "true"' "$rendered_manifest") -eq 1 ]]
  echo "GPU Worker manifest renderer self-test passed"
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
[[ $EUID -eq 0 ]] || die "GPU Worker installation must run as root"
require_control_plane_identity

if [[ $TSS_ENABLE_GPU_WORKER != true ]]; then
  echo "SKIP: GPU Worker installation is disabled by TSS_ENABLE_GPU_WORKER=false"
  exit 0
fi

for command_name in awk flock grep mktemp seq sha256sum sleep sort; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done
[[ -x $TSS_KUBECTL_PATH ]] || die "kubectl is not executable: $TSS_KUBECTL_PATH"
[[ -r $TSS_ADMIN_KUBECONFIG ]] \
  || die "admin kubeconfig is not readable: $TSS_ADMIN_KUBECONFIG"

source_manifest="${internal_root}/manifests/nvidia-device-plugin.yml"
[[ -f $source_manifest && ! -L $source_manifest ]] \
  || die "versioned NVIDIA Device Plugin manifest is absent"
nvidia_url="https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/${TSS_NVIDIA_DEVICE_PLUGIN_VERSION}/deployments/static/nvidia-device-plugin.yml"
expected_manifest_sha="$(awk -v url="$nvidia_url" \
  '$1 == "manifest" && $2 == url {sub(/^sha256:/, "", $3); print $3}' \
  "${internal_root}/artifacts.lock")"
[[ $expected_manifest_sha =~ ^[0-9a-f]{64}$ ]] \
  || die "NVIDIA manifest checksum is absent from artifacts.lock"
[[ $(sha256sum "$source_manifest" | awk '{print $1}') == "$expected_manifest_sha" ]] \
  || die "versioned NVIDIA manifest differs from artifacts.lock"

expected_image="nvcr.io/nvidia/k8s-device-plugin:${TSS_NVIDIA_DEVICE_PLUGIN_VERSION}"
expected_image_digest="$(awk -v image="$expected_image" \
  '$1 == "image" && $2 == image {print $3}' "${internal_root}/artifacts.lock")"
[[ $expected_image_digest =~ ^sha256:[0-9a-f]{64}$ ]] \
  || die "NVIDIA Device Plugin image digest is absent from artifacts.lock"
[[ $(grep -Fc "      - image: ${expected_image}" "$source_manifest") -eq 1 ]] \
  || die "NVIDIA source manifest image differs from the version contract"

rendered_manifest="$(mktemp)"
runtime_manifest="$(mktemp)"
trap 'rm -f "$rendered_manifest" "$runtime_manifest"' EXIT
render_plugin_manifest "$source_manifest" >"$rendered_manifest"
bash "${internal_root}/scripts/render-runtime-class.sh" >"$runtime_manifest"
[[ $(grep -Fc '      runtimeClassName: nvidia' "$rendered_manifest") -eq 1 \
  && $(grep -Fc '      - key: node-role.kubernetes.io/control-plane' "$rendered_manifest") -eq 1 \
  && $(grep -Fc '        tss.ai/accelerator: nvidia' "$rendered_manifest") -eq 1 \
  && $(grep -Fc '        imagePullPolicy: Never' "$rendered_manifest") -eq 1 \
  && $(grep -Fc '            value: "true"' "$rendered_manifest") -eq 1 ]] \
  || die "rendered NVIDIA manifest does not contain the reviewed offline/runtime overrides"

admin=("$TSS_KUBECTL_PATH" --kubeconfig "$TSS_ADMIN_KUBECONFIG" --request-timeout=15s)
api_server="$("${admin[@]}" config view --raw --minify -o jsonpath='{.clusters[0].cluster.server}')"
[[ $api_server == "https://${TSS_CONTROL_PLANE_ENDPOINT}" ]] \
  || die "admin kubeconfig points to an unexpected API server: $api_server"
mapfile -t nodes < <("${admin[@]}" get nodes \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort)
[[ ${#nodes[@]} -eq 2 && " ${nodes[*]} " == *" ${TSS_NODE_NAME} "* \
  && " ${nodes[*]} " == *" ${TSS_PLATFORM_WORKER_NODE} "* ]] \
  || die "GPU Worker target is not the reviewed two-node internal cluster"
[[ " ${nodes[*]} " != *" k8s-master "* && " ${nodes[*]} " != *" k8s-node1 "* ]] \
  || die "refusing a kubeconfig that resembles the Main/Second cluster"
[[ $TSS_PLATFORM_WORKER_NODE != "$TSS_NODE_NAME" ]] \
  || die "the reviewed first GPU Worker must not be the control-plane node"

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

for gpu_node in "${gpu_nodes[@]}"; do
  ready="$("${admin[@]}" get node "$gpu_node" \
    -o jsonpath='{range .status.conditions[?(@.type=="Ready")]}{.status}{end}')"
  [[ $ready == True ]] || die "GPU node is not Ready: $gpu_node"
  existing_accelerator="$("${admin[@]}" get node "$gpu_node" \
    -o jsonpath='{.metadata.labels.tss\.ai/accelerator}' 2>/dev/null || true)"
  [[ -z $existing_accelerator || $existing_accelerator == nvidia ]] \
    || die "GPU node has a conflicting accelerator label: ${gpu_node}=${existing_accelerator}"
  if [[ $gpu_node == "$TSS_PLATFORM_WORKER_NODE" ]]; then
    existing_node_pool="$("${admin[@]}" get node "$gpu_node" \
      -o jsonpath='{.metadata.labels.tss\.ai/node-pool}' 2>/dev/null || true)"
    [[ $existing_node_pool == cpu ]] \
      || die "GPU Worker must preserve the reviewed tss.ai/node-pool=cpu label"
  fi
done
existing_runtime_handler="$("${admin[@]}" get runtimeclass nvidia \
  -o jsonpath='{.handler}' 2>/dev/null || true)"
[[ -z $existing_runtime_handler || $existing_runtime_handler == nvidia ]] \
  || die "an unmanaged nvidia RuntimeClass already exists: $existing_runtime_handler"
existing_image="$("${admin[@]}" -n kube-system get daemonset nvidia-device-plugin-daemonset \
  -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
[[ -z $existing_image || $existing_image == "$expected_image" ]] \
  || die "an unmanaged NVIDIA Device Plugin image already exists: $existing_image"

"${admin[@]}" apply --dry-run=server -f "$runtime_manifest" >/dev/null
"${admin[@]}" apply --dry-run=server -f "$rendered_manifest" >/dev/null
if [[ $mode == --check ]]; then
  echo "PASS: GPU Worker artifacts, cluster identity and server dry-run passed without writes"
  exit 0
fi

exec 9>/run/lock/tss-aiplatform-gpu-worker.lock
flock -n 9 || die "another GPU Worker installation is running"
for gpu_node in "${gpu_nodes[@]}"; do
  "${admin[@]}" label node "$gpu_node" \
    tss.ai/accelerator=nvidia --overwrite >/dev/null
  if [[ $gpu_node == "$TSS_NODE_NAME" ]]; then
    "${admin[@]}" label node "$gpu_node" \
      tss.ai/gpu-schedulable=false --overwrite >/dev/null
  else
    "${admin[@]}" label node "$gpu_node" \
      tss.ai/gpu-schedulable=true --overwrite >/dev/null
  fi
done
[[ $("${admin[@]}" get node "$TSS_PLATFORM_WORKER_NODE" \
  -o jsonpath='{.metadata.labels.tss\.ai/node-pool}') == cpu ]] \
  || die "GPU Worker CPU node-pool label changed unexpectedly"
if has_role gpu; then
  [[ $("${admin[@]}" get node "$TSS_NODE_NAME" \
    -o jsonpath='{.metadata.labels.tss\.ai/gpu-schedulable}') == false ]] \
    || die "GPU control plane must remain excluded from GPU scheduling"
fi
"${admin[@]}" apply -f "$runtime_manifest" >/dev/null
"${admin[@]}" apply -f "$rendered_manifest" >/dev/null
if ! "${admin[@]}" -n kube-system rollout status \
  daemonset/nvidia-device-plugin-daemonset --timeout=180s; then
  "${admin[@]}" -n kube-system get daemonset,pod \
    -l name=nvidia-device-plugin-ds -o wide >&2 || true
  "${admin[@]}" -n kube-system logs daemonset/nvidia-device-plugin-daemonset \
    --tail=100 >&2 || true
  die "NVIDIA Device Plugin rollout failed; resources were preserved for diagnosis"
fi

advertised=()
for gpu_node in "${gpu_nodes[@]}"; do
  gpu_count=''
  for _attempt in $(seq 1 30); do
    gpu_count="$("${admin[@]}" get node "$gpu_node" \
      -o jsonpath='{.status.allocatable.nvidia\.com/gpu}' 2>/dev/null || true)"
    [[ $gpu_count =~ ^[1-9][0-9]*$ ]] && break
    gpu_count=''
    sleep 5
  done
  [[ -n $gpu_count ]] \
    || die "GPU node did not advertise nvidia.com/gpu: ${gpu_node}; resources were preserved for diagnosis"
  advertised+=("${gpu_node}=${gpu_count}")
done
echo "PASS: GPU nodes advertise ${advertised[*]}; no training Job was submitted"
