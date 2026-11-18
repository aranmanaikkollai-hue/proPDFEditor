#!/usr/bin/env python3
"""Read-only report on how editable the text in PDF files is (Phase 3 feasibility study).

Usage:  python3 analyze_text_editability.py file1.pdf [file2.pdf ...] [--json out.json] [--summary]
Needs:  pip install pikepdf

It never writes to the input. It reports, per file and per font, the facts that decide whether text could be
edited in place: font type, embedding, subsetting, ToUnicode map, which characters an embedded subset can still
draw, how text is split into operators, spacing operators, text render modes, text inside form XObjects, and
signature / encryption flags.
"""
import json
import re
import sys
from collections import Counter

import pikepdf
from pikepdf import Name, parse_content_stream

LETTERS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")


def tounicode_chars(font):
    """Set of Unicode characters a ToUnicode CMap can produce (simple bfchar/bfrange forms only)."""
    try:
        data = font.ToUnicode.read_bytes().decode("latin-1")
    except Exception:
        return None
    chars = set()
    for block in re.findall(r"beginbfchar(.*?)endbfchar", data, re.S):
        for _src, dst in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            chars.update(_decode(dst))
    for block in re.findall(r"beginbfrange(.*?)endbfrange", data, re.S):
        for lo, hi, dst in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            first = _decode(dst)
            if len(first) == 1:
                span = min(int(hi, 16) - int(lo, 16), 0xFFFF)
                chars.update(chr(ord(first) + i) for i in range(span + 1))
            else:
                chars.update(first)
    return chars


def _decode(hexstr):
    try:
        return bytes.fromhex(hexstr).decode("utf-16-be")
    except Exception:
        return ""


def describe_font(font):
    subtype = str(font.get("/Subtype", "?")).lstrip("/")
    base = str(font.get("/BaseFont", "?")).lstrip("/")
    desc = font
    if subtype == "Type0":
        try:
            desc = font.DescendantFonts[0]
        except Exception:
            pass
    fd = desc.get("/FontDescriptor")
    embedded = bool(fd is not None and any(k in fd for k in ("/FontFile", "/FontFile2", "/FontFile3")))
    subset = bool(re.match(r"^[A-Z]{6}\+", base))
    uni = tounicode_chars(font) if "/ToUnicode" in font else None
    coverage = None
    if uni is not None:
        coverage = "%d/%d of A-Za-z0-9" % (len(LETTERS & uni), len(LETTERS))
    encoding = font.get("/Encoding")
    if isinstance(encoding, pikepdf.Name):
        encoding = str(encoding)
    elif encoding is not None:
        encoding = "custom/CMap dictionary"
    return {
        "name": base, "type": subtype, "embedded": embedded, "subset": subset,
        "to_unicode": "/ToUnicode" in font, "encoding": encoding,
        "alphanumeric_coverage_in_subset": coverage if (embedded and subset) else None,
    }


def page_report(page):
    ops = Counter()
    tr_modes = set()
    forms_with_text = 0
    try:
        stream = parse_content_stream(page)
    except Exception as exc:  # unreadable content stream
        return {"error": str(exc)}
    for operands, op in stream:
        name = str(op)
        ops[name] += 1
        if name == "Tr" and operands:
            tr_modes.add(int(operands[0]))
        if name == "Do" and operands:
            try:
                xo = page.Resources.XObject[operands[0]]
                if xo.get("/Subtype") == "/Form" and b"BT" in xo.read_bytes():
                    forms_with_text += 1
            except Exception:
                pass
    fonts = {}
    try:
        for key, font in page.Resources.Font.items():
            fonts[str(key)] = describe_font(font)
    except Exception:
        pass
    shows = ops["Tj"] + ops["TJ"] + ops["'"] + ops['"']
    return {
        "rotate": int(page.get("/Rotate", 0)),
        "has_cropbox": "/CropBox" in page,
        "text_blocks_BT": ops["BT"],
        "text_show_operators": shows,
        "kerned_TJ_operators": ops["TJ"],
        "char_or_word_spacing_ops": ops["Tc"] + ops["Tw"],
        "horizontal_scaling_ops": ops["Tz"],
        "text_render_modes_used": sorted(tr_modes),
        "clip_operators": ops["W"] + ops["W*"],
        "form_xobjects_with_text": forms_with_text,
        "fonts": fonts,
    }


def file_report(path):
    pdf = pikepdf.open(path)
    root = pdf.Root
    acro = root.get("/AcroForm")
    signed = False
    if acro is not None and "/Fields" in acro:
        for f in acro.Fields:
            if f.get("/FT") == "/Sig":
                signed = True
    return {
        "file": path, "pages": len(pdf.pages), "encrypted": pdf.is_encrypted, "signature_fields": signed,
        "has_acroform": acro is not None, "tagged": "/MarkInfo" in root,
        "page_reports": [page_report(p) for p in pdf.pages],
    }


def summarize(rep):
    print("== %s: %d page(s), encrypted=%s, signed=%s, acroform=%s, tagged=%s" % (
        rep["file"], rep["pages"], rep["encrypted"], rep["signature_fields"], rep["has_acroform"], rep["tagged"]))
    for i, p in enumerate(rep["page_reports"], 1):
        if "error" in p:
            print("  page %d: unreadable content stream: %s" % (i, p["error"]))
            continue
        print("  page %d: rotate=%d cropbox=%s BT=%d show-ops=%d (TJ=%d) spacing-ops=%d Tr=%s clip=%d text-in-forms=%d" % (
            i, p["rotate"], p["has_cropbox"], p["text_blocks_BT"], p["text_show_operators"], p["kerned_TJ_operators"],
            p["char_or_word_spacing_ops"], p["text_render_modes_used"], p["clip_operators"], p["form_xobjects_with_text"]))
        for key, f in p["fonts"].items():
            print("     font %s: %s %s embedded=%s subset=%s ToUnicode=%s enc=%s coverage=%s" % (
                key, f["name"], f["type"], f["embedded"], f["subset"], f["to_unicode"], f["encoding"],
                f["alphanumeric_coverage_in_subset"]))


def font_class(f):
    """One label per font, from the point of view of in-place text editing."""
    if f["type"] == "Type3":
        return "type3 (not editable)"
    if not f["embedded"]:
        return "not embedded (standard or system font)"
    if not f["subset"]:
        return "embedded, full font"
    cov = f["alphanumeric_coverage_in_subset"]
    if cov is None:
        return "embedded subset, no ToUnicode (cannot tell)"
    have = int(cov.split("/")[0])
    if have >= 56:
        return "embedded subset, nearly all letters/digits"
    if have >= 30:
        return "embedded subset, partial letters/digits"
    return "embedded subset, few letters/digits"


def print_summary(reports):
    fonts = Counter()
    files = len(reports)
    signed = sum(1 for r in reports if r["signature_fields"])
    encrypted = sum(1 for r in reports if r["encrypted"])
    tagged = sum(1 for r in reports if r["tagged"])
    pages = text_pages = form_text_pages = rotated = 0
    for r in reports:
        seen = set()
        for p in r["page_reports"]:
            pages += 1
            if "error" in p:
                continue
            if p["text_show_operators"]:
                text_pages += 1
            if p["form_xobjects_with_text"]:
                form_text_pages += 1
            if p["rotate"]:
                rotated += 1
            for key, f in p["fonts"].items():
                ident = (f["name"], f["type"])
                if ident in seen:
                    continue
                seen.add(ident)
                fonts[font_class(f)] += 1
    print("")
    print("SUMMARY over %d file(s), %d page(s)" % (files, pages))
    print("  files signed: %d, encrypted: %d, tagged: %d" % (signed, encrypted, tagged))
    print("  pages with text: %d, pages with text inside form XObjects: %d, pages with /Rotate: %d" % (text_pages, form_text_pages, rotated))
    total = sum(fonts.values()) or 1
    print("  fonts (distinct per file), by editability class:")
    for label, n in fonts.most_common():
        print("    %5.1f%%  %4d  %s" % (100.0 * n / total, n, label))


def main(argv):
    out = None
    want_summary = False
    files = []
    it = iter(argv)
    for a in it:
        if a == "--json":
            out = next(it)
        elif a == "--summary":
            want_summary = True
        else:
            files.append(a)
    if not files:
        print(__doc__)
        return 2
    reports = []
    for f in files:
        try:
            rep = file_report(f)
        except Exception as exc:
            print("== %s: cannot open: %s" % (f, exc))
            continue
        reports.append(rep)
        summarize(rep)
    if want_summary and reports:
        print_summary(reports)
    if out:
        with open(out, "w") as fh:
            json.dump(reports, fh, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
