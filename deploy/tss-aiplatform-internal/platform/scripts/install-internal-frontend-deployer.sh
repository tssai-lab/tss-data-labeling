#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

usage() {
  echo "usage: $0 --node-config FILE --platform-config FILE --deployment-user USER --frontend-port PORT --confirm-node NODE" >&2
  exit 2
}

[[ $# -eq 10 && $1 == --node-config && $3 == --platform-config \
  && $5 == --deployment-user && $7 == --frontend-port \
  && $9 == --confirm-node ]] || usage
node_config=$2
platform_config=$4
deployment_user=$6
frontend_port=$8
confirmation_node=${10}

[[ $(id -u) -eq 0 ]] || die "frontend deployer installation must run as root"
[[ $deployment_user =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || die "frontend deployment user is invalid"
getent passwd "$deployment_user" >/dev/null || die "frontend deployment user is absent"
deployment_group=$(id -gn "$deployment_user")
TSS_REVIEWED_FRONTEND_PORT=$frontend_port validate_port TSS_REVIEWED_FRONTEND_PORT

load_platform_config "$node_config" "$platform_config"
[[ $confirmation_node == "$TSS_NODE_NAME" ]] \
  || die "confirmation node does not match the reviewed control-plane configuration"
for occupied_port in "$TSS_BACKEND_PORT" "$TSS_POSTGRES_PORT" "$TSS_MINIO_API_PORT" \
  "$TSS_MINIO_CONSOLE_PORT" "$TSS_MLFLOW_PORT"; do
  [[ $frontend_port != "$occupied_port" ]] || die "frontend port conflicts with a platform service"
done

backend_config=/etc/tss-aiplatform-deploy/backend.env
[[ -f $backend_config && ! -L $backend_config \
  && $(stat -c '%U:%G:%a' "$backend_config") == root:root:644 ]] \
  || die "installed backend deployment target configuration is unavailable"
# shellcheck disable=SC1090
source "$backend_config"
[[ $TSS_DEPLOYMENT_USER == "$deployment_user" \
  && ( $TSS_DEPLOYMENT_BRANCH == backend-ops || $TSS_DEPLOYMENT_BRANCH == backend-gpu ) \
  && $TSS_PROJECT_ROOT == "${TSS_PLATFORM_ROOT%/platform}" \
  && $TSS_PLATFORM_ROOT == "${TSS_PROJECT_ROOT}/platform" \
  && $TSS_REPOSITORY_ROOT == "$TSS_PROJECT_ROOT"/* \
  && $TSS_DEPLOY_STAGE_ROOT == "${TSS_PROJECT_ROOT}/staging/internal-deploy" ]] \
  || die "frontend target differs from the installed backend deployment boundary"

deploy_config_root=/etc/tss-aiplatform-deploy
frontend_config=${deploy_config_root}/frontend.env
frontend_state_file=${TSS_PLATFORM_ROOT}/state/c7-frontend-deployment.env
for path in "$frontend_config" "$frontend_state_file"; do
  TSS_FRONTEND_INSTALL_PATH=$path validate_path TSS_FRONTEND_INSTALL_PATH
done
command -v ss >/dev/null 2>&1 || die "socket inventory command is unavailable"
if [[ -e $frontend_config || -L $frontend_config ]]; then
  [[ -f $frontend_config && ! -L $frontend_config \
    && $(stat -c '%U:%G:%a' "$frontend_config") == root:root:644 ]] \
    || die "existing frontend deployment configuration is unsafe"
  # shellcheck disable=SC1090
  source "$frontend_config"
  [[ ${TSS_FRONTEND_PORT:-} == "$frontend_port" \
    && ${TSS_FRONTEND_REPOSITORY_ROOT:-} == "$TSS_REPOSITORY_ROOT" ]] \
    || die "existing frontend deployment target differs"
elif ss -H -ltn "sport = :${frontend_port}" | grep -q .; then
  die "frontend port is already listening on the shared host"
fi

install -d -o root -g root -m 0755 "$deploy_config_root"
pending_config=$(mktemp "${deploy_config_root}/.frontend.env.XXXXXX")
trap 'rm -f "$pending_config"' EXIT
{
  printf 'TSS_FRONTEND_DEPLOYMENT_USER=%s\n' "$deployment_user"
  printf 'TSS_FRONTEND_DEPLOYMENT_BRANCH=%s\n' "$TSS_DEPLOYMENT_BRANCH"
  printf 'TSS_FRONTEND_PROJECT_ROOT=%s\n' "$TSS_PROJECT_ROOT"
  printf 'TSS_FRONTEND_PLATFORM_ROOT=%s\n' "$TSS_PLATFORM_ROOT"
  printf 'TSS_FRONTEND_REPOSITORY_ROOT=%s\n' "$TSS_REPOSITORY_ROOT"
  printf 'TSS_FRONTEND_NODE_CONFIG=%s\n' "$node_config"
  printf 'TSS_FRONTEND_PLATFORM_CONFIG=%s\n' "$platform_config"
  printf 'TSS_FRONTEND_STAGE_ROOT=%s\n' "$TSS_DEPLOY_STAGE_ROOT"
  printf 'TSS_FRONTEND_STATE_FILE=%s\n' "$frontend_state_file"
  printf 'TSS_FRONTEND_PORT=%s\n' "$frontend_port"
} >"$pending_config"
chown root:root "$pending_config"
chmod 0644 "$pending_config"
mv "$pending_config" "$frontend_config"
trap - EXIT

install -d -o "$deployment_user" -g "$deployment_group" -m 0700 "$TSS_DEPLOY_STAGE_ROOT"
install -d -o root -g root -m 0700 "$(dirname "$frontend_state_file")"
install -o root -g root -m 0755 "${script_dir}/internal-runner-gateway.sh" \
  /usr/local/sbin/tss-aiplatform-internal-runner-gateway
install -o root -g root -m 0755 "${script_dir}/deploy-internal-frontend.sh" \
  /usr/local/sbin/tss-aiplatform-internal-deploy-frontend

sudoers_pending=$(mktemp /etc/sudoers.d/.tss-aiplatform-internal-frontend.XXXXXX)
trap 'rm -f "$sudoers_pending"' EXIT
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/tss-aiplatform-internal-deploy-frontend\n' \
  "$deployment_user" >"$sudoers_pending"
printf '%s ALL=(root) NOPASSWD: /usr/bin/cat %s\n' \
  "$deployment_user" "$frontend_state_file" >>"$sudoers_pending"
chown root:root "$sudoers_pending"
chmod 0440 "$sudoers_pending"
visudo -cf "$sudoers_pending" >/dev/null
mv "$sudoers_pending" /etc/sudoers.d/tss-aiplatform-internal-frontend-deploy
trap - EXIT

echo "PASS: restricted internal frontend deployer installed for node=${TSS_NODE_NAME} port=${frontend_port}"
