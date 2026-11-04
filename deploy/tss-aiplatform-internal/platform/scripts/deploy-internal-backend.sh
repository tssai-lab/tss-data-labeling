#!/usr/bin/env bash
set -Eeuo pipefail
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

die() {
  echo "ERROR: $*" >&2
  exit 1
}

deployment_config=/etc/tss-aiplatform-deploy/backend.env
[[ -f $deployment_config && ! -L $deployment_config ]] \
  || die "backend deployment target configuration is absent or symbolic"
[[ $(stat -c '%U:%G:%a' "$deployment_config") == root:root:644 ]] \
  || die "backend deployment target configuration metadata differs"
# shellcheck disable=SC1090
source "$deployment_config"
for name in TSS_DEPLOYMENT_USER TSS_DEPLOYMENT_BRANCH TSS_PROJECT_ROOT TSS_PLATFORM_ROOT \
  TSS_REPOSITORY_ROOT TSS_NODE_CONFIG TSS_PLATFORM_CONFIG \
  TSS_DEPLOY_STAGE_ROOT TSS_DEPLOY_STATE_FILE; do
  [[ -n ${!name:-} ]] || die "backend deployment target setting is empty: $name"
done
[[ $TSS_DEPLOYMENT_USER =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || die "backend deployment user is invalid"
[[ $TSS_DEPLOYMENT_BRANCH == backend-ops || $TSS_DEPLOYMENT_BRANCH == backend-gpu ]] \
  || die "backend deployment branch is invalid"
export TSS_DEPLOYMENT_BRANCH
repository_root=$TSS_REPOSITORY_ROOT
node_config=$TSS_NODE_CONFIG
platform_config=$TSS_PLATFORM_CONFIG
secrets_file=${TSS_PLATFORM_ROOT}/config/platform.secrets.env
stage_root=$TSS_DEPLOY_STAGE_ROOT
bundle_path=${stage_root}/backend.bundle
state_file=$TSS_DEPLOY_STATE_FILE
lock_file=${repository_root}/deploy/tss-aiplatform-internal/platform/platform-images.lock
compose_file=${repository_root}/deploy/tss-aiplatform-internal/platform/compose.yml

[[ $(id -u) -eq 0 ]] || die "internal backend deployment must run as root"
[[ -d $repository_root/.git ]] || die "deployment repository is absent"
[[ -z $(/usr/bin/git -C "$repository_root" status --porcelain) ]] || die "deployment repository is not clean"
[[ $(/usr/bin/git -C "$repository_root" symbolic-ref --quiet --short HEAD 2>/dev/null || true) == "$TSS_DEPLOYMENT_BRANCH" ]] \
  || die "internal deployment must use the configured deployment branch"
[[ $(/usr/bin/git -C "$repository_root" rev-parse HEAD) == $(/usr/bin/git -C "$repository_root" rev-parse "refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}") ]] \
  || die "internal deployment repository is not the protected remote head"
origin_url=$(/usr/bin/git -C "$repository_root" remote get-url origin)
[[ $origin_url =~ ^(https://github\.com/|git@github\.com:|ssh://git@ssh\.github\.com:443/)tssai-lab/TSSAIPlatform(\.git)?$ ]] \
  || die "internal deployment repository origin differs"
script_dir=${repository_root}/deploy/tss-aiplatform-internal/platform/scripts
[[ -f $script_dir/lib-platform.sh && ! -L $script_dir/lib-platform.sh ]] \
  || die "versioned platform deployment library is absent or symbolic"
# The root-owned launcher reaches versioned helpers only after the worktree is
# clean, on the configured deployment branch and at the exact fetched remote SHA.
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"
load_platform_config "$node_config" "$platform_config"
load_platform_secrets "$secrets_file"
require_control_plane_identity
require_space_gates
[[ $TSS_PROJECT_ROOT == "${TSS_PLATFORM_ROOT%/platform}" \
  && $repository_root == "$TSS_REPOSITORY_ROOT" \
  && $stage_root == "${TSS_PROJECT_ROOT}/staging/internal-deploy" \
  && $state_file == "${TSS_PLATFORM_ROOT}/state/c7-backend-deployment.env" ]] \
  || die "backend deployment target paths differ from the root-administered platform overlay"
deployment_group=$(id -gn "$TSS_DEPLOYMENT_USER" 2>/dev/null || true)
[[ -n $deployment_group ]] || die "backend deployment group is absent"
for command_name in docker flock git python3 sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 || die "required deployment command is missing: ${command_name}"
done
[[ -f $bundle_path && ! -L $bundle_path ]] || die "backend deployment bundle is absent or symbolic"
[[ $(stat -c '%U:%G:%a' "$bundle_path") == "${TSS_DEPLOYMENT_USER}:${deployment_group}:600" ]] \
  || die "backend deployment bundle metadata differs"
bundle_size=$(stat -c %s "$bundle_path")
(( bundle_size > 0 && bundle_size <= 800 * 1024 * 1024 )) \
  || die "backend deployment bundle exceeds its safety limit"

exec 9>/run/lock/tss-aiplatform-internal-backend-deploy.lock
flock -n 9 || die "another internal backend deployment is running"

work_dir=$(mktemp -d "${TSS_PLATFORM_ROOT}/state/.c7-backend.XXXXXX")
platform_backup=${work_dir}/platform.env.before
nonbackend_before=${work_dir}/nonbackend.before
nonbackend_after=${work_dir}/nonbackend.after
configuration_changed=false
deployment_succeeded=false

capture_nonbackend() {
  local output=$1 name
  : >"$output"
  for name in tss-aiplatform-internal-postgres tss-aiplatform-internal-redis tss-aiplatform-internal-minio tss-aiplatform-internal-mlflow; do
    docker inspect --format '{{.Name}}|{{.Id}}|{{.RestartCount}}|{{.State.Status}}|{{.Config.Image}}' "$name" >>"$output"
  done
  sort -o "$output" "$output"
}

rollback() {
  local exit_code=$?
  if [[ $deployment_succeeded != true && $configuration_changed == true && -f $platform_backup ]]; then
    install -o root -g root -m 0600 "$platform_backup" "$platform_config"
    TSS_BACKEND_IMAGE="$old_image" \
      docker compose -f "$compose_file" up -d backend >/dev/null 2>&1 || true
  fi
  # 部署尝试结束后清掉暂存包，避免旧文件拦住下一次安全重试。
  rm -f "$bundle_path"
  rm -rf "$work_dir"
  exit "$exit_code"
}
trap rollback EXIT

python3 - "$bundle_path" "$work_dir" <<'PY'
import os
import shutil
import sys
import tarfile

bundle, target = sys.argv[1:]
expected = {"backend-image-amd64.tar", "backend-image.sha256", "sources.lock"}
with tarfile.open(bundle, "r:*") as archive:
    members = archive.getmembers()
    names = [member.name for member in members]
    if len(names) != len(expected) or set(names) != expected:
        raise SystemExit("backend deployment outer archive has unexpected entries")
    if any(not member.isfile() or member.name != os.path.basename(member.name) for member in members):
        raise SystemExit("backend deployment outer archive contains an unsafe member")
    if sum(member.size for member in members) > 790 * 1024 * 1024:
        raise SystemExit("backend deployment extracted content exceeds its safety limit")
    for member in members:
        source = archive.extractfile(member)
        if source is None:
            raise SystemExit("backend deployment member is unreadable")
        output = os.path.join(target, member.name)
        fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with source, os.fdopen(fd, "wb") as destination:
            shutil.copyfileobj(source, destination, length=1024 * 1024)
PY

(
  cd "$work_dir"
  [[ $(awk '{print $2}' backend-image.sha256) == $'backend-image-amd64.tar\nsources.lock' ]] \
    || die "backend deployment checksum manifest differs"
  sha256sum --check --strict backend-image.sha256
)
expected_lock_row=$(grep '^ghcr.io/tssai-lab/tssai-backend:' "$lock_file")
[[ $(grep -c '^ghcr.io/tssai-lab/tssai-backend:' "$lock_file") -eq 1 ]] \
  || die "repository backend image lock is not unique"
[[ $(<"$work_dir/sources.lock") == "$expected_lock_row" ]] \
  || die "staged backend source lock differs from protected repository"

IFS='|' read -r source_ref source_digest project_ref expected_id expected_fingerprint budget_bytes \
  <<<"$expected_lock_row"
[[ $source_ref =~ ^ghcr\.io/tssai-lab/tssai-backend:([0-9a-f]{40})$ ]] \
  || die "backend source reference is not an immutable application SHA"
application_sha=${BASH_REMATCH[1]}
[[ $source_digest =~ ^sha256:[0-9a-f]{64}$ && $expected_id =~ ^sha256:[0-9a-f]{64}$ ]] \
  || die "backend source digest or image ID differs"
[[ $project_ref =~ ^tss-aiplatform-internal/backend:[0-9a-f]{8}-[0-9a-f]{12}$ ]] \
  || die "backend project image reference differs"
[[ $expected_fingerprint =~ ^[0-9a-f]{64}$ && $budget_bytes =~ ^[1-9][0-9]*$ ]] \
  || die "backend fingerprint or budget differs"

# Existing internal installations may predate the standard Redis service. Start
# only that reviewed service before taking the non-backend snapshot; a later
# backend failure leaves the healthy Redis and its persistent data in place.
docker compose -f "$compose_file" up -d redis >/dev/null
redis_healthy=false
for _attempt in $(seq 1 24); do
  if [[ $(docker inspect -f '{{.State.Health.Status}}' tss-aiplatform-internal-redis 2>/dev/null || true) == healthy ]]; then
    redis_healthy=true
    break
  fi
  sleep 3
done
[[ $redis_healthy == true ]] || die "reviewed internal Redis did not become healthy"
capture_nonbackend "$nonbackend_before"
old_image=${TSS_BACKEND_IMAGE}
docker load --input "$work_dir/backend-image-amd64.tar" >/dev/null
actual_id=$(docker image inspect --format '{{.Id}}' "$project_ref" 2>/dev/null || true)
actual_size=$(docker image inspect --format '{{.Size}}' "$project_ref" 2>/dev/null || true)
actual_platform=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$project_ref" 2>/dev/null || true)
actual_fingerprint=$(docker image inspect "$project_ref" | python3 "${script_dir}/image-runtime-fingerprint.py")
[[ -n $actual_id && $actual_size =~ ^[0-9]+$ && $actual_size -le $budget_bytes ]] \
  || die "loaded backend image is absent or exceeds its budget"
[[ $actual_platform == linux/amd64 && $actual_fingerprint == "$expected_fingerprint" ]] \
  || die "loaded backend platform or runtime content differs"
if [[ $actual_id != "$expected_id" ]]; then
  echo "INFO: local image ID was rewritten, but locked backend runtime content matches" >&2
fi

if [[ $old_image != "$project_ref" ]]; then
  install -o root -g root -m 0600 "$platform_config" "$platform_backup"
  pending_config=$(mktemp "${platform_config}.c7.XXXXXX")
  awk -v image="$project_ref" '
    BEGIN { replaced=0 }
    /^TSS_BACKEND_IMAGE=/ { print "TSS_BACKEND_IMAGE=" image; replaced=1; next }
    { print }
    END { if (!replaced) exit 2 }
  ' "$platform_config" >"$pending_config"
  chown root:root "$pending_config"
  chmod 0600 "$pending_config"
  mv "$pending_config" "$platform_config"
  configuration_changed=true
  load_platform_config "$node_config" "$platform_config"
fi

"${script_dir}/prepare-platform.sh" --check "$node_config" "$platform_config" "$secrets_file"
"${script_dir}/prepare-platform.sh" --apply "$node_config" "$platform_config" "$secrets_file" \
  --confirm-node "$TSS_NODE_NAME"
"${script_dir}/verify-platform.sh" "$node_config" "$platform_config" "$secrets_file"
[[ $(docker inspect -f '{{.Config.Image}}' tss-aiplatform-internal-backend) == "$project_ref" ]] \
  || die "running internal backend image differs after deployment"
capture_nonbackend "$nonbackend_after"
cmp "$nonbackend_before" "$nonbackend_after" \
  || die "a non-backend platform container changed during backend deployment"

previous_count=0
if [[ -f $state_file && ! -L $state_file && $(stat -c '%U:%G:%a' "$state_file") == root:root:644 ]]; then
  previous_app=$(sed -n 's/^TSS_APPLICATION_SOURCE_SHA=//p' "$state_file")
  previous_infra=$(sed -n 's/^TSS_INFRASTRUCTURE_SHA=//p' "$state_file")
  previous_image=$(sed -n 's/^TSS_BACKEND_IMAGE=//p' "$state_file")
  previous_count=$(sed -n 's/^TSS_CONSECUTIVE_SUCCESS_COUNT=//p' "$state_file")
  [[ $previous_count =~ ^[0-9]+$ ]] || previous_count=0
  if [[ $previous_app != "$application_sha" || $previous_infra != "$(git -C "$repository_root" rev-parse HEAD)" || $previous_image != "$project_ref" ]]; then
    previous_count=0
  fi
fi
success_count=$((previous_count + 1))
state_pending=$(mktemp "${TSS_PLATFORM_ROOT}/state/.c7-backend-deployment.XXXXXX")
{
  printf 'TSS_APPLICATION_SOURCE_SHA=%s\n' "$application_sha"
  printf 'TSS_INFRASTRUCTURE_SHA=%s\n' "$(git -C "$repository_root" rev-parse HEAD)"
  printf 'TSS_BACKEND_IMAGE=%s\n' "$project_ref"
  printf 'TSS_SOURCE_MANIFEST_DIGEST=%s\n' "$source_digest"
  printf 'TSS_EXPECTED_IMAGE_ID=%s\n' "$expected_id"
  printf 'TSS_RUNTIME_FINGERPRINT_SHA256=%s\n' "$expected_fingerprint"
  printf 'TSS_CONSECUTIVE_SUCCESS_COUNT=%s\n' "$success_count"
  printf 'TSS_DEPLOYED_AT_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$state_pending"
chown root:root "$state_pending"
chmod 0644 "$state_pending"
mv "$state_pending" "$state_file"
deployment_succeeded=true
configuration_changed=false

# The accepted storage policy keeps one useful backend image locally. Delete
# only exact project backend tags with no container references, after verify.
current_project_id=$(docker image inspect --format '{{.Id}}' "$project_ref")
while IFS= read -r candidate; do
  [[ -n $candidate && $candidate != "$project_ref" ]] || continue
  [[ $candidate =~ ^tss-aiplatform-internal/backend:[0-9a-f]{8}-[0-9a-f]{12}$ ]] || continue
  candidate_id=$(docker image inspect --format '{{.Id}}' "$candidate")
  # Infrastructure-only commits can produce a new immutable tag for the exact
  # same image ID. In that case a running container also matches the old tag's
  # ancestor filter, but removing the old tag is only an untag operation. Keep
  # the reference guard for genuinely different image content.
  if [[ $candidate_id != "$current_project_id" ]]; then
    [[ -z $(docker ps -aq --filter "ancestor=${candidate}") ]] \
      || die "old project backend image is still referenced: ${candidate}"
  fi
  docker image rm "$candidate" >/dev/null
done < <(docker images --format '{{.Repository}}:{{.Tag}}' tss-aiplatform-internal/backend)

rm -f "$bundle_path"
rm -rf "$work_dir"
trap - EXIT
echo "PASS: internal backend deployment verified; consecutive identical successes=${success_count}"
