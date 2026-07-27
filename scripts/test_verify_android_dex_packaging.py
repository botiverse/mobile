#!/usr/bin/env python3

from __future__ import annotations

import random
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_android_dex_packaging import inspect_apk, verify_report


class VerifyAndroidDexPackagingTest(unittest.TestCase):
    def create_apk(
        self,
        root: Path,
        *,
        compression: int,
        dex_bytes: bytes,
        filename: str = "candidate.apk",
    ) -> Path:
        apk_path = root / filename
        with zipfile.ZipFile(apk_path, "w", compression=compression) as apk:
            apk.writestr("classes.dex", dex_bytes)
            apk.writestr("classes2.dex", dex_bytes)
            apk.writestr("res/raw/payload.bin", b"payload")
        return apk_path

    def test_accepts_deflated_dex_within_caps(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = self.create_apk(
                Path(directory),
                compression=zipfile.ZIP_DEFLATED,
                dex_bytes=b"dex-content" * 4096,
            )
            report = inspect_apk(apk)
            verify_report(
                report,
                expected_method="deflated",
                max_apk_size_bytes=apk.stat().st_size,
                max_dex_compression_ratio=0.60,
            )
            self.assertEqual(2, report["dex_count"])
            self.assertEqual(
                {"classes.dex": "deflated", "classes2.dex": "deflated"},
                report["dex_compression_methods"],
            )

    def test_counts_multidex_names_above_nine(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk_path = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(
                apk_path, "w", compression=zipfile.ZIP_STORED
            ) as apk:
                apk.writestr("classes.dex", b"one")
                apk.writestr("classes10.dex", b"ten")
                apk.writestr("classes100.dex", b"hundred")
                apk.writestr("classes1.dex", b"not-a-valid-secondary-name")
            report = inspect_apk(apk_path)
            self.assertEqual(3, report["dex_count"])

    def test_accepts_stored_dex_for_mmap_comparison(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = self.create_apk(
                Path(directory),
                compression=zipfile.ZIP_STORED,
                dex_bytes=b"dex-content" * 4096,
            )
            verify_report(
                inspect_apk(apk),
                expected_method="stored",
                max_apk_size_bytes=None,
                max_dex_compression_ratio=None,
            )

    def test_rejects_wrong_dex_method(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = self.create_apk(
                Path(directory),
                compression=zipfile.ZIP_STORED,
                dex_bytes=b"dex-content" * 4096,
            )
            with self.assertRaisesRegex(ValueError, "must be deflated"):
                verify_report(
                    inspect_apk(apk),
                    expected_method="deflated",
                    max_apk_size_bytes=None,
                    max_dex_compression_ratio=None,
                )

    def test_rejects_apk_above_size_cap(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = self.create_apk(
                Path(directory),
                compression=zipfile.ZIP_DEFLATED,
                dex_bytes=b"dex-content" * 4096,
            )
            with self.assertRaisesRegex(ValueError, "exceeds release cap"):
                verify_report(
                    inspect_apk(apk),
                    expected_method="deflated",
                    max_apk_size_bytes=apk.stat().st_size - 1,
                    max_dex_compression_ratio=None,
                )

    def test_rejects_ineffective_deflate_ratio(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            random_bytes = random.Random(0).randbytes(128 * 1024)
            apk = self.create_apk(
                Path(directory),
                compression=zipfile.ZIP_DEFLATED,
                dex_bytes=random_bytes,
            )
            with self.assertRaisesRegex(ValueError, "compression ratio"):
                verify_report(
                    inspect_apk(apk),
                    expected_method="deflated",
                    max_apk_size_bytes=None,
                    max_dex_compression_ratio=0.60,
                )


if __name__ == "__main__":
    unittest.main()
