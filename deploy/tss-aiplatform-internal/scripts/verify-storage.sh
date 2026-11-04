#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"
load_internal_config "${1:-}"

[[ -d $TSS_STORAGE_MOUNT_POINT ]] \
  || die "storage mount point is absent: $TSS_STORAGE_MOUNT_POINT"
[[ ! -L $TSS_STORAGE_MOUNT_POINT ]] \
  || die "storage mount point must not be a symbolic link"
mountpoint -q "$TSS_STORAGE_MOUNT_POINT" \
  || die "storage filesystem is not mounted: $TSS_STORAGE_MOUNT_POINT"

actual_uuid="$(findmnt -rn -M "$TSS_STORAGE_MOUNT_POINT" -o UUID)"
[[ $actual_uuid == "$TSS_EXPECTED_STORAGE_UUID" ]] \
  || die "storage UUID mismatch at $TSS_STORAGE_MOUNT_POINT"

[[ -d $TSS_PROJECT_ROOT && ! -L $TSS_PROJECT_ROOT ]] \
  || die "project root is absent or symbolic: $TSS_PROJECT_ROOT"

echo "Storage guard passed: mount=${TSS_STORAGE_MOUNT_POINT} project=${TSS_PROJECT_ROOT}"
