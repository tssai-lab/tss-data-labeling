#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -eq 1 ]] || die "usage: $0 APPLICATION_SHA"
application_sha=$1
[[ $application_sha =~ ^[0-9a-f]{40}$ ]] \
  || die "application SHA must contain exactly 40 lowercase hexadecimal characters"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
platform_root="$(cd "${script_dir}/.." && pwd)"
lock_file=${platform_root}/platform-images.lock
environment_file=${platform_root}/platform.env.example
source_ref="ghcr.io/tssai-lab/tssai-backend:${application_sha}"
budget_bytes=734003200

for command_name in docker python3 sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: ${command_name}"
done
docker buildx version >/dev/null 2>&1 || die "Docker Buildx is required"
[[ -f $lock_file && ! -L $lock_file && -f $environment_file && ! -L $environment_file ]] \
  || die "platform image lock or environment example is absent or symbolic"

extract_digest() {
  awk '$1 == "Digest:" && digest == "" {digest = $2} END {print digest}'
}

manifest_digest="$(docker buildx imagetools inspect "$source_ref" | extract_digest)"
[[ $manifest_digest =~ ^sha256:[0-9a-f]{64}$ ]] \
  || die "published backend manifest digest is invalid"
immutable_ref="${source_ref}@${manifest_digest}"

cleanup() {
  docker image rm "$immutable_ref" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker pull --quiet --platform linux/amd64 "$immutable_ref" >/dev/null
image_id="$(docker image inspect --format '{{.Id}}' "$immutable_ref")"
image_size="$(docker image inspect --format '{{.Size}}' "$immutable_ref")"
image_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$immutable_ref")"
fingerprint="$(
  docker image inspect "$immutable_ref" \
    | python3 "${script_dir}/image-runtime-fingerprint.py"
)"

[[ $image_id =~ ^sha256:[0-9a-f]{64}$ ]] || die "published backend image ID is invalid"
[[ $image_size =~ ^[1-9][0-9]*$ && $image_size -le $budget_bytes ]] \
  || die "published backend image exceeds the reviewed disk budget"
[[ $image_platform == linux/amd64 ]] || die "published backend image is not linux/amd64"
[[ $fingerprint =~ ^[0-9a-f]{64}$ ]] || die "published backend runtime fingerprint is invalid"

image_id_short=${image_id#sha256:}
project_ref="tss-aiplatform-internal/backend:${application_sha:0:8}-${image_id_short:0:12}"
replacement="${source_ref}|${manifest_digest}|${project_ref}|${image_id}|${fingerprint}|${budget_bytes}"

python3 - "$lock_file" "$replacement" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
replacement = sys.argv[2]
lines = path.read_text(encoding="utf-8").splitlines()
matched = [index for index, line in enumerate(lines) if line.startswith("ghcr.io/tssai-lab/tssai-backend:")]
if len(matched) != 1:
    raise SystemExit("backend image lock row is not unique")
lines[matched[0]] = replacement
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

python3 - "$environment_file" "$project_ref" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
project_ref = sys.argv[2]
lines = path.read_text(encoding="utf-8").splitlines()
matched = [index for index, line in enumerate(lines) if line.startswith("TSS_BACKEND_IMAGE=")]
if len(matched) != 1:
    raise SystemExit("backend image environment row is not unique")
lines[matched[0]] = f"TSS_BACKEND_IMAGE={project_ref}"
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

"${script_dir}/export-platform-images.sh" --validate-only >/dev/null
grep -Fx "$replacement" "$lock_file" >/dev/null \
  || die "promoted backend image lock could not be reread"
grep -Fx "TSS_BACKEND_IMAGE=${project_ref}" "$environment_file" >/dev/null \
  || die "promoted backend environment alias could not be reread"

echo "PASS: promoted backend image lock for application ${application_sha}"
