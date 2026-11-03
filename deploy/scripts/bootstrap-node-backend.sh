#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

if [[ $# -ne 1 || ! -f $1 ]]; then
  echo "Usage: TSS_NODE_CONFIG=/path/to/node.env $0 /path/to/compose.backend.yml" >&2
  exit 1
fi

if [[ -n ${TSS_NODE_CONFIG:-} ]]; then
  if [[ ! -f $TSS_NODE_CONFIG ]]; then
    echo "Node configuration file does not exist: $TSS_NODE_CONFIG" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$TSS_NODE_CONFIG"
  set +a
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/scripts/lib-runtime-env.sh
source "${script_dir}/lib-runtime-env.sh"

node_id="${TSS_NODE_ID:-node-01}"
platform_dir="${TSS_PLATFORM_DIR:-/opt/tss-platform}"
deploy_user="${TSS_DEPLOY_USER:-tss-deployer}"
compose_base="${TSS_COMPOSE_BASE:-${platform_dir}/compose.yml}"
compose_overlay="$1"
runtime_env="${platform_dir}/backend/.env.runtime"
node_runtime_env="${TSS_NODE_RUNTIME_ENV:-/etc/tss-platform/node-runtime.env}"
backend_container="${TSS_BACKEND_CONTAINER:-tss-backend}"
redis_container="${TSS_REDIS_CONTAINER:-tss-redis}"
postgres_container="${TSS_POSTGRES_CONTAINER:-tss-postgres}"
minio_container="${TSS_MINIO_CONTAINER:-tss-minio}"
backend_image_repository="${TSS_BACKEND_IMAGE_REPOSITORY:-ghcr.io/tssai-lab/tssai-backend}"
inference_image_repository="${TSS_INFERENCE_IMAGE_REPOSITORY:-crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-inference-worker-cpu}"
fallback_inference_image_repository="${TSS_INFERENCE_FALLBACK_IMAGE_REPOSITORY:-ghcr.io/tssai-lab/tss-inference-worker-cpu}"
cv_image_repository="${TSS_CV_IMAGE_REPOSITORY:-crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-cv-worker}"
nlp_image_repository="${TSS_NLP_IMAGE_REPOSITORY:-crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com/tss-platform/tss-nlp-worker}"
backend_health_url="${TSS_BACKEND_HEALTH_URL:-http://127.0.0.1:8080/health/ready}"
cluster_name="${TSS_CLUSTER_NAME:-tss-training-${node_id}}"
deployment_smoke_node="${TSS_DEPLOYMENT_SMOKE_NODE:-}"
k8s_kubeconfig="${TSS_K8S_KUBECONFIG:-/opt/tss-platform/k8s/.kube/config}"
k8s_verify_script="${TSS_K8S_VERIFY_SCRIPT:-/opt/tss-platform/backend/scripts/k8s/verify-local-kind.sh}"
server_address="${TSS_SERVER_ADDRESS:-127.0.0.1}"
spring_profiles_active="${TSS_SPRING_PROFILES_ACTIVE:-default}"
datasource_host="${TSS_DATASOURCE_HOST:-127.0.0.1}"
datasource_port="${TSS_DATASOURCE_PORT:-5432}"
minio_endpoint="${TSS_MINIO_ENDPOINT:-http://127.0.0.1:9010}"
minio_bucket="${TSS_MINIO_BUCKET:-models}"
mlflow_tracking_uri="${TSS_MLFLOW_TRACKING_URI:-http://127.0.0.1:5000}"
redis_host="${TSS_REDIS_HOST:-127.0.0.1}"
redis_port="${TSS_REDIS_PORT:-6379}"
training_experiment_name="${TSS_TRAINING_EXPERIMENT_NAME:-tss-training}"
training_k8s_client_mode="${TSS_TRAINING_K8S_CLIENT_MODE:-kubectl}"
k8s_backend_service_url="${TSS_K8S_BACKEND_SERVICE_URL:-http://tss-backend:8080}"
k8s_minio_service_url="${TSS_K8S_MINIO_SERVICE_URL:-http://tss-minio:9000}"
k8s_mlflow_service_url="${TSS_K8S_MLFLOW_SERVICE_URL:-http://tss-mlflow:5000}"
mlflow_image="${TSS_MLFLOW_IMAGE:-tss-platform-mlflow:local}"
reviewed_redis_image="tss-platform-redis:7.4.11-alpine-amd64-5509c0097c60"
reviewed_redis_image_id="sha256:5509c0097c6064aa8a3b1df58f1d950e67090fffa6678ae8f3f1dc2385f12deb"
redis_image="${TSS_REDIS_IMAGE:-$reviewed_redis_image}"
redis_image_id="${TSS_REDIS_IMAGE_ID:-$reviewed_redis_image_id}"
training_worker_image="${TSS_TRAINING_WORKER_IMAGE:-tss-training-worker:local}"
inference_worker_image="${TSS_INFERENCE_WORKER_IMAGE:-tss-inference-worker-cpu:local}"

if [[ -f ${platform_dir}/runtime-images.env ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${platform_dir}/runtime-images.env"
  set +a
  mlflow_image="${TSS_MLFLOW_IMAGE:-$mlflow_image}"
  training_worker_image="${TSS_TRAINING_WORKER_IMAGE:-$training_worker_image}"
  inference_worker_image="${TSS_INFERENCE_WORKER_IMAGE:-$inference_worker_image}"
fi

# Runtime releases update the active backend environment after Kubernetes-only
# images are imported. On an existing node, preserve those exact active refs
# instead of replacing them with stale bootstrap defaults from node.env.
if [[ -f $runtime_env ]]; then
  active_cluster_name="$(
    awk -F= '$1 == "TRAINING_K8S_CLUSTER_NAME" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_k8s_kubeconfig="$(
    awk -F= '$1 == "TRAINING_K8S_KUBECONFIG" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_k8s_verify_script="$(
    awk -F= '$1 == "TRAINING_K8S_VERIFY_SCRIPT" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_training_worker_image="$(
    awk -F= '$1 == "TRAINING_K8S_WORKER_IMAGE" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_inference_worker_image="$(
    awk -F= '$1 == "INFERENCE_KUBERNETES_WORKER_IMAGE" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_model_cache_enabled="$(
    awk -F= '$1 == "INFERENCE_KUBERNETES_MODEL_CACHE_ENABLED" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_model_cache_node_path="$(
    awk -F= '$1 == "INFERENCE_KUBERNETES_MODEL_CACHE_NODE_PATH" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_model_cache_mount_path="$(
    awk -F= '$1 == "INFERENCE_KUBERNETES_MODEL_CACHE_MOUNT_PATH" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_model_cache_max_bytes="$(
    awk -F= '$1 == "INFERENCE_KUBERNETES_MODEL_CACHE_MAX_BYTES" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_model_cache_min_free_bytes="$(
    awk -F= '$1 == "INFERENCE_KUBERNETES_MODEL_CACHE_MIN_FREE_BYTES" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  active_model_cache_runtime_reserve_bytes="$(
    awk -F= '$1 == "INFERENCE_KUBERNETES_MODEL_CACHE_RUNTIME_RESERVE_BYTES" { print substr($0, index($0, "=") + 1); exit }' \
      "$runtime_env"
  )"
  cluster_name="${active_cluster_name:-$cluster_name}"
  k8s_kubeconfig="${active_k8s_kubeconfig:-$k8s_kubeconfig}"
  k8s_verify_script="${active_k8s_verify_script:-$k8s_verify_script}"
  training_worker_image="${active_training_worker_image:-$training_worker_image}"
  inference_worker_image="${active_inference_worker_image:-$inference_worker_image}"
fi

if [[ -f $node_runtime_env ]]; then
  active_deployment_smoke_node="$(
    awk -F= '$1 == "TSS_DEPLOYMENT_SMOKE_NODE" { print substr($0, index($0, "=") + 1); exit }' \
      "$node_runtime_env"
  )"
  deployment_smoke_node="${active_deployment_smoke_node:-$deployment_smoke_node}"
fi

if [[ $redis_host != 127.0.0.1 ]]; then
  echo "TSS_REDIS_HOST must remain on 127.0.0.1 for the single-node session store." >&2
  exit 1
fi
if [[ $redis_container != tss-redis ]]; then
  echo "TSS_REDIS_CONTAINER must remain tss-redis for the restricted deployment helper." >&2
  exit 1
fi
if [[ $redis_port != 6379 ]]; then
  echo "TSS_REDIS_PORT must remain 6379 for the loopback-only session store." >&2
  exit 1
fi
if [[ $redis_image != "$reviewed_redis_image" ]]; then
  echo "TSS_REDIS_IMAGE must use the reviewed offline Redis reference." >&2
  exit 1
fi
if [[ $redis_image_id != "$reviewed_redis_image_id" ]]; then
  echo "TSS_REDIS_IMAGE_ID must use the reviewed Redis image identifier." >&2
  exit 1
fi
if [[ -z $deployment_smoke_node ]]; then
  echo "TSS_DEPLOYMENT_SMOKE_NODE must name the Kubernetes node used for deployment smoke tests." >&2
  exit 1
fi
if [[ ! $deployment_smoke_node =~ ^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$ ]]; then
  echo "TSS_DEPLOYMENT_SMOKE_NODE is not a valid Kubernetes node name." >&2
  exit 1
fi

model_cache_enabled="${TSS_MODEL_CACHE_ENABLED:-false}"
model_cache_node_path="${TSS_MODEL_CACHE_NODE_PATH:-/opt/tss-platform/model-cache}"
model_cache_mount_path="${TSS_MODEL_CACHE_MOUNT_PATH:-/var/cache/tss/models}"
model_cache_max_bytes="${TSS_MODEL_CACHE_MAX_BYTES:-1073741824}"
model_cache_min_free_bytes="${TSS_MODEL_CACHE_MIN_FREE_BYTES:-3221225472}"
model_cache_runtime_reserve_bytes="${TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES:-10737418240}"

if [[ $training_k8s_client_mode != kubectl && $training_k8s_client_mode != fabric8 ]]; then
  echo "TSS_TRAINING_K8S_CLIENT_MODE must be kubectl or fabric8." >&2
  exit 1
fi

if [[ -f $runtime_env ]]; then
  model_cache_enabled="${active_model_cache_enabled:-$model_cache_enabled}"
  model_cache_node_path="${active_model_cache_node_path:-$model_cache_node_path}"
  model_cache_mount_path="${active_model_cache_mount_path:-$model_cache_mount_path}"
  model_cache_max_bytes="${active_model_cache_max_bytes:-$model_cache_max_bytes}"
  model_cache_min_free_bytes="${active_model_cache_min_free_bytes:-$model_cache_min_free_bytes}"
  model_cache_runtime_reserve_bytes="${active_model_cache_runtime_reserve_bytes:-$model_cache_runtime_reserve_bytes}"
fi

if [[ $model_cache_enabled != true && $model_cache_enabled != false ]]; then
  echo "TSS_MODEL_CACHE_ENABLED must be true or false." >&2
  exit 1
fi
for cache_path in "$model_cache_node_path" "$model_cache_mount_path"; do
  normalized_cache_path="/${cache_path#/}/"
  if [[ ! $cache_path =~ ^/[A-Za-z0-9._/-]+$ \
    || $cache_path == / \
    || $cache_path == *"//"* \
    || $normalized_cache_path == *"/./"* \
    || $normalized_cache_path == *"/../"* ]]; then
    echo "Model cache paths must be safe absolute paths without whitespace or dot segments." >&2
    exit 1
  fi
done
if [[ ! $model_cache_max_bytes =~ ^[1-9][0-9]*$ \
  || ! $model_cache_min_free_bytes =~ ^[0-9]+$ \
  || ! $model_cache_runtime_reserve_bytes =~ ^[0-9]+$ ]]; then
  echo "Model cache maximum must be positive and reserve values non-negative." >&2
  exit 1
fi

for command_name in ctr curl docker flock gzip openssl visudo; do
  command -v "$command_name" >/dev/null
done
docker compose version >/dev/null
docker inspect "$postgres_container" >/dev/null
docker inspect "$minio_container" >/dev/null
docker image inspect "$mlflow_image" >/dev/null
docker image inspect "$redis_image" >/dev/null
if [[ $(docker image inspect "$redis_image" --format '{{.Id}}') != "$redis_image_id" ]]; then
  echo "Preloaded Redis image identifier differs from the reviewed image." >&2
  exit 1
fi
id "$deploy_user" >/dev/null

for required_path in \
  "$k8s_kubeconfig" \
  "${platform_dir}/.tools/bin/kubectl" \
  "$k8s_verify_script"; do
  if [[ ! -e $required_path ]]; then
    echo "Required node training runtime path is missing: $required_path" >&2
    exit 1
  fi
done

"${platform_dir}/.tools/bin/kubectl" \
  --kubeconfig "$k8s_kubeconfig" \
  get node "$deployment_smoke_node" >/dev/null

get_container_env() {
  local container="$1"
  local key="$2"

  docker inspect "$container" --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | awk -v prefix="${key}=" 'index($0, prefix) == 1 { print substr($0, length(prefix) + 1); exit }'
}

postgres_db="$(get_container_env "$postgres_container" POSTGRES_DB)"
postgres_user="$(get_container_env "$postgres_container" POSTGRES_USER)"
postgres_password="$(get_container_env "$postgres_container" POSTGRES_PASSWORD)"
minio_user="$(get_container_env "$minio_container" MINIO_ROOT_USER)"
minio_password="$(get_container_env "$minio_container" MINIO_ROOT_PASSWORD)"

for value_name in postgres_db postgres_user postgres_password minio_user minio_password; do
  if [[ -z ${!value_name} ]]; then
    echo "Required runtime value ${value_name} is missing." >&2
    exit 1
  fi
done

install -d -m 700 "${platform_dir}/backend"
install -d -m 750 "${platform_dir}/backend/logs"
chown 10001:10001 "${platform_dir}/backend/logs"
install -d -m 700 -o 999 -g 1000 "${platform_dir}/redis-data"
install -m 600 "$compose_overlay" "${platform_dir}/compose.backend.yml"

umask 077
callback_token="$(
  awk -F= '$1 == "TRAINING_K8S_INTERNAL_CALLBACK_TOKEN" { print substr($0, index($0, "=") + 1); exit }' \
    "$runtime_env" 2>/dev/null || true
)"
if [[ -z $callback_token ]]; then
  callback_token="$(openssl rand -hex 32)"
fi

managed_runtime_env="$(mktemp "${runtime_env}.managed.XXXXXX")"
cleanup_managed_runtime_env() {
  if [[ -n ${managed_runtime_env:-} ]]; then
    rm -f -- "$managed_runtime_env"
  fi
}
trap cleanup_managed_runtime_env EXIT

cat > "$managed_runtime_env" <<EOF
TSS_NODE_ID=${node_id}
SERVER_ADDRESS=${server_address}
SPRING_PROFILES_ACTIVE=${spring_profiles_active}
SPRING_DATASOURCE_URL=jdbc:postgresql://${datasource_host}:${datasource_port}/${postgres_db}
SPRING_DATASOURCE_USERNAME=${postgres_user}
SPRING_DATASOURCE_PASSWORD=${postgres_password}
AUTH_SESSION_STORE=redis
SPRING_DATA_REDIS_HOST=${redis_host}
SPRING_DATA_REDIS_PORT=${redis_port}
SPRING_DATA_REDIS_CONNECT_TIMEOUT=3s
SPRING_DATA_REDIS_TIMEOUT=3s
MINIO_ENDPOINT=${minio_endpoint}
MINIO_ACCESS_KEY=${minio_user}
MINIO_SECRET_KEY=${minio_password}
MINIO_BUCKET=${minio_bucket}
TRAINING_MLFLOW_ENABLED=true
TRAINING_MLFLOW_TRACKING_URI=${mlflow_tracking_uri}
TRAINING_MLFLOW_EXPERIMENT_NAME=${training_experiment_name}
TRAINING_K8S_ENABLED=true
TRAINING_K8S_CLIENT_MODE=${training_k8s_client_mode}
TRAINING_K8S_AUTO_CREATE=false
TRAINING_K8S_VERIFY_ON_STARTUP=true
TRAINING_K8S_FALLBACK_TO_LOCAL=false
TRAINING_K8S_CLUSTER_NAME=${cluster_name}
TRAINING_K8S_PROJECT_ROOT=/opt/tss-platform
TRAINING_K8S_KUBECONFIG=${k8s_kubeconfig}
TRAINING_K8S_KUBECTL_PATH=/opt/tss-platform/.tools/bin/kubectl
TRAINING_K8S_VERIFY_SCRIPT=${k8s_verify_script}
TRAINING_K8S_WORKER_IMAGE=${training_worker_image}
TRAINING_K8S_WORKER_IMAGE_PULL_POLICY=IfNotPresent
TRAINING_K8S_BACKEND_SERVICE_URL=${k8s_backend_service_url}
TRAINING_K8S_MINIO_SERVICE_URL=${k8s_minio_service_url}
TRAINING_K8S_MLFLOW_SERVICE_URL=${k8s_mlflow_service_url}
TRAINING_K8S_INTERNAL_CALLBACK_TOKEN=${callback_token}
INFERENCE_KUBERNETES_WORKER_IMAGE=${inference_worker_image}
INFERENCE_KUBERNETES_WORKER_IMAGE_PULL_POLICY=IfNotPresent
INFERENCE_KUBERNETES_MODEL_CACHE_ENABLED=${model_cache_enabled}
INFERENCE_KUBERNETES_MODEL_CACHE_NODE_PATH=${model_cache_node_path}
INFERENCE_KUBERNETES_MODEL_CACHE_MOUNT_PATH=${model_cache_mount_path}
INFERENCE_KUBERNETES_MODEL_CACHE_MAX_BYTES=${model_cache_max_bytes}
INFERENCE_KUBERNETES_MODEL_CACHE_MIN_FREE_BYTES=${model_cache_min_free_bytes}
INFERENCE_KUBERNETES_MODEL_CACHE_RUNTIME_RESERVE_BYTES=${model_cache_runtime_reserve_bytes}
EOF
chmod 600 "$managed_runtime_env"
tss_merge_runtime_env_file "$runtime_env" "$managed_runtime_env"
rm -f -- "$managed_runtime_env"
managed_runtime_env=""

install -d -m 700 /etc/tss-platform
{
  printf 'TSS_NODE_ID=%q\n' "$node_id"
  printf 'TSS_PLATFORM_DIR=%q\n' "$platform_dir"
  printf 'TSS_COMPOSE_BASE=%q\n' "$compose_base"
  printf 'TSS_COMPOSE_OVERLAY=%q\n' "${platform_dir}/compose.backend.yml"
  printf 'TSS_BACKEND_CONTAINER=%q\n' "$backend_container"
  printf 'TSS_REDIS_CONTAINER=%q\n' "$redis_container"
  printf 'TSS_REQUIRE_REDIS_SESSION_STORE=true\n'
  printf 'TSS_BACKEND_IMAGE_REPOSITORY=%q\n' "$backend_image_repository"
  printf 'TSS_INFERENCE_IMAGE_REPOSITORY=%q\n' "$inference_image_repository"
  printf 'TSS_INFERENCE_FALLBACK_IMAGE_REPOSITORY=%q\n' "$fallback_inference_image_repository"
  printf 'TSS_CV_IMAGE_REPOSITORY=%q\n' "$cv_image_repository"
  printf 'TSS_NLP_IMAGE_REPOSITORY=%q\n' "$nlp_image_repository"
  printf 'TSS_BACKEND_HEALTH_URL=%q\n' "$backend_health_url"
  printf 'TSS_DEPLOYMENT_SMOKE_NODE=%q\n' "$deployment_smoke_node"
  printf 'TSS_MLFLOW_IMAGE=%q\n' "$mlflow_image"
  printf 'TSS_REDIS_IMAGE=%q\n' "$redis_image"
  printf 'TSS_REDIS_IMAGE_ID=%q\n' "$redis_image_id"
  printf 'TSS_MODEL_CACHE_RUNTIME_RESERVE_BYTES=%q\n' "$model_cache_runtime_reserve_bytes"
} > "$node_runtime_env"
chmod 600 "$node_runtime_env"

install -m 700 \
  "${script_dir}/tss-node-activate-backend" \
  /usr/local/sbin/tss-node-activate-backend
install -m 700 \
  "${script_dir}/tss-node-load-backend" \
  /usr/local/sbin/tss-node-load-backend
install -m 700 \
  "${script_dir}/tss-node-validate-deployment" \
  /usr/local/sbin/tss-node-validate-deployment
install -m 700 \
  "${script_dir}/tss-node-load-inference" \
  /usr/local/sbin/tss-node-load-inference
install -m 700 \
  "${script_dir}/tss-node-prepare-model-cache" \
  /usr/local/sbin/tss-node-prepare-model-cache
install -m 700 \
  "${script_dir}/tss-node-validate-model-cache" \
  /usr/local/sbin/tss-node-validate-model-cache

if [[ ${TSS_REMOVE_LEGACY_HELPERS:-false} == true ]]; then
  rm -f \
    /usr/local/sbin/tss-main-login-ghcr \
    /usr/local/sbin/tss-main-deploy-backend \
    /usr/local/sbin/tss-main-load-backend \
    /usr/local/sbin/tss-main-activate-backend \
    /etc/sudoers.d/tss-main-backend-deployer
fi

cat > /etc/sudoers.d/tss-node-backend-deployer <<EOF
${deploy_user} ALL=(root) NOPASSWD: /usr/local/sbin/tss-node-load-backend *
${deploy_user} ALL=(root) NOPASSWD: /usr/local/sbin/tss-node-load-inference *
EOF
chmod 440 /etc/sudoers.d/tss-node-backend-deployer
visudo -cf /etc/sudoers.d/tss-node-backend-deployer >/dev/null

TSS_BACKEND_IMAGE="${backend_image_repository}:0000000000000000000000000000000000000000" \
TSS_MLFLOW_IMAGE="$mlflow_image" \
TSS_REDIS_IMAGE="$redis_image" \
  docker compose -f "$compose_base" -f "${platform_dir}/compose.backend.yml" config --quiet

TSS_BACKEND_IMAGE="${backend_image_repository}:0000000000000000000000000000000000000000" \
TSS_MLFLOW_IMAGE="$mlflow_image" \
TSS_REDIS_IMAGE="$redis_image" \
  docker compose -f "$compose_base" -f "${platform_dir}/compose.backend.yml" up -d --no-deps redis

redis_healthy=false
for _ in $(seq 1 24); do
  if [[ $(docker inspect "$redis_container" --format '{{.State.Health.Status}}' 2>/dev/null || true) == healthy ]]; then
    redis_healthy=true
    break
  fi
  sleep 2
done
if [[ $redis_healthy != true ]]; then
  echo "Redis session store did not become healthy during bootstrap." >&2
  docker logs --tail 100 "$redis_container" >&2 || true
  exit 1
fi

echo "Node backend deployment bootstrap completed: $node_id"
