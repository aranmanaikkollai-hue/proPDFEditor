import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "lint_gate.py")


def issue(sev, iid="SomeId", msg="m"):
    return ('<issue id="%s" severity="%s" message="%s" category="Correctness" priority="5" '
            'summary="s" explanation="e"><location file="src/A.kt" line="3"/></issue>' % (iid, sev, msg))


def report(*issues):
    return '<?xml version="1.0" encoding="UTF-8"?>\n<issues format="6" by="lint 8.2.2">\n%s\n</issues>\n' % "\n".join(issues)


class LintGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def gate(self, content=None):
        path = os.path.join(self.tmp.name, "lint-results-release.xml")
        if content is not None:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(content)
        r = subprocess.run([sys.executable, SCRIPT, path], capture_output=True, text=True)
        return r.returncode, r.stdout

    def test_zero_issues_passes(self):
        code, out = self.gate(report())
        self.assertEqual(code, 0, out)
        self.assertIn("errors (Error+Fatal): 0", out)

    def test_one_error_fails(self):
        code, out = self.gate(report(issue("Error", "MissingClass")))
        self.assertEqual(code, 1)
        self.assertIn("MissingClass", out)

    def test_multiple_errors_fail_and_all_listed(self):
        code, out = self.gate(report(issue("Error", "A1"), issue("Fatal", "B2"), issue("Error", "C3"), issue("Warning", "W")))
        self.assertEqual(code, 1)
        for i in ("A1", "B2", "C3"):
            self.assertIn(i, out)
        self.assertIn("errors (Error+Fatal): 3", out)

    def test_warnings_only_passes_and_reports_warnings(self):
        code, out = self.gate(report(issue("Warning", "UnusedResources"), issue("Warning", "UnusedResources"), issue("Informational", "X")))
        self.assertEqual(code, 0)
        self.assertIn("UnusedResources x2", out)

    def test_missing_xml_fails_closed(self):
        code, out = self.gate(None)
        self.assertEqual(code, 2)
        self.assertIn("GATE_INCOMPLETE", out)

    def test_malformed_xml_fails_closed(self):
        code, _ = self.gate('<issues format="6"><issue id="A" severity="Warning"')
        self.assertEqual(code, 2)

    def test_empty_file_fails_closed(self):
        self.assertEqual(self.gate("")[0], 2)

    def test_wrong_root_element_fails_closed(self):
        self.assertEqual(self.gate("<html><body/></html>")[0], 2)

    def test_issue_without_known_severity_fails_closed(self):
        code, _ = self.gate('<issues format="6"><issue id="A"/></issues>')
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
