#!/usr/bin/env python3
"""Fail-closed Android lint gate (release).

Reads the lint XML report (AGP default: app/build/reports/lint-results-release.xml) and decides.

Exit codes:
  0  report present, well-formed, and contains NO issue with severity Error or Fatal
  1  report contains at least one Error/Fatal issue
  2  the gate cannot prove lint completed: report missing/empty/unreadable/malformed/not a lint report

A missing report is NEVER treated as zero errors. Warnings are reported separately and do not fail the gate.
Standard library only.
"""
import argparse
import collections
import json
import os
import sys
import xml.etree.ElementTree as ET

FAIL_SEVERITIES = ("Fatal", "Error")
KNOWN_SEVERITIES = ("Fatal", "Error", "Warning", "Informational", "Ignore")


class GateError(Exception):
    """The gate cannot prove lint completed successfully."""


def parse_report(path):
    if not os.path.isfile(path):
        raise GateError("lint XML report not found: %s" % path)
    if os.path.getsize(path) == 0:
        raise GateError("lint XML report is empty: %s" % path)
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        raise GateError("lint XML report is malformed: %s" % e)
    except OSError as e:
        raise GateError("lint XML report unreadable (%s)" % type(e).__name__)
    if root.tag != "issues":
        raise GateError("not a lint report: root element is <%s>, expected <issues>" % root.tag)
    issues = []
    for el in root.findall("issue"):
        if el.get("severity") not in KNOWN_SEVERITIES:
            raise GateError("issue %r has missing/unknown severity %r; cannot classify" % (el.get("id"), el.get("severity")))
        loc = el.find("location")
        issues.append({
            "id": el.get("id", "?"),
            "severity": el.get("severity", "?"),
            "message": (el.get("message") or "").strip(),
            "file": loc.get("file") if loc is not None else None,
            "line": loc.get("line") if loc is not None else None,
        })
    return issues


def summarize(issues):
    by_sev = collections.Counter(i["severity"] for i in issues)
    errors = [i for i in issues if i["severity"] in FAIL_SEVERITIES]
    warnings = [i for i in issues if i["severity"] == "Warning"]
    return by_sev, errors, warnings


def render(path, issues, errors, warnings, by_sev, status, code):
    out = ["Lint report: %s" % path,
           "issues total: %d | errors (Error+Fatal): %d | warnings: %d" % (len(issues), len(errors), len(warnings)),
           "by severity: %s" % (dict(by_sev) or "{}")]
    if errors:
        out.append("ERRORS:")
        for i in errors:
            out.append("  [%s] %s  %s:%s  %s" % (i["severity"], i["id"], i["file"], i["line"], i["message"][:200]))
    if warnings:
        ids = collections.Counter(i["id"] for i in warnings)
        out.append("WARNINGS (reported separately, not gating): " + ", ".join("%s x%d" % kv for kv in sorted(ids.items())))
    out.append("RESULT: %s (exit %d)" % (status, code))
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("xml", help="lint XML report")
    ap.add_argument("--output", help="write text summary here")
    ap.add_argument("--json", help="write JSON summary here")
    args = ap.parse_args(argv)
    try:
        issues = parse_report(args.xml)
    except GateError as e:
        text = "LINT GATE INCOMPLETE: %s\nRESULT: GATE_INCOMPLETE (exit 2)\n" % e
        sys.stdout.write(text)
        if args.output:
            open(args.output, "w", encoding="utf-8").write(text)
        if args.json:
            json.dump({"status": "GATE_INCOMPLETE", "exit_code": 2, "reason": str(e)}, open(args.json, "w"), indent=2)
        return 2
    by_sev, errors, warnings = summarize(issues)
    status, code = ("LINT_ERRORS", 1) if errors else ("PASS", 0)
    text = render(args.xml, issues, errors, warnings, by_sev, status, code)
    sys.stdout.write(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"status": status, "exit_code": code, "errors": errors,
                       "warning_count": len(warnings), "by_severity": dict(by_sev)}, fh, indent=2)
    return code


if __name__ == "__main__":
    sys.exit(main())
