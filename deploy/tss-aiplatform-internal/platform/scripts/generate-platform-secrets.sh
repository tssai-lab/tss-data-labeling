#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

mode="${1:-}"
node_config="${2:-}"
platform_config="${3:-}"
secrets_file="${4:-}"
confirmation_flag="${5:-}"
confirmation_node="${6:-}"
[[ $mode == --check || $mode == --apply ]] \
  || die "usage: $0 --check /path/to/node.env /path/to/platform.env /path/to/platform.secrets.env; $0 --apply ... --confirm-node NODE"
load_platform_config "$node_config" "$platform_config"
require_apply_confirmation "$mode" "$confirmation_flag" "$confirmation_node"
[[ $secrets_file == "${TSS_PLATFORM_ROOT}/config/platform.secrets.env" ]] \
  || die "platform secrets must use ${TSS_PLATFORM_ROOT}/config/platform.secrets.env"

if [[ -e $secrets_file ]]; then
  load_platform_secrets "$secrets_file"
  echo "PASS: existing platform secrets satisfy the local file contract"
  exit 0
fi
[[ $mode == --apply ]] || die "platform secrets are absent"
[[ $(id -u) -eq 0 ]] || die "--apply must run as root"
command -v openssl >/dev/null || die "openssl is required"
require_control_plane_identity

install -d -m 0700 -o root -g root "${TSS_PLATFORM_ROOT}/config"
umask 077
tmp_file="$(mktemp "${TSS_PLATFORM_ROOT}/config/.platform.secrets.XXXXXX")"
trap 'rm -f "$tmp_file"' EXIT
postgres_password="$(openssl rand -hex 32)"
minio_user="tssinternal$(openssl rand -hex 6)"
minio_password="$(openssl rand -hex 32)"
callback_token="$(openssl rand -hex 48)"
{
  printf 'TSS_POSTGRES_DB=tss\n'
  printf 'TSS_POSTGRES_USER=tss_internal\n'
  printf 'TSS_POSTGRES_PASSWORD=%s\n' "$postgres_password"
  printf 'TSS_MINIO_ROOT_USER=%s\n' "$minio_user"
  printf 'TSS_MINIO_ROOT_PASSWORD=%s\n' "$minio_password"
  printf 'TSS_INTERNAL_CALLBACK_TOKEN=%s\n' "$callback_token"
} >"$tmp_file"
chmod 0600 "$tmp_file"
chown root:root "$tmp_file"
mv "$tmp_file" "$secrets_file"
trap - EXIT
load_platform_secrets "$secrets_file"
echo "PASS: generated protected platform secrets without printing their values"
