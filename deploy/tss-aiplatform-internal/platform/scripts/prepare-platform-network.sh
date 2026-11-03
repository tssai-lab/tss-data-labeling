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
[[ $(id -u) -eq 0 ]] || die "platform network preparation must run as root"
require_control_plane_identity
command -v ufw >/dev/null || die "ufw is required on the reviewed control plane"
LC_ALL=C ufw status | grep -Fx 'Status: active' >/dev/null \
  || die "UFW must already be active; this script will not change its policy"

route_to_worker="$(ip -4 route get "$TSS_PLATFORM_WORKER_IP")"
grep -F "src ${TSS_PLATFORM_BIND_IP}" <<<"$route_to_worker" >/dev/null \
  || die "reviewed control-plane address is not used to reach the worker"
control_interface="$(awk '{for (i=1;i<=NF;i++) if ($i=="dev") {print $(i+1); exit}}' <<<"$route_to_worker")"
[[ $control_interface =~ ^[A-Za-z0-9_.:-]+$ ]] || die "control-plane interface is invalid"

ports=("$TSS_BACKEND_PORT" "$TSS_MINIO_API_PORT" "$TSS_MLFLOW_PORT")
for port in "${ports[@]}"; do
  ufw --dry-run allow proto tcp from "$TSS_PLATFORM_WORKER_IP" to "$TSS_PLATFORM_BIND_IP" port "$port" \
    comment 'tss-AIplatform internal service' >/dev/null
  ufw --dry-run allow proto tcp from "$TSS_POD_CIDR" to "$TSS_PLATFORM_BIND_IP" port "$port" \
    comment 'tss-AIplatform internal pod service' >/dev/null
done

firewalld_active=false
firewalld_zone=''
if command -v firewall-cmd >/dev/null && systemctl is-active --quiet firewalld; then
  [[ $(firewall-cmd --state) == running ]] || die "firewalld command interface is unavailable"
  firewalld_zone="$(firewall-cmd --get-zone-of-interface="$control_interface")"
  [[ -n $firewalld_zone && $firewalld_zone != 'no zone' ]] \
    || firewalld_zone="$(firewall-cmd --get-default-zone)"
  firewall-cmd --zone=trusted --query-source="$TSS_POD_CIDR" >/dev/null \
    || die "C4 firewalld Pod CIDR trust is absent at runtime"
  firewall-cmd --permanent --zone=trusted --query-source="$TSS_POD_CIDR" >/dev/null \
    || die "C4 firewalld Pod CIDR trust is absent permanently"
  firewalld_active=true
fi

if [[ $mode == --check ]]; then
  echo "PASS: scoped internal platform firewall plan is valid; no rule was written"
  exit 0
fi
exec 9>/run/lock/tss-aiplatform-platform-network.lock
flock -n 9 || die "another internal platform network preparation is running"

for port in "${ports[@]}"; do
  ufw allow proto tcp from "$TSS_PLATFORM_WORKER_IP" to "$TSS_PLATFORM_BIND_IP" port "$port" \
    comment 'tss-AIplatform internal service' >/dev/null
  ufw allow proto tcp from "$TSS_POD_CIDR" to "$TSS_PLATFORM_BIND_IP" port "$port" \
    comment 'tss-AIplatform internal pod service' >/dev/null
  if [[ $firewalld_active == true ]]; then
    rule="rule family=\"ipv4\" source address=\"${TSS_PLATFORM_WORKER_IP}/32\" destination address=\"${TSS_PLATFORM_BIND_IP}/32\" port port=\"${port}\" protocol=\"tcp\" accept"
    firewall-cmd --zone="$firewalld_zone" --query-rich-rule="$rule" >/dev/null \
      || firewall-cmd --zone="$firewalld_zone" --add-rich-rule="$rule" >/dev/null
    firewall-cmd --permanent --zone="$firewalld_zone" --query-rich-rule="$rule" >/dev/null \
      || firewall-cmd --permanent --zone="$firewalld_zone" --add-rich-rule="$rule" >/dev/null
  fi
done
echo "PASS: added only the worker/Pod-to-platform TCP rules for the three internal service ports"
