#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
backend_workflow="${repo_root}/.github/workflows/backend-ci.yml"
internal_workflow="${repo_root}/.github/workflows/backend-gpu-internal-deploy.yml"
platform_scripts="${repo_root}/deploy/tss-aiplatform-internal/platform/scripts"

for file in \
  "$backend_workflow" \
  "$internal_workflow" \
  "$platform_scripts/promote-backend-image-lock.sh" \
  "$platform_scripts/install-internal-runner-gateway.sh" \
  "$platform_scripts/install-internal-frontend-deployer.sh" \
  "$platform_scripts/internal-runner-gateway.sh" \
  "$platform_scripts/deploy-internal-backend.sh" \
  "$platform_scripts/deploy-internal-frontend.sh"; do
  [[ -f $file ]] || { echo "missing deployment-lane file: $file" >&2; exit 1; }
done

bash -n "$platform_scripts/promote-backend-image-lock.sh"
bash -n "$platform_scripts/install-internal-runner-gateway.sh"
bash -n "$platform_scripts/internal-runner-gateway.sh"
bash -n "$platform_scripts/deploy-internal-backend.sh"
bash -n "$platform_scripts/install-internal-frontend-deployer.sh"
bash -n "$platform_scripts/deploy-internal-frontend.sh"

argument_probe=$(mktemp)
trap 'rm -f "$argument_probe"' EXIT
if bash "$platform_scripts/install-internal-runner-gateway.sh" \
  --public-key /nonexistent/reviewed-key \
  --node-config /nonexistent/node.env \
  --platform-config /nonexistent/platform.env \
  --deployment-user reviewed-user \
  --deployment-branch backend-gpu \
  --confirm-node reviewed-node >"$argument_probe" 2>&1; then
  echo 'gateway installer unexpectedly accepted nonexistent reviewed inputs' >&2
  exit 1
fi
! grep -F 'usage:' "$argument_probe" >/dev/null

grep -F -- '- backend-gpu' "$backend_workflow" >/dev/null
grep -F "github.ref == 'refs/heads/backend-ops'" "$backend_workflow" >/dev/null
grep -F "github.ref == 'refs/heads/backend-gpu'" "$backend_workflow" >/dev/null
grep -F 'needs.deploy-main.result == '\''success'\''' "$backend_workflow" >/dev/null
grep -F 'git merge --no-edit "$DEPLOYED_CPU_SHA"' "$backend_workflow" >/dev/null
grep -F 'refs/heads/backend-ops:refs/remotes/origin/backend-ops' "$backend_workflow" >/dev/null
grep -F 'refs/heads/backend-gpu:refs/remotes/origin/backend-gpu' "$backend_workflow" >/dev/null
grep -F 'git push origin "HEAD:refs/heads/backend-gpu"' "$backend_workflow" >/dev/null
grep -F 'gh workflow run backend-ci.yml' "$backend_workflow" >/dev/null
grep -F "vars.INTERNAL_GPU_AUTO_DEPLOY_ENABLED == 'true'" "$backend_workflow" >/dev/null
! grep -E 'git push .*--force|git push .* -f( |$)|--force-with-lease' "$backend_workflow" >/dev/null

grep -F 'name: 内网 GPU 分支部署' "$internal_workflow" >/dev/null
grep -F 'workflow_call:' "$internal_workflow" >/dev/null
grep -F 'group: tss-aiplatform-internal-deploy' "$internal_workflow" >/dev/null
grep -F 'ssh_with_retry() {' "$internal_workflow" >/dev/null
grep -F 'for attempt in 1 2 3 4; do' "$internal_workflow" >/dev/null
grep -F 'sleep $((attempt * 2))' "$internal_workflow" >/dev/null
grep -F 'ssh_with_retry "sync ${RELEASE_SHA}"' "$internal_workflow" >/dev/null
grep -F 'ssh_with_retry probe' "$internal_workflow" >/dev/null
grep -F 'stage_source_with_retry() {' "$internal_workflow" >/dev/null
grep -F '"stage-source ${RELEASE_SHA}" < "$source_bundle"' "$internal_workflow" >/dev/null
grep -F 'source_ref=refs/tss-aiplatform/internal-release' "$internal_workflow" >/dev/null
grep -F 'git bundle verify "$source_bundle"' "$internal_workflow" >/dev/null
grep -F -- '-o ServerAliveInterval=15' "$internal_workflow" >/dev/null
grep -F -- '-o ServerAliveCountMax=2' "$internal_workflow" >/dev/null
! grep -F 'ssh_with_retry deploy-backend' "$internal_workflow" >/dev/null
! grep -F 'ssh_with_retry stage-backend' "$internal_workflow" >/dev/null
grep -F 'stage_backend_with_retry() {' "$internal_workflow" >/dev/null
grep -F 'stage_backend_with_retry' "$internal_workflow" >/dev/null
grep -F 'sleep $((attempt * 5))' "$internal_workflow" >/dev/null
grep -F 'already promoted identical bundle is accepted by its digest' \
  "$internal_workflow" >/dev/null
grep -F 'git merge-base --is-ancestor "$APPLICATION_SHA" "$current_head"' \
  "$internal_workflow" >/dev/null
grep -F "allowed_post_test_file='deploy/tss-aiplatform-internal/platform/frontend-image.lock'" \
  "$internal_workflow" >/dev/null
grep -F '[[ -z "$changed_after_test" || "$changed_after_test" == "$allowed_post_test_file" ]]' \
  "$internal_workflow" >/dev/null
allowed_post_test_file='deploy/tss-aiplatform-internal/platform/frontend-image.lock'
for allowed_change in '' "$allowed_post_test_file"; do
  [[ -z $allowed_change || $allowed_change == "$allowed_post_test_file" ]] \
    || { echo 'reviewed frontend lock was rejected after backend testing' >&2; exit 1; }
done
for rejected_change in \
  'backend/src/main/java/Unexpected.java' \
  "$allowed_post_test_file"$'\nbackend/src/main/java/Unexpected.java'; do
  if [[ -z $rejected_change || $rejected_change == "$allowed_post_test_file" ]]; then
    echo 'non-frontend change was accepted after backend testing' >&2
    exit 1
  fi
done
grep -F 'environment: tss-aiplatform-internal' "$internal_workflow" >/dev/null
grep -F 'runs-on: [self-hosted, Linux, X64, tss-aiplatform-internal, deploy]' \
  "$internal_workflow" >/dev/null
grep -F 'promote-backend-image-lock.sh' "$internal_workflow" >/dev/null
grep -F 'retention-days: 3' "$internal_workflow" >/dev/null
grep -F 'External Main cluster: not targeted' "$internal_workflow" >/dev/null
! grep -F 'deploy-main-validation.yml' "$internal_workflow" >/dev/null
! grep -F '${{ secrets.' "$internal_workflow" >/dev/null

grep -F 'stage-source\ *)' "$platform_scripts/internal-runner-gateway.sh" >/dev/null
grep -F 'fetch --no-tags "$source_bundle_path"' \
  "$platform_scripts/internal-runner-gateway.sh" >/dev/null
! grep -F 'fetch --no-tags --prune origin' \
  "$platform_scripts/internal-runner-gateway.sh" >/dev/null

grep -F 'TSS_DEPLOYMENT_BRANCH=%s' \
  "$platform_scripts/install-internal-runner-gateway.sh" >/dev/null
grep -F 'backend-ops || $deployment_branch == backend-gpu' \
  "$platform_scripts/install-internal-runner-gateway.sh" >/dev/null
grep -F 'refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}' \
  "$platform_scripts/internal-runner-gateway.sh" >/dev/null
grep -F '${source_bundle_ref}:refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}' \
  "$platform_scripts/internal-runner-gateway.sh" >/dev/null
! grep -F '!deploy/tss-aiplatform-internal/**' "$backend_workflow" >/dev/null
grep -F 'refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}' \
  "$platform_scripts/deploy-internal-backend.sh" >/dev/null
grep -F 'TSS_FRONTEND_DEPLOYMENT_BRANCH=%s' \
  "$platform_scripts/install-internal-frontend-deployer.sh" >/dev/null
grep -F 'refs/remotes/origin/${TSS_FRONTEND_DEPLOYMENT_BRANCH}' \
  "$platform_scripts/deploy-internal-frontend.sh" >/dev/null

echo 'PASS: external CPU and internal GPU deployment lanes are one-way and isolated'
