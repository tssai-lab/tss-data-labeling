"""仅用小型合成镜像归档验证交付边界，不调用 Docker/Kubernetes。"""
import hashlib
import ast
import gzip
import io
import json
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from image_bundle import (GROUPS, PROJECT_IMAGE_COUNT, create_group, finish_bundle, hash_file,
                          inspect_archive, project_groups, require_new_directory, verify_bundle,
                          verify_group, write_json)
from image_catalog import collect_catalog

ROOT = Path(__file__).resolve().parents[2]
FINGERPRINTER = ROOT / 'deploy/tss-aiplatform-internal/platform/scripts/image-runtime-fingerprint.py'


def fixture(path, *, corrupt=False, extra=None, ref='example/image:v1'):
    layer = b'synthetic immutable layer'
    config = json.dumps({'architecture': 'amd64', 'os': 'linux', 'config': {'User': '10001'},
                         'rootfs': {'type': 'layers', 'diff_ids': ['sha256:' + hashlib.sha256(layer).hexdigest()]}}).encode()
    config_id = hashlib.sha256(config).hexdigest()
    config_path = config_id + '.json'
    manifest = [{'Config': config_path, 'RepoTags': [ref], 'Layers': ['layer/layer.tar']}]
    with tarfile.open(path, 'w') as archive:
        for name, payload in [(config_path, config), ('layer/layer.tar', b'bad' if corrupt else layer),
                              ('manifest.json', json.dumps(manifest).encode())] + ([extra] if extra else []):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
    return {'source': ref, 'runtime_ref': ref, 'image_id': 'sha256:' + config_id,
            'runtime_fingerprint': None, 'purpose': 'cpu-inference', 'store': 'containerd:k8s.io',
            'digest': 'sha256:' + 'a' * 64}


class ImageBundleTest(unittest.TestCase):
    def test_partition_is_exactly_twelve_project_and_thirteen_online(self):
        groups = project_groups(collect_catalog(ROOT))
        self.assertEqual({key: len(value) for key, value in groups.items()},
                         {'platform': 5, 'cpu': 3, 'gpu': 3, 'frontend': 1})
        self.assertEqual(PROJECT_IMAGE_COUNT, 12)
        self.assertEqual(set(groups), set(GROUPS))
        self.assertFalse(any(row['purpose'] == 'kubernetes' for rows in groups.values() for row in rows))

    def test_missing_or_duplicate_purpose_is_rejected(self):
        for mode in ('missing', 'duplicate'):
            catalog = collect_catalog(ROOT)
            project = next(row for row in catalog['images'] if row['purpose'] == 'frontend')
            if mode == 'missing':
                catalog['images'].remove(project)
            else:
                catalog['images'].append(dict(project))
            with self.assertRaises(ValueError):
                project_groups(catalog)

    def test_valid_archive_checks_actual_config_and_layer(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'source.tar'
            row = fixture(path)
            result = inspect_archive(path, [row], FINGERPRINTER)
            self.assertEqual(result[0]['actual_config_digest'], row['image_id'])
            self.assertEqual(result[0]['verified_layers'], 1)

    def test_corrupt_layer_is_rejected_even_with_correct_tag_and_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'source.tar'
            row = fixture(path, corrupt=True)
            with self.assertRaisesRegex(ValueError, 'layer'):
                inspect_archive(path, [row], FINGERPRINTER)

    def test_unknown_image_tag_and_wrong_config_identity_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'source.tar'
            row = fixture(path)
            for change in ({'source': 'wrong:v1', 'runtime_ref': 'wrong:v1'},
                           {'image_id': 'sha256:' + 'b' * 64}):
                with self.assertRaises(ValueError):
                    inspect_archive(path, [dict(row, **change)], FINGERPRINTER)

    def test_path_traversal_duplicate_and_symlink_fail(self):
        for name in ('../escape', '/absolute', 'a\\b', 'manifest.json'):
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'source.tar'
                row = fixture(path, extra=(name, b'bad'))
                with self.assertRaises(ValueError):
                    inspect_archive(path, [row], FINGERPRINTER)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'source.tar'
            row = fixture(path)
            with tarfile.open(path, 'a') as archive:
                info = tarfile.TarInfo('unsafe-link')
                info.type = tarfile.SYMTYPE
                info.linkname = '/etc/passwd'
                archive.addfile(info)
            with self.assertRaises(ValueError):
                inspect_archive(path, [row], FINGERPRINTER)

    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileExistsError):
                require_new_directory(Path(tmp))

    def test_incomplete_bundle_cannot_be_verified(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises((ValueError, FileNotFoundError)):
                verify_bundle(Path(tmp), collect_catalog(ROOT))

    def test_group_round_trip_and_corrupt_archive_receipt_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'source.tar'
            row = fixture(source)
            row['purpose'] = 'frontend'
            catalog = collect_catalog(ROOT)
            catalog['images'] = [image for image in catalog['images'] if image['purpose'] != 'frontend'] + [row]
            output = root / 'frontend'
            with patch('image_bundle.shutil.disk_usage') as usage:
                usage.return_value.free = 100 * 1024 ** 3
                create_group('frontend', source, output, catalog, FINGERPRINTER)
            receipt = verify_group(output, 'frontend', catalog)
            self.assertFalse(receipt['installation_verified'])
            self.assertEqual(gzip.decompress((output / 'frontend.tar.gz').read_bytes()), source.read_bytes())
            self.assertEqual(receipt['source_archive_sha256'], hash_file(source))
            with (output / 'frontend.tar.gz').open('ab') as stream:
                stream.write(b'corrupt')
            with self.assertRaises(ValueError):
                verify_group(output, 'frontend', catalog)

    def test_low_disk_never_creates_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'source.tar'
            fixture(source)
            with patch('image_bundle.shutil.disk_usage') as usage:
                usage.return_value.free = 1
                with self.assertRaisesRegex(ValueError, 'space'):
                    create_group('cpu', source, root / 'out', collect_catalog(ROOT), FINGERPRINTER)
            self.assertFalse((root / 'out').exists())

    def test_incomplete_four_groups_cannot_be_sealed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'frontend').mkdir()
            with self.assertRaises(ValueError):
                finish_bundle(root, collect_catalog(ROOT))
            self.assertFalse((root / 'BUNDLE_COMPLETE.json').exists())

    def test_source_changed_during_inspection_never_creates_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'source.tar'
            fixture(source)
            with patch('image_bundle.shutil.disk_usage') as usage, \
                    patch('image_bundle.inspect_archive', return_value=[]), \
                    patch('image_bundle.fingerprint_file', side_effect=[(1, 1, 10, 1, 1), (1, 1, 11, 2, 2)]):
                usage.return_value.free = 100 * 1024 ** 3
                with self.assertRaisesRegex(ValueError, 'changed during verification'):
                    create_group('cpu', source, root / 'out', collect_catalog(ROOT), FINGERPRINTER)
            self.assertFalse((root / 'out').exists())

    def test_compression_failure_has_no_success_marker_and_cannot_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'source.tar'
            fixture(source)
            output = root / 'out'
            with patch('image_bundle.shutil.disk_usage') as usage, \
                    patch('image_bundle.inspect_archive', return_value=[]), \
                    patch('image_bundle.gzip.GzipFile', side_effect=OSError('simulated disk failure')):
                usage.return_value.free = 100 * 1024 ** 3
                with self.assertRaisesRegex(OSError, 'disk failure'):
                    create_group('cpu', source, output, collect_catalog(ROOT), FINGERPRINTER)
            self.assertFalse((output / 'GROUP_COMPLETE.json').exists())
            with self.assertRaises(FileExistsError):
                require_new_directory(output)

    def test_complete_bundle_transport_check_rejects_changed_metadata(self):
        # 仅测试分组封存/传输校验；真实镜像层检查由前面的归档用例覆盖。
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            catalog = collect_catalog(ROOT)
            for group, rows in project_groups(catalog).items():
                directory = root / group
                directory.mkdir()
                archive = directory / (group + '.tar.gz')
                archive.write_bytes(b'isolated transport fixture')
                write_json(directory / 'GROUP_COMPLETE.json', dict(schema_version=1, group=group,
                           archive=archive.name, archive_bytes=archive.stat().st_size,
                           archive_sha256=hash_file(archive), images=rows, installation_verified=False))
            finish_bundle(root, catalog)
            self.assertEqual(verify_bundle(root, catalog), PROJECT_IMAGE_COUNT)
            with self.assertRaises(ValueError):
                finish_bundle(root, catalog)
            (root / 'online-infrastructure.json').write_text('{}')
            with self.assertRaises(ValueError):
                verify_bundle(root, catalog)

    def test_rewritten_id_requires_exact_locked_runtime_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'source.tar'
            row = fixture(path)
            first = inspect_archive(path, [row], FINGERPRINTER)[0]
            row['image_id'] = 'sha256:' + '0' * 64
            row['runtime_fingerprint'] = first['runtime_fingerprint']
            self.assertEqual(inspect_archive(path, [row], FINGERPRINTER)[0], first)
            row['runtime_fingerprint'] = 'f' * 64
            with self.assertRaisesRegex(ValueError, 'fingerprint'):
                inspect_archive(path, [row], FINGERPRINTER)

    def test_exact_config_digest_is_stronger_than_supplemental_inspect_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'source.tar'
            row = fixture(path)
            # Docker inspect 的默认字段可能与 OCI 原始 config 不同；字节摘要仍一致。
            row['runtime_fingerprint'] = '0' * 64
            result = inspect_archive(path, [row], FINGERPRINTER)
            self.assertEqual(result[0]['actual_config_digest'], row['image_id'])

    def test_tool_remains_python38_compatible(self):
        ast.parse(Path(__file__).with_name('image_bundle.py').read_text(encoding='utf-8'),
                  feature_version=(3, 8))


if __name__ == '__main__':
    unittest.main()
