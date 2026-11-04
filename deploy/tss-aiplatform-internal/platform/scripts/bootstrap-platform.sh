#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

mode="${1:-}"
node_config="${2:-}"
platform_config="${3:-}"
confirmation_flag="${4:-}"
confirmation_node="${5:-}"
[[ $mode == --check || $mode == --apply ]] \
  || die "usage: $0 --check /path/to/node.env /path/to/platform.env; $0 --apply ... --confirm-node NODE"
load_platform_config "$node_config" "$platform_config"
require_apply_confirmation "$mode" "$confirmation_flag" "$confirmation_node"
secrets_file="${TSS_PLATFORM_ROOT}/config/platform.secrets.env"

if [[ $mode == --check ]]; then
  "${script_dir}/generate-platform-secrets.sh" --check "$node_config" "$platform_config" "$secrets_file"
  "${script_dir}/verify-platform-images.sh" "$node_config" "$platform_config"
  "${script_dir}/bootstrap-platform-kubernetes.sh" --check "$node_config" "$platform_config"
  "${script_dir}/prepare-platform-network.sh" --check "$node_config" "$platform_config"
  "${script_dir}/prepare-platform.sh" --check "$node_config" "$platform_config" "$secrets_file"
  echo "PASS: C5 empty-platform preflight completed without writes"
  exit 0
fi

[[ $(id -u) -eq 0 ]] || die "C5 platform bootstrap must run as root"
fresh=false
[[ ! -e ${TSS_PLATFORM_ROOT}/data/postgres/PG_VERSION ]] && fresh=true
smoke_needed=false
[[ ! -e ${TSS_PLATFORM_ROOT}/state/c5-api-smoke.ok ]] && smoke_needed=true
"${script_dir}/generate-platform-secrets.sh" --apply "$node_config" "$platform_config" "$secrets_file" \
  --confirm-node "$confirmation_node"
"${script_dir}/bootstrap-platform-kubernetes.sh" --apply "$node_config" "$platform_config" \
  --confirm-node "$confirmation_node"
"${script_dir}/prepare-platform-network.sh" --apply "$node_config" "$platform_config" \
  --confirm-node "$confirmation_node"
"${script_dir}/prepare-platform.sh" --apply "$node_config" "$platform_config" "$secrets_file" \
  --confirm-node "$confirmation_node"
if [[ $fresh == true ]]; then
  "${script_dir}/verify-platform.sh" --fresh "$node_config" "$platform_config" "$secrets_file"
else
  "${script_dir}/verify-platform.sh" "$node_config" "$platform_config" "$secrets_file"
fi
if [[ $smoke_needed == true ]]; then
  "${script_dir}/smoke-platform-api.sh" "$node_config" "$platform_config"
  install -m 0644 -o root -g root /dev/null "${TSS_PLATFORM_ROOT}/state/c5-api-smoke.ok"
fi
echo "PASS: C5 empty platform is online; C6 CPU business parity remains separate"
