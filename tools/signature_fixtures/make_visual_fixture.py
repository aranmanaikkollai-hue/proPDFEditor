#!/usr/bin/env python3
"""visual_signature.pdf: unsigned.pdf + a drawn-looking image stamp. A VISUAL signature only: no /Sig, no ByteRange, no CMS."""
import sys, os, io, pikepdf
from pikepdf import Pdf, Name, Dictionary, Stream
from PIL import Image, ImageDraw
d = sys.argv[1]
img = Image.new("RGB", (240, 80), "white"); dr = ImageDraw.Draw(img)
dr.line([(10, 60), (60, 15), (110, 65), (170, 20), (230, 55)], fill=(10, 10, 120), width=4)
pdf = Pdf.open(os.path.join(d, "unsigned.pdf"))
page = pdf.pages[0]
xobj = Stream(pdf, img.tobytes()); xobj.stream_dict = Dictionary(Type=Name.XObject, Subtype=Name.Image, Width=240, Height=80,
    ColorSpace=Name.DeviceRGB, BitsPerComponent=8)
xobj = pdf.make_stream(img.tobytes(), Type=Name.XObject, Subtype=Name.Image, Width=240, Height=80, ColorSpace=Name.DeviceRGB, BitsPerComponent=8)
page.Resources.XObject = Dictionary(Sig1=xobj)
page.contents_add(pdf.make_stream(b"q 180 0 0 60 72 600 cm /Sig1 Do Q"), prepend=False)
pdf.save(os.path.join(d, "visual_signature.pdf"))
