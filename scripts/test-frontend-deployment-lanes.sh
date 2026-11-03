#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
external_workflow="${root_dir}/.github/workflows/deploy.yml"
runtime_workflow="${root_dir}/.github/workflows/frontend-runtime-image.yml"
promoter="${root_dir}/scripts/promote-internal-frontend-lock.sh"

trigger_block=$(awk '/^on:/{capture=1} /^jobs:/{capture=0} capture' "$external_workflow")
grep -F 'frontend-dev' <<<"$trigger_block" >/dev/null
grep -F 'frontend-main' <<<"$trigger_block" >/dev/null
! grep -F 'frontend-gpu' <<<"$trigger_block" >/dev/null
grep -F 'refs/heads/frontend-dev' "$external_workflow" >/dev/null
grep -F 'refs/heads/frontend-gpu' "$external_workflow" >/dev/null
grep -F 'needs.build-and-deploy.result == '\''success'\''' "$external_workflow" >/dev/null
grep -F 'git merge --no-edit "$DEPLOYED_FRONTEND_SHA"' "$external_workflow" >/dev/null
grep -F 'uses: ./.github/workflows/frontend-runtime-image.yml' "$external_workflow" >/dev/null
grep -F 'source_sha: ${{ needs.sync-tested-frontend-to-gpu.outputs.merged_sha }}' \
  "$external_workflow" >/dev/null
grep -F 'deploy_internal: true' "$external_workflow" >/dev/null
! grep -F -- '--force' "$external_workflow" >/dev/null

runtime_push_block=$(awk '/^  push:/{capture=1} /^  workflow_call:/{capture=0} capture' \
  "$runtime_workflow")
grep -F -- '- frontend-gpu' <<<"$runtime_push_block" >/dev/null
! grep -F -- '- frontend-dev' <<<"$runtime_push_block" >/dev/null
grep -F '"$GITHUB_REF" == refs/heads/frontend-gpu' "$runtime_workflow" >/dev/null
grep -F "needs.resolve-source.outputs.deploy_internal == 'true'" \
  "$runtime_workflow" >/dev/null
grep -F 'environment: tss-aiplatform-internal' "$runtime_workflow" >/dev/null
grep -F 'ref: backend-gpu' "$runtime_workflow" >/dev/null
grep -F 'refs/heads/frontend-gpu:refs/remotes/origin/frontend-gpu' \
  "$runtime_workflow" >/dev/null
grep -F 'remote_frontend_sha=' "$runtime_workflow" >/dev/null
grep -F '[[ "$RUNNER_TEMP" == "${runner_work_root}/"* ]]' "$runtime_workflow" >/dev/null
grep -F 'fetch-depth: 0' "$runtime_workflow" >/dev/null
grep -F 'source_ref=refs/tss-aiplatform/internal-release' "$runtime_workflow" >/dev/null
grep -F 'git bundle verify "$source_bundle"' "$runtime_workflow" >/dev/null
grep -F 'stage_source_with_retry() {' "$runtime_workflow" >/dev/null
grep -F '"stage-source ${INFRASTRUCTURE_SHA}" < "$source_bundle"' \
  "$runtime_workflow" >/dev/null
grep -F -- '-o ServerAliveInterval=15' "$runtime_workflow" >/dev/null
grep -F -- '-o ServerAliveCountMax=2' "$runtime_workflow" >/dev/null
grep -F 'stage-frontend' "$runtime_workflow" >/dev/null
grep -F 'deploy-frontend' "$runtime_workflow" >/dev/null
grep -F "connection throttle settle after the completed staging stream" \
  "$runtime_workflow" >/dev/null
grep -F 'sleep 10' "$runtime_workflow" >/dev/null
! grep -F 'ssh_with_retry deploy-frontend' "$runtime_workflow" >/dev/null
! grep -F 'MAIN_SERVER_' "$runtime_workflow" >/dev/null
! grep -F -- '--force' "$runtime_workflow" >/dev/null
bash -n "$promoter"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
infra_root="${workdir}/infra"
target_dir="${infra_root}/deploy/tss-aiplatform-internal/platform"
mkdir -p "$target_dir"
git -C "$infra_root" init -q
cat >"${target_dir}/frontend-image.lock" <<'EOF'
# source_ref|source_manifest_digest|runtime_ref|linux_amd64_image_id|runtime_fingerprint_sha256|conservative_budget_bytes
ghcr.io/tssai-lab/tssai-frontend:0000000000000000000000000000000000000000|sha256:0000000000000000000000000000000000000000000000000000000000000000|docker.io/library/tss-aiplatform-frontend:000000000000|sha256:0000000000000000000000000000000000000000000000000000000000000000|0000000000000000000000000000000000000000000000000000000000000000|268435456
EOF
source_sha=1111111111111111111111111111111111111111
candidate="${workdir}/candidate.lock"
cat >"$candidate" <<EOF
ghcr.io/tssai-lab/tssai-frontend:${source_sha}|sha256:2222222222222222222222222222222222222222222222222222222222222222|docker.io/library/tss-aiplatform-frontend:${source_sha:0:12}|sha256:3333333333333333333333333333333333333333333333333333333333333333|4444444444444444444444444444444444444444444444444444444444444444|268435456
EOF
bash "$promoter" "$candidate" "$infra_root" "$source_sha" >/dev/null
grep -F "ghcr.io/tssai-lab/tssai-frontend:${source_sha}" \
  "${target_dir}/frontend-image.lock" >/dev/null
cp "${target_dir}/frontend-image.lock" "${workdir}/valid.lock"
if bash "$promoter" "$candidate" "$infra_root" \
  5555555555555555555555555555555555555555 >/dev/null 2>&1; then
  echo 'Promoter accepted a candidate for the wrong source SHA.' >&2
  exit 1
fi
cmp "${target_dir}/frontend-image.lock" "${workdir}/valid.lock" >/dev/null

echo 'PASS: frontend external-to-internal deployment lanes are one-way and isolated'
