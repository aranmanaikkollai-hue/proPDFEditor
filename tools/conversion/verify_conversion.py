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
Creation (images to PDF, text to PDF): the PDF passes qpdf --check, has the expected page count and sizes (A4, Letter or
the picture size), every page shows the upright picture (EXIF orientation applied) inside the planned rectangle with white
margins, JPEG input is embedded without recompression (pdfimages), text equals the source after whitespace removal, fonts are
embedded subsets, and every word lies inside the margins (pdftotext -bbox).
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


# ---- creation: images to PDF and text to PDF --------------------------------------------------------------------------

A4 = (595.2756, 841.8898)
LETTER = (612.0, 792.0)
IMAGE_MAX_MEAN_DIFF = 14.0
MARGIN_MIN_BRIGHTNESS = 250.0


def plan_page(mode, dw, dh, margin, match_dpi):
    """Page size and picture rectangle (left, top, width, height in points from the top-left) as the app's rule defines them."""
    if mode == "MATCH_IMAGE":
        dpi = min(600, max(36, match_dpi))
        pw, ph = dw * 72.0 / dpi, dh * 72.0 / dpi
        big = max(pw, ph)
        if big > 14400.0:
            pw, ph = pw * 14400.0 / big, ph * 14400.0 / big
        return pw, ph, (0.0, 0.0, pw, ph)
    base = A4 if mode == "A4" else LETTER
    pw, ph = (base[1], base[0]) if dw > dh else base
    m = min(max(margin, 0.0), min(pw, ph) / 4.0)
    scale = min((pw - 2 * m) / dw, (ph - 2 * m) / dh)
    w, h = dw * scale, dh * scale
    return pw, ph, ((pw - w) / 2.0, (ph - h) / 2.0, w, h)


def qpdf_check(res, eid, pdf):
    proc = run(["qpdf", "--check", pdf])
    out = (proc.stdout + proc.stderr).decode("utf-8", "replace")
    if proc.returncode == 0:
        return True
    if proc.returncode == 3:
        res.note(eid, "qpdf reports warnings: %s" % out.strip().splitlines()[-1][:120])
        return True
    res.fail(eid, "qpdf --check failed: %s" % " | ".join(out.strip().splitlines()[-3:])[:300])
    return False


def pdfinfo_pages(pdf):
    proc = run(["pdfinfo", "-f", "1", "-l", "9999", pdf])
    text = proc.stdout.decode("utf-8", "replace")
    m = re.search(r"^Pages:\s+(\d+)", text, re.M)
    sizes = {}
    for mm in re.finditer(r"^Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+) pts", text, re.M):
        sizes[int(mm.group(1))] = (float(mm.group(2)), float(mm.group(3)))
    return (int(m.group(1)) if m else None), sizes


def load_upright(path):
    im = Image.open(path)
    im.load()
    im = ImageOps.exif_transpose(im)
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
        im = Image.alpha_composite(bg, im)
    return im.convert("RGB")


def check_images_to_pdf(res, e, folder):
    eid = e["id"]
    pdf = os.path.join(folder, e["output"])
    if not os.path.isfile(pdf):
        res.fail(eid, "output %s missing" % e["output"])
        return
    if not qpdf_check(res, eid, pdf):
        return
    inputs = [os.path.join(folder, x) for x in e["inputs"]]
    pages, sizes = pdfinfo_pages(pdf)
    if pages != len(inputs):
        res.fail(eid, "PDF has %s pages for %d images" % (pages, len(inputs)))
        return
    listing = run(["pdfimages", "-list", pdf]).stdout.decode("utf-8", "replace").splitlines()[2:]
    encs = {}
    for line in listing:
        cols = line.split()
        if len(cols) > 8 and cols[0].isdigit():
            encs.setdefault(int(cols[0]), []).append(cols[8])
    for i, path in enumerate(inputs, 1):
        pid = "%s page %d" % (eid, i)
        up = load_upright(path)
        dw, dh = up.size
        pw, ph, (rl, rt, rw, rh) = plan_page(e["page_mode"], dw, dh, float(e["margin"]), int(e["match_dpi"]))
        got = sizes.get(i)
        if got is None or abs(got[0] - pw) > 1.0 or abs(got[1] - ph) > 1.0:
            res.fail(pid, "page size %s, expected %.1f x %.1f pt" % (got, pw, ph))
            continue
        # pdfimages labels DCT (JPEG) streams "jpeg"; older versions print "dct"
        if path.lower().endswith((".jpg", ".jpeg")) and not ({"jpeg", "dct"} & set(encs.get(i, []))):
            res.fail(pid, "JPEG input was not embedded as a JPEG (pdfimages: %s)" % encs.get(i))
        ref = poppler_png(pdf, i, None, ["-r", "72"])
        if ref is None:
            res.fail(pid, "Poppler could not render the page")
            continue
        page = Image.open(ref).convert("RGB")
        if abs(page.width - pw) > 2 or abs(page.height - ph) > 2:
            res.fail(pid, "rendered size %dx%d differs from %.1f x %.1f" % (page.width, page.height, pw, ph))
            continue
        box = (round(rl), round(rt), round(rl + rw), round(rt + rh))
        inner = (box[0] + 2, box[1] + 2, box[2] - 2, box[3] - 2)
        if inner[2] - inner[0] < 4 or inner[3] - inner[1] < 4:
            res.fail(pid, "picture rectangle is too small to compare: %s" % (box,))
            continue
        expected = up.resize((max(1, box[2] - box[0]), max(1, box[3] - box[1])), Image.BOX)
        ex_inner = expected.crop((2, 2, expected.width - 2, expected.height - 2))
        got_inner = page.crop(inner)
        if got_inner.size != ex_inner.size:
            got_inner = got_inner.resize(ex_inner.size, Image.BOX)
        tw = 200
        th = max(1, round(tw * ex_inner.height / ex_inner.width))
        a = ex_inner.resize((tw, th), Image.BOX).convert("L")
        b = got_inner.resize((tw, th), Image.BOX).convert("L")
        diff = ImageStat.Stat(ImageChops.difference(a, b)).mean[0]
        if diff > IMAGE_MAX_MEAN_DIFF:
            res.fail(pid, "picture differs from the upright source (mean difference %.1f > %.1f); orientation or placement wrong" % (diff, IMAGE_MAX_MEAN_DIFF))
        outside = page.copy()
        outside.paste((255, 255, 255), (max(0, box[0] - 1), max(0, box[1] - 1), box[2] + 1, box[3] + 1))
        bright = ImageStat.Stat(outside.convert("L")).mean[0]
        if bright < MARGIN_MIN_BRIGHTNESS:
            res.fail(pid, "margins are not white (mean brightness %.1f)" % bright)
        res.note(pid, "ok (%.1f x %.1f pt, mean difference %.1f)" % (pw, ph, diff))


def decode_source_text(raw):
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw[3:].decode("utf-8")
    if raw.startswith(b"\xff\xfe"):
        return raw[2:].decode("utf-16-le")
    if raw.startswith(b"\xfe\xff"):
        return raw[2:].decode("utf-16-be")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp1252")


def check_text_to_pdf(res, e, folder):
    eid = e["id"]
    pdf = os.path.join(folder, e["output"])
    if not os.path.isfile(pdf):
        res.fail(eid, "output %s missing" % e["output"])
        return
    if not qpdf_check(res, eid, pdf):
        return
    pages, sizes = pdfinfo_pages(pdf)
    if pages != e["pages"] or not pages:
        res.fail(eid, "PDF has %s pages, the app reported %s" % (pages, e["pages"]))
        return
    base = A4 if e["page_mode"] == "A4" else LETTER
    for p, size in sizes.items():
        if abs(size[0] - base[0]) > 0.5 or abs(size[1] - base[1]) > 0.5:
            res.fail(eid, "page %d size %s, expected %s" % (p, size, base))
            return
    fonts = run(["pdffonts", pdf]).stdout.decode("utf-8", "replace").splitlines()[2:]
    if not fonts:
        res.fail(eid, "no fonts in the PDF")
    for line in fonts:
        cols = line.split()
        # name type encoding emb sub uni object-id ; the name has no spaces
        if len(cols) >= 5 and (cols[-5] != "yes" or cols[-4] != "yes"):
            res.fail(eid, "font is not an embedded subset: %s" % line.strip()[:100])
    src = decode_source_text(open(os.path.join(folder, e["input"]), "rb").read())
    proc = run(["pdftotext", "-enc", "UTF-8", pdf, "-"])
    got = normalize(proc.stdout.decode("utf-8", "replace"))
    want = normalize(src)
    if e.get("lossy"):
        want = "".join(ch for ch in want if ord(ch) < 0x370)
        got = "".join(ch for ch in got if ord(ch) < 0x370 and ch != "?")
    if got != want:
        sim = similarity(got, want)
        res.fail(eid, "text differs from the source (similarity %.4f, %d vs %d characters)" % (sim, len(got), len(want)))
    bbox = run(["pdftotext", "-bbox", pdf, "-"]).stdout.decode("utf-8", "replace")
    margin = float(e["margin"])
    size = float(e["font_size"])
    words = 0
    bad = 0
    for page in re.finditer(r'<page width="([\d.]+)" height="([\d.]+)">(.*?)</page>', bbox, re.S):
        pw, ph = float(page.group(1)), float(page.group(2))
        for w in re.finditer(r'<word xMin="([\d.]+)" yMin="([\d.]+)" xMax="([\d.]+)" yMax="([\d.]+)">', page.group(3)):
            x0, y0, x1, y1 = (float(w.group(i)) for i in range(1, 5))
            words += 1
            if x0 < margin - 1.5 or x1 > pw - margin + 1.5 or y0 < margin - 1.5 or y1 > ph - margin + 0.3 * size + 1.5:
                bad += 1
    if words == 0:
        res.fail(eid, "pdftotext -bbox found no words")
    elif bad:
        res.fail(eid, "%d of %d words lie outside the %.0f pt margins" % (bad, words, margin))
    else:
        res.note(eid, "ok (%d pages, %d words inside the margins)" % (pages, words))


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
        folder = os.path.join(args.verify_dir, eid)
        src = os.path.join(args.corpus, e.get("source", ""))
        if e["kind"] in ("images", "text") and not os.path.isfile(src):
            res.fail(eid, "source PDF %s not found" % e.get("source"))
            continue
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
        elif e["kind"] == "images_to_pdf":
            check_images_to_pdf(res, e, folder)
        elif e["kind"] == "text_to_pdf":
            check_text_to_pdf(res, e, folder)
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
