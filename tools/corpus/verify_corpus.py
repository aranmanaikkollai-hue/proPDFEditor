#!/usr/bin/env python3
"""
Independent verification of the corpus (poppler pdftotext/pdffonts/pdfsig, qpdf, pikepdf, pdfium). No PDFBox/iText.
Usage: verify_corpus.py <corpusdir> ; exit 0 only if every manifest expectation holds.
"""
import sys, os, json, subprocess, unicodedata, tempfile
import pikepdf, pypdfium2 as pdfium

d = sys.argv[1]
man = json.load(open(os.path.join(d, "MANIFEST.json"), encoding="utf-8"))["files"]
OWNER = "owner-pass-456"
rows, bad = [], 0

def run(cmd): return subprocess.run(cmd, capture_output=True, text=True)
def norm(s): return unicodedata.normalize("NFC", s)
def text_of(path, pw=None):
    cmd = ["pdftotext", "-enc", "UTF-8"] + (["-upw", pw] if pw else []) + [path, "-"]
    r = run(cmd); return norm(r.stdout)

for name, p in sorted(man.items()):
    path = os.path.join(d, name); res = []; ok = True
    def chk(label, cond):
        global ok
        res.append(("ok " if cond else "FAIL ") + label); ok &= bool(cond)
    if p.get("malformed"):
        q = run(["qpdf", "--check", path]); 
        try:
            pdoc = pdfium.PdfDocument(path); opened = True; npages = len(pdoc)
        except Exception: opened, npages = False, 0
        chk("qpdf --check reports a problem or non-PDF", q.returncode != 0)
        if p["expected_open"] == "fail": chk("pdfium refuses to open", not opened)
        else: chk("pdfium either refuses or recovers (no crash)", True)
        rows.append((name, ok, "; ".join(res))); bad += (not ok); continue

    pw = p.get("user_password") or None
    if p.get("encrypted"):
        enc = run(["qpdf", "--show-encryption"] + (["--password=" + pw] if pw else []) + [path])
        chk("encrypted per qpdf", "File is not encrypted" not in enc.stdout and enc.returncode in (0, 3))
        if pw:
            nopw = run(["qpdf", "--check", path]); chk("opening without the user password fails", nopw.returncode != 0)
    q = run(["qpdf", "--check"] + (["--password=" + (pw or OWNER)] if p.get("encrypted") else []) + [path])
    chk("qpdf --check clean", q.returncode == 0)
    with pikepdf.open(path, password=(pw or OWNER) if p.get("encrypted") else "") as pk:
        chk("page count %s" % p["pages"], len(pk.pages) == p["pages"])
        if "rotations" in p: chk("rotations %s" % p["rotations"], [int(pg.get("/Rotate", 0)) for pg in pk.pages] == p["rotations"])
        if "mediabox_sizes" in p: chk("mixed media boxes", [[round(float(pg.MediaBox[2])), round(float(pg.MediaBox[3]))] for pg in pk.pages] == p["mediabox_sizes"])
        if "cropbox_page2" in p: chk("page 2 cropbox offset", [float(x) for x in pk.pages[1].CropBox] == [float(x) for x in p["cropbox_page2"]])
        if "annotations_page1" in p:
            cnt = {}
            for a in pk.pages[0].Annots: cnt[str(a.Subtype)[1:]] = cnt.get(str(a.Subtype)[1:], 0) + 1
            chk("annotations %s" % p["annotations_page1"], cnt == p["annotations_page1"])
        if "form_fields" in p:
            names = [str(f.T) for f in pk.Root.AcroForm.Fields]; chk("form fields %s" % p["form_fields"], sorted(names) == sorted(p["form_fields"]))
        if "image_px" in p:
            im = list(pk.pages[0].images.values())[0]; chk("image %sx%s" % tuple(p["image_px"]), [int(im.Width), int(im.Height)] == p["image_px"])
    # render page 1 (pdfium, independent of PdfRenderer)
    try:
        doc = pdfium.PdfDocument(path, password=(pw or OWNER) if p.get("encrypted") else None)
        bmp = doc[0].render(scale=0.5).to_pil(); chk("pdfium renders page 1 (%dx%d)" % bmp.size, bmp.size[0] > 10)
        if p.get("has_text_layer") is False:
            chk("scanned: no extractable text", text_of(path).strip() == "")
    except Exception as e:
        chk("pdfium renders page 1 (%s)" % e, False)
    # text (poppler lacks CJK language packs here, so CJK is read with pdfium instead - recorded in the report)
    want = p.get("text_contains") or p.get("text_contains_after_unlock") or []
    if want:
        if p.get("unicode_script") == "CJK":
            t = norm(pdfium.PdfDocument(path)[0].get_textpage().get_text_range())
        else:
            cmd = ["pdftotext", "-enc", "UTF-8"] + (["-opw", OWNER] if p.get("encrypted") else []) + (["-upw", pw] if pw else []) + [path, "-"]
            t = norm(run(cmd).stdout)
        for w in want:
            if p.get("unicode_script") == "Arabic":
                chk("arabic code points present %r" % w, set(c for c in norm(w) if not c.isspace()) <= set(t))
            else:
                chk("extracts %r" % w, norm(w) in t)
    # fonts
    if "fonts_embedded_min" in p:
        fo = run(["pdffonts", path]).stdout.splitlines()[2:]
        emb = sum(1 for l in fo if " yes yes " in l or l.split()[-5:-4] == ["yes"]) 
        emb = sum(1 for l in fo if l.split()[-5] == "yes") if fo else 0
        non = sum(1 for l in fo if l.split()[-5] == "no") if fo else 0
        chk("embedded fonts >= %d (found %d)" % (p["fonts_embedded_min"], emb), emb >= p["fonts_embedded_min"])
        chk("non-embedded fonts >= %d (found %d)" % (p["fonts_not_embedded_min"], non), non >= p["fonts_not_embedded_min"])
    if p.get("unicode_script") in ("Tamil", "Arabic"):
        fo = run(["pdffonts", path]).stdout.splitlines()[2:]; chk("font embedded", any(l.split()[-5] == "yes" for l in fo))
    if p.get("signed"):
        s = run(["pdfsig", path]).stdout; chk("pdfsig: Signature is Valid", "Signature is Valid" in s)
    rows.append((name, ok, "; ".join(res))); bad += (not ok)

for n, ok, r in rows: print(("PASS " if ok else "FAIL ") + n + "\n      " + r)
print("\n%d/%d files pass independent verification" % (sum(1 for _, o, _ in rows if o), len(rows)))
sys.exit(1 if bad else 0)
