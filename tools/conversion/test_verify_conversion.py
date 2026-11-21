#!/usr/bin/env python3
"""Self-test of verify_conversion.py: it must pass faithful output and catch each kind of wrong output.

Needs poppler-utils, reportlab, pikepdf and pillow (the CI jobs that run the verifier install them).
Run:  python3 -m unittest tools/conversion/test_verify_conversion.py   (or python3 test_verify_conversion.py)
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

import pikepdf
from PIL import Image, ImageFilter
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verify_conversion as vc  # noqa: E402


def make_corpus(d):
    c = canvas.Canvas(os.path.join(d, "a.pdf"), pagesize=A4)
    for i, marker in enumerate(["ALPHA-7731", "BRAVO-4410", "CHARLIE-9082"]):
        c.setFont("Helvetica", 22)
        c.drawString(72, 700, "Page %d marker %s" % (i + 1, marker))
        c.setFont("Helvetica", 12)
        for line in range(12):
            c.drawString(72, 650 - 18 * line, "Lorem ipsum dolor sit amet %d consectetur adipiscing elit" % (line + i))
        c.showPage()
    c.save()
    c = canvas.Canvas(os.path.join(d, "_r.pdf"), pagesize=A4)
    for i in range(2):
        c.setFont("Helvetica", 20)
        c.drawString(60, 780, "TOP-LEFT MARKER %d" % i)
        c.rect(300, 100, 200, 80, fill=1)
        c.showPage()
    c.save()
    with pikepdf.open(os.path.join(d, "_r.pdf")) as pdf:
        pdf.pages[1].Rotate = 90
        pdf.save(os.path.join(d, "rot.pdf"))
    os.remove(os.path.join(d, "_r.pdf"))
    img = Image.new("L", (800, 1100), 255)
    for x in range(100, 700):
        for y in range(200, 260):
            img.putpixel((x, y), 0)
    img.convert("RGB").save(os.path.join(d, "scan.pdf"), resolution=100.0)
    c = canvas.Canvas(os.path.join(d, "_c.pdf"), pagesize=(612, 792))
    c.setFont("Helvetica", 24)
    c.drawString(80, 700, "CROPPED PAGE MARKER")
    c.rect(60, 60, 480, 640)
    c.showPage()
    c.save()
    with pikepdf.open(os.path.join(d, "_c.pdf")) as pdf:
        pdf.pages[0].CropBox = [50, 50, 450, 750]
        pdf.save(os.path.join(d, "crop.pdf"))
    os.remove(os.path.join(d, "_c.pdf"))
    manifest = {"files": {
        "crop.pdf": {"pages": 1, "text_contains": ["CROPPED PAGE MARKER"]},
        "a.pdf": {"pages": 3, "text_contains": ["ALPHA-7731", "BRAVO-4410", "CHARLIE-9082"]},
        "rot.pdf": {"pages": 2, "text_contains": ["TOP-LEFT MARKER 0"]},
        "scan.pdf": {"pages": 1, "text_contains": []},
    }}
    json.dump(manifest, open(os.path.join(d, "MANIFEST.json"), "w"))


def poppler_image(pdf, page, dpi, cropbox=True):
    tmp = tempfile.mkdtemp()
    subprocess.run(["pdftoppm", "-png"] + (["-cropbox"] if cropbox else []) + ["-r", str(dpi), "-f", str(page), "-l", str(page), pdf, os.path.join(tmp, "p")], check=True)
    f = [x for x in os.listdir(tmp) if x.endswith(".png")][0]
    return Image.open(os.path.join(tmp, f)).convert("RGB")


def poppler_text(pdf, page):
    return subprocess.run(["pdftotext", "-enc", "UTF-8", "-f", str(page), "-l", str(page), pdf, "-"], capture_output=True, check=True).stdout.decode()


class VerifierTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="vc-")
        self.corpus = os.path.join(self.root, "corpus")
        self.out = os.path.join(self.root, "out")
        os.makedirs(self.corpus)
        os.makedirs(self.out)
        make_corpus(self.corpus)
        self.entries = []

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    # --- builders for faithful output --------------------------------------------------------------------------
    def images(self, eid, source, pages, dpi=72, fmt="png", blur=True, cropbox=True, **extra):
        folder = os.path.join(self.out, eid)
        os.makedirs(folder)
        names = []
        for p in pages:
            im = poppler_image(os.path.join(self.corpus, source), p, dpi, cropbox)
            if blur:
                im = im.filter(ImageFilter.GaussianBlur(0.6))
            name = "%s_page_%03d.%s" % (source[:-4], p, fmt)
            im.save(os.path.join(folder, name), "PNG" if fmt == "png" else "JPEG", quality=85)
            names.append(name)
        e = {"id": eid, "kind": "images", "source": source, "format": fmt, "dpi": dpi, "max_pixels": 25000000,
             "pages": pages, "files": names, "password": None, "reduced": False}
        e.update(extra)
        self.entries.append(e)
        return folder, names

    def text(self, eid, source, pages, separator="FORM_FEED", **extra):
        folder = os.path.join(self.out, eid)
        os.makedirs(folder)
        parts = [poppler_text(os.path.join(self.corpus, source), p) for p in pages]
        if separator == "FORM_FEED":
            body = "".join(t.replace("\f", "") + "\f" for t in parts)
        else:
            body = "".join("--- Page %d ---\n%s" % (p, t.replace("\f", "")) for p, t in zip(pages, parts))
        name = source[:-4] + ".txt"
        open(os.path.join(folder, name), "w", encoding="utf-8", newline="").write(body)
        e = {"id": eid, "kind": "text", "source": source, "separator": separator, "pages": pages, "file": name,
             "password": None, "no_text_layer": False}
        e.update(extra)
        self.entries.append(e)
        return folder, name

    def verify(self):
        json.dump({"version": 1, "entries": self.entries}, open(os.path.join(self.out, "manifest.json"), "w"))
        return vc.main([self.out, "--corpus", self.corpus])

    # --- faithful output passes --------------------------------------------------------------------------------
    def test_faithful_output_passes(self):
        self.images("i1", "a.pdf", [1, 2, 3], dpi=72)
        self.images("i2", "rot.pdf", [1, 2], dpi=72)
        self.images("i3", "a.pdf", [2], dpi=100, fmt="jpg")
        self.text("t1", "a.pdf", [1, 2, 3])
        self.text("t2", "a.pdf", [1, 2, 3], separator="HEADER_LINE")
        self.text("t3", "rot.pdf", [1, 2])
        self.assertEqual(0, self.verify())

    def test_cropped_page_rendered_as_displayed_passes(self):
        self.images("ic", "crop.pdf", [1], dpi=72)
        self.assertEqual(0, self.verify())

    def test_cropped_page_rendered_from_the_media_box_is_caught(self):
        self.images("ic", "crop.pdf", [1], dpi=72, cropbox=False)
        self.expect_failure("differs from Poppler's")

    def test_presentation_forms_compare_equal_to_base_letters(self):
        folder, name = self.text("t", "a.pdf", [1, 2, 3])
        p = os.path.join(folder, name)
        s = open(p, encoding="utf-8").read().replace("ALPHA", "\uff21\uff2c\uff30\uff28\uff21")  # fullwidth letters
        open(p, "w", encoding="utf-8", newline="").write(s)
        self.assertEqual(0, self.verify())

    def test_scanned_document_with_empty_text_passes(self):
        folder = os.path.join(self.out, "ts")
        os.makedirs(folder)
        open(os.path.join(folder, "scan.txt"), "w").write("\n\f")
        self.entries.append({"id": "ts", "kind": "text", "source": "scan.pdf", "separator": "FORM_FEED", "pages": [1],
                             "file": "scan.txt", "password": None, "no_text_layer": True})
        self.assertEqual(0, self.verify())

    def test_reduced_resolution_within_limit_passes(self):
        folder, names = self.images("ic", "a.pdf", [1], dpi=72, blur=False)
        im = Image.open(os.path.join(folder, names[0]))
        im.resize((im.width // 2, im.height // 2), Image.BOX).save(os.path.join(folder, names[0]))
        self.entries[-1].update({"dpi": 144, "reduced": True, "max_pixels": 200000})
        self.assertEqual(0, self.verify())

    # --- wrong output is caught --------------------------------------------------------------------------------
    def expect_failure(self, fragment):
        buf = io.StringIO()
        old = sys.stdout
        sys.stdout = buf
        try:
            code = self.verify()
        finally:
            sys.stdout = old
        self.assertEqual(1, code, buf.getvalue())
        self.assertIn(fragment, buf.getvalue())

    def test_rotated_image_is_caught(self):
        folder, names = self.images("i", "a.pdf", [1])
        im = Image.open(os.path.join(folder, names[0]))
        im.rotate(90, expand=True).save(os.path.join(folder, names[0]))
        self.expect_failure("differs from Poppler's")

    def test_mirrored_content_is_caught(self):
        folder, names = self.images("i", "rot.pdf", [1])
        im = Image.open(os.path.join(folder, names[0])).transpose(Image.FLIP_TOP_BOTTOM)
        im.save(os.path.join(folder, names[0]))
        self.expect_failure("FAIL")

    def test_blank_image_is_caught(self):
        folder, names = self.images("i", "a.pdf", [1])
        im = Image.open(os.path.join(folder, names[0]))
        Image.new("RGB", im.size, "white").save(os.path.join(folder, names[0]))
        self.expect_failure("ink present in only one")

    def test_transparent_pixels_are_caught(self):
        folder, names = self.images("i", "a.pdf", [1])
        im = Image.open(os.path.join(folder, names[0])).convert("RGBA")
        im.putpixel((0, 0), (255, 255, 255, 0))
        im.save(os.path.join(folder, names[0]))
        self.expect_failure("transparent")

    def test_wrong_file_type_is_caught(self):
        folder, names = self.images("i", "a.pdf", [1])
        Image.open(os.path.join(folder, names[0])).convert("RGB").save(os.path.join(folder, names[0]), "JPEG")
        self.expect_failure("not a PNG")

    def test_wrong_size_is_caught(self):
        folder, names = self.images("i", "a.pdf", [1], blur=False)
        im = Image.open(os.path.join(folder, names[0]))
        im.resize((im.width // 2, im.height // 2)).save(os.path.join(folder, names[0]))
        self.expect_failure("differs from Poppler's")

    def test_size_over_the_limit_is_caught(self):
        self.images("i", "a.pdf", [1], blur=False)
        self.entries[-1].update({"reduced": True, "max_pixels": 1000})
        self.expect_failure("exceeds the pixel limit")

    def test_leftover_file_is_caught(self):
        folder, _ = self.images("i", "a.pdf", [1])
        open(os.path.join(folder, "partial.png"), "wb").write(b"x")
        self.expect_failure("differ from the report")

    def test_missing_marker_is_caught(self):
        folder, name = self.text("t", "a.pdf", [1, 2, 3])
        p = os.path.join(folder, name)
        s = open(p, encoding="utf-8").read().replace("BRAVO-4410", "BRAVO-0000")
        open(p, "w", encoding="utf-8", newline="").write(s)
        self.expect_failure("missing expected text")

    def test_missing_text_is_caught(self):
        folder, name = self.text("t", "a.pdf", [1, 2, 3])
        p = os.path.join(folder, name)
        parts = open(p, encoding="utf-8").read().split("\f")
        parts[1] = ""
        open(p, "w", encoding="utf-8", newline="").write("\f".join(parts))
        self.expect_failure("text is missing")

    def test_wrong_page_separator_count_is_caught(self):
        folder, name = self.text("t", "a.pdf", [1, 2, 3])
        p = os.path.join(folder, name)
        s = open(p, encoding="utf-8").read()
        open(p, "w", encoding="utf-8", newline="").write(s.rstrip("\f"))
        self.expect_failure("form feed")

    def test_wrong_page_headers_are_caught(self):
        folder, name = self.text("t", "a.pdf", [1, 2, 3], separator="HEADER_LINE")
        p = os.path.join(folder, name)
        s = open(p, encoding="utf-8").read().replace("--- Page 2 ---", "--- Page 5 ---")
        open(p, "w", encoding="utf-8", newline="").write(s)
        self.expect_failure("page headers")

    def test_byte_order_mark_is_caught(self):
        folder, name = self.text("t", "a.pdf", [1, 2, 3])
        p = os.path.join(folder, name)
        data = open(p, "rb").read()
        open(p, "wb").write(b"\xef\xbb\xbf" + data)
        self.expect_failure("byte order mark")

    def test_text_in_a_scanned_document_is_caught(self):
        folder = os.path.join(self.out, "ts")
        os.makedirs(folder)
        open(os.path.join(folder, "scan.txt"), "w").write("invented words appear here\n\f")
        self.entries.append({"id": "ts", "kind": "text", "source": "scan.pdf", "separator": "FORM_FEED", "pages": [1],
                             "file": "scan.txt", "password": None, "no_text_layer": True})
        self.expect_failure("scanned document produced text")

    def test_missing_manifest_is_caught(self):
        self.assertEqual(1, vc.main([self.out, "--corpus", self.corpus]))


if __name__ == "__main__":
    unittest.main()
