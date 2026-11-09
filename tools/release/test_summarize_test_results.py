import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "summarize_test_results.py")


class SummarizeTest(unittest.TestCase):
    def run_on(self, files, *extra):
        with tempfile.TemporaryDirectory() as d:
            for rel, content in files.items():
                p = os.path.join(d, rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                open(p, "w").write(content)
            r = subprocess.run([sys.executable, SCRIPT, d, *extra], capture_output=True, text=True)
            return r.returncode, r.stdout

    def suite(self, t, f=0, e=0, s=0):
        return '<testsuite name="x" tests="%d" failures="%d" errors="%d" skipped="%d"/>' % (t, f, e, s)

    def test_passing_unit_and_instrumented(self):
        code, out = self.run_on({
            "app/build/test-results/testDebugUnitTest/a.xml": self.suite(10),
            "core/build/outputs/androidTest-results/connected/b.xml": self.suite(5, s=1)})
        self.assertEqual(code, 0, out)
        self.assertIn("skipped=1", out)

    def test_failure_is_reported(self):
        self.assertEqual(self.run_on({"app/build/test-results/t/a.xml": self.suite(3, f=1)})[0], 1)

    def test_no_reports_is_never_a_pass(self):
        self.assertEqual(self.run_on({"README": "x"})[0], 2)

    def test_malformed_report_is_incomplete(self):
        self.assertEqual(self.run_on({"app/build/test-results/t/a.xml": "<testsuite"})[0], 2)

    def test_required_module_missing(self):
        code, out = self.run_on({"app/build/test-results/t/a.xml": self.suite(1)}, "--require-modules", "app,core")
        self.assertEqual(code, 2)
        self.assertIn("core", out)


if __name__ == "__main__":
    unittest.main()
