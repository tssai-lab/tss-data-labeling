#!/usr/bin/env bash
set -Eeuo pipefail
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

deployment_config=/etc/tss-aiplatform-deploy/backend.env
origin_pattern='^(https://github\.com/|git@github\.com:|ssh://git@ssh\.github\.com:443/)tssai-lab/TSSAIPlatform(\.git)?$'

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f $deployment_config && ! -L $deployment_config ]] \
  || die "backend deployment target configuration is absent or symbolic"
[[ $(stat -c '%U:%G:%a' "$deployment_config") == root:root:644 ]] \
  || die "backend deployment target configuration metadata differs"
# This root-owned file contains paths and a local account name only, never credentials.
# shellcheck disable=SC1090
source "$deployment_config"
for name in TSS_DEPLOYMENT_USER TSS_DEPLOYMENT_BRANCH TSS_PROJECT_ROOT TSS_PLATFORM_ROOT \
  TSS_REPOSITORY_ROOT TSS_DEPLOY_STAGE_ROOT TSS_DEPLOY_STATE_FILE; do
  [[ -n ${!name:-} ]] || die "backend deployment target setting is empty: $name"
done
[[ $TSS_DEPLOYMENT_USER =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || die "backend deployment user is invalid"
[[ $TSS_DEPLOYMENT_BRANCH == backend-ops || $TSS_DEPLOYMENT_BRANCH == backend-gpu ]] \
  || die "backend deployment branch is invalid"
for path in "$TSS_PROJECT_ROOT" "$TSS_PLATFORM_ROOT" "$TSS_REPOSITORY_ROOT" \
  "$TSS_DEPLOY_STAGE_ROOT" "$TSS_DEPLOY_STATE_FILE"; do
  [[ $path =~ ^/[A-Za-z0-9._/-]+$ && $path != / && $path != *//* \
    && $path != */../* && $path != */.. ]] \
    || die "backend deployment target contains an unsafe path: $path"
done
[[ $TSS_PLATFORM_ROOT == "${TSS_PROJECT_ROOT}/platform" \
  && $TSS_REPOSITORY_ROOT == "$TSS_PROJECT_ROOT"/* \
  && $TSS_DEPLOY_STAGE_ROOT == "${TSS_PROJECT_ROOT}/staging/internal-deploy" \
  && $TSS_DEPLOY_STATE_FILE == "${TSS_PLATFORM_ROOT}/state/c7-backend-deployment.env" ]] \
  || die "backend deployment target paths cross their reviewed project boundaries"

expected_user=$TSS_DEPLOYMENT_USER
repository_root=$TSS_REPOSITORY_ROOT
stage_root=$TSS_DEPLOY_STAGE_ROOT
bundle_path=${stage_root}/backend.bundle
source_bundle_path=${stage_root}/release-source.bundle
source_bundle_ref=refs/tss-aiplatform/internal-release
state_file=$TSS_DEPLOY_STATE_FILE
frontend_config=/etc/tss-aiplatform-deploy/frontend.env
expected_group=$(id -gn "$expected_user" 2>/dev/null || true)
[[ -n $expected_group ]] || die "backend deployment group is absent"
[[ $(id -un) == "$expected_user" ]] || die "internal Runner gateway has an unexpected local user"
[[ -d $repository_root/.git && -d $stage_root && ! -L $stage_root ]] \
  || die "internal deployment paths are absent or unsafe"
[[ $(stat -c '%U:%G:%a' "$stage_root") == "${expected_user}:${expected_group}:700" ]] \
  || die "internal deployment staging metadata differs"

load_frontend_target() {
  [[ -f $frontend_config && ! -L $frontend_config \
    && $(stat -c '%U:%G:%a' "$frontend_config") == root:root:644 ]] \
    || die "frontend deployment target configuration metadata differs"
  # shellcheck disable=SC1090
  source "$frontend_config"
  for name in TSS_FRONTEND_DEPLOYMENT_USER TSS_FRONTEND_DEPLOYMENT_BRANCH \
    TSS_FRONTEND_PROJECT_ROOT \
    TSS_FRONTEND_PLATFORM_ROOT TSS_FRONTEND_REPOSITORY_ROOT \
    TSS_FRONTEND_STAGE_ROOT TSS_FRONTEND_STATE_FILE TSS_FRONTEND_PORT; do
    [[ -n ${!name:-} ]] || die "frontend deployment target setting is empty: $name"
  done
  [[ $TSS_FRONTEND_DEPLOYMENT_USER == "$expected_user" \
    && $TSS_FRONTEND_DEPLOYMENT_BRANCH == "$TSS_DEPLOYMENT_BRANCH" \
    && $TSS_FRONTEND_PROJECT_ROOT == "$TSS_PROJECT_ROOT" \
    && $TSS_FRONTEND_PLATFORM_ROOT == "$TSS_PLATFORM_ROOT" \
    && $TSS_FRONTEND_REPOSITORY_ROOT == "$repository_root" \
    && $TSS_FRONTEND_STAGE_ROOT == "$stage_root" \
    && $TSS_FRONTEND_STATE_FILE == "${TSS_PLATFORM_ROOT}/state/c7-frontend-deployment.env" \
    && $TSS_FRONTEND_PORT =~ ^[0-9]{4,5}$ \
    && $TSS_FRONTEND_PORT -ge 1024 && $TSS_FRONTEND_PORT -le 65535 ]] \
    || die "frontend deployment target crosses its reviewed project boundary"
  frontend_bundle_path=${stage_root}/frontend.bundle
  frontend_state_file=$TSS_FRONTEND_STATE_FILE
}

validate_source_bundle() {
  local bundle_file=$1 requested_sha=$2
  /usr/bin/git -C "$repository_root" bundle verify "$bundle_file" >/dev/null
  [[ $(/usr/bin/git bundle list-heads "$bundle_file" "$source_bundle_ref") == \
    "${requested_sha} ${source_bundle_ref}" ]] \
    || die "staged source bundle does not contain the requested release"
}

command_text=${SSH_ORIGINAL_COMMAND:-}
case "$command_text" in
  probe)
    state_content=$(sudo -n /usr/bin/cat "$state_file" 2>/dev/null || true)
    if [[ -n $state_content ]]; then
      grep -E '^TSS_(APPLICATION_SOURCE_SHA|INFRASTRUCTURE_SHA|BACKEND_IMAGE|CONSECUTIVE_SUCCESS_COUNT|DEPLOYED_AT_UTC)=' \
        <<<"$state_content"
    else
      echo 'TSS_C7_BACKEND_DEPLOYMENT=NOT_RECORDED'
    fi
    ;;
  stage-source\ *)
    requested_sha=${command_text#stage-source }
    [[ $requested_sha =~ ^[0-9a-f]{40}$ ]] || die "requested deployment SHA is invalid"
    [[ ! -e $source_bundle_path || ( -f $source_bundle_path && ! -L $source_bundle_path \
      && $(stat -c '%U:%G:%a' "$source_bundle_path") == "${expected_user}:${expected_group}:600" ) ]] \
      || die "pending source bundle metadata differs"
    pending=$(mktemp "${stage_root}/.release-source.bundle.XXXXXX")
    trap 'rm -f "$pending"' EXIT
    chmod 0600 "$pending"
    ulimit -f $((128 * 1024))
    dd of="$pending" bs=1M status=none
    size=$(stat -c %s "$pending")
    (( size > 0 && size <= 128 * 1024 * 1024 )) || die "source bundle size differs"
    validate_source_bundle "$pending" "$requested_sha"
    if [[ -f $source_bundle_path ]]; then
      [[ $(sha256sum "$pending" | awk '{print $1}') == $(sha256sum "$source_bundle_path" | awk '{print $1}') ]] \
        || die "a different source bundle is already pending"
      rm -f "$pending"
    else
      mv "$pending" "$source_bundle_path"
    fi
    trap - EXIT
    echo "PASS: release source bundle staged for ${requested_sha} (${size} bytes)"
    ;;
  sync\ *)
    requested_sha=${command_text#sync }
    [[ $requested_sha =~ ^[0-9a-f]{40}$ ]] || die "requested deployment SHA is invalid"
    [[ -z $(/usr/bin/git -C "$repository_root" status --porcelain) ]] \
      || die "deployment repository is not clean"
    [[ $(/usr/bin/git -C "$repository_root" symbolic-ref --quiet --short HEAD 2>/dev/null || true) == "$TSS_DEPLOYMENT_BRANCH" ]] \
      || die "deployment repository is not on the configured branch"
    origin_url=$(/usr/bin/git -C "$repository_root" remote get-url origin)
    [[ $origin_url =~ $origin_pattern ]] || die "deployment repository origin differs"
    [[ -f $source_bundle_path && ! -L $source_bundle_path \
      && $(stat -c '%U:%G:%a' "$source_bundle_path") == "${expected_user}:${expected_group}:600" ]] \
      || die "staged source bundle is absent or unsafe"
    validate_source_bundle "$source_bundle_path" "$requested_sha"
    /usr/bin/git -C "$repository_root" fetch --no-tags "$source_bundle_path" \
      "${source_bundle_ref}:refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}"
    [[ $(/usr/bin/git -C "$repository_root" rev-parse "refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}") == "$requested_sha" ]] \
      || die "requested SHA is not the current configured deployment branch head"
    /usr/bin/git -C "$repository_root" merge --ff-only "$requested_sha"
    [[ $(/usr/bin/git -C "$repository_root" rev-parse HEAD) == "$requested_sha" ]] \
      || die "deployment repository did not reach the requested SHA"
    rm -f "$source_bundle_path"
    echo "PASS: internal deployment repository synchronized to ${requested_sha}"
    ;;
  stage-backend)
    [[ ! -e $bundle_path || ( -f $bundle_path && ! -L $bundle_path \
      && $(stat -c '%U:%G:%a' "$bundle_path") == "${expected_user}:${expected_group}:600" ) ]] \
      || die "pending backend deployment bundle metadata differs"
    pending=$(mktemp "${stage_root}/.backend.bundle.XXXXXX")
    trap 'rm -f "$pending"' EXIT
    chmod 0600 "$pending"
    # ulimit -f is expressed in 1 KiB blocks by Bash on the supported hosts.
    # The reviewed backend artifact is below 768 MiB; allow only a narrow outer-tar margin.
    ulimit -f $((800 * 1024))
    dd of="$pending" bs=1M status=none
    size=$(stat -c %s "$pending")
    (( size > 0 && size <= 800 * 1024 * 1024 )) || die "backend deployment bundle size differs"
    if [[ -f $bundle_path ]]; then
      [[ $(sha256sum "$pending" | awk '{print $1}') == $(sha256sum "$bundle_path" | awk '{print $1}') ]] \
        || die "a different backend deployment bundle is already pending"
      rm -f "$pending"
    else
      mv "$pending" "$bundle_path"
    fi
    trap - EXIT
    echo "PASS: backend deployment bundle staged (${size} bytes)"
    ;;
  deploy-backend)
    exec sudo -n /usr/local/sbin/tss-aiplatform-internal-deploy-backend
    ;;
  probe-frontend)
    load_frontend_target
    state_content=$(sudo -n /usr/bin/cat "$frontend_state_file" 2>/dev/null || true)
    if [[ -n $state_content ]]; then
      grep -E '^TSS_(FRONTEND_SOURCE_SHA|INFRASTRUCTURE_SHA|FRONTEND_IMAGE|FRONTEND_PORT|CONSECUTIVE_SUCCESS_COUNT|DEPLOYED_AT_UTC)=' \
        <<<"$state_content"
    else
      echo 'TSS_C7_FRONTEND_DEPLOYMENT=NOT_RECORDED'
    fi
    ;;
  stage-frontend)
    load_frontend_target
    [[ ! -e $frontend_bundle_path || ( -f $frontend_bundle_path && ! -L $frontend_bundle_path \
      && $(stat -c '%U:%G:%a' "$frontend_bundle_path") == "${expected_user}:${expected_group}:600" ) ]] \
      || die "pending frontend deployment bundle metadata differs"
    pending=$(mktemp "${stage_root}/.frontend.bundle.XXXXXX")
    trap 'rm -f "$pending"' EXIT
    chmod 0600 "$pending"
    ulimit -f $((256 * 1024))
    dd of="$pending" bs=1M status=none
    size=$(stat -c %s "$pending")
    (( size > 0 && size <= 256 * 1024 * 1024 )) \
      || die "frontend deployment bundle size differs"
    if [[ -f $frontend_bundle_path ]]; then
      [[ $(sha256sum "$pending" | awk '{print $1}') == $(sha256sum "$frontend_bundle_path" | awk '{print $1}') ]] \
        || die "a different frontend deployment bundle is already pending"
      rm -f "$pending"
    else
      mv "$pending" "$frontend_bundle_path"
    fi
    trap - EXIT
    echo "PASS: frontend deployment bundle staged (${size} bytes)"
    ;;
  deploy-frontend)
    load_frontend_target
    exec sudo -n /usr/local/sbin/tss-aiplatform-internal-deploy-frontend
    ;;
  *)
    die "command is not permitted by the internal Runner gateway"
    ;;
esac
