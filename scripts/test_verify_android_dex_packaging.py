#!/usr/bin/env python3

from __future__ import annotations

import random
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_android_dex_packaging import inspect_apk, verify_report


def _uleb128(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _build_minimal_dex(
    *,
    class_name: str,
    method_name: str,
    regs: int,
    ins: int,
    insns: list[int],
) -> bytes:
    """Build a minimal DEX containing one direct method with the given bytecode."""
    strings = [class_name, method_name, "V"]
    # Header is 112 bytes; place tables right after it.
    header_size = 112
    str_ids_off = header_size
    type_ids_off = str_ids_off + len(strings) * 4
    proto_ids_off = type_ids_off + 4
    method_ids_off = proto_ids_off + 12
    class_defs_off = method_ids_off + 8
    data_off = class_defs_off + 32

    data = bytearray()

    def align4() -> None:
        while (data_off + len(data)) % 4 != 0:
            data.append(0)

    str_offsets: list[int] = []
    for text in strings:
        str_offsets.append(data_off + len(data))
        raw = text.encode("utf-8")
        data.extend(_uleb128(len(raw)))
        data.extend(raw)
        data.append(0)

    align4()
    code_off = data_off + len(data)
    data.extend(struct.pack("<HHHHII", regs, ins, 0, 0, 0, len(insns)))
    for unit in insns:
        data.extend(struct.pack("<H", unit))

    class_data_off = data_off + len(data)
    # 0 static fields, 0 instance fields, 1 direct method, 0 virtual methods
    data.extend(_uleb128(0))
    data.extend(_uleb128(0))
    data.extend(_uleb128(1))
    data.extend(_uleb128(0))
    # direct method 0: method_idx_diff=0, access=static(0x8), code_off
    data.extend(_uleb128(0))
    data.extend(_uleb128(0x8))
    data.extend(_uleb128(code_off))

    header = bytearray(header_size)
    header[0:8] = b"dex\n039\x00"
    struct.pack_into("<I", header, 32, data_off + len(data))
    struct.pack_into("<I", header, 36, header_size)
    struct.pack_into("<I", header, 40, 0x12345678)
    struct.pack_into("<II", header, 56, len(strings), str_ids_off)
    struct.pack_into("<II", header, 64, 1, type_ids_off)
    struct.pack_into("<II", header, 72, 1, proto_ids_off)
    struct.pack_into("<II", header, 88, 1, method_ids_off)
    struct.pack_into("<II", header, 96, 1, class_defs_off)

    tables = bytearray()
    for off in str_offsets:
        tables.extend(struct.pack("<I", off))
    # type_id 0 -> string 0 (class_name)
    tables.extend(struct.pack("<I", 0))
    # proto_id 0 -> shorty string 2 ("V"), return type 0, params 0
    tables.extend(struct.pack("<III", 2, 0, 0))
    # method_id 0 -> class 0, proto 0, name string 1
    tables.extend(struct.pack("<HHI", 0, 0, 1))
    # class_def 0 -> class 0, class_data_off
    tables.extend(struct.pack("<IIIIIIII", 0, 1, 0xFFFFFFFF, 0, 0xFFFFFFFF, 0, class_data_off, 0))

    return bytes(header + tables + data)


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

    def test_rejects_high_register_truncation_undefined_read(self) -> None:
        # Reproduce AGP/R8 bug: regs=260, ins=25 -> param v259 (0x103) truncated
        # to v3 in `and-int/lit8 v3, v3, #3` (`03dd 0303`) before v3 is defined.
        bad_dex = _build_minimal_dex(
            class_name="Lfgp;",
            method_name="C",
            regs=260,
            ins=25,
            insns=[0x0008, 0x0102, 0x03DD, 0x0303, 0x000E],
        )
        with tempfile.TemporaryDirectory() as directory:
            apk = self.create_apk(
                Path(directory),
                compression=zipfile.ZIP_DEFLATED,
                dex_bytes=bad_dex,
            )
            report = inspect_apk(apk)
            self.assertEqual(2, len(report["dex_entry_register_errors"]))
            with self.assertRaisesRegex(ValueError, "ART VerifyError risk"):
                verify_report(
                    report,
                    expected_method="deflated",
                    max_apk_size_bytes=None,
                    max_dex_compression_ratio=None,
                )

    def test_accepts_high_register_method_when_param_moved_from16(self) -> None:
        # Clean high-register method: moves v259 into v3 via move/from16 first,
        # then runs `and-int/lit8 v3, v3, #3`.
        good_dex = _build_minimal_dex(
            class_name="Lfgp;",
            method_name="C",
            regs=260,
            ins=25,
            insns=[0x0302, 0x0103, 0x03DD, 0x0303, 0x000E],
        )
        with tempfile.TemporaryDirectory() as directory:
            apk = self.create_apk(
                Path(directory),
                compression=zipfile.ZIP_DEFLATED,
                dex_bytes=good_dex,
            )
            report = inspect_apk(apk)
            self.assertEqual([], report["dex_entry_register_errors"])
            verify_report(
                report,
                expected_method="deflated",
                max_apk_size_bytes=None,
                max_dex_compression_ratio=None,
            )


if __name__ == "__main__":
    unittest.main()
