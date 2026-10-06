#!/usr/bin/env python3
"""
ProPDF regression corpus generator (Phase 9).

Everything is GENERATED from scratch by this script - no third-party documents are copied, so there is no
upstream copyright to track. Embedded fonts are the only third-party material:
  - Tamil:  FreeSans (GNU FreeFont, GPLv3 with font-embedding exception; embedding in a document does not
            make the document GPL).                                   [LEGAL: confirm if you redistribute fonts]
  - Arabic: DejaVu Sans (Bitstream Vera / DejaVu free license)
  - CJK:    NO font file used: predefined Adobe CID fonts (STSong-Light / HeiseiMin-W3 / HYSMyeongJo-Medium),
            referenced by name, not embedded (tests the non-embedded CID path; viewers substitute a system font)
Tamil/Arabic fonts are SUBSET when embedded by ReportLab. No shaping engine is used: Tamil/Arabic glyphs are placed per code
point, so these files test Unicode EXTRACTION / SEARCH / round-trip, NOT correct visual shaping.

Test-only passwords (public, not secrets): USER_PW / OWNER_PW below. Never reuse them anywhere real.

Usage: make_corpus.py <outdir>
"""
import os, sys, io, json, hashlib, subprocess, shutil, random
from reportlab import rl_config
rl_config.invariant = 1          # reproducible timestamps/ids in ReportLab output (qpdf encryption and JPEG noise remain seeded/random)
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4, letter, A5, legal
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.utils import ImageReader
from PIL import Image, ImageDraw, ImageFont
import pikepdf
from pikepdf import Name, Dictionary, Array, String

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
USER_PW, OWNER_PW = "user-pass-123", "owner-pass-456"
random.seed(20261005)

FONT_TAMIL = "/usr/share/fonts/truetype/freefont/FreeSans.ttf"
FONT_ARABIC = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
pdfmetrics.registerFont(TTFont("Tamil", FONT_TAMIL))
pdfmetrics.registerFont(TTFont("Arabic", FONT_ARABIC))
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
for _f in ("STSong-Light", "HeiseiMin-W3", "HYSMyeongJo-Medium"):   # predefined Adobe CID fonts: NOT embedded
    pdfmetrics.registerFont(UnicodeCIDFont(_f))

manifest = {}

def record(name, **props):
    path = os.path.join(OUT, name)
    data = open(path, "rb").read()
    props["bytes"] = len(data)
    props["sha256"] = hashlib.sha256(data).hexdigest()
    manifest[name] = props

def text_pdf(path, pages_text, size=A4, font="Helvetica", fsize=14, title=None):
    c = canvas.Canvas(path, pagesize=size, pageCompression=1)
    if title: c.setTitle(title)
    for lines in pages_text:
        c.setFont(font, fsize)
        y = size[1] - 72
        for ln in lines:
            c.drawString(72, y, ln); y -= fsize * 1.6
        c.showPage()
    c.save()

# ---------------------------------------------------------------- 01 normal
text_pdf(f"{OUT}/01_normal.pdf",
         [["ProPDF corpus - normal document", "Page one. The quick brown fox jumps over the lazy dog.", "Keyword: ALPHA-7731"],
          ["Page two continues here.", "Keyword: BRAVO-4410"],
          ["Page three is the last page.", "Keyword: CHARLIE-9082"]], title="Corpus normal")
record("01_normal.pdf", pages=3, text_contains=["ALPHA-7731", "BRAVO-4410", "CHARLIE-9082"], encrypted=False)

# ---------------------------------------------------------------- 02 large (many pages)
c = canvas.Canvas(f"{OUT}/02_large_300pages.pdf", pagesize=A4, pageCompression=1)
for i in range(1, 301):
    c.setFont("Helvetica", 12)
    c.drawString(72, 770, f"Large document page {i} of 300")
    for k in range(40):
        c.drawString(72, 740 - k * 16, f"Line {k:02d} of page {i}: lorem ipsum dolor sit amet LG-{i:03d}-{k:02d}")
    c.showPage()
c.save()
record("02_large_300pages.pdf", pages=300, text_contains=["LG-001-00", "LG-300-39"], encrypted=False)

# ---------------------------------------------------------------- 03 scanned (image only, NO text layer)
def scan_image(w=1240, h=1754):
    img = Image.new("L", (w, h), 245)
    d = ImageDraw.Draw(img)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 44)
    except Exception:
        f = ImageFont.load_default()
    for i, t in enumerate(["SCANNED PAGE - NO TEXT LAYER", "OCR keyword: DELTA-5521", "The quick brown fox jumps over the lazy dog."]):
        d.text((100, 150 + i * 90), t, fill=20, font=f)
    for _ in range(3000):                       # light speckle noise like a real scan
        x, y = random.randrange(w), random.randrange(h); d.point((x, y), fill=random.randrange(150, 230))
    return img.rotate(0.6, fillcolor=245)       # slight skew
imgs = [scan_image() for _ in range(2)]
imgs[0].save(f"{OUT}/03_scanned_2pages.pdf", save_all=True, append_images=imgs[1:], resolution=150.0, quality=80)
record("03_scanned_2pages.pdf", pages=2, text_contains=[], has_text_layer=False, ocr_expected_contains=["DELTA-5521"], encrypted=False)

# ---------------------------------------------------------------- 04 encrypted (AES-256, and AES-128)
subprocess.run(["qpdf", f"--encrypt", USER_PW, OWNER_PW, "256", "--", f"{OUT}/01_normal.pdf", f"{OUT}/04_encrypted_aes256.pdf"], check=True)
subprocess.run(["qpdf", f"--encrypt", USER_PW, OWNER_PW, "128", "--use-aes=y", "--", f"{OUT}/01_normal.pdf", f"{OUT}/04_encrypted_aes128.pdf"], check=True)
subprocess.run(["qpdf", f"--encrypt", "", OWNER_PW, "256", "--print=none", "--modify=none", "--extract=n", "--", f"{OUT}/01_normal.pdf", f"{OUT}/04_owner_restricted_no_user_pw.pdf"], check=True)
for n, user in (("04_encrypted_aes256.pdf", USER_PW), ("04_encrypted_aes128.pdf", USER_PW), ("04_owner_restricted_no_user_pw.pdf", "")):
    record(n, pages=3, encrypted=True, user_password=user, owner_password=OWNER_PW,
           text_contains_after_unlock=["ALPHA-7731"])

# ---------------------------------------------------------------- 05 forms (AcroForm)
c = canvas.Canvas(f"{OUT}/05_form.pdf", pagesize=A4)
c.setFont("Helvetica", 14); c.drawString(72, 780, "ProPDF corpus - AcroForm")
f = c.acroForm
c.drawString(72, 730, "Name:");  f.textfield(name="full_name", tooltip="Full name", x=140, y=722, width=260, height=22, value="", borderStyle="inset", forceBorder=True)
c.drawString(72, 690, "Agree:"); f.checkbox(name="agree", tooltip="Agree", x=140, y=686, size=18, checked=False, buttonStyle="check", forceBorder=True)
c.drawString(72, 650, "Colour:"); f.choice(name="colour", tooltip="Colour", x=140, y=642, width=140, height=22, value="Red", options=["Red", "Green", "Blue"], forceBorder=True)
c.drawString(72, 610, "Notes:"); f.textfield(name="notes", tooltip="Notes", x=140, y=560, width=260, height=60, fieldFlags="multiline", forceBorder=True)
c.save()
record("05_form.pdf", pages=1, encrypted=False, form_fields=["full_name", "agree", "colour", "notes"])

# ---------------------------------------------------------------- 06 annotations (link, highlight, note, free text) on a text page
with pikepdf.open(f"{OUT}/01_normal.pdf") as pdf:
    p = pdf.pages[0]
    link = pdf.make_indirect(Dictionary(Type=Name.Annot, Subtype=Name.Link, Rect=[72, 700, 300, 720], Border=[0, 0, 1],
                                        A=Dictionary(S=Name.URI, URI=String("https://example.invalid/corpus-link"))))
    hi = pdf.make_indirect(Dictionary(Type=Name.Annot, Subtype=Name.Highlight, Rect=[72, 735, 330, 755], QuadPoints=[72, 755, 330, 755, 72, 735, 330, 735],
                                      C=[1, 1, 0], Contents=String("corpus highlight"), F=4))
    note = pdf.make_indirect(Dictionary(Type=Name.Annot, Subtype=Name.Text, Rect=[400, 750, 420, 770], Contents=String("corpus sticky note"), Name=Name.Note, F=4))
    ft = pdf.make_indirect(Dictionary(Type=Name.Annot, Subtype=Name.FreeText, Rect=[72, 600, 300, 640], Contents=String("corpus free text"),
                                      DA=String("/Helv 12 Tf 0 g"), F=4))
    p.Annots = Array([link, hi, note, ft])
    pdf.save(f"{OUT}/06_annotated.pdf")
record("06_annotated.pdf", pages=3, encrypted=False, annotations_page1={"Link": 1, "Highlight": 1, "Text": 1, "FreeText": 1},
       text_contains=["ALPHA-7731"])

# ---------------------------------------------------------------- 07 signed (reuse independent Phase 5 fixture)
src_signed = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "androidTest", "assets", "signature_fixtures", "valid_signed.pdf")
shutil.copyfile(src_signed, f"{OUT}/07_signed_valid.pdf")
record("07_signed_valid.pdf", pages=1, encrypted=False, signed=True, signatures=1, signature_valid=True,
       note="CMS detached, SHA-256, RSA-2048, self-signed throwaway cert (see tools/signature_fixtures)")

# ---------------------------------------------------------------- 08 rotated pages (0/90/180/270) with arrow-like asymmetric content
c = canvas.Canvas(f"{OUT}/_rot_base.pdf", pagesize=A4)
for r in (0, 90, 180, 270):
    c.setFont("Helvetica-Bold", 28); c.drawString(72, 760, f"TOP-LEFT MARKER rotate={r}")
    c.setFillColorRGB(0, 0, 0); c.rect(72, 600, 120, 120, fill=1)     # black square near top-left (user space)
    c.showPage()
c.save()
with pikepdf.open(f"{OUT}/_rot_base.pdf") as pdf:
    for page, r in zip(pdf.pages, (0, 90, 180, 270)):
        page.Rotate = r
    pdf.save(f"{OUT}/08_rotated_pages.pdf")
os.remove(f"{OUT}/_rot_base.pdf")
record("08_rotated_pages.pdf", pages=4, rotations=[0, 90, 180, 270], text_contains=["TOP-LEFT MARKER rotate=90"], encrypted=False)

# ---------------------------------------------------------------- 09 mixed page sizes + cropbox offset
c = canvas.Canvas(f"{OUT}/_mix_base.pdf")
sizes = [A4, letter, A5, legal, (300, 900)]
for i, s in enumerate(sizes, 1):
    c.setPageSize(s); c.setFont("Helvetica", 18); c.drawString(30, s[1] - 50, f"MIXED-SIZE page {i}: {int(s[0])}x{int(s[1])}"); c.showPage()
c.save()
with pikepdf.open(f"{OUT}/_mix_base.pdf") as pdf:
    pdf.pages[1].CropBox = [20, 20, 592, 772]       # non-zero-origin CropBox on the letter page
    pdf.save(f"{OUT}/09_mixed_sizes.pdf")
os.remove(f"{OUT}/_mix_base.pdf")
record("09_mixed_sizes.pdf", pages=5, mediabox_sizes=[[round(s[0]), round(s[1])] for s in sizes], cropbox_page2=[20, 20, 592, 772],
       text_contains=["MIXED-SIZE page 1", "MIXED-SIZE page 5"], encrypted=False)

# ---------------------------------------------------------------- 10-12 Unicode
TAMIL = ["யாதும் ஊரே யாவரும் கேளிர்", "தமிழ் மொழி"]          # classical Tamil line (Purananuru 192, public domain) + "Tamil language"
ARABIC = ["مرحبا بالعالم", "اللغة العربية"]                   # "Hello world", "The Arabic language"
CJK = ["你好，世界", "こんにちは世界", "안녕하세요 세계"]
text_pdf(f"{OUT}/10_unicode_tamil.pdf", [["Tamil sample (unshaped, extraction test)"] + TAMIL], font="Tamil", fsize=18)
text_pdf(f"{OUT}/11_unicode_arabic.pdf", [["Arabic sample (unshaped, extraction test)"] + ARABIC], font="Arabic", fsize=18)
c = canvas.Canvas(f"{OUT}/12_unicode_cjk.pdf", pagesize=A4)
for font, line, y in (("STSong-Light", CJK[0], 760), ("HeiseiMin-W3", CJK[1], 720), ("HYSMyeongJo-Medium", CJK[2], 680)):
    c.setFont(font, 18); c.drawString(72, y, line)
c.save()
record("10_unicode_tamil.pdf", pages=1, text_contains=TAMIL, unicode_script="Tamil", shaped=False, encrypted=False, fonts_embedded=True)
record("11_unicode_arabic.pdf", pages=1, text_contains=ARABIC, unicode_script="Arabic", shaped=False, encrypted=False, fonts_embedded=True,
       note="extraction order of unshaped RTL text is not guaranteed; compare as a set of code points")
record("12_unicode_cjk.pdf", pages=1, text_contains=CJK, unicode_script="CJK", shaped=True, encrypted=False, fonts_embedded=False,
       note="non-embedded predefined CID fonts; extraction relies on the viewer/extractor CMap support")

# ---------------------------------------------------------------- 13 embedded fonts (explicit non-subset-name check handled by verify script)
text_pdf(f"{OUT}/13_embedded_fonts_mixed.pdf", [["Latin Helvetica line (standard-14, not embedded)"] + [""]], font="Helvetica", fsize=14)
c = canvas.Canvas(f"{OUT}/13_embedded_fonts_mixed.pdf", pagesize=A4)
c.setFont("Helvetica", 14); c.drawString(72, 760, "Standard-14 Helvetica (not embedded) EMB-LATIN")
c.setFont("Arabic", 14);   c.drawString(72, 730, "Embedded DejaVu EMB-DEJAVU")
c.setFont("Tamil", 14);    c.drawString(72, 700, "Embedded FreeSans EMB-FREESANS")
c.save()
record("13_embedded_fonts_mixed.pdf", pages=1, text_contains=["EMB-LATIN", "EMB-DEJAVU", "EMB-FREESANS"], encrypted=False,
       fonts_embedded_min=2, fonts_not_embedded_min=1)

# ---------------------------------------------------------------- 14 large image (decode-memory stress: 4000x5000 px)
w, h = 4000, 5000
big = Image.new("RGB", (w, h))
px = big.load()
d = ImageDraw.Draw(big)
for y in range(0, h, 4):                                   # smooth gradient (compresses well) ...
    d.line([(0, y), (w, y)], fill=(y * 255 // h, 255 - y * 255 // h, 128))
for _ in range(400):                                       # ... plus shapes so it is not trivially flat
    x0, y0 = random.randrange(w - 200), random.randrange(h - 200)
    d.ellipse([x0, y0, x0 + random.randrange(40, 200), y0 + random.randrange(40, 200)], outline=(random.randrange(256),) * 3, width=3)
buf = io.BytesIO(); big.save(buf, "JPEG", quality=70); buf.seek(0)
c = canvas.Canvas(f"{OUT}/14_large_image.pdf", pagesize=A4)
c.drawImage(ImageReader(buf), 0, 0, width=A4[0], height=A4[1]); c.setFont("Helvetica", 10); c.drawString(20, 20, "LARGE-IMAGE-PAGE"); c.showPage(); c.save()
record("14_large_image.pdf", pages=1, image_px=[w, h], decode_rgba_bytes=w * h * 4, text_contains=["LARGE-IMAGE-PAGE"], encrypted=False)

# ---------------------------------------------------------------- 15 malformed (each must fail to open/parse WITHOUT crashing the app)
good = open(f"{OUT}/01_normal.pdf", "rb").read()
open(f"{OUT}/15_malformed_truncated.pdf", "wb").write(good[: len(good) // 2])
open(f"{OUT}/15_malformed_zero_bytes.pdf", "wb").write(b"")
open(f"{OUT}/15_malformed_garbage_after_header.pdf", "wb").write(b"%PDF-1.7\n" + bytes(random.randrange(256) for _ in range(4096)))
bad = bytearray(good); i = bad.rfind(b"startxref"); bad[i + 10:i + 14] = b"9999"
open(f"{OUT}/15_malformed_bad_startxref.pdf", "wb").write(bytes(bad))
open(f"{OUT}/15_malformed_not_a_pdf.pdf", "wb").write(b"This is plain text renamed to .pdf\n")
for n, expect in (("15_malformed_truncated.pdf", "recoverable_or_fail"), ("15_malformed_zero_bytes.pdf", "fail"),
                  ("15_malformed_garbage_after_header.pdf", "fail"), ("15_malformed_bad_startxref.pdf", "recoverable_or_fail"),
                  ("15_malformed_not_a_pdf.pdf", "fail")):
    record(n, malformed=True, expected_open=expect, encrypted=False)

with open(f"{OUT}/MANIFEST.json", "w", encoding="utf-8") as fh:
    json.dump({"generator": "tools/corpus/make_corpus.py", "usb_note": "test-only passwords are public", "files": manifest}, fh, ensure_ascii=False, indent=2, sort_keys=True)
print("corpus files:", len(manifest), "total bytes:", sum(v["bytes"] for v in manifest.values()))
