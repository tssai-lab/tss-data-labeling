#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KUBECTL="${KUBECTL:-${ROOT_DIR}/.tools/bin/kubectl}"
KUBECONFIG_PATH="${KUBECONFIG_PATH:-${ROOT_DIR}/k8s/.kube/config}"
CONNECTIVITY_TEST_IMAGE="${CONNECTIVITY_TEST_IMAGE:-tss-training-worker:local}"

export KUBECONFIG="${KUBECONFIG_PATH}"

"${KUBECTL}" delete job tss-training-connectivity \
  --namespace tss-training \
  --ignore-not-found
sed \
  -e "s|__CONNECTIVITY_TEST_IMAGE__|${CONNECTIVITY_TEST_IMAGE}|g" \
  "${ROOT_DIR}/k8s/local/connectivity-job.yaml" \
  | "${KUBECTL}" apply -f -
"${KUBECTL}" wait \
  --for=condition=complete \
  job/tss-training-connectivity \
  --namespace tss-training \
  --timeout=180s
"${KUBECTL}" logs \
  job/tss-training-connectivity \
  --namespace tss-training
"${KUBECTL}" get pod \
  --namespace tss-training \
  --selector app.kubernetes.io/name=tss-training-connectivity \
  -o wide
