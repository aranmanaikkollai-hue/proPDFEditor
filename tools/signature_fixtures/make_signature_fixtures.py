#!/usr/bin/env python3
"""
Generates signature test fixtures WITHOUT iText or PDFBox (independent of the code under test).

Outputs (to the directory given as argv[1]):
  unsigned.pdf            one-page PDF, no signature
  valid_signed.pdf        one PKCS#7/CMS detached signature (SHA-256, RSA-2048), ByteRange covers whole file
  tampered_signed.pdf     valid_signed.pdf with one signed content byte changed (same length)
  appended_after_sign.pdf valid_signed.pdf plus a trailing incremental-style append (outside ByteRange)
  certificate.pem         PUBLIC certificate of the throwaway signer (no private key is ever written)

The signing key is generated in memory per run and discarded. No private key, password or keystore
is written to disk or committed.
"""
import sys, os, re, datetime, hashlib
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs7

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# ---- throwaway key + self-signed cert (memory only) ----
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([
    x509.NameAttribute(NameOID.COMMON_NAME, "ProPDF Test Signer"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "ProPDF Test Fixtures"),
])
now = datetime.datetime(2026, 10, 1, tzinfo=datetime.timezone.utc)
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
        .public_key(key.public_key()).serial_number(0x5051)
        .not_valid_before(now).not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=True, key_cert_sign=True,
                                     crl_sign=False, key_encipherment=False, data_encipherment=False,
                                     key_agreement=False, encipher_only=False, decipher_only=False), critical=True)
        .sign(key, hashes.SHA256()))
open(os.path.join(out, "certificate.pem"), "wb").write(cert.public_bytes(serialization.Encoding.PEM))

# ---- unsigned base PDF (hand-assembled, classic xref) ----
def build_pdf(with_sig_placeholder):
    objs = []
    objs.append(b"<< /Type /Catalog /Pages 2 0 R" + (b" /AcroForm << /Fields [5 0 R] /SigFlags 3 >>" if with_sig_placeholder else b"") + b" >>")
    objs.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    page = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >>"
    if with_sig_placeholder:
        page += b" /Annots [5 0 R]"
    page += b" >>"
    objs.append(page)
    content = b"BT /F1 24 Tf 72 700 Td (ProPDF signature fixture - signed content) Tj ET"
    objs.append(b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")
    if with_sig_placeholder:
        objs.append(b"<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1) /F 132 /Rect [0 0 0 0] /P 3 0 R /V 6 0 R >>")
        objs.append(b"@@SIGDICT@@")
    buf = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(buf))
        buf += b"%d 0 obj\n" % i + o + b"\nendobj\n"
    xref_pos = len(buf)
    buf += b"xref\n0 %d\n" % (len(objs) + 1) + b"0000000000 65535 f \n"
    for off in offs:
        buf += b"%010d 00000 n \n" % off
    buf += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref_pos)
    return bytes(buf)

open(os.path.join(out, "unsigned.pdf"), "wb").write(build_pdf(False))

SIG_HEX_LEN = 16384  # hex chars reserved for /Contents (8192 bytes)
base = build_pdf(True)
sigdict_tpl = (b"<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached "
               b"/ByteRange [0 0000000000 0000000000 0000000000] "
               b"/Contents <" + b"0" * SIG_HEX_LEN + b"> "
               b"/Name (ProPDF Test Signer) /Reason (Fixture) /M (D:20261001000000Z) >>")
pdf = base.replace(b"@@SIGDICT@@", sigdict_tpl)
# NOTE: replacing the placeholder changes object offsets after obj 6; obj 6 is last, xref is rebuilt below.
# Rebuild xref/trailer properly for the final byte layout.
body_end = pdf.index(b"xref\n0 ")
body = pdf[:body_end]
offs = [m.start() for m in re.finditer(rb"(?m)^\d+ 0 obj$", body)]
xref_pos = len(body)
n = len(offs) + 1
xref = b"xref\n0 %d\n" % n + b"0000000000 65535 f \n" + b"".join(b"%010d 00000 n \n" % o for o in offs)
trailer = b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (n, xref_pos)
pdf = body + xref + trailer

c_start = pdf.index(b"/Contents <") + len(b"/Contents ")
c_end = c_start + 1 + SIG_HEX_LEN + 1  # '<' + hex + '>'
br = [0, c_start, c_end, len(pdf) - c_end]
brs = b"[%d %010d %010d %010d]" % tuple(br)  # fixed width, same length as template
tpl_br = b"[0 0000000000 0000000000 0000000000]"
assert len(brs) == len(tpl_br), (len(brs), len(tpl_br))
pdf = pdf.replace(tpl_br, brs)
assert len(pdf) == br[2] + br[3]
signed_bytes = pdf[:br[1]] + pdf[br[2]:]

cms = (pkcs7.PKCS7SignatureBuilder().set_data(signed_bytes).add_signer(cert, key, hashes.SHA256())
       .sign(serialization.Encoding.DER, [pkcs7.PKCS7Options.DetachedSignature, pkcs7.PKCS7Options.Binary]))
assert len(cms) * 2 <= SIG_HEX_LEN, "CMS too large for placeholder"
hexsig = cms.hex().encode().ljust(SIG_HEX_LEN, b"0")
final = pdf[:c_start + 1] + hexsig + pdf[c_start + 1 + SIG_HEX_LEN:]
assert len(final) == len(pdf)
open(os.path.join(out, "valid_signed.pdf"), "wb").write(final)

# tampered: flip one byte inside the signed page content (same length, still a parseable PDF)
marker = b"signed content"
i = final.index(marker)
t = bytearray(final); t[i] = ord("S")  # 's' -> 'S'
open(os.path.join(out, "tampered_signed.pdf"), "wb").write(bytes(t))

# appended after signature (outside ByteRange): signature must stay valid but "covers whole document" must be false
open(os.path.join(out, "appended_after_sign.pdf"), "wb").write(final + b"\n% appended outside the signed range\n")
del key
print("fixtures written to", out, "ByteRange", br, "cms bytes", len(cms))
