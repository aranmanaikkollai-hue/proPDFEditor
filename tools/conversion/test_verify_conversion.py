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


# ---------------------------------------------------------------------------------------------------------------------
# Creation checks (images to PDF, text to PDF)
# ---------------------------------------------------------------------------------------------------------------------
from PIL import ImageDraw  # noqa: E402
from reportlab.lib.utils import ImageReader  # noqa: E402
from reportlab.pdfbase import pdfmetrics  # noqa: E402
from reportlab.pdfbase.ttfonts import TTFont  # noqa: E402

DEJAVU = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def quadrant_image(w, h):
    im = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w // 2, h // 2), fill=(255, 0, 0))
    d.rectangle((w // 2, 0, w, h // 2), fill=(0, 255, 0))
    d.rectangle((0, h // 2, w // 2, h), fill=(0, 0, 255))
    d.rectangle((w // 2, h // 2, w, h), fill=(255, 255, 0))
    d.line((0, 0, w, h), fill=(0, 0, 0), width=4)
    return im


def save_jpeg_with_orientation(im, path, orientation):
    exif = Image.Exif()
    exif[0x0112] = orientation
    im.save(path, "JPEG", quality=95, exif=exif)


class CreationVerifierTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="vcc-")
        self.out = os.path.join(self.root, "out")
        self.corpus = os.path.join(self.root, "corpus")
        os.makedirs(self.out)
        os.makedirs(self.corpus)
        self.entries = []

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def verify(self):
        json.dump({"version": 1, "entries": self.entries}, open(os.path.join(self.out, "manifest.json"), "w"))
        return vc.main([self.out, "--corpus", self.corpus])

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

    # --- images to PDF ---------------------------------------------------------------------------------------
    def inputs(self, folder):
        d = os.path.join(folder, "inputs")
        os.makedirs(d)
        quadrant_image(300, 400).save(os.path.join(d, "001_a.png"))
        save_jpeg_with_orientation(quadrant_image(400, 300), os.path.join(d, "002_b.jpg"), 6)
        alpha = Image.new("RGBA", (200, 200), (0, 0, 0, 0))
        ImageDraw.Draw(alpha).ellipse((40, 40, 160, 160), fill=(255, 0, 255, 255))
        alpha.save(os.path.join(d, "003_c.png"))
        save_jpeg_with_orientation(quadrant_image(400, 300), os.path.join(d, "004_d.jpg"), 3)
        return ["inputs/" + n for n in sorted(os.listdir(d))]

    def build_images_pdf(self, eid, mode="A4", margin=18.0, dpi=150, ignore_orientation=False, ignore_margin=False, wrong_mode=None, truncate=False):
        folder = os.path.join(self.out, eid)
        os.makedirs(folder)
        rel = self.inputs(folder)
        c = canvas.Canvas(os.path.join(folder, "result.pdf"))
        tmpdir = tempfile.mkdtemp()
        for i, r in enumerate(rel):
            path = os.path.join(folder, r)
            up = vc.load_upright(path)
            drawn = Image.open(path).convert("RGB") if ignore_orientation else up
            pw, ph, (rl, rt, rw, rh) = vc.plan_page(wrong_mode or mode, up.size[0], up.size[1], 0.0 if ignore_margin else margin, dpi)
            c.setPageSize((pw, ph))
            if r.endswith(".jpg"):
                jp = os.path.join(tmpdir, "u%d.jpg" % i)
                drawn.save(jp, "JPEG", quality=95)
                c.drawImage(jp, rl, ph - rt - rh, rw, rh)
            else:
                c.drawImage(ImageReader(drawn), rl, ph - rt - rh, rw, rh, mask="auto")
            c.showPage()
        c.save()
        if truncate:
            p = os.path.join(folder, "result.pdf")
            data = open(p, "rb").read()
            open(p, "wb").write(data[: len(data) // 2])
        self.entries.append({"id": eid, "kind": "images_to_pdf", "output": "result.pdf", "inputs": rel,
                             "page_mode": mode, "margin": margin, "match_dpi": dpi})

    def test_faithful_images_pdf_passes(self):
        self.build_images_pdf("a4", "A4", 18.0)
        self.build_images_pdf("letter", "LETTER", 36.0)
        self.build_images_pdf("match", "MATCH_IMAGE", 0.0, 150)
        self.assertEqual(0, self.verify())

    def test_ignored_exif_orientation_is_caught(self):
        self.build_images_pdf("a4", "A4", 18.0, ignore_orientation=True)
        self.expect_failure("orientation or placement wrong")

    def test_wrong_page_size_is_caught(self):
        self.build_images_pdf("a4", "LETTER", 18.0, wrong_mode="A4")
        self.entries[-1]["page_mode"] = "LETTER"
        self.expect_failure("page size")

    def test_ignored_margin_is_caught(self):
        self.build_images_pdf("a4", "A4", 40.0, ignore_margin=True)
        self.expect_failure("FAIL")

    def test_broken_pdf_is_caught(self):
        self.build_images_pdf("a4", "A4", 18.0, truncate=True)
        self.expect_failure("qpdf --check failed")

    def test_wrong_page_count_is_caught(self):
        self.build_images_pdf("a4", "A4", 18.0)
        self.entries[-1]["inputs"] = self.entries[-1]["inputs"][:3]
        self.expect_failure("pages for 3 images")

    # --- text to PDF -----------------------------------------------------------------------------------------
    SAMPLE = "\n".join(
        ("" if n % 10 == 0 else "Line %d: The quick brown fox jumps over the lazy dog, again and again, until the sentence is long enough to wrap around the page." % n)
        for n in range(1, 61)
    ) + "\n" + "Supercalifragilisticexpialidocious" * 4

    def build_text_pdf(self, eid, mode="A4", margin=54.0, size=11.0, overflow=False, drop_last=False, helvetica=False, pages_override=None):
        folder = os.path.join(self.out, eid)
        os.makedirs(folder)
        open(os.path.join(folder, "source.txt"), "w", encoding="utf-8").write(self.SAMPLE)
        font = "Helvetica"
        if not helvetica:
            if "DejaVuT" not in pdfmetrics.getRegisteredFontNames():
                pdfmetrics.registerFont(TTFont("DejaVuT", DEJAVU))
            font = "DejaVuT"
        pw, ph = vc.A4 if mode == "A4" else vc.LETTER
        text = self.SAMPLE.rsplit("\n", 1)[0] if drop_last else self.SAMPLE
        lines = []
        for para in text.split("\n"):
            if not para.strip():
                lines.append("")
            elif overflow:
                lines.append(para)
            else:
                cur = ""
                for word in para.split(" "):
                    trial = (cur + " " + word).strip()
                    if pdfmetrics.stringWidth(trial, font, size) <= pw - 2 * margin:
                        cur = trial
                        continue
                    if cur:
                        lines.append(cur)
                    cur = word
                    while pdfmetrics.stringWidth(cur, font, size) > pw - 2 * margin:
                        k = len(cur) - 1
                        while pdfmetrics.stringWidth(cur[:k], font, size) > pw - 2 * margin:
                            k -= 1
                        lines.append(cur[:k])
                        cur = cur[k:]
                lines.append(cur)
        leading = size * 1.25
        per_page = int((ph - 2 * margin) // leading)
        c = canvas.Canvas(os.path.join(folder, "result.pdf"), pagesize=(pw, ph), initialFontName=font)
        pages = 0
        for start in range(0, len(lines), per_page):
            c.setFont(font, size)
            y = ph - margin - size
            for line in lines[start:start + per_page]:
                if line:
                    c.drawString(margin, y, line)
                y -= leading
            c.showPage()
            pages += 1
        c.save()
        self.entries.append({"id": eid, "kind": "text_to_pdf", "output": "result.pdf", "input": "source.txt", "page_mode": mode,
                             "margin": margin, "font_size": size, "pages": pages_override or pages, "lossy": False})

    @unittest.skipUnless(os.path.isfile(DEJAVU), "DejaVu font not installed")
    def test_faithful_text_pdf_passes(self):
        self.build_text_pdf("t1")
        self.build_text_pdf("t2", "LETTER", 72.0, 14.0)
        self.assertEqual(0, self.verify())

    @unittest.skipUnless(os.path.isfile(DEJAVU), "DejaVu font not installed")
    def test_text_running_past_the_margin_is_caught(self):
        self.build_text_pdf("t1", overflow=True)
        self.expect_failure("outside the")

    @unittest.skipUnless(os.path.isfile(DEJAVU), "DejaVu font not installed")
    def test_missing_text_is_caught_in_pdf(self):
        self.build_text_pdf("t1", drop_last=True)
        self.expect_failure("text differs from the source")

    @unittest.skipUnless(os.path.isfile(DEJAVU), "DejaVu font not installed")
    def test_unembedded_font_is_caught(self):
        self.build_text_pdf("t1", helvetica=True)
        self.expect_failure("not an embedded subset")

    @unittest.skipUnless(os.path.isfile(DEJAVU), "DejaVu font not installed")
    def test_wrong_reported_page_count_is_caught(self):
        self.build_text_pdf("t1", pages_override=99)
        self.expect_failure("the app reported")


if __name__ == "__main__":
    unittest.main()
