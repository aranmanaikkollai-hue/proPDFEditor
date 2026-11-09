#!/usr/bin/env python3
"""Summarise JUnit XML results (Gradle unit and connected-test output) into counts per module.

Exit codes: 0 reports found and no failures/errors | 1 failures or errors present | 2 no usable reports (never a pass)
Skipped tests are reported separately. Usage: summarize_test_results.py ROOT [--require-modules app,core] [--out FILE]
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def module_of(path, root):
    rel = os.path.relpath(path, root).split(os.sep)
    return rel[0] if rel and rel[0] not in ("build",) else "?"


def collect(root):
    files = glob.glob(os.path.join(root, "**", "build", "test-results", "**", "*.xml"), recursive=True)
    files += glob.glob(os.path.join(root, "**", "build", "outputs", "androidTest-results", "**", "*.xml"), recursive=True)
    stats, bad = {}, []
    for f in sorted(set(files)):
        try:
            tree = ET.parse(f).getroot()
        except ET.ParseError:
            bad.append(f)
            continue
        suites = [tree] if tree.tag == "testsuite" else list(tree.iter("testsuite"))
        for s in suites:
            kind = "instrumented" if "androidTest-results" in f else "unit"
            key = (module_of(f, root), kind)
            d = stats.setdefault(key, dict(tests=0, failures=0, errors=0, skipped=0, suites=0))
            d["tests"] += int(s.get("tests", 0))
            d["failures"] += int(s.get("failures", 0))
            d["errors"] += int(s.get("errors", 0))
            d["skipped"] += int(s.get("skipped", 0))
            d["suites"] += 1
    return stats, bad


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root")
    ap.add_argument("--require-modules", default="", help="comma list; each must have at least one result")
    ap.add_argument("--out")
    args = ap.parse_args(argv)
    stats, bad = collect(args.root)
    lines, code = [], 0
    if not stats:
        lines.append("NO USABLE TEST REPORTS FOUND: this is not a pass")
        code = 2
    tot = dict(tests=0, failures=0, errors=0, skipped=0)
    for (mod, kind), d in sorted(stats.items()):
        passed = d["tests"] - d["failures"] - d["errors"] - d["skipped"]
        lines.append("%-10s %-12s tests=%d passed=%d failed=%d errors=%d skipped=%d (suites=%d)" % (
            mod, kind, d["tests"], passed, d["failures"], d["errors"], d["skipped"], d["suites"]))
        for k in tot:
            tot[k] += d[k]
    if stats:
        lines.append("TOTAL tests=%d failed=%d errors=%d skipped=%d" % (tot["tests"], tot["failures"], tot["errors"], tot["skipped"]))
    for b in bad:
        lines.append("UNPARSEABLE REPORT: %s" % b)
        code = 2
    for m in [x for x in args.require_modules.split(",") if x]:
        if not any(k[0] == m for k in stats):
            lines.append("REQUIRED MODULE HAS NO RESULTS: %s" % m)
            code = 2
    if code == 0 and (tot["failures"] or tot["errors"]):
        code = 1
    lines.append("RESULT: %s" % {0: "ALL EXECUTED TESTS PASSED", 1: "FAILURES PRESENT", 2: "INCOMPLETE"}[code])
    text = "\n".join(lines) + "\n"
    sys.stdout.write(text)
    if args.out:
        open(args.out, "w", encoding="utf-8").write(text)
    return code


if __name__ == "__main__":
    sys.exit(main())
