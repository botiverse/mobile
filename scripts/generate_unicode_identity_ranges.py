#!/usr/bin/env python3
"""Regenerate or verify Mobile's canonical Unicode identity ranges."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
import urllib.request
from pathlib import Path


UNICODE_VERSION = "17.0"
SERVER_NODE_VERSION = "24.15.0"
UNICODE_URL = (
    "https://www.unicode.org/Public/17.0.0/ucd/extracted/"
    "DerivedGeneralCategory.txt"
)
UNICODE_SHA256 = "d62e5bab70ca74f099343f71224fa051cb1fdd61a1ab45c0488c44cfc0b6102e"
SOURCE_PATH = Path(
    "compose/shared/src/commonMain/kotlin/build/raft/app/UnicodeIdentityHandle.kt"
)
BEGIN_MARKER = "// BEGIN GENERATED UNICODE IDENTITY RANGES"
END_MARKER = "// END GENERATED UNICODE IDENTITY RANGES"
UCD_LINE = re.compile(
    r"^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*([A-Z][a-z])"
)


def load_ucd(path: Path | None) -> bytes:
    if path is not None:
        payload = path.read_bytes()
    else:
        with urllib.request.urlopen(UNICODE_URL, timeout=30) as response:
            payload = response.read()
    digest = hashlib.sha256(payload).hexdigest()
    if digest != UNICODE_SHA256:
        raise SystemExit(
            f"Unicode source digest mismatch: expected {UNICODE_SHA256}, got {digest}"
        )
    return payload


def verify_server_runtime(node_version_file: Path, node_binary: Path) -> None:
    pinned_node = node_version_file.read_text(encoding="utf-8").strip().removeprefix("v")
    if pinned_node != SERVER_NODE_VERSION:
        raise SystemExit(
            f"Server .node-version drift: expected {SERVER_NODE_VERSION}, got {pinned_node}"
        )
    actual_node = (
        subprocess.check_output([node_binary, "--version"], text=True)
        .strip()
        .removeprefix("v")
    )
    if actual_node != SERVER_NODE_VERSION:
        raise SystemExit(
            f"Node runtime drift: expected {SERVER_NODE_VERSION}, got {actual_node}"
        )
    actual_unicode = subprocess.check_output(
        [node_binary, "-p", "process.versions.unicode"],
        text=True,
    ).strip()
    if actual_unicode != UNICODE_VERSION:
        raise SystemExit(
            f"Node Unicode drift: expected {UNICODE_VERSION}, got {actual_unicode}"
        )


def merged_ranges(payload: bytes, category_prefix: str) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    for line in payload.decode("utf-8").splitlines():
        match = UCD_LINE.match(line)
        if match is None or not match.group(3).startswith(category_prefix):
            continue
        start = int(match.group(1), 16)
        end = int(match.group(2) or match.group(1), 16)
        ranges.append((start, end))

    merged: list[tuple[int, int]] = []
    for start, end in sorted(ranges):
        if merged and start <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def render_constant(name: str, ranges: list[tuple[int, int]]) -> str:
    encoded = "".join(f"{start:06X}{end:06X}" for start, end in ranges)
    chunks = [encoded[index : index + 120] for index in range(0, len(encoded), 120)]
    lines = [f"private const val {name} ="]
    for index, chunk in enumerate(chunks):
        indent = "    " if index == 0 else "        "
        suffix = " +" if index + 1 < len(chunks) else ""
        lines.append(f'{indent}"{chunk}"{suffix}')
    return "\n".join(lines)


def render_generated_block(payload: bytes) -> str:
    letters = merged_ranges(payload, "L")
    numbers = merged_ranges(payload, "N")
    return "\n".join(
        (
            BEGIN_MARKER,
            render_constant("AUTH_UNICODE_LETTER_RANGES", letters),
            "",
            render_constant("AUTH_UNICODE_NUMBER_RANGES", numbers),
            END_MARKER,
        )
    )


def replace_generated_block(source: str, generated: str) -> str:
    pattern = re.compile(
        re.escape(BEGIN_MARKER) + r".*?" + re.escape(END_MARKER),
        re.DOTALL,
    )
    updated, count = pattern.subn(generated, source)
    if count != 1:
        raise SystemExit(f"Expected exactly one generated range block, found {count}")
    return updated


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if source is stale")
    parser.add_argument("--ucd-file", type=Path, help="use a local pinned UCD file")
    parser.add_argument(
        "--server-node-version-file",
        type=Path,
        required=True,
        help="canonical slock .node-version",
    )
    parser.add_argument(
        "--node-binary",
        type=Path,
        required=True,
        help="Node binary resolved from the canonical .node-version",
    )
    args = parser.parse_args()

    verify_server_runtime(args.server_node_version_file, args.node_binary)
    payload = load_ucd(args.ucd_file)
    source = SOURCE_PATH.read_text(encoding="utf-8")
    if f"Unicode {UNICODE_VERSION} DerivedGeneralCategory.txt" not in source:
        raise SystemExit("Kotlin source Unicode version header is stale")
    if UNICODE_SHA256 not in source:
        raise SystemExit("Kotlin source Unicode digest header is stale")

    expected = replace_generated_block(source, render_generated_block(payload))
    if args.check:
        if expected != source:
            raise SystemExit(
                "Unicode identity ranges are stale; run "
                "python3 scripts/generate_unicode_identity_ranges.py"
            )
        print(f"Unicode identity ranges PASS: {UNICODE_VERSION} {UNICODE_SHA256}")
        return 0

    SOURCE_PATH.write_text(expected, encoding="utf-8")
    print(f"Updated {SOURCE_PATH} from Unicode {UNICODE_VERSION}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
