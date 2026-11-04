#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

usage() {
  echo "usage: $0 --public-key FILE --node-config FILE --platform-config FILE --deployment-user USER --deployment-branch BRANCH --confirm-node NODE" >&2
  exit 2
}

[[ $# -eq 12 && $1 == --public-key && $3 == --node-config \
  && $5 == --platform-config && $7 == --deployment-user \
  && $9 == --deployment-branch && ${11} == --confirm-node ]] || usage
public_key_file=$2
node_config=$4
platform_config=$6
deployment_user=$8
deployment_branch=${10}
confirmation_node=${12}

[[ $(id -u) -eq 0 ]] || die "gateway installation must run as root"
[[ -f $public_key_file && ! -L $public_key_file ]] \
  || die "reviewed Runner public key is absent or symbolic"
public_key=$(<"$public_key_file")
[[ $public_key =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+\ tss-aiplatform-internal-deploy$ ]] \
  || die "Runner public key format or comment differs"
[[ $deployment_user =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || die "control-plane deployment user is invalid"
[[ $deployment_branch == backend-ops || $deployment_branch == backend-gpu ]] \
  || die "deployment branch must be backend-ops or backend-gpu"
user_record=$(getent passwd "$deployment_user" || true)
[[ -n $user_record ]] || die "control-plane deployment user is absent"
IFS=: read -r _ _ _ _ _ deployment_home _ <<<"$user_record"
TSS_DEPLOYMENT_HOME=$deployment_home validate_path TSS_DEPLOYMENT_HOME
deployment_group=$(id -gn "$deployment_user")
[[ -x /usr/bin/cat ]] || die "fixed state reader is absent"

load_platform_config "$node_config" "$platform_config"
[[ $confirmation_node == "$TSS_NODE_NAME" ]] \
  || die "confirmation node does not match the reviewed control-plane configuration"

install_root=/usr/local/lib/tss-aiplatform-internal
deploy_config_root=/etc/tss-aiplatform-deploy
deployment_config=${deploy_config_root}/backend.env
repository_root=$TSS_REPOSITORY_ROOT
stage_root=${TSS_PROJECT_ROOT}/staging/internal-deploy
state_file=${TSS_PLATFORM_ROOT}/state/c7-backend-deployment.env
ssh_dir=${deployment_home}/.ssh
authorized_keys=${ssh_dir}/authorized_keys
for path in "$repository_root" "$stage_root" "$state_file" "$ssh_dir" "$authorized_keys"; do
  TSS_GATEWAY_INSTALL_PATH=$path validate_path TSS_GATEWAY_INSTALL_PATH
done
[[ -d $repository_root/.git && ! -L $repository_root ]] \
  || die "reviewed deployment repository is absent or symbolic"
[[ ! -e $ssh_dir || ( -d $ssh_dir && ! -L $ssh_dir ) ]] \
  || die "deployment user SSH directory is unsafe"
[[ ! -e $authorized_keys || ( -f $authorized_keys && ! -L $authorized_keys ) ]] \
  || die "authorized_keys is not a regular file"

# Correct the original cp -a ownership leak before adding a root deployment capability.
TSS_INSTALL_ROOT_TO_HARDEN=$install_root harden_project_install_tree "$install_root"

install -d -o root -g root -m 0755 "$deploy_config_root"
deployment_pending=$(mktemp "${deploy_config_root}/.backend.env.XXXXXX")
trap 'rm -f "$deployment_pending"' EXIT
{
  printf 'TSS_DEPLOYMENT_USER=%s\n' "$deployment_user"
  printf 'TSS_DEPLOYMENT_BRANCH=%s\n' "$deployment_branch"
  printf 'TSS_PROJECT_ROOT=%s\n' "$TSS_PROJECT_ROOT"
  printf 'TSS_PLATFORM_ROOT=%s\n' "$TSS_PLATFORM_ROOT"
  printf 'TSS_REPOSITORY_ROOT=%s\n' "$repository_root"
  printf 'TSS_NODE_CONFIG=%s\n' "$node_config"
  printf 'TSS_PLATFORM_CONFIG=%s\n' "$platform_config"
  printf 'TSS_DEPLOY_STAGE_ROOT=%s\n' "$stage_root"
  printf 'TSS_DEPLOY_STATE_FILE=%s\n' "$state_file"
} >"$deployment_pending"
chown root:root "$deployment_pending"
chmod 0644 "$deployment_pending"
mv "$deployment_pending" "$deployment_config"
trap - EXIT

install -d -o "$deployment_user" -g "$deployment_group" -m 0700 "$stage_root"
install -o root -g root -m 0755 "${script_dir}/internal-runner-gateway.sh" \
  /usr/local/sbin/tss-aiplatform-internal-runner-gateway
install -o root -g root -m 0755 "${script_dir}/deploy-internal-backend.sh" \
  /usr/local/sbin/tss-aiplatform-internal-deploy-backend

sudoers_pending=$(mktemp /etc/sudoers.d/.tss-aiplatform-internal-deploy.XXXXXX)
trap 'rm -f "$sudoers_pending"' EXIT
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/tss-aiplatform-internal-deploy-backend\n' \
  "$deployment_user" >"$sudoers_pending"
printf '%s ALL=(root) NOPASSWD: /usr/bin/cat %s\n' \
  "$deployment_user" "$state_file" >>"$sudoers_pending"
chown root:root "$sudoers_pending"
chmod 0440 "$sudoers_pending"
visudo -cf "$sudoers_pending" >/dev/null
mv "$sudoers_pending" /etc/sudoers.d/tss-aiplatform-internal-deploy
trap - EXIT

install -d -o "$deployment_user" -g "$deployment_group" -m 0700 "$ssh_dir"
touch "$authorized_keys"
chown "$deployment_user:$deployment_group" "$authorized_keys"
chmod 0600 "$authorized_keys"
marker='tss-aiplatform-internal-deploy$'
pending_keys=$(mktemp "${ssh_dir}/.authorized_keys.XXXXXX")
grep -Ev "$marker" "$authorized_keys" >"$pending_keys" || true
printf 'restrict,command="/usr/local/sbin/tss-aiplatform-internal-runner-gateway" %s\n' "$public_key" \
  >>"$pending_keys"
chown "$deployment_user:$deployment_group" "$pending_keys"
chmod 0600 "$pending_keys"
mv "$pending_keys" "$authorized_keys"

echo "PASS: restricted Runner gateway installed from root-owned target configuration for node=${TSS_NODE_NAME}"
