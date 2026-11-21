#!/usr/bin/env python3
"""Independent check of the app's PDF-to-image and PDF-to-text output against Poppler (Phase 4).

Usage:  verify_conversion.py <conversion-verify dir> --corpus <corpus dir> [--json report.json]

The directory is written by PdfConversionInstrumentedTest.exportVerificationSet(): a manifest.json plus one
sub-folder of converted files per entry. Nothing here uses the app's code: references come from pdftoppm and
pdftotext (Poppler); the source PDFs are the regression corpus made by tools/corpus/make_corpus.py.

Images: file type and decodability, opaque pixels, size equals Poppler's size at the same dpi (or stays within the
pixel limit with the same aspect ratio when the resolution was reduced), the visible content matches Poppler's
render at the same size (mean difference of a reduced grayscale copy) and the bounding box of the ink matches
(this catches rotation, crop and page-order mistakes).
Text: UTF-8 without BOM, page separators match the page count, per-page character content matches pdftotext
(order-insensitive, whitespace-insensitive, so right-to-left reordering differences do not matter), the corpus
markers are present for whole-document conversions, and scanned documents give empty text.
Exit status 1 when any check fails.
"""
import argparse
import collections
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata

from PIL import Image, ImageChops, ImageOps, ImageStat

MAX_MEAN_DIFF = 12.0          # grayscale levels (0-255) on a reduced copy; anti-aliasing differs between renderers
BBOX_TOLERANCE = 0.03         # fraction of the page size
SIZE_TOLERANCE_PX = 2
SIZE_TOLERANCE_REL = 0.015
TEXT_SIMILARITY = 0.97
TEXT_SIMILARITY_UNORDERED = 0.90   # right-to-left scripts: shaping and digit handling differ between extractors
INK_THRESHOLD = 100           # inverted gray level (of the reduced copy) above which a pixel counts as ink


class Result:
    def __init__(self):
        self.failures = []
        self.notes = []

    def fail(self, entry, message):
        self.failures.append("%s: %s" % (entry, message))

    def note(self, entry, message):
        self.notes.append("%s: %s" % (entry, message))


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=False)


def poppler_png(pdf, page, password, extra):
    tmp = tempfile.mkdtemp(prefix="pp-")
    prefix = os.path.join(tmp, "p")
    # Viewers (and PdfRenderer) show the CropBox; pdftoppm uses the MediaBox unless told otherwise.
    cmd = ["pdftoppm", "-png", "-cropbox", "-f", str(page), "-l", str(page)] + extra
    if password:
        cmd += ["-upw", password]
    cmd += [pdf, prefix]
    proc = run(cmd)
    found = sorted(glob.glob(prefix + "-*.png"))
    if proc.returncode != 0 or not found:
        return None
    return found[0]


def ink_bbox(gray):
    ink = ImageOps.invert(gray).point(lambda v: 255 if v > INK_THRESHOLD else 0)
    return ink.getbbox()


def check_image(res, entry, path, fmt, source_pdf, page, dpi, password, capped, max_pixels):
    eid = "%s page %d" % (entry, page)
    with open(path, "rb") as fh:
        head = fh.read(8)
    if fmt == "png" and head[:8] != b"\x89PNG\r\n\x1a\n":
        res.fail(eid, "not a PNG file")
        return
    if fmt == "jpg" and head[:3] != b"\xff\xd8\xff":
        res.fail(eid, "not a JPEG file")
        return
    try:
        with Image.open(path) as im:
            im.verify()
        im = Image.open(path)
        im.load()
    except Exception as exc:
        res.fail(eid, "image does not decode: %s" % exc)
        return
    if im.mode in ("RGBA", "LA"):
        low = im.getchannel("A").getextrema()[0]
        if low != 255:
            res.fail(eid, "image has transparent pixels (min alpha %d)" % low)
    w, h = im.size
    # Poppler's -scale-to options ignore /Rotate, so references are rendered by dpi and resized afterwards.
    if capped:
        ref_nat = poppler_png(source_pdf, page, password, ["-r", "72"])
    else:
        ref_nat = poppler_png(source_pdf, page, password, ["-r", str(dpi)])
    if ref_nat is None:
        res.fail(eid, "Poppler could not render the source page")
        return
    nat = Image.open(ref_nat)
    rw, rh = nat.size
    ref_path = ref_nat
    if capped:
        if w * h > max_pixels:
            res.fail(eid, "%dx%d exceeds the pixel limit %d" % (w, h, max_pixels))
        if abs((w / h) - (rw / rh)) / (rw / rh) > 0.01:
            res.fail(eid, "aspect ratio %.4f differs from Poppler's %.4f" % (w / h, rw / rh))
        effective = max(36, round(72.0 * w / rw))
        ref_path = poppler_png(source_pdf, page, password, ["-r", str(effective)]) or ref_nat
    else:
        if abs(w - rw) > max(SIZE_TOLERANCE_PX, SIZE_TOLERANCE_REL * rw) or abs(h - rh) > max(SIZE_TOLERANCE_PX, SIZE_TOLERANCE_REL * rh):
            res.fail(eid, "size %dx%d differs from Poppler's %dx%d" % (w, h, rw, rh))
            return
    ours = im.convert("L")
    ref = Image.open(ref_path).convert("L")
    if ref.size != ours.size:
        ref = ref.resize(ours.size, Image.BOX)
    target_w = 200
    target_h = max(1, round(target_w * h / w))
    a = ours.resize((target_w, target_h), Image.BOX)
    b = ref.resize((target_w, target_h), Image.BOX)
    diff = ImageStat.Stat(ImageChops.difference(a, b)).mean[0]
    if diff > MAX_MEAN_DIFF:
        res.fail(eid, "content differs from Poppler's render (mean difference %.1f > %.1f)" % (diff, MAX_MEAN_DIFF))
    # The bounding box is taken on the reduced copies so that scanner noise and JPEG artefacts do not count as ink.
    bo, br = ink_bbox(a), ink_bbox(b)
    if (bo is None) != (br is None):
        res.fail(eid, "ink present in only one of ours/Poppler's (ours=%s, Poppler=%s)" % (bo, br))
    elif bo is not None:
        for i, (x, y) in enumerate(zip(bo, br)):
            dim = target_w if i % 2 == 0 else target_h
            if abs(x - y) > BBOX_TOLERANCE * dim:
                res.fail(eid, "ink bounding box %s differs from Poppler's %s (reduced copies)" % (bo, br))
                break
    res.note(eid, "ok (%dx%d, mean difference %.1f)" % (w, h, diff))


def normalize(s):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", s))


def similarity(a, b):
    ca, cb = collections.Counter(a), collections.Counter(b)
    total = max(sum(ca.values()), sum(cb.values()))
    if total == 0:
        return 1.0
    return sum((ca & cb).values()) / total


def poppler_text(pdf, page, password):
    cmd = ["pdftotext", "-enc", "UTF-8", "-f", str(page), "-l", str(page)]
    if password:
        cmd += ["-upw", password]
    cmd += [pdf, "-"]
    proc = run(cmd)
    if proc.returncode != 0:
        return None
    return proc.stdout.decode("utf-8", "replace")


def split_pages(text, separator, pages, entry, res):
    if separator == "FORM_FEED":
        parts = text.split("\f")
        if parts and parts[-1] == "":
            parts = parts[:-1]
        else:
            res.fail(entry, "text does not end with a form feed after the last page")
        if len(parts) != len(pages):
            res.fail(entry, "found %d form-feed separated pages, expected %d" % (len(parts), len(pages)))
            return None
        return parts
    if separator == "HEADER_LINE":
        found = list(re.finditer(r"--- Page (\d+) ---\n", text))
        numbers = [int(m.group(1)) for m in found]
        if numbers != pages:
            res.fail(entry, "page headers %s, expected %s" % (numbers, pages))
            return None
        parts = []
        for i, m in enumerate(found):
            end = found[i + 1].start() if i + 1 < len(found) else len(text)
            parts.append(text[m.end():end])
        return parts
    return [text]


def check_text(res, e, path, source_pdf, markers, total_pages, unordered=False, soft=False):
    eid = e["id"]
    raw = open(path, "rb").read()
    if raw.startswith(b"\xef\xbb\xbf"):
        res.fail(eid, "file starts with a byte order mark")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        res.fail(eid, "not valid UTF-8: %s" % exc)
        return
    pages = e["pages"]
    password = e.get("password")
    parts = split_pages(text, e["separator"], pages, eid, res)
    if parts is None:
        return
    if e["separator"] == "NONE":
        per_page = None
    else:
        per_page = parts
    if e.get("no_text_layer"):
        if len(normalize(text)) > 5:
            res.fail(eid, "scanned document produced text: %r" % normalize(text)[:40])
        else:
            res.note(eid, "ok (no text layer, empty output)")
        return
    if per_page is not None:
        for page, ours in zip(pages, per_page):
            ref = poppler_text(source_pdf, page, password)
            if ref is None:
                res.fail("%s page %d" % (eid, page), "pdftotext failed")
                continue
            a, b = normalize(ours), normalize(ref)
            if not b and a:
                res.note("%s page %d" % (eid, page), "Poppler extracted nothing (likely missing font data); ours has %d characters" % len(a))
            elif b and len(a) < max(1, len(b) // 20):
                res.fail("%s page %d" % (eid, page), "text is missing (ours %d characters, Poppler %d)" % (len(a), len(b)))
            elif b:
                sim = similarity(a, b)
                need = TEXT_SIMILARITY_UNORDERED if unordered else TEXT_SIMILARITY
                if sim < need:
                    res.fail("%s page %d" % (eid, page), "text differs from pdftotext (similarity %.3f < %.2f)" % (sim, need))
                else:
                    res.note("%s page %d" % (eid, page), "ok (similarity %.3f)" % sim)
    if markers and len(pages) == total_pages:
        whole = normalize(text)
        for m in markers:
            if normalize(m) in whole:
                continue
            if unordered and not (collections.Counter(normalize(m)) - collections.Counter(whole)):
                res.note(eid, "marker %r present as a set of characters (order not guaranteed for this script)" % m)
                continue
            if soft:
                res.note(eid, "marker %r not extracted (known limitation: depends on CMap support for non-embedded fonts)" % m)
                continue
            res.fail(eid, "missing expected text %r" % m)


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("verify_dir")
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--json")
    ap.add_argument("--min-entries", type=int, default=1)
    args = ap.parse_args(argv)

    manifest_path = os.path.join(args.verify_dir, "manifest.json")
    if not os.path.isfile(manifest_path):
        print("FAIL: no manifest.json in %s" % args.verify_dir)
        return 1
    manifest = json.load(open(manifest_path, encoding="utf-8"))
    entries = manifest["entries"]
    corpus_manifest = {}
    cm = os.path.join(args.corpus, "MANIFEST.json")
    if os.path.isfile(cm):
        corpus_manifest = json.load(open(cm, encoding="utf-8")).get("files", {})
    res = Result()
    if len(entries) < args.min_entries:
        res.fail("manifest", "only %d entries, expected at least %d" % (len(entries), args.min_entries))

    for e in entries:
        eid = e["id"]
        src = os.path.join(args.corpus, e["source"])
        if not os.path.isfile(src):
            res.fail(eid, "source PDF %s not found" % e["source"])
            continue
        folder = os.path.join(args.verify_dir, eid)
        if e["kind"] == "images":
            expected = e["files"]
            actual = sorted(os.listdir(folder)) if os.path.isdir(folder) else []
            if sorted(expected) != actual:
                res.fail(eid, "files in folder %s differ from the report %s" % (actual, sorted(expected)))
                continue
            if len(expected) != len(e["pages"]):
                res.fail(eid, "%d files for %d pages" % (len(expected), len(e["pages"])))
                continue
            for name, page in zip(expected, e["pages"]):
                m = re.search(r"_page_0*%d\.(png|jpg)$" % page, name)
                if not m:
                    res.fail(eid, "file name %s does not carry page number %d" % (name, page))
                    continue
                check_image(res, eid, os.path.join(folder, name), e["format"], src, page, e["dpi"], e.get("password"),
                            bool(e.get("reduced")), e.get("max_pixels") or 0)
        elif e["kind"] == "text":
            path = os.path.join(folder, e["file"])
            actual = sorted(os.listdir(folder)) if os.path.isdir(folder) else []
            if actual != [e["file"]]:
                res.fail(eid, "folder contains %s, expected only %s" % (actual, e["file"]))
                continue
            info = corpus_manifest.get(e["source"], {})
            markers = info.get("text_contains") or info.get("text_contains_after_unlock") or []
            unordered = "not guaranteed" in (info.get("note") or "")
            soft = "CMap" in (info.get("note") or "")
            check_text(res, e, path, src, markers, info.get("pages") or len(e["pages"]), unordered, soft)
        else:
            res.fail(eid, "unknown kind %s" % e["kind"])

    for n in res.notes:
        print("  " + n)
    print("")
    print("%d entries checked, %d failure(s)" % (len(entries), len(res.failures)))
    for f in res.failures:
        print("FAIL " + f)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"entries": len(entries), "failures": res.failures, "notes": res.notes}, fh, indent=2)
    return 1 if res.failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
