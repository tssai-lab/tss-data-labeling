#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
internal_dir="${root_dir}/deploy/tss-aiplatform-internal"
# shellcheck disable=SC1091
source "${internal_dir}/versions.env"
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

uuid=11111111-2222-3333-4444-555555555555
# Keep explicit generated fixtures here as well as committed test fixtures: the
# generated copies exercise the exact values used by negative mutation tests.
cat >"${workdir}/control.env" <<EOF
TSS_CLUSTER_ID=tss-aiplatform-internal
TSS_NODE_NAME=tss-ai-control-01
TSS_NODE_ROLES=control-plane,platform,storage,gpu
TSS_NODE_IP=192.0.2.10
TSS_CONTROL_PLANE_ENDPOINT=192.0.2.10:6443
TSS_ADDRESS_STABILITY_CONFIRMED=true
TSS_CONTAINERD_CONFIG_VERSION=3
TSS_CONTAINERD_ROOT=/srv/tss-AIplatform/containerd
TSS_CONTAINERD_STATE_DIR=/run/tss-aiplatform/containerd
TSS_CONTAINERD_SOCKET=/run/tss-aiplatform/containerd/containerd.sock
TSS_ENABLE_NVIDIA_RUNTIME=true
TSS_STORAGE_MOUNT_POINT=/srv/tss-AIplatform
TSS_EXPECTED_STORAGE_UUID=${uuid}
TSS_PROJECT_ROOT=/srv/tss-AIplatform
TSS_KUBELET_ROOT=/srv/tss-AIplatform/kubelet
TSS_MIN_STORAGE_FREE_GIB=1024
TSS_MIN_ROOT_FREE_GIB=20
TSS_ETCD_DATA_DIR=/var/lib/tss-aiplatform/etcd
TSS_POD_CIDR=10.245.0.0/16
TSS_SERVICE_CIDR=10.97.0.0/16
EOF

cat >"${workdir}/worker.env" <<EOF
TSS_CLUSTER_ID=tss-aiplatform-internal
TSS_NODE_NAME=tss-ai-worker-01
TSS_NODE_ROLES=worker,cpu,gpu
TSS_NODE_IP=192.0.2.20
TSS_CONTROL_PLANE_ENDPOINT=192.0.2.10:6443
TSS_ADDRESS_STABILITY_CONFIRMED=true
TSS_CONTAINERD_CONFIG_VERSION=2
TSS_CONTAINERD_ROOT=/media/seu/data/tss-AIplatform/containerd
TSS_CONTAINERD_STATE_DIR=/run/tss-aiplatform/containerd
TSS_CONTAINERD_SOCKET=/run/tss-aiplatform/containerd/containerd.sock
TSS_ENABLE_NVIDIA_RUNTIME=true
TSS_STORAGE_MOUNT_POINT=/media/seu/data
TSS_EXPECTED_STORAGE_UUID=${uuid}
TSS_PROJECT_ROOT=/media/seu/data/tss-AIplatform
TSS_KUBELET_ROOT=/media/seu/data/tss-AIplatform/kubelet
TSS_MIN_STORAGE_FREE_GIB=1024
TSS_MIN_ROOT_FREE_GIB=100
TSS_POD_CIDR=10.245.0.0/16
TSS_SERVICE_CIDR=10.97.0.0/16
EOF

bash "${internal_dir}/scripts/preflight.sh" --config-only "${workdir}/control.env" >/dev/null
bash "${internal_dir}/scripts/preflight.sh" --config-only "${workdir}/worker.env" >/dev/null
bash "${internal_dir}/scripts/prepare-node.sh" --config-only "${workdir}/control.env" >/dev/null
bash "${internal_dir}/scripts/prepare-node.sh" --config-only "${workdir}/worker.env" >/dev/null
bash "${internal_dir}/scripts/import-airgap-bundles.sh" --config-only \
  "${workdir}/control.env" >/dev/null
bash "${internal_dir}/scripts/import-airgap-bundles.sh" --config-only \
  "${workdir}/worker.env" >/dev/null
bash "${internal_dir}/scripts/import-cpu-runtime-images.sh" --config-only \
  "${workdir}/worker.env" >/dev/null
bash "${internal_dir}/scripts/prepare-control-plane-network.sh" --config-only \
  "${workdir}/control.env" 192.0.2.20 >/dev/null
bash "${internal_dir}/scripts/render-calico-vxlan.sh" --self-test >/dev/null

if bash "${internal_dir}/scripts/prepare-control-plane-network.sh" --config-only \
  "${workdir}/worker.env" 192.0.2.10 >/dev/null 2>&1; then
  echo "Worker configuration must not prepare the control-plane firewall." >&2
  exit 1
fi
if bash "${internal_dir}/scripts/prepare-control-plane-network.sh" --config-only \
  "${workdir}/control.env" 192.0.2.10 >/dev/null 2>&1; then
  echo "Control-plane and worker addresses must differ." >&2
  exit 1
fi

bash "${internal_dir}/scripts/render-containerd-config.sh" "${workdir}/control.env" >"${workdir}/containerd-v3.toml"
grep -F 'version = 3' "${workdir}/containerd-v3.toml" >/dev/null
grep -F "[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runc.options]" "${workdir}/containerd-v3.toml" >/dev/null
grep -F "sandbox = 'registry.k8s.io/pause:3.10.1'" "${workdir}/containerd-v3.toml" >/dev/null
grep -F "[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.nvidia.options]" \
  "${workdir}/containerd-v3.toml" >/dev/null
grep -F "BinaryName = '/usr/bin/nvidia-container-runtime'" \
  "${workdir}/containerd-v3.toml" >/dev/null

bash "${internal_dir}/scripts/render-containerd-config.sh" "${workdir}/worker.env" >"${workdir}/containerd-v2.toml"
grep -F 'version = 2' "${workdir}/containerd-v2.toml" >/dev/null
grep -F '[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc.options]' "${workdir}/containerd-v2.toml" >/dev/null
grep -F '[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.nvidia.options]' "${workdir}/containerd-v2.toml" >/dev/null
grep -F 'default_runtime_name = "runc"' "${workdir}/containerd-v2.toml" >/dev/null
grep -F 'BinaryName = "/usr/bin/nvidia-container-runtime"' "${workdir}/containerd-v2.toml" >/dev/null

if grep -F '/var/lib/containerd' "${workdir}/containerd-v2.toml" "${workdir}/containerd-v3.toml" >/dev/null; then
  echo "Generated project runtime must not use the system containerd root." >&2
  exit 1
fi
if grep -F '/run/containerd/containerd.sock' "${workdir}/containerd-v2.toml" "${workdir}/containerd-v3.toml" >/dev/null; then
  echo "Generated project runtime must not use the system containerd socket." >&2
  exit 1
fi

bash "${internal_dir}/scripts/render-kubeadm-init.sh" "${workdir}/control.env" >"${workdir}/kubeadm.yaml"
for expected in \
  'apiVersion: kubeadm.k8s.io/v1beta4' \
  'name: tss-ai-control-01' \
  'criSocket: unix:///run/tss-aiplatform/containerd/containerd.sock' \
  'value: /srv/tss-AIplatform/kubelet' \
  'kubernetesVersion: v1.35.7' \
  'controlPlaneEndpoint: "192.0.2.10:6443"' \
  'dataDir: /var/lib/tss-aiplatform/etcd' \
  'podSubnet: 10.245.0.0/16' \
  'serviceSubnet: 10.97.0.0/16' \
  'failSwapOn: false' \
  'swapBehavior: NoSwap'; do
  grep -F "$expected" "${workdir}/kubeadm.yaml" >/dev/null
done

cat >"${workdir}/bootstrap.env" <<'EOF'
TSS_BOOTSTRAP_TOKEN=abcdef.0123456789abcdef
TSS_CA_CERT_HASH=sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
EOF
chmod 0600 "${workdir}/bootstrap.env"
bash "${internal_dir}/scripts/render-kubeadm-join.sh" \
  "${workdir}/worker.env" "${workdir}/bootstrap.env" >"${workdir}/kubeadm-join.yaml"
for expected in \
  'apiVersion: kubeadm.k8s.io/v1beta4' \
  'apiServerEndpoint: 192.0.2.10:6443' \
  'token: abcdef.0123456789abcdef' \
  'name: tss-ai-worker-01' \
  'criSocket: unix:///run/tss-aiplatform/containerd/containerd.sock' \
  'key: tss.ai/staging' \
  'effect: NoSchedule' \
  'value: 192.0.2.20' \
  'value: /media/seu/data/tss-AIplatform/kubelet'; do
  grep -F "$expected" "${workdir}/kubeadm-join.yaml" >/dev/null
done
chmod 0644 "${workdir}/bootstrap.env"
if bash "${internal_dir}/scripts/render-kubeadm-join.sh" \
  "${workdir}/worker.env" "${workdir}/bootstrap.env" >/dev/null 2>&1; then
  echo "World-readable bootstrap material must be rejected." >&2
  exit 1
fi
chmod 0600 "${workdir}/bootstrap.env"
sed -i 's/abcdef\.0123456789abcdef/invalid-token/' "${workdir}/bootstrap.env"
if bash "${internal_dir}/scripts/render-kubeadm-join.sh" \
  "${workdir}/worker.env" "${workdir}/bootstrap.env" >/dev/null 2>&1; then
  echo "Malformed bootstrap tokens must be rejected." >&2
  exit 1
fi

bash "${internal_dir}/scripts/render-containerd-unit.sh" "${workdir}/control.env" >"${workdir}/containerd.service"
grep -F 'RequiresMountsFor=/srv/tss-AIplatform' "${workdir}/containerd.service" >/dev/null
grep -F '/usr/local/lib/tss-aiplatform-internal/scripts/verify-storage.sh' "${workdir}/containerd.service" >/dev/null
grep -F 'ExecStartPre=/usr/bin/install -d -m 0755 /run/tss-aiplatform/containerd' \
  "${workdir}/containerd.service" >/dev/null

cp "${workdir}/worker.env" "${workdir}/invalid.env"
sed -i 's#TSS_CONTAINERD_ROOT=.*#TSS_CONTAINERD_ROOT=/var/lib/containerd#' "${workdir}/invalid.env"
if bash "${internal_dir}/scripts/preflight.sh" --config-only "${workdir}/invalid.env" >/dev/null 2>&1; then
  echo "System containerd root must be rejected." >&2
  exit 1
fi

cp "${workdir}/worker.env" "${workdir}/invalid-socket.env"
sed -i 's#TSS_CONTAINERD_SOCKET=.*#TSS_CONTAINERD_SOCKET=/run/containerd/containerd.sock#' "${workdir}/invalid-socket.env"
if bash "${internal_dir}/scripts/preflight.sh" --config-only "${workdir}/invalid-socket.env" >/dev/null 2>&1; then
  echo "System containerd socket must be rejected." >&2
  exit 1
fi

classifier="${internal_dir}/ci/classify-backend-deploy-scope.sh"
[[ $(printf '%s\n' 'deploy/tss-aiplatform-internal/README.md' | bash "$classifier") == deploy_main=false ]]
[[ $(printf '%s\n' '.github/workflows/tss-aiplatform-internal-validation.yml' | bash "$classifier") == deploy_main=false ]]
[[ $(printf '%s\n' 'backend/src/main/java/example.java' | bash "$classifier") == deploy_main=true ]]
[[ $(printf '%s\n' 'k8s/inference-worker/worker.py' | bash "$classifier") == deploy_main=true ]]
[[ $(printf '%s\n' 'deploy/tss-aiplatform-internal/README.md' 'backend/pom.xml' | bash "$classifier") == deploy_main=true ]]

backend_workflow="${root_dir}/.github/workflows/backend-ci.yml"
internal_workflow="${root_dir}/.github/workflows/tss-aiplatform-internal-validation.yml"
internal_compose="${internal_dir}/platform/compose.yml"
! grep -F '!deploy/tss-aiplatform-internal/**' "$backend_workflow" >/dev/null
grep -F "needs.verify-and-build.outputs.deploy_main == 'true'" "$backend_workflow" >/dev/null
grep -F "steps.scope.outputs.deploy_main == 'true'" "$backend_workflow" >/dev/null
grep -F 'github.event_name }}" == "workflow_dispatch"' "$backend_workflow" >/dev/null
grep -F 'bash deploy/tss-aiplatform-internal/tests/test-cluster-copy.sh' "$internal_workflow" >/dev/null
grep -F 'environment: tss-aiplatform-internal' "$internal_workflow" >/dev/null
grep -F 'TRAINING_K8S_CLIENT_MODE: ${TSS_K8S_CLIENT_MODE:-fabric8}' "$internal_compose" >/dev/null
grep -F 'TRAINING_K8S_EXACT_GPU_SELECTION_ENABLED:' "$internal_compose" >/dev/null
grep -F 'INFERENCE_KUBERNETES_GPU_WORKER_IMAGE:' "$internal_compose" >/dev/null
grep -F 'inputs.task == '\''runner-smoke'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''resolve-artifact-lock'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''export-airgap-bundles'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''export-platform-images'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''export-backend-image'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''export-cpu-runtime-images'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''export-gpu-runtime-images'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''stage-airgap-bundles'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''stage-platform-images'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''stage-cpu-runtime-images'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''stage-gpu-runtime-images'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''deploy-cpu-runtime-images'\''' "$internal_workflow" >/dev/null
grep -F 'inputs.task == '\''deploy-gpu-runtime-images'\''' "$internal_workflow" >/dev/null
grep -F 'fetch-depth: 0' "$internal_workflow" >/dev/null
grep -F 'actions: read' "$internal_workflow" >/dev/null
grep -F 'packages: read' "$internal_workflow" >/dev/null
grep -F 'download-airgap-artifact.py' "$internal_workflow" >/dev/null
grep -F -- '--backend-only' "$internal_workflow" >/dev/null
grep -F -- '--profile cpu-runtime' "$internal_workflow" >/dev/null
grep -F -- '--profile gpu-runtime' "$internal_workflow" >/dev/null
grep -F -- '--profile platform' "$internal_workflow" >/dev/null
grep -F 'git merge-base --is-ancestor "$RUNTIME_HEAD_SHA" "$GITHUB_SHA"' \
  "$internal_workflow" >/dev/null
[[ $(grep -Fc '[[ "$GITHUB_REF_NAME" == backend-ops || "$GITHUB_REF_NAME" == backend-gpu ]]' \
  "$internal_workflow") -ge 4 ]]
grep -F 'exported-cpu-runtime-images.lock' "$internal_workflow" >/dev/null
grep -F 'exported-platform-images.lock' "$internal_workflow" >/dev/null
grep -F -- '--workers 16' "$internal_workflow" >/dev/null
grep -F 'AIRGAP_STAGE_ROOT: ${{ vars.AIRGAP_STAGE_ROOT }}' "$internal_workflow" >/dev/null
grep -F 'untrusted export workflow metadata' "$internal_workflow" >/dev/null
grep -F 'test ! -L "$target_dir"' "$internal_workflow" >/dev/null
grep -F 'sha256sum --check --strict airgap-common.sha256' "$internal_workflow" >/dev/null
grep -F 'sudo, containerd import and cluster writes: not attempted' "$internal_workflow" >/dev/null
grep -F 'persist-credentials: false' "$internal_workflow" >/dev/null
grep -F "github.event_name == 'workflow_dispatch' && inputs.task || github.event_name" \
  "$internal_workflow" >/dev/null
grep -F 'RUNNER_WORK_ROOT: ${{ vars.RUNNER_WORK_ROOT }}' "$internal_workflow" >/dev/null
grep -F 'GITHUB_WORKSPACE" == "${runner_work_root}/"*' "$internal_workflow" >/dev/null
grep -F 'runs-on: [self-hosted, Linux, X64, tss-aiplatform-internal, deploy]' \
  "$internal_workflow" >/dev/null
! grep -F 'seu4080' "$internal_workflow" >/dev/null
grep -F 'CONTROL_PLANE_HOST" =~ ^[0-9]' "$internal_workflow" >/dev/null
grep -F 'SSH authentication, sudo, deployment and host writes: not attempted' "$internal_workflow" >/dev/null
if grep -F '${{ secrets.' "$internal_workflow" >/dev/null; then
  echo "Internal validation must not consume repository or environment Secrets." >&2
  exit 1
fi
if grep -F ':latest' "${internal_dir}/versions.env" >/dev/null; then
  echo "Internal cluster versions must not use latest tags." >&2
  exit 1
fi
bash "${internal_dir}/ci/resolve-artifact-lock.sh" --validate-only >/dev/null
bash "${internal_dir}/ci/resolve-artifact-lock.sh" --self-test >/dev/null
bash "${internal_dir}/ci/export-airgap-bundles.sh" --validate-only >/dev/null
bash "${internal_dir}/ci/export-cpu-runtime-images.sh" --validate-only >/dev/null
bash "${internal_dir}/ci/export-gpu-runtime-images.sh" --validate-only >/dev/null
python3 "${internal_dir}/ci/download-airgap-artifact.py" --self-test >/dev/null
grep -F '"max_bytes": 10 * 1024**3' \
  "${internal_dir}/ci/download-airgap-artifact.py" >/dev/null
grep -F 'resume_files=' "${internal_dir}/ci/download-airgap-artifact.py" >/dev/null
bash "${internal_dir}/scripts/prepare-storage.sh" --config-only \
  "${internal_dir}/config/seu5090-storage.env" >/dev/null

cp "${internal_dir}/config/seu5090-storage.env" "${workdir}/alternate-storage.env"
sed -i 's#TSS_STORAGE_MOUNT_POINT=.*#TSS_STORAGE_MOUNT_POINT=/data/tss-AIplatform#' \
  "${workdir}/alternate-storage.env"
bash "${internal_dir}/scripts/prepare-storage.sh" --config-only \
  "${workdir}/alternate-storage.env" >/dev/null

cp "${internal_dir}/config/seu5090-storage.env" "${workdir}/invalid-storage.env"
sed -i 's#TSS_STORAGE_MOUNT_POINT=.*#TSS_STORAGE_MOUNT_POINT=/etc/tss-AIplatform#' \
  "${workdir}/invalid-storage.env"
if bash "${internal_dir}/scripts/prepare-storage.sh" --config-only \
  "${workdir}/invalid-storage.env" >/dev/null 2>&1; then
  echo "Storage configuration must reject an operating-system mount path." >&2
  exit 1
fi

cp "${internal_dir}/config/seu5090-storage.env" "${workdir}/invalid-storage.env"
sed -i 's/TSS_STORAGE_PARTITION_GIB=2048/TSS_STORAGE_PARTITION_GIB=4096/' \
  "${workdir}/invalid-storage.env"
if bash "${internal_dir}/scripts/prepare-storage.sh" --config-only \
  "${workdir}/invalid-storage.env" >/dev/null 2>&1; then
  echo "Storage configuration must reject an unapproved partition size." >&2
  exit 1
fi

cp "${internal_dir}/config/seu5090-storage.env" "${workdir}/invalid-storage.env"
sed -i 's#TSS_STORAGE_DEVICE_BY_ID=.*#TSS_STORAGE_DEVICE_BY_ID=/dev/sda#' \
  "${workdir}/invalid-storage.env"
if bash "${internal_dir}/scripts/prepare-storage.sh" --config-only \
  "${workdir}/invalid-storage.env" >/dev/null 2>&1; then
  echo "Storage configuration must reject unstable kernel device paths." >&2
  exit 1
fi

cp "${internal_dir}/config/seu5090-storage.env" "${workdir}/invalid-storage.env"
sed -i 's/TSS_STORAGE_SMART_POLICY=.*/TSS_STORAGE_SMART_POLICY=skip-all/' \
  "${workdir}/invalid-storage.env"
if bash "${internal_dir}/scripts/prepare-storage.sh" --config-only \
  "${workdir}/invalid-storage.env" >/dev/null 2>&1; then
  echo "Storage configuration must reject an unknown SMART policy." >&2
  exit 1
fi

cp "${internal_dir}/config/seu5090-storage.env" "${workdir}/extended-storage.env"
sed -i 's/TSS_STORAGE_SMART_POLICY=.*/TSS_STORAGE_SMART_POLICY=extended/' \
  "${workdir}/extended-storage.env"
bash "${internal_dir}/scripts/prepare-storage.sh" --config-only \
  "${workdir}/extended-storage.env" >/dev/null

grep -F 'TSS_STORAGE_SMART_POLICY=short-plus-critical-attributes' \
  "${internal_dir}/config/seu5090-storage.env" >/dev/null
grep -F "TSS_STORAGE_SMART_POLICY == extended" \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F "latest_self_test == *'Extended offline'*" \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F "latest_short_test == *'Completed without error'*" \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F 'for attribute_id in 5 187 188 197 198 199' \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F 'a SMART self-test is still running' \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F 'sgdisk --clear' "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F 'leave the remaining disk capacity unallocated' \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
storage_lock_line="$(grep -nF 'exec 9>/run/lock/tss-aiplatform-storage.lock' \
  "${internal_dir}/scripts/prepare-storage.sh" | cut -d: -f1)"
storage_resolve_line="$(grep -nF 'resolved_device="$(readlink -f' \
  "${internal_dir}/scripts/prepare-storage.sh" | cut -d: -f1)"
(( storage_lock_line < storage_resolve_line ))
grep -F 'mount point directory is not empty' \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F 'fstab verification failed; the original file was restored' \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F 'mount failed; the original fstab was restored' \
  "${internal_dir}/scripts/prepare-storage.sh" >/dev/null
grep -F 'configured node address is absent from the host' \
  "${internal_dir}/scripts/preflight.sh" >/dev/null
grep -F 'isolated project containerd is reachable' \
  "${internal_dir}/scripts/preflight.sh" >/dev/null
grep -F 'unexpected_kubernetes_state=' \
  "${internal_dir}/scripts/prepare-node.sh" >/dev/null
grep -F 'actual_storage_uuid=' \
  "${internal_dir}/scripts/prepare-node.sh" >/dev/null
grep -F 'existing project root is not a real directory' \
  "${internal_dir}/scripts/prepare-node.sh" >/dev/null
grep -F 'systemctl stop kubelet' \
  "${internal_dir}/scripts/prepare-node.sh" >/dev/null
grep -F 'shared system containerd PID changed' \
  "${internal_dir}/scripts/prepare-node.sh" >/dev/null
grep -F 'shared Docker container count changed' \
  "${internal_dir}/scripts/prepare-node.sh" >/dev/null
grep -F '$1 ~ /cri/ || $2 ~ /cri/' \
  "${internal_dir}/scripts/prepare-node.sh" >/dev/null
grep -F 'compression-level: 0' "$internal_workflow" >/dev/null
grep -F 'expected 7 Kubernetes core images' \
  "${internal_dir}/ci/export-airgap-bundles.sh" >/dev/null
grep -F 'expected 1 Metrics Server image' \
  "${internal_dir}/ci/export-airgap-bundles.sh" >/dev/null
grep -F 'bundle sources do not match the committed artifact lock' \
  "${internal_dir}/scripts/import-airgap-bundles.sh" >/dev/null
grep -F 'shared system containerd PID changed during image import' \
  "${internal_dir}/scripts/import-airgap-bundles.sh" >/dev/null
grep -F 'CPU runtime bundle sources do not match the committed lock' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null
grep -F 'GPU runtime bundle sources do not match the committed lock' \
  "${internal_dir}/scripts/import-gpu-runtime-images.sh" >/dev/null
for file in \
  "${internal_dir}/scripts/install-cpu-runtime-deployer.sh" \
  "${internal_dir}/scripts/deploy-cpu-runtime.sh" \
  "${internal_dir}/scripts/deploy-gpu-runtime.sh"; do
  [[ -f $file ]]
  bash -n "$file"
done
grep -F 'TSS_INSTALL_ROOT_TO_HARDEN=$install_root harden_project_install_tree' \
  "${internal_dir}/scripts/install-cpu-runtime-deployer.sh" >/dev/null
grep -F 'SUDO_USER:-} == "$TSS_DEPLOYMENT_USER"' \
  "${internal_dir}/scripts/deploy-cpu-runtime.sh" >/dev/null
grep -F 'chown -R root:root "$bundle_dir"' \
  "${internal_dir}/scripts/deploy-cpu-runtime.sh" >/dev/null
grep -F 'sudo -n /usr/local/sbin/tss-aiplatform-internal-deploy-cpu-runtime' \
  "$internal_workflow" >/dev/null
grep -F 'sudo -n /usr/local/sbin/tss-aiplatform-internal-deploy-gpu-runtime' \
  "$internal_workflow" >/dev/null
grep -F 'shared Docker container count changed during CPU runtime import' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null
grep -F 'imported image ID differs from lock' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null
grep -F '!= "$actual_manifest"' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null
[[ $(grep -Fc 'images tag --force \' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh") -eq 1 ]]
grep -F 'runtime image alias points to unexpected content' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null
grep -F '== "$actual_manifest"' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null
if grep -F 'actual_manifest == "$manifest_digest"' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null; then
  echo "Imported Docker archives must use the locked config digest, not the reserialized manifest." >&2
  exit 1
fi
if grep -F '{print; exit}' \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh" >/dev/null; then
  echo "CPU runtime image lookup must consume ctr output under pipefail." >&2
  exit 1
fi
[[ $(grep -Fc "awk -v ref=\"\$source_ref\" '\$1 == ref {print}'" \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh") -eq 1 ]]
[[ $(grep -Fc "awk -v ref=\"\$runtime_ref\" '\$1 == ref {print}'" \
  "${internal_dir}/scripts/import-cpu-runtime-images.sh") -eq 2 ]]
[[ $(grep -Fc 'images tag --force \' \
  "${internal_dir}/scripts/import-gpu-runtime-images.sh") -eq 1 ]]

cpu_runtime_lock="${internal_dir}/reproducible/cpu-runtime-images.lock"
[[ $(grep -Evc '^(#|$)' "$cpu_runtime_lock") -eq 3 ]]
[[ $(awk -F'|' '!/^#/ {print $5}' "$cpu_runtime_lock" | sort) == $'cpu-inference\ncv-training\nnlp-training' ]]
if grep -F ':latest' "$cpu_runtime_lock" >/dev/null; then
  echo "CPU runtime image lock must not use latest tags." >&2
  exit 1
fi
while IFS='|' read -r source_ref manifest_digest image_id runtime_ref purpose producer_run; do
  [[ $source_ref =~ ^ghcr\.io/tssai-lab/[a-z0-9-]+:[0-9a-f]{40}$ ]]
  [[ $manifest_digest =~ ^sha256:[0-9a-f]{64}$ ]]
  [[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]]
  [[ -n $runtime_ref && -n $purpose && $producer_run =~ ^[1-9][0-9]*$ ]]
done < <(grep -Ev '^(#|$)' "$cpu_runtime_lock")

gpu_runtime_lock="${internal_dir}/reproducible/gpu-runtime-images.lock"
gpu_runtime_count=$(grep -Evc '^(#|$)' "$gpu_runtime_lock")
[[ $gpu_runtime_count -eq 2 || $gpu_runtime_count -eq 3 ]]
gpu_runtime_purposes=$(awk -F'|' '!/^#/ {print $5}' "$gpu_runtime_lock" | sort)
if [[ $gpu_runtime_count -eq 3 ]]; then
  [[ $gpu_runtime_purposes == $'cv-gpu-training\ngpu-inference\nnlp-gpu-training' ]]
else
  [[ $gpu_runtime_purposes == $'cv-gpu-training\nnlp-gpu-training' ]]
fi
if grep -F ':latest' "$gpu_runtime_lock" >/dev/null; then
  echo "GPU runtime image lock must not use latest tags." >&2
  exit 1
fi
[[ $(awk -F'|' '!/^#/ {split($1, fields, ":"); print fields[2]}' \
  "$gpu_runtime_lock" | sort -u | wc -l) -eq 1 ]]
while IFS='|' read -r source_ref manifest_digest image_id runtime_ref purpose producer_run; do
  [[ $source_ref =~ ^ghcr\.io/tssai-lab/tss-(cv-worker|nlp-worker|inference-worker-gpu):[0-9a-f]{40}$ ]]
  [[ $manifest_digest =~ ^sha256:[0-9a-f]{64}$ ]]
  [[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]]
  [[ $runtime_ref == *@"$manifest_digest" ]]
  [[ -n $purpose && $producer_run =~ ^[1-9][0-9]*$ ]]
done < <(grep -Ev '^(#|$)' "$gpu_runtime_lock")
grep -F 'purpose == gpu-inference' \
  "${internal_dir}/ci/export-gpu-runtime-images.sh" >/dev/null
grep -F 'purpose == gpu-inference' \
  "${internal_dir}/scripts/import-gpu-runtime-images.sh" >/dev/null
grep -F 'PLAN: do not install the Device Plugin and do not submit a training Job' \
  "${internal_dir}/scripts/import-gpu-runtime-images.sh" >/dev/null
grep -F 'shared Docker container count changed during GPU runtime import' \
  "${internal_dir}/scripts/import-gpu-runtime-images.sh" >/dev/null
grep -F 'TSS_GPU_DEPLOY_STATE_FILE=' \
  "${internal_dir}/scripts/install-cpu-runtime-deployer.sh" >/dev/null
grep -F '/usr/local/sbin/tss-aiplatform-internal-deploy-gpu-runtime' \
  "${internal_dir}/scripts/install-cpu-runtime-deployer.sh" >/dev/null
grep -F "ufw --dry-run allow proto tcp" \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'ufw_user_rules=/etc/ufw/user.rules' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F '### tuple ### allow ${protocol} ${port}' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F '### tuple ### route:allow any any 0.0.0.0/0 any ${TSS_POD_CIDR} in' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'pod_host_rule_present 6443' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'pod_host_rule_present 10250' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'ufw --dry-run route allow from "$TSS_POD_CIDR"' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'from "$TSS_POD_CIDR" to "$TSS_NODE_IP" port 6443' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'from "$TSS_POD_CIDR" to "$TSS_NODE_IP" port 10250' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'from "$worker_ip" to "$TSS_NODE_IP" port 10250' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'from "$worker_ip" to "$TSS_NODE_IP" port 4789' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'confirmation node does not match the reviewed configuration' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'firewall-cmd --get-zone-of-interface="$control_interface"' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F -- '--query-rich-rule="$rule"' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F -- '--add-rich-rule="$rule"' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'source address="%s/32" destination address="%s/32"' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F -- '--zone=trusted' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F -- '--query-source="$TSS_POD_CIDR"' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F -- '--add-source="$TSS_POD_CIDR"' \
  "${internal_dir}/scripts/prepare-control-plane-network.sh" >/dev/null
grep -F 'kubernetes-internal-ip' \
  "${internal_dir}/scripts/render-calico-vxlan.sh" >/dev/null
grep -F 'calico_backend: \"vxlan\"' \
  "${internal_dir}/scripts/render-calico-vxlan.sh" >/dev/null
grep -F 'name: FELIX_NFTABLESMODE' \
  "${internal_dir}/scripts/render-calico-vxlan.sh" >/dev/null
grep -F 'value: \"Disabled\"' \
  "${internal_dir}/scripts/render-calico-vxlan.sh" >/dev/null
grep -F 'name: FELIX_IPTABLESBACKEND' \
  "${internal_dir}/scripts/render-calico-vxlan.sh" >/dev/null
grep -F 'value: \"Legacy\"' \
  "${internal_dir}/scripts/render-calico-vxlan.sh" >/dev/null
grep -Fx 'registry.k8s.io/pause:3.10.1' "${internal_dir}/core-images.txt" >/dev/null
if grep -F ':latest' "${internal_dir}/core-images.txt" >/dev/null; then
  echo "Core images must not use latest tags." >&2
  exit 1
fi

artifact_lock="${internal_dir}/artifacts.lock"
[[ -f $artifact_lock ]]
[[ $(grep -c '^manifest ' "$artifact_lock") -eq 3 ]]
[[ $(grep -c '^image ' "$artifact_lock") -eq 13 ]]
if grep -Ev '^(#.*|manifest https://[^ ]+ sha256:[0-9a-f]{64}|image [^ ]+:[^ ]+ sha256:[0-9a-f]{64})$' \
  "$artifact_lock" >/dev/null; then
  echo "Artifact lock contains an invalid line." >&2
  exit 1
fi
if grep -F ':latest ' "$artifact_lock" >/dev/null; then
  echo "Artifact lock must not use latest tags." >&2
  exit 1
fi
[[ $(awk '$1 == "image" {print $2}' "$artifact_lock" | sort | uniq -d | wc -l) -eq 0 ]]
while IFS= read -r core_image; do
  grep -E "^image ${core_image//./\\.} sha256:[0-9a-f]{64}$" "$artifact_lock" >/dev/null
done < <(grep -Ev '^(#|$)' "${internal_dir}/core-images.txt")
for required_image in \
  "quay.io/calico/cni:${TSS_CALICO_VERSION}" \
  "quay.io/calico/kube-controllers:${TSS_CALICO_VERSION}" \
  "quay.io/calico/node:${TSS_CALICO_VERSION}" \
  "registry.k8s.io/metrics-server/metrics-server:${TSS_METRICS_SERVER_VERSION}" \
  "nvcr.io/nvidia/k8s-device-plugin:${TSS_NVIDIA_DEVICE_PLUGIN_VERSION}" \
  "nvcr.io/nvidia/k8s/dcgm-exporter:${TSS_DCGM_EXPORTER_VERSION}"; do
  grep -E "^image ${required_image//./\\.} sha256:[0-9a-f]{64}$" "$artifact_lock" >/dev/null
done

metrics_manifest="${internal_dir}/manifests/metrics-server-components.yaml"
metrics_installer="${internal_dir}/scripts/install-metrics-server.sh"
[[ -f $metrics_manifest && -f $metrics_installer ]]
metrics_url="https://github.com/kubernetes-sigs/metrics-server/releases/download/${TSS_METRICS_SERVER_VERSION}/components.yaml"
metrics_sha="$(awk -v url="$metrics_url" \
  '$1 == "manifest" && $2 == url {sub(/^sha256:/, "", $3); print $3}' \
  "$artifact_lock")"
[[ $metrics_sha =~ ^[0-9a-f]{64}$ ]]
[[ $(sha256sum "$metrics_manifest" | awk '{print $1}') == "$metrics_sha" ]]
grep -F 'image: registry.k8s.io/metrics-server/metrics-server:v0.9.0' \
  "$metrics_manifest" >/dev/null
grep -F 'imagePullPolicy: Never' "$metrics_installer" >/dev/null
grep -F -- '--kubelet-insecure-tls' "$metrics_installer" >/dev/null
grep -F 'node-role.kubernetes.io/control-plane: ""' "$metrics_installer" >/dev/null
grep -F 'key: node-role.kubernetes.io/control-plane' "$metrics_installer" >/dev/null
grep -F 'Metrics Server is not running on a control-plane node' \
  "$metrics_installer" >/dev/null
grep -F 'apply --dry-run=server' "$metrics_installer" >/dev/null
grep -F 'refusing a kubeconfig that resembles the Main/Second cluster' \
  "$metrics_installer" >/dev/null
grep -F 'top nodes --no-headers' "$metrics_installer" >/dev/null
grep -F 'resources were preserved for diagnosis' "$metrics_installer" >/dev/null
grep -F 'if ($field == "<unknown>") valid=0' "$metrics_installer" >/dev/null
bash "$metrics_installer" --self-test >/dev/null
grep -F 'install-metrics-server.sh' \
  "${internal_dir}/platform/scripts/bootstrap-platform-kubernetes.sh" >/dev/null

nvidia_manifest="${internal_dir}/manifests/nvidia-device-plugin.yml"
gpu_installer="${internal_dir}/platform/scripts/install-gpu-worker.sh"
[[ -f $nvidia_manifest && -f $gpu_installer ]]
nvidia_url="https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/${TSS_NVIDIA_DEVICE_PLUGIN_VERSION}/deployments/static/nvidia-device-plugin.yml"
nvidia_sha="$(awk -v url="$nvidia_url" \
  '$1 == "manifest" && $2 == url {sub(/^sha256:/, "", $3); print $3}' \
  "$artifact_lock")"
[[ $nvidia_sha =~ ^[0-9a-f]{64}$ ]]
[[ $(sha256sum "$nvidia_manifest" | awk '{print $1}') == "$nvidia_sha" ]]
grep -F 'runtimeClassName: nvidia' "$gpu_installer" >/dev/null
grep -F 'node-role.kubernetes.io/control-plane' "$gpu_installer" >/dev/null
grep -F 'GPU control plane must retain its NoSchedule taint' "$gpu_installer" >/dev/null
grep -F 'tss.ai/accelerator: nvidia' "$gpu_installer" >/dev/null
grep -F 'tss.ai/node-pool=cpu' "$gpu_installer" >/dev/null
grep -F 'tss.ai/gpu-schedulable=false' "$gpu_installer" >/dev/null
grep -F 'tss.ai/gpu-schedulable=true' "$gpu_installer" >/dev/null
grep -F 'GPU control plane must remain excluded from GPU scheduling' \
  "$gpu_installer" >/dev/null
grep -F 'imagePullPolicy: Never' "$gpu_installer" >/dev/null
grep -F 'apply --dry-run=server' "$gpu_installer" >/dev/null
grep -F 'refusing a kubeconfig that resembles the Main/Second cluster' \
  "$gpu_installer" >/dev/null
grep -F 'nvidia.com/gpu' "$gpu_installer" >/dev/null
grep -F 'no training Job was submitted' "$gpu_installer" >/dev/null
bash "$gpu_installer" --self-test >/dev/null
grep -F 'install-gpu-worker.sh' \
  "${internal_dir}/platform/scripts/bootstrap-platform-kubernetes.sh" >/dev/null
dcgm_manifest="${internal_dir}/manifests/dcgm-exporter.yaml"
dcgm_installer="${internal_dir}/platform/scripts/install-dcgm-exporter.sh"
[[ -f $dcgm_manifest && -f $dcgm_installer ]]
grep -F "image: nvcr.io/nvidia/k8s/dcgm-exporter:${TSS_DCGM_EXPORTER_VERSION}" \
  "$dcgm_manifest" >/dev/null
grep -F 'tss.ai/accelerator: nvidia' "$dcgm_manifest" >/dev/null
grep -F 'node-role.kubernetes.io/control-plane' "$dcgm_manifest" >/dev/null
grep -F 'imagePullPolicy: Never' "$dcgm_manifest" >/dev/null
grep -F -- '--address=$(HOST_IP):9400' "$dcgm_manifest" >/dev/null
grep -F 'DCGM_EXPORTER_KUBERNETES' "$dcgm_manifest" >/dev/null
grep -F 'DISABLE_STARTUP_VALIDATE' "$dcgm_manifest" >/dev/null
grep -F 'memory: 512Mi' "$dcgm_manifest" >/dev/null
grep -F 'SYS_ADMIN' "$dcgm_manifest" >/dev/null
! grep -F 'privileged: true' "$dcgm_manifest" >/dev/null
grep -F 'apply --dry-run=server' "$dcgm_installer" >/dev/null
grep -F 'refusing a kubeconfig that resembles the Main/Second cluster' \
  "$dcgm_installer" >/dev/null
grep -F 'DCGM_FI_DEV_GPU_TEMP' "$dcgm_installer" >/dev/null
grep -F 'GPU control plane must retain its NoSchedule taint' "$dcgm_installer" >/dev/null
grep -F 'resources were preserved for diagnosis' "$dcgm_installer" >/dev/null
bash "$dcgm_installer" --self-test >/dev/null
grep -F 'install-dcgm-exporter.sh' \
  "${internal_dir}/platform/scripts/bootstrap-platform-kubernetes.sh" >/dev/null
grep -F 'TSS_ENABLE_GPU_WORKER=false' \
  "${internal_dir}/platform/platform.env.example" >/dev/null
grep -F 'TSS_EXACT_GPU_SELECTION_ENABLED=false' \
  "${internal_dir}/platform/platform.env.example" >/dev/null
grep -F 'exact GPU selection requires TSS_K8S_CLIENT_MODE=kubectl' \
  "${internal_dir}/platform/scripts/lib-platform.sh" >/dev/null
grep -F 'configured DRA DeviceClass is unavailable' \
  "${internal_dir}/platform/scripts/verify-internal-kubeadm.sh" >/dev/null
grep -F 'resources: ["deviceclasses", "resourceslices"]' \
  "${internal_dir}/platform/k8s/backend-access.yaml" >/dev/null

gpu_worker_base='FROM pytorch/pytorch:2.7.1-cuda12.8-cudnn9-runtime@sha256:c16f4c749e2d9e96878875cdf6cc45cddda1d1a36fddd371dd6f2360f1b6e2a2'
for gpu_dockerfile in \
  "${internal_dir}/../../k8s/training-worker/Dockerfile.cv" \
  "${internal_dir}/../../k8s/training-worker/Dockerfile.nlp" \
  "${internal_dir}/../../k8s/training-worker/Dockerfile.pytorch-cuda12"; do
  [[ $(tr -d '\r' <"$gpu_dockerfile" | grep -Fxc "$gpu_worker_base") -eq 1 ]]
done

find "${internal_dir}" -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
echo "Internal cluster copy contract tests passed."
