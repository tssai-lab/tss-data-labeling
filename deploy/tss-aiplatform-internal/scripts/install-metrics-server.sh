#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

node_metrics_complete() {
  local wanted="$1"
  awk -v wanted="$wanted" '
    $1 == wanted && NF >= 5 {
      valid=1
      for (field=2; field<=5; field++) {
        if ($field == "<unknown>") valid=0
      }
      if (valid) found=1
    }
    END {exit !found}
  '
}

usage() {
  echo "usage: $0 --self-test" >&2
  echo "usage: $0 --check /path/to/node.env" >&2
  echo "       $0 --apply /path/to/node.env --confirm-node NODE" >&2
  exit 2
}

mode="${1:-}"
if [[ $mode == --self-test ]]; then
  printf '%s\n' 'node-a 25m 1% 512Mi 4%' \
    | node_metrics_complete node-a \
    || die "complete node metrics must pass"
  if printf '%s\n' 'node-a <unknown> <unknown> <unknown> <unknown>' \
    | node_metrics_complete node-a; then
    die "unknown node metrics must fail"
  fi
  if printf '%s\n' 'node-b 25m 1% 512Mi 4%' \
    | node_metrics_complete node-a; then
    die "a missing node metric line must fail"
  fi
  echo "Metrics Server node-usage parser self-test passed"
  exit 0
fi
config_file="${2:-}"
confirmation_flag="${3:-}"
confirmation_node="${4:-}"
[[ $mode == --check || $mode == --apply ]] || usage
[[ -n $config_file && -f $config_file ]] || usage
if [[ $mode == --check ]]; then
  [[ -z $confirmation_flag && -z $confirmation_node ]] || usage
else
  [[ $confirmation_flag == --confirm-node && -n $confirmation_node ]] || usage
fi

load_internal_config "$config_file"
[[ $EUID -eq 0 ]] || die "Metrics Server installation must run as root"
has_role control-plane || die "Metrics Server may be installed only from the control plane"
if [[ $mode == --apply ]]; then
  [[ $confirmation_node == "$TSS_NODE_NAME" ]] \
    || die "confirmation node does not match the reviewed configuration"
fi

KUBECTL="${KUBECTL:-kubectl}"
KUBECONFIG_PATH="${KUBECONFIG:-/etc/kubernetes/admin.conf}"
command -v "$KUBECTL" >/dev/null 2>&1 || [[ -x $KUBECTL ]] \
  || die "kubectl is not executable: $KUBECTL"
[[ -r $KUBECONFIG_PATH ]] || die "admin kubeconfig is not readable: $KUBECONFIG_PATH"
for command_name in awk ctr flock grep mktemp seq sha256sum sleep sort systemctl; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done
systemctl is-active --quiet tss-aiplatform-containerd.service \
  || die "isolated project containerd is not active"
[[ -S $TSS_CONTAINERD_SOCKET ]] || die "isolated project containerd socket is absent"
bash "${script_dir}/verify-storage.sh" "$config_file" >/dev/null

source_manifest="${internal_root}/manifests/metrics-server-components.yaml"
[[ -f $source_manifest && ! -L $source_manifest ]] \
  || die "versioned Metrics Server manifest is absent"
metrics_url="https://github.com/kubernetes-sigs/metrics-server/releases/download/${TSS_METRICS_SERVER_VERSION}/components.yaml"
expected_manifest_sha="$(awk -v url="$metrics_url" \
  '$1 == "manifest" && $2 == url {sub(/^sha256:/, "", $3); print $3}' \
  "${internal_root}/artifacts.lock")"
[[ $expected_manifest_sha =~ ^[0-9a-f]{64}$ ]] \
  || die "Metrics Server manifest checksum is absent from artifacts.lock"
[[ $(sha256sum "$source_manifest" | awk '{print $1}') == "$expected_manifest_sha" ]] \
  || die "versioned Metrics Server manifest differs from artifacts.lock"

expected_image="registry.k8s.io/metrics-server/metrics-server:${TSS_METRICS_SERVER_VERSION}"
expected_image_digest="$(awk -v image="$expected_image" \
  '$1 == "image" && $2 == image {print $3}' "${internal_root}/artifacts.lock")"
[[ $expected_image_digest =~ ^sha256:[0-9a-f]{64}$ ]] \
  || die "Metrics Server image digest is absent from artifacts.lock"
[[ $(grep -Fc "        image: ${expected_image}" "$source_manifest") -eq 1 ]] \
  || die "Metrics Server manifest image differs from the version contract"
[[ $(grep -Fc '        - --metric-resolution=15s' "$source_manifest") -eq 1 ]] \
  || die "Metrics Server manifest sampling anchor differs"
[[ $(grep -Fc '        imagePullPolicy: IfNotPresent' "$source_manifest") -eq 1 ]] \
  || die "Metrics Server manifest pull-policy anchor differs"
[[ $(grep -Fc '        kubernetes.io/os: linux' "$source_manifest") -eq 1 ]] \
  || die "Metrics Server manifest node-selector anchor differs"
[[ $(grep -Fc '      priorityClassName: system-cluster-critical' "$source_manifest") -eq 1 ]] \
  || die "Metrics Server manifest priority-class anchor differs"
if grep -F -- '--kubelet-insecure-tls' "$source_manifest" >/dev/null; then
  die "the locked upstream manifest unexpectedly contains the local TLS override"
fi
ctr --address "$TSS_CONTAINERD_SOCKET" --namespace k8s.io images list -q \
  | grep -Fx "$expected_image" >/dev/null \
  || die "locked Metrics Server image is absent from the project containerd"

rendered_manifest="$(mktemp)"
trap 'rm -f "$rendered_manifest"' EXIT
awk '
  $0 == "        - --metric-resolution=15s" {
    print
    print "        - --kubelet-insecure-tls"
    next
  }
  $0 == "        imagePullPolicy: IfNotPresent" {
    print "        imagePullPolicy: Never"
    next
  }
  $0 == "        kubernetes.io/os: linux" {
    print
    print "        node-role.kubernetes.io/control-plane: \"\""
    next
  }
  $0 == "      priorityClassName: system-cluster-critical" {
    print "      tolerations:"
    print "      - effect: NoSchedule"
    print "        key: node-role.kubernetes.io/control-plane"
    print "        operator: Exists"
    print
    next
  }
  { print }
' "$source_manifest" >"$rendered_manifest"
[[ $(grep -Fc '        - --kubelet-insecure-tls' "$rendered_manifest") -eq 1 \
  && $(grep -Fc '        imagePullPolicy: Never' "$rendered_manifest") -eq 1 \
  && $(grep -Fc '        node-role.kubernetes.io/control-plane: ""' "$rendered_manifest") -eq 1 \
  && $(grep -Fc '        key: node-role.kubernetes.io/control-plane' "$rendered_manifest") -eq 1 ]] \
  || die "rendered Metrics Server manifest does not contain the reviewed offline overrides"

admin=("$KUBECTL" --kubeconfig "$KUBECONFIG_PATH" --request-timeout=15s)
api_server="$("${admin[@]}" config view --raw --minify -o jsonpath='{.clusters[0].cluster.server}')"
[[ $api_server == "https://${TSS_CONTROL_PLANE_ENDPOINT}" ]] \
  || die "admin kubeconfig points to an unexpected API server: $api_server"
mapfile -t nodes < <("${admin[@]}" get nodes \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort)
[[ ${#nodes[@]} -eq 2 && " ${nodes[*]} " == *" ${TSS_NODE_NAME} "* ]] \
  || die "Metrics Server target is not the reviewed two-node internal cluster"
[[ " ${nodes[*]} " != *" k8s-master "* && " ${nodes[*]} " != *" k8s-node1 "* ]] \
  || die "refusing a kubeconfig that resembles the Main/Second cluster"
existing_image="$("${admin[@]}" -n kube-system get deploy metrics-server \
  -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
[[ -z $existing_image || $existing_image == "$expected_image" ]] \
  || die "an unmanaged Metrics Server image already exists: $existing_image"
"${admin[@]}" apply --dry-run=server -f "$rendered_manifest" >/dev/null

if [[ $mode == --check ]]; then
  echo "PASS: Metrics Server artifact, image, cluster identity and server dry-run passed without writes"
  exit 0
fi

exec 9>/run/lock/tss-aiplatform-metrics-server.lock
flock -n 9 || die "another Metrics Server installation is running"
"${admin[@]}" apply -f "$rendered_manifest" >/dev/null
if ! "${admin[@]}" -n kube-system rollout status deployment/metrics-server --timeout=180s; then
  "${admin[@]}" -n kube-system get deployment,pod -l k8s-app=metrics-server -o wide >&2 || true
  "${admin[@]}" -n kube-system logs deployment/metrics-server --tail=80 >&2 || true
  die "Metrics Server rollout failed; resources were preserved for diagnosis"
fi
"${admin[@]}" wait --for=condition=Available \
  apiservice/v1beta1.metrics.k8s.io --timeout=120s >/dev/null
metrics_node="$("${admin[@]}" -n kube-system get pods \
  -l k8s-app=metrics-server --field-selector=status.phase=Running \
  -o jsonpath='{.items[0].spec.nodeName}')"
[[ -n $metrics_node ]] || die "Metrics Server has no running pod"
"${admin[@]}" get node "$metrics_node" \
  -l node-role.kubernetes.io/control-plane -o name | grep -q . \
  || die "Metrics Server is not running on a control-plane node"

top_output=''
for _attempt in $(seq 1 30); do
  if top_output="$("${admin[@]}" top nodes --no-headers 2>/dev/null)"; then
    missing=false
    for node in "${nodes[@]}"; do
      if ! printf '%s\n' "$top_output" | node_metrics_complete "$node"; then
        missing=true
        break
      fi
    done
    [[ $missing == false ]] && break
  fi
  top_output=''
  sleep 5
done
[[ -n $top_output ]] || die "Metrics API did not return every reviewed node"
echo "PASS: Metrics Server is Ready and reports all reviewed nodes"
