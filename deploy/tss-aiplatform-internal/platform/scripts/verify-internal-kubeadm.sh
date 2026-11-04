#!/usr/bin/env bash
set -Eeuo pipefail

KUBECTL="${KUBECTL:-/opt/tss-platform/.tools/bin/kubectl}"
KUBECONFIG_PATH="${KUBECONFIG:-/opt/tss-platform/k8s/backend.kubeconfig}"
namespace=tss-training

[[ -x $KUBECTL ]] || { echo "kubectl is not executable" >&2; exit 1; }
[[ -r $KUBECONFIG_PATH ]] || { echo "restricted kubeconfig is not readable" >&2; exit 1; }
kube=("$KUBECTL" --kubeconfig "$KUBECONFIG_PATH" --request-timeout=15s)

"${kube[@]}" wait --for=condition=Ready node --all --timeout=30s >/dev/null
"${kube[@]}" get namespace "$namespace" >/dev/null
exact_gpu_enabled="${TRAINING_K8S_EXACT_GPU_SELECTION_ENABLED:-false}"
required_permissions=(
  'create jobs.batch'
  'delete jobs.batch'
  'create pods'
  'delete pods'
  'get pods/log'
  'get resourcequota/tss-training-quota'
  'patch resourcequota/tss-training-quota'
  'get configmap/tss-model-cache-policy'
  'list nodes'
  'list nodes.metrics.k8s.io'
)
# 只有打开精确选卡时，才要求后端具备 DRA 资源模板权限。
if [[ $exact_gpu_enabled == true ]]; then
  required_permissions+=(
    'create resourceclaimtemplates.resource.k8s.io'
    'patch resourceclaimtemplates.resource.k8s.io'
  )
fi
for permission in "${required_permissions[@]}"; do
  read -r verb resource <<<"$permission"
  [[ $("${kube[@]}" auth can-i "$verb" "$resource" -n "$namespace") == yes ]] \
    || { echo "required Kubernetes permission is missing: $permission" >&2; exit 1; }
done
[[ $("${kube[@]}" auth can-i get secrets -n "$namespace") == no ]] \
  || { echo "restricted backend identity must not read Kubernetes Secrets" >&2; exit 1; }
[[ $("${kube[@]}" auth can-i create namespaces) == no ]] \
  || { echo "restricted backend identity must not create namespaces" >&2; exit 1; }
"${kube[@]}" get --raw /apis/metrics.k8s.io/v1beta1/nodes \
  | grep -F '"items"' >/dev/null \
  || { echo "Metrics API is unavailable to the restricted backend identity" >&2; exit 1; }
metrics_output="$("${kube[@]}" top nodes --no-headers)" \
  || { echo "Metrics API did not return node usage" >&2; exit 1; }
mapfile -t cluster_nodes < <("${kube[@]}" get nodes \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
for node in "${cluster_nodes[@]}"; do
  printf '%s\n' "$metrics_output" | awk -v wanted="$node" '
    $1 == wanted && NF >= 5 {
      valid=1
      for (field=2; field<=5; field++) {
        if ($field == "<unknown>") valid=0
      }
      if (valid) found=1
    }
    END {exit !found}
  ' || { echo "Metrics API has no complete usage for node: $node" >&2; exit 1; }
done

if [[ $exact_gpu_enabled == true ]]; then
  [[ ${TRAINING_K8S_CLIENT_MODE:-} == kubectl ]] \
    || { echo "exact GPU selection requires the kubectl client" >&2; exit 1; }
  device_class="${TRAINING_K8S_GPU_DEVICE_CLASS_NAME:-gpu.nvidia.com}"
  [[ $("${kube[@]}" auth can-i get deviceclasses.resource.k8s.io) == yes \
    && $("${kube[@]}" auth can-i list resourceslices.resource.k8s.io) == yes ]] \
    || { echo "restricted backend identity cannot inspect DRA devices" >&2; exit 1; }
  "${kube[@]}" get deviceclass "$device_class" >/dev/null \
    || { echo "configured DRA DeviceClass is unavailable: $device_class" >&2; exit 1; }
  dra_slices=$("${kube[@]}" get resourceslices.resource.k8s.io -o json)
  grep -Eq '"driver"[[:space:]]*:[[:space:]]*"gpu\.nvidia\.com"' <<<"$dra_slices" \
    || { echo "NVIDIA DRA ResourceSlice is unavailable" >&2; exit 1; }
  grep -Eq '"gpu\.nvidia\.com/uuid"[[:space:]]*:' <<<"$dra_slices" \
    || { echo "NVIDIA DRA ResourceSlice has no GPU UUID attribute" >&2; exit 1; }

  cat <<YAML | "${kube[@]}" create --dry-run=server -f - >/dev/null
apiVersion: resource.k8s.io/v1
kind: ResourceClaimTemplate
metadata:
  name: tss-dra-permission-probe
  namespace: ${namespace}
spec:
  spec:
    devices:
      requests:
        - name: gpu
          exactly:
            deviceClassName: ${device_class}
            selectors:
              - cel:
                  expression: "device.attributes['gpu.nvidia.com'].type == 'gpu'"
YAML
fi

cat <<'YAML' | "${kube[@]}" create --dry-run=server -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata:
  name: tss-c5-permission-probe
  namespace: tss-training
spec:
  template:
    spec:
      serviceAccountName: tss-training-worker
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: probe
          image: registry.k8s.io/pause:3.10.1
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 100m
              memory: 128Mi
YAML
echo "PASS: restricted backend kubeconfig can manage only the required platform resources"
