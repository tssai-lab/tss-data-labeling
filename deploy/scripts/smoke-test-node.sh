#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [node.env]" >&2
  exit 1
fi

config_file="${1:-${TSS_NODE_CONFIG:-}}"
if [[ -n $config_file ]]; then
  if [[ ! -f $config_file ]]; then
    echo "Node configuration file does not exist: $config_file" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$config_file"
  set +a
fi

platform_url="${TSS_PLATFORM_URL:-http://127.0.0.1:8080}"
mlflow_url="${TSS_MLFLOW_HEALTH_URL:-http://127.0.0.1:5000/}"
platform_dir="${TSS_PLATFORM_DIR:-/opt/tss-platform}"
kubectl_bin="${TSS_KUBECTL_BIN:-${platform_dir}/.tools/bin/kubectl}"
kubeconfig="${TSS_KUBECONFIG:-${platform_dir}/k8s/.kube/config}"
training_worker_image="${TSS_TRAINING_WORKER_IMAGE:-tss-training-worker:local}"

expect_status() {
  local label="$1"
  local expected="$2"
  local url="$3"
  shift 3
  local actual
  actual="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 10 "$@" "$url")"
  if [[ $actual != "$expected" ]]; then
    echo "FAIL: $label expected HTTP $expected, got $actual" >&2
    exit 1
  fi
  echo "PASS: $label HTTP $actual"
}

expect_status liveness 200 "${platform_url}/health/live"
expect_status readiness 200 "${platform_url}/health/ready"
expect_status unauthenticated-guard 401 "${platform_url}/api/user/current-user"
expect_status mlflow 200 "$mlflow_url"

if [[ -n ${TSS_SMOKE_AUTH_TOKEN:-} ]]; then
  expect_status authenticated-user 200 \
    "${platform_url}/api/user/current-user" \
    -H "Authorization: Bearer ${TSS_SMOKE_AUTH_TOKEN}"
  expect_status training-environment 200 \
    "${platform_url}/api/training/environment/status" \
    -H "Authorization: Bearer ${TSS_SMOKE_AUTH_TOKEN}"
else
  echo "SKIP: authenticated checks; TSS_SMOKE_AUTH_TOKEN is not set"
fi

if [[ -x $kubectl_bin && -f $kubeconfig ]]; then
  KUBECONFIG="$kubeconfig" "$kubectl_bin" wait \
    --for=condition=Ready node --all --timeout=30s
  KUBECONFIG="$kubeconfig" \
  CONNECTIVITY_TEST_IMAGE="$training_worker_image" \
    bash "${platform_dir}/backend/scripts/k8s/verify-local-kind.sh"
else
  echo "SKIP: Kubernetes checks; kubectl or kubeconfig is unavailable"
fi

if [[ -n ${TSS_EXTENDED_SMOKE_COMMAND:-} ]]; then
  if [[ ! -x $TSS_EXTENDED_SMOKE_COMMAND ]]; then
    echo "Extended smoke command is not executable: $TSS_EXTENDED_SMOKE_COMMAND" >&2
    exit 1
  fi
  "$TSS_EXTENDED_SMOKE_COMMAND"
else
  echo "SKIP: project-specific file/training/inference smoke command is not configured"
fi

echo "Node smoke tests passed."
