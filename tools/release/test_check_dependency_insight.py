import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_dependency_insight.py")
PRESENT = "com.itextpdf:kernel:7.2.5\n   variant \"releaseRuntimeElements\"\n\ncom.itextpdf:kernel:7.2.5\n\\--- com.itextpdf:itext7-core:7.2.5\n"
ABSENT = "> Task :app:dependencyInsight\nNo dependencies matching given input were found in configuration ':app:releaseRuntimeClasspath'\n"


class InsightTest(unittest.TestCase):
    def run_check(self, content, exit_code, expect, group="com.itextpdf"):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "r.txt")
            if content is not None:
                open(p, "w").write(content)
            r = subprocess.run([sys.executable, SCRIPT, "--report", p, "--exit-code", str(exit_code),
                                "--group", group, "--expect", expect], capture_output=True, text=True)
            return r.returncode, r.stdout

    def test_absent_is_not_a_failure_and_meets_absent_expectation(self):
        self.assertEqual(self.run_check(ABSENT, 0, "absent")[0], 0)

    def test_present_when_absent_expected_is_a_mismatch(self):
        self.assertEqual(self.run_check(PRESENT, 0, "absent")[0], 1)

    def test_present_meets_present_expectation(self):
        self.assertEqual(self.run_check(PRESENT, 0, "present")[0], 0)

    def test_gradle_failure_is_never_absent(self):
        code, out = self.run_check(ABSENT, 1, "absent")
        self.assertEqual(code, 2)
        self.assertIn("FAILED", out)

    def test_empty_report_with_exit_zero_fails_closed(self):
        self.assertEqual(self.run_check("", 0, "absent")[0], 2)

    def test_missing_report_fails_closed(self):
        self.assertEqual(self.run_check(None, 0, "absent")[0], 2)

    def test_unrecognisable_report_fails_closed(self):
        self.assertEqual(self.run_check("FAILURE: Build failed with an exception.\n", 0, "absent")[0], 2)

    def test_any_expectation_still_fails_on_gradle_failure(self):
        self.assertEqual(self.run_check(ABSENT, 1, "any")[0], 2)
        self.assertEqual(self.run_check(PRESENT, 0, "any")[0], 0)


if __name__ == "__main__":
    unittest.main()
