import ast
import subprocess
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch

from preflight import (assess, assess_configuration, nearest_existing_directory, parse_listening_ports,
                       read_os_release, run_readonly, version_pair)


class PreflightTest(unittest.TestCase):
    def baseline(self):
        return {'system': 'Linux', 'os': {'ID': 'ubuntu', 'VERSION_ID': '24.04'},
                'architecture': 'x86_64', 'kernel': '6.8.0-41-generic',
                'cgroup_v2': True, 'gpu_summary': ['test GPU, test driver, 32768'],
                'commands': {'docker': True}, 'existing_kubernetes': False}

    def test_target_versions_are_not_misrepresented_as_verified(self):
        for version in ('22.04', '24.04', '26.04', '25.10', '28.04'):
            facts = self.baseline()
            facts['os']['VERSION_ID'] = version
            result = assess(facts)
            self.assertEqual(result['status'], 'basic_compatible')
            self.assertFalse(result['installation_verified'])
            self.assertTrue(result['warnings'])

    def test_supported_os_still_requires_compatible_kernel_and_cgroup(self):
        facts = self.baseline()
        facts['os']['VERSION_ID'] = '22.04'
        facts['kernel'] = '5.4.0-generic'
        facts['cgroup_v2'] = False
        self.assertEqual(len(assess(facts)['failures']), 2)

    def test_refuses_wrong_os_architecture_or_occupied_kubernetes(self):
        for key, value in (('architecture', 'aarch64'), ('system', 'Windows'),
                           ('existing_kubernetes', True), ('gpu_summary', [])):
            facts = self.baseline()
            facts[key] = value
            self.assertEqual(assess(facts)['status'], 'not_ready')

    def test_rejects_old_or_unparseable_os(self):
        for version in ('18.04', '20.04', '22.03', '', 'unknown'):
            facts = self.baseline()
            facts['os']['VERSION_ID'] = version
            self.assertEqual(assess(facts)['status'], 'not_ready')

    def test_missing_tools_are_installation_todos_not_fake_success(self):
        facts = self.baseline()
        facts['commands']['kubeadm'] = False
        result = assess(facts)
        self.assertTrue(any('kubeadm' in item for item in result['warnings']))
        self.assertFalse(result['installation_verified'])

    def test_parser_never_executes_shell_or_exports_unknown_fields(self):
        parsed = read_os_release('ID=ubuntu\nVERSION_ID="20.04"\nSECRET=value\nNAME="$(false)"')
        self.assertEqual(parsed, {'ID': 'ubuntu', 'VERSION_ID': '20.04'})
        self.assertEqual(version_pair('5.15.0-67-generic'), (5, 15))
        self.assertIsNone(version_pair('broken'))

    def test_assessment_does_not_mutate_inventory(self):
        facts = self.baseline()
        before = deepcopy(facts)
        assess(facts)
        self.assertEqual(facts, before)

    def test_missing_data_path_only_inspects_parent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            planned = root / 'not-created' / 'data'
            self.assertEqual(nearest_existing_directory(planned), root)
            self.assertFalse(planned.parent.exists())

    def test_rejects_relative_traversal_file_and_symbolic_link(self):
        for candidate in ('relative/data', str(Path.cwd() / '..' / 'data')):
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                nearest_existing_directory(candidate)
        with self.assertRaises(ValueError):
            nearest_existing_directory(Path(__file__).resolve())
        # Windows 非管理员不一定有创建符号链接权限；只模拟这一元数据检查。
        with patch.object(Path, 'is_symlink', return_value=True):
            with self.assertRaises(ValueError):
                nearest_existing_directory(Path.cwd())

    def test_gpu_probe_handles_missing_command_failure_and_timeout(self):
        for error in (FileNotFoundError(), subprocess.TimeoutExpired('nvidia-smi', 10)):
            with patch('preflight.subprocess.run', side_effect=error):
                self.assertIsNone(run_readonly(['nvidia-smi']))
        with patch('preflight.subprocess.run', return_value=subprocess.CompletedProcess(
                ['nvidia-smi'], 1, stdout='partial output')):
            self.assertIsNone(run_readonly(['nvidia-smi']))

    def test_gpu_probe_is_bounded_and_does_not_invoke_shell(self):
        with patch('preflight.subprocess.run', return_value=subprocess.CompletedProcess(
                ['nvidia-smi'], 0, stdout='GPU\n')) as process:
            self.assertEqual(run_readonly(['nvidia-smi']), 'GPU')
            self.assertEqual(process.call_args.kwargs['timeout'], 10)
            self.assertNotIn('shell', process.call_args.kwargs)

    def test_unknown_os_fields_are_ignored_even_if_malformed(self):
        self.assertEqual(read_os_release('ID=ubuntu\nUNKNOWN="unfinished'), {'ID': 'ubuntu'})

    def test_preflight_source_uses_python_38_syntax(self):
        source = Path(__file__).with_name('preflight.py').read_text(encoding='utf-8')
        ast.parse(source, feature_version=(3, 8))

    def test_port_parser_distinguishes_empty_from_unavailable_and_handles_ipv6(self):
        self.assertEqual(parse_listening_ports(''), [])
        self.assertIsNone(parse_listening_ports(None))
        self.assertIsNone(parse_listening_ports('unexpected'))
        self.assertEqual(parse_listening_ports('tcp LISTEN 0 128 [::]:22 [::]:*\n'
                                               'udp UNCONN 0 0 0.0.0.0:53 0.0.0.0:*'), [22, 53])

    def test_cpu_worker_does_not_require_a_gpu_or_nvidia_runtime(self):
        facts = self.baseline()
        facts['gpu_summary'] = []
        facts['commands']['nvidia-container-runtime'] = False
        result = assess(facts, require_gpu=False)
        self.assertEqual(result['status'], 'basic_compatible')
        self.assertFalse(any('nvidia-container-runtime' in warning for warning in result['warnings']))
        self.assertEqual(assess(facts)['status'], 'not_ready')

    def test_plan_checks_real_capacity_ip_ports_and_actual_data_path(self):
        import json
        config = json.loads(Path(__file__).with_name('node.example.json').read_text())
        facts = {'host_ipv4_addresses': [config['node_ip']], 'cpu_logical_cores': 16,
                 'memory_bytes': {'MemTotal': 32 * 1024 ** 3},
                 'listening_ports': [22], 'data_path': config['data_root']}
        self.assertEqual(assess_configuration(facts, config), [])
        for key, value in (('host_ipv4_addresses', []), ('cpu_logical_cores', 6),
                           ('memory_bytes', {'MemTotal': 8 * 1024 ** 3}),
                           ('listening_ports', [18081]), ('listening_ports', None),
                           ('data_path', '/other/project')):
            with self.subTest(key=key, value=value):
                changed = deepcopy(facts)
                changed[key] = value
                self.assertTrue(assess_configuration(changed, config))


if __name__ == '__main__':
    unittest.main()
