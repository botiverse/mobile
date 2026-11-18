#!/usr/bin/env python3
"""Fake-artifact tests for check_qa_readiness_packaging.py (synthetic zips; no real APK/AAB)."""
from __future__ import annotations

import os
import pathlib
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import check_qa_readiness_packaging as pkg  # noqa: E402

XMLTREE_OK = """N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    E: application (line=10)
      E: receiver (line=40)
        A: android:name(0x01010003)="build.raft.app.core.data.auth.QaReleaseReadinessReceiver" (Raw: "build.raft.app.core.data.auth.QaReleaseReadinessReceiver")
        A: android:enabled(0x0101000e)=(type 0x12)0x0
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
      E: activity (line=50)
        A: android:name(0x01010003)="build.raft.app.MainActivity" (Raw: "build.raft.app.MainActivity")
"""


class PackagingTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp(prefix="pkg-check-"))

    def tearDown(self):
        for p in sorted(self.dir.rglob("*"), reverse=True):
            p.unlink() if p.is_file() else p.rmdir()
        self.dir.rmdir()

    def zip(self, name: str, entries: dict[str, bytes]) -> str:
        path = self.dir / name
        with zipfile.ZipFile(path, "w") as z:
            for k, v in entries.items():
                z.writestr(k, v)
        return str(path)

    def aapt(self, text: str, rc: int = 0) -> str:
        p = self.dir / "aapt"
        (self.dir / "xmltree.txt").write_text(text)
        p.write_text(f"#!/bin/sh\ncat '{self.dir / 'xmltree.txt'}'\nexit {rc}\n")
        p.chmod(0o755)
        return str(p)

    def qa_apk(self) -> str:
        return self.zip("qa.apk", {
            "classes.dex": b"dex\n035\x00 Lbuild/raft/app/core/data/auth/QaReleaseReadinessReceiver; RAFT_QA_READINESS",
            "AndroidManifest.xml": "build.raft.app.core.data.auth.QaReleaseReadinessReceiver".encode("utf-16-le"),
        })

    def test_store_artifact_without_receiver_passes(self):
        apk = self.zip("release.apk", {"classes.dex": b"dex\n035\x00 Lbuild/raft/app/MainActivity;", "AndroidManifest.xml": "build.raft.app.MainActivity".encode("utf-16-le")})
        aab = self.zip("release.aab", {"base/dex/classes.dex": b"dex Lbuild/raft/app/MainActivity;", "base/manifest/AndroidManifest.xml": b"\x0a\x08manifest build.raft.app"})
        self.assertEqual(pkg.main(["--expect", "absent", apk, aab]), 0)

    def test_store_artifact_with_receiver_in_dex_or_manifest_fails(self):
        dex = self.zip("bad1.apk", {"classes2.dex": b"x RAFT_QA_READINESS x", "AndroidManifest.xml": b"m"})
        man = self.zip("bad2.aab", {"base/dex/classes.dex": b"d", "base/manifest/AndroidManifest.xml": b"QaReleaseReadinessReceiver"})
        utf16 = self.zip("bad3.apk", {"classes.dex": b"d", "AndroidManifest.xml": "QaReleaseReadinessReceiver".encode("utf-16-le")})
        for f in (dex, man, utf16):
            self.assertEqual(pkg.main(["--expect", "absent", f]), 1, f)

    def test_uninspectable_artifact_fails_closed(self):
        self.assertEqual(pkg.main(["--expect", "absent", self.zip("empty.apk", {"res/x": b""})]), 1)
        (self.dir / "notzip.apk").write_bytes(b"not a zip")
        self.assertEqual(pkg.main(["--expect", "absent", str(self.dir / "notzip.apk")]), 1)

    def test_qa_candidate_must_carry_a_disabled_dump_protected_receiver(self):
        apk = self.qa_apk()
        self.assertEqual(pkg.main(["--expect", "present", "--aapt", self.aapt(XMLTREE_OK), apk]), 0)
        enabled = XMLTREE_OK.replace("android:enabled(0x0101000e)=(type 0x12)0x0", "android:enabled(0x0101000e)=(type 0x12)0xffffffff")
        self.assertEqual(pkg.main(["--expect", "present", "--aapt", self.aapt(enabled), apk]), 1)
        no_perm = "\n".join(l for l in XMLTREE_OK.splitlines() if "permission" not in l)
        self.assertEqual(pkg.main(["--expect", "present", "--aapt", self.aapt(no_perm), apk]), 1)
        self.assertEqual(pkg.main(["--expect", "present", "--aapt", self.aapt(XMLTREE_OK, rc=1), apk]), 1)
        missing = self.zip("qa-missing.apk", {"classes.dex": b"d", "AndroidManifest.xml": b"m"})
        self.assertEqual(pkg.main(["--expect", "present", missing]), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
