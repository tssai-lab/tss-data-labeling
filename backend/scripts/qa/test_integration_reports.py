"""只验证报告保护门，不能代替 PostgreSQL/MinIO/Redis 集成结果。"""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("reports", Path(__file__).with_name("verify-integration-reports.py"))
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)


class ReportGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "TEST-example.IntegrationTest.xml"
        self.suites = ["example.IntegrationTest"]

    def write(self, tests="1", failures="0", errors="0", skipped="0", body="<testcase name='works'/>", name="example.IntegrationTest"):
        self.path.write_text(f'<testsuite name="{name}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">{body}</testsuite>', encoding="utf-8")

    def check(self):
        return reports.verify(self.temp.name, self.suites, 1)

    def test_real_case_is_required(self):
        self.write()
        self.assertEqual(self.check()["passed"], 1)
        self.write(tests="0", body="")
        with self.assertRaises(ValueError):
            self.check()

    def test_missing_suite_fails(self):
        with self.assertRaises(ValueError):
            self.check()

    def test_stale_report_fails(self):
        self.write()
        os.utime(self.path, (0, 0))
        with self.assertRaises(ValueError):
            self.check()

    def test_failure_error_and_skip_each_fail(self):
        for field in ("failures", "errors", "skipped"):
            with self.subTest(field=field):
                self.write(**{field: "1"})
                with self.assertRaises(ValueError):
                    self.check()

    def test_case_contents_cannot_hide_behind_green_summary(self):
        for kind in ("failure", "error", "skipped"):
            with self.subTest(kind=kind):
                self.write(body=f"<testcase name='works'><{kind}/></testcase>")
                with self.assertRaises(ValueError):
                    self.check()

    def test_count_mismatch_fails(self):
        self.write(tests="2")
        with self.assertRaises(ValueError):
            self.check()

    def test_wrong_identity_fails(self):
        self.write(name="example.OtherTest")
        with self.assertRaises(ValueError):
            self.check()

    def test_invalid_suite_list_fails(self):
        self.write()
        for suites in ([], self.suites * 2):
            with self.assertRaises(ValueError):
                reports.verify(self.temp.name, suites, 1)


if __name__ == "__main__":
    unittest.main()
