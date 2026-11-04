#!/usr/bin/env bash
set -Eeuo pipefail
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -eq 2 ]] || die "usage: $0 SOURCE_SHA EXPORT_RUN_ID"
source_sha=$1
export_run_id=$2
[[ $source_sha =~ ^[0-9a-f]{40}$ ]] || die "CPU runtime source SHA is invalid"
[[ $export_run_id =~ ^[1-9][0-9]*$ ]] || die "CPU runtime export run ID is invalid"
[[ $EUID -eq 0 ]] || die "CPU runtime deployment must run as root"

deployment_config=/etc/tss-aiplatform-deploy/runtime.env
[[ -f $deployment_config && ! -L $deployment_config ]] \
  || die "CPU runtime deployment target configuration is absent or symbolic"
[[ $(stat -c '%U:%G:%a' "$deployment_config") == root:root:644 ]] \
  || die "CPU runtime deployment target configuration metadata differs"
# shellcheck disable=SC1090
source "$deployment_config"
for name in TSS_DEPLOYMENT_USER TSS_PROJECT_ROOT TSS_NODE_CONFIG \
  TSS_DEPLOY_STAGE_ROOT TSS_DEPLOY_STATE_FILE; do
  [[ -n ${!name:-} ]] || die "CPU runtime deployment target setting is empty: $name"
done
[[ ${SUDO_USER:-} == "$TSS_DEPLOYMENT_USER" ]] \
  || die "CPU runtime deployer caller differs from the configured Runner user"
deployment_group=$(id -gn "$TSS_DEPLOYMENT_USER" 2>/dev/null || true)
[[ -n $deployment_group ]] || die "CPU runtime deployment group is absent"

runtime_root=/usr/local/lib/tss-aiplatform-internal/runtime-deployer
importer=${runtime_root}/scripts/import-cpu-runtime-images.sh
lock_file=${runtime_root}/reproducible/cpu-runtime-images.lock
[[ -x $importer && -f $lock_file && ! -L $lock_file ]] \
  || die "root-owned CPU runtime deployer files are incomplete"
[[ $(stat -c '%U:%G:%a' "$importer") == root:root:755 \
  && $(stat -c '%U:%G:%a' "$lock_file") == root:root:644 ]] \
  || die "root-owned CPU runtime deployer metadata differs"

# shellcheck source=lib.sh
source "${runtime_root}/scripts/lib.sh"
load_internal_config "$TSS_NODE_CONFIG"
has_role worker || die "CPU runtime deployment target is not a worker"
[[ $TSS_PROJECT_ROOT == "${TSS_DEPLOY_STAGE_ROOT%/staging/airgap}" \
  && $TSS_DEPLOY_STAGE_ROOT == "${TSS_PROJECT_ROOT}/staging/airgap" \
  && $TSS_DEPLOY_STATE_FILE == "${TSS_PROJECT_ROOT}/audit/c7-cpu-runtime-deployment.env" ]] \
  || die "CPU runtime deployment target paths cross their reviewed project boundaries"

bundle_dir=${TSS_DEPLOY_STAGE_ROOT}/${source_sha}-${export_run_id}
[[ -d $bundle_dir && ! -L $bundle_dir ]] || die "staged CPU runtime bundle is absent or symbolic"
bundle_owner=$(stat -c '%U:%G' "$bundle_dir")
[[ $bundle_owner == "${TSS_DEPLOYMENT_USER}:${deployment_group}" || $bundle_owner == root:root ]] \
  || die "staged CPU runtime bundle owner differs"
[[ $(stat -c '%a' "$bundle_dir") == 700 ]] || die "staged CPU runtime bundle mode differs"
[[ $(find "$bundle_dir" -mindepth 1 -maxdepth 1 -type f | wc -l) -eq 3 \
  && $(find "$bundle_dir" -mindepth 1 -maxdepth 1 ! -type f | wc -l) -eq 0 ]] \
  || die "staged CPU runtime bundle contains unexpected entries"
for file in cpu-runtime-amd64.tar cpu-runtime.sha256 sources.lock; do
  [[ -f ${bundle_dir}/${file} && ! -L ${bundle_dir}/${file} ]] \
    || die "staged CPU runtime bundle file is absent or symbolic: $file"
done

exec 9>/run/lock/tss-aiplatform-cpu-runtime-deploy.lock
flock -n 9 || die "another CPU runtime deployment is running"

# Freeze the already staged artifact before root parses or imports it. A failed
# deployment deliberately leaves it root-owned for inspection.
chown -R root:root "$bundle_dir"
chmod 0700 "$bundle_dir"
chmod 0600 "$bundle_dir"/*
"$importer" --check "$TSS_NODE_CONFIG" "$bundle_dir"
"$importer" --apply "$TSS_NODE_CONFIG" "$bundle_dir" --confirm-node "$TSS_NODE_NAME"

lock_sha=$(sha256sum "$lock_file" | awk '{print $1}')
previous_count=0
if [[ -f $TSS_DEPLOY_STATE_FILE && ! -L $TSS_DEPLOY_STATE_FILE \
  && $(stat -c '%U:%G:%a' "$TSS_DEPLOY_STATE_FILE") == root:root:644 ]]; then
  previous_lock=$(sed -n 's/^TSS_RUNTIME_LOCK_SHA256=//p' "$TSS_DEPLOY_STATE_FILE")
  previous_source=$(sed -n 's/^TSS_RUNTIME_SOURCE_SHA=//p' "$TSS_DEPLOY_STATE_FILE")
  previous_run=$(sed -n 's/^TSS_RUNTIME_EXPORT_RUN_ID=//p' "$TSS_DEPLOY_STATE_FILE")
  previous_count=$(sed -n 's/^TSS_CONSECUTIVE_SUCCESS_COUNT=//p' "$TSS_DEPLOY_STATE_FILE")
  [[ $previous_count =~ ^[0-9]+$ ]] || previous_count=0
  if [[ $previous_lock != "$lock_sha" || $previous_source != "$source_sha" \
    || $previous_run != "$export_run_id" ]]; then
    previous_count=0
  fi
fi
success_count=$((previous_count + 1))
state_pending=$(mktemp "$(dirname "$TSS_DEPLOY_STATE_FILE")/.c7-cpu-runtime.XXXXXX")
{
  printf 'TSS_RUNTIME_LOCK_SHA256=%s\n' "$lock_sha"
  printf 'TSS_RUNTIME_SOURCE_SHA=%s\n' "$source_sha"
  printf 'TSS_RUNTIME_EXPORT_RUN_ID=%s\n' "$export_run_id"
  printf 'TSS_RUNTIME_NODE_NAME=%s\n' "$TSS_NODE_NAME"
  printf 'TSS_CONSECUTIVE_SUCCESS_COUNT=%s\n' "$success_count"
  printf 'TSS_DEPLOYED_AT_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$state_pending"
chown root:root "$state_pending"
chmod 0644 "$state_pending"
mv "$state_pending" "$TSS_DEPLOY_STATE_FILE"

echo "PASS: CPU runtime deployment verified node=${TSS_NODE_NAME} consecutive=${success_count}"
