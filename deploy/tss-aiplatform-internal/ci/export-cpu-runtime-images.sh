#!/usr/bin/env bash
set -Eeuo pipefail

internal_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="${internal_dir}/reproducible/cpu-runtime-images.lock"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f $lock_file ]] || die "CPU runtime image lock is missing"
mapfile -t lock_lines < <(grep -Ev '^(#|$)' "$lock_file")
[[ ${#lock_lines[@]} -eq 3 ]] || die "CPU runtime lock must contain exactly three images"

declare -A seen_purposes=()
for line in "${lock_lines[@]}"; do
  IFS='|' read -r source_ref manifest_digest image_id runtime_ref purpose producer_run extra <<<"$line"
  [[ -z ${extra:-} ]] || die "CPU runtime lock line has unexpected fields"
  [[ $source_ref =~ ^ghcr\.io/tssai-lab/[a-z0-9-]+:[0-9a-f]{40}$ ]] \
    || die "CPU runtime source must use a full commit tag: $source_ref"
  [[ $manifest_digest =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "invalid source manifest digest: $source_ref"
  [[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "invalid linux/amd64 image ID: $source_ref"
  [[ $runtime_ref != *:latest && $runtime_ref != *@sha256:*:* ]] \
    || die "unsafe runtime image reference: $runtime_ref"
  [[ $purpose == cv-training || $purpose == nlp-training || $purpose == cpu-inference ]] \
    || die "unknown CPU runtime image purpose: $purpose"
  if [[ $purpose == cv-training ]]; then
    [[ $runtime_ref == crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-cv-worker@"${manifest_digest}" ]] \
      || die "CV runtime reference must match the published training plan digest"
  elif [[ $purpose == nlp-training ]]; then
    [[ $runtime_ref == crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-nlp-worker@"${manifest_digest}" ]] \
      || die "NLP runtime reference must match the published training plan digest"
  else
    [[ $runtime_ref == docker.io/library/tss-inference-worker:local ]] \
      || die "CPU inference runtime reference must match the backend default"
  fi
  [[ -z ${seen_purposes[$purpose]:-} ]] || die "duplicate CPU runtime purpose: $purpose"
  [[ $producer_run =~ ^[1-9][0-9]*$ ]] || die "invalid producer run: $source_ref"
  seen_purposes[$purpose]=1
done
[[ ${seen_purposes[cv-training]:-} == 1 && ${seen_purposes[nlp-training]:-} == 1 \
  && ${seen_purposes[cpu-inference]:-} == 1 ]] \
  || die "CPU runtime lock must contain CV training, NLP training and CPU inference"

if [[ ${1:-} == --validate-only ]]; then
  echo "CPU runtime export contract passed: cv-training=1 nlp-training=1 cpu-inference=1"
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
    || die "CPU runtime image ID differs from lock: $source_ref"
  [[ $actual_platform == linux/amd64 ]] \
    || die "CPU runtime image is not linux/amd64: $source_ref"
  docker tag "$immutable_ref" "$source_ref"
  export_refs+=("$source_ref")
done

docker image save --output "${output_dir}/cpu-runtime-amd64.tar" "${export_refs[@]}"
cp -- "$lock_file" "${output_dir}/sources.lock"
(
  cd "$output_dir"
  sha256sum cpu-runtime-amd64.tar sources.lock >cpu-runtime.sha256
  sha256sum --check --strict cpu-runtime.sha256 >/dev/null
)
for output_file in cpu-runtime-amd64.tar sources.lock cpu-runtime.sha256; do
  [[ -s ${output_dir}/${output_file} ]] || die "bundle output is empty: $output_file"
done
echo "CPU runtime bundle exported from three immutable linux/amd64 images: $output_dir"
