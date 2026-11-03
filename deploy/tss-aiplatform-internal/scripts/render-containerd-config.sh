#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"
load_internal_config "${1:-}"

render_runtimes_v2() {
  cat <<EOF
[plugins."io.containerd.grpc.v1.cri".containerd]
  snapshotter = "overlayfs"
  default_runtime_name = "runc"

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc]
  runtime_type = "io.containerd.runc.v2"

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc.options]
  SystemdCgroup = true
EOF
  if [[ $TSS_ENABLE_NVIDIA_RUNTIME == true ]]; then
    cat <<'EOF'

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.nvidia]
  runtime_type = "io.containerd.runc.v2"

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.nvidia.options]
  BinaryName = "/usr/bin/nvidia-container-runtime"
  SystemdCgroup = true
EOF
  fi
}

render_runtimes_v3() {
  cat <<EOF
[plugins.'io.containerd.cri.v1.images']
  snapshotter = 'overlayfs'

[plugins.'io.containerd.cri.v1.images'.pinned_images]
  sandbox = '${TSS_CONTAINERD_PAUSE_IMAGE}'

[plugins.'io.containerd.cri.v1.runtime'.containerd]
  default_runtime_name = 'runc'

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runc]
  runtime_type = 'io.containerd.runc.v2'

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runc.options]
  SystemdCgroup = true
EOF
  if [[ $TSS_ENABLE_NVIDIA_RUNTIME == true ]]; then
    cat <<'EOF'

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.nvidia]
  runtime_type = 'io.containerd.runc.v2'

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.nvidia.options]
  BinaryName = '/usr/bin/nvidia-container-runtime'
  SystemdCgroup = true
EOF
  fi
}

cat <<EOF
# Generated for ${TSS_NODE_NAME}. Do not install into /etc/containerd/config.toml.
version = ${TSS_CONTAINERD_CONFIG_VERSION}
root = "${TSS_CONTAINERD_ROOT}"
state = "${TSS_CONTAINERD_STATE_DIR}"

[grpc]
  address = "${TSS_CONTAINERD_SOCKET}"
EOF

if [[ $TSS_CONTAINERD_CONFIG_VERSION == 2 ]]; then
  cat <<EOF

[plugins."io.containerd.grpc.v1.cri"]
  sandbox_image = "${TSS_CONTAINERD_PAUSE_IMAGE}"
EOF
  render_runtimes_v2
else
  render_runtimes_v3
fi
