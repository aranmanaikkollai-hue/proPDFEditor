#!/usr/bin/env python3
"""
Mirrors VisualSignatureGeometry.place() in Python, stamps a solid-black image into pages with /Rotate 0/90/180/270
and a non-origin CropBox, renders with pdfium (independent of PDFBox), and checks that the black block lands where
the DISPLAY-space (top-left origin) rectangle says it should. Exit 0 only if every case passes.
"""
import math, io, sys
import pikepdf
from pikepdf import Pdf, Name, Dictionary
import pypdfium2 as pdfium

def place(box, rot, rect, imgW, imgH):
    bl, bb, br, bt = box
    bw, bh = br - bl, bt - bb
    q = rot in (90, 270)
    dispW, dispH = (bh, bw) if q else (bw, bh)
    l = max(0, min(rect[0], rect[2])); r = min(dispW, max(rect[0], rect[2]))
    t = max(0, min(rect[1], rect[3])); b = min(dispH, max(rect[1], rect[3]))
    assert r - l >= 1 and b - t >= 1
    aw, ah = r - l, b - t
    s = min(aw / imgW, ah / imgH); dw, dh = imgW * s, imgH * s
    dx = l + (aw - dw) / 2; topdown = t + (ah - dh) / 2; dyup = dispH - (topdown + dh)
    ux = {0: bl + dx, 90: bl + (bw - dyup), 180: bl + (bw - dx), 270: bl + dyup}[rot]
    uy = {0: bb + dyup, 90: bb + dx, 180: bb + (bh - dyup), 270: bb + (bh - dx)}[rot]
    c = {0: 1, 90: 0, 180: -1, 270: 0}[rot]; sn = {0: 0, 90: 1, 180: 0, 270: -1}[rot]
    return (dw * c, dw * sn, -dh * sn, dh * c, ux, uy), (dispW, dispH)

def make(rot, box, rect, imgW=40, imgH=20):
    pdf = Pdf.new()
    pdf.add_blank_page(page_size=(box[2] + box[0] if False else 400, 300))
    pg = pdf.pages[0]
    pg.MediaBox = [0, 0, 400, 300]
    pg.CropBox = list(box)
    pg.Rotate = rot
    img = pdf.make_stream(bytes([0, 0, 0]) * (imgW * imgH), Type=Name.XObject, Subtype=Name.Image, Width=imgW, Height=imgH,
                          ColorSpace=Name.DeviceRGB, BitsPerComponent=8)
    pg.Resources = Dictionary(XObject=Dictionary(Im=img))
    m, disp = place(box, rot, rect, imgW, imgH)
    pg.Contents = pdf.make_stream(("q %f %f %f %f %f %f cm /Im Do Q" % m).encode())
    bio = io.BytesIO(); pdf.save(bio)
    return bio.getvalue(), disp, m

fails = 0
for rot in (0, 90, 180, 270):
    for box in ((0, 0, 400, 300), (50, 40, 350, 260)):
        bw, bh = box[2] - box[0], box[3] - box[1]
        dispW, dispH = (bh, bw) if rot in (90, 270) else (bw, bh)
        rect = (dispW * 0.10, dispH * 0.60, dispW * 0.40, dispH * 0.90)   # lower-left-ish area, top-left origin
        data, disp, m = make(rot, box, rect)
        page = pdfium.PdfDocument(data)[0]
        bmp = page.render(scale=1.0).to_pil().convert("L")
        W, H = bmp.size
        ok_size = (round(disp[0]), round(disp[1])) == (W, H)
        # centre of the requested rectangle must be black; far opposite corner must be white
        cx, cy = (rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2
        black_centre = bmp.getpixel((int(cx), int(cy))) < 40
        white_far = bmp.getpixel((int(dispW * 0.9), int(dispH * 0.1))) > 215
        # bounding box of dark pixels must sit inside the rect (+1px tolerance) and be non-empty
        dark = bmp.point(lambda v: 255 if v < 128 else 0).getbbox()
        inside = dark and dark[0] >= rect[0] - 1.5 and dark[1] >= rect[1] - 1.5 and dark[2] <= rect[2] + 1.5 and dark[3] <= rect[3] + 1.5
        # aspect ratio 2:1 preserved
        aspect = dark and abs((dark[2] - dark[0]) / max(1, (dark[3] - dark[1])) - 2.0) < 0.15
        good = ok_size and black_centre and white_far and inside and aspect
        fails += (not good)
        print(("PASS" if good else "FAIL"), "rot=%3d box=%s render=%s dispExpected=%s darkbbox=%s rect=%s" % (
            rot, box, (W, H), tuple(round(x) for x in disp), dark, tuple(round(x, 1) for x in rect)))
sys.exit(1 if fails else 0)
