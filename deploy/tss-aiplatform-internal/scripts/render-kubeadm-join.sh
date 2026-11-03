#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

config_file="${1:-}"
bootstrap_file="${2:-}"
[[ -n $config_file && -f $config_file && -n $bootstrap_file \
  && -f $bootstrap_file && ! -L $bootstrap_file && $# -eq 2 ]] \
  || die "usage: $0 /path/to/worker.env /path/to/bootstrap.env"
load_internal_config "$config_file"
has_role worker || die "kubeadm join rendering requires the worker role"
has_role control-plane && die "a control-plane configuration cannot render a worker join"

bootstrap_permissions="$(stat -c '%a' "$bootstrap_file")"
[[ $bootstrap_permissions == 400 || $bootstrap_permissions == 600 ]] \
  || die "bootstrap file permissions must be 0400 or 0600"

TSS_BOOTSTRAP_TOKEN=''
TSS_CA_CERT_HASH=''
while IFS= read -r line || [[ -n $line ]]; do
  [[ -z $line || $line == \#* ]] && continue
  case "$line" in
    TSS_BOOTSTRAP_TOKEN=*)
      [[ -z $TSS_BOOTSTRAP_TOKEN ]] || die "duplicate bootstrap token setting"
      TSS_BOOTSTRAP_TOKEN="${line#*=}"
      ;;
    TSS_CA_CERT_HASH=*)
      [[ -z $TSS_CA_CERT_HASH ]] || die "duplicate CA certificate hash setting"
      TSS_CA_CERT_HASH="${line#*=}"
      ;;
    *)
      die "unknown or malformed bootstrap setting"
      ;;
  esac
done <"$bootstrap_file"

[[ $TSS_BOOTSTRAP_TOKEN =~ ^[a-z0-9]{6}\.[a-z0-9]{16}$ ]] \
  || die "bootstrap token has an invalid kubeadm format"
[[ $TSS_CA_CERT_HASH =~ ^sha256:[0-9a-f]{64}$ ]] \
  || die "CA certificate hash has an invalid kubeadm format"

cat <<EOF
apiVersion: kubeadm.k8s.io/${TSS_KUBEADM_API_VERSION}
kind: JoinConfiguration
discovery:
  bootstrapToken:
    apiServerEndpoint: ${TSS_CONTROL_PLANE_ENDPOINT}
    token: ${TSS_BOOTSTRAP_TOKEN}
    caCertHashes:
      - ${TSS_CA_CERT_HASH}
  tlsBootstrapToken: ${TSS_BOOTSTRAP_TOKEN}
nodeRegistration:
  name: ${TSS_NODE_NAME}
  criSocket: unix://${TSS_CONTAINERD_SOCKET}
  imagePullPolicy: IfNotPresent
  imagePullSerial: true
  taints:
    - key: tss.ai/staging
      value: "true"
      effect: NoSchedule
  kubeletExtraArgs:
    - name: node-ip
      value: ${TSS_NODE_IP}
    - name: root-dir
      value: ${TSS_KUBELET_ROOT}
EOF
