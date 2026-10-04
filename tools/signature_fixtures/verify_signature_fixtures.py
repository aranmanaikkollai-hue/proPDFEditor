#!/usr/bin/env python3
"""
Independent verifier for the signature fixtures (no iText, no PDFBox).
Checks: ByteRange present + well-formed + covers the file except /Contents; CMS present and parseable;
digest + signature via `openssl cms -verify` (separate implementation from the app); signer certificate info.
Usage: verify_signature_fixtures.py <dir>
Exit code 0 only if every fixture produced the EXPECTED verdict.
"""
import sys, re, os, subprocess, tempfile
from cryptography import x509

d = sys.argv[1]
cert = x509.load_pem_x509_certificate(open(os.path.join(d, "certificate.pem"), "rb").read())

def check(path):
    data = open(path, "rb").read()
    m = re.search(rb"/ByteRange\s*\[\s*(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*\]", data)
    if not m:
        return dict(byterange=False)
    a, b, c, dd = map(int, m.groups())
    res = dict(byterange=True, br=(a, b, c, dd))
    res["starts_at_zero"] = a == 0
    res["ends_at_eof_or_before"] = c + dd <= len(data)
    res["covers_whole_file"] = (c + dd == len(data))
    gap = data[b:c]
    res["gap_is_hex_string"] = gap[:1] == b"<" and gap[-1:] == b">"
    hexs = gap[1:-1].rstrip(b"0")
    if len(hexs) % 2: hexs += b"0"
    der = bytes.fromhex(hexs.decode())
    res["cms_present"] = der[:1] == b"\x30" and len(der) > 100
    signed = data[a:a + b] + data[c:c + dd]
    with tempfile.TemporaryDirectory() as t:
        open(f"{t}/sig.der", "wb").write(der); open(f"{t}/content.bin", "wb").write(signed)
        p = subprocess.run(["openssl", "cms", "-verify", "-binary", "-inform", "DER", "-in", f"{t}/sig.der",
                            "-content", f"{t}/content.bin", "-noverify", "-out", "/dev/null"],
                           capture_output=True, text=True)
        res["openssl_cms_verify"] = (p.returncode == 0)
        p2 = subprocess.run(["openssl", "cms", "-verify", "-binary", "-inform", "DER", "-in", f"{t}/sig.der",
                             "-content", f"{t}/content.bin", "-CAfile", os.path.join(d, "certificate.pem"),
                             "-purpose", "any", "-out", "/dev/null"], capture_output=True, text=True)
        res["openssl_chain_to_fixture_cert"] = (p2.returncode == 0)
    return res

expected = {
    "valid_signed.pdf":        dict(openssl_cms_verify=True,  covers_whole_file=True),
    "tampered_signed.pdf":     dict(openssl_cms_verify=False, covers_whole_file=True),
    "appended_after_sign.pdf": dict(openssl_cms_verify=True,  covers_whole_file=False),
}
ok = True
print("certificate fixture: subject=%s issuer=%s serial=%x notBefore=%s notAfter=%s sigalg=%s selfSigned=%s" % (
    cert.subject.rfc4514_string(), cert.issuer.rfc4514_string(), cert.serial_number,
    cert.not_valid_before_utc.date(), cert.not_valid_after_utc.date(), cert.signature_hash_algorithm.name,
    cert.subject == cert.issuer))
for f, exp in expected.items():
    r = check(os.path.join(d, f))
    good = all(r.get(k) == v for k, v in exp.items()) and r.get("byterange") and r.get("cms_present") and r.get("gap_is_hex_string")
    ok &= bool(good)
    print(("PASS " if good else "FAIL ") + f, r)
u = check(os.path.join(d, "unsigned.pdf"))
good = (u == dict(byterange=False)); ok &= good
print(("PASS " if good else "FAIL ") + "unsigned.pdf (no ByteRange)", u)
sys.exit(0 if ok else 1)
