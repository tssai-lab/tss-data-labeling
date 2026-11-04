#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
validator="${root_dir}/deploy/scripts/tss-node-validate-deployment"
bootstrap="${root_dir}/deploy/scripts/bootstrap-node-backend.sh"
main_bootstrap="${root_dir}/deploy/scripts/bootstrap-main-backend.sh"
backend_activator="${root_dir}/deploy/scripts/tss-node-activate-backend"
backend_loader="${root_dir}/deploy/scripts/tss-node-load-backend"
runtime_loader="${root_dir}/deploy/scripts/tss-node-load-inference"
cache_preparer="${root_dir}/deploy/scripts/tss-node-prepare-model-cache"
cache_validator="${root_dir}/deploy/scripts/tss-node-validate-model-cache"
redis_compose="${root_dir}/deploy/main/compose.backend.yml"
redis_runbook="${root_dir}/deploy/SESSION_REDIS_RUNBOOK.md"
runtime_env_lib="${root_dir}/deploy/scripts/lib-runtime-env.sh"
runtime_workflow="${root_dir}/.github/workflows/runtime-images.yml"
cv_dockerfile="${root_dir}/k8s/training-worker/Dockerfile.cv"
inference_dockerfile="${root_dir}/k8s/inference-worker/Dockerfile"
inference_ml_requirements="${root_dir}/k8s/inference-worker/requirements-ml-cpu.txt"
kubeadm_verifier="${root_dir}/backend/scripts/k8s/verify-kubeadm.sh"
kubeadm_probe="${root_dir}/k8s/base/backend-access-probe-job.yaml"

workdir="$(mktemp -d)"
cleanup() {
  rm -rf "$workdir"
}
trap cleanup EXIT

runtime_file="${workdir}/node-runtime.env"
cat >"$runtime_file" <<'EOF'
TSS_BACKEND_IMAGE_REPOSITORY=ghcr.io/tssai-lab/tssai-backend
TSS_INFERENCE_IMAGE_REPOSITORY=ghcr.io/tssai-lab/tss-inference-worker-cpu
TSS_INFERENCE_FALLBACK_IMAGE_REPOSITORY=registry.example/tss-inference-worker-cpu
TSS_CV_IMAGE_REPOSITORY=registry.example/tss-cv-worker
TSS_NLP_IMAGE_REPOSITORY=registry.example/tss-nlp-worker
TSS_PLATFORM_DIR=/nonexistent/tss-platform
TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES=10737418240
EOF

# A bootstrap may update its own keys, but it must not erase independently
# managed features such as the SMS provider configuration.
existing_runtime_env="${workdir}/existing-runtime.env"
managed_runtime_env="${workdir}/managed-runtime.env"
cat >"$existing_runtime_env" <<'EOF'
SPRING_DATASOURCE_URL=jdbc:old
SMS_PROVIDER=aliyun
SMS_SIGN_SECRET=keep-this-value
EOF
cat >"$managed_runtime_env" <<'EOF'
SPRING_DATASOURCE_URL=jdbc:new
AUTH_SESSION_STORE=redis
EOF
# shellcheck source=deploy/scripts/lib-runtime-env.sh
source "$runtime_env_lib"
tss_merge_runtime_env_file "$existing_runtime_env" "$managed_runtime_env"
[[ $(grep -Fc 'SPRING_DATASOURCE_URL=' "$existing_runtime_env") -eq 1 ]]
grep -Fx 'SPRING_DATASOURCE_URL=jdbc:new' "$existing_runtime_env" >/dev/null
grep -Fx 'AUTH_SESSION_STORE=redis' "$existing_runtime_env" >/dev/null
grep -Fx 'SMS_PROVIDER=aliyun' "$existing_runtime_env" >/dev/null
grep -Fx 'SMS_SIGN_SECRET=keep-this-value' "$existing_runtime_env" >/dev/null
[[ $(stat -c '%a' "$existing_runtime_env") == 600 ]]

fail_case() {
  local expected_message="$1"
  shift
  if TSS_NODE_RUNTIME_ENV="$runtime_file" bash "$validator" "$@" >"${workdir}/output" 2>&1; then
    echo "Expected validator failure: $expected_message" >&2
    exit 1
  fi
  if ! grep -F "$expected_message" "${workdir}/output" >/dev/null; then
    cat "${workdir}/output" >&2
    exit 1
  fi
}

valid_backend="ghcr.io/tssai-lab/tssai-backend:$(printf 'a%.0s' {1..40})"
valid_id="sha256:$(printf 'b%.0s' {1..64})"
valid_cv="registry.example/tss-cv-worker:$(printf 'c%.0s' {1..40})"
valid_inference_fallback="registry.example/tss-inference-worker-cpu:$(printf 'd%.0s' {1..40})"

fail_case "Backend image must use" "ghcr.io/tssai-lab/tssai-backend:latest" "$valid_id"
fail_case "Backend image ID is invalid" "$valid_backend" "sha256:short"
fail_case "NLP image must use" "$valid_backend" "$valid_id" "$valid_cv" "registry.example/tss-nlp-worker:latest"

if TSS_NODE_RUNTIME_ENV="$runtime_file" bash "$runtime_loader" \
  "$valid_inference_fallback" "$valid_id" >"${workdir}/loader-output" 2>&1; then
  echo "Expected loader to stop at the intentionally absent platform directory." >&2
  exit 1
fi
if ! grep -F "Backend runtime environment does not exist." \
  "${workdir}/loader-output" >/dev/null; then
  cat "${workdir}/loader-output" >&2
  exit 1
fi

grep -F 'tss.ai/deployment-smoke: "true"' "$validator" >/dev/null
grep -F 'imagePullPolicy: ${pull_policy}' "$validator" >/dev/null
grep -F 'automountServiceAccountToken: false' "$validator" >/dev/null
grep -F 'trap cleanup EXIT' "$validator" >/dev/null
grep -F 'tss-node-activate-backend' "$bootstrap" >/dev/null
grep -F 'tss-node-load-backend' "$bootstrap" >/dev/null
grep -F 'training_k8s_client_mode="${TSS_TRAINING_K8S_CLIENT_MODE:-kubectl}"' "$bootstrap" >/dev/null
grep -F 'TSS_TRAINING_K8S_CLIENT_MODE must be kubectl or fabric8.' "$bootstrap" >/dev/null
grep -F 'TRAINING_K8S_CLIENT_MODE=${training_k8s_client_mode}' "$bootstrap" >/dev/null
grep -F 'tss-node-validate-deployment "$image" "$expected_image_id"' "$backend_activator" >/dev/null
grep -F 'up -d --no-deps redis mlflow' "$backend_activator" >/dev/null
grep -F 'Redis session store is unavailable; keeping the current backend.' "$backend_activator" >/dev/null
grep -F 'tss-node-activate-backend "$image" "$expected_image_id"' "$backend_loader" >/dev/null
grep -F 'AUTH_SESSION_STORE=redis' "$bootstrap" >/dev/null
grep -F 'tss_merge_runtime_env_file "$runtime_env" "$managed_runtime_env"' "$bootstrap" >/dev/null
grep -F 'TSS_REQUIRE_REDIS_SESSION_STORE=true' "$bootstrap" >/dev/null
grep -F 'TSS_DEPLOYMENT_SMOKE_NODE must name the Kubernetes node used for deployment smoke tests.' "$bootstrap" >/dev/null
grep -F 'get node "$deployment_smoke_node"' "$bootstrap" >/dev/null
grep -F "printf 'TSS_DEPLOYMENT_SMOKE_NODE=%q\\n'" "$bootstrap" >/dev/null
grep -F 'docker image inspect "$redis_image"' "$bootstrap" >/dev/null
grep -F 'active_inference_worker_image' "$bootstrap" >/dev/null
grep -F 'INFERENCE_KUBERNETES_WORKER_IMAGE' "$bootstrap" >/dev/null
grep -F 'active_cluster_name' "$bootstrap" >/dev/null
grep -F 'active_k8s_kubeconfig' "$bootstrap" >/dev/null
grep -F 'active_k8s_verify_script' "$bootstrap" >/dev/null
grep -F 'active_deployment_smoke_node' "$bootstrap" >/dev/null
grep -F 'active_model_cache_enabled' "$bootstrap" >/dev/null
grep -F 'active_model_cache_runtime_reserve_bytes' "$bootstrap" >/dev/null
grep -F 'TRAINING_K8S_KUBECONFIG=${k8s_kubeconfig}' "$bootstrap" >/dev/null
grep -F 'TRAINING_K8S_VERIFY_SCRIPT=${k8s_verify_script}' "$bootstrap" >/dev/null
grep -F 'TSS_K8S_KUBECONFIG=/opt/tss-platform/k8s/.kube/admin.conf' \
  "${root_dir}/deploy/nodes/main.env.example" >/dev/null
grep -F 'TSS_DEPLOYMENT_SMOKE_NODE=k8s-master' \
  "${root_dir}/deploy/nodes/main.env.example" >/dev/null
grep -F 'TSS_MODEL_CACHE_ENABLED=true' \
  "${root_dir}/deploy/nodes/main.env.example" >/dev/null
grep -F 'TSS_CLUSTER_NAME:-main-kubeadm' "$main_bootstrap" >/dev/null
grep -F 'TSS_DEPLOYMENT_SMOKE_NODE:-k8s-master' "$main_bootstrap" >/dev/null
grep -F 'TSS_K8S_KUBECONFIG:-/opt/tss-platform/k8s/.kube/admin.conf' \
  "$main_bootstrap" >/dev/null
grep -F 'retired `tss-training-control-plane` kind cluster' "$redis_runbook" >/dev/null
grep -F 'backend-access-probe-job.yaml' "$kubeadm_verifier" >/dev/null
grep -F -- '--dry-run=server' "$kubeadm_verifier" >/dev/null
grep -F 'automountServiceAccountToken: false' "$kubeadm_probe" >/dev/null
grep -F 'readOnlyRootFilesystem: true' "$kubeadm_probe" >/dev/null
if grep -F 'docker image inspect "$training_worker_image"' "$bootstrap" >/dev/null \
  || grep -F 'docker image inspect "$inference_worker_image"' "$bootstrap" >/dev/null; then
  echo "Backend bootstrap must not require duplicate Docker copies of Kubernetes-only worker images." >&2
  exit 1
fi
grep -F 'redis_image != "$reviewed_redis_image"' "$bootstrap" >/dev/null
grep -F 'redis_image_id != "$reviewed_redis_image_id"' "$bootstrap" >/dev/null
grep -F "docker image inspect \"\$redis_image\" --format '{{.Id}}'" "$bootstrap" >/dev/null
grep -F 'install -d -m 700 -o 999 -g 1000 "${platform_dir}/redis-data"' "$bootstrap" >/dev/null
grep -F '127.0.0.1:6379:6379' "$redis_compose" >/dev/null
grep -F 'pull_policy: never' "$redis_compose" >/dev/null
grep -F './redis-data:/data' "$redis_compose" >/dev/null
grep -F 'appendonly' "$redis_compose" >/dev/null
grep -F 'noeviction' "$redis_compose" >/dev/null
grep -F 'Redis-backed auth session readiness is not UP' "$validator" >/dev/null
grep -F 'redis_image == "$reviewed_redis_image"' "$validator" >/dev/null
grep -F 'actual_redis_image_id != "$redis_image_id"' "$validator" >/dev/null
grep -F 'docker_bin port "$redis_container" 6379/tcp' "$validator" >/dev/null
grep -F 'actual_redis_image_id != "$TSS_REDIS_IMAGE_ID"' "$backend_activator" >/dev/null
grep -F 'Application rollback must never remove it.' "$redis_runbook" >/dev/null
grep -F 'tss-node-validate-deployment "${validation_args[@]}"' "$runtime_loader" >/dev/null
grep -F 'TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES="${TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES:-10737418240}"' "$runtime_loader" >/dev/null
grep -F 'Deploy and validate Main runtime images' "$runtime_workflow" >/dev/null
grep -F 'tss-node-load-inference' "$runtime_workflow" | grep -F '$cv_image' >/dev/null
grep -F 'tss-node-load-inference --registry' "$runtime_workflow" >/dev/null
if grep -F 'docker save "$image" "$cv_image" "$nlp_image"' "$runtime_workflow" >/dev/null; then
  echo "Main runtime deployment must use the persistent Kubernetes registry credential instead of a combined archive." >&2
  exit 1
fi
grep -F 'ServerAliveInterval=30 -o ServerAliveCountMax=20' "$runtime_workflow" >/dev/null
grep -F 'required_images+=("$cv_image" "$nlp_image")' "$runtime_loader" >/dev/null
grep -F 'Private-registry runtime preload passed' "$runtime_loader" >/dev/null
grep -F 'serviceAccountName: ${service_account}' "$runtime_loader" >/dev/null
grep -F 'imagePullPolicy: Always' "$runtime_loader" >/dev/null
grep -F 'TRAINING_K8S_KUBECTL_PATH' "$runtime_loader" >/dev/null
grep -F 'for required_image in "${required_images[@]}"' "$runtime_loader" >/dev/null
grep -F 'available_bytes -lt $runtime_reserve_bytes' "$runtime_loader" >/dev/null
grep -F 'Runtime image is not available in Kubernetes containerd' "$runtime_loader" >/dev/null
grep -F 'gzip -dc | ctr --namespace k8s.io images import -' "$runtime_loader" >/dev/null
grep -F 'import_status=("${PIPESTATUS[@]}")' "$runtime_loader" >/dev/null
grep -F 'import_status[0] != 0 && import_status[0] != 141' "$runtime_loader" >/dev/null
grep -F 'import_status[1] != 0' "$runtime_loader" >/dev/null
grep -F 'ctr --namespace k8s.io content get "$image_target_digest"' "$runtime_loader" >/dev/null
if grep -F 'print $3; exit' "$runtime_loader" >/dev/null; then
  echo "Runtime image lookup must consume the full containerd list under pipefail." >&2
  exit 1
fi
grep -F 'json.load(sys.stdin)["config"]["digest"]' "$runtime_loader" >/dev/null
grep -F 'Imported image identifier does not match the runner image.' "$runtime_loader" >/dev/null
if grep -F 'docker load' "$runtime_loader" >/dev/null; then
  echo "Runtime loader must not keep a second Docker copy of Kubernetes-only images." >&2
  exit 1
fi
grep -F 'tss-node-prepare-model-cache' "$bootstrap" >/dev/null
grep -F 'tss-node-validate-model-cache' "$bootstrap" >/dev/null
grep -F 'TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES' "$cache_preparer" >/dev/null
grep -F 'max_bytes="${TSS_MODEL_CACHE_MAX_BYTES:-1073741824}"' "$cache_preparer" >/dev/null
grep -F 'min_free_bytes="${TSS_MODEL_CACHE_MIN_FREE_BYTES:-3221225472}"' "$cache_preparer" >/dev/null
grep -F 'setpriv --reuid="$cache_uid"' "$cache_preparer" >/dev/null
grep -F 'tss.ai/model-cache-ready=true --overwrite' "$cache_validator" >/dev/null
grep -F 'kind: PersistentVolume' "$cache_validator" >/dev/null
grep -F 'kind: PersistentVolumeClaim' "$cache_validator" >/dev/null
grep -F 'persistentVolumeReclaimPolicy: Retain' "$cache_validator" >/dev/null
grep -F 'persistentVolumeClaim:' "$cache_validator" >/dev/null
grep -F 'get configmap "$policy_config_map"' "$cache_validator" >/dev/null
grep -F 'refusing to validate with stale defaults' "$cache_validator" >/dev/null
grep -F 'ConfigMap values must be integer GiB between 1 and 1024' "$cache_validator" >/dev/null
grep -F 'TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES="$cache_runtime_reserve_bytes"' "$validator" >/dev/null
if grep -F 'hostPath:' "$cache_validator" >/dev/null; then
  echo "Restricted cache probe must mount the node-local cache through a bound PVC." >&2
  exit 1
fi
if grep -F '.tss-model-cache-root' "$cache_validator" >/dev/null; then
  echo "Cluster-admin validation must not inspect a remote node through its own filesystem." >&2
  exit 1
fi
grep -F -- '--probe-only' "$validator" >/dev/null
grep -F 'INFERENCE_KUBERNETES_MODEL_CACHE_ENABLED' "$validator" >/dev/null

# GitHub-hosted Ubuntu runners provide passwordless sudo. Exercise the real
# physical-directory preparation without weakening its root-only production
# guard; local environments without passwordless sudo still run every static
# contract check above.
if sudo -n true >/dev/null 2>&1; then
  chmod 0755 "$workdir"
  cache_root="${workdir}/model-cache"
  sudo -n env \
    TSS_MODEL_CACHE_HOST_PATH="$cache_root" \
    TSS_MODEL_CACHE_MAX_BYTES=1048576 \
    TSS_MODEL_CACHE_MIN_FREE_BYTES=0 \
    TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES=0 \
    TSS_MODEL_CACHE_UID="$(id -u)" \
    TSS_MODEL_CACHE_GID="$(id -g)" \
    bash "$cache_preparer" >"${workdir}/cache-prepare-output"
  grep -F 'Model cache directory prepared' "${workdir}/cache-prepare-output" >/dev/null
  [[ $(<"${cache_root}/.tss-model-cache-root") == tss-model-cache-v1 ]]
  [[ -d ${cache_root}/entries && -d ${cache_root}/locks && -d ${cache_root}/tmp ]]

  ln -s "$cache_root" "${workdir}/model-cache-link"
  if sudo -n env \
    TSS_MODEL_CACHE_HOST_PATH="${workdir}/model-cache-link" \
    TSS_MODEL_CACHE_MAX_BYTES=1048576 \
    TSS_MODEL_CACHE_MIN_FREE_BYTES=0 \
    TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES=0 \
    TSS_MODEL_CACHE_UID="$(id -u)" \
    TSS_MODEL_CACHE_GID="$(id -g)" \
    bash "$cache_preparer" >"${workdir}/cache-link-output" 2>&1; then
    echo "Expected model cache preparation to reject a symbolic-link path." >&2
    exit 1
  fi
  grep -F 'must not traverse symbolic links' "${workdir}/cache-link-output" >/dev/null

  nested_cache_root="${workdir}/nested-model-cache"
  mkdir -p "$nested_cache_root"
  ln -s /tmp "${nested_cache_root}/entries"
  if sudo -n env \
    TSS_MODEL_CACHE_HOST_PATH="$nested_cache_root" \
    TSS_MODEL_CACHE_MAX_BYTES=1048576 \
    TSS_MODEL_CACHE_MIN_FREE_BYTES=0 \
    TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES=0 \
    TSS_MODEL_CACHE_UID="$(id -u)" \
    TSS_MODEL_CACHE_GID="$(id -g)" \
    bash "$cache_preparer" >"${workdir}/cache-nested-link-output" 2>&1; then
    echo "Expected model cache preparation to reject a nested symbolic link." >&2
    exit 1
  fi
  grep -F 'components must not be symbolic links' \
    "${workdir}/cache-nested-link-output" >/dev/null
fi
grep -F 'crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-inference-worker-cpu:${{ github.sha }}' "$runtime_workflow" >/dev/null
grep -F 'image="crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-inference-worker-cpu:${GITHUB_SHA}"' "$runtime_workflow" >/dev/null
grep -F 'run_smoke_pod "inference" "$inference_image" "IfNotPresent"' "$validator" >/dev/null
grep -F 'run_smoke_pod "cv" "$cv_image" "IfNotPresent"' "$validator" >/dev/null
grep -F 'run_smoke_pod "nlp" "$nlp_image" "IfNotPresent"' "$validator" >/dev/null
grep -F -A1 'import infer_worker' "$validator" | grep -Fx 'import ultralytics' >/dev/null
grep -F 'status.conditions[?(@.type=="DiskPressure")].status' "$validator" >/dev/null
grep -F 'Smoke node remained under DiskPressure' "$validator" >/dev/null
grep -F 'images list --quiet' "$validator" >/dev/null
if grep -F 'Configured inference image is not ready in Kubernetes containerd.' "$validator" >/dev/null; then
  echo "Validator must allow an authenticated IfNotPresent pull after kubelet image GC." >&2
  exit 1
fi
requirements_install_line="$(grep -nF 'pip install --no-cache-dir -r requirements-cv.txt' "$cv_dockerfile" | cut -d: -f1)"
opencv_uninstall_line="$(grep -nF 'pip uninstall -y opencv-python opencv-contrib-python opencv-contrib-python-headless' "$cv_dockerfile" | cut -d: -f1)"
headless_reinstall_line="$(grep -nF 'pip install --no-cache-dir --force-reinstall --no-deps opencv-python-headless==4.10.0.84' "$cv_dockerfile" | cut -d: -f1)"
[[ "$requirements_install_line" -lt "$opencv_uninstall_line" ]]
[[ "$opencv_uninstall_line" -lt "$headless_reinstall_line" ]]

grep -Fx 'ultralytics==8.3.18' "$inference_ml_requirements" >/dev/null
grep -Fx 'numpy>=1.23,<2.0' "$inference_ml_requirements" >/dev/null
grep -Fx 'opencv-python==4.11.0.86' "$inference_ml_requirements" >/dev/null
grep -Fx 'USER root' "$inference_dockerfile" >/dev/null
grep -F 'PYTHONNOUSERSITE=1 python -c' "$inference_dockerfile" >/dev/null
grep -F 'import cv2, numpy, torch, transformers, ultralytics' "$inference_dockerfile" >/dev/null
inference_requirements_install_line="$(grep -nF 'pip install --no-cache-dir -r requirements-ml-cpu.txt' "$inference_dockerfile" | cut -d: -f1)"
inference_opencv_uninstall_line="$(grep -nF 'opencv-python opencv-contrib-python opencv-contrib-python-headless' "$inference_dockerfile" | tail -n1 | cut -d: -f1)"
inference_headless_reinstall_line="$(grep -nF 'opencv-python-headless==4.11.0.86' "$inference_dockerfile" | tail -n1 | cut -d: -f1)"
inference_runtime_user_line="$(grep -nF 'USER 10001' "$inference_dockerfile" | tail -n1 | cut -d: -f1)"
inference_runtime_import_line="$(grep -nF 'PYTHONNOUSERSITE=1 python -c' "$inference_dockerfile" | cut -d: -f1)"
[[ "$inference_requirements_install_line" -lt "$inference_opencv_uninstall_line" ]]
[[ "$inference_opencv_uninstall_line" -lt "$inference_headless_reinstall_line" ]]
[[ "$inference_headless_reinstall_line" -lt "$inference_runtime_user_line" ]]
[[ "$inference_runtime_user_line" -lt "$inference_runtime_import_line" ]]

echo "Deployment validation contract tests passed."
