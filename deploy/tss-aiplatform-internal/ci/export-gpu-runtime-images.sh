#!/usr/bin/env bash
set -Eeuo pipefail

internal_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="${internal_dir}/reproducible/gpu-runtime-images.lock"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f $lock_file ]] || die "GPU runtime image lock is missing"
mapfile -t lock_lines < <(grep -Ev '^(#|$)' "$lock_file")
[[ ${#lock_lines[@]} -eq 2 || ${#lock_lines[@]} -eq 3 ]] \
  || die "GPU runtime lock must contain the two training images and optionally the GPU inference image"

declare -A seen_purposes=()
source_commit=""
for line in "${lock_lines[@]}"; do
  IFS='|' read -r source_ref manifest_digest image_id runtime_ref purpose producer_run extra <<<"$line"
  [[ -z ${extra:-} ]] || die "GPU runtime lock line has unexpected fields"
  [[ $source_ref =~ ^ghcr\.io/tssai-lab/(tss-cv-worker|tss-nlp-worker|tss-inference-worker-gpu):([0-9a-f]{40})$ ]] \
    || die "GPU runtime source must use an approved worker and full commit tag: $source_ref"
  current_commit="${BASH_REMATCH[2]}"
  [[ -z $source_commit || $source_commit == "$current_commit" ]] \
    || die "GPU runtime images must come from the same source commit"
  source_commit="$current_commit"
  [[ $manifest_digest =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "invalid source manifest digest: $source_ref"
  [[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "invalid linux/amd64 image ID: $source_ref"
  [[ $purpose == cv-gpu-training || $purpose == nlp-gpu-training || $purpose == gpu-inference ]] \
    || die "unknown GPU runtime image purpose: $purpose"
  expected_name=tss-cv-worker
  [[ $purpose == nlp-gpu-training ]] && expected_name=tss-nlp-worker
  [[ $purpose == gpu-inference ]] && expected_name=tss-inference-worker-gpu
  expected_runtime="crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/${expected_name}@${manifest_digest}"
  [[ $runtime_ref == "$expected_runtime" ]] \
    || die "GPU runtime reference must match its published manifest digest: $purpose"
  [[ -z ${seen_purposes[$purpose]:-} ]] || die "duplicate GPU runtime purpose: $purpose"
  [[ $producer_run =~ ^[1-9][0-9]*$ ]] || die "invalid producer run: $source_ref"
  seen_purposes[$purpose]=1
done
[[ ${seen_purposes[cv-gpu-training]:-} == 1 \
  && ${seen_purposes[nlp-gpu-training]:-} == 1 ]] \
  || die "GPU runtime lock must contain CV and NLP training images"
[[ ${#lock_lines[@]} -eq 2 || ${seen_purposes[gpu-inference]:-} == 1 ]] \
  || die "a three-image GPU runtime lock must contain the GPU inference image"

if [[ ${1:-} == --validate-only ]]; then
  echo "GPU runtime export contract passed: cv=1 nlp=1 inference=${seen_purposes[gpu-inference]:-0} source=${source_commit}"
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
mkdir -m 0700 -- "$output_dir"

declare -a export_refs=()
for line in "${lock_lines[@]}"; do
  IFS='|' read -r source_ref manifest_digest image_id runtime_ref purpose producer_run <<<"$line"
  immutable_ref="${source_ref}@${manifest_digest}"
  docker pull --quiet --platform linux/amd64 "$immutable_ref" >/dev/null
  actual_id="$(docker image inspect --format '{{.Id}}' "$immutable_ref")"
  actual_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$immutable_ref")"
  [[ $actual_id == "$image_id" ]] \
    || die "GPU runtime image ID differs from lock: $source_ref"
  [[ $actual_platform == linux/amd64 ]] \
    || die "GPU runtime image is not linux/amd64: $source_ref"
  docker tag "$immutable_ref" "$source_ref"
  export_refs+=("$source_ref")
done

docker image save --output "${output_dir}/gpu-runtime-amd64.tar" "${export_refs[@]}"
cp -- "$lock_file" "${output_dir}/sources.lock"
(
  cd "$output_dir"
  sha256sum gpu-runtime-amd64.tar sources.lock >gpu-runtime.sha256
  sha256sum --check --strict gpu-runtime.sha256 >/dev/null
)
for output_file in gpu-runtime-amd64.tar sources.lock gpu-runtime.sha256; do
  [[ -s ${output_dir}/${output_file} ]] || die "bundle output is empty: $output_file"
done
echo "GPU runtime bundle exported from ${#lock_lines[@]} immutable linux/amd64 images: $output_dir"
