#!/usr/bin/env bash
set -Eeuo pipefail

internal_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_lock="${internal_dir}/artifacts.lock"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

mapfile -t metrics_lines < <(awk '$1 == "image" && $2 ~ /^registry\.k8s\.io\/metrics-server\// {print}' "$artifact_lock")
mapfile -t core_lines < <(awk '$1 == "image" && $2 ~ /^registry\.k8s\.io\// && $2 !~ /^registry\.k8s\.io\/metrics-server\// {print}' "$artifact_lock")
mapfile -t calico_lines < <(awk '$1 == "image" && $2 ~ /^quay\.io\/calico\// {print}' "$artifact_lock")
mapfile -t nvidia_lines < <(awk '$1 == "image" && $2 ~ /^nvcr\.io\/nvidia\// {print}' "$artifact_lock")
[[ ${#core_lines[@]} -eq 7 ]] || die "expected 7 Kubernetes core images"
[[ ${#calico_lines[@]} -eq 3 ]] || die "expected 3 Calico images"
[[ ${#metrics_lines[@]} -eq 1 ]] || die "expected 1 Metrics Server image"
[[ ${#nvidia_lines[@]} -eq 2 ]] || die "expected 2 NVIDIA images"
[[ $(grep -c '^image ' "$artifact_lock") -eq 13 ]] || die "expected 13 locked images"

if [[ ${1:-} == --validate-only ]]; then
  echo "Air-gap export contract passed: core=7 calico=3 metrics-server=1 nvidia=2"
  exit 0
fi

output_dir="${1:-}"
[[ -n $output_dir && $output_dir == /* ]] \
  || die "usage: $0 --validate-only|/absolute/output/directory"
[[ ! -e $output_dir && ! -L $output_dir ]] \
  || die "output directory already exists: $output_dir"
for command_name in docker mkdir sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done
mkdir -p -- "$output_dir"

declare -a core_refs=()
declare -a calico_refs=()
declare -a metrics_refs=()
declare -a nvidia_refs=()
pull_group() {
  local group_name="$1"
  shift
  local line kind reference digest
  for line in "$@"; do
    read -r kind reference digest <<<"$line"
    [[ $kind == image && $digest =~ ^sha256:[0-9a-f]{64}$ ]] \
      || die "invalid locked image line: $line"
    [[ $reference != *:latest ]] || die "latest image is forbidden: $reference"
    docker pull --quiet --platform linux/amd64 "${reference}@${digest}" >/dev/null
    docker image inspect "${reference}@${digest}" >/dev/null
    docker tag "${reference}@${digest}" "$reference"
    case "$group_name" in
      core) core_refs+=("$reference") ;;
      calico) calico_refs+=("$reference") ;;
      metrics) metrics_refs+=("$reference") ;;
      nvidia) nvidia_refs+=("$reference") ;;
      *) die "unknown image group: $group_name" ;;
    esac
  done
}

pull_group core "${core_lines[@]}"
pull_group calico "${calico_lines[@]}"
pull_group metrics "${metrics_lines[@]}"
pull_group nvidia "${nvidia_lines[@]}"

docker image save --output "${output_dir}/k8s-core-amd64.tar" "${core_refs[@]}"
docker image save --output "${output_dir}/calico-amd64.tar" "${calico_refs[@]}"
docker image save --output "${output_dir}/metrics-server-amd64.tar" "${metrics_refs[@]}"
docker image save --output "${output_dir}/nvidia-amd64.tar" "${nvidia_refs[@]}"
grep '^image ' "$artifact_lock" >"${output_dir}/sources.lock"

(
  cd "$output_dir"
  sha256sum k8s-core-amd64.tar calico-amd64.tar \
    metrics-server-amd64.tar sources.lock \
    >airgap-common.sha256
  sha256sum nvidia-amd64.tar >airgap-gpu.sha256
)
for output_file in \
  k8s-core-amd64.tar calico-amd64.tar metrics-server-amd64.tar \
  nvidia-amd64.tar sources.lock \
  airgap-common.sha256 airgap-gpu.sha256; do
  [[ -s ${output_dir}/${output_file} ]] || die "bundle output is empty: $output_file"
done

echo "Air-gap bundles exported from 13 immutable image digests: $output_dir"
