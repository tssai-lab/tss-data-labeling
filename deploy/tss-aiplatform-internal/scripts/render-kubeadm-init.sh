#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"
load_internal_config "${1:-}"
has_role control-plane || die "kubeadm init rendering requires the control-plane role"
endpoint_host="${TSS_CONTROL_PLANE_ENDPOINT%:*}"

cat <<EOF
apiVersion: kubeadm.k8s.io/${TSS_KUBEADM_API_VERSION}
kind: InitConfiguration
localAPIEndpoint:
  advertiseAddress: ${TSS_NODE_IP}
  bindPort: 6443
nodeRegistration:
  name: ${TSS_NODE_NAME}
  criSocket: unix://${TSS_CONTAINERD_SOCKET}
  kubeletExtraArgs:
    - name: node-ip
      value: ${TSS_NODE_IP}
    - name: root-dir
      value: ${TSS_KUBELET_ROOT}
---
apiVersion: kubeadm.k8s.io/${TSS_KUBEADM_API_VERSION}
kind: ClusterConfiguration
clusterName: ${TSS_CLUSTER_ID}
kubernetesVersion: ${TSS_KUBERNETES_VERSION}
controlPlaneEndpoint: "${TSS_CONTROL_PLANE_ENDPOINT}"
apiServer:
  certSANs:
    - ${TSS_NODE_IP}
EOF
if [[ $endpoint_host != "$TSS_NODE_IP" ]]; then
  printf '    - %s\n' "$endpoint_host"
fi
cat <<EOF
etcd:
  local:
    dataDir: ${TSS_ETCD_DATA_DIR}
networking:
  dnsDomain: cluster.local
  podSubnet: ${TSS_POD_CIDR}
  serviceSubnet: ${TSS_SERVICE_CIDR}
---
apiVersion: kubelet.config.k8s.io/${TSS_KUBELET_CONFIG_API_VERSION}
kind: KubeletConfiguration
cgroupDriver: systemd
failSwapOn: false
memorySwap:
  swapBehavior: NoSwap
EOF
