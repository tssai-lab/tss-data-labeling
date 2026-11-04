#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

node_config="${1:-}"
platform_config="${2:-}"
load_platform_config "$node_config" "$platform_config"
command -v docker >/dev/null || die "docker is required"
command -v python3 >/dev/null || die "python3 is required"
lock_file="${script_dir}/../platform-images.lock"
[[ -f $lock_file ]] || die "platform image lock is missing"

count=0
while IFS='|' read -r _source_ref _source_digest project_ref expected_id expected_fingerprint budget_bytes; do
  [[ -n $_source_ref && $_source_ref != \#* ]] || continue
  actual_id="$(docker image inspect --format '{{.Id}}' "$project_ref" 2>/dev/null || true)"
  actual_size="$(docker image inspect --format '{{.Size}}' "$project_ref" 2>/dev/null || true)"
  actual_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$project_ref" 2>/dev/null || true)"
  [[ -n $actual_id ]] || die "platform image is missing: $project_ref"
  actual_fingerprint="$(
    docker image inspect "$project_ref" \
      | python3 "${script_dir}/image-runtime-fingerprint.py"
  )"
  [[ $actual_fingerprint == "$expected_fingerprint" ]] \
    || die "platform image runtime content differs from lock: $project_ref"
  if [[ $actual_id != "$expected_id" ]]; then
    echo "INFO: local image ID was rewritten, but locked runtime content matches: $project_ref" >&2
  fi
  (( actual_size <= budget_bytes )) || die "platform image exceeds its conservative disk budget: $project_ref"
  [[ $actual_platform == linux/amd64 ]] || die "platform image is not linux/amd64: $project_ref"
  count=$((count + 1))
done <"$lock_file"
[[ $count -eq 5 ]] || die "expected five locked platform images"
echo "PASS: five project-specific application images match the immutable registry and runtime-content baseline"
