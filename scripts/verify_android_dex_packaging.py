#!/usr/bin/env python3
"""Inspect and verify release APK DEX packaging without preselecting a mode."""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path


DEX_NAME = re.compile(r"^classes(?:[2-9]|[1-9][0-9]+)?\.dex$")
COMPRESSION_METHODS = {
    zipfile.ZIP_STORED: "stored",
    zipfile.ZIP_DEFLATED: "deflated",
}


def inspect_apk(apk_path: Path) -> dict[str, object]:
    with zipfile.ZipFile(apk_path) as apk:
        dex_entries = sorted(
            (entry for entry in apk.infolist() if DEX_NAME.fullmatch(entry.filename)),
            key=lambda entry: entry.filename,
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
