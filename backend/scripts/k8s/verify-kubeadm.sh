#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KUBECTL="${KUBECTL:-${ROOT_DIR}/.tools/bin/kubectl}"
KUBECONFIG_PATH="${KUBECONFIG_PATH:-${KUBECONFIG:-${ROOT_DIR}/k8s/.kube/admin.conf}}"
PROBE_MANIFEST="${ROOT_DIR}/k8s/base/backend-access-probe-job.yaml"

[[ -x $KUBECTL ]] || { echo "kubectl is not executable" >&2; exit 1; }
[[ -r $KUBECONFIG_PATH ]] || { echo "kubeconfig is not readable" >&2; exit 1; }
[[ -r $PROBE_MANIFEST ]] || { echo "backend access probe manifest is not readable" >&2; exit 1; }

"${KUBECTL}" --kubeconfig="${KUBECONFIG_PATH}" wait \
  --for=condition=Ready node --all --timeout=30s
"${KUBECTL}" --kubeconfig="${KUBECONFIG_PATH}" get namespace tss-training >/dev/null
"${KUBECTL}" --kubeconfig="${KUBECONFIG_PATH}" auth can-i create jobs.batch \
  --namespace=tss-training | grep -Fxq yes
"${KUBECTL}" --kubeconfig="${KUBECONFIG_PATH}" create \
  --dry-run=server -f "${PROBE_MANIFEST}" -o name >/dev/null

echo "kubeadm API, authentication, RBAC and Job admission are ready"
