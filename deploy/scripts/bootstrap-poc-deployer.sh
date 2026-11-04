#!/usr/bin/env bash
set -euo pipefail

namespace=tss-poc
deploy_user=tss-deployer
public_key_file=${1:-}

if [[ $(id -u) -ne 0 ]]; then
  echo "Run this script as root on k8s-master." >&2
  exit 1
fi

if [[ -z "$public_key_file" || ! -f "$public_key_file" ]]; then
  echo "Usage: bash bootstrap-poc-deployer.sh /path/to/tssai_poc_deployer.pub" >&2
  exit 1
fi

if ! id "$deploy_user" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$deploy_user"
fi

install -d -m 700 -o "$deploy_user" -g "$deploy_user" "/home/$deploy_user/.ssh"
install -m 600 -o "$deploy_user" -g "$deploy_user" "$public_key_file" "/home/$deploy_user/.ssh/authorized_keys"

kubectl get namespace "$namespace" >/dev/null 2>&1 || kubectl create namespace "$namespace"

cat <<'EOF' | kubectl apply -f -
apiVersion: v1
kind: ServiceAccount
metadata:
  name: tss-poc-deployer
  namespace: tss-poc
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: tss-poc-deployer
  namespace: tss-poc
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/log", "services", "configmaps", "secrets", "events"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["apps"]
    resources: ["deployments", "deployments/scale", "replicasets"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: tss-poc-deployer
  namespace: tss-poc
subjects:
  - kind: ServiceAccount
    name: tss-poc-deployer
    namespace: tss-poc
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: tss-poc-deployer
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: tss-poc-limits
  namespace: tss-poc
spec:
  hard:
    pods: "8"
    requests.cpu: "2"
    requests.memory: 4Gi
    limits.cpu: "3"
    limits.memory: 5Gi
    services: "6"
EOF

token_secret=tss-poc-deployer-token
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: $token_secret
  namespace: $namespace
  annotations:
    kubernetes.io/service-account.name: tss-poc-deployer
type: kubernetes.io/service-account-token
EOF

for _ in $(seq 1 20); do
  token_b64=$(kubectl -n "$namespace" get secret "$token_secret" -o jsonpath='{.data.token}' 2>/dev/null || true)
  [[ -n "$token_b64" ]] && break
  sleep 1
done

if [[ -z "${token_b64:-}" ]]; then
  echo "Kubernetes did not issue the restricted service-account token." >&2
  exit 1
fi

api_server=$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.server}')
ca_data=$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')
token=$(printf '%s' "$token_b64" | base64 --decode)

install -d -m 700 -o "$deploy_user" -g "$deploy_user" "/home/$deploy_user/.kube"
cat > "/home/$deploy_user/.kube/config" <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: tss-cluster
    cluster:
      certificate-authority-data: $ca_data
      server: $api_server
contexts:
  - name: tss-poc
    context:
      cluster: tss-cluster
      namespace: $namespace
      user: tss-poc-deployer
current-context: tss-poc
users:
  - name: tss-poc-deployer
    user:
      token: $token
EOF
chown "$deploy_user:$deploy_user" "/home/$deploy_user/.kube/config"
chmod 600 "/home/$deploy_user/.kube/config"

sudo -u "$deploy_user" kubectl auth can-i create deployments -n "$namespace"
sudo -u "$deploy_user" kubectl auth can-i get secrets -n "$namespace"
echo "POC deployer bootstrap completed for $deploy_user."
