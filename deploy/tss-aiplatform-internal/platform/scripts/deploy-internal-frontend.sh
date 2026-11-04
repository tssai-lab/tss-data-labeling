#!/usr/bin/env bash
set -Eeuo pipefail
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

die() {
  echo "ERROR: $*" >&2
  exit 1
}

deployment_config=/etc/tss-aiplatform-deploy/frontend.env
[[ -f $deployment_config && ! -L $deployment_config \
  && $(stat -c '%U:%G:%a' "$deployment_config") == root:root:644 ]] \
  || die "frontend deployment target configuration metadata differs"
# shellcheck disable=SC1090
source "$deployment_config"
for name in TSS_FRONTEND_DEPLOYMENT_USER TSS_FRONTEND_DEPLOYMENT_BRANCH \
  TSS_FRONTEND_PROJECT_ROOT \
  TSS_FRONTEND_PLATFORM_ROOT TSS_FRONTEND_REPOSITORY_ROOT \
  TSS_FRONTEND_NODE_CONFIG TSS_FRONTEND_PLATFORM_CONFIG \
  TSS_FRONTEND_STAGE_ROOT TSS_FRONTEND_STATE_FILE TSS_FRONTEND_PORT; do
  [[ -n ${!name:-} ]] || die "frontend deployment target setting is empty: $name"
done
[[ $TSS_FRONTEND_DEPLOYMENT_USER =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || die "frontend deployment user is invalid"
[[ $TSS_FRONTEND_DEPLOYMENT_BRANCH == backend-ops || $TSS_FRONTEND_DEPLOYMENT_BRANCH == backend-gpu ]] \
  || die "frontend deployment branch is invalid"

repository_root=$TSS_FRONTEND_REPOSITORY_ROOT
node_config=$TSS_FRONTEND_NODE_CONFIG
platform_config=$TSS_FRONTEND_PLATFORM_CONFIG
stage_root=$TSS_FRONTEND_STAGE_ROOT
bundle_path=${stage_root}/frontend.bundle
state_file=$TSS_FRONTEND_STATE_FILE
lock_file=${repository_root}/deploy/tss-aiplatform-internal/platform/frontend-image.lock
manifest_template=${repository_root}/deploy/tss-aiplatform-internal/platform/k8s/frontend.yaml.template

[[ $(id -u) -eq 0 ]] || die "internal frontend deployment must run as root"
[[ -d $repository_root/.git && -z $(git -C "$repository_root" status --porcelain) ]] \
  || die "frontend deployment repository is absent or dirty"
[[ $(git -C "$repository_root" symbolic-ref --quiet --short HEAD 2>/dev/null || true) == "$TSS_FRONTEND_DEPLOYMENT_BRANCH" ]] \
  || die "frontend deployment repository must use the configured deployment branch"
[[ $(git -C "$repository_root" rev-parse HEAD) == $(git -C "$repository_root" rev-parse "refs/remotes/origin/${TSS_FRONTEND_DEPLOYMENT_BRANCH}") ]] \
  || die "frontend deployment repository is not the protected remote head"
[[ $(git -C "$repository_root" remote get-url origin) =~ ^(https://github\.com/|git@github\.com:|ssh://git@ssh\.github\.com:443/)tssai-lab/TSSAIPlatform(\.git)?$ ]] \
  || die "frontend deployment repository origin differs"

script_dir=${repository_root}/deploy/tss-aiplatform-internal/platform/scripts
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"
export TSS_DEPLOYMENT_BRANCH=$TSS_FRONTEND_DEPLOYMENT_BRANCH
load_platform_config "$node_config" "$platform_config"
require_control_plane_identity
require_space_gates
TSS_REVIEWED_FRONTEND_PORT=$TSS_FRONTEND_PORT validate_port TSS_REVIEWED_FRONTEND_PORT
[[ $TSS_FRONTEND_PROJECT_ROOT == "$TSS_PROJECT_ROOT" \
  && $TSS_FRONTEND_PLATFORM_ROOT == "$TSS_PLATFORM_ROOT" \
  && $stage_root == "${TSS_PROJECT_ROOT}/staging/internal-deploy" \
  && $state_file == "${TSS_PLATFORM_ROOT}/state/c7-frontend-deployment.env" ]] \
  || die "frontend deployment target crosses its reviewed project boundary"
for occupied_port in "$TSS_BACKEND_PORT" "$TSS_POSTGRES_PORT" "$TSS_MINIO_API_PORT" \
  "$TSS_MINIO_CONSOLE_PORT" "$TSS_MLFLOW_PORT"; do
  [[ $TSS_FRONTEND_PORT != "$occupied_port" ]] || die "frontend port conflicts with a platform service"
done
for command_name in awk cmp ctr curl docker flock git python3 sha256sum sort systemctl tar wc; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command is missing: $command_name"
done
[[ -x $TSS_KUBECTL_PATH && -r $TSS_ADMIN_KUBECONFIG ]] \
  || die "administrative Kubernetes client is unavailable"
[[ -f $bundle_path && ! -L $bundle_path ]] || die "frontend deployment bundle is absent or symbolic"
deployment_group=$(id -gn "$TSS_FRONTEND_DEPLOYMENT_USER" 2>/dev/null || true)
[[ -n $deployment_group \
  && $(stat -c '%U:%G:%a' "$bundle_path") == "${TSS_FRONTEND_DEPLOYMENT_USER}:${deployment_group}:600" ]] \
  || die "frontend deployment bundle metadata differs"
bundle_size=$(stat -c %s "$bundle_path")
(( bundle_size > 0 && bundle_size <= 256 * 1024 * 1024 )) \
  || die "frontend deployment bundle exceeds its safety limit"
[[ -f $lock_file && ! -L $lock_file && -f $manifest_template && ! -L $manifest_template ]] \
  || die "versioned frontend lock or manifest template is unavailable"

exec 9>/run/lock/tss-aiplatform-internal-frontend-deploy.lock
flock -n 9 || die "another internal frontend deployment is running"
work_dir=$(mktemp -d "${TSS_PLATFORM_ROOT}/state/.c7-frontend.XXXXXX")
deployment_existed=false
deployment_changed=false
deployment_succeeded=false
kube=("$TSS_KUBECTL_PATH" --kubeconfig "$TSS_ADMIN_KUBECONFIG" --request-timeout=15s)
if "${kube[@]}" -n tss-platform-system get deployment/tss-aiplatform-frontend >/dev/null 2>&1; then
  deployment_existed=true
fi

rollback() {
  local exit_code=$?
  if [[ $deployment_succeeded != true && $deployment_changed == true ]]; then
    if [[ $deployment_existed == true ]]; then
      "${kube[@]}" -n tss-platform-system rollout undo deployment/tss-aiplatform-frontend >/dev/null 2>&1 || true
      "${kube[@]}" -n tss-platform-system rollout status deployment/tss-aiplatform-frontend \
        --timeout=120s >/dev/null 2>&1 || true
    else
      "${kube[@]}" -n tss-platform-system delete deployment/tss-aiplatform-frontend \
        --ignore-not-found --wait=true >/dev/null 2>&1 || true
    fi
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
expected = {"frontend-image-amd64.tar", "frontend-image.sha256", "sources.lock"}
with tarfile.open(bundle, "r:*") as archive:
    members = archive.getmembers()
    names = [member.name for member in members]
    if len(names) != len(expected) or set(names) != expected:
        raise SystemExit("frontend deployment bundle has unexpected entries")
    if any(not member.isfile() or member.name != os.path.basename(member.name) for member in members):
        raise SystemExit("frontend deployment bundle contains an unsafe member")
    if sum(member.size for member in members) > 250 * 1024 * 1024:
        raise SystemExit("frontend deployment extracted content exceeds its safety limit")
    for member in members:
        source = archive.extractfile(member)
        if source is None:
            raise SystemExit("frontend deployment member is unreadable")
        output = os.path.join(target, member.name)
        fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with source, os.fdopen(fd, "wb") as destination:
            shutil.copyfileobj(source, destination, length=1024 * 1024)
PY
(
  cd "$work_dir"
  [[ $(awk '{print $2}' frontend-image.sha256) == $'frontend-image-amd64.tar\nsources.lock' ]] \
    || die "frontend checksum manifest differs"
  sha256sum --check --strict frontend-image.sha256 >/dev/null
)
cmp "$lock_file" "$work_dir/sources.lock" >/dev/null \
  || die "staged frontend source lock differs from protected repository"
mapfile -t lock_lines < <(grep -Ev '^(#|$)' "$lock_file")
[[ ${#lock_lines[@]} -eq 1 ]] || die "frontend image lock must contain one image"
IFS='|' read -r source_ref source_digest runtime_ref expected_id expected_fingerprint budget_bytes extra \
  <<<"${lock_lines[0]}"
[[ -z ${extra:-} \
  && $source_ref =~ ^ghcr\.io/tssai-lab/tssai-frontend:([0-9a-f]{40})$ \
  && $source_digest =~ ^sha256:[0-9a-f]{64}$ \
  && $expected_id =~ ^sha256:[0-9a-f]{64}$ \
  && $expected_fingerprint =~ ^[0-9a-f]{64}$ \
  && $budget_bytes =~ ^[1-9][0-9]*$ ]] \
  || die "frontend image lock differs"
frontend_source_sha=${source_ref##*:}
[[ $runtime_ref == docker.io/library/tss-aiplatform-frontend:"${frontend_source_sha:0:12}" ]] \
  || die "frontend runtime reference differs"

systemctl is-active --quiet tss-aiplatform-containerd.service \
  || die "isolated project containerd is not active"
[[ -S $TSS_CONTAINERD_SOCKET ]] || die "isolated project containerd socket is absent"
system_containerd_pid=$(systemctl show containerd -p MainPID --value)
shared_docker_ids=$(docker ps -q --no-trunc | sort | sha256sum | awk '{print $1}')
ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images import \
  "$work_dir/frontend-image-amd64.tar" >/dev/null
image_line=$(ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images list \
  | awk -v ref="$runtime_ref" '$1 == ref {print}')
[[ -n $image_line ]] || die "frontend runtime image is absent after import"
actual_manifest=$(awk '{print $3}' <<<"$image_line")
[[ $actual_manifest =~ ^sha256:[0-9a-f]{64}$ ]] || die "frontend local manifest is invalid"
actual_id=$(ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io content get "$actual_manifest" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["config"]["digest"])')
[[ $actual_id == "$expected_id" ]] || die "frontend imported image ID differs from lock"
[[ $(systemctl show containerd -p MainPID --value) == "$system_containerd_pid" \
  && $(docker ps -q --no-trunc | sort | sha256sum | awk '{print $1}') == "$shared_docker_ids" ]] \
  || die "shared runtime state changed during frontend image import"

rendered_manifest=${work_dir}/frontend.yaml
python3 - "$manifest_template" "$rendered_manifest" \
  "$frontend_source_sha" "$runtime_ref" "$TSS_NODE_NAME" "$TSS_PLATFORM_BIND_IP" \
  "$TSS_BACKEND_PORT" "$TSS_MLFLOW_PORT" "$TSS_FRONTEND_PORT" <<'PY'
import pathlib
import sys

source, target, source_sha, image, node, ip, backend_port, mlflow_port, frontend_port = sys.argv[1:]
replacements = {
    "REPLACE_FRONTEND_SOURCE_SHA": source_sha,
    "REPLACE_FRONTEND_IMAGE": image,
    "REPLACE_CONTROL_PLANE_NODE": node,
    "REPLACE_CONTROL_PLANE_IP": ip,
    "REPLACE_BACKEND_PORT": backend_port,
    "REPLACE_MLFLOW_PORT": mlflow_port,
    "REPLACE_FRONTEND_PORT": frontend_port,
}
text = pathlib.Path(source).read_text(encoding="utf-8")
for key, value in replacements.items():
    if text.count(key) == 0:
        raise SystemExit(f"frontend manifest placeholder is absent: {key}")
    text = text.replace(key, value)
if "REPLACE_" in text:
    raise SystemExit("frontend manifest retains an unresolved placeholder")
pathlib.Path(target).write_text(text, encoding="utf-8")
PY
namespace_manifest=${work_dir}/frontend-namespace.yaml
"${kube[@]}" apply --dry-run=client -f "$rendered_manifest" >/dev/null
"${kube[@]}" create namespace tss-platform-system --dry-run=client -o yaml \
  >"$namespace_manifest"
"${kube[@]}" apply --dry-run=server -f "$namespace_manifest" >/dev/null
"${kube[@]}" apply -f "$namespace_manifest" >/dev/null
"${kube[@]}" apply --dry-run=server -f "$rendered_manifest" >/dev/null
deployment_changed=true
"${kube[@]}" apply -f "$rendered_manifest" >/dev/null
"${kube[@]}" -n tss-platform-system rollout status deployment/tss-aiplatform-frontend \
  --timeout=180s >/dev/null
pod_json_file=${work_dir}/frontend-pods.json
"${kube[@]}" -n tss-platform-system get pods \
  -l app.kubernetes.io/name=tss-aiplatform-frontend -o json >"$pod_json_file"
python3 - "$pod_json_file" "$TSS_NODE_NAME" "$runtime_ref" <<'PY'
import json
import sys

path, node, image = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
items = payload.get("items", [])
if len(items) != 1:
    raise SystemExit("frontend deployment does not have exactly one Pod")
pod = items[0]
if pod.get("spec", {}).get("nodeName") != node:
    raise SystemExit("frontend Pod is not on the reviewed control plane")
containers = pod.get("spec", {}).get("containers", [])
if len(containers) != 1 or containers[0].get("image") != image:
    raise SystemExit("frontend Pod image differs from the locked runtime alias")
conditions = {row.get("type"): row.get("status") for row in pod.get("status", {}).get("conditions", [])}
if conditions.get("Ready") != "True":
    raise SystemExit("frontend Pod is not Ready")
PY
curl --silent --show-error --fail --max-time 10 \
  "http://${TSS_PLATFORM_BIND_IP}:${TSS_FRONTEND_PORT}/healthz" >/dev/null
curl --silent --show-error --fail --max-time 15 \
  "http://${TSS_PLATFORM_BIND_IP}:${TSS_FRONTEND_PORT}/v3/api-docs" >/dev/null

infrastructure_sha=$(git -C "$repository_root" rev-parse HEAD)
previous_count=0
if [[ -f $state_file && ! -L $state_file ]]; then
  previous_source=$(awk -F= '$1 == "TSS_FRONTEND_SOURCE_SHA" {print $2}' "$state_file")
  previous_infra=$(awk -F= '$1 == "TSS_INFRASTRUCTURE_SHA" {print $2}' "$state_file")
  previous_value=$(awk -F= '$1 == "TSS_CONSECUTIVE_SUCCESS_COUNT" {print $2}' "$state_file")
  if [[ $previous_source == "$frontend_source_sha" && $previous_infra == "$infrastructure_sha" \
    && $previous_value =~ ^[1-9][0-9]*$ ]]; then
    previous_count=$previous_value
  fi
fi
success_count=$((previous_count + 1))
state_pending=$(mktemp "${state_file}.XXXXXX")
{
  printf 'TSS_FRONTEND_SOURCE_SHA=%s\n' "$frontend_source_sha"
  printf 'TSS_INFRASTRUCTURE_SHA=%s\n' "$infrastructure_sha"
  printf 'TSS_FRONTEND_IMAGE=%s\n' "$runtime_ref"
  printf 'TSS_FRONTEND_PORT=%s\n' "$TSS_FRONTEND_PORT"
  printf 'TSS_CONSECUTIVE_SUCCESS_COUNT=%s\n' "$success_count"
  printf 'TSS_DEPLOYED_AT_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$state_pending"
chown root:root "$state_pending"
chmod 0644 "$state_pending"
mv "$state_pending" "$state_file"
rm -f "$bundle_path"
deployment_succeeded=true
trap - EXIT
rm -rf "$work_dir"
echo "PASS: internal frontend deployment verified source=${frontend_source_sha} port=${TSS_FRONTEND_PORT} consecutive=${success_count}"
