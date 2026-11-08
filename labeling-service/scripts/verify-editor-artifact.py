#!/usr/bin/env python3
"""Fail before deployment if the executable JAR is missing or corrupting editor runtime assets."""
import argparse, hashlib, json, re, zipfile
PREFIX = 'BOOT-INF/classes/static/editor-assets/'
REVISION = 'df96d79183156e556d91825d15fa7721f1145e1a'

def verify(jar):
    with zipfile.ZipFile(jar) as archive:
        manifest = json.loads(archive.read(PREFIX + 'asset-manifest.json'))
        if manifest['version'] != '1.23.2' or manifest['sourceRevision'] != REVISION:
            raise ValueError('Unexpected editor version or source revision')
        expected = {entry['path'] for entry in manifest['files']}
        if not {'main.js', 'main.css', 'LICENSE.txt', '3rdpartylicenses.txt'} <= expected:
            raise ValueError('Incomplete editor manifest')
        actual = {name[len(PREFIX):] for name in archive.namelist() if name.startswith(PREFIX) and not name.endswith('/')}
        if actual != expected | {'asset-manifest.json'}:
            missing = (expected | {'asset-manifest.json'}) - actual
            extra = actual - (expected | {'asset-manifest.json'})
            raise ValueError('Packaged runtime differs: missing=' + ','.join(sorted(missing)) + '; extra=' + ','.join(sorted(extra)))
        for entry in manifest['files']:
            data = archive.read(PREFIX + entry['path'])
            if len(data) != entry['bytes'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Editor asset checksum mismatch: ' + entry['path'])
        css = archive.read(PREFIX + 'main.css').decode('utf-8')
        for match in re.findall(r'url\(([^)]+)\)', css):
            reference = match.strip(' \"\'')
            if reference.startswith(('data:', 'http:', 'https:', '#')):
                continue
            reference = reference.removeprefix('./').split('?', 1)[0].split('#', 1)[0]
            if reference not in expected:
                raise ValueError('CSS references missing editor resource: ' + reference)
    print('Verified editor 1.23.2: ' + str(len(expected)) + ' runtime/license files in executable JAR')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('jar')
    args = parser.parse_args()
    verify(args.jar)
