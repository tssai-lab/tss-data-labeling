#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

usage() {
  echo "usage: $0 --config-only /path/to/node.env" >&2
  echo "       $0 --check /path/to/node.env /path/to/bundle" >&2
  echo "       $0 --apply /path/to/node.env /path/to/bundle --confirm-node NODE" >&2
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
  [[ -n $bundle_dir && $confirmation_flag == --confirm-node && -n $confirmation_node ]] || usage
fi

load_internal_config "$config_file"
if [[ $mode == --config-only ]]; then
  echo "CPU runtime import configuration passed: node=${TSS_NODE_NAME}"
  exit 0
fi

[[ $EUID -eq 0 ]] || die "CPU runtime bundle inspection and import must run as root"
for command_name in awk cmp ctr docker flock grep python3 sha256sum systemctl wc; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done
[[ -d $bundle_dir && ! -L $bundle_dir ]] || die "bundle directory is absent or symbolic"
bundle_dir="$(cd "$bundle_dir" && pwd -P)"
for required_file in cpu-runtime-amd64.tar sources.lock cpu-runtime.sha256; do
  [[ -f ${bundle_dir}/${required_file} && ! -L ${bundle_dir}/${required_file} ]] \
    || die "required bundle file is absent or symbolic: $required_file"
done
[[ $(awk '{print $2}' "${bundle_dir}/cpu-runtime.sha256") == $'cpu-runtime-amd64.tar\nsources.lock' ]] \
  || die "CPU runtime checksum file contains unexpected paths"
(
  cd "$bundle_dir"
  sha256sum --check --strict cpu-runtime.sha256 >/dev/null
)
cmp "${internal_root}/reproducible/cpu-runtime-images.lock" "${bundle_dir}/sources.lock" >/dev/null \
  || die "CPU runtime bundle sources do not match the committed lock"
[[ $(grep -Evc '^(#|$)' "${bundle_dir}/sources.lock") -eq 3 ]] \
  || die "CPU runtime source lock must contain exactly three images"

systemctl is-active --quiet tss-aiplatform-containerd.service \
  || die "isolated project containerd is not active"
systemctl is-active --quiet containerd || die "shared system containerd is not active"
systemctl is-active --quiet docker || die "shared Docker is not active"
[[ -S $TSS_CONTAINERD_SOCKET ]] || die "isolated project containerd socket is absent"
bash "${script_dir}/verify-storage.sh" "$config_file" >/dev/null
system_containerd_pid="$(systemctl show containerd -p MainPID --value)"
[[ $system_containerd_pid =~ ^[1-9][0-9]*$ ]] || die "shared system containerd PID is invalid"
docker_container_count="$(docker ps -q | wc -l)"

echo "PASS: CPU runtime checksums and three locked image sources verified"
echo "PLAN: import CV training, NLP training and CPU inference images into node=${TSS_NODE_NAME}"
if [[ $mode == --check ]]; then
  echo "CPU runtime bundle check passed without image writes: node=${TSS_NODE_NAME}"
  exit 0
fi

[[ $confirmation_node == "$TSS_NODE_NAME" ]] \
  || die "confirmation node does not match the reviewed configuration"
exec 9>/run/lock/tss-aiplatform-image-import.lock
flock -n 9 || die "another project image import is already running"

ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images import \
  "${bundle_dir}/cpu-runtime-amd64.tar"

while IFS='|' read -r source_ref manifest_digest image_id runtime_ref purpose producer_run; do
  [[ $source_ref == ghcr.io/tssai-lab/* && $manifest_digest =~ ^sha256:[0-9a-f]{64}$ \
    && $image_id =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "invalid CPU runtime source line after import"
  image_line="$(ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images list \
    | awk -v ref="$source_ref" '$1 == ref {print}')"
  [[ -n $image_line ]] || die "expected source image tag is absent after import: $source_ref"
  actual_manifest="$(awk '{print $3}' <<<"$image_line")"
  [[ $actual_manifest =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "imported manifest is invalid: $source_ref"
  # docker image save serializes a platform image as a new local OCI manifest,
  # so its descriptor can differ from the registry manifest verified at export.
  # The immutable config digest still identifies the imported image contents.
  actual_image_id="$(
    ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io content get "$actual_manifest" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["config"]["digest"])'
  )"
  [[ $actual_image_id == "$image_id" ]] || die "imported image ID differs from lock: $source_ref"
  runtime_line="$(ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images list \
    | awk -v ref="$runtime_ref" '$1 == ref {print}')"
  if [[ -z $runtime_line \
    || $(awk '{print $3}' <<<"$runtime_line") != "$actual_manifest" ]]; then
    # A runtime alias is intentionally mutable: keep the verified immutable
    # source image and atomically point the alias at the newly locked content.
    ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images tag --force \
      "$source_ref" "$runtime_ref" >/dev/null
    runtime_line="$(ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images list \
      | awk -v ref="$runtime_ref" '$1 == ref {print}')"
  fi
  [[ -n $runtime_line ]] || die "runtime image alias is absent after import: $runtime_ref"
  [[ $(awk '{print $3}' <<<"$runtime_line") == "$actual_manifest" ]] \
    || die "runtime image alias points to unexpected content: $runtime_ref"
done < <(grep -Ev '^(#|$)' "${bundle_dir}/sources.lock")

[[ $(systemctl show containerd -p MainPID --value) == "$system_containerd_pid" ]] \
  || die "shared system containerd PID changed during CPU runtime import"
[[ $(docker ps -q | wc -l) -eq $docker_container_count ]] \
  || die "shared Docker container count changed during CPU runtime import"
logger -t tss-aiplatform-images \
  "CPU runtime import complete node=${TSS_NODE_NAME} locked_images=3" 2>/dev/null || true
echo "CPU runtime image import complete: node=${TSS_NODE_NAME}"
