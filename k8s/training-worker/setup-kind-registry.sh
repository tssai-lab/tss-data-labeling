#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND="${KIND:-${ROOT_DIR}/.tools/bin/kind}"
CLUSTER_NAME="${CLUSTER_NAME:-tss-training}"
REGISTRY_NAME="${REGISTRY_NAME:-tss-kind-registry}"
REGISTRY_PORT="${REGISTRY_PORT:-5001}"
REGISTRY_IMAGE="${REGISTRY_IMAGE:-registry:2}"

if ! docker inspect "${REGISTRY_NAME}" >/dev/null 2>&1; then
  docker run -d \
    --restart=always \
    -p "127.0.0.1:${REGISTRY_PORT}:5000" \
    --name "${REGISTRY_NAME}" \
    "${REGISTRY_IMAGE}" >/dev/null
elif [[ "$(docker inspect -f '{{.State.Running}}' "${REGISTRY_NAME}")" != "true" ]]; then
  docker start "${REGISTRY_NAME}" >/dev/null
fi

if ! docker network inspect kind \
  --format '{{range .Containers}}{{.Name}}{{"\n"}}{{end}}' \
  | grep -Fxq "${REGISTRY_NAME}"; then
  docker network connect kind "${REGISTRY_NAME}"
fi

for node in $("${KIND}" get nodes --name "${CLUSTER_NAME}"); do
  registry_dir="/etc/containerd/certs.d/localhost:${REGISTRY_PORT}"
  docker exec "${node}" mkdir -p "${registry_dir}"
  printf '%s\n' \
    "server = \"http://localhost:${REGISTRY_PORT}\"" \
    "" \
    "[host.\"http://${REGISTRY_NAME}:5000\"]" \
    "  capabilities = [\"pull\", \"resolve\", \"push\"]" \
    | docker exec -i "${node}" tee "${registry_dir}/hosts.toml" >/dev/null

  # Some development hosts inject an HTTP proxy into the kind node. Internal
  # registry traffic must bypass it or containerd will try 127.0.0.1:<proxy>
  # from inside the node container.
  proxy_dir="/etc/systemd/system/containerd.service.d"
  docker exec "${node}" mkdir -p "${proxy_dir}"
  printf '%s\n' \
    "[Service]" \
    "Environment=\"NO_PROXY=127.0.0.1,localhost,${REGISTRY_NAME},.svc,.cluster.local\"" \
    "Environment=\"no_proxy=127.0.0.1,localhost,${REGISTRY_NAME},.svc,.cluster.local\"" \
    | docker exec -i "${node}" tee "${proxy_dir}/10-tss-registry-no-proxy.conf" >/dev/null
  docker exec "${node}" systemctl daemon-reload
  docker exec "${node}" systemctl restart containerd
done

echo "Local training registry ready: localhost:${REGISTRY_PORT}"
