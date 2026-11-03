#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
internal_root="$(cd "${script_dir}/.." && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

usage() {
  echo "usage: $0 --node-config FILE --deployment-user USER --confirm-node NODE" >&2
  exit 2
}

[[ $# -eq 6 && $1 == --node-config && $3 == --deployment-user \
  && $5 == --confirm-node ]] || usage
node_config=$2
deployment_user=$4
confirmation_node=$6

[[ $EUID -eq 0 ]] || die "CPU runtime deployer installation must run as root"
[[ -f $node_config && ! -L $node_config ]] \
  || die "root node configuration is absent or symbolic"
node_config_mode=$(stat -c '%a' "$node_config")
[[ $(stat -c '%U:%G' "$node_config") == root:root \
  && $node_config_mode =~ ^[0-7]{3,4}$ \
  && $((8#$node_config_mode & 0022)) -eq 0 ]] \
  || die "node configuration must be root-owned and not group/world-writable"
load_internal_config "$node_config"
has_role worker || die "CPU runtime deployment capability belongs on a worker node"
[[ $confirmation_node == "$TSS_NODE_NAME" ]] \
  || die "confirmation node does not match the reviewed worker configuration"
[[ $deployment_user =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || die "runtime deployment user is invalid"
getent passwd "$deployment_user" >/dev/null || die "runtime deployment user is absent"
deployment_group=$(id -gn "$deployment_user")

install_root=/usr/local/lib/tss-aiplatform-internal
runtime_root=${install_root}/runtime-deployer
deploy_config_root=/etc/tss-aiplatform-deploy
deployment_config=${deploy_config_root}/runtime.env
stage_root=${TSS_PROJECT_ROOT}/staging/airgap
state_file=${TSS_PROJECT_ROOT}/audit/c7-cpu-runtime-deployment.env
gpu_state_file=${TSS_PROJECT_ROOT}/audit/c8-gpu-runtime-deployment.env
for path in "$install_root" "$runtime_root" "$stage_root" "$state_file" "$gpu_state_file"; do
  TSS_RUNTIME_INSTALL_PATH=$path validate_path TSS_RUNTIME_INSTALL_PATH
done
[[ -d $install_root && ! -L $install_root ]] \
  || die "project install tree is absent or symbolic"

install -d -o root -g root -m 0755 "$runtime_root/scripts" "$runtime_root/reproducible"
install -o root -g root -m 0755 \
  "${script_dir}/lib.sh" \
  "${script_dir}/verify-storage.sh" \
  "${script_dir}/import-cpu-runtime-images.sh" \
  "${script_dir}/import-gpu-runtime-images.sh" \
  "$runtime_root/scripts/"
install -o root -g root -m 0644 "${internal_root}/versions.env" "$runtime_root/versions.env"
install -o root -g root -m 0644 \
  "${internal_root}/reproducible/cpu-runtime-images.lock" \
  "$runtime_root/reproducible/cpu-runtime-images.lock"
install -o root -g root -m 0644 \
  "${internal_root}/reproducible/gpu-runtime-images.lock" \
  "$runtime_root/reproducible/gpu-runtime-images.lock"
TSS_INSTALL_ROOT_TO_HARDEN=$install_root harden_project_install_tree "$install_root"

install -d -o root -g root -m 0755 "$deploy_config_root"
deployment_pending=$(mktemp "${deploy_config_root}/.runtime.env.XXXXXX")
trap 'rm -f "$deployment_pending"' EXIT
{
  printf 'TSS_DEPLOYMENT_USER=%s\n' "$deployment_user"
  printf 'TSS_PROJECT_ROOT=%s\n' "$TSS_PROJECT_ROOT"
  printf 'TSS_NODE_CONFIG=%s\n' "$node_config"
  printf 'TSS_DEPLOY_STAGE_ROOT=%s\n' "$stage_root"
  printf 'TSS_DEPLOY_STATE_FILE=%s\n' "$state_file"
  printf 'TSS_GPU_DEPLOY_STATE_FILE=%s\n' "$gpu_state_file"
} >"$deployment_pending"
chown root:root "$deployment_pending"
chmod 0644 "$deployment_pending"
mv "$deployment_pending" "$deployment_config"
trap - EXIT

install -d -o "$deployment_user" -g "$deployment_group" -m 0700 "$stage_root"
install -d -o root -g root -m 0700 "$(dirname "$state_file")"
install -o root -g root -m 0755 "${script_dir}/deploy-cpu-runtime.sh" \
  /usr/local/sbin/tss-aiplatform-internal-deploy-cpu-runtime
install -o root -g root -m 0755 "${script_dir}/deploy-gpu-runtime.sh" \
  /usr/local/sbin/tss-aiplatform-internal-deploy-gpu-runtime

sudoers_pending=$(mktemp /etc/sudoers.d/.tss-aiplatform-runtime-deploy.XXXXXX)
trap 'rm -f "$sudoers_pending"' EXIT
{
  printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/tss-aiplatform-internal-deploy-cpu-runtime *\n' \
    "$deployment_user"
  if has_role gpu; then
    printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/tss-aiplatform-internal-deploy-gpu-runtime *\n' \
      "$deployment_user"
  fi
} >"$sudoers_pending"
chown root:root "$sudoers_pending"
chmod 0440 "$sudoers_pending"
visudo -cf "$sudoers_pending" >/dev/null
mv "$sudoers_pending" /etc/sudoers.d/tss-aiplatform-runtime-deploy
trap - EXIT

echo "PASS: root-owned runtime deployer installed for node=${TSS_NODE_NAME} user=${deployment_user}"
