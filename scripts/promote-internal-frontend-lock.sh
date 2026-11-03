#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  echo "ERROR: $*" >&2
  exit 1
}

candidate=${1:-}
infrastructure_root=${2:-}
expected_source_sha=${3:-}
[[ $candidate == /* && $infrastructure_root == /* ]] \
  || die "usage: $0 /absolute/candidate /absolute/infrastructure-root <frontend-sha>"
[[ $expected_source_sha =~ ^[0-9a-f]{40}$ ]] || die "frontend source SHA is invalid"
[[ -f $candidate && ! -L $candidate ]] || die "frontend lock candidate is absent or symbolic"
[[ $(git -C "$infrastructure_root" rev-parse --is-inside-work-tree 2>/dev/null || true) == true ]] \
  || die "infrastructure checkout is not a Git worktree"

target=${infrastructure_root}/deploy/tss-aiplatform-internal/platform/frontend-image.lock
[[ -f $target && ! -L $target ]] || die "protected frontend lock is absent or symbolic"
mapfile -t candidate_lines < <(grep -Ev '^(#|$)' "$candidate")
[[ ${#candidate_lines[@]} -eq 1 ]] || die "frontend lock candidate must contain one image"
IFS='|' read -r source_ref manifest_digest runtime_ref image_id fingerprint budget_bytes extra \
  <<<"${candidate_lines[0]}"
[[ -z ${extra:-} \
  && $source_ref == "ghcr.io/tssai-lab/tssai-frontend:${expected_source_sha}" \
  && $manifest_digest =~ ^sha256:[0-9a-f]{64}$ \
  && $runtime_ref == "docker.io/library/tss-aiplatform-frontend:${expected_source_sha:0:12}" \
  && $image_id =~ ^sha256:[0-9a-f]{64}$ \
  && $fingerprint =~ ^[0-9a-f]{64}$ \
  && $budget_bytes == 268435456 ]] \
  || die "frontend lock candidate identity differs"

pending=$(mktemp "${target}.XXXXXX")
trap 'rm -f "$pending"' EXIT
{
  echo '# source_ref|source_manifest_digest|runtime_ref|linux_amd64_image_id|runtime_fingerprint_sha256|conservative_budget_bytes'
  echo "${candidate_lines[0]}"
} >"$pending"
chmod 0644 "$pending"
mv "$pending" "$target"
trap - EXIT
echo "PASS: internal frontend lock promoted source=${expected_source_sha}"
