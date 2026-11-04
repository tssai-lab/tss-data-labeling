#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

usage() {
  echo "usage: $0 --config-only /path/to/node.env" >&2
  echo "       $0 --check /path/to/node.env /path/to/bundles" >&2
  echo "       $0 --apply /path/to/node.env /path/to/bundles --confirm-node NODE" >&2
  exit 2
}

mode="${1:-}"
config_file="${2:-}"
bundle_dir="${3:-}"
confirmation_flag="${4:-}"
confirmation_node="${5:-}"
[[ $mode == --config-only || $mode == --check || $mode == --apply ]] || usage
[[ -n $config_file && -f $config_file ]] || usage
if [[ $mode == --config-only ]]; then
  [[ -z $bundle_dir && -z $confirmation_flag && -z $confirmation_node ]] || usage
elif [[ $mode == --check ]]; then
  [[ -n $bundle_dir && -z $confirmation_flag && -z $confirmation_node ]] || usage
else
  [[ -n $bundle_dir && $confirmation_flag == --confirm-node \
    && -n $confirmation_node ]] || usage
fi

load_internal_config "$config_file"
if [[ $mode == --config-only ]]; then
  echo "Air-gap import configuration passed: node=${TSS_NODE_NAME}"
  exit 0
fi

[[ $EUID -eq 0 ]] || die "bundle inspection and import must run as root"
for command_name in awk cmp ctr docker flock grep sha256sum systemctl wc; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done
[[ -d $bundle_dir && ! -L $bundle_dir ]] \
  || die "bundle directory is absent or symbolic"
bundle_dir="$(cd "$bundle_dir" && pwd -P)"

declare -a required_files=(
  k8s-core-amd64.tar
  calico-amd64.tar
  metrics-server-amd64.tar
  sources.lock
  airgap-common.sha256
)
if has_role gpu; then
  required_files+=(nvidia-amd64.tar airgap-gpu.sha256)
fi
for required_file in "${required_files[@]}"; do
  [[ -f ${bundle_dir}/${required_file} && ! -L ${bundle_dir}/${required_file} ]] \
    || die "required bundle file is absent or symbolic: $required_file"
done

common_names="$(awk '{print $2}' "${bundle_dir}/airgap-common.sha256")"
[[ $common_names == $'k8s-core-amd64.tar\ncalico-amd64.tar\nmetrics-server-amd64.tar\nsources.lock' ]] \
  || die "common checksum file contains unexpected paths"
(
  cd "$bundle_dir"
  sha256sum --check --strict airgap-common.sha256 >/dev/null
)
if has_role gpu; then
  [[ $(awk '{print $2}' "${bundle_dir}/airgap-gpu.sha256") == nvidia-amd64.tar ]] \
    || die "GPU checksum file contains an unexpected path"
  (
    cd "$bundle_dir"
    sha256sum --check --strict airgap-gpu.sha256 >/dev/null
  )
fi

cmp <(grep '^image ' "${internal_root}/artifacts.lock") \
  "${bundle_dir}/sources.lock" >/dev/null \
  || die "bundle sources do not match the committed artifact lock"
[[ $(grep -c '^image ' "${bundle_dir}/sources.lock") -eq 13 ]] \
  || die "bundle source lock must contain exactly 13 images"
metrics_url="https://github.com/kubernetes-sigs/metrics-server/releases/download/${TSS_METRICS_SERVER_VERSION}/components.yaml"
metrics_sha="$(awk -v url="$metrics_url" \
  '$1 == "manifest" && $2 == url {sub(/^sha256:/, "", $3); print $3}' \
  "${internal_root}/artifacts.lock")"
[[ $metrics_sha =~ ^[0-9a-f]{64}$ ]] \
  || die "Metrics Server manifest checksum is absent from the committed lock"
metrics_manifest="${internal_root}/manifests/metrics-server-components.yaml"
[[ -f $metrics_manifest && ! -L $metrics_manifest ]] \
  || die "versioned Metrics Server manifest is absent"
[[ $(sha256sum "$metrics_manifest" | awk '{print $1}') == "$metrics_sha" ]] \
  || die "Metrics Server manifest differs from the committed lock"

systemctl is-active --quiet tss-aiplatform-containerd.service \
  || die "isolated project containerd is not active"
systemctl is-active --quiet containerd \
  || die "shared system containerd is not active"
systemctl is-active --quiet docker \
  || die "shared Docker is not active"
[[ -S $TSS_CONTAINERD_SOCKET ]] \
  || die "isolated project containerd socket is absent"
bash "${script_dir}/verify-storage.sh" "$config_file" >/dev/null
system_containerd_pid="$(systemctl show containerd -p MainPID --value)"
[[ $system_containerd_pid =~ ^[1-9][0-9]*$ ]] \
  || die "shared system containerd PID is invalid"
docker_container_count="$(docker ps -q | wc -l)"

echo "PASS: bundle checksums and 13 locked sources verified"
echo "PLAN: import Kubernetes core, Calico and Metrics Server into node=${TSS_NODE_NAME}"
if has_role gpu; then
  echo "PLAN: import the NVIDIA device-plugin and DCGM Exporter images for the GPU node"
fi
if [[ $mode == --check ]]; then
  echo "Air-gap bundle check passed without image writes: node=${TSS_NODE_NAME}"
  exit 0
fi

[[ $confirmation_node == "$TSS_NODE_NAME" ]] \
  || die "confirmation node does not match the reviewed configuration"
exec 9>/run/lock/tss-aiplatform-image-import.lock
flock -n 9 || die "another project image import is already running"

ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images import \
  "${bundle_dir}/k8s-core-amd64.tar"
ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images import \
  "${bundle_dir}/calico-amd64.tar"
ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images import \
  "${bundle_dir}/metrics-server-amd64.tar"
if has_role gpu; then
  ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images import \
    "${bundle_dir}/nvidia-amd64.tar"
fi

while read -r kind reference digest; do
  [[ $kind == image && $digest =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "invalid image source line after import"
  if [[ $reference == nvcr.io/nvidia/* ]] && ! has_role gpu; then
    continue
  fi
  ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images list -q \
    | grep -Fx "$reference" >/dev/null \
    || die "expected image tag is absent after import: $reference"
done <"${bundle_dir}/sources.lock"

[[ $(systemctl show containerd -p MainPID --value) == "$system_containerd_pid" ]] \
  || die "shared system containerd PID changed during image import"
[[ $(docker ps -q | wc -l) == "$docker_container_count" ]] \
  || die "shared Docker container count changed during image import"
logger -t tss-aiplatform-images \
  "import complete node=${TSS_NODE_NAME} locked_images=13" 2>/dev/null || true
echo "Air-gap image import complete: node=${TSS_NODE_NAME}"
