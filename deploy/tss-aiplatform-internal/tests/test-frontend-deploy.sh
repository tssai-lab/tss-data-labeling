#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
platform_root="${repo_root}/deploy/tss-aiplatform-internal/platform"
workflow="${repo_root}/.github/workflows/tss-aiplatform-internal-validation.yml"
lock="${platform_root}/frontend-image.lock"
manifest="${platform_root}/k8s/frontend.yaml.template"
exporter="${platform_root}/scripts/export-frontend-image.sh"
normalizer="${platform_root}/scripts/normalize-image-archive.py"
deployer="${platform_root}/scripts/deploy-internal-frontend.sh"
installer="${platform_root}/scripts/install-internal-frontend-deployer.sh"
gateway="${platform_root}/scripts/internal-runner-gateway.sh"
downloader="${repo_root}/deploy/tss-aiplatform-internal/ci/download-airgap-artifact.py"

for file in "$lock" "$manifest" "$exporter" "$normalizer" "$deployer" "$installer" \
  "$gateway" "$downloader" "$workflow"; do
  [[ -f $file ]] || { echo "missing frontend deployment file: $file" >&2; exit 1; }
done
for script in "$exporter" "$deployer" "$installer" "$gateway"; do
  bash -n "$script"
done
python3 "$downloader" --self-test >/dev/null
python3 "$normalizer" --self-test >/dev/null

mapfile -t lock_lines < <(grep -Ev '^(#|$)' "$lock")
[[ ${#lock_lines[@]} -eq 1 ]]
IFS='|' read -r source_ref source_digest runtime_ref image_id fingerprint budget extra \
  <<<"${lock_lines[0]}"
[[ -z ${extra:-} ]]
[[ $source_ref =~ ^ghcr\.io/tssai-lab/tssai-frontend:([0-9a-f]{40})$ ]]
source_sha=${source_ref##*:}
[[ $source_digest =~ ^sha256:[0-9a-f]{64}$ ]]
[[ $runtime_ref == docker.io/library/tss-aiplatform-frontend:"${source_sha:0:12}" ]]
[[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]]
[[ $fingerprint =~ ^[0-9a-f]{64}$ ]]
[[ $budget =~ ^[1-9][0-9]*$ && $budget -le $((256 * 1024 * 1024)) ]]
bash "$exporter" --validate-only >/dev/null
grep -F 'normalize-image-archive.py' "$exporter" >/dev/null
grep -F 'docker image load --input' "$exporter" >/dev/null
grep -F 'normalized frontend image ID differs from lock' "$exporter" >/dev/null
grep -F 'archive_sha256=' "$exporter" >/dev/null

grep -F 'type: Recreate' "$manifest" >/dev/null
grep -F 'automountServiceAccountToken: false' "$manifest" >/dev/null
grep -F 'kubernetes.io/hostname: REPLACE_CONTROL_PLANE_NODE' "$manifest" >/dev/null
grep -F 'key: node-role.kubernetes.io/control-plane' "$manifest" >/dev/null
grep -F 'imagePullPolicy: Never' "$manifest" >/dev/null
grep -F 'hostPort: REPLACE_FRONTEND_PORT' "$manifest" >/dev/null
grep -F 'hostIP: REPLACE_CONTROL_PLANE_IP' "$manifest" >/dev/null
[[ $(grep -Fc 'http://127.0.0.1/healthz' "$manifest") -eq 2 ]]
[[ $(grep -Fc -- '- wget' "$manifest") -eq 2 ]]
grep -F 'allowPrivilegeEscalation: false' "$manifest" >/dev/null
for capability in CHOWN NET_BIND_SERVICE SETGID SETUID; do
  grep -F -- "- ${capability}" "$manifest" >/dev/null
done
grep -F 'type: RuntimeDefault' "$manifest" >/dev/null
for placeholder in REPLACE_FRONTEND_SOURCE_SHA REPLACE_FRONTEND_IMAGE \
  REPLACE_CONTROL_PLANE_NODE REPLACE_CONTROL_PLANE_IP REPLACE_BACKEND_PORT \
  REPLACE_MLFLOW_PORT REPLACE_FRONTEND_PORT; do
  grep -F "$placeholder" "$manifest" >/dev/null
done

grep -F 'require_control_plane_identity' "$deployer" >/dev/null
grep -F 'require_space_gates' "$deployer" >/dev/null
grep -F 'frontend-image.sha256' "$deployer" >/dev/null
grep -F 'staged frontend source lock differs from protected repository' "$deployer" >/dev/null
grep -F 'frontend_source_sha=${source_ref##*:}' "$deployer" >/dev/null
! grep -F 'frontend_source_sha=${BASH_REMATCH[1]}' "$deployer" >/dev/null
grep -F 'frontend imported image ID differs from lock' "$deployer" >/dev/null
grep -F 'shared runtime state changed during frontend image import' "$deployer" >/dev/null
grep -F "docker ps -q --no-trunc | sort | sha256sum" "$deployer" >/dev/null
grep -F 'apply --dry-run=client -f "$rendered_manifest"' "$deployer" >/dev/null
grep -F 'create namespace tss-platform-system --dry-run=client -o yaml' "$deployer" >/dev/null
grep -F 'apply --dry-run=server -f "$namespace_manifest"' "$deployer" >/dev/null
grep -F 'apply --dry-run=server' "$deployer" >/dev/null
grep -F 'rollout undo deployment/tss-aiplatform-frontend' "$deployer" >/dev/null
grep -F 'delete deployment/tss-aiplatform-frontend' "$deployer" >/dev/null
grep -F 'TSS_CONSECUTIVE_SUCCESS_COUNT=' "$deployer" >/dev/null
grep -F 'http://${TSS_PLATFORM_BIND_IP}:${TSS_FRONTEND_PORT}/healthz' "$deployer" >/dev/null
grep -F 'http://${TSS_PLATFORM_BIND_IP}:${TSS_FRONTEND_PORT}/v3/api-docs' "$deployer" >/dev/null
! grep -E 'docker (system|image|container|volume) prune' "$deployer" >/dev/null

grep -F 'probe-frontend)' "$gateway" >/dev/null
grep -F 'stage-frontend)' "$gateway" >/dev/null
grep -F 'deploy-frontend)' "$gateway" >/dev/null
grep -F 'ulimit -f $((256 * 1024))' "$gateway" >/dev/null
grep -F 'exec sudo -n /usr/local/sbin/tss-aiplatform-internal-deploy-frontend' \
  "$gateway" >/dev/null
grep -F 'NOPASSWD: /usr/local/sbin/tss-aiplatform-internal-deploy-frontend' \
  "$installer" >/dev/null
grep -F 'frontend port is already listening on the shared host' "$installer" >/dev/null
! grep -F 'NOPASSWD: ALL' "$installer" >/dev/null

grep -F '"frontend-image": {' "$downloader" >/dev/null
grep -F '"max_bytes": 256 * 1024**2' "$downloader" >/dev/null
grep -F -- '- export-frontend-image' "$workflow" >/dev/null
grep -F -- '- deploy-frontend' "$workflow" >/dev/null
grep -F "inputs.task == 'export-frontend-image'" "$workflow" >/dev/null
grep -F "inputs.task == 'deploy-frontend'" "$workflow" >/dev/null
grep -F -- '--profile frontend-image' "$workflow" >/dev/null
grep -F 'probe-frontend' "$workflow" >/dev/null
grep -F 'stage-frontend' "$workflow" >/dev/null
grep -F 'deploy-frontend' "$workflow" >/dev/null
grep -F -- 'tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner' \
  "$workflow" >/dev/null
grep -F -- "--mode='u+rw,go-rwx'" "$workflow" >/dev/null
grep -F 'runs-on: [self-hosted, Linux, X64, tss-aiplatform-internal, deploy]' \
  "$workflow" >/dev/null

if grep -REn 'seu4080|seu5090|/media/seu|/home/user|expected_user=user' \
  "$manifest" "$exporter" "$deployer" "$installer" "$gateway" >/dev/null; then
  echo 'frontend deployment contains a physical environment identity or path' >&2
  exit 1
fi
if grep -En 'seu4080|seu5090|/media/seu|/home/user|"user@\$\{CONTROL_PLANE_HOST\}' \
  "$workflow" >/dev/null; then
  echo 'frontend workflow contains a physical environment identity or path' >&2
  exit 1
fi

echo "PASS: C7 internal frontend deployment contract"
