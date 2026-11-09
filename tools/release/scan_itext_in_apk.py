#!/usr/bin/env python3
"""Fail-closed iText scanner for a release APK.

Exit codes:
  0  scan COMPLETED and no iText was found           (the ONLY success)
  1  scan completed and iText WAS found
  2  scan could not complete (missing/corrupt APK, no DEX, corrupt DEX, unreadable mapping, bad usage)

An extraction or read problem is never treated as a clean zero-result scan.
Standard library only.
"""
import argparse
import json
import re
import struct
import sys
import zipfile
import zlib

DEX_NAME = re.compile(r"^classes[0-9]*\.dex$")
ITEXT_DESCRIPTOR = re.compile(rb"Lcom/itextpdf/[A-Za-z0-9_$/]+")
ITEXT_ENTRY = re.compile(r"(^|/)com/itextpdf(/|$)")
MAPPING_ITEXT = re.compile(r"^com\.itextpdf\.\S+ -> ", re.M)
DEX_MAGIC = re.compile(rb"^dex\n0[0-9]{2}\x00$")
DEX_HEADER_SIZE = 0x70


class ScanError(Exception):
    """The scan could not complete. Never means 'clean'."""


def validate_dex(name, data):
    if len(data) == 0:
        raise ScanError("%s: empty DEX entry" % name)
    if len(data) < DEX_HEADER_SIZE:
        raise ScanError("%s: truncated DEX (%d bytes, header needs %d)" % (name, len(data), DEX_HEADER_SIZE))
    if not DEX_MAGIC.match(data[:8]):
        raise ScanError("%s: invalid DEX magic" % name)
    checksum, = struct.unpack_from("<I", data, 8)
    file_size, header_size = struct.unpack_from("<II", data, 32)
    if header_size != DEX_HEADER_SIZE:
        raise ScanError("%s: unexpected DEX header size %d" % (name, header_size))
    if file_size != len(data):
        raise ScanError("%s: DEX file_size %d does not match entry length %d" % (name, file_size, len(data)))
    if zlib.adler32(data[12:]) & 0xFFFFFFFF != checksum:
        raise ScanError("%s: DEX checksum mismatch (corrupt)" % name)


def scan(apk_path, mapping_path=None):
    """Returns a result dict; raises ScanError if the scan cannot complete."""
    try:
        zf = zipfile.ZipFile(apk_path, "r")
    except FileNotFoundError:
        raise ScanError("APK not found: %s" % apk_path)
    except (zipfile.BadZipFile, OSError) as e:
        raise ScanError("APK is not a readable ZIP archive (%s)" % type(e).__name__)
    with zf:
        try:
            bad = zf.testzip()  # reads and CRC-checks every entry
        except (zipfile.BadZipFile, zlib.error, OSError, EOFError, NotImplementedError) as e:
            raise ScanError("ZIP integrity check failed (%s)" % type(e).__name__)
        if bad is not None:
            raise ScanError("ZIP integrity check failed: corrupt entry %s" % bad)
        names = zf.namelist()
        dex_names = sorted(n for n in names if DEX_NAME.match(n))
        if not dex_names:
            raise ScanError("No classes*.dex entries in APK")
        matches = set()
        dex_sizes = {}
        for n in dex_names:
            try:
                data = zf.read(n)
            except (zipfile.BadZipFile, zlib.error, OSError, EOFError, NotImplementedError) as e:
                raise ScanError("%s: unreadable (%s)" % (n, type(e).__name__))
            validate_dex(n, data)
            dex_sizes[n] = len(data)
            for m in ITEXT_DESCRIPTOR.finditer(data):
                matches.add((n, m.group(0).decode("ascii")))
        entry_hits = sorted(n for n in names if ITEXT_ENTRY.search(n))
    mapping_hits = []
    if mapping_path is not None:
        try:
            with open(mapping_path, "r", encoding="utf-8", errors="strict") as fh:
                text = fh.read()
        except (OSError, UnicodeDecodeError) as e:
            raise ScanError("R8 mapping file unreadable (%s)" % type(e).__name__)
        if not text.strip():
            raise ScanError("R8 mapping file is empty")
        mapping_hits = sorted(set(m.group(0).split(" -> ")[0] for m in MAPPING_ITEXT.finditer(text)))
    return {
        "apk": apk_path,
        "dex_files_scanned": dex_names,
        "dex_sizes": dex_sizes,
        "mapping_checked": mapping_path is not None,
        "itext_dex_descriptors": sorted("%s: %s" % t for t in matches),
        "itext_apk_entries": entry_hits,
        "itext_mapping_classes": mapping_hits,
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk", help="release APK")
    ap.add_argument("--mapping", help="R8 mapping.txt (also scanned; required to detect renamed classes)")
    ap.add_argument("--output", help="write a text report here")
    ap.add_argument("--json", help="write a JSON report here")
    args = ap.parse_args(argv)
    lines = []
    status, code = "SCAN_INCOMPLETE", 2
    result = None
    try:
        result = scan(args.apk, args.mapping)
        found = result["itext_dex_descriptors"] or result["itext_apk_entries"] or result["itext_mapping_classes"]
        status, code = ("ITEXT_FOUND", 1) if found else ("CLEAN", 0)
        lines.append("APK: %s" % result["apk"])
        lines.append("dex files scanned: %d (%s)" % (len(result["dex_files_scanned"]), ", ".join(result["dex_files_scanned"])))
        lines.append("R8 mapping checked: %s" % result["mapping_checked"])
        for title, key in (("iText DEX descriptors", "itext_dex_descriptors"),
                           ("iText APK entries", "itext_apk_entries"),
                           ("iText classes in R8 mapping", "itext_mapping_classes")):
            lines.append("%s: %d" % (title, len(result[key])))
            lines.extend("  " + x for x in result[key])
    except ScanError as e:
        lines.append("SCAN INCOMPLETE: %s" % e)
    lines.append("RESULT: %s (exit %d)" % (status, code))
    text = "\n".join(lines) + "\n"
    sys.stdout.write(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"status": status, "exit_code": code, "result": result}, fh, indent=2)
    return code


if __name__ == "__main__":
    sys.exit(main())
