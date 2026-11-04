#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [node.env]" >&2
  exit 1
fi

config_file="${1:-${TSS_NODE_CONFIG:-}}"
if [[ -n $config_file ]]; then
  if [[ ! -f $config_file ]]; then
    echo "Node configuration file does not exist: $config_file" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$config_file"
  set +a
fi

node_id="${TSS_NODE_ID:-}"
roles="${TSS_NODE_ROLES:-app}"
platform_dir="${TSS_PLATFORM_DIR:-/opt/tss-platform}"
min_cpu="${TSS_MIN_CPU_CORES:-2}"
min_memory_mb="${TSS_MIN_MEMORY_MB:-4096}"
min_free_disk_mb="${TSS_MIN_FREE_DISK_MB:-20480}"
allow_occupied_ports="${TSS_ALLOW_OCCUPIED_PORTS:-false}"

failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

pass() {
  echo "PASS: $*"
}

require_command() {
  local command_name="$1"
  if command -v "$command_name" >/dev/null 2>&1; then
    pass "command available: $command_name"
  else
    fail "required command is missing: $command_name"
  fi
}

require_positive_integer() {
  local label="$1"
  local value="$2"
  if [[ $value =~ ^[1-9][0-9]*$ ]]; then
    return
  fi
  fail "$label must be a positive integer: $value"
}

if [[ $(uname -s) != Linux ]]; then
  fail "node operating system must be Linux"
else
  pass "operating system is Linux"
fi

if [[ ! $node_id =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]]; then
  fail "TSS_NODE_ID must be a lowercase DNS label"
else
  pass "node id is valid: $node_id"
fi

if [[ ! $roles =~ ^[a-z]+(,[a-z]+)*$ ]]; then
  fail "TSS_NODE_ROLES must be a comma-separated lowercase list"
else
  pass "node roles: $roles"
fi

if [[ $platform_dir != /* || $platform_dir == / ]]; then
  fail "TSS_PLATFORM_DIR must be an absolute non-root path"
else
  pass "platform directory: $platform_dir"
fi

for command_name in awk curl df docker free gzip nginx nproc openssl sed sha256sum ss systemctl; do
  require_command "$command_name"
done

if command -v docker >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    pass "Docker Compose plugin is available"
  else
    fail "Docker Compose plugin is unavailable"
  fi
fi

require_positive_integer TSS_MIN_CPU_CORES "$min_cpu"
require_positive_integer TSS_MIN_MEMORY_MB "$min_memory_mb"
require_positive_integer TSS_MIN_FREE_DISK_MB "$min_free_disk_mb"

actual_cpu="$(nproc 2>/dev/null || echo 0)"
actual_memory_mb="$(awk '/MemTotal:/ {print int($2 / 1024)}' /proc/meminfo 2>/dev/null || echo 0)"
existing_path="$platform_dir"
while [[ ! -e $existing_path && $existing_path != / ]]; do
  existing_path="$(dirname "$existing_path")"
done
actual_free_disk_mb="$(df -Pm "$existing_path" | awk 'NR == 2 {print $4}')"

if (( actual_cpu < min_cpu )); then
  fail "CPU cores $actual_cpu are below required $min_cpu"
else
  pass "CPU cores: $actual_cpu"
fi
if (( actual_memory_mb < min_memory_mb )); then
  fail "memory ${actual_memory_mb}MB is below required ${min_memory_mb}MB"
else
  pass "memory: ${actual_memory_mb}MB"
fi
if (( actual_free_disk_mb < min_free_disk_mb )); then
  fail "free disk ${actual_free_disk_mb}MB is below required ${min_free_disk_mb}MB"
else
  pass "free disk: ${actual_free_disk_mb}MB"
fi

if [[ $allow_occupied_ports != true ]]; then
  for port_variable in TSS_BACKEND_LISTEN_PORT TSS_MLFLOW_LISTEN_PORT TSS_REDIS_LISTEN_PORT; do
    port="${!port_variable:-}"
    [[ -z $port ]] && continue
    require_positive_integer "$port_variable" "$port"
    if ss -H -lnt | awk '{print $4}' | grep -Eq ":${port}$"; then
      fail "port $port is already listening; set TSS_ALLOW_OCCUPIED_PORTS=true only for an idempotent rerun"
    else
      pass "port is available: $port"
    fi
  done
else
  pass "occupied port check is explicitly disabled for this rerun"
fi

if (( failures > 0 )); then
  echo "Node preflight failed with $failures problem(s)." >&2
  exit 1
fi

echo "Node preflight passed: node=$node_id roles=$roles"
