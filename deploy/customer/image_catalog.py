#!/usr/bin/env python3
"""从已审阅的锁文件生成交付清单；不拉镜像，不把清单误称为已导出的离线包。"""
import hashlib
import json
import re
from pathlib import Path


INTERNAL = Path('deploy/tss-aiplatform-internal')
DIGEST = re.compile(r'^sha256:[0-9a-f]{64}$')
REF = re.compile(r'^[a-z0-9][A-Za-z0-9._:/@-]+$')


def source_text(root, relative):
    path = root / relative
    if path.is_symlink() or not path.is_file():
        raise ValueError('缺少普通源码文件：' + str(relative))
    return path.read_text(encoding='utf-8')


def collect_catalog(root):
    images = []
    manifests = []
    locks = {}

    def read_lock(relative):
        text = source_text(root, relative)
        # 与 Git 文本保持一致，不让 Windows checkout 的 CRLF 改变交付清单。
        locks[str(relative).replace('\\', '/')] = hashlib.sha256(text.encode('utf-8')).hexdigest()
        return [line for line in text.splitlines() if line and not line.startswith('#')]

    def add(source, digest, alias, purpose, store, image_id=None, fingerprint=None):
        if (not REF.fullmatch(source) or source.endswith(':latest') or '@' in source
                or not DIGEST.fullmatch(digest) or not REF.fullmatch(alias)):
            raise ValueError('无效镜像锁：' + source)
        if image_id is not None and not DIGEST.fullmatch(image_id):
            raise ValueError('无效镜像 ID：' + source)
        if fingerprint is not None and not re.fullmatch('[0-9a-f]{64}', fingerprint):
            raise ValueError('无效运行内容指纹：' + source)
        if any(row['runtime_ref'] == alias for row in images):
            raise ValueError('镜像运行名称重复：' + alias)
        images.append(dict(source=source, digest=digest, runtime_ref=alias, purpose=purpose,
                           store=store, image_id=image_id, runtime_fingerprint=fingerprint))

    for line in read_lock(INTERNAL / 'artifacts.lock'):
        kind, source, digest = line.split()
        if not DIGEST.fullmatch(digest):
            raise ValueError('无效基础设施摘要')
        if kind == 'image':
            add(source, digest, source, 'kubernetes', 'containerd:k8s.io')
        elif kind == 'manifest' and source.startswith('https://'):
            manifests.append(dict(source=source, sha256=digest[7:]))
        else:
            raise ValueError('未知基础设施锁条目')

    platform_rows = read_lock(INTERNAL / 'platform/platform-images.lock')
    if len(platform_rows) != 5:
        raise ValueError('平台锁必须包含五个已审阅服务')
    for line in platform_rows:
        source, digest, alias, image_id, fingerprint, budget = line.split('|')
        if not budget.isdigit() or int(budget) <= 0:
            raise ValueError('镜像容量预算无效')
        add(source, digest, alias, alias.split('/')[-1].split(':')[0], 'docker', image_id, fingerprint)

    for name in ('cpu-runtime-images.lock', 'gpu-runtime-images.lock'):
        for line in read_lock(INTERNAL / 'reproducible' / name):
            source, digest, image_id, alias, purpose, _producer = line.split('|')
            add(source, digest, alias, purpose, 'containerd:k8s.io', image_id)

    frontend_rows = read_lock(INTERNAL / 'platform/frontend-image.lock')
    if len(frontend_rows) != 1:
        raise ValueError('前端锁必须且只能有一个镜像')
    source, digest, alias, image_id, fingerprint, _budget = frontend_rows[0].split('|')
    add(source, digest, alias, 'frontend', 'containerd:k8s.io', image_id, fingerprint)
    required = {'postgres', 'redis', 'minio', 'mlflow-lite', 'backend', 'frontend',
                'cv-training', 'nlp-training', 'cpu-inference', 'cv-gpu-training',
                'nlp-gpu-training', 'gpu-inference'}
    if not required.issubset({row['purpose'] for row in images}):
        raise ValueError('清单缺少当前业务镜像')
    return dict(schema_version=1, architecture='linux/amd64', images=images,
                manifests=manifests, lock_sha256=locks, archives_verified=False,
                notes=['此清单不包含镜像文件；导出后仍需核对归档校验和、运行别名及摘要。',
                       '不包含 Ubuntu 系统；平台运行依赖及驱动先检查现有安装，缺项按目标版本补齐，不包含用户数据或凭据。',
                       '运行别名中的原仓库名称是本地查找键，不代表安装时需要访问该仓库。'])


if __name__ == '__main__':
    print(json.dumps(collect_catalog(Path(__file__).resolve().parents[2]), ensure_ascii=False, indent=2))
