import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "scan_itext_in_apk.py")


def make_dex(payload=b"Lcom/example/Clean;\x00", version=b"035"):
    body_len = 0x70 - 12 + len(payload)
    size = 12 + body_len
    hdr = bytearray(0x70)
    hdr[0:8] = b"dex\n" + version + b"\x00"
    struct.pack_into("<I", hdr, 32, size)
    struct.pack_into("<I", hdr, 36, 0x70)
    struct.pack_into("<I", hdr, 40, 0x12345678)
    data = bytes(hdr) + payload
    struct.pack_into("<I", (b := bytearray(data)), 8, zlib.adler32(bytes(b[12:])) & 0xFFFFFFFF)
    return bytes(b)


def make_apk(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)


class ScannerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def p(self, name):
        return os.path.join(self.tmp.name, name)

    def run_scan(self, apk, *extra):
        r = subprocess.run([sys.executable, SCRIPT, apk, *extra], capture_output=True, text=True)
        return r.returncode, r.stdout

    def test_clean_apk_passes(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex(), "AndroidManifest.xml": b"x"})
        code, out = self.run_scan(self.p("a.apk"))
        self.assertEqual(code, 0, out)
        self.assertIn("CLEAN", out)

    def test_itext_found_fails_and_reports_descriptor(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex(b"Lcom/itextpdf/kernel/pdf/PdfDocument;\x00")})
        code, out = self.run_scan(self.p("a.apk"))
        self.assertEqual(code, 1)
        self.assertIn("Lcom/itextpdf/kernel/pdf/PdfDocument", out)

    def test_itext_in_secondary_dex_fails(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex(), "classes2.dex": make_dex(b"Lcom/itextpdf/io/X;\x00")})
        code, out = self.run_scan(self.p("a.apk"))
        self.assertEqual(code, 1)
        self.assertIn("classes2.dex", out)

    def test_multiple_clean_dex_files_pass_and_all_are_scanned(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex(), "classes2.dex": make_dex(), "classes3.dex": make_dex()})
        code, out = self.run_scan(self.p("a.apk"))
        self.assertEqual(code, 0)
        self.assertIn("dex files scanned: 3", out)

    def test_apk_without_dex_fails(self):
        make_apk(self.p("a.apk"), {"AndroidManifest.xml": b"x"})
        code, out = self.run_scan(self.p("a.apk"))
        self.assertEqual(code, 2)
        self.assertIn("SCAN INCOMPLETE", out)

    def test_corrupt_apk_fails(self):
        with open(self.p("a.apk"), "wb") as f:
            f.write(b"this is not a zip file at all")
        self.assertEqual(self.run_scan(self.p("a.apk"))[0], 2)

    def test_truncated_apk_fails(self):
        make_apk(self.p("full.apk"), {"classes.dex": make_dex()})
        data = open(self.p("full.apk"), "rb").read()
        with open(self.p("a.apk"), "wb") as f:
            f.write(data[: len(data) // 2])
        self.assertEqual(self.run_scan(self.p("a.apk"))[0], 2)

    def test_corrupt_dex_content_fails(self):
        bad = bytearray(make_dex())
        bad[200 if len(bad) > 200 else -1] ^= 0xFF  # checksum no longer matches
        make_apk(self.p("a.apk"), {"classes.dex": bytes(bad)})
        code, out = self.run_scan(self.p("a.apk"))
        self.assertEqual(code, 2)
        self.assertIn("corrupt", out)

    def test_dex_with_bad_magic_fails(self):
        make_apk(self.p("a.apk"), {"classes.dex": b"NOTADEX!" + b"\x00" * 200})
        self.assertEqual(self.run_scan(self.p("a.apk"))[0], 2)

    def test_corrupt_zip_entry_crc_fails(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex(b"Lcom/example/Pad;\x00" * 50)})
        # store uncompressed so flipping a data byte breaks the CRC rather than inflate
        with zipfile.ZipFile(self.p("b.apk"), "w", zipfile.ZIP_STORED) as z:
            z.writestr("classes.dex", make_dex(b"Lcom/example/Pad;\x00" * 50))
        raw = bytearray(open(self.p("b.apk"), "rb").read())
        raw[120] ^= 0xFF
        open(self.p("b.apk"), "wb").write(bytes(raw))
        self.assertEqual(self.run_scan(self.p("b.apk"))[0], 2)

    def test_empty_dex_entry_fails(self):
        make_apk(self.p("a.apk"), {"classes.dex": b""})
        code, out = self.run_scan(self.p("a.apk"))
        self.assertEqual(code, 2)
        self.assertIn("empty DEX", out)

    def test_missing_apk_fails(self):
        code, out = self.run_scan(self.p("missing.apk"))
        self.assertEqual(code, 2)
        self.assertIn("not found", out)

    def test_itext_resource_entry_fails(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex(), "com/itextpdf/font/x.afm": b"x"})
        self.assertEqual(self.run_scan(self.p("a.apk"))[0], 1)

    def test_mapping_with_itext_fails_even_if_dex_is_clean(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex()})
        open(self.p("mapping.txt"), "w").write("com.itextpdf.kernel.Foo -> a.a:\n    int x -> a\n")
        code, out = self.run_scan(self.p("a.apk"), "--mapping", self.p("mapping.txt"))
        self.assertEqual(code, 1)
        self.assertIn("com.itextpdf.kernel.Foo", out)

    def test_clean_mapping_passes_and_missing_or_empty_mapping_fails(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex()})
        open(self.p("mapping.txt"), "w").write("com.example.Foo -> a.a:\n")
        self.assertEqual(self.run_scan(self.p("a.apk"), "--mapping", self.p("mapping.txt"))[0], 0)
        self.assertEqual(self.run_scan(self.p("a.apk"), "--mapping", self.p("nope.txt"))[0], 2)
        open(self.p("empty.txt"), "w").write("")
        self.assertEqual(self.run_scan(self.p("a.apk"), "--mapping", self.p("empty.txt"))[0], 2)

    def test_report_files_are_written(self):
        make_apk(self.p("a.apk"), {"classes.dex": make_dex()})
        code, _ = self.run_scan(self.p("a.apk"), "--output", self.p("r.txt"), "--json", self.p("r.json"))
        self.assertEqual(code, 0)
        self.assertIn("RESULT: CLEAN", open(self.p("r.txt")).read())
        self.assertTrue(os.path.getsize(self.p("r.json")) > 0)


if __name__ == "__main__":
    unittest.main()
