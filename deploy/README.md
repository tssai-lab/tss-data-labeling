# Backend Node Deployment

This directory contains the project-specific implementation for repeatable
backend deployment. It separates one-time node bootstrap from routine
application releases.

## Deployment model

- `backend-ops` is the integration branch and automatically deploys a verified
  backend image to the Main integration node.
- `backend` is the future stable backend branch. It builds an image but does
  not automatically deploy until the complete multi-node route is accepted.
- CI builds one immutable backend image tagged with the 40-character Git commit
  SHA. The target node does not pull source code or rebuild the application.
- PostgreSQL, MinIO, MLflow, the Main Redis session store and the worker runtime are node dependencies. A
  normal backend release only replaces the backend container.
- Application rollback never automatically restores database data.

See `BRANCH_GOVERNANCE.md` for branch and promotion rules.

## Files

| Path | Purpose |
|---|---|
| `nodes/inventory.example.yml` | Node inventory schema without real addresses or credentials |
| `nodes/node.env.example` | Generic non-secret node configuration template |
| `nodes/main.env.example` | Current Main integration-node configuration template |
| `scripts/preflight-node.sh` | Read-only operating system, tool and capacity checks |
| `scripts/bootstrap-node-training-runtime.sh` | Legacy isolated-POC MLflow, worker image and kind bootstrap; not for Main kubeadm expansion |
| `scripts/bootstrap-node-backend.sh` | One-time backend runtime and restricted deploy-command bootstrap |
| `scripts/smoke-test-node.sh` | Repeatable non-destructive node smoke checks |
| `scripts/bootstrap-main-*.sh` | Compatibility entry points for the current Main node |
| `SESSION_REDIS_RUNBOOK.md` | Main Redis first bootstrap, routine automation, verification and recovery |

## New node procedure

Main expansion nodes join the existing kubeadm cluster. Do not run
`bootstrap-node-training-runtime.sh` for them: that script intentionally creates
an independent kind cluster and is retained only for an isolated POC.

For a Main kubeadm compute node:

1. Run `preflight-node.sh`, then use the infrastructure-owned kubeadm join
   command. Join tokens and certificate material are short-lived secrets and
   must never be committed to this repository.
2. Wait until the control-plane reports the exact node `Ready`. Prepare the
   namespace registry pull secret and verify the immutable CV, NLP and inference
   images on that node with a real Pod.
3. Install and run `tss-node-prepare-model-cache` on the physical node. From a
   cluster-admin host, run `tss-node-validate-model-cache --label-ready`. The
   validator creates one static Local PV/PVC pair bound to that exact node, so
   restricted workload Pods use a PVC and never request `hostPath` directly.
4. Verify that resource monitoring has synchronized the live node labels and
   capacity. Run a minimal task before enabling the compute-server record for
   normal scheduling.

The procedure below is only for an intentionally independent app/worker POC:

1. Copy `nodes/node.env.example` outside the repository and set non-secret
   values for the target node. Protect the installed file with mode `600`.
2. Run the read-only preflight:

```bash
bash deploy/scripts/preflight-node.sh /path/to/node.env
```

3. Install Docker, Nginx and project data dependencies through their reviewed
   infrastructure runbooks. Do not place passwords in `node.env`.
4. Bootstrap the worker runtime from a clean checkout:

```bash
TSS_NODE_CONFIG=/path/to/node.env \
  bash deploy/scripts/bootstrap-node-training-runtime.sh /path/to/repository
```

5. Bootstrap the backend deploy helper:

```bash
TSS_NODE_CONFIG=/path/to/node.env \
  bash deploy/scripts/bootstrap-node-backend.sh deploy/main/compose.backend.yml
```

The backend bootstrap reads existing PostgreSQL and MinIO credentials directly
from their local containers, writes the backend runtime file with mode `600`,
starts the pinned loopback-only Redis session store, and grants the deploy user
permission to run only the image-load helper. Follow
[`SESSION_REDIS_RUNBOOK.md`](SESSION_REDIS_RUNBOOK.md) before enabling a backend
build that requires Redis on an existing node.

## GitHub Environment

Create a GitHub Environment named `main` with:

| Type | Name | Value |
|---|---|---|
| Variable | `DEPLOY_HOST` | Main SSH host or DNS name |
| Variable | `DEPLOY_USER` | Dedicated deployment user |
| Variable | `DEPLOY_KNOWN_HOSTS` | Offline-verified SSH host-key line |
| Secret | `DEPLOY_SSH_PRIVATE_KEY` | Private key for the dedicated deployment user |

The workflow uses the short-lived repository `GITHUB_TOKEN` to pull the image
on the GitHub runner, so no long-lived GHCR pull token is required. The image is
then streamed to the node over SSH and verified by image ID before activation.

Do not generate `DEPLOY_KNOWN_HOSTS` by trusting `ssh-keyscan` inside the same
deployment job. Verify the host key through an existing trusted channel first.

## Routine release

1. Push a reviewed commit to `backend-ops`.
2. `Backend CI and Image` runs the complete Maven verification.
3. CI publishes `ghcr.io/tssai-lab/tssai-backend:<commit-sha>`.
4. The reusable deployment workflow streams that exact image to Main.
5. Main ensures the pinned Redis container is healthy, then waits for
   `/health/ready`; failure prints backend logs and restores the previously
   running backend image. Redis data is not removed by application rollback.
6. Run the node smoke test and archive its result:

```bash
bash deploy/scripts/smoke-test-node.sh /etc/tss-platform/node.env
```

The default smoke test is non-destructive. Authenticated and full business
closed-loop tests are enabled only with controlled test credentials and data.

## Physical-node model weight cache (training and inference)

The model cache is an opt-in optimization shared by training and inference. A trusted init container streams
the attested model artifact from MinIO, verifies its SHA-256 and size, then
atomically materializes it under the physical node's cache directory. The user
training or inference container receives only that digest's `data` directory and
lock file as read-only subPath mounts; it never receives the full cache. MinIO
remains the source of truth, so clearing a cache entry never deletes the model.

Defaults:

- physical host path: `/opt/tss-platform/model-cache`
- kubeadm physical node/hostPath: `/opt/tss-platform/model-cache`
- workload volume: node-bound static Local PV/PVC named
  `tss-model-cache-<sanitized-node-name>`
- trusted init-container path: `/var/cache/tss/models`
- maximum materialized cache data: 8 GiB on the disk-constrained Main node
- reserved free filesystem space: 5 GiB
- additional runtime image rollout reserve: 10 GiB during node validation
- backend switch: disabled

Capacity and memory behavior:

- downloads and SHA-256 checks are streamed in 1 MiB chunks; model artifacts are
  not loaded into the backend or worker process heap as one byte array;
- the limit is disk-cache capacity, not RAM capacity;
- before materializing a miss, the worker checks both the configured cache limit
  and reserved filesystem free space;
- least-recently-used entries older than the eviction grace period are removed
  first; entries with an active training or inference read lock are skipped;
- a model larger than the configured cache limit bypasses caching at manifest
  construction time and follows the existing direct-download path.

Administration:

- every administrator can open `/system/model-cache` and inspect per-node disk
  use, entries, validity and active-use state;
- only the super administrator (`role_id=1`) can clear selected entries or all
  entries on selected nodes;
- active entries are reported as `in use` and are never deleted; partial node
  failures are returned per node and every clear operation is audit logged;
- the API endpoints are `GET /api/system/model-cache` and
  `POST /api/system/model-cache/clear`.


Main uses kubeadm, so the physical directory is the Pod `hostPath`; no kind
container mount or cluster recreation is involved. Because Main enforces the
Kubernetes restricted Pod Security Standard, application Pods do not declare
`hostPath` themselves: a static Local PV exposes that directory and an exactly
pre-bound PVC is mounted by the restricted training, inference, probe and
administration Pods. The old kind runtime
bootstrap remains available only for an intentionally isolated POC.

### Main maintenance migration

The cache must be introduced in a maintenance window, but Main's kubeadm cluster
must not be recreated. PostgreSQL, MinIO and MLflow remain the source systems;
check their health and backups before changing the cache switch.

1. Keep `TSS_MODEL_CACHE_ENABLED=false` and wait for active Jobs to finish:

   ```bash
   kubectl --kubeconfig /opt/tss-platform/k8s/.kube/admin.conf \
     get jobs -n tss-training
   ```

2. On the target physical node, prepare the directory and enforce UID/GID,
   local-filesystem and disk-reserve checks:

   ```bash
   sudo TSS_MODEL_CACHE_HOST_PATH=/opt/tss-platform/model-cache \
     deploy/scripts/tss-node-prepare-model-cache
   ```

3. Ensure the immutable inference worker image and registry pull secret are
   ready on that node. From a cluster-admin host, run the real worker probe; it
   creates/verifies the node-bound Local PV/PVC and adds the ready label only
   after the restricted worker Pod, permissions and capacity pass:

   ```bash
   sudo TSS_KUBECONFIG=/opt/tss-platform/k8s/.kube/admin.conf \
     deploy/scripts/tss-node-validate-model-cache \
       k8s-master registry.example/tss-inference-worker-cpu:<40-char-sha> \
       --label-ready
   ```

4. Wait for the resource monitor to copy the live ready label into the compute
   node record. Only then set `TSS_MODEL_CACHE_ENABLED=true`, keep the node path
   equal to `/opt/tss-platform/model-cache`, and redeploy the immutable backend.
   Run the same model twice: the first initializer log must report `populated`;
   the second must report `hit`.

For every additional kubeadm worker, repeat the same order: system/disk
preflight, join the existing cluster, create the registry secret, load or pull
the immutable worker image, prepare the local directory, run the real worker
probe, verify monitoring, run a minimal task, and only then enable scheduling.
Never copy the ready label from another node.

Rollback is non-destructive: set the switch back to `false`, regenerate the
backend runtime environment, and redeploy. Keep the mounted cache directory for
inspection; keep the Local PV/PVC with reclaim policy `Retain`. Removing a
node's PV/PVC or deleting cached weights is a separate, explicit maintenance
action and must happen only after the node is disabled and its ready label is
removed.

## Rollback

Redeploy an earlier verified SHA through the manual workflow. Only images that
implement the standardized `/health/ready` contract are accepted by automated
rollback. Pre-standardization images require a separately reviewed manual
procedure.

## Current boundaries

- Main data is still local and is not highly available.
- Runtime worker images are published immutably, but Main remains on its
  verified local runtime images until registry promotion is accepted.
- Second is not modified by this stage.
- Multi-node traffic switching, data replication and automatic failover belong
  to later route stages.
