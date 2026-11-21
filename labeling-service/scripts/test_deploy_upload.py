"""只测试上传重试与超时参数，不连接服务器或调用部署脚本。"""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


class DeployUploadTests(unittest.TestCase):
    def run_upload(self, fail_always: bool) -> tuple[subprocess.CompletedProcess, list[str]]:
        workflow = Path(__file__).resolve().parents[2] / '.github/workflows/labeling-backend-deploy.yml'
        source = workflow.read_text()
        start = source.index('          upload_file() {')
        end = source.index('\n          upload_file "$artifact"', start)
        function = textwrap.dedent(source[start:end])
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            log = root / 'calls'
            # 替代 timeout/scp，验证调用参数和退出码，避免真实传输及等待。
            commands = {
                'timeout': '#!/bin/sh\nprintf "timeout %s %s\\n" "$1" "$2" >> "$UPLOAD_TEST_LOG"\nshift 2\nexec "$@"\n',
                'scp': '#!/bin/sh\nprintf "scp %s %s %s\\n" "$1" "$2" "$3" >> "$UPLOAD_TEST_LOG"\n'
                       'if [ "$UPLOAD_TEST_FAIL_ALWAYS" = 1 ]; then exit 124; fi\n'
                       'if [ ! -f "$UPLOAD_TEST_MARKER" ]; then touch "$UPLOAD_TEST_MARKER"; exit 255; fi\n',
            }
            for name, body in commands.items():
                executable = root / name
                executable.write_text(body)
                executable.chmod(0o700)
            result = subprocess.run(
                ['bash', '-c', 'set -Eeuo pipefail\n' + function + '\nupload_file "$1" "$2" 300s',
                 'upload-test', 'release bundle.tar.gz', '/var/tmp/release.tar.gz'],
                env={**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                     'UPLOAD_TEST_LOG': str(log), 'UPLOAD_TEST_MARKER': str(root / 'retried'),
                     'UPLOAD_TEST_FAIL_ALWAYS': '1' if fail_always else '0'},
                capture_output=True, text=True, timeout=10,
            )
            return result, log.read_text().splitlines()

    def test_transient_transfer_failure_retries_and_then_succeeds(self) -> None:
        result, calls = self.run_upload(False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, calls.count('timeout --kill-after=10s 300s'))
        self.assertEqual(2, calls.count('scp -v release bundle.tar.gz labeling-target:/var/tmp/release.tar.gz'))
        self.assertIn('上传完成', result.stdout)

    def test_repeated_timeout_stops_after_two_attempts(self) -> None:
        result, calls = self.run_upload(True)
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertEqual(4, len(calls))
        self.assertNotIn('上传完成', result.stdout)


if __name__ == '__main__':
    unittest.main()
