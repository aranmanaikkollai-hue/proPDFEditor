#!/usr/bin/env python3
"""Classify a saved `gradlew dependencyInsight` run as PRESENT / ABSENT / FAILED and check an expectation.

A dependency that is NOT in the configuration is a normal, successful Gradle outcome (exit 0, message
"No dependencies matching given input were found"). A genuinely failed Gradle command (non-zero exit, empty or
unrecognisable report) is a different outcome and is never reported as ABSENT.

Exit codes: 0 expectation met | 1 expectation not met | 2 command failed / report unusable
"""
import argparse
import os
import re
import sys

ABSENT_MARKER = "No dependencies matching given input were found"


def classify(report_path, exit_code, group):
    if exit_code != 0:
        return "FAILED", "gradle exited with code %d" % exit_code
    if not os.path.isfile(report_path):
        return "FAILED", "report file missing"
    text = open(report_path, encoding="utf-8", errors="replace").read()
    if not text.strip():
        return "FAILED", "report is empty"
    if ABSENT_MARKER in text:
        return "ABSENT", "gradle reported no matching dependencies"
    if re.search(r"(^|[\s|+\\-])%s:[A-Za-z0-9_.\-]+" % re.escape(group), text, re.M):
        return "PRESENT", "dependency coordinates found in report"
    return "FAILED", "report is neither a match listing nor the 'no dependencies' message; cannot classify"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--report", required=True)
    ap.add_argument("--exit-code", type=int, required=True, help="exit code of the gradle command")
    ap.add_argument("--group", required=True, help="dependency group, e.g. com.itextpdf")
    ap.add_argument("--expect", choices=["present", "absent", "any"], required=True)
    ap.add_argument("--label", default="")
    args = ap.parse_args(argv)
    state, why = classify(args.report, args.exit_code, args.group)
    label = args.label or args.group
    if state == "FAILED":
        print("%s: FAILED (%s)" % (label, why))
        return 2
    ok = args.expect == "any" or state.lower() == args.expect
    print("%s: %s (expected %s) -> %s" % (label, state, args.expect, "OK" if ok else "MISMATCH"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
