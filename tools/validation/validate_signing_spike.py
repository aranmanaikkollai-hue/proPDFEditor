#!/usr/bin/env python3
"""Independent validation of the PDFBox + Bouncy Castle signing spike output.

Input directory (produced by PdfBoxCmsSignerInstrumentedTest.exportArtefactsForIndependentValidation):
  unsigned.pdf, signed_one.pdf, signed_two.pdf, signed_tampered.pdf, signed_appended.pdf

Validators: qpdf (structure), Poppler pdfsig, pyHanko. Each property is reported separately:
  structure (qpdf --check), integrity (digest + signature), coverage (whole document or not),
  certificate trust (the spike certificates are self-signed, so "not trusted" is the EXPECTED result).

Status words: PASS / FAIL / NOT RUN. A tool that is missing or cannot run yields NOT RUN, never PASS.
Exit code 1 only if some check FAILED; NOT RUN does not hide behind success: it is listed and counted.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

rows = []  # (case, validator, property, expected, observed, status)


def add(case, validator, prop, expected, observed, status):
    rows.append((case, validator, prop, expected, observed, status))


def run(cmd):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except FileNotFoundError:
        return None, "tool not found"
    except Exception as e:  # timeout etc.
        return None, repr(e)


def version(cmd):
    code, out = run(cmd)
    return out.strip().splitlines()[0] if out.strip() else "unknown"


# ---------------------------------------------------------------- qpdf
def check_qpdf(case, path):
    if not shutil.which("qpdf"):
        add(case, "qpdf", "structure", "--check passes", "qpdf not installed", "NOT RUN")
        return
    code, out = run(["qpdf", "--check", path])
    ok = code in (0, 3)  # 3 = warnings only
    add(case, "qpdf", "structure", "--check passes", f"exit {code}", "PASS" if ok else "FAIL")


# ---------------------------------------------------------------- pdfsig
def parse_pdfsig(text):
    sigs = []
    for block in re.split(r"Signature #\d+:", text)[1:]:
        sigs.append({
            "validation": (re.search(r"Signature Validation: (.*)", block) or [None, ""])[1].strip(),
            "total": "Total document signed" in block and "Not total" not in block,
            "cert": (re.search(r"Certificate Validation: (.*)", block) or [None, ""])[1].strip(),
        })
    return sigs


def check_pdfsig(case, path, expect):
    """expect: list of dicts {valid: bool, total: bool} per signature, in order."""
    if not shutil.which("pdfsig"):
        for prop in ("integrity", "coverage", "trust"):
            add(case, "pdfsig", prop, "-", "pdfsig not installed", "NOT RUN")
        return
    _, no_cert = run(["pdfsig", "-nocert", path])
    _, with_cert = run(["pdfsig", path])
    got, got_cert = parse_pdfsig(no_cert), parse_pdfsig(with_cert)
    if len(got) != len(expect):
        add(case, "pdfsig", "signature count", str(len(expect)), str(len(got)), "FAIL")
        return
    for i, (g, e) in enumerate(zip(got, expect), 1):
        is_valid = g["validation"].startswith("Signature is Valid")
        add(case, "pdfsig", f"integrity #{i}", "valid" if e["valid"] else "invalid", g["validation"] or "?",
            "PASS" if is_valid == e["valid"] else "FAIL")
        add(case, "pdfsig", f"coverage #{i}", "whole document" if e["total"] else "partial",
            "whole document" if g["total"] else "partial", "PASS" if g["total"] == e["total"] else "FAIL")
        cert = got_cert[i - 1]["cert"] if i - 1 < len(got_cert) else "?"
        # Self-signed: trust must NOT be established. Reported on its own line, never merged into integrity.
        trusted = bool(re.search(r"is Trusted|Certificate is Trusted", cert)) and "isn't" not in cert and "Not" not in cert
        add(case, "pdfsig", f"cert trust #{i}", "not trusted (self-signed)", cert or "?",
            "FAIL" if trusted else ("PASS" if cert else "NOT RUN"))


# ---------------------------------------------------------------- pyHanko
def load_pyhanko():
    try:
        from pyhanko.pdf_utils.reader import PdfFileReader
        from pyhanko.sign.validation import validate_pdf_signature
        import pyhanko
        return PdfFileReader, validate_pdf_signature, getattr(pyhanko, "__version__", "unknown"), None
    except Exception as e:  # not installed / API mismatch
        return None, None, None, repr(e)


def check_pyhanko(case, path, expect, api):
    reader_cls, validate, _ver, err = api
    if reader_cls is None:
        for prop in ("integrity", "coverage", "trust"):
            add(case, "pyHanko", prop, "-", f"pyHanko unavailable: {err}", "NOT RUN")
        return
    got = []
    try:
        with open(path, "rb") as fh:
            reader = reader_cls(fh)
            for emb in reader.embedded_signatures:
                try:
                    st = validate(emb)  # no trust roots supplied: trust must come out False
                    cov = getattr(st, "coverage", None)
                    got.append({"intact": bool(st.intact), "valid": bool(st.valid), "trusted": bool(st.trusted),
                                "coverage": getattr(cov, "name", str(cov))})
                except Exception as e:
                    got.append({"intact": False, "valid": False, "trusted": False, "coverage": "n/a", "error": repr(e)[:120]})
    except Exception as e:
        add(case, "pyHanko", "open", "readable", repr(e)[:160], "FAIL")
        return
    if len(got) != len(expect):
        add(case, "pyHanko", "signature count", str(len(expect)), str(len(got)), "FAIL")
        return
    for i, (g, e) in enumerate(zip(got, expect), 1):
        ok_integrity = (g["intact"] and g["valid"]) == e["valid"]
        add(case, "pyHanko", f"integrity #{i}", "intact+valid" if e["valid"] else "not intact",
            f"intact={g['intact']} valid={g['valid']}" + (f" {g['error']}" if "error" in g else ""),
            "PASS" if ok_integrity else "FAIL")
        if e["valid"]:
            whole = g["coverage"] == "ENTIRE_FILE"
            add(case, "pyHanko", f"coverage #{i}", "ENTIRE_FILE" if e["total"] else "not ENTIRE_FILE", g["coverage"],
                "PASS" if whole == e["total"] else "FAIL")
        add(case, "pyHanko", f"cert trust #{i}", "not trusted (self-signed)", f"trusted={g['trusted']}",
            "FAIL" if g["trusted"] else "PASS")


# ---------------------------------------------------------------- main
CASES = [
    # name,                expectations per signature in order
    ("unsigned.pdf", []),
    ("signed_one.pdf", [{"valid": True, "total": True}]),
    ("signed_two.pdf", [{"valid": True, "total": False}, {"valid": True, "total": True}]),
    ("signed_tampered.pdf", [{"valid": False, "total": True}]),
    ("signed_appended.pdf", [{"valid": True, "total": False}]),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("directory")
    ap.add_argument("--report", default="")
    args = ap.parse_args()

    api = load_pyhanko()
    tools = {
        "qpdf": version(["qpdf", "--version"]) if shutil.which("qpdf") else "NOT INSTALLED",
        "pdfsig": version(["pdfsig", "-v"]) if shutil.which("pdfsig") else "NOT INSTALLED",
        "pyHanko": api[2] if api[0] else f"NOT AVAILABLE ({api[3]})",
    }

    for name, expect in CASES:
        path = os.path.join(args.directory, name)
        if not os.path.isfile(path):
            add(name, "-", "file", "present", "missing", "NOT RUN")
            continue
        check_qpdf(name, path)
        if expect:
            check_pdfsig(name, path, expect)
            check_pyhanko(name, path, expect, api)

    counts = {s: sum(1 for r in rows if r[5] == s) for s in ("PASS", "FAIL", "NOT RUN")}
    lines = ["# Signing spike: independent validation", "", "Tools: " + "; ".join(f"{k} {v}" for k, v in tools.items()), "",
             "| case | validator | property | expected | observed | status |", "|---|---|---|---|---|---|"]
    lines += [f"| {c} | {v} | {p} | {e} | {o} | **{s}** |" for c, v, p, e, o, s in rows]
    lines += ["", f"PASS {counts['PASS']}, FAIL {counts['FAIL']}, NOT RUN {counts['NOT RUN']}",
              "", "Integrity and certificate trust are separate rows. The spike certificates are self-signed, so an untrusted",
              "result is expected and is not a failure; a TRUSTED result would be (nothing supplied a trust root)."]
    text = "\n".join(lines)
    print(text)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
    return 1 if counts["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
