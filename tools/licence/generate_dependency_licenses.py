#!/usr/bin/env python3
"""Builds DEPENDENCY_LICENSES.csv from real Gradle output and the Gradle module cache.

Inputs
  --deps FILE [FILE...]   output of `./gradlew :<module>:dependencies --configuration releaseRuntimeClasspath`
  --cache DIR             Gradle module cache, normally ~/.gradle/caches/modules-2/files-2.1
  --out FILE              CSV to write

What it does NOT do: it never guesses a licence from an artifact's name. A licence is reported only
if the artifact's POM (or a parent POM found in the same cache) declares one. Everything else is
written as "NONE DECLARED" and flagged for manual review. Declared licences that look copyleft,
proprietary or non-standard are flagged too. This is an engineering aid, not legal advice.
"""
import argparse
import csv
import os
import re
import sys
import xml.etree.ElementTree as ET

COORD = re.compile(r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::([^\s]+))?(?:\s+->\s+([^\s]+))?")
REVIEW = re.compile(r"agpl|gpl|lgpl|affero|commercial|proprietary|terms|unknown|sspl|cc-by-nc", re.I)


def parse_deps(path):
    """Yield (group, artifact, resolved_version) for every resolved external dependency line."""
    found = set()
    with open(path, encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            if "project :" in line or line.rstrip().endswith("(c)"):
                continue
            stripped = re.sub(r"^[\s|+\\\-]+", "", line).strip()
            if not stripped or stripped.startswith(("No dependencies", "releaseRuntimeClasspath")):
                continue
            stripped = re.sub(r"\s+\((\*|n)\)\s*$", "", stripped)
            m = COORD.match(stripped)
            if not m:
                continue
            group, artifact, declared, resolved = m.groups()
            version = resolved or declared
            if not version or version.startswith("{") or ":" in (version or ""):
                continue
            found.add((group, artifact, version))
    return found


def strip_ns(tag):
    return tag.split("}", 1)[1] if "}" in tag else tag


def read_pom(cache, group, artifact, version):
    base = os.path.join(cache, group, artifact, version)
    if not os.path.isdir(base):
        return None, []
    kinds = set()
    pom_path = None
    for root, _dirs, files in os.walk(base):
        for name in files:
            ext = name.rsplit(".", 1)[-1]
            if ext in ("aar", "jar", "pom", "module"):
                kinds.add(ext)
            if ext == "pom" and pom_path is None:
                pom_path = os.path.join(root, name)
    if not pom_path:
        return None, sorted(kinds)
    try:
        return ET.parse(pom_path).getroot(), sorted(kinds)
    except ET.ParseError:
        return None, sorted(kinds)


def child(node, name):
    for c in node:
        if strip_ns(c.tag) == name:
            return c
    return None


def licenses_of(root):
    lic = child(root, "licenses") if root is not None else None
    out = []
    if lic is not None:
        for l in lic:
            n, u = child(l, "name"), child(l, "url")
            out.append(((n.text or "").strip() if n is not None else "", (u.text or "").strip() if u is not None else ""))
    return out


def resolve_licenses(cache, group, artifact, version):
    root, kinds = read_pom(cache, group, artifact, version)
    source = "pom"
    for _ in range(6):
        found = licenses_of(root)
        if found:
            return found, source, kinds
        parent = child(root, "parent") if root is not None else None
        if parent is None:
            break
        g, a, v = (child(parent, k) for k in ("groupId", "artifactId", "version"))
        if g is None or a is None or v is None:
            break
        root, _ = read_pom(cache, g.text.strip(), a.text.strip(), v.text.strip())
        source = "parent-pom"
    return [], "none", kinds


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--deps", nargs="+", required=True)
    ap.add_argument("--cache", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)

    coords = set()
    for path in args.deps:
        coords |= parse_deps(path)
    if not coords:
        print("ERROR: no dependencies parsed; check the --deps files", file=sys.stderr)
        return 2

    rows = []
    for group, artifact, version in sorted(coords):
        lics, source, kinds = resolve_licenses(args.cache, group, artifact, version)
        names = "; ".join(n for n, _ in lics if n) or "NONE DECLARED"
        urls = "; ".join(u for _, u in lics if u)
        in_cache = bool(kinds)
        review = (not lics) or bool(REVIEW.search(names)) or len(lics) > 1 or not in_cache
        reason = []
        if not in_cache:
            reason.append("artifact not found in Gradle cache")
        if not lics:
            reason.append("no licence in POM or parents")
        if REVIEW.search(names):
            reason.append("copyleft/proprietary/non-standard wording")
        if len(lics) > 1:
            reason.append("multiple licences declared")
        rows.append([group, artifact, version, names, urls, source, ",".join(kinds),
                     "Y", "Y" if review else "N", "; ".join(reason)])

    with open(args.out, "w", newline="", encoding="utf-8") as handle:
        w = csv.writer(handle)
        w.writerow(["group", "artifact", "resolved_version", "pom_licenses", "pom_license_urls",
                    "license_source", "cached_artifact_types", "in_release_runtime_classpath",
                    "manual_review", "review_reason"])
        w.writerows(rows)
    flagged = sum(1 for r in rows if r[8] == "Y")
    print(f"{len(rows)} dependencies written to {args.out}; {flagged} need manual review")
    return 0


if __name__ == "__main__":
    sys.exit(main())
