#!/usr/bin/env bash
# Explicit maintenance operation. Normal Maven/CI builds consume the reviewed bundle offline.
set -euo pipefail
service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
revision=df96d79183156e556d91825d15fa7721f1145e1a
[[ "$(node --version)" == v22.* ]] || { echo 'Node 22 is required'; exit 1; }
[[ "$(corepack yarn --version)" == 1.22.22 ]] || { echo 'Activate Yarn 1.22.22 through Corepack'; exit 1; }
source_dir="$(mktemp -d "${TMPDIR:-/tmp}/label-studio-editor.XXXXXXXX")"
# Leave the isolated source directory for inspection, including when an upstream build fails.
echo "Isolated upstream build: $source_dir"
git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/HumanSignal/label-studio.git
git -C "$source_dir" fetch --depth=1 origin "$revision"
git -C "$source_dir" checkout --detach FETCH_HEAD
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$revision" ]]
cd "$source_dir/web"
corepack yarn install --frozen-lockfile
NODE_ENV=production MODE=standalone NX_DAEMON=false NODE_OPTIONS=--max-old-space-size=4096 corepack yarn nx run editor:build:production
python3 "$service_dir/scripts/package-editor-assets.py" --dist "$source_dir/web/dist/libs/editor" --license "$source_dir/LICENSE" --output "$service_dir/third-party/label-studio/editor-assets-1.23.2.zip"
echo 'Review bundle, checksum properties, licenses and browser behavior before committing regenerated assets.'
