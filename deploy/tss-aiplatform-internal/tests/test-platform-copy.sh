#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
platform_root="${repo_root}/deploy/tss-aiplatform-internal/platform"
reproducible_root="${repo_root}/deploy/tss-aiplatform-internal/reproducible"
compose="${platform_root}/compose.yml"
lock="${platform_root}/platform-images.lock"
runtime_lock="${reproducible_root}/runtime-images.lock"
gpu_runtime_lock="${reproducible_root}/gpu-runtime-images.lock"
dependency_inventory="${reproducible_root}/main-dependency-inventory.tsv"
frontend_lock="${reproducible_root}/frontend-source.lock"
frontend_nginx="${reproducible_root}/nginx/frontend.conf.template"
rbac="${platform_root}/k8s/backend-access.yaml"
workflow="${repo_root}/.github/workflows/tss-aiplatform-internal-validation.yml"
classifier="${repo_root}/deploy/tss-aiplatform-internal/ci/classify-backend-deploy-scope.sh"
backend_application="${repo_root}/backend/src/main/resources/application.yml"
inference_manifest_builder="${repo_root}/backend/src/main/java/com/tss/platform/inference/KubernetesInferenceJobManifestBuilder.java"

for file in \
  "$compose" "$lock" "$rbac" "$backend_application" "$inference_manifest_builder" \
  "$platform_root/platform.env.example" \
  "$platform_root/scripts/lib-platform.sh" \
  "$platform_root/scripts/generate-platform-secrets.sh" \
  "$platform_root/scripts/image-runtime-fingerprint.py" \
  "$platform_root/scripts/export-platform-images.sh" \
  "$platform_root/scripts/promote-backend-image-lock.sh" \
  "$platform_root/scripts/check-platform-image-budget.sh" \
  "$platform_root/scripts/verify-platform-images.sh" \
  "$platform_root/scripts/bootstrap-platform-kubernetes.sh" \
  "$platform_root/scripts/prepare-platform-network.sh" \
  "$platform_root/scripts/prepare-platform.sh" \
  "$platform_root/scripts/verify-platform.sh" \
  "$platform_root/scripts/internal-runner-gateway.sh" \
  "$platform_root/scripts/deploy-internal-backend.sh" \
  "$platform_root/scripts/install-internal-runner-gateway.sh" \
  "$platform_root/scripts/smoke-platform-api.sh" \
  "$platform_root/scripts/bootstrap-platform.sh" \
  "$platform_root/scripts/verify-internal-kubeadm.sh" \
  "$reproducible_root/README.md" \
  "$runtime_lock" "$gpu_runtime_lock" "$dependency_inventory" "$frontend_lock" "$frontend_nginx"; do
  [[ -f $file ]] || { echo "missing C5 file: $file" >&2; exit 1; }
done

grep -F 'install-metrics-server.sh' \
  "$platform_root/scripts/bootstrap-platform-kubernetes.sh" >/dev/null
grep -F 'list nodes.metrics.k8s.io' \
  "$platform_root/scripts/verify-internal-kubeadm.sh" >/dev/null
grep -F '/apis/metrics.k8s.io/v1beta1/nodes' \
  "$platform_root/scripts/verify-internal-kubeadm.sh" >/dev/null
grep -F 'if ($field == "<unknown>") valid=0' \
  "$platform_root/scripts/verify-internal-kubeadm.sh" >/dev/null

while IFS= read -r script; do
  bash -n "$script"
done < <(find "$platform_root/scripts" -maxdepth 1 -type f -name '*.sh' | sort)

[[ $(grep -Ev '^(#|$)' "$lock" | wc -l) -eq 5 ]]
[[ $(cut -d'|' -f3 "$lock" | grep -c '^tss-aiplatform-internal/') -eq 5 ]]
! cut -d'|' -f1 "$lock" | grep -F ':latest' >/dev/null
[[ $(cut -d'|' -f2 "$lock" | grep -Ec '^sha256:[0-9a-f]{64}$') -eq 5 ]]
[[ $(cut -d'|' -f4 "$lock" | grep -Ec '^sha256:[0-9a-f]{64}$') -eq 5 ]]
[[ $(cut -d'|' -f5 "$lock" | grep -Ec '^[0-9a-f]{64}$') -eq 5 ]]
[[ $(grep -Ev '^(#|$)' "$lock" | cut -d'|' -f2 | sort -u | wc -l) -eq 5 ]] \
  || { echo "image lock contains a duplicate source manifest digest" >&2; exit 1; }
[[ $(grep -Ev '^(#|$)' "$lock" | cut -d'|' -f4 | sort -u | wc -l) -eq 5 ]] \
  || { echo "image lock contains a duplicate image ID" >&2; exit 1; }
lock_entry_count=0
while IFS='|' read -r source_ref source_digest _project_ref expected_id expected_fingerprint budget_bytes; do
  [[ -n $source_ref && $source_ref != \#* ]] || continue
  [[ $source_digest =~ ^sha256:[0-9a-f]{64}$ ]]
  [[ $expected_id =~ ^sha256:[0-9a-f]{64}$ ]]
  [[ $expected_fingerprint =~ ^[0-9a-f]{64}$ ]]
  [[ $budget_bytes =~ ^[1-9][0-9]*$ ]] \
    || { echo "image lock contains a non-numeric budget" >&2; exit 1; }
  lock_entry_count=$((lock_entry_count + 1))
done <"$lock"
[[ $lock_entry_count -eq 5 ]] || { echo "image lock parser did not produce five entries" >&2; exit 1; }
bash "$platform_root/scripts/export-platform-images.sh" --validate-only >/dev/null
grep -F -- '--backend-only' "$platform_root/scripts/export-platform-images.sh" >/dev/null
grep -F '[[ -n $_source_ref && $_source_ref != \#* ]] || continue' \
  "$platform_root/scripts/verify-platform-images.sh" >/dev/null \
  || { echo "platform image verifier does not skip the commented lock header" >&2; exit 1; }

oci_fingerprint="$(python3 "$platform_root/scripts/image-runtime-fingerprint.py" <<'JSON'
{"architecture":"amd64","os":"linux","config":{"Cmd":["run"],"Env":["A=1"],"OnBuild":null},"rootfs":{"type":"layers","diff_ids":["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}
JSON
)"
docker_fingerprint="$(python3 "$platform_root/scripts/image-runtime-fingerprint.py" <<'JSON'
[{"Architecture":"amd64","Os":"linux","Config":{"Env":["A=1"],"Cmd":["run"]},"RootFS":{"Type":"layers","Layers":["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]},"Created":"ignored"}]
JSON
)"
[[ $oci_fingerprint == "$docker_fingerprint" ]] \
  || { echo "OCI config and equivalent Docker inspect JSON have different runtime fingerprints" >&2; exit 1; }
changed_fingerprint="$(python3 "$platform_root/scripts/image-runtime-fingerprint.py" <<'JSON'
{"architecture":"amd64","os":"linux","config":{"Cmd":["different"],"Env":["A=1"]},"rootfs":{"type":"layers","diff_ids":["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}}
JSON
)"
[[ $changed_fingerprint != "$oci_fingerprint" ]] \
  || { echo "runtime fingerprint did not detect a changed command" >&2; exit 1; }

[[ $(grep -Ev '^(#|$)' "$runtime_lock" | wc -l) -eq 4 ]]
! cut -d'|' -f1 "$runtime_lock" | grep -F ':latest' >/dev/null
[[ $(cut -d'|' -f2 "$runtime_lock" | grep -Ec '^sha256:[0-9a-f]{64}$') -eq 4 ]]
[[ $(cut -d'|' -f3 "$runtime_lock" | grep -Ec '^sha256:[0-9a-f]{64}$') -eq 4 ]]
[[ $(cut -d'|' -f5 "$runtime_lock" | grep -Ec '^[1-9][0-9]*$') -eq 4 ]]

[[ $(awk -F '\t' 'NF && NF != 5 {bad++} END {print bad + 0}' "$dependency_inventory") -eq 0 ]]
grep -F $'platform-secrets\t' "$dependency_inventory" | grep -F $'\tSECRET_REGENERATE' >/dev/null
grep -F $'database-and-object-data\t' "$dependency_inventory" | grep -F $'\tBUSINESS_DATA_EXCLUDED' >/dev/null
grep -Fx 'branch=frontend-dev' "$frontend_lock" >/dev/null
grep -E '^commit=[0-9a-f]{40}$' "$frontend_lock" >/dev/null
grep -E '^main_deployment_run=[1-9][0-9]*$' "$frontend_lock" >/dev/null
for placeholder in \
  REPLACE_LISTEN_ADDRESS REPLACE_SERVER_NAME REPLACE_FRONTEND_ROOT \
  REPLACE_BACKEND_ORIGIN REPLACE_MLFLOW_ORIGIN; do
  grep -F "$placeholder" "$frontend_nginx" >/dev/null
done
! grep -F '47.111.225.144' "$frontend_nginx" >/dev/null

for image_variable in \
  TSS_POSTGRES_IMAGE TSS_MINIO_IMAGE TSS_REDIS_IMAGE TSS_MLFLOW_IMAGE TSS_BACKEND_IMAGE; do
  image_ref="$(sed -n "s/^${image_variable}=//p" "$platform_root/platform.env.example")"
  [[ -n $image_ref ]]
  grep -F "|${image_ref}|" "$lock" >/dev/null \
    || { echo "platform environment image is absent from the lock: $image_variable" >&2; exit 1; }
done

gpu_inference_ref="$(sed -n 's/^TSS_GPU_INFERENCE_WORKER_IMAGE=//p' "$platform_root/platform.env.example")"
[[ -n $gpu_inference_ref ]]
grep -F "|${gpu_inference_ref}|gpu-inference|" "$gpu_runtime_lock" >/dev/null \
  || { echo "platform GPU inference image is absent from the runtime lock" >&2; exit 1; }
grep -F "gpu-worker-image: \${INFERENCE_KUBERNETES_GPU_WORKER_IMAGE:${gpu_inference_ref}}" \
  "$backend_application" >/dev/null
grep -F '@Value("${inference.kubernetes.gpu-worker-image}")' \
  "$inference_manifest_builder" >/dev/null
grep -F "INFERENCE_KUBERNETES_GPU_WORKER_IMAGE: \${TSS_GPU_INFERENCE_WORKER_IMAGE:-${gpu_inference_ref}}" \
  "$compose" >/dev/null

grep -F 'name: tss-aiplatform-internal' "$compose" >/dev/null
grep -F '127.0.0.1:${TSS_POSTGRES_PORT' "$compose" >/dev/null
grep -F '127.0.0.1:${TSS_REDIS_PORT' "$compose" >/dev/null
grep -F 'AUTH_SESSION_STORE: redis' "$compose" >/dev/null
grep -F 'SPRING_DATA_REDIS_HOST: 127.0.0.1' "$compose" >/dev/null
grep -F 'network_mode: host' "$compose" >/dev/null
grep -F 'MINIO_ENDPOINT: http://${TSS_PLATFORM_BIND_IP}:${TSS_MINIO_API_PORT}' "$compose" >/dev/null
grep -F 'TRAINING_MLFLOW_TRACKING_URI: http://${TSS_PLATFORM_BIND_IP}:${TSS_MLFLOW_PORT}' "$compose" >/dev/null
! grep -F 'MINIO_ENDPOINT: http://127.0.0.1' "$compose" >/dev/null
! grep -F 'TRAINING_MLFLOW_TRACKING_URI: http://127.0.0.1' "$compose" >/dev/null
grep -F 'TRAINING_K8S_AUTO_CREATE: "false"' "$compose" >/dev/null
grep -F 'TRAINING_K8S_FALLBACK_TO_LOCAL: "false"' "$compose" >/dev/null
grep -F 'INFERENCE_KUBERNETES_MODEL_CACHE_ENABLED: "false"' "$compose" >/dev/null
grep -F '[[ $files_health_status == 401 ]]' "$platform_root/scripts/verify-platform.sh" >/dev/null
grep -F '[[ $flyway == "$expected_flyway" ]]' "$platform_root/scripts/verify-platform.sh" >/dev/null
! grep -E '\[\[ \$flyway == [0-9]+' "$platform_root/scripts/verify-platform.sh" >/dev/null
[[ $(grep -Fc 'payload.get("success") is not True' "$platform_root/scripts/smoke-platform-api.sh") -eq 2 ]]
grep -F 'module1-schema-postgresql.sql:/docker-entrypoint-initdb.d/001-module1.sql:ro' "$compose" >/dev/null
grep -F "psql -U \$\$POSTGRES_USER -d \$\$POSTGRES_DB -Atqc 'SELECT 1'" "$compose" >/dev/null
grep -F 'install -d -m 0700 -o 999 -g 999' "$platform_root/scripts/prepare-platform.sh" >/dev/null
! grep -E 'container_name: tss-(backend|postgres|redis|minio|mlflow)$' "$compose" >/dev/null
! grep -F '/opt/tss-platform/postgres-data' "$compose" >/dev/null
! grep -E 'image:.*:latest' "$compose" >/dev/null

grep -F 'automountServiceAccountToken: false' "$rbac" >/dev/null
grep -F 'resources: ["resourcequotas"]' "$rbac" >/dev/null
grep -F 'resources: ["configmaps"]' "$rbac" >/dev/null
grep -F 'resources: ["nodes/proxy"]' "$rbac" >/dev/null
grep -F 'resources: ["pods/log"]' "$rbac" >/dev/null
! grep -F 'resources: ["secrets"]' "$rbac" >/dev/null
! grep -F 'verbs: ["*"]' "$rbac" >/dev/null
! grep -F 'cluster-admin' "$rbac" >/dev/null

classification="$(printf '%s\n' \
  'deploy/tss-aiplatform-internal/platform/compose.yml' \
  'deploy/tss-aiplatform-internal/platform/scripts/bootstrap-platform.sh' \
  'deploy/tss-aiplatform-internal/reproducible/main-dependency-inventory.tsv' \
  | bash "$classifier")"
[[ $classification == 'deploy_main=false' ]] \
  || { echo "internal platform-only changes would deploy Main" >&2; exit 1; }
grep -F 'bash deploy/tss-aiplatform-internal/tests/test-platform-copy.sh' "$workflow" >/dev/null
grep -F -- '- export-platform-images' "$workflow" >/dev/null
grep -F "inputs.task == 'export-platform-images'" "$workflow" >/dev/null
grep -F 'bash deploy/tss-aiplatform-internal/platform/scripts/export-platform-images.sh' "$workflow" >/dev/null
grep -F -- '- deploy-backend' "$workflow" >/dev/null
grep -F "inputs.task == 'deploy-backend'" "$workflow" >/dev/null
grep -F 'runs-on: [self-hosted, Linux, X64, tss-aiplatform-internal, deploy]' "$workflow" >/dev/null
grep -F 'CONTROL_PLANE_DEPLOYMENT_USER: ${{ vars.CONTROL_PLANE_DEPLOYMENT_USER }}' \
  "$workflow" >/dev/null
grep -F 'CONTROL_PLANE_SSH_KEY: ${{ vars.CONTROL_PLANE_SSH_KEY }}' "$workflow" >/dev/null
grep -F 'CONTROL_PLANE_KNOWN_HOSTS: ${{ vars.CONTROL_PLANE_KNOWN_HOSTS }}' "$workflow" >/dev/null
grep -F -- '--profile backend-image' "$workflow" >/dev/null
grep -F -- '-o BatchMode=yes' "$workflow" >/dev/null
grep -F -- '-o IdentitiesOnly=yes' "$workflow" >/dev/null
grep -F -- '-o StrictHostKeyChecking=yes' "$workflow" >/dev/null
grep -F 'stage-backend' "$workflow" >/dev/null
grep -F 'stage-source ${GITHUB_SHA}' "$workflow" >/dev/null
grep -F 'deploy-backend' "$workflow" >/dev/null

gateway="$platform_root/scripts/internal-runner-gateway.sh"
deployer="$platform_root/scripts/deploy-internal-backend.sh"
frontend_deployer="$platform_root/scripts/deploy-internal-frontend.sh"
installer="$platform_root/scripts/install-internal-runner-gateway.sh"
grep -F 'current_project_id=$(docker image inspect --format' "$deployer" >/dev/null
grep -F 'candidate_id=$(docker image inspect --format' "$deployer" >/dev/null
grep -F 'if [[ $candidate_id != "$current_project_id" ]]; then' "$deployer" >/dev/null
grep -F 'TSS_BACKEND_IMAGE="$old_image" \' "$deployer" >/dev/null
grep -F 'rm -f "$bundle_path"' "$deployer" >/dev/null
grep -F 'rm -f "$bundle_path"' "$frontend_deployer" >/dev/null
grep -F 'stage-source\ *)' "$gateway" >/dev/null
grep -F 'source_bundle_ref=refs/tss-aiplatform/internal-release' "$gateway" >/dev/null
grep -F 'fetch --no-tags "$source_bundle_path"' "$gateway" >/dev/null
! grep -F 'fetch --no-tags --prune origin' "$gateway" >/dev/null
grep -F 'command_text=${SSH_ORIGINAL_COMMAND:-}' "$gateway" >/dev/null
grep -F 'exec sudo -n /usr/local/sbin/tss-aiplatform-internal-deploy-backend' "$gateway" >/dev/null
grep -F 'command is not permitted by the internal Runner gateway' "$gateway" >/dev/null
grep -F 'restrict,command="/usr/local/sbin/tss-aiplatform-internal-runner-gateway"' "$installer" >/dev/null
grep -F "printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/tss-aiplatform-internal-deploy-backend\\n'" \
  "$installer" >/dev/null
grep -F "printf '%s ALL=(root) NOPASSWD: /usr/bin/cat %s\\n'" \
  "$installer" >/dev/null
grep -F 'state_content=$(sudo -n /usr/bin/cat "$state_file" 2>/dev/null || true)' \
  "$gateway" >/dev/null
! grep -F 'NOPASSWD: ALL' "$installer" >/dev/null
grep -F 'deployment_config=/etc/tss-aiplatform-deploy/backend.env' "$gateway" >/dev/null
grep -F 'deployment_config=/etc/tss-aiplatform-deploy/backend.env' "$deployer" >/dev/null
grep -F 'TSS_DEPLOYMENT_BRANCH=%s' "$installer" >/dev/null
grep -F 'refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}' "$gateway" >/dev/null
grep -F 'refs/remotes/origin/${TSS_DEPLOYMENT_BRANCH}' "$deployer" >/dev/null
grep -F 'TSS_INSTALL_ROOT_TO_HARDEN=$install_root harden_project_install_tree' \
  "$installer" >/dev/null
if grep -REn 'seu4080|seu5090|/media/seu|/home/user|expected_user=user' \
  "$platform_root/scripts" >/dev/null; then
  echo 'platform deployment logic contains a physical environment identity or path' >&2
  exit 1
fi
if grep -En 'seu4080|seu5090|/media/seu|/home/user|"user@\$\{CONTROL_PLANE_HOST\}' \
  "$workflow" >/dev/null; then
  echo 'internal workflow contains a physical Runner, user or work-root binding' >&2
  exit 1
fi
grep -F 'sha256sum --check --strict backend-image.sha256' "$deployer" >/dev/null
grep -F 'staged backend source lock differs from protected repository' "$deployer" >/dev/null
grep -F 'runtime content differs' "$deployer" >/dev/null
grep -F 'docker compose -f "$compose_file" up -d backend' "$deployer" >/dev/null
grep -F -- '--confirm-node "$TSS_NODE_NAME"' "$deployer" >/dev/null
! grep -F -- '--confirm-node "$TSS_PLATFORM_HOSTNAME"' "$deployer" >/dev/null
grep -F 'a non-backend platform container changed during backend deployment' "$deployer" >/dev/null
grep -F 'reviewed internal Redis did not become healthy' "$deployer" >/dev/null
grep -F 'TSS_CONSECUTIVE_SUCCESS_COUNT=' "$deployer" >/dev/null
! grep -E 'docker (system|image|container|volume) prune' "$deployer" >/dev/null
if grep -F '${{ secrets.' "$workflow" >/dev/null; then
  echo 'internal workflow unexpectedly depends on a GitHub secret' >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
sed \
  -e 's|REPLACE_CONTROL_PLANE_HOSTNAME|user-rtx5090|g' \
  -e 's|REPLACE_CONTROL_PLANE_IP|10.201.96.68|g' \
  -e 's|REPLACE_WORKER_IP|10.201.81.142|g' \
  "$platform_root/platform.env.example" >"$tmp_dir/platform.env"
sed \
  -e 's|REPLACE_CONTROL_PLANE_IP|10.201.96.68|g' \
  -e 's|REPLACE_AFTER_APPROVED_DISK_PREPARATION|3a669b7d-6d65-4dfd-87a6-fa9246b9a194|g' \
  -e 's|TSS_ADDRESS_STABILITY_CONFIRMED=false|TSS_ADDRESS_STABILITY_CONFIRMED=true|g' \
  "$repo_root/deploy/tss-aiplatform-internal/config/control-plane.env.example" >"$tmp_dir/node.env"
bash -c 'source "$1"; load_platform_config "$2" "$3"' _ \
  "$platform_root/scripts/lib-platform.sh" "$tmp_dir/node.env" "$tmp_dir/platform.env"
grep -Fx 'TSS_MIN_ROOT_FREE_GIB=10' "$tmp_dir/platform.env" >/dev/null
cp "$tmp_dir/platform.env" "$tmp_dir/platform-root-nine.env"
sed -i 's/TSS_MIN_ROOT_FREE_GIB=10/TSS_MIN_ROOT_FREE_GIB=9/' \
  "$tmp_dir/platform-root-nine.env"
if bash -c 'source "$1"; load_platform_config "$2" "$3"' _ \
  "$platform_root/scripts/lib-platform.sh" "$tmp_dir/node.env" \
  "$tmp_dir/platform-root-nine.env" >/dev/null 2>&1; then
  echo "platform root survival floor below 10 GiB was accepted" >&2
  exit 1
fi
grep -Fx 'TSS_MIN_PLATFORM_FREE_GIB=100' "$tmp_dir/platform.env" >/dev/null
grep -F 'INFERENCE_KUBERNETES_MODEL_CACHE_ENABLED: "false"' "$compose" >/dev/null
bash -c '
  source "$1"
  TSS_PLATFORM_HOSTNAME=user-rtx5090
  TSS_NODE_NAME=tss-ai-control-01
  TSS_NODE_IP=10.201.96.68
  TSS_STORAGE_MOUNT_POINT=/srv/tss-AIplatform
  TSS_EXPECTED_STORAGE_UUID=3a669b7d-6d65-4dfd-87a6-fa9246b9a194
  hostname() { printf "%s\n" user-rtx5090; }
  ip() { printf "%s\n" "2: eth0 inet 10.201.96.68/16 scope global eth0"; }
  findmnt() { printf "%s\n" 3a669b7d-6d65-4dfd-87a6-fa9246b9a194; }
  require_control_plane_identity
' _ "$platform_root/scripts/lib-platform.sh"
if bash -c '
  source "$1"
  TSS_PLATFORM_HOSTNAME=some-other-host
  TSS_NODE_IP=10.201.96.68
  TSS_STORAGE_MOUNT_POINT=/srv/tss-AIplatform
  TSS_EXPECTED_STORAGE_UUID=3a669b7d-6d65-4dfd-87a6-fa9246b9a194
  hostname() { printf "%s\n" user-rtx5090; }
  ip() { printf "%s\n" "2: eth0 inet 10.201.96.68/16 scope global eth0"; }
  findmnt() { printf "%s\n" 3a669b7d-6d65-4dfd-87a6-fa9246b9a194; }
  require_control_plane_identity
' _ "$platform_root/scripts/lib-platform.sh" >/dev/null 2>&1; then
  echo "wrong physical hostname was accepted" >&2
  exit 1
fi
sed -i 's/TSS_MLFLOW_PORT=15000/TSS_MLFLOW_PORT=18080/' "$tmp_dir/platform.env"
if bash -c 'source "$1"; load_platform_config "$2" "$3"' _ \
  "$platform_root/scripts/lib-platform.sh" "$tmp_dir/node.env" "$tmp_dir/platform.env" \
  >/dev/null 2>&1; then
  echo "duplicate platform ports were accepted" >&2
  exit 1
fi

echo "PASS: C5 empty-platform copy contract"
