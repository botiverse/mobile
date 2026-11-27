#!/usr/bin/env python3
"""Inspect and verify release APK DEX packaging without preselecting a mode."""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zipfile
from pathlib import Path


DEX_NAME = re.compile(r"^classes(?:[2-9]|[1-9][0-9]+)?\.dex$")
DEX_MAGIC_PREFIX = b"dex\n"
COMPRESSION_METHODS = {
    zipfile.ZIP_STORED: "stored",
    zipfile.ZIP_DEFLATED: "deflated",
}


def _read_uleb128(buf: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = buf[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return result, offset
        shift += 7


def _find_entry_block_undefined_registers(
    dex_name: str,
    data: bytes,
) -> list[dict[str, object]]:
    """Detect AGP/R8 high-register truncation bugs in methods with >255 registers.

    When R8 allocates >= 256 registers for a method whose parameter register index
    crosses 255 (e.g. Compose `changed: Int` in v259), a buggy 8-bit opcode such as
    `and-int/lit8` (22b) can truncate the high register index to `reg & 0xFF` (v3),
    reading an undefined register at method entry and throwing `java.lang.VerifyError`
    under ART. Walking the entry basic block catches every such method statically.
    """
    if len(data) < 112 or not data.startswith(DEX_MAGIC_PREFIX):
        return []

    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 64)
    method_ids_size, method_ids_off = struct.unpack_from("<II", data, 88)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)
    if (
        string_ids_off + string_ids_size * 4 > len(data)
        or type_ids_off + type_ids_size * 4 > len(data)
        or method_ids_off + method_ids_size * 8 > len(data)
        or class_defs_off + class_defs_size * 32 > len(data)
    ):
        return []

    def get_str(index: int) -> str:
        str_off = struct.unpack_from("<I", data, string_ids_off + index * 4)[0]
        _, pos = _read_uleb128(data, str_off)
        end = data.index(b"\x00", pos)
        return data[pos:end].decode("utf-8", "replace")

    def get_type(index: int) -> str:
        str_idx = struct.unpack_from("<I", data, type_ids_off + index * 4)[0]
        return get_str(str_idx)

    errors: list[dict[str, object]] = []
    for class_idx in range(class_defs_size):
        class_off = class_defs_off + class_idx * 32
        _, _, _, _, _, _, class_data_off, _ = struct.unpack_from(
            "<IIIIIIII", data, class_off
        )
        if class_data_off == 0:
            continue
        pos = class_data_off
        static_fields, pos = _read_uleb128(data, pos)
        instance_fields, pos = _read_uleb128(data, pos)
        direct_methods, pos = _read_uleb128(data, pos)
        virtual_methods, pos = _read_uleb128(data, pos)
        for _ in range(static_fields + instance_fields):
            _, pos = _read_uleb128(data, pos)
            _, pos = _read_uleb128(data, pos)
        for method_count in (direct_methods, virtual_methods):
            method_idx = 0
            for _ in range(method_count):
                diff, pos = _read_uleb128(data, pos)
                method_idx += diff
                _, pos = _read_uleb128(data, pos)
                code_off, pos = _read_uleb128(data, pos)
                if code_off == 0:
                    continue
                regs, ins, _, _, _, insns_size = struct.unpack_from(
                    "<HHHHII", data, code_off
                )
                if regs <= 255 or ins == 0 or insns_size == 0:
                    continue
                insns = struct.unpack_from(f"<{insns_size}H", data, code_off + 16)
                defined = set(range(regs - ins, regs))
                pc = 0
                while pc < insns_size:
                    w0 = insns[pc]
                    op = w0 & 0xFF
                    srcs: list[int] = []
                    dsts: list[int] = []
                    step = 0
                    if op == 0x00:
                        step = 1
                    elif op in (0x01, 0x07):
                        srcs = [(w0 >> 12) & 0xF]
                        dsts = [(w0 >> 8) & 0xF]
                        step = 1
                    elif op == 0x04:
                        b = (w0 >> 12) & 0xF
                        a = (w0 >> 8) & 0xF
                        srcs = [b, b + 1]
                        dsts = [a, a + 1]
                        step = 1
                    elif op in (0x02, 0x08):
                        srcs = [insns[pc + 1]]
                        dsts = [(w0 >> 8) & 0xFF]
                        step = 2
                    elif op == 0x05:
                        b = insns[pc + 1]
                        a = (w0 >> 8) & 0xFF
                        srcs = [b, b + 1]
                        dsts = [a, a + 1]
                        step = 2
                    elif op in (0x03, 0x09):
                        srcs = [insns[pc + 2]]
                        dsts = [insns[pc + 1]]
                        step = 3
                    elif op == 0x06:
                        b = insns[pc + 2]
                        a = insns[pc + 1]
                        srcs = [b, b + 1]
                        dsts = [a, a + 1]
                        step = 3
                    elif op in (0x0A, 0x0C, 0x0D):
                        dsts = [(w0 >> 8) & 0xFF]
                        step = 1
                    elif op == 0x0B:
                        a = (w0 >> 8) & 0xFF
                        dsts = [a, a + 1]
                        step = 1
                    elif op == 0x12:
                        dsts = [(w0 >> 8) & 0xF]
                        step = 1
                    elif op in (0x13, 0x15, 0x1A, 0x1C, 0x22) or (0x60 <= op <= 0x66):
                        a = (w0 >> 8) & 0xFF
                        dsts = [a, a + 1] if op == 0x61 else [a]
                        step = 2
                    elif op in (0x16, 0x19):
                        a = (w0 >> 8) & 0xFF
                        dsts = [a, a + 1]
                        step = 2
                    elif op in (0x14, 0x1B):
                        dsts = [(w0 >> 8) & 0xFF]
                        step = 3
                    elif op == 0x17:
                        a = (w0 >> 8) & 0xFF
                        dsts = [a, a + 1]
                        step = 3
                    elif op == 0x18:
                        a = (w0 >> 8) & 0xFF
                        dsts = [a, a + 1]
                        step = 5
                    elif 0x67 <= op <= 0x6D:
                        a = (w0 >> 8) & 0xFF
                        srcs = [a, a + 1] if op == 0x68 else [a]
                        step = 2
                    elif 0x52 <= op <= 0x58:
                        a = (w0 >> 8) & 0xF
                        b = (w0 >> 12) & 0xF
                        srcs = [b]
                        dsts = [a, a + 1] if op == 0x53 else [a]
                        step = 2
                    elif 0x59 <= op <= 0x5F:
                        a = (w0 >> 8) & 0xF
                        b = (w0 >> 12) & 0xF
                        srcs = [a, a + 1, b] if op == 0x5A else [a, b]
                        step = 2
                    elif op in (0x1F, 0x1D, 0x1E):
                        srcs = [(w0 >> 8) & 0xFF]
                        step = 2 if op == 0x1F else 1
                    elif op in (0x20, 0x23):
                        a = (w0 >> 8) & 0xF
                        b = (w0 >> 12) & 0xF
                        srcs = [b]
                        dsts = [a]
                        step = 2
                    elif op == 0x21:
                        a = (w0 >> 8) & 0xF
                        b = (w0 >> 12) & 0xF
                        srcs = [b]
                        dsts = [a]
                        step = 1
                    elif 0xD8 <= op <= 0xE2:
                        w1 = insns[pc + 1]
                        dsts = [(w0 >> 8) & 0xFF]
                        srcs = [w1 & 0xFF]
                        step = 2
                    elif 0xD0 <= op <= 0xD7:
                        dsts = [(w0 >> 8) & 0xF]
                        srcs = [(w0 >> 12) & 0xF]
                        step = 2
                    elif 0x6E <= op <= 0x72:
                        cnt = (w0 >> 12) & 0xF
                        w2 = insns[pc + 2]
                        rlist = [
                            w2 & 0xF,
                            (w2 >> 4) & 0xF,
                            (w2 >> 8) & 0xF,
                            (w2 >> 12) & 0xF,
                            (w0 >> 8) & 0xF,
                        ]
                        srcs = rlist[:cnt]
                        step = 3
                    elif 0x74 <= op <= 0x78:
                        cnt = (w0 >> 8) & 0xFF
                        start = insns[pc + 2]
                        srcs = list(range(start, start + cnt))
                        step = 3
                    else:
                        break
                    undefined = [reg for reg in srcs if reg not in defined]
                    if undefined:
                        cls_idx, _, name_idx = struct.unpack_from(
                            "<HHI", data, method_ids_off + method_idx * 8
                        )
                        errors.append(
                            {
                                "dex": dex_name,
                                "class": get_type(cls_idx),
                                "method": get_str(name_idx),
                                "regs": regs,
                                "ins": ins,
                                "pc": pc,
                                "opcode": f"0x{op:02x}",
                                "undefined_registers": undefined,
                            }
                        )
                        break
                    defined.update(dsts)
                    pc += step
    return errors


def inspect_apk(apk_path: Path) -> dict[str, object]:
    entry_register_errors: list[dict[str, object]] = []
    with zipfile.ZipFile(apk_path) as apk:
        dex_entries = sorted(
            (entry for entry in apk.infolist() if DEX_NAME.fullmatch(entry.filename)),
            key=lambda entry: entry.filename,
        )
        for entry in dex_entries:
            entry_register_errors.extend(
                _find_entry_block_undefined_registers(
                    entry.filename, apk.read(entry.filename)
                )
            )

    if not dex_entries:
        raise ValueError("APK contains no root classes*.dex entries")

    uncompressed_bytes = sum(entry.file_size for entry in dex_entries)
    compressed_bytes = sum(entry.compress_size for entry in dex_entries)
    if uncompressed_bytes <= 0:
        raise ValueError("APK DEX entries have zero uncompressed size")

    methods = {
        entry.filename: COMPRESSION_METHODS.get(
            entry.compress_type, f"zip-method-{entry.compress_type}"
        )
        for entry in dex_entries
    }
    return {
        "apk": str(apk_path),
        "apk_size_bytes": apk_path.stat().st_size,
        "dex_count": len(dex_entries),
        "dex_uncompressed_bytes": uncompressed_bytes,
        "dex_compressed_bytes": compressed_bytes,
        "dex_compression_ratio": compressed_bytes / uncompressed_bytes,
        "dex_entries": [entry.filename for entry in dex_entries],
        "dex_compression_methods": methods,
        "dex_entry_register_errors": entry_register_errors,
    }


def verify_report(
    report: dict[str, object],
    *,
    expected_method: str,
    max_apk_size_bytes: int | None,
    max_dex_compression_ratio: float | None,
) -> None:
    methods = report["dex_compression_methods"]
    assert isinstance(methods, dict)
    unexpected = [
        f"{name}={method}"
        for name, method in methods.items()
        if method != expected_method
    ]
    if unexpected:
        raise ValueError(
            f"DEX entries must be {expected_method}, got: {', '.join(unexpected)}"
        )

    apk_size = int(report["apk_size_bytes"])
    if max_apk_size_bytes is not None and apk_size > max_apk_size_bytes:
        raise ValueError(
            f"APK size {apk_size} exceeds release cap {max_apk_size_bytes} bytes"
        )

    ratio = float(report["dex_compression_ratio"])
    if (
        max_dex_compression_ratio is not None
        and ratio > max_dex_compression_ratio
    ):
        raise ValueError(
            "DEX compression ratio "
            f"{ratio:.4f} exceeds release cap {max_dex_compression_ratio:.4f}"
        )

    entry_errors = report.get("dex_entry_register_errors") or []
    if entry_errors:
        formatted = "; ".join(
            f"{err['dex']}:{err['class']}.{err['method']} "
            f"(regs={err['regs']}, ins={err['ins']}, pc=0x{int(err['pc']):x}, "
            f"op={err['opcode']}, undef={err['undefined_registers']})"
            for err in entry_errors
        )
        raise ValueError(
            f"DEX entry-block undefined register read (ART VerifyError risk): {formatted}"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument(
        "--expect-dex-method",
        choices=("deflated", "stored"),
        required=True,
    )
    parser.add_argument("--max-apk-size-bytes", type=int)
    parser.add_argument("--max-dex-compression-ratio", type=float)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        report = inspect_apk(args.apk)
        verify_report(
            report,
            expected_method=args.expect_dex_method,
            max_apk_size_bytes=args.max_apk_size_bytes,
            max_dex_compression_ratio=args.max_dex_compression_ratio,
        )
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Android DEX packaging verification failed: {error}", file=sys.stderr)
        return 1

    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
