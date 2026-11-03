#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

usage() {
  echo "usage: $0 --config-only|--check /path/to/node.env" >&2
  echo "       $0 --apply /path/to/node.env --confirm-node NODE_NAME" >&2
  exit 2
}

mode="${1:-}"
config_file="${2:-}"
confirmation_flag="${3:-}"
confirmation_node="${4:-}"
[[ $mode == --config-only || $mode == --check || $mode == --apply ]] || usage
[[ -n $config_file && -f $config_file ]] || usage
if [[ $mode == --apply ]]; then
  [[ $confirmation_flag == --confirm-node && -n $confirmation_node ]] || usage
else
  [[ -z $confirmation_flag && -z $confirmation_node ]] || usage
fi

load_internal_config "$config_file"
if [[ $mode == --config-only ]]; then
  echo "Node preparation configuration passed: node=${TSS_NODE_NAME}"
  exit 0
fi

[[ $EUID -eq 0 ]] || die "node inspection and preparation must run as root"
for command_name in \
  awk cat chmod chown containerd cp ctr docker find findmnt flock grep install ip \
  mktemp modprobe mountpoint rm sleep systemctl sysctl wc; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done

if [[ $mode == --apply ]]; then
  exec 9>/run/lock/tss-aiplatform-node-prepare.lock
  flock -n 9 || die "another node preparation is already running"
fi

actual_node_ips="$(ip -4 -o addr show scope global \
  | awk '{split($4, address, "/"); print address[1]}')"
grep -Fx "$TSS_NODE_IP" <<<"$actual_node_ips" >/dev/null \
  || die "configured node address is absent from this host: $TSS_NODE_IP"
[[ $TSS_ADDRESS_STABILITY_CONFIRMED == true ]] \
  || die "node address risk has not been explicitly accepted"

[[ -d $TSS_STORAGE_MOUNT_POINT && ! -L $TSS_STORAGE_MOUNT_POINT ]] \
  || die "storage mount point is absent or symbolic: $TSS_STORAGE_MOUNT_POINT"
mountpoint -q "$TSS_STORAGE_MOUNT_POINT" \
  || die "storage filesystem is not mounted: $TSS_STORAGE_MOUNT_POINT"
actual_storage_uuid="$(findmnt -rn -M "$TSS_STORAGE_MOUNT_POINT" -o UUID)"
[[ $actual_storage_uuid == "$TSS_EXPECTED_STORAGE_UUID" ]] \
  || die "storage UUID does not match the reviewed filesystem"
if [[ -e $TSS_PROJECT_ROOT || -L $TSS_PROJECT_ROOT ]]; then
  [[ -d $TSS_PROJECT_ROOT && ! -L $TSS_PROJECT_ROOT ]] \
    || die "existing project root is not a real directory: $TSS_PROJECT_ROOT"
fi
systemctl is-active --quiet docker \
  || die "shared Docker must be healthy before project preparation"
systemctl is-active --quiet containerd \
  || die "shared system containerd must be healthy before project preparation"

unexpected_kubernetes_state="$(find /etc/kubernetes -mindepth 1 \
  ! -path /etc/kubernetes/manifests \
  ! -path /etc/kubernetes/manifests/.kubelet-keep -print -quit 2>/dev/null || true)"
[[ -z $unexpected_kubernetes_state ]] \
  || die "existing Kubernetes state is outside the reviewed empty baseline: $unexpected_kubernetes_state"
unexpected_kubelet_state="$(find /var/lib/kubelet -mindepth 1 \
  ! -name .kubelet-keep -print -quit 2>/dev/null || true)"
[[ -z $unexpected_kubelet_state ]] \
  || die "existing kubelet state is outside the reviewed empty baseline: $unexpected_kubelet_state"
[[ ! -e /var/lib/etcd ]] || die "default etcd data already exists"
if has_role control-plane; then
  [[ ! -e $TSS_ETCD_DATA_DIR ]] || die "project etcd data directory already exists"
fi

install_root=/usr/local/lib/tss-aiplatform-internal
config_root=/etc/tss-aiplatform
unit_file=/etc/systemd/system/tss-aiplatform-containerd.service
modules_file=/etc/modules-load.d/tss-aiplatform.conf
sysctl_file=/etc/sysctl.d/99-tss-aiplatform.conf
for protected_path in \
  "$install_root" "$config_root" "$unit_file" "$modules_file" "$sysctl_file"; do
  [[ ! -e $protected_path && ! -L $protected_path ]] \
    || die "project preparation target already exists: $protected_path"
done
[[ ! -S $TSS_CONTAINERD_SOCKET ]] \
  || die "project containerd socket already exists"

system_containerd_pid="$(systemctl show containerd -p MainPID --value)"
docker_container_count="$(docker ps -q | wc -l)"
[[ $system_containerd_pid =~ ^[1-9][0-9]*$ ]] \
  || die "shared system containerd PID is invalid"

echo "PASS: empty Kubernetes baseline and project paths rechecked"
echo "PASS: shared Docker/containerd baseline recorded: containers=${docker_container_count}"
echo "PLAN: install isolated runtime files for node=${TSS_NODE_NAME}"
echo "PLAN: load bridge netfilter and project-only sysctl settings"
echo "PLAN: stop the unconfigured kubelet, then start only project containerd"
if [[ $mode == --check ]]; then
  echo "Node preparation check passed without writes: node=${TSS_NODE_NAME}"
  exit 0
fi

[[ $confirmation_node == "$TSS_NODE_NAME" ]] \
  || die "confirmation node does not match the reviewed configuration"

apply_phase=render
temporary_dir="$(mktemp -d)"
cleanup() {
  local temporary_parent="${TMPDIR:-/tmp}"
  [[ -n ${temporary_dir:-} && -d $temporary_dir \
    && $temporary_dir == "$temporary_parent"/tmp.* ]] \
    && rm -rf -- "$temporary_dir"
}
trap cleanup EXIT
on_apply_error() {
  local status=$?
  echo "ERROR: node preparation failed during phase=${apply_phase}; preserve project paths for inspection" >&2
  logger -t tss-aiplatform-node \
    "prepare failed node=${TSS_NODE_NAME} phase=${apply_phase} status=${status}" \
    2>/dev/null || true
  exit "$status"
}
trap on_apply_error ERR

bash "${script_dir}/render-containerd-config.sh" "$config_file" \
  >"${temporary_dir}/containerd.toml"
containerd --config "${temporary_dir}/containerd.toml" config dump >/dev/null
bash "${script_dir}/render-containerd-unit.sh" "$config_file" \
  >"${temporary_dir}/tss-aiplatform-containerd.service"

apply_phase=install-files
install -d -m 0755 -o root -g root "$install_root"
cp -a "${internal_root}/." "$install_root/"
TSS_INSTALL_ROOT_TO_HARDEN="$install_root" harden_project_install_tree "$install_root"
install -d -m 0700 -o root -g root "$config_root"
install -m 0600 -o root -g root "$config_file" "${config_root}/node.env"
install -m 0644 -o root -g root "${temporary_dir}/containerd.toml" \
  "${config_root}/containerd.toml"
install -m 0644 -o root -g root \
  "${temporary_dir}/tss-aiplatform-containerd.service" "$unit_file"

install -d -m 0755 -o root -g root "$TSS_PROJECT_ROOT"
install -d -m 0711 -o root -g root "$TSS_CONTAINERD_ROOT"
install -d -m 0755 -o root -g root "$TSS_KUBELET_ROOT"
if has_role control-plane; then
  install -d -m 0700 -o root -g root "$TSS_ETCD_DATA_DIR"
fi

printf 'overlay\nbr_netfilter\n' >"$modules_file"
chmod 0644 "$modules_file"
cat >"$sysctl_file" <<'EOF'
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward = 1
EOF
chmod 0644 "$sysctl_file"

apply_phase=kernel
modprobe overlay
modprobe br_netfilter
sysctl -p "$sysctl_file" >/dev/null

apply_phase=services
systemctl stop kubelet
systemctl daemon-reload
systemctl enable --now tss-aiplatform-containerd.service
for _ in {1..30}; do
  [[ -S $TSS_CONTAINERD_SOCKET ]] && break
  sleep 1
done
[[ -S $TSS_CONTAINERD_SOCKET ]] || die "project containerd socket did not appear"
ctr --address "$TSS_CONTAINERD_SOCKET" version >/dev/null
ctr --address "$TSS_CONTAINERD_SOCKET" plugins list \
  | awk '($1 ~ /cri/ || $2 ~ /cri/) && $NF == "ok" {found=1} END {exit !found}' \
  || die "project containerd CRI plugin is not healthy"

[[ $(systemctl show containerd -p MainPID --value) == "$system_containerd_pid" ]] \
  || die "shared system containerd PID changed during project preparation"
[[ $(docker ps -q | wc -l) == "$docker_container_count" ]] \
  || die "shared Docker container count changed during project preparation"
systemctl is-active --quiet docker \
  || die "shared Docker is not active after project preparation"

trap - ERR
logger -t tss-aiplatform-node \
  "prepare complete node=${TSS_NODE_NAME} socket=${TSS_CONTAINERD_SOCKET}" \
  2>/dev/null || true
echo "Node preparation complete: node=${TSS_NODE_NAME} socket=${TSS_CONTAINERD_SOCKET}"
