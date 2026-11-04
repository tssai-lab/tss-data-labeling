# Reproducible deployment baseline

This directory is the index for preparing a new tss-AIplatform server without
copying files from Main. Git stores the small, reviewable inputs. Container
images remain in their registries and can be exported by GitHub Actions when an
offline bundle is needed.

## One source, no Main dependency

| Layer | Versioned source | How a new server obtains it |
|---|---|---|
| Kubernetes, Calico, Metrics Server and NVIDIA bootstrap | `../versions.env`, `../artifacts.lock` and repository scripts | Run the existing `export-airgap-bundles` workflow task, or pull the locked digests directly. |
| PostgreSQL, Redis, MinIO, MLflow-lite and backend | `../platform/platform-images.lock` | Run the `export-platform-images` workflow task, or run `platform/scripts/export-platform-images.sh` on any registry-connected Docker host. |
| Historical four-worker runtime inventory | `runtime-images.lock` | Retained as the wider CV/NLP inventory; the same commit can rebuild it with `runtime-images.yml`. |
| Minimal CPU training and inference images | `cpu-runtime-images.lock` | Export and stage the locked CV training, NLP training and CPU inference images with the internal validation workflow. |
| Minimal GPU runtime images | `gpu-runtime-images.lock` | Export and stage the digest-locked CV/NLP training workers and GPU inference worker from `backend-gpu`; this does not enable GPU discovery or submit workloads. |
| Frontend source | `frontend-source.lock` | Check out the exact `frontend-dev` commit and build from its lock file. Do not copy `/var/www` from Main. |
| Public Nginx routes | `nginx/frontend.conf.template` | Replace the five `REPLACE_*` values, review, run `nginx -t`, then install it for that environment. |
| Node and platform configuration | `../config/*.example` and `../platform/platform.env.example` | Create environment-owned files outside Git. |
| Passwords, tokens, certificates and kubeconfig | repository generators and Kubernetes bootstrap | Generate fresh values on the new environment. Never copy them from Main or commit them. |
| Database rows, MinIO objects, models, datasets, logs and caches | environment backup/restore process | Not part of a clean deployment baseline. Restore only when an explicitly approved data migration is required. |

The exact audit is recorded in `main-dependency-inventory.tsv`. Its final
column states whether the item is versioned, regenerated, environment-specific
or deliberately excluded.

## Why image tar files are not committed

GitHub rejects ordinary Git files above 100 MiB, and large binary history would
make every clone permanently larger. The branch therefore contains immutable
manifest digests and exporters, while GHCR/Docker Hub contain the image layers.
An exported tar is a disposable delivery artifact, not source code.

## Generate and download the platform bundle

After this workflow task is present on the reviewed integration branch, an
operator with repository access can generate the bundle without contacting
Main:

```bash
gh workflow run tss-aiplatform-internal-validation.yml \
  --ref backend-ops \
  -f task=export-platform-images

gh run list \
  --workflow tss-aiplatform-internal-validation.yml \
  --branch backend-ops \
  --event workflow_dispatch
```

Use the successful run ID and its exact commit SHA shown by GitHub:

```bash
# Preferred on the reviewed seu4080 Runner: download and verify without sudo
# or changing Docker. The export SHA may be an ancestor of the current
# backend-ops SHA only when both platform image locks are byte-identical.
gh workflow run tss-aiplatform-internal-validation.yml \
  --ref backend-ops \
  -f task=stage-platform-images \
  -f platform_run_id=REPLACE_RUN_ID \
  -f platform_head_sha=REPLACE_40_CHARACTER_SHA

# Direct operator download remains available when the network permits it.
gh run download REPLACE_RUN_ID \
  --name tss-aiplatform-platform-images-REPLACE_40_CHARACTER_SHA \
  --dir /path/to/new-empty-directory

cd /path/to/new-empty-directory
sha256sum --check --strict platform-images.sha256
docker load --input platform-images-amd64.tar
```

After import, the platform verifier checks both the immutable source baseline
and a canonical runtime-content fingerprint. This is required because Docker
29 with the containerd image store can rewrite a local image ID while retaining
the same filesystem and execution configuration; content changes are still
rejected.

The Actions artifact is intentionally retained for seven days to avoid using
GitHub as a permanent binary backup. The committed lock and exporter do not
expire; rerun the workflow to recreate the same bundle from the same digests.

For an existing platform whose four base services are already verified, do
not move the complete five-image bundle just to update the application. Run
`export-backend-image` instead. It exports only the single backend entry from
the same lock, with its checksum and source metadata. This is the disk-minimal
path used to keep the internal platform aligned with Main.

Interrupted range downloads retain only the private, run-specific partial
directory and resume its verified byte ranges on the next identical run. A
push/PR validation run uses a different concurrency key, so it cannot cancel a
manually dispatched long transfer.

## Stage the minimal C6 CPU runtime bundle

C6 does not import all four historical runtime images. It locks and exports
the CV training, NLP training and CPU inference images currently referenced by
the platform in `cpu-runtime-images.lock`. The lock also records the exact names that Kubernetes
must resolve after import. This prevents a worker from silently pulling a
different image and avoids using the laboratory's shared Docker image store.

```bash
gh workflow run tss-aiplatform-internal-validation.yml \
  --ref backend-ops \
  -f task=export-cpu-runtime-images

# After the export succeeds, use its run ID and exact backend-ops SHA.
gh workflow run tss-aiplatform-internal-validation.yml \
  --ref backend-ops \
  -f task=stage-cpu-runtime-images \
  -f runtime_run_id=REPLACE_RUN_ID \
  -f runtime_head_sha=REPLACE_40_CHARACTER_SHA
```

The staging job only downloads and verifies files under the isolated Runner
staging directory. It does not use sudo or import images. On the reviewed
worker, first run the no-write check and then the explicit node-confirmed
import:

```bash
sudo /srv/tss-AIplatform/repository/deploy/tss-aiplatform-internal/scripts/import-cpu-runtime-images.sh \
  --check /etc/tss-aiplatform/node.env /absolute/staged/bundle
sudo /srv/tss-AIplatform/repository/deploy/tss-aiplatform-internal/scripts/import-cpu-runtime-images.sh \
  --apply /etc/tss-aiplatform/node.env /absolute/staged/bundle \
  --confirm-node REPLACE_CPU_CAPABLE_NODE
```

The exporter verifies source registry manifest digests and linux/amd64 image
IDs. The importer verifies bundle checksums, the committed three-image lock,
imported config digests and Kubernetes runtime aliases. The split is required
because `docker image save` can reserialize a platform image under a different
local OCI manifest descriptor while preserving its locked config and layers.
The importer also verifies that the shared Docker container count and shared
containerd PID do not change.

## Stage the minimal GPU runtime bundle

The new GPU bundle contains the CV and NLP training workers plus the GPU
inference worker built from the same full commit. A historical two-image lock
remains importable during the rolling upgrade, but exact GPU inference must not
be enabled until the lock contains `gpu-inference`. Shared CUDA/PyTorch layers
are stored once in the combined archive. Build, export, stage and import remain
separate operations:

```bash
gh workflow run tss-aiplatform-internal-validation.yml \
  --ref backend-gpu \
  -f task=export-gpu-runtime-images

gh workflow run tss-aiplatform-internal-validation.yml \
  --ref backend-gpu \
  -f task=stage-gpu-runtime-images \
  -f gpu_runtime_run_id=REPLACE_RUN_ID \
  -f gpu_runtime_head_sha=REPLACE_40_CHARACTER_SHA

gh workflow run tss-aiplatform-internal-validation.yml \
  --ref backend-gpu \
  -f task=deploy-gpu-runtime-images \
  -f gpu_runtime_run_id=REPLACE_RUN_ID \
  -f gpu_runtime_head_sha=REPLACE_40_CHARACTER_SHA
```

The staging task performs no root or containerd write. The deployment task
imports only into the isolated project containerd and verifies both config
digests and runtime aliases. It deliberately does not install the NVIDIA Device
Plugin, change node labels or submit a training Job. Those operations remain
behind the separate GPU-worker gate and the immediate shared-host idle check.

## Minimal clean-server flow

1. Check out the reviewed deployment branch/SHA.
2. Install the operating-system prerequisites and pinned Kubernetes version
   documented in `../versions.env`.
3. Generate the Kubernetes air-gap bundles from GitHub Actions and import them
   with the existing guarded scripts.
4. Generate the five platform-image bundle from GitHub Actions and load it with
   `docker load`.
5. Create node/platform environment files from the committed examples and
   generate fresh Secrets.
6. Bootstrap the empty platform and run its verification scripts.
7. Build the locked frontend commit and install the reviewed Nginx template.
8. Import the locked CPU runtime images and run real CV/NLP acceptance tasks.
9. For a GPU worker, import the locked CV/NLP GPU runtime bundle and the locked
   NVIDIA infrastructure bundle. Then enable `TSS_ENABLE_GPU_WORKER`, rerun the
   guarded platform bootstrap, require a positive `nvidia.com/gpu` capacity and
   verify the DCGM InternalIP endpoint before any single-GPU acceptance task.

Steps 7 and 8 are required for a complete user-facing CPU platform. Finishing
only the five base containers is an empty-platform milestone, not full
acceptance. Step 9 installs GPU discovery and monitoring infrastructure only. GPU business
behavior is claimed only after the immutable workload image and the guarded
single-GPU real test both pass; on a shared host, Kubernetes capacity never
replaces the immediate host-level idle check.
