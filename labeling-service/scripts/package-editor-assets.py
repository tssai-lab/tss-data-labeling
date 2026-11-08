#!/usr/bin/env python3
"""Package reviewed standalone editor output; no node_modules, demo media or sourcemaps."""
import argparse, hashlib, json, zipfile
from pathlib import Path
VERSION = '1.23.2'
REVISION = 'df96d79183156e556d91825d15fa7721f1145e1a'
RUNTIME = {'.js', '.css', '.svg', '.png', '.jpg', '.jpeg', '.gif', '.ico', '.woff', '.woff2', '.ttf', '.wasm'}

def package(dist, license_path, output):
    files = {p.name: p.read_bytes() for p in dist.iterdir() if p.is_file() and (p.suffix in RUNTIME or 'license' in p.name.lower())}
    for required in ['main.js', 'main.css', '3rdpartylicenses.txt']:
        if required not in files: raise SystemExit('Missing editor runtime file: ' + required)
    files['LICENSE.txt'] = license_path.read_bytes()
    manifest = {'version': VERSION, 'sourceRevision': REVISION,
        'files': [{'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()} for name, data in sorted(files.items())]}
    files['asset-manifest.json'] = (json.dumps(manifest, indent=2, ensure_ascii=True) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (2026, 11, 7, 0, 0, 0))
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data, compresslevel=9)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.properties').write_text('editor.version=' + VERSION + '\neditor.source.revision=' + REVISION + '\neditor.bundle.sha256=' + digest + '\n', encoding='ascii')
    print(json.dumps({'files': len(files), 'bytes': output.stat().st_size, 'sha256': digest}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--dist', type=Path, required=True)
    parser.add_argument('--license', dest='license_path', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(args.dist, args.license_path, args.output)
