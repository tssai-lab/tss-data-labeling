"""客户配置生成的边界测试：只在测试临时目录生成，不执行安装命令。"""
import ast
import json
import tempfile
import unittest
import yaml
from copy import deepcopy
from pathlib import Path

from deployment_plan import load_config, render_plan, validate_config, verify_plan, write_plan
from image_catalog import collect_catalog


ROOT = Path(__file__).resolve().parents[2]
EXAMPLE = Path(__file__).with_name('node.example.json')


class PlanTest(unittest.TestCase):
    def config(self):
        return json.loads(EXAMPLE.read_text(encoding='utf-8'))

    def render(self, config=None):
        return render_plan(validate_config(config or self.config()), ROOT)

    def test_unknown_keys_duplicate_keys_and_missing_fields_fail(self):
        for mutate in (lambda c: c.update(typo=True), lambda c: c.pop('role'),
                       lambda c: c['reserved'].update(typo=1),
                       lambda c: c['ports'].update(typo=1)):
            config = self.config()
            mutate(config)
            with self.assertRaises(ValueError):
                validate_config(config)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'duplicate.json'
            path.write_text('{"role":"worker","role":"control-compute"}')
            with self.assertRaises(ValueError):
                load_config(path)

    def test_rejects_unsafe_names_paths_and_values(self):
        for field, values in {
            'cluster_name': ['tss-aiplatform-internal', 'Bad Name', 'a;reboot'],
            'node_name': ['a\nb', '', 'a' * 64],
            'role': ['control', 'worker;true'],
            'data_root': ['/', '/srv', '/etc/test', '/var/lib/docker/tss', '/srv/x/../y', '/srv/a b'],
            'source_root': ['/srv/x\nother', 'relative', '/srv/tss-AIplatform/platform'],
            'etcd_data_dir': ['/srv/tss-AIplatform', '/srv/tss-AIplatform/platform/data/postgres'],
            'gpu_enabled': ['true', 1, None],
            'containerd_config_version': [1, 4, True],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    config = self.config()
                    config[field] = value
                    validate_config(config)

    def test_rejects_invalid_overlapping_networks_and_ports(self):
        for change in ({'node_ip': '127.0.0.1'}, {'node_ip': '::1'},
                       {'control_plane_endpoint': '192.0.2.11:6443'},
                       {'pod_cidr': '10.97.0.0/16'}, {'pod_cidr': '192.0.2.0/24'},
                       {'pod_cidr': '10.245.0.1/16'}, {'service_cidr': '10.97.0.0/32'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                config = self.config()
                config.update(change)
                validate_config(config)
        for value in (True, 0, 65536, 6443, 9400, 18080):
            with self.subTest(port=value), self.assertRaises(ValueError):
                config = self.config()
                config['ports']['frontend'] = value
                validate_config(config)

    def test_control_reserve_covers_compose_but_does_not_restrict_host_services(self):
        files = self.render()
        kubelet = json.loads(files['kubelet-config.json'])
        self.assertEqual(kubelet['systemReserved'], {'cpu': '5000m', 'memory': '8192Mi'})
        self.assertEqual(kubelet['enforceNodeAllocatable'], ['pods'])
        self.assertNotIn('systemReservedCgroup', kubelet)
        self.assertEqual(kubelet['memorySwap']['swapBehavior'], 'NoSwap')
        for key, value in (('system_memory_mib', 5376), ('system_cpu_millis', 3750),
                           ('kube_cpu_millis', 0), ('kube_memory_mib', True)):
            with self.assertRaises(ValueError):
                config = self.config()
                config['reserved'][key] = value
                validate_config(config)

    def test_single_node_stages_with_standard_taint_so_system_dns_can_start(self):
        files = self.render()
        init, cluster, kubelet = [json.loads(part) for part in files['kubeadm-init.yaml'].split('\n---\n')]
        self.assertEqual(init['nodeRegistration']['imagePullPolicy'], 'Never')
        self.assertEqual(init['nodeRegistration']['taints'][0]['key'], 'node-role.kubernetes.io/control-plane')
        self.assertEqual(cluster['etcd']['local']['dataDir'], self.config()['etcd_data_dir'])
        self.assertEqual(kubelet, json.loads(files['kubelet-config.json']))
        self.assertNotIn('ignorePreflightErrors', init['nodeRegistration'])
        self.assertNotIn('--root-dir', files['kubeadm-init.yaml'])

    def test_storage_keeps_standard_kubelet_plugin_path_and_uses_project_runtime(self):
        files = self.render()
        self.assertIn('Where=/var/lib/kubelet', files['var-lib-kubelet.mount'])
        self.assertIn('What=/srv/tss-AIplatform/kubelet', files['var-lib-kubelet.mount'])
        self.assertIn('Requires=var-lib-kubelet.mount', files['kubelet-dependencies.conf'])
        self.assertIn('tss-customer-containerd.service', files['kubelet-dependencies.conf'])
        self.assertIn('root = "/srv/tss-AIplatform/containerd"', files['containerd.toml'])
        self.assertIn('/run/tss-customer/containerd/containerd.sock', files['containerd.toml'])
        self.assertNotIn('/run/containerd/containerd.sock', files['containerd.toml'])
        self.assertIn('imports = []', files['containerd.toml'])
        self.assertNotIn('/etc/containerd/', files['containerd.toml'].split('\n', 1)[1])
        self.assertIn('config_path = "/etc/tss-customer/certs.d"', files['containerd.toml'])

    def test_containerd_two_versions_and_cpu_only(self):
        for version in (2, 3):
            config = self.config()
            config['containerd_config_version'] = version
            config['gpu_enabled'] = False
            files = self.render(config)
            self.assertNotIn('nvidia', files['containerd.toml'])
            self.assertNotIn('nvidia-device-plugin.yaml', files)
            self.assertIn('SystemdCgroup = true', files['containerd.toml'])

    def test_compose_reuses_upstream_services_and_never_pulls(self):
        files = self.render()
        self.assertEqual(files['compose.yml'].count('    pull_policy: never'), 5)
        self.assertIn('name: tss-customer', files['compose.yml'])
        self.assertIn('TRAINING_K8S_CLUSTER_NAME: tss-customer', files['compose.yml'])
        self.assertIn('TSS_BACKEND_IMAGE=tss-aiplatform-internal/backend:', files['platform.env'])
        self.assertIn('TSS_CORS_ALLOWED_ORIGIN_PATTERNS=http://192.0.2.10:18081', files['platform.env'])
        self.assertNotIn('PASSWORD=', files['platform.env'])
        self.assertNotIn('TOKEN=', files['platform.env'])
        self.assertNotIn('seu5090', '\n'.join(files.values()))

    def test_observability_and_access_are_included(self):
        files = self.render()
        for key in ('training-namespace.yaml', 'training-resource-policy.yaml',
                    'training-service-account.yaml', 'backend-access.yaml',
                    'host-services.yaml', 'frontend.yaml', 'metrics-server.yaml',
                    'nvidia-device-plugin.yaml', 'dcgm-exporter.yaml', 'runtime-class.json'):
            self.assertIn(key, files)
        self.assertIn('tss.ai/staging', files['nvidia-device-plugin.yaml'])
        self.assertIn('imagePullPolicy: Never', files['metrics-server.yaml'])
        self.assertNotIn('REPLACE_', files['frontend.yaml'])
        self.assertNotIn('__HOST_GATEWAY__', files['host-services.yaml'])

    def test_generated_manifests_parse_and_tolerations_are_flat(self):
        files = self.render()
        for filename, text in files.items():
            if filename.endswith(('.yaml', '.yml')):
                with self.subTest(filename=filename):
                    docs = list(yaml.safe_load_all(text))
                    self.assertTrue(all(isinstance(doc, dict) for doc in docs))
                    for doc in docs:
                        if doc.get('kind') in ('Deployment', 'DaemonSet'):
                            tolerations = doc['spec']['template']['spec'].get('tolerations', [])
                            self.assertTrue(all(set(t) <= {'key', 'value', 'operator', 'effect', 'tolerationSeconds'} for t in tolerations))

    def test_worker_cannot_emit_control_init_compose_or_credentials(self):
        config = self.config()
        config.update(role='worker', node_name='ai-worker-02', node_ip='192.0.2.11')
        files = self.render(config)
        self.assertNotIn('kubeadm-init.yaml', files)
        self.assertNotIn('compose.yml', files)
        self.assertNotIn('platform.env', files)
        self.assertNotIn('backend-access.yaml', files)
        self.assertIn('kubelet-config.json', files)
        self.assertIn('join-instructions.md', files)
        join = json.loads(files['kubeadm-join.yaml.template'])
        self.assertEqual(join['nodeRegistration']['imagePullPolicy'], 'Never')
        self.assertEqual(join['patches']['directory'], '/etc/tss-customer/join-patches')
        self.assertIn('REPLACE_CA_CERT_HASH', files['kubeadm-join.yaml.template'])
        self.assertNotIn('unsafeSkipCAVerification', files['kubeadm-join.yaml.template'])
        self.assertEqual(json.loads(files['kubeletconfiguration0+merge.yaml'])['systemReserved'],
                         json.loads(files['kubelet-config.json'])['systemReserved'])

    def test_write_refuses_existing_output_and_is_reproducible(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'rendered'
            files = self.render()
            write_plan(output, files)
            before = (output / 'SHA256SUMS').read_bytes()
            with self.assertRaises(FileExistsError):
                write_plan(output, files)
            self.assertEqual(before, (output / 'SHA256SUMS').read_bytes())
            self.assertEqual(files, self.render())
            self.assertEqual(verify_plan(output), len(files) + 1)

    def test_partial_changed_missing_and_extra_outputs_fail_verification(self):
        for mode in ('partial', 'changed', 'missing', 'extra'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp) / 'rendered'
                write_plan(output, self.render())
                if mode == 'partial':
                    (output / 'PLAN_COMPLETE.json').unlink()
                elif mode == 'changed':
                    (output / 'platform.env').write_text('changed')
                elif mode == 'missing':
                    (output / 'frontend.yaml').unlink()
                else:
                    (output / 'unexpected.txt').write_text('extra')
                with self.assertRaises(ValueError):
                    verify_plan(output)

    def test_validation_does_not_mutate_config(self):
        config = self.config()
        before = deepcopy(config)
        validate_config(config)
        self.assertEqual(before, config)

    def test_catalog_includes_each_required_role_and_no_mutable_sources(self):
        catalog = collect_catalog(ROOT)
        # artifacts.lock 13 + platform 5 + CPU 3 + GPU 3 + frontend 1。
        self.assertEqual(len(catalog['images']), 25)
        self.assertEqual(len({x['digest'] for x in catalog['images']}), 25)
        self.assertEqual(len([x for x in catalog['images'] if x['store'] == 'docker']), 5)
        self.assertTrue(any('metrics-server' in x['source'] for x in catalog['images']))
        for row in catalog['images']:
            self.assertRegex(row['digest'], r'^sha256:[0-9a-f]{64}$')
            self.assertTrue(row['runtime_ref'])
        self.assertFalse(catalog['archives_verified'])

    def test_sources_remain_python38_compatible(self):
        for filename in ('deployment_plan.py', 'image_catalog.py'):
            ast.parse(Path(__file__).with_name(filename).read_text(encoding='utf-8'),
                      feature_version=(3, 8))


if __name__ == '__main__':
    unittest.main()
