#!/usr/bin/env python3
"""Reproducible source-tree SHA-256 fingerprint.

Algorithm (identical to: find . -type f -not -path './.git/*' | LC_ALL=C sort | xargs sha256sum | sha256sum):
  for every regular file (excluding .git/), in byte-wise path order, build the line "<sha256>  ./<relpath>\\n";
  the fingerprint is the SHA-256 of all lines concatenated.
Run it right after checkout, before any build output exists. Prints fingerprint and file count.
"""
import hashlib
import os
import sys


def fingerprint(root):
    entries = []
    for dp, dn, fn in os.walk(root):
        if os.path.relpath(dp, root) == ".":
            dn[:] = [d for d in dn if d != ".git"]
        for f in fn:
            full = os.path.join(dp, f)
            if os.path.islink(full) or not os.path.isfile(full):
                continue
            entries.append("./" + os.path.relpath(full, root).replace(os.sep, "/"))
    entries.sort(key=lambda s: s.encode("utf-8"))
    outer = hashlib.sha256()
    for rel in entries:
        h = hashlib.sha256()
        with open(os.path.join(root, rel[2:]), "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        outer.update(("%s  %s\n" % (h.hexdigest(), rel)).encode("utf-8"))
    return outer.hexdigest(), len(entries)


if __name__ == "__main__":
    fp, n = fingerprint(sys.argv[1] if len(sys.argv) > 1 else ".")
    print("source_tree_sha256=%s\nfile_count=%d" % (fp, n))
