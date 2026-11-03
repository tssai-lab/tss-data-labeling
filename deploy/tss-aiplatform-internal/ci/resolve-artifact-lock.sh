#!/usr/bin/env bash
set -Eeuo pipefail

internal_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${internal_root}/versions.env"
core_images="${internal_root}/core-images.txt"
mode="${1:-resolve}"

calico_url="https://raw.githubusercontent.com/projectcalico/calico/${TSS_CALICO_VERSION}/manifests/calico.yaml"
nvidia_url="https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/${TSS_NVIDIA_DEVICE_PLUGIN_VERSION}/deployments/static/nvidia-device-plugin.yml"
metrics_url="https://github.com/kubernetes-sigs/metrics-server/releases/download/${TSS_METRICS_SERVER_VERSION}/components.yaml"
dcgm_image="nvcr.io/nvidia/k8s/dcgm-exporter:${TSS_DCGM_EXPORTER_VERSION}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

extract_digest() {
  awk '$1 == "Digest:" && digest == "" {digest = $2} END {print digest}'
}

extract_images() {
  awk '
    {
      for (field = 1; field < NF; field++) {
        if ($field == "image:") {
          image = $(field + 1)
          gsub(/["'\'' ]/, "", image)
          print image
        }
      }
    }
  '
}

[[ -f $core_images ]] || die "core image list is absent"
grep -Fx "$TSS_CONTAINERD_PAUSE_IMAGE" "$core_images" >/dev/null \
  || die "containerd pause image must match kubeadm's exact image list"
grep -Fx "registry.k8s.io/kube-apiserver:${TSS_KUBERNETES_VERSION}" "$core_images" >/dev/null \
  || die "kube-apiserver image must match the pinned Kubernetes version"
if grep -Ev '^(#.*|$|[a-z0-9.-]+(/[A-Za-z0-9._-]+)+:[A-Za-z0-9._-]+)$' "$core_images" >/dev/null; then
  die "core image list contains an invalid reference"
fi
if grep -F ':latest' "$core_images" >/dev/null; then
  die "latest image tags are forbidden"
fi
duplicate="$(grep -Ev '^(#|$)' "$core_images" | sort | uniq -d | head -n 1)"
[[ -z $duplicate ]] || die "duplicate core image: $duplicate"

if [[ $mode == --validate-only ]]; then
  echo "Artifact lock input contract passed."
  exit 0
fi
if [[ $mode == --self-test ]]; then
  digest="$(
    printf '%s\n' \
      'Name: example.invalid/test:v1' \
      'Digest: sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
      'MediaType: application/vnd.oci.image.index.v1+json' \
      | extract_digest
  )"
  [[ $digest == sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ]] \
    || die "digest parser self-test failed"
  mapfile -t self_test_images < <(
    printf '%s\n' \
      'image: registry.example/standalone:v1' \
      '- image: registry.example/list-item:v2' \
      | extract_images
  )
  [[ ${self_test_images[*]} == \
    'registry.example/standalone:v1 registry.example/list-item:v2' ]] \
    || die "image parser self-test failed"
  echo "Artifact lock digest parser self-test passed."
  exit 0
fi
[[ $mode == resolve ]] || die "usage: $0 [--validate-only|--self-test]"

for command_name in curl docker sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done
docker buildx version >/dev/null 2>&1 \
  || die "Docker Buildx is required to resolve manifest-list digests"

workdir="$(mktemp -d)"
cleanup() {
  rm -rf "$workdir"
}
trap cleanup EXIT

curl --fail --location --silent --show-error \
  "$calico_url" -o "${workdir}/calico.yaml"
curl --fail --location --silent --show-error \
  "$nvidia_url" -o "${workdir}/nvidia-device-plugin.yaml"
curl --fail --location --silent --show-error \
  "$metrics_url" -o "${workdir}/metrics-server-components.yaml"

calico_sha="$(sha256sum "${workdir}/calico.yaml" | awk '{print $1}')"
nvidia_sha="$(sha256sum "${workdir}/nvidia-device-plugin.yaml" | awk '{print $1}')"
metrics_sha="$(sha256sum "${workdir}/metrics-server-components.yaml" | awk '{print $1}')"
[[ $calico_sha =~ ^[0-9a-f]{64}$ && $nvidia_sha =~ ^[0-9a-f]{64}$ \
  && $metrics_sha =~ ^[0-9a-f]{64}$ ]] \
  || die "manifest SHA-256 resolution failed"

mapfile -t images < <(
  {
    grep -Ev '^(#|$)' "$core_images"
    extract_images < <(
      cat "${workdir}/calico.yaml" "${workdir}/nvidia-device-plugin.yaml" \
        "${workdir}/metrics-server-components.yaml"
    )
    printf '%s\n' "$dcgm_image"
  } | sort -u
)
(( ${#images[@]} >= 9 )) || die "resolved image set is unexpectedly small"

required_manifest_images=(
  "quay.io/calico/cni:${TSS_CALICO_VERSION}"
  "quay.io/calico/kube-controllers:${TSS_CALICO_VERSION}"
  "quay.io/calico/node:${TSS_CALICO_VERSION}"
  "nvcr.io/nvidia/k8s-device-plugin:${TSS_NVIDIA_DEVICE_PLUGIN_VERSION}"
  "$dcgm_image"
  "registry.k8s.io/metrics-server/metrics-server:${TSS_METRICS_SERVER_VERSION}"
)
for required_image in "${required_manifest_images[@]}"; do
  printf '%s\n' "${images[@]}" | grep -Fx "$required_image" >/dev/null \
    || die "required manifest image is absent: $required_image"
done

printf '# tss-AIplatform internal artifact lock candidate\n'
printf '# Commit this output only after independent review.\n'
printf 'manifest %s sha256:%s\n' "$calico_url" "$calico_sha"
printf 'manifest %s sha256:%s\n' "$nvidia_url" "$nvidia_sha"
printf 'manifest %s sha256:%s\n' "$metrics_url" "$metrics_sha"

for image in "${images[@]}"; do
  [[ $image != *:latest ]] || die "latest image tag is forbidden: $image"
  echo "Resolving ${image}" >&2
  digest="$(
    docker buildx imagetools inspect "$image" \
      | extract_digest
  )"
  [[ $digest =~ ^sha256:[0-9a-f]{64}$ ]] \
    || die "invalid digest for $image: $digest"
  printf 'image %s %s\n' "$image" "$digest"
done
