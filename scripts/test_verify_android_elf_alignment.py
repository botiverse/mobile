#!/usr/bin/env python3

from __future__ import annotations

import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from verify_android_elf_alignment import verify_archive


def elf64(
    *,
    alignment: int,
    machine: int,
    elf_class: int = 2,
    offset: int = 0,
    byte_order: int = 1,
    elf_type: int = 3,
    file_size: int = 128,
    memory_size: int = 128,
    materialize_segment: bool = True,
    truncate_to: int | None = None,
) -> bytes:
    data_size = max(256, offset + file_size) if materialize_segment else 256
    data = bytearray(data_size)
    data[:6] = b"\x7fELF" + bytes((elf_class, byte_order))
    struct.pack_into("<H", data, 16, elf_type)
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<H", data, 54, 56)
    struct.pack_into("<H", data, 56, 1)
    struct.pack_into(
        "<IIQQQQQQ",
        data,
        64,
        1,
        5,
        offset,
        0,
        0,
        file_size,
        memory_size,
        alignment,
    )
    encoded = bytes(data)
    return encoded if truncate_to is None else encoded[:truncate_to]


class VerifyAndroidElfAlignmentTest(unittest.TestCase):
    def create_archive(
        self,
        root: Path,
        *,
        alignment: int,
        compression: int = zipfile.ZIP_STORED,
        arm64_offset: int = 0,
        arm64_machine: int = 183,
        arm64_class: int = 2,
        arm64_byte_order: int = 1,
        arm64_type: int = 3,
        arm64_file_size: int = 128,
        arm64_memory_size: int = 128,
        arm64_materialize_segment: bool = True,
        arm64_truncate_to: int | None = None,
        include_x86_64: bool = True,
    ) -> Path:
        archive_path = root / "candidate.apk"
        with zipfile.ZipFile(archive_path, "w", compression=compression) as archive:
            archive.writestr(
                "lib/arm64-v8a/libfirst.so",
                elf64(
                    alignment=alignment,
                    machine=arm64_machine,
                    elf_class=arm64_class,
                    offset=arm64_offset,
                    byte_order=arm64_byte_order,
                    elf_type=arm64_type,
                    file_size=arm64_file_size,
                    memory_size=arm64_memory_size,
                    materialize_segment=arm64_materialize_segment,
                    truncate_to=arm64_truncate_to,
                ),
            )
            archive.writestr(
                "lib/arm64-v8a/libsecond.so",
                elf64(alignment=alignment, machine=183),
            )
            if include_x86_64:
                archive.writestr(
                    "lib/x86_64/libfirst.so",
                    elf64(alignment=alignment, machine=62),
                )
        return archive_path

    def test_accepts_all_16kb_stored_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = verify_archive(
                self.create_archive(Path(directory), alignment=16 * 1024),
                required_abis=("arm64-v8a", "x86_64"),
                expected_native_method="stored",
            )
            self.assertEqual(3, len(report["libraries"]))

    def test_accepts_deflated_legacy_packaging(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = verify_archive(
                self.create_archive(
                    Path(directory),
                    alignment=16 * 1024,
                    compression=zipfile.ZIP_DEFLATED,
                ),
                expected_native_method="deflated",
            )
            methods = {
                item["compression_method"] for item in report["libraries"].values()
            }
            self.assertEqual({"deflated"}, methods)

    def test_rejects_one_4kb_library_among_valid_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive_path = self.create_archive(
                Path(directory), alignment=16 * 1024
            )
            with zipfile.ZipFile(archive_path, "a") as archive:
                archive.writestr(
                    "lib/x86_64/libbad.so",
                    elf64(alignment=4 * 1024, machine=62),
                )
            with self.assertRaisesRegex(ValueError, "libbad.so.*below 0x4000"):
                verify_archive(archive_path)

    def test_rejects_noncongruent_load_segments(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "not congruent"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_offset=4096,
                    )
                )

    def test_rejects_64kb_alignment_with_only_16kb_congruence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "p_align=0x10000"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=64 * 1024,
                        arm64_offset=16 * 1024,
                    )
                )

    def test_rejects_elfclass32_in_64bit_abi_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "ELFCLASS64, got class 1"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_class=1,
                    )
                )

    def test_rejects_wrong_machine_for_abi(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "machine 62.*expected machine 183"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_machine=62,
                    )
                )

    def test_rejects_big_endian_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "must be little-endian"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_byte_order=2,
                    )
                )

    def test_rejects_et_rel_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "must contain ET_DYN"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_type=1,
                    )
                )

    def test_rejects_segment_file_size_above_memory_size(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "exceeds memory size"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_file_size=256,
                        arm64_memory_size=128,
                    )
                )

    def test_rejects_segment_range_past_file_end(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "exceeds file bounds"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_file_size=4096,
                        arm64_memory_size=4096,
                        arm64_materialize_segment=False,
                    )
                )

    def test_rejects_zero_length_segment_offset_past_file_end(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "exceeds file bounds"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_offset=16 * 1024,
                        arm64_file_size=0,
                        arm64_memory_size=0,
                        arm64_materialize_segment=False,
                    )
                )

    def test_rejects_truncated_library_with_intact_program_headers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "exceeds file bounds"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        arm64_file_size=8192,
                        arm64_memory_size=8192,
                        arm64_truncate_to=1024,
                    )
                )

    def test_rejects_non_power_of_two_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "not a power of two"):
                verify_archive(
                    self.create_archive(Path(directory), alignment=24 * 1024)
                )

    def test_rejects_zero_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "not a power of two"):
                verify_archive(
                    self.create_archive(Path(directory), alignment=0)
                )

    def test_requires_requested_abis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "missing required ABIs: x86_64"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        include_x86_64=False,
                    ),
                    required_abis=("arm64-v8a", "x86_64"),
                )

    def test_rejects_wrong_native_compression_method(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "must be stored, got deflated"):
                verify_archive(
                    self.create_archive(
                        Path(directory),
                        alignment=16 * 1024,
                        compression=zipfile.ZIP_DEFLATED,
                    ),
                    expected_native_method="stored",
                )

    def test_rejects_duplicate_library_member(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive_path = self.create_archive(
                Path(directory), alignment=16 * 1024
            )
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(archive_path, "a") as archive:
                    archive.writestr(
                        "lib/arm64-v8a/libfirst.so",
                        elf64(alignment=16 * 1024, machine=183),
                    )
            with self.assertRaisesRegex(ValueError, "duplicate member"):
                verify_archive(archive_path)

    def test_rejects_duplicate_non_library_member(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive_path = self.create_archive(
                Path(directory), alignment=16 * 1024
            )
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(archive_path, "a") as archive:
                    archive.writestr("assets/repeated.txt", b"first")
                    archive.writestr("assets/repeated.txt", b"second")
            with self.assertRaisesRegex(
                ValueError, "duplicate members: assets/repeated.txt"
            ):
                verify_archive(archive_path)

    def test_rejects_malformed_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "candidate.apk"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("lib/arm64-v8a/libbroken.so", b"not-elf")
            with self.assertRaisesRegex(ValueError, "libbroken.so: not an ELF"):
                verify_archive(archive_path)


if __name__ == "__main__":
    unittest.main()
