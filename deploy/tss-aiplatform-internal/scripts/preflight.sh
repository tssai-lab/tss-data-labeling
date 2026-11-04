#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

mode=observe
if [[ ${1:-} == --config-only ]]; then
  mode=config-only
  shift
elif [[ ${1:-} == --ready ]]; then
  mode=ready
  shift
fi
load_internal_config "${1:-}"

if [[ $mode == config-only ]]; then
  echo "Configuration contract passed: node=${TSS_NODE_NAME}"
  exit 0
fi

failures=0
warnings=0
pass() { echo "PASS: $*"; }
warn() { echo "WARN: $*" >&2; warnings=$((warnings + 1)); }
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }
not_ready() {
  if [[ $mode == ready ]]; then fail "$*"; else warn "$*"; fi
}

[[ $EUID -eq 0 ]] || fail "run the host preflight with sudo"

for command_name in containerd ctr df findmnt ip kubeadm kubelet kubectl mountpoint nproc python3 ss stat swapon systemctl timedatectl; do
  if command -v "$command_name" >/dev/null 2>&1; then
    pass "command available: $command_name"
  else
    fail "required command is missing: $command_name"
  fi
done

installed_kubernetes="$(kubeadm version -o short 2>/dev/null || true)"
[[ $installed_kubernetes == "$TSS_KUBERNETES_VERSION" ]] \
  && pass "kubeadm version is pinned: $installed_kubernetes" \
  || fail "kubeadm version is $installed_kubernetes; expected $TSS_KUBERNETES_VERSION"

installed_kubelet="$(kubelet --version 2>/dev/null | awk '{print $2}')"
[[ $installed_kubelet == "$TSS_KUBERNETES_VERSION" ]] \
  && pass "kubelet version is pinned: $installed_kubelet" \
  || fail "kubelet version is $installed_kubelet; expected $TSS_KUBERNETES_VERSION"

installed_kubectl="$(kubectl version --client 2>/dev/null | awk '/Client Version:/ {print $3; exit}')"
[[ $installed_kubectl == "$TSS_KUBERNETES_VERSION" ]] \
  && pass "kubectl version is pinned: $installed_kubectl" \
  || fail "kubectl version is $installed_kubectl; expected $TSS_KUBERNETES_VERSION"

containerd_version="$(containerd --version 2>/dev/null | awk '{print $3}')"
containerd_version="${containerd_version#v}"
containerd_major="${containerd_version%%.*}"
[[ $containerd_major == "$((TSS_CONTAINERD_CONFIG_VERSION - 1))" ]] \
  && pass "containerd $containerd_version matches config v${TSS_CONTAINERD_CONFIG_VERSION}" \
  || fail "containerd $containerd_version does not match config v${TSS_CONTAINERD_CONFIG_VERSION}"

if [[ $TSS_ADDRESS_STABILITY_CONFIRMED == true ]]; then
  pass "node address stability is externally confirmed"
else
  not_ready "node address is DHCP-derived; reserve it before kubeadm init"
fi
actual_node_ips="$(ip -4 -o addr show scope global \
  | awk '{split($4, address, "/"); print address[1]}')"
if grep -Fx "$TSS_NODE_IP" <<<"$actual_node_ips" >/dev/null; then
  pass "configured node address is present on the host"
else
  fail "configured node address is absent from the host: $TSS_NODE_IP"
fi

route_cidrs="$(ip -4 route show | awk '$1 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+\/[0-9]+$/ {print $1}')"
if ! TSS_EXISTING_ROUTE_CIDRS="$route_cidrs" python3 - "$TSS_POD_CIDR" "$TSS_SERVICE_CIDR" <<'PY'
import ipaddress
import os
import sys

candidates = [ipaddress.ip_network(value, strict=False) for value in sys.argv[1:]]
if candidates[0].overlaps(candidates[1]):
    raise SystemExit(1)
existing = [
    ipaddress.ip_network(value, strict=False)
    for value in os.environ.get("TSS_EXISTING_ROUTE_CIDRS", "").splitlines()
    if value
]
raise SystemExit(any(left.overlaps(right) for left in candidates for right in existing))
PY
then
  fail "the selected Kubernetes CIDRs overlap each other or an existing host route"
else
  pass "selected Pod and Service CIDRs are absent from the host route table"
fi

if mountpoint -q "$TSS_STORAGE_MOUNT_POINT"; then
  actual_uuid="$(findmnt -rn -M "$TSS_STORAGE_MOUNT_POINT" -o UUID)"
  if [[ $actual_uuid == "$TSS_EXPECTED_STORAGE_UUID" ]]; then
    pass "verified storage filesystem is mounted"
  else
    fail "storage UUID does not match the reviewed filesystem"
  fi
else
  not_ready "storage filesystem is not mounted at $TSS_STORAGE_MOUNT_POINT"
fi

if [[ -d $TSS_PROJECT_ROOT && ! -L $TSS_PROJECT_ROOT ]]; then
  pass "project root exists and is not a symbolic link"
else
  not_ready "project root is absent or symbolic: $TSS_PROJECT_ROOT"
fi

root_free_gib="$(df -Pk / | awk 'NR == 2 {print int($4 / 1024 / 1024)}')"
(( root_free_gib >= TSS_MIN_ROOT_FREE_GIB )) \
  && pass "root filesystem reserve: ${root_free_gib}GiB" \
  || fail "root filesystem has ${root_free_gib}GiB free; require ${TSS_MIN_ROOT_FREE_GIB}GiB"

if mountpoint -q "$TSS_STORAGE_MOUNT_POINT"; then
  storage_free_gib="$(df -Pk "$TSS_STORAGE_MOUNT_POINT" | awk 'NR == 2 {print int($4 / 1024 / 1024)}')"
  (( storage_free_gib >= TSS_MIN_STORAGE_FREE_GIB )) \
    && pass "project storage reserve: ${storage_free_gib}GiB" \
    || fail "project storage has ${storage_free_gib}GiB free; require ${TSS_MIN_STORAGE_FREE_GIB}GiB"
fi

if [[ -S $TSS_CONTAINERD_SOCKET ]]; then
  if [[ $mode == ready ]] \
    && ctr --address "$TSS_CONTAINERD_SOCKET" version >/dev/null 2>&1; then
    pass "isolated project containerd is reachable"
  else
    not_ready "project containerd socket already exists; inspect ownership before continuing"
  fi
else
  if [[ $mode == ready ]]; then
    fail "isolated project containerd is not running"
  else
    pass "project containerd socket is isolated and currently absent"
  fi
fi

if systemctl is-active --quiet docker; then
  pass "existing Docker is active and remains outside project scope"
fi
if systemctl is-active --quiet containerd; then
  system_tasks="$(ctr -n moby tasks list -q 2>/dev/null | wc -l)"
  pass "system containerd is shared by existing Docker tasks: ${system_tasks}; it will not be modified"
fi

if [[ ! -e /etc/kubernetes/admin.conf && ! -e /var/lib/kubelet/config.yaml ]]; then
  pass "node is not already initialized by kubeadm"
else
  not_ready "existing kubeadm state requires explicit classification"
fi

declare -a ports=(10250)
if has_role control-plane; then
  ports+=(2379 2380 6443 10257 10259)
fi
for port in "${ports[@]}"; do
  if ss -H -lnt | awk '{print $4}' | grep -Eq ":${port}$"; then
    fail "Kubernetes port is already listening: $port"
  else
    pass "Kubernetes port is available: $port"
  fi
done

if [[ ! -e /proc/sys/net/bridge/bridge-nf-call-iptables ]]; then
  not_ready "br_netfilter is not loaded"
elif [[ $(< /proc/sys/net/bridge/bridge-nf-call-iptables) != 1 ]]; then
  not_ready "bridge-nf-call-iptables is not 1"
else
  pass "bridge netfilter is enabled"
fi
[[ $(< /proc/sys/net/ipv4/ip_forward) == 1 ]] \
  && pass "IPv4 forwarding is enabled" \
  || not_ready "IPv4 forwarding is not enabled"

[[ $(stat -fc %T /sys/fs/cgroup 2>/dev/null || true) == cgroup2fs ]] \
  && pass "cgroup v2 is active" \
  || fail "cgroup v2 is required"

if command -v timedatectl >/dev/null 2>&1 \
  && [[ $(timedatectl show -p NTPSynchronized --value 2>/dev/null || true) == yes ]]; then
  pass "system clock is synchronized"
else
  not_ready "system clock synchronization is not confirmed"
fi

if swapon --noheadings --show 2>/dev/null | grep -q .; then
  pass "host swap remains enabled; kubelet is rendered with NoSwap for Pods"
else
  pass "host swap is disabled"
fi

if has_role gpu; then
  command -v nvidia-smi >/dev/null 2>&1 \
    && nvidia-smi -L >/dev/null 2>&1 \
    && pass "NVIDIA driver reports at least one GPU" \
    || fail "NVIDIA driver or GPU is unavailable"
  command -v nvidia-container-runtime >/dev/null 2>&1 \
    && pass "NVIDIA container runtime is installed" \
    || fail "NVIDIA container runtime is missing"
fi

echo "Preflight result: mode=${mode} failures=${failures} warnings=${warnings}"
(( failures == 0 ))
