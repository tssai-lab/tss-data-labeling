# tss-AIplatform internal cluster copy

This directory describes the independent validation cluster copied onto
seu5090 and seu4080. It does not migrate, join, drain or modify the Main/Second
cluster.

## Fixed boundaries

- Main/Second remains the CPU stable baseline and keeps its current deployment
  and data.
- The internal cluster uses `backend-gpu`: every successfully deployed
  `backend-ops` CPU baseline is merged into it in one direction, while GPU-only
  commits remain internal until an explicit later merge back to `backend`.
  Automatic internal deployment remains disabled unless the repository variable
  `INTERNAL_GPU_AUTO_DEPLOY_ENABLED` is exactly `true`; this allows the branch,
  Environment policy and restricted server gateway to be prepared safely first.
  The cluster owns a separate Kubernetes cluster, database, object store,
  MLflow data, Secrets, logs, caches and rollback record.
- seu5090 is the control-plane/platform/storage host. Its control-plane etcd
  remains on NVMe; bulk platform data may use only the separately approved
  `tss-AIplatform` filesystem on the 8 TB disk.
- The reviewed seu5090 host also contains one RTX 5090. Its isolated project
  containerd enables the NVIDIA runtime, and the Device Plugin/DCGM exporter
  may report that GPU. The standard control-plane `NoSchedule` taint is kept.
  GPU discovery keeps `tss.ai/accelerator=nvidia`, while
  `tss.ai/gpu-schedulable=false` prevents GPU overflow onto the control plane.
  The control plane may serve as a one-task CPU fallback only when it also has
  `tss.ai/node-pool=cpu`, `tss.ai/platform-schedulable=true` and
  `tss.ai/platform-max-active-tasks=1`. Cordon, `NoExecute`, maintenance taints
  or a missing/invalid bound still fail closed before a Job is pinned by
  `nodeName`.
- The current seu5090 project root is `/srv/tss-AIplatform`, mounted from
  `/dev/sda1`. `/media/user/data` is on the nearly-full root filesystem and is
  not a valid project target.
- seu4080 is the first CPU/GPU worker. Existing Docker containers and all files
  outside `/media/seu/data/tss-AIplatform` are fixed external state.
- Neither host may reuse `/run/containerd/containerd.sock` or
  `/var/lib/containerd`. Both system containerd daemons are already used by
  non-project Docker workloads. Kubernetes therefore gets a dedicated service,
  socket, state directory and data root.
- The reviewed C4 installer must copy this complete directory to
  `/usr/local/lib/tss-aiplatform-internal`; the storage guard intentionally
  depends on its sibling `lib.sh` and pinned `versions.env`.
- CPU remains the default runtime. GPU Pods explicitly select the `nvidia`
  RuntimeClass. Monitoring components tolerate the reviewed control-plane
  taint, while ordinary training Pods do not.

## Stages and gates

1. **C0 evidence (complete):** read-only health, storage, network, runtime and
   port inventory. No host was changed.
2. **C1 configuration (this directory):** validate node configuration and
   render containerd/kubeadm files to stdout. The scripts do not install or
   start services.
3. **C2 delivery isolation:** validate a dedicated GitHub Environment and
   Runner without Main credentials. Record exact image digests.
4. **C3 storage gate:** partitioning or formatting seu5090 `/dev/sda` requires a
   separate explicit approval after device identity and SMART evidence are
   rechecked. The existing seu4080 filesystem is never repartitioned. The
   approved operation uses the stable ATA by-id path, creates only one 2048 GiB
   ext4 partition, and leaves the remaining capacity unallocated. The disk
   reports 8090 power-on hours. The reviewed disk-specific configuration records
   the user's explicit decision to replace the extended-test gate with overall
   health, a successful short test and zero critical SMART counters.
5. **C4 cluster:** install the isolated runtime, run kubeadm, then network and
   node probes. Main/Second is checked before and after.
6. **C5-C8:** deploy empty platform services, prove CPU parity, enable the
   independent second deployment target, and only then add GPU behavior.

The C5 empty-platform Compose, least-privilege Kubernetes credential, locked
registry application images and one-command guarded bootstrap are defined
in [`platform/README.md`](platform/README.md). C5 does not remove the worker's
staging taint or claim CPU/GPU business parity.

The same platform guide defines the C7 internal frontend release. It preserves
Main's existing frontend deployment, consumes a full-SHA immutable image through
manual export/deploy tasks, keeps the internal port in root-owned local config,
and uses SSH forwarding for initial acceptance instead of opening a new campus
firewall rule.

The cross-layer inventory for bringing up a new server without copying files
from Main is defined in [`reproducible/README.md`](reproducible/README.md).

## Configuration and read-only use

Copy the appropriate example outside Git, replace every `REPLACE_*` value, and
protect it as an infrastructure file. It must contain no password, token,
kubeconfig or registry credential.

```bash
cp deploy/tss-aiplatform-internal/config/control-plane.env.example /tmp/control.env
chmod 600 /tmp/control.env
vi /tmp/control.env

bash deploy/tss-aiplatform-internal/scripts/preflight.sh --config-only /tmp/control.env
sudo bash deploy/tss-aiplatform-internal/scripts/preflight.sh /tmp/control.env

bash deploy/tss-aiplatform-internal/scripts/render-containerd-config.sh /tmp/control.env
bash deploy/tss-aiplatform-internal/scripts/render-containerd-unit.sh /tmp/control.env
bash deploy/tss-aiplatform-internal/scripts/render-kubeadm-init.sh /tmp/control.env
```

The default host preflight is observational: missing future prerequisites are
warnings. `--ready` turns them into failures and is the C4 gate. A generated
file is not authorization to install it.

The first C4 host step is deliberately narrower than cluster creation. It
installs only the reviewed project directory, root-owned node configuration,
isolated containerd unit, project directories and the Kubernetes bridge
sysctls. It stops an unconfigured kubelet but does not modify UFW, pull images,
run kubeadm or apply a CNI. The check and explicit node confirmation are
mandatory.

```bash
sudo bash deploy/tss-aiplatform-internal/scripts/prepare-node.sh \
  --check /path/to/node.env

sudo bash deploy/tss-aiplatform-internal/scripts/prepare-node.sh \
  --apply /path/to/node.env --confirm-node REPLACE_WITH_REVIEWED_NODE_NAME
```

The campus hosts can reach `registry.k8s.io` itself but time out after it
redirects to Google Artifact Registry. C4 therefore uses the no-Secrets
`export-airgap-bundles` workflow task on a GitHub-hosted Runner. It pulls the
already locked linux/amd64 image digests and emits Kubernetes, Calico, Metrics
Server and optional NVIDIA files in one short-lived artifact.
Download the artifact from the exact trusted `backend-ops` run, copy only the
common files to the control plane and include the NVIDIA files on GPU workers.
The import command verifies every bundle checksum and requires `sources.lock`
to byte-match the committed 13-image lock before writing the isolated runtime.
When the operator's direct download path is unreliable, the
`stage-airgap-bundles` task may download the exact successful export run to the
reviewed `AIRGAP_STAGE_ROOT` on the protected internal Runner. It verifies the
run SHA, branch, workflow, conclusion, seven files, both checksum lists and the
13-image source lock. Because the campus artifact path is slow per connection,
the Runner downloads 16 strict byte ranges in parallel, then requires the
combined archive to match GitHub's whole-artifact SHA256 before extracting it.
This staging task does not use sudo, import images or write cluster state.

```bash
sudo bash deploy/tss-aiplatform-internal/scripts/import-airgap-bundles.sh \
  --check /etc/tss-aiplatform/node.env /path/to/bundles

sudo bash deploy/tss-aiplatform-internal/scripts/import-airgap-bundles.sh \
  --apply /etc/tss-aiplatform/node.env /path/to/bundles \
  --confirm-node REPLACE_WITH_REVIEWED_NODE_NAME
```

The common bundle includes the digest-locked Metrics Server image. Import it
on every node that may schedule system workloads. The exact upstream v0.9.0
manifest is versioned below `manifests/` and byte-matches the SHA-256 in
`artifacts.lock`. After both node imports, install it only from the reviewed
control plane. The installer adds the laboratory kubelet-certificate override,
forces offline `imagePullPolicy: Never`, rejects Main/Second node names and
requires the Metrics API to report every reviewed node. It also selects the
standard `node-role.kubernetes.io/control-plane` label and tolerates only that
role's `NoSchedule` taint. This keeps the single Metrics Server replica on the
stable control plane, so a worker shutdown does not also remove control-plane
CPU and memory metrics. No host name or internal IP is embedded in the rule.
The platform bootstrap calls the same installer, so a clean redeployment cannot
silently omit CPU and memory metrics or place their only collector on a worker.

```bash
sudo bash deploy/tss-aiplatform-internal/scripts/install-metrics-server.sh \
  --check /etc/tss-aiplatform/node.env

sudo bash deploy/tss-aiplatform-internal/scripts/install-metrics-server.sh \
  --apply /etc/tss-aiplatform/node.env \
  --confirm-node REPLACE_WITH_REVIEWED_CONTROL_PLANE_NODE
```

For a GPU worker, also import the generated `nvidia-amd64.tar` into that
worker's isolated project containerd. GPU discovery is not an undocumented
post-install command: after the runtime and image preconditions pass, set
`TSS_ENABLE_GPU_WORKER=true` in the root-owned platform configuration and run
the platform bootstrap. The bootstrap installs the committed, checksum-locked
NVIDIA Device Plugin, `nvidia` RuntimeClass and DCGM Exporter through guarded
installers. DCGM listens only on the GPU node's reviewed InternalIP port 9400
and exposes read-only utilization, memory and temperature metrics to the
platform backend. CPU-only deployments leave the same switch false and skip
both components. The complete safety gate and removal boundary are documented
in [`platform/README.md`](platform/README.md#optional-first-gpu-worker).

After the exact image import succeeds, prepare only the active control-plane
UFW instance. The command leaves UFW enabled and preserves its defaults and
unrelated rules. It permits the reviewed worker address to reach only the
Kubernetes API and Calico VXLAN on the control-plane address. The worker host's
inactive UFW is not enabled or otherwise changed. Because the existing UFW
policy denies forwarding, it also permits routed traffic only when its source
is the configured Pod CIDR; without that rule Pods cannot reach the Kubernetes
API service or external DNS. Service traffic to the local API is delivered as
host input after kube-proxy translation, so a separate rule permits the Pod
CIDR to reach only TCP 6443 on the control-plane address.

If firewalld is active alongside UFW, the command also adds the same
worker-only `6443/tcp` and `4789/udp` allowances to both the runtime and
permanent configuration of the control-plane interface's active zone. It does
not reload, restart or disable either firewall, so unrelated rules and
established shared services remain in place.

For this internal validation cluster, the configured Pod CIDR is added to
firewalld's trusted zone in runtime and permanent configuration. This is the
minimal no-reload solution, but it intentionally trusts Pods to reach host and
forwarded traffic; do not reuse it for untrusted multi-tenant clusters.

```bash
sudo bash deploy/tss-aiplatform-internal/scripts/prepare-control-plane-network.sh \
  --check /etc/tss-aiplatform/node.env REPLACE_WORKER_IP

sudo bash deploy/tss-aiplatform-internal/scripts/prepare-control-plane-network.sh \
  --apply /etc/tss-aiplatform/node.env REPLACE_WORKER_IP \
  --confirm-node REPLACE_WITH_REVIEWED_NODE_NAME
```

The pinned stock Calico manifest defaults to IP-in-IP and BGP. Render it into
the reviewed VXLAN-only form before its first apply. The renderer verifies the
source checksum from `artifacts.lock`, uses the configured Pod CIDR, selects
each Kubernetes InternalIP, disables BGP/BIRD, and enables VXLAN `Always`.
It also fixes Felix to the iptables dataplane and legacy backend used by Docker
on the reviewed shared hosts. This prevents Calico's automatic nftables choice
from being evaluated separately and then dropped by Docker's legacy FORWARD
policy. This keeps the node-to-node network requirement to bidirectional UDP
4789 without changing or restarting shared Docker.

```bash
bash deploy/tss-aiplatform-internal/scripts/render-calico-vxlan.sh \
  /etc/tss-aiplatform/node.env /path/to/pinned-calico.yaml \
  >/path/to/reviewed-calico-vxlan.yaml
```

Join credentials are short lived and must never enter Git, logs or shell
arguments. Place only the generated bootstrap token and CA hash in a mode-0600
temporary file on the worker, then render the worker-specific join config. The
renderer keeps the worker on the isolated CRI socket, reviewed node address and
project kubelet directory. The worker joins with a `tss.ai/staging=true:NoSchedule`
taint so ordinary workloads cannot consume a shared laboratory host before its
Docker, GPU-process and network regression checks pass. Delete both
credential-bearing files immediately after `kubeadm join` succeeds or expires.

```bash
sudo bash deploy/tss-aiplatform-internal/scripts/render-kubeadm-join.sh \
  /etc/tss-aiplatform/node.env /path/to/bootstrap.env \
  >/path/to/kubeadm-join.yaml

sudo kubeadm join --config /path/to/kubeadm-join.yaml
```

The C3 storage command has a separate failure-closed check and apply mode. The
apply mode re-runs the same exact model, serial, WWN, capacity, empty-disk and
configured SMART-policy checks before its first write. It also requires the
reviewed serial on the command line. The normal policy remains a completed
extended test; a weaker disk-specific policy must be explicit in the reviewed
configuration and SOP. No policy permits a running self-test, failed overall
health, failed short test or nonzero critical counters.

```bash
sudo bash deploy/tss-aiplatform-internal/scripts/prepare-storage.sh \
  --check deploy/tss-aiplatform-internal/config/seu5090-storage.env

sudo bash deploy/tss-aiplatform-internal/scripts/prepare-storage.sh \
  --apply deploy/tss-aiplatform-internal/config/seu5090-storage.env \
  --confirm-serial REPLACE_WITH_REVIEWED_SERIAL
```

## Known prerequisites not yet satisfied

- The current addresses are DHCP-derived. The user accepted their observed
  stability for this internal validation environment only. Preflight must still
  compare the configured and actual addresses; this is not a permanent-address
  guarantee.
- seu5090 has active UFW default-deny rules; reviewed node-to-node Kubernetes
  and Calico VXLAN rules are required before C4.
- `br_netfilter` is not currently loaded on either host.
- seu5090 `/dev/sda` is raw and unmounted. Initial SMART health and the short
  test passed; the user accepted the documented weaker policy instead of
  waiting for the extended test. It has not been partitioned or formatted.
- seu4080 kubelet currently loops because `/var/lib/kubelet/config.yaml` is
  absent. That pre-existing service noise must be stopped only inside the
  approved C4 maintenance step.
- Runtime and Kubernetes image tags are fixed in `versions.env`; their C2
  immutable digest lock has been independently verified and published.

## C2 Runner and artifact lock

The existing internal deployment Runner is selected through the logical labels
`tss-aiplatform-internal` and `deploy`, not a physical hostname. Its work root
comes from the environment variable `RUNNER_WORK_ROOT`. The manually dispatched,
no-Secrets smoke from the protected `backend-ops` branch checks out the exact
SHA, repeats the GitHub data path, and checks TCP/22 reachability to the
configured control-plane address. It deliberately does not attempt SSH
authentication, sudo, deployment or host writes.

The same workflow resolves an artifact lock on a GitHub-hosted Runner. This
avoids relying on the campus hosts' currently unreliable redirect from
`registry.k8s.io` to Google Artifact Registry. The reviewed result is committed
as `artifacts.lock`: its three manifest checksums and all 13 multi-architecture
image digests were independently checked against the source registries. C4
must reject a missing or mismatched artifact instead of silently using a tag.

## Rollback rule

Every environment keeps its own last-known-good SHA. If one target fails, keep
the successful target online, mark the pair `OUT_OF_SYNC`, classify the failure
as common-code or environment-specific, and retry the failed target with the
same SHA/digests. Never roll back Main merely to hide an internal environment
problem.
