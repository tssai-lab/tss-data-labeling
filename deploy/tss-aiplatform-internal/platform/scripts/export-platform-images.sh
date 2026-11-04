#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lock_file="${script_dir}/../platform-images.lock"
[[ -f $lock_file ]] || { echo "ERROR: image lock is missing" >&2; exit 1; }

mode="${1:-export}"
[[ $# -le 1 && ( $mode == export || $mode == --validate-only || $mode == --backend-only ) ]] \
  || { echo "ERROR: usage: $0 [--validate-only|--backend-only]; export streams a Docker archive to stdout" >&2; exit 1; }

entry_count=0
while IFS='|' read -r source_ref source_digest project_ref expected_id expected_fingerprint budget_bytes; do
  [[ -n $source_ref && $source_ref != \#* ]] || continue
  [[ $source_ref != *:latest && $source_ref != *@* ]] \
    || { echo "ERROR: source image must use a non-latest tag without an inline digest: $source_ref" >&2; exit 1; }
  [[ $source_digest =~ ^sha256:[0-9a-f]{64}$ ]] \
    || { echo "ERROR: invalid source manifest digest: $source_ref" >&2; exit 1; }
  [[ $project_ref == tss-aiplatform-internal/* && $project_ref != *:latest ]] \
    || { echo "ERROR: unsafe project image alias: $project_ref" >&2; exit 1; }
  [[ $expected_id =~ ^sha256:[0-9a-f]{64}$ ]] \
    || { echo "ERROR: invalid linux/amd64 image ID: $project_ref" >&2; exit 1; }
  [[ $expected_fingerprint =~ ^[0-9a-f]{64}$ ]] \
    || { echo "ERROR: invalid runtime fingerprint: $project_ref" >&2; exit 1; }
  [[ $budget_bytes =~ ^[1-9][0-9]*$ ]] \
    || { echo "ERROR: invalid conservative image budget: $project_ref" >&2; exit 1; }
  entry_count=$((entry_count + 1))
done <"$lock_file"
[[ $entry_count -eq 5 ]] || { echo "ERROR: expected five platform images" >&2; exit 1; }

if [[ $mode == --validate-only ]]; then
  echo "PASS: five platform images have immutable registry digests and local aliases"
  exit 0
fi

command -v docker >/dev/null || { echo "ERROR: docker is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required" >&2; exit 1; }

declare -a temporary_tags=()
declare -a temporary_sources=()
cleanup() {
  local tag
  for tag in "${temporary_tags[@]}"; do
    docker image rm "$tag" >/dev/null 2>&1 || true
  done
  for tag in "${temporary_sources[@]}"; do
    docker image rm "$tag" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

declare -a project_refs=()
while IFS='|' read -r source_ref source_digest project_ref expected_id expected_fingerprint budget_bytes; do
  [[ -n $source_ref && $source_ref != \#* ]] || continue
  if [[ $mode == --backend-only && $source_ref != ghcr.io/tssai-lab/tssai-backend:* ]]; then
    continue
  fi
  immutable_ref="${source_ref}@${source_digest}"
  if ! docker image inspect "$immutable_ref" >/dev/null 2>&1; then
    docker pull --quiet --platform linux/amd64 "$immutable_ref" >/dev/null
    temporary_sources+=("$immutable_ref")
  fi
  actual_id="$(docker image inspect --format '{{.Id}}' "$immutable_ref")"
  actual_size="$(docker image inspect --format '{{.Size}}' "$immutable_ref")"
  actual_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$immutable_ref")"
  actual_fingerprint="$(
    docker image inspect "$immutable_ref" \
      | python3 "${script_dir}/image-runtime-fingerprint.py"
  )"
  # 精确 config ID 未变时直接采用更强的字节身份；仅在运行时重写 ID 时用指纹兜底。
  if [[ $actual_id != "$expected_id" ]]; then
    [[ $actual_fingerprint == "$expected_fingerprint" ]] \
      || { echo "ERROR: registry image runtime content differs from lock: $source_ref" >&2; exit 1; }
    echo "INFO: local image ID was rewritten, but locked registry/runtime content matches: $source_ref" >&2
  fi
  [[ $actual_platform == linux/amd64 ]] \
    || { echo "ERROR: registry image is not linux/amd64: $source_ref" >&2; exit 1; }
  (( actual_size <= budget_bytes )) \
    || { echo "ERROR: registry image exceeds its conservative disk budget: $source_ref" >&2; exit 1; }
  if docker image inspect "$project_ref" >/dev/null 2>&1; then
    echo "ERROR: temporary project alias already exists on source host: $project_ref" >&2
    exit 1
  fi
  docker tag "$immutable_ref" "$project_ref"
  temporary_tags+=("$project_ref")
  project_refs+=("$project_ref")
done <"$lock_file"
expected_exports=5
[[ $mode == --backend-only ]] && expected_exports=1
[[ ${#project_refs[@]} -eq $expected_exports ]] \
  || { echo "ERROR: expected ${expected_exports} platform image export(s)" >&2; exit 1; }

echo "PASS: exporting ${expected_exports} registry-locked application image(s); temporary pull references and aliases will be removed" >&2
docker save "${project_refs[@]}"
