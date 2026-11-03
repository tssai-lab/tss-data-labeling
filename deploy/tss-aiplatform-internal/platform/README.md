# C5 independent empty platform

This directory starts a new, empty application environment on the seu5090
control plane and connects it to the independent seu5090/seu4080 Kubernetes
cluster. It does not copy Main data, credentials, certificates, logs or
kubeconfig, and it does not modify or join the Main/Second cluster.

## What this stage contains

- five uniquely named Docker containers: PostgreSQL, Redis, MinIO, MLflow-lite
  and the backend;
- persistent data and logs only below `/srv/tss-AIplatform/platform`;
- the repository's module-one bootstrap SQL plus all Flyway migrations from the
  locked backend image;
- a Kubernetes ServiceAccount credential that is long-lived until explicitly
  revoked, but cannot read Secrets, create namespaces or act as cluster-admin;
- exact high-port and worker/Pod firewall rules, with PostgreSQL and the MinIO
  console remaining loopback-only;
- immutable registry manifests, original linux/amd64 image IDs and
  execution-relevant runtime-content fingerprints. Compose never pulls
  `latest` and never builds a replacement image on the server. A bundle is
  generated from GitHub/registries without reading Main.

Docker 29's containerd image store may assign a different local ID after
`docker load`. The verifier does not ignore that difference: it compares the
locked OS/architecture, filesystem layers, user, entrypoint, command,
environment, ports, health check and other runtime fields. A metadata-only
local ID rewrite is reported as information; any executable-content difference
still fails closed.

The C5 image set consumes about 1.7 GB before layer sharing. The scripts keep a
10 GiB system-root survival floor for the shared Docker engine and a separate
100 GiB floor on the dedicated 2 TiB project filesystem. These deployment
floors are not the model-cache policy: model caching remains disabled in this
environment until its node-local storage is prepared and accepted. Business
data stays on the dedicated project filesystem.

## Minimal operator flow

1. Check out the reviewed `backend-ops` SHA into
   `/srv/tss-AIplatform/repository`. Keep the worktree clean.
2. Copy `platform.env.example` to `/etc/tss-aiplatform/platform.env`, replace
   the physical control-plane hostname and both IP placeholders, and verify the
   six ports are unused on seu5090. The physical hostname may intentionally
   differ from the Kubernetes logical node name.
3. Run `check-platform-image-budget.sh` on seu5090, then use the
   `export-platform-images` GitHub Actions task to generate the five-image
   bundle from immutable registry digests. Load the verified stream into
   seu5090 Docker. The same exporter may run on any registry-connected Docker
   host; Main is neither a source nor a required hop. Temporary
   `tss-aiplatform-internal/*` aliases and pull references are removed on exit.
4. Run the single guarded bootstrap command on seu5090:

```bash
sudo bash deploy/tss-aiplatform-internal/platform/scripts/bootstrap-platform.sh \
  --apply /etc/tss-aiplatform/node.env /etc/tss-aiplatform/platform.env \
  --confirm-node tss-ai-control-01
```

The command generates fresh local secrets without displaying them, prepares
the locked Metrics Server plus only the internal namespace/RBAC/services and
exact firewall ports, starts the
five Compose services, then verifies database, the Redis-backed login session,
object storage, MLflow, Kubernetes least privilege, live node metrics and callback authentication. Re-running `--apply`
is idempotent. It also creates one clearly named normal smoke user, proves
login, permission denial and a tiny MinIO upload/download/delete flow, and
retains the resulting audit evidence. After the first deployment, `--check`
performs the same local preflight without changing state.

## Optional first GPU worker

GPU support is an explicit opt-in and is part of the normal bootstrap path, so
a clean deployment cannot silently omit the required component. Keep
`TSS_ENABLE_GPU_WORKER=false` for CPU-only clusters. Set it to `true` in the
root-owned `platform.env` only after all of the following are true:

- the reviewed worker uses the isolated `nvidia` containerd runtime;
- `nvidia-amd64.tar` has been imported into the project containerd on that
  worker;
- the worker is Ready and the configured node name still identifies that exact
  host;
- host-level `nvidia-smi` confirms that no other laboratory user is using any
  GPU exposed by the worker's Device Plugin. The first-stage scheduler requests
  one GPU but cannot safely choose around unmanaged host processes.
- `TSS_GPU_INFERENCE_WORKER_IMAGE` uses the immutable `gpu-inference` reference
  recorded in `reproducible/gpu-runtime-images.lock`.

The guarded Kubernetes bootstrap then installs the digest-locked NVIDIA Device
Plugin, the `nvidia` RuntimeClass and the standalone DCGM Exporter from
committed artifacts. It labels every GPU-capable node declared by the reviewed
node configurations with `tss.ai/accelerator=nvidia`; the worker keeps
`tss.ai/node-pool=cpu` and `tss.ai/gpu-schedulable=true`. The control plane
keeps its `NoSchedule` taint and uses `tss.ai/gpu-schedulable=false`, so DCGM
can observe the RTX 5090 without making it a GPU overflow target. A reviewed
CPU fallback opts in separately with `tss.ai/platform-schedulable=true` and a
bounded `tss.ai/platform-max-active-tasks` value.
The Device Plugin and DCGM exporter tolerate that taint only to advertise and
observe the RTX 5090; ordinary training Jobs do not inherit the toleration.
DCGM Exporter binds each NVIDIA node's read-only endpoint to its reviewed
InternalIP on port 9400 and does not require Prometheus or the full GPU
Operator. The container is not privileged and
receives only the NVIDIA-required `SYS_ADMIN` capability; it has no Kubernetes
service-account token or host-directory mounts. Check mode performs artifact, cluster
identity and server-side dry-run validation without changing the cluster:

On the reviewed GeForce worker, NVIDIA's exporter startup validator exits with
status 1 and no diagnostic even though embedded DCGM can read both GPUs. The
committed manifest explicitly disables that preflight only; the guarded
installer compensates by failing unless the live endpoint returns the four
exact GPU metrics required by this platform. The 512 MiB memory limit is based
on a reproduced 256 MiB `OOMKilled` result on this node.

```bash
sudo bash deploy/tss-aiplatform-internal/platform/scripts/install-gpu-worker.sh \
  --check /etc/tss-aiplatform/node.env /etc/tss-aiplatform/platform.env

sudo bash deploy/tss-aiplatform-internal/platform/scripts/install-gpu-worker.sh \
  --apply /etc/tss-aiplatform/node.env /etc/tss-aiplatform/platform.env \
  --confirm-node tss-ai-control-01

sudo bash deploy/tss-aiplatform-internal/platform/scripts/install-dcgm-exporter.sh \
  --check /etc/tss-aiplatform/node.env /etc/tss-aiplatform/platform.env

sudo bash deploy/tss-aiplatform-internal/platform/scripts/install-dcgm-exporter.sh \
  --apply /etc/tss-aiplatform/node.env /etc/tss-aiplatform/platform.env \
  --confirm-node tss-ai-control-01
```

Successful installation means the worker reports a positive allocatable
`nvidia.com/gpu` value and its InternalIP endpoint exposes
`DCGM_FI_DEV_GPU_UTIL`, `DCGM_FI_DEV_FB_USED`, either
`DCGM_FI_DEV_FB_FREE` or `DCGM_FI_DEV_FB_TOTAL`, and
`DCGM_FI_DEV_GPU_TEMP`. The former proves Kubernetes can discover the device;
the latter proves monitoring is available. Neither proves that an unmanaged
host process is absent. On these shared laboratory servers, every real GPU
acceptance run still requires an immediate host-level idle check. Never stop
another user's process or submit a second test merely because Kubernetes
reports capacity.

If the optional components must be removed, first ensure no project GPU Job is
running, then delete only `tss-dcgm-exporter`,
`nvidia-device-plugin-daemonset`, the `nvidia` RuntimeClass and the worker's
`tss.ai/accelerator` label. Do not reset the cluster, change the CPU node-pool
label, prune shared images or remove the host driver/runtime. Preserve failed
Pod logs before any cleanup.

## Credential and permission boundary

The backend kubeconfig is stored at
`/srv/tss-AIplatform/platform/config/backend.kubeconfig`, owned by
`root:root` with mode `0640`; only the backend container receives supplemental
group 0 to read that single bind-mounted file. Its token is independent from Main and remains
valid across ordinary service restarts, so no repeated registry or Kubernetes
login is needed. To revoke it, stop only the internal backend, delete the
`tss-backend-access-token` Secret in the internal cluster, and rerun the guarded
bootstrap to issue a replacement.

The empty deployment intentionally creates no super-administrator. Public
username registration creates only a normal user, as the existing code
requires. Selecting and promoting the first internal super-administrator is a
permission decision and is therefore a separate, recorded C5 acceptance step;
the bootstrap does not guess an account or copy Main users.

The complete no-Main-copy inventory, frontend source lock and parameterized
Nginx route are indexed in [`../reproducible/README.md`](../reproducible/README.md).

## Failure and rollback

If C5 fails, retain the new data directories and logs. Stop only the five
containers in the `tss-aiplatform-internal` Compose project and correct the
classified local cause. Do not run a global Docker prune, delete volumes, reset
Kubernetes, loosen Main, or remove any 4080/5090 non-project service. C6 CPU
training/inference and C8 GPU work remain separate stages.

## C7 restricted backend deployment

C7 keeps the existing Main deployment unchanged and adds an independent,
manual-only internal backend target. The selected internal Runner remains an
unprivileged local process. Its project deployment key is stored only in the
Runner's root-owned project disk; the private key is not uploaded to GitHub. On
the configured control plane, the matching public key is restricted by
`authorized_keys` to
`internal-runner-gateway.sh`. That gateway permits only `probe`, an exact
protected-branch fast-forward, a bounded backend bundle stream and one fixed
root deployment command. It can also read only the fixed C7 deployment-state
file through an exact sudoers command; the rest of the root-owned state
directory stays private. Port, agent and X11 forwarding and arbitrary shells
remain disabled.

Install the gateway only after reviewing the Runner public key and the exact
deployment branch. The stable external target uses `backend-ops`; the internal
GPU integration target uses `backend-gpu`:

```bash
sudo bash deploy/tss-aiplatform-internal/platform/scripts/install-internal-runner-gateway.sh \
  --public-key /path/to/reviewed/tss-aiplatform-internal-deploy.pub \
  --node-config /etc/tss-aiplatform/node.env \
  --platform-config /etc/tss-aiplatform/platform.env \
  --deployment-user REPLACE_DEPLOYMENT_USER \
  --deployment-branch backend-gpu \
  --confirm-node REPLACE_CONTROL_PLANE_NODE_NAME
```

The installer writes only paths, the reviewed branch and the local account name to the root-owned
`/etc/tss-aiplatform-deploy/backend.env`; credentials stay outside that file.
The `tss-aiplatform-internal` GitHub Environment then needs the non-secret
variables `CONTROL_PLANE_HOST`, `CONTROL_PLANE_DEPLOYMENT_USER`,
`CONTROL_PLANE_SSH_KEY`, `CONTROL_PLANE_KNOWN_HOSTS`, `RUNNER_WORK_ROOT` and
`AIRGAP_STAGE_ROOT`. Path values are absolute paths on the isolated Runner. The
Runner needs the logical labels `tss-aiplatform-internal` and `deploy`; no
physical hostname is part of the workflow. The workflow never accepts a private
key value from GitHub.

The dedicated `backend-gpu-internal-deploy.yml` reusable workflow is called only
after the complete backend CI succeeds on `backend-gpu` and the repository
variable `INTERNAL_GPU_AUTO_DEPLOY_ENABLED` is exactly `true`. Leave that
variable absent or set it to `false` while preparing or repairing the internal
Environment and gateway. Once enabled, the workflow promotes the exact
published image digest into a small release-lock commit, exports only that
backend image, downloads the short-lived bundle to the mechanical-disk Runner,
and deploys through the forced gateway. The root helper rechecks the committed
lock, runtime fingerprint, disk gates, host identity and all five platform
images before Compose may act. It snapshots the four non-backend platform
containers, changes only the backend, runs the full platform verifier and writes
the state file below the configured `TSS_PLATFORM_ROOT`. Failed switches restore
the previous backend configuration. Successful switches keep only the current
project backend image; no global prune is used.

After an external `backend-ops` deployment succeeds, `backend-ci.yml` performs a
normal, non-force merge into `backend-gpu` and explicitly starts the integrated
GPU CI. A conflict, concurrent push or failed test stops only the internal lane.
No workflow merges `backend-gpu` back to `backend` or deploys it to Main.

To
revoke the channel, remove only the authorized-key line ending in
`tss-aiplatform-internal-deploy`,
`/etc/sudoers.d/tss-aiplatform-internal-deploy` and the two exact
`/usr/local/sbin/tss-aiplatform-internal-*` gateway files. Revocation does not
stop the currently accepted backend or delete platform data.

## C7 fixed CPU runtime deployment

Install the runtime deployer on the configured worker only after the node file
has been installed as a root-owned configuration:

```bash
sudo bash deploy/tss-aiplatform-internal/scripts/install-cpu-runtime-deployer.sh \
  --node-config /etc/tss-aiplatform/node.env \
  --deployment-user REPLACE_DEPLOYMENT_USER \
  --confirm-node REPLACE_WORKER_NODE_NAME
```

The installer makes the complete project helper tree root-owned and grants the
deployment user only the fixed CPU runtime command and, on a node with the
`gpu` role, the fixed GPU runtime command—not a general root shell. After
`stage-cpu-runtime-images` has verified and staged an exact successful export,
dispatch `deploy-cpu-runtime-images` with the same run ID and source SHA. The
root helper freezes the staged directory, repeats the immutable lock and image
checks, imports only into the configured project containerd, and records an
independent result below the configured project audit directory.

For a reviewed GPU worker, the same installer also provisions the separate
`tss-aiplatform-internal-deploy-gpu-runtime` helper. Run the three
`export-gpu-runtime-images`, `stage-gpu-runtime-images` and
`deploy-gpu-runtime-images` workflow tasks from `backend-gpu` with one exact
source SHA and export run ID. The current bundle contains three images: CV
training, NLP training and GPU inference. Importing these images does not enable the
Device Plugin or start a training workload.

## C7 internal frontend (manual, independent target)

The internal frontend is separate from Main's existing host-nginx deployment.
A `frontend-dev` merge still follows the original Main deployment workflow and
also publishes one immutable linux/amd64 image tagged with the full frontend
commit SHA. It does not automatically change the internal cluster.

The frontend image workflow pulls its just-published image back from GHCR and
records its manifest digest, image ID, execution fingerprint and measured Docker
archive size in a short-lived identity artifact. Review that artifact and commit
its single lock row as `frontend-image.lock` on `backend`, then release the exact
backend commit to `backend-ops`. This keeps application source identity separate
from the environment-specific deployment mechanism.

Install the restricted frontend helper only after the C7 backend gateway is
installed. The port must be a reviewed unused high port and is stored in a
root-owned local configuration file; no physical host, account, path, address or
port is committed to Git.

```bash
sudo bash deploy/tss-aiplatform-internal/platform/scripts/install-internal-frontend-deployer.sh \
  --node-config /etc/tss-aiplatform/node.env \
  --platform-config /etc/tss-aiplatform/platform.env \
  --deployment-user REPLACE_DEPLOYMENT_USER \
  --frontend-port REPLACE_FRONTEND_PORT \
  --confirm-node REPLACE_CONTROL_PLANE_NODE
```

The release consists of two explicit `backend-ops` workflow dispatches:

1. `export-frontend-image` exports only the image named by the committed lock.
2. `deploy-frontend` receives that successful run ID and the exact current
   `backend-ops` SHA, verifies the run and checksums, then stages at most 256 MiB
   through the restricted gateway.

The root helper fails before import when the common 10 GiB root survival floor
or platform-disk gate is not met. It fixes the Pod to the reviewed control-plane
node, uses `imagePullPolicy: Never`, exposes only the configured host port, and
checks `/healthz` plus the proxied backend `/v3/api-docs` route. If Kubernetes
was changed and verification fails, it rolls an existing Deployment back or
removes a failed first Deployment. It never rolls Main back or deletes data.

No campus-network firewall opening is part of C7. For first acceptance, use an
SSH local forward from an authorized operator machine and browse the local port.
Direct network exposure is a separate permission decision.

```bash
ssh -L REPLACE_LOCAL_PORT:REPLACE_CONTROL_PLANE_IP:REPLACE_FRONTEND_PORT \
  REPLACE_SSH_USER@REPLACE_CONTROL_PLANE_IP
```

Record two consecutive successful deployments of the same frontend source and
infrastructure SHA before calling this independent release path stable. A
failure on only one cluster is fixed as an environment adaptation; the other
cluster remains online.
