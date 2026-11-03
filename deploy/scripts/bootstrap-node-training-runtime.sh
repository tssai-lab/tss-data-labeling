#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

if [[ $# -ne 1 || ! -d $1 ]]; then
  echo "Usage: TSS_NODE_CONFIG=/path/to/node.env $0 /path/to/TSSAIPlatform" >&2
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

source_root="$(cd "$1" && pwd)"
node_id="${TSS_NODE_ID:-node-01}"
platform_dir="${TSS_PLATFORM_DIR:-/opt/tss-platform}"
compose_base="${TSS_COMPOSE_BASE:-${platform_dir}/compose.yml}"
compose_overlay="${TSS_COMPOSE_OVERLAY:-${platform_dir}/compose.backend.yml}"
kind_bin="${TSS_KIND_BIN:-${platform_dir}/.tools/bin/kind}"
kubectl_bin="${TSS_KUBECTL_BIN:-${platform_dir}/.tools/bin/kubectl}"
cluster_name="${TSS_CLUSTER_NAME:-tss-training-${node_id}}"
backend_host_port="${TSS_BACKEND_HOST_PORT:-18080}"
minio_host_port="${TSS_MINIO_HOST_PORT:-9010}"
mlflow_host_port="${TSS_MLFLOW_HOST_PORT:-15000}"
backend_listen_port="${TSS_BACKEND_LISTEN_PORT:-8080}"
mlflow_listen_port="${TSS_MLFLOW_LISTEN_PORT:-5000}"
backend_gid="${TSS_BACKEND_GID:-10001}"
runtime_source="${TSS_RUNTIME_IMAGE_SOURCE:-build}"
runtime_tag="${TSS_RUNTIME_IMAGE_TAG:-local}"
image_registry="${TSS_IMAGE_REGISTRY:-ghcr.io/tssai-lab}"
python_base_image="${TSS_PYTHON_BASE_IMAGE:-docker.m.daocloud.io/library/python:3.11-slim}"
model_cache_host_path="${TSS_MODEL_CACHE_HOST_PATH:-${platform_dir}/model-cache}"
model_cache_node_path="${TSS_MODEL_CACHE_NODE_PATH:-/var/lib/tss-platform/model-cache}"
model_cache_max_bytes="${TSS_MODEL_CACHE_MAX_BYTES:-1073741824}"
model_cache_min_free_bytes="${TSS_MODEL_CACHE_MIN_FREE_BYTES:-3221225472}"

if [[ ! $node_id =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]]; then
  echo "TSS_NODE_ID must be a lowercase DNS label." >&2
  exit 1
fi
if [[ $platform_dir != /* || $platform_dir == / ]]; then
  echo "TSS_PLATFORM_DIR must be an absolute non-root path." >&2
  exit 1
fi
for cache_path in "$model_cache_host_path" "$model_cache_node_path"; do
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
if [[ ! $model_cache_max_bytes =~ ^[1-9][0-9]*$ || ! $model_cache_min_free_bytes =~ ^[0-9]+$ ]]; then
  echo "TSS_MODEL_CACHE_MAX_BYTES must be positive and TSS_MODEL_CACHE_MIN_FREE_BYTES non-negative." >&2
  exit 1
fi
if [[ $runtime_source != build && $runtime_source != registry ]]; then
  echo "TSS_RUNTIME_IMAGE_SOURCE must be build or registry." >&2
  exit 1
fi
if [[ $runtime_source == registry && ! $runtime_tag =~ ^[0-9a-f]{40}$ ]]; then
  echo "Registry runtime images require a 40-character Git SHA tag." >&2
  exit 1
fi

mlflow_image="${TSS_MLFLOW_IMAGE:-${image_registry}/tss-mlflow-lite:${runtime_tag}}"
training_worker_image="${TSS_TRAINING_WORKER_IMAGE:-${image_registry}/tss-training-worker:${runtime_tag}}"
inference_worker_image="${TSS_INFERENCE_WORKER_IMAGE:-${image_registry}/tss-inference-worker-cpu:${runtime_tag}}"

for command_name in curl docker gzip nginx sha256sum systemctl; do
  command -v "$command_name" >/dev/null
done
docker compose version >/dev/null

install -d -m 755 "${platform_dir}/.tools/bin"

install_tool() {
  local target="$1"
  local url="$2"
  local expected_sha256="$3"
  local temporary

  if [[ -x $target ]] \
    && echo "${expected_sha256}  ${target}" | sha256sum --check --status; then
    return
  fi

  temporary="$(mktemp)"
  trap 'rm -f "$temporary"' RETURN
  curl --fail --location --retry 3 --output "$temporary" "$url"
  echo "${expected_sha256}  ${temporary}" | sha256sum --check --status
  install -m 755 "$temporary" "$target"
  rm -f "$temporary"
  trap - RETURN
}

install_tool \
  "$kind_bin" \
  "https://kind.sigs.k8s.io/dl/v0.32.0/kind-linux-amd64" \
  "50030de23cf40a18505f20426f6a8506bedf13c6e509244bd1fa9463721b0f54"
install_tool \
  "$kubectl_bin" \
  "https://dl.k8s.io/release/v1.34.8/bin/linux/amd64/kubectl" \
  "f6249132865c13abe3c9dd5038f5da65849cb86eee1608c001831504e481aa8c"

for required_path in \
  "$compose_base" \
  "$source_root/k8s/kind/cluster.yaml" \
  "$source_root/k8s/local/host-services.template.yaml" \
  "$source_root/backend/scripts/k8s/bootstrap-local-kind.sh" \
  "$source_root/backend/scripts/k8s/verify-local-kind.sh" \
  "$source_root/deploy/main/compose.backend.yml" \
  "$source_root/deploy/main/mlflow-lite/Dockerfile" \
  "$source_root/deploy/main/mlflow-lite/app.py"; do
  if [[ ! -e $required_path ]]; then
    echo "Required path is missing: $required_path" >&2
    exit 1
  fi
done

install -d -m 755 \
  "${platform_dir}/k8s" \
  "${platform_dir}/backend/scripts/k8s" \
  "${platform_dir}/mlflow-lite" \
  "${platform_dir}/mlflow-data"
chown 10001:10001 "${platform_dir}/mlflow-data"
install -d -m 750 -o 10001 -g 10001 \
  "$model_cache_host_path" \
  "${model_cache_host_path}/entries" \
  "${model_cache_host_path}/locks" \
  "${model_cache_host_path}/tmp"
printf 'tss-model-cache-v1\n' > "${model_cache_host_path}/.tss-model-cache-root"
chown 10001:10001 "${model_cache_host_path}/.tss-model-cache-root"
chmod 640 "${model_cache_host_path}/.tss-model-cache-root"
touch "${model_cache_host_path}/capacity.lock"
chown 10001:10001 "${model_cache_host_path}/capacity.lock"
chmod 640 "${model_cache_host_path}/capacity.lock"

cp -a "$source_root/k8s/." "${platform_dir}/k8s/"
install -m 755 \
  "$source_root/backend/scripts/k8s/bootstrap-local-kind.sh" \
  "${platform_dir}/backend/scripts/k8s/bootstrap-local-kind.sh"
install -m 755 \
  "$source_root/backend/scripts/k8s/verify-local-kind.sh" \
  "${platform_dir}/backend/scripts/k8s/verify-local-kind.sh"
install -m 644 \
  "$source_root/deploy/main/mlflow-lite/Dockerfile" \
  "${platform_dir}/mlflow-lite/Dockerfile"
install -m 644 \
  "$source_root/deploy/main/mlflow-lite/app.py" \
  "${platform_dir}/mlflow-lite/app.py"
install -m 600 \
  "$source_root/deploy/main/compose.backend.yml" \
  "$compose_overlay"

if [[ $runtime_source == build ]]; then
  docker build \
    --build-arg PYTHON_BASE_IMAGE="$python_base_image" \
    -t "$mlflow_image" \
    "${platform_dir}/mlflow-lite"
  docker build \
    --build-arg PYTHON_BASE_IMAGE="$python_base_image" \
    -t "$training_worker_image" \
    "${platform_dir}/k8s/training-worker"
  docker build \
    --build-arg INFERENCE_BASE_IMAGE="$training_worker_image" \
    -t "$inference_worker_image" \
    "${platform_dir}/k8s/inference-worker"
else
  docker pull "$mlflow_image"
  docker pull "$training_worker_image"
  docker pull "$inference_worker_image"
fi

current_image="$(docker inspect tss-backend --format '{{.Config.Image}}' 2>/dev/null || true)"
if [[ -z $current_image ]]; then
  current_image="${TSS_BACKEND_IMAGE_REPOSITORY:-ghcr.io/tssai-lab/tssai-backend}:0000000000000000000000000000000000000000"
fi
export TSS_BACKEND_IMAGE="$current_image"
export TSS_MLFLOW_IMAGE="$mlflow_image"
docker compose -f "$compose_base" -f "$compose_overlay" up -d --no-deps mlflow

SKIP_WORKER_BUILD=true \
CLUSTER_NAME="$cluster_name" \
BACKEND_HOST_PORT="$backend_host_port" \
MODEL_CACHE_HOST_PATH="$model_cache_host_path" \
MODEL_CACHE_NODE_PATH="$model_cache_node_path" \
MINIO_HOST_PORT="$minio_host_port" \
MLFLOW_HOST_PORT="$mlflow_host_port" \
  bash "${platform_dir}/backend/scripts/k8s/bootstrap-local-kind.sh"

host_gateway="$(
  docker network inspect kind \
    --format '{{range .IPAM.Config}}{{if .Gateway}}{{.Gateway}}{{println}}{{end}}{{end}}' \
  | head -1 | tr -d '[:space:]'
)"
if [[ -z $host_gateway ]]; then
  echo "Unable to determine the kind network gateway." >&2
  exit 1
fi

cat > "/etc/nginx/conf.d/tss-${node_id}-training-runtime.conf" <<EOF
server {
    listen ${host_gateway}:${backend_host_port};
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:${backend_listen_port};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
    }
}

server {
    listen ${host_gateway}:${mlflow_host_port};
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:${mlflow_listen_port};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
    }
}
EOF
nginx -t
systemctl reload nginx

"$kind_bin" load docker-image "$training_worker_image" --name "$cluster_name"
"$kind_bin" load docker-image "$inference_worker_image" --name "$cluster_name"

chown "root:${backend_gid}" "${platform_dir}/k8s/.kube/config"
chmod 640 "${platform_dir}/k8s/.kube/config"

CLUSTER_NAME="$cluster_name" \
CONNECTIVITY_TEST_IMAGE="$training_worker_image" \
  bash "${platform_dir}/backend/scripts/k8s/verify-local-kind.sh"

umask 077
cat > "${platform_dir}/runtime-images.env" <<EOF
TSS_RUNTIME_IMAGE_TAG=${runtime_tag}
TSS_MLFLOW_IMAGE=${mlflow_image}
TSS_TRAINING_WORKER_IMAGE=${training_worker_image}
TSS_INFERENCE_WORKER_IMAGE=${inference_worker_image}
TSS_MODEL_CACHE_HOST_PATH=${model_cache_host_path}
TSS_MODEL_CACHE_NODE_PATH=${model_cache_node_path}
TSS_MODEL_CACHE_MAX_BYTES=${model_cache_max_bytes}
TSS_MODEL_CACHE_MIN_FREE_BYTES=${model_cache_min_free_bytes}
EOF

echo "Node training runtime bootstrap completed."
echo "Node: $node_id"
echo "Cluster: $cluster_name"
echo "Runtime tag: $runtime_tag"
echo "Physical model cache: $model_cache_host_path -> $model_cache_node_path"
