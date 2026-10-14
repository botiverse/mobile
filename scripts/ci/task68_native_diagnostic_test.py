#!/usr/bin/env python3
"""Lightweight fixed-input checks; optionally replay existing artifact preparation.

  python3 scripts/ci/task68_native_diagnostic_test.py
  python3 scripts/ci/task68_native_diagnostic_test.py --original-dir DOWNLOADED_ARTIFACT

Tests never sign, use keys, run devices, invoke a build, or claim that synthetic
public-output fixtures constitute real signature validation.
"""

import argparse
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

import task68_native_diagnostic as diagnostic


ORIGINAL_DIR = None
FIXTURE = Path(__file__).resolve().parent / "fixtures/task68-native-probe-inputs.zip"


def small_hap(entries=None, version=11301900):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("module.json", json.dumps({"app": {
            "bundleName": "build.raft.mobile", "versionCode": version,
            "versionName": "1.13.1-diag-113bb09c"}}))
        for name, data in (entries or {"payload": b"frozen"}).items():
            archive.writestr(name, data)
    return stream.getvalue()


def public_outputs(directory):
    for role in ("original", "control", "probe"):
        (directory / f"{role}.cer").write_bytes(b"TEST PUBLIC CERTIFICATE; NOT A REAL VERIFY RESULT")
        (directory / f"{role}.p7b").write_bytes(b"TEST PUBLIC PROFILE; NOT A REAL VERIFY RESULT")


class ContractTests(unittest.TestCase):
    def test_fixed_archive_libraries_and_tampering(self):
        libraries = diagnostic.input_libraries(FIXTURE)
        self.assertEqual({k: diagnostic.sha(v) for k, v in libraries.items()}, diagnostic.LIBRARY_SHA)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tampered.zip"
            data = bytearray(FIXTURE.read_bytes())
            data[len(data) // 2] ^= 1
            path.write_bytes(data)
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                diagnostic.input_libraries(path)

    def test_zip_traversal_rejected_before_reading(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("../outside", b"do not extract")
        with zipfile.ZipFile(data) as archive:
            with self.assertRaisesRegex(ValueError, "unsafe ZIP member"):
                diagnostic.safe_entries(archive)

    def test_signing_pages_allowed_but_unrelated_payload_changes_rejected(self):
        original = diagnostic.hap_payload(small_hap())
        signed_shape = diagnostic.hap_payload(small_hap({"payload": b"frozen", ".pages.info": b"generated"}))
        diagnostic.compare_signed_payload(original, signed_shape)
        tampered = diagnostic.hap_payload(small_hap({"payload": b"changed", ".pages.info": b"generated"}))
        with self.assertRaisesRegex(ValueError, "payload changed"):
            diagnostic.compare_signed_payload(original, tampered)
        unexpected = diagnostic.hap_payload(small_hap({"payload": b"frozen", "unexpected": b"new"}))
        with self.assertRaisesRegex(ValueError, "payload changed"):
            diagnostic.compare_signed_payload(original, unexpected)

    def test_version_identity_drift_rejected(self):
        with self.assertRaisesRegex(ValueError, "versionCode changed"):
            diagnostic.hap_payload(small_hap(version=11301901))

    def test_public_profile_must_match_original(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            public_outputs(root)
            diagnostic.public_identity(root)
            (root / "probe.p7b").write_bytes(b"different profile")
            with self.assertRaisesRegex(ValueError, "differs from original"):
                diagnostic.public_identity(root)


class ExistingArtifactReplay(unittest.TestCase):
    def test_prepare_and_payload_identity_roundtrip(self):
        if not ORIGINAL_DIR:
            self.skipTest("pass --original-dir to replay the existing original artifact")
        with tempfile.TemporaryDirectory(prefix="task68-replay-") as directory:
            root = Path(directory)
            work, signed, verified = [root / name for name in ("work", "signed", "verified")]
            signed.mkdir()
            verified.mkdir()
            receipt = diagnostic.prepare(ORIGINAL_DIR, work)
            self.assertEqual(receipt["entry_count"], 264)
            self.assertEqual(receipt["changed_entries"], [diagnostic.TARGET])
            for role, expected in diagnostic.UNSIGNED_SHA.items():
                self.assertEqual(receipt["outputs"][role]["sha256"], expected)
                # A payload-shape fixture, deliberately NOT a real signed HAP.
                # Official verify-app is the caller's separate CI responsibility.
                os.link(work / f"{role}-UNSIGNED.hap", signed / f"{role}.signed.hap")
            public_outputs(verified)
            checked = diagnostic.verify(ORIGINAL_DIR, work, signed, verified, root / "receipt.json")
            self.assertEqual(checked["state"], "PAYLOAD_AND_PUBLIC_SIGNER_IDENTITY_MATCH")
            self.assertEqual(checked["runtime"], "NOT_RUN")
            self.assertIn("does not validate signatures", checked["signature_validation_boundary"])
            self.assertEqual({k: v["unsigned_sha256"] for k, v in checked["outputs"].items()},
                             diagnostic.UNSIGNED_SHA)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--original-dir")
    args, remaining = parser.parse_known_args()
    ORIGINAL_DIR = args.original_dir
    unittest.main(argv=[__file__] + remaining)
