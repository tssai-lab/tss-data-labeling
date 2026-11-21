"""Build and verify a self-contained, versioned labeling release (stdlib only)."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import zipfile
from email.parser import Parser

SERVICE = Path(__file__).resolve().parents[1]


def digest(stream):
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        result.update(block)
    return result.hexdigest()


def safe_name(name):
    path = PurePosixPath(name)
    if not path.parts or '\\' in name or ':' in name or '\0' in name or path.is_absolute() or '..' in path.parts or str(path) != name:
        raise ValueError('Unsafe release path: ' + name)
    return name


def build(jar, wheelhouse, revision, output):
    if not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise ValueError('A full source commit SHA is required')
    files = {'service.jar': jar, 'release/runtime_check.py': SERVICE / 'scripts/runtime_check.py',
             'release/backup_database.py': SERVICE / 'scripts/backup_database.py'}
    source = SERVICE / 'ls-source-adapter'
    for path in sorted(source.rglob('*')):
        relative = path.relative_to(source)
        if any(part in ('__pycache__', '.test-jobs', 'tests', '.git') for part in relative.parts):
            continue
        if path.is_symlink():
            raise ValueError('Source symlinks are not release inputs')
        if path.is_file() and path.suffix not in ('.pyc', '.log'):
            files['ls-source-adapter/' + relative.as_posix()] = path
    packages = {}
    for wheel in sorted(wheelhouse.glob('*.whl')):
        if wheel.is_symlink() or not (wheel.name.endswith('-any.whl') or 'manylinux' in wheel.name and wheel.name.endswith('_x86_64.whl')):
            raise ValueError('Only Linux x86_64 or universal wheels are allowed')
        with zipfile.ZipFile(wheel) as archive:
            metadata = [n for n in archive.namelist() if n.endswith('.dist-info/METADATA')]
            if len(metadata) != 1:
                raise ValueError('Invalid wheel metadata')
            headers = Parser().parsestr(archive.read(metadata[0]).decode('utf-8'))
        name = re.sub(r'[-_.]+', '-', headers['Name']).lower()
        version = headers['Version']
        if not re.fullmatch(r'[a-z0-9-]+', name) or not re.fullmatch(r'[a-zA-Z0-9.!+_-]+', version) or name in packages:
            raise ValueError('Invalid or duplicate wheel package')
        with wheel.open('rb') as stream:
            packages[name] = (version, digest(stream))
        files['wheels/' + wheel.name] = wheel
    # Every directly pinned dependency must be present; transitive wheels are
    # pinned and hashed as well, so installation never consults a package index.
    for line in (source / 'requirements.txt').read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        name, version = line.strip().split('==')
        if packages.get(re.sub(r'[-_.]+', '-', name).lower(), (None,))[0] != version:
            raise ValueError('Missing pinned runtime dependency: ' + name)
    lock = ''.join(f'{name}=={version} --hash=sha256:{sha}\n' for name, (version, sha) in sorted(packages.items())).encode()
    if lock != (source / 'requirements.lock').read_bytes():
        raise ValueError('Wheel contents differ from the committed Linux/Python 3.11 dependency lock')
    generated = {'requirements.lock': lock}
    manifest = {'schemaVersion': 1, 'revision': revision, 'python': '3.11', 'platform': 'linux-x86_64', 'files': {}}
    for name, path in files.items():
        safe_name(name)
        if not path.is_file() or path.is_symlink():
            raise ValueError('Missing release input: ' + name)
        with path.open('rb') as stream:
            manifest['files'][name] = digest(stream)
    manifest['files']['requirements.lock'] = hashlib.sha256(lock).hexdigest()
    generated['release.json'] = json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2).encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, 'w:gz') as archive:
        for name in sorted(set(files) | set(generated)):
            info = tarfile.TarInfo(name)
            info.mode = 0o644
            if name in generated:
                info.size = len(generated[name])
                archive.addfile(info, io.BytesIO(generated[name]))
            else:
                info.size = files[name].stat().st_size
                with files[name].open('rb') as stream:
                    archive.addfile(info, stream)
    print(json.dumps({'artifact': str(output), 'revision': revision, 'files': len(manifest['files']), 'packages': len(packages)}))


def verify(artifact, revision=None, destination=None):
    with tarfile.open(artifact, 'r:gz') as archive:
        members = archive.getmembers()
        names = set()
        for member in members:
            safe_name(member.name)
            if not member.isfile() or member.name in names or member.size > 512 * 1024 * 1024:
                raise ValueError('Invalid, duplicate or oversized release entry')
            names.add(member.name)
        if sum(m.size for m in members) > 2 * 1024 ** 3:
            raise ValueError('Release exceeds size limit')
        metadata = archive.getmember('release.json')
        if metadata.size > 1024 * 1024:
            raise ValueError('Release manifest exceeds size limit')
        manifest = json.load(archive.extractfile(metadata))
        if manifest.get('schemaVersion') != 1 or manifest.get('python') != '3.11' or manifest.get('platform') != 'linux-x86_64':
            raise ValueError('Incompatible release manifest')
        if revision is not None and manifest['revision'] != revision:
            raise ValueError('Source revision mismatch')
        if names != set(manifest['files']) | {'release.json'}:
            raise ValueError('Release contents differ from manifest')
        for member in members:
            if member.name != 'release.json' and digest(archive.extractfile(member)) != manifest['files'][member.name]:
                raise ValueError('Release checksum mismatch: ' + member.name)
        if destination is not None:
            if destination.is_symlink() or destination.exists() and any(destination.iterdir()):
                raise ValueError('Extraction requires a new, empty release directory')
            destination.mkdir(parents=True, exist_ok=True)
            for member in members:
                target = destination / member.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o644)
        return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    create = sub.add_parser('build')
    create.add_argument('--jar', type=Path, required=True)
    create.add_argument('--wheelhouse', type=Path, required=True)
    create.add_argument('--revision', required=True)
    create.add_argument('--output', type=Path, required=True)
    check = sub.add_parser('verify')
    check.add_argument('artifact', type=Path)
    check.add_argument('--revision')
    check.add_argument('--destination', type=Path)
    args = parser.parse_args()
    if args.command == 'build':
        build(args.jar, args.wheelhouse, args.revision, args.output)
    else:
        print(json.dumps(verify(args.artifact, args.revision, args.destination)))
