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
load_platform_secrets "$secrets_file"
[[ $(id -u) -eq 0 ]] || die "platform preparation must run as root"
require_control_plane_identity
require_space_gates
for command_name in docker flock git ss; do
  command -v "$command_name" >/dev/null || die "required command is missing: $command_name"
done
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is required"
systemctl is-active --quiet docker || die "shared Docker is not active"
[[ -d ${TSS_REPOSITORY_ROOT}/.git ]] || die "repository root is not a Git worktree"
[[ -z $(git -C "$TSS_REPOSITORY_ROOT" status --porcelain) ]] \
  || die "deployment repository must be clean"
deployment_branch=${TSS_DEPLOYMENT_BRANCH:-backend-ops}
[[ $deployment_branch == backend-ops || $deployment_branch == backend-gpu ]] \
  || die "deployment branch must be backend-ops or backend-gpu"
[[ $(git -C "$TSS_REPOSITORY_ROOT" symbolic-ref --quiet --short HEAD 2>/dev/null || true) == "$deployment_branch" ]] \
  || die "platform preparation must use the configured deployment branch"
[[ $(git -C "$TSS_REPOSITORY_ROOT" rev-parse HEAD) == \
   $(git -C "$TSS_REPOSITORY_ROOT" rev-parse "refs/remotes/origin/${deployment_branch}") ]] \
  || die "local deployment branch is not the exact recorded remote commit"
origin_url="$(git -C "$TSS_REPOSITORY_ROOT" remote get-url origin)"
[[ $origin_url =~ ^(https://github\.com/|git@github\.com:|ssh://git@ssh\.github\.com:443/)tssai-lab/TSSAIPlatform(\.git)?$ ]] \
  || die "deployment repository origin is not the reviewed private repository"
[[ -r ${TSS_PLATFORM_ROOT}/config/backend.kubeconfig ]] \
  || die "restricted backend kubeconfig is not ready"
"${script_dir}/verify-platform-images.sh" "$node_config" "$platform_config" >/dev/null

declare -A port_owners=(
  ["$TSS_BACKEND_PORT"]=tss-aiplatform-internal-backend
  ["$TSS_POSTGRES_PORT"]=tss-aiplatform-internal-postgres
  ["$TSS_REDIS_PORT"]=tss-aiplatform-internal-redis
  ["$TSS_MINIO_API_PORT"]=tss-aiplatform-internal-minio
  ["$TSS_MINIO_CONSOLE_PORT"]=tss-aiplatform-internal-minio
  ["$TSS_MLFLOW_PORT"]=tss-aiplatform-internal-mlflow
)
for port in "${!port_owners[@]}"; do
  if ss -H -ltn "sport = :$port" | grep -q .; then
    container="${port_owners[$port]}"
    [[ $(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || true) == true ]] \
      || die "platform port $port is already used by a non-project listener"
  fi
done

compose_file="${TSS_REPOSITORY_ROOT}/deploy/tss-aiplatform-internal/platform/compose.yml"
docker compose -f "$compose_file" config --quiet
if [[ $mode == --check ]]; then
  echo "PASS: empty platform Compose, images, ports, storage and credentials passed without writes"
  exit 0
fi

exec 9>/run/lock/tss-aiplatform-platform-compose.lock
flock -n 9 || die "another internal platform deployment is running"
# The locked official PostgreSQL image runs the database as 999:999. Reapply
# must preserve that ownership instead of making an initialized PGDATA unreadable.
install -d -m 0700 -o 999 -g 999 \
  "${TSS_PLATFORM_ROOT}/data/postgres"
install -d -m 0750 -o root -g root \
  "${TSS_PLATFORM_ROOT}/data/minio" \
  "${TSS_PLATFORM_ROOT}/state"
install -d -m 0750 -o 999 -g 1000 \
  "${TSS_PLATFORM_ROOT}/data/redis"
install -d -m 0750 -o 10001 -g 10001 \
  "${TSS_PLATFORM_ROOT}/data/mlflow" \
  "${TSS_PLATFORM_ROOT}/logs/backend"

docker compose -f "$compose_file" up -d

release_sha="$(git -C "$TSS_REPOSITORY_ROOT" rev-parse HEAD)"
state_tmp="$(mktemp "${TSS_PLATFORM_ROOT}/state/.current-release.XXXXXX")"
trap 'rm -f "$state_tmp"' EXIT
{
  printf 'TSS_PLATFORM_ENVIRONMENT=%s\n' "$TSS_PLATFORM_ENVIRONMENT"
  printf 'TSS_INFRASTRUCTURE_SHA=%s\n' "$release_sha"
  printf 'TSS_BACKEND_IMAGE=%s\n' "$TSS_BACKEND_IMAGE"
  printf 'TSS_DEPLOYED_AT_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$state_tmp"
chmod 0644 "$state_tmp"
chown root:root "$state_tmp"
mv "$state_tmp" "${TSS_PLATFORM_ROOT}/state/current-release.env"
trap - EXIT
echo "PASS: independent empty platform containers started from locked images"
