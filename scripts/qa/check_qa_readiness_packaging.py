#!/usr/bin/env python3
"""Packaged-content check for the release pre-login readiness receiver.

Inspects BUILT artifacts (APK / AAB zip entries: every *.dex and every AndroidManifest.xml,
binary-XML or protobuf), not sources or merged manifests:

  --expect absent   (release / play APK + AAB): no dex or manifest may contain the receiver's
                    class name, its type descriptor or its log marker. An artifact with no dex or no
                    manifest cannot be inspected and FAILS (fail closed).
  --expect present  (qaRelease APK): the class descriptor is in a dex, the manifest names the
                    receiver, and (with --aapt) the receiver is android:enabled=false,
                    android:exported=true and android:permission=android.permission.DUMP.

Byte search covers UTF-8 and UTF-16LE (binary-XML string pools may be either). This proves what
these artifacts contain; it is NOT acceptance of the raw store artifact a store later serves.

Usage: check_qa_readiness_packaging.py --expect absent|present [--aapt PATH] FILE...
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import zipfile

CLASS = "build.raft.app.core.data.auth.QaReleaseReadinessReceiver"
DESCRIPTOR = "Lbuild/raft/app/core/data/auth/QaReleaseReadinessReceiver;"
MARKERS = ("QaReleaseReadinessReceiver", "RAFT_QA_READINESS")


def _contains(data: bytes, text: str) -> bool:
    return text.encode("utf-8") in data or text.encode("utf-16-le") in data


def inspect(path: str) -> dict:
    dex_hits, manifest_hits, dex_count, manifest_count = set(), set(), 0, 0
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            base = name.rsplit("/", 1)[-1]
            if base.endswith(".dex"):
                dex_count += 1
                data = z.read(name)
                dex_hits.update(m for m in (*MARKERS, DESCRIPTOR) if _contains(data, m))
            elif base == "AndroidManifest.xml":
                manifest_count += 1
                data = z.read(name)
                manifest_hits.update(m for m in (*MARKERS, CLASS) if _contains(data, m))
    return {"dex_count": dex_count, "manifest_count": manifest_count, "dex_hits": dex_hits, "manifest_hits": manifest_hits}


def receiver_attributes(xmltree: str) -> dict | None:
    """The receiver's attributes from `aapt dump xmltree <apk> AndroidManifest.xml`, or None."""
    blocks = re.split(r"\n\s*E: ", xmltree)
    for block in blocks:
        if not block.startswith("receiver") or CLASS not in block:
            continue
        attrs = {}
        for key in ("enabled", "exported"):
            m = re.search(rf"A: android:{key}\([^)]*\)=\(type 0x12\)(0x[0-9a-f]+)", block)
            attrs[key] = None if not m else (m.group(1) != "0x0")
        m = re.search(r'A: android:permission\([^)]*\)="([^"]*)"', block)
        attrs["permission"] = m.group(1) if m else None
        return attrs
    return None


def check(path: str, expect: str, aapt: str | None) -> list[str]:
    try:
        found = inspect(path)
    except (OSError, zipfile.BadZipFile) as error:
        return [f"{path}: cannot read ({error.__class__.__name__})"]
    problems = []
    if found["dex_count"] == 0 or found["manifest_count"] == 0:
        problems.append(f"{path}: no dex or no manifest to inspect")
    if expect == "absent":
        if found["dex_hits"] or found["manifest_hits"]:
            problems.append(f"{path}: store artifact contains the readiness receiver ({sorted(found['dex_hits'] | found['manifest_hits'])})")
        return problems
    if DESCRIPTOR not in found["dex_hits"]:
        problems.append(f"{path}: receiver class not in any dex")
    if CLASS not in found["manifest_hits"]:
        problems.append(f"{path}: manifest does not declare the receiver")
    if aapt and path.endswith(".apk"):
        r = subprocess.run([aapt, "dump", "xmltree", path, "AndroidManifest.xml"], capture_output=True, text=True)
        attrs = receiver_attributes(r.stdout) if r.returncode == 0 else None
        if attrs is None:
            problems.append(f"{path}: receiver attributes unreadable")
        elif attrs != {"enabled": False, "exported": True, "permission": "android.permission.DUMP"}:
            problems.append(f"{path}: receiver must be enabled=false, exported=true, DUMP-protected; got {attrs}")
    return problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expect", choices=("absent", "present"), required=True)
    parser.add_argument("--aapt")
    parser.add_argument("files", nargs="+")
    args = parser.parse_args(argv)
    problems = [p for f in args.files for p in check(f, args.expect, args.aapt)]
    for p in problems:
        print(f"FAIL {p}")
    if not problems:
        print(f"OK readiness receiver {args.expect} in {len(args.files)} artifact(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
