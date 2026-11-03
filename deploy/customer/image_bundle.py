#!/usr/bin/env python3
"""检查现有项目镜像归档并压缩；不 pull/tag/load，不连接或修改运行集群。"""
import argparse
import gzip
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path, PurePosixPath

from image_catalog import collect_catalog

GROUPS = {'platform': {'postgres', 'redis', 'minio', 'mlflow-lite', 'backend'},
          'cpu': {'cv-training', 'nlp-training', 'cpu-inference'},
          'gpu': {'cv-gpu-training', 'nlp-gpu-training', 'gpu-inference'},
          'frontend': {'frontend'}}
PROJECT_IMAGE_COUNT = sum(len(purposes) for purposes in GROUPS.values())
CHUNK = 4 * 1024 * 1024


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key: ' + key)
        result[key] = value
    return result


def load_json(data):
    return json.loads(data, object_pairs_hook=unique)


def project_groups(catalog):
    result = {name: [] for name in GROUPS}
    seen = set()
    for row in catalog['images']:
        if row['purpose'] == 'kubernetes':
            continue
        matches = [name for name, purposes in GROUPS.items() if row['purpose'] in purposes]
        if len(matches) != 1 or row['purpose'] in seen:
            raise ValueError('unknown or duplicate project image purpose')
        seen.add(row['purpose'])
        result[matches[0]].append(row)
    if any({row['purpose'] for row in result[name]} != purposes for name, purposes in GROUPS.items()):
        raise ValueError('project image list is incomplete')
    return result


def safe_path(path):
    path = Path(path).absolute()
    if '..' in path.parts:
        raise ValueError('path traversal refused')
    for candidate in [path] + list(path.parents):
        if candidate.is_symlink():
            raise ValueError('symbolic path refused: ' + str(candidate))
    return path


def require_new_directory(path):
    path = safe_path(path)
    if path.exists():
        raise FileExistsError('output already exists: ' + str(path))
    if not path.parent.is_dir():
        raise ValueError('output parent must already exist')
    return path


def hash_stream(stream):
    digest = hashlib.sha256()
    while True:
        chunk = stream.read(CHUNK)
        if not chunk:
            return digest.hexdigest()
        digest.update(chunk)


def hash_file(path):
    with safe_path(path).open('rb') as stream:
        return hash_stream(stream)


def normalize_ref(ref):
    # Docker save 有时省略默认 registry/library；不改非默认仓库的名字。
    if ref.startswith('docker.io/'):
        ref = ref[len('docker.io/'):]
    if ref.startswith('library/'):
        ref = ref[len('library/'):]
    return ref


def config_fingerprint(payload, fingerprinter):
    process = subprocess.run([sys.executable, str(safe_path(fingerprinter))], input=payload,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    if process.returncode:
        raise ValueError('runtime fingerprint calculation failed')
    return process.stdout.decode('ascii').strip()


def inspect_archive(path, expected, fingerprinter):
    """只读 tar，不向文件系统解包；实际层内容必须匹配镜像 config 的 diff_ids。"""
    path = safe_path(path)
    if not path.is_file():
        raise ValueError('source archive is missing')
    verified_layers = {}
    results = []
    with tarfile.open(path, 'r:') as archive:
        members = {}
        for member in archive:
            name, parts = member.name, PurePosixPath(member.name)
            if (not name or parts.is_absolute() or '\\' in name or '..' in parts.parts
                    or str(parts) != name.rstrip('/') or name in members
                    or not (member.isfile() or member.isdir())):
                raise ValueError('unsafe or duplicate archive member: ' + name)
            if len(members) > 100000:
                raise ValueError('too many archive members')
            members[name] = member

        def metadata(name):
            member = members.get(name)
            if member is None or not member.isfile() or member.size > 16 * 1024 * 1024:
                raise ValueError('missing or oversized archive metadata: ' + str(name))
            return archive.extractfile(member).read()

        manifest = load_json(metadata('manifest.json'))
        if not isinstance(manifest, list) or len(manifest) != len(expected):
            raise ValueError('archive image count does not match selected group')
        remaining = list(expected)
        for image in manifest:
            tags = image.get('RepoTags') or []
            matches = [row for row in remaining if any(normalize_ref(tag) in
                       {normalize_ref(row['source']), normalize_ref(row['runtime_ref'])} for tag in tags)]
            if len(matches) != 1:
                raise ValueError('unknown, duplicate or ambiguous archive image tag')
            row = matches[0]
            if any(normalize_ref(tag) not in {normalize_ref(row['source']), normalize_ref(row['runtime_ref'])} for tag in tags):
                raise ValueError('archive contains an unreviewed image alias')
            payload = metadata(image['Config'])
            actual_id = 'sha256:' + hashlib.sha256(payload).hexdigest()
            config = load_json(payload)
            if config.get('os') != 'linux' or config.get('architecture') != 'amd64':
                raise ValueError('archive image is not linux/amd64')
            fingerprint = config_fingerprint(payload, fingerprinter)
            # 精确 config 摘要可证明原始配置字节一致；Docker inspect 与 OCI config
            # 的默认字段表示可能不同，仅在 ID 被序列化重写时使用既有运行指纹规则。
            # 无论哪种身份验证，下面仍逐层验证实际字节的 diff_ids。
            if actual_id != row['image_id']:
                if not row.get('runtime_fingerprint'):
                    raise ValueError('archive config digest differs from lock')
                if fingerprint != row['runtime_fingerprint']:
                    raise ValueError('archive runtime fingerprint differs from lock')
            layers, diff_ids = image.get('Layers'), config.get('rootfs', {}).get('diff_ids')
            if not isinstance(layers, list) or not layers or not isinstance(diff_ids, list) or len(layers) != len(diff_ids):
                raise ValueError('archive layer list is incomplete')
            for name, expected_diff in zip(layers, diff_ids):
                if name not in verified_layers:
                    member = members.get(name)
                    if member is None or not member.isfile():
                        raise ValueError('missing archive layer')
                    stream = archive.extractfile(member)
                    signature = stream.read(2)
                    stream.seek(0)
                    reader = gzip.GzipFile(fileobj=stream) if signature == b'\x1f\x8b' else stream
                    verified_layers[name] = 'sha256:' + hash_stream(reader)
                    reader.close()
                    stream.close()
                if verified_layers[name] != expected_diff:
                    raise ValueError('archive layer content differs from config')
            results.append(dict(purpose=row['purpose'], actual_config_digest=actual_id,
                                runtime_fingerprint=fingerprint, verified_layers=len(layers),
                                source_ref=row['source'], runtime_ref=row['runtime_ref']))
            remaining.remove(row)
    return results


def fingerprint_file(path):
    info = path.stat()
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def write_json(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write('\n')


def create_group(group, source, output, catalog, fingerprinter):
    rows = project_groups(catalog)[group]
    source, output = safe_path(source), require_new_directory(output)
    before = fingerprint_file(source)
    if shutil.disk_usage(output.parent).free < before[2] * 1.1 + 10 * 1024 ** 3:
        raise ValueError('insufficient output filesystem space, including 10 GiB reserve')
    print('VERIFY ' + group + ': config and layer bytes', flush=True)
    evidence = inspect_archive(source, rows, fingerprinter)
    if fingerprint_file(source) != before:
        raise ValueError('source archive changed during verification')
    output.mkdir(mode=0o700)
    filename = group + '.tar.gz'
    print('COMPRESS ' + group + ': low-memory gzip level 1', flush=True)
    source_hash = hashlib.sha256()
    with source.open('rb') as src, (output / filename).open('xb') as dest:
        with gzip.GzipFile(filename='', mode='wb', fileobj=dest, compresslevel=1, mtime=0) as compressed:
            for chunk in iter(lambda: src.read(CHUNK), b''):
                source_hash.update(chunk)
                compressed.write(chunk)
        dest.flush()
        os.fsync(dest.fileno())
    if fingerprint_file(source) != before:
        raise ValueError('source archive changed during compression; incomplete output retained')
    archive_hash = hash_file(output / filename)
    receipt = dict(schema_version=1, group=group, archive=filename,
                   archive_sha256=archive_hash, archive_bytes=(output / filename).stat().st_size,
                   source_archive_sha256=source_hash.hexdigest(), images=rows, evidence=evidence,
                   installation_verified=False)
    # 最后写完成回执；中断或错误不制造成功标记，也不删除现场。
    write_json(output / 'GROUP_COMPLETE.json', receipt)
    print('GROUP_COMPLETE ' + group + ' ' + archive_hash, flush=True)


def verify_group(directory, group, catalog):
    directory = safe_path(directory)
    if {p.name for p in directory.iterdir()} != {group + '.tar.gz', 'GROUP_COMPLETE.json'}:
        raise ValueError('group has incomplete or extra files: ' + group)
    receipt = load_json(safe_path(directory / 'GROUP_COMPLETE.json').read_text(encoding='utf-8'))
    if (receipt.get('schema_version') != 1 or receipt.get('group') != group
            or receipt.get('archive') != group + '.tar.gz'
            or receipt.get('images') != project_groups(catalog)[group]
            or receipt.get('installation_verified') is not False):
        raise ValueError('group receipt does not match selected source locks')
    archive = safe_path(directory / receipt['archive'])
    if (not archive.is_file() or archive.stat().st_size != receipt.get('archive_bytes')
            or hash_file(archive) != receipt.get('archive_sha256')):
        raise ValueError('group archive checksum mismatch')
    return receipt


def finish_bundle(directory, catalog):
    directory = safe_path(directory)
    existing = {p.name for p in directory.iterdir()}
    if existing not in (set(GROUPS), set(GROUPS) | {'online-infrastructure.json'}):
        raise ValueError('bundle must contain exactly four complete groups before sealing')
    receipts = {name: verify_group(directory / name, name, catalog) for name in GROUPS}
    online = dict(images=[row for row in catalog['images'] if row['purpose'] == 'kubernetes'],
                  manifests=catalog['manifests'], installation='online, pinned versions and digests')
    online_path = safe_path(directory / 'online-infrastructure.json')
    if online_path.exists():
        if load_json(online_path.read_text(encoding='utf-8')) != online:
            raise ValueError('partial online infrastructure metadata differs from locks')
    else:
        write_json(online_path, online)
    files = {name + '/GROUP_COMPLETE.json': hash_file(directory / name / 'GROUP_COMPLETE.json') for name in GROUPS}
    files.update({name + '/' + receipts[name]['archive']: receipts[name]['archive_sha256'] for name in GROUPS})
    files['online-infrastructure.json'] = hash_file(directory / 'online-infrastructure.json')
    write_json(directory / 'BUNDLE_COMPLETE.json', dict(schema_version=1,
               project_images=PROJECT_IMAGE_COUNT,
               files=files, lock_sha256=catalog['lock_sha256'], installation_verified=False))
    print('BUNDLE_COMPLETE {} project images; installation is NOT verified'.format(
          PROJECT_IMAGE_COUNT), flush=True)


def verify_bundle(directory, catalog):
    directory = safe_path(directory)
    if {p.name for p in directory.iterdir()} != set(GROUPS) | {'online-infrastructure.json', 'BUNDLE_COMPLETE.json'}:
        raise ValueError('incomplete bundle or unexpected files')
    manifest = load_json(safe_path(directory / 'BUNDLE_COMPLETE.json').read_text(encoding='utf-8'))
    expected_files = {'online-infrastructure.json'} | {group + '/' + filename for group in GROUPS
                        for filename in ('GROUP_COMPLETE.json', group + '.tar.gz')}
    if (manifest.get('schema_version') != 1
            or manifest.get('project_images') != PROJECT_IMAGE_COUNT
            or manifest.get('lock_sha256') != catalog['lock_sha256']
            or manifest.get('installation_verified') is not False
            or set(manifest.get('files', {})) != expected_files):
        raise ValueError('bundle completion identity differs from locks')
    for name in GROUPS:
        verify_group(directory / name, name, catalog)
    # 大归档已在 verify_group 校验，不为重复校验再读一遍数十 GB 文件。
    for name, digest in manifest['files'].items():
        if name.endswith('.tar.gz'):
            receipt = load_json((directory / name.split('/')[0] / 'GROUP_COMPLETE.json').read_text())
            if digest != receipt['archive_sha256']:
                raise ValueError('bundle archive receipt mismatch')
        elif hash_file(directory / name) != digest:
            raise ValueError('bundle metadata checksum mismatch')
    return PROJECT_IMAGE_COUNT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, help='已审阅的完整 image-catalog.json')
    parser.add_argument('--fingerprinter', type=Path, help='项目原有 image-runtime-fingerprint.py')
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument('--group', choices=GROUPS)
    actions.add_argument('--finish', type=Path)
    actions.add_argument('--verify', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.group and not all((args.source, args.output, args.fingerprinter)):
        parser.error('--group requires --source, --output and --fingerprinter')
    try:
        catalog = load_json(safe_path(args.catalog).read_text(encoding='utf-8')) if args.catalog else collect_catalog(Path(__file__).resolve().parents[2])
        if args.group:
            create_group(args.group, args.source, args.output, catalog, args.fingerprinter)
        elif args.finish:
            finish_bundle(args.finish, catalog)
        else:
            print('VERIFIED {} project images; no installation performed'.format(verify_bundle(args.verify, catalog)))
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError, subprocess.SubprocessError) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
