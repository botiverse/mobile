#!/usr/bin/env python3
"""Support tooling for the task #141 pre-PUT consumer-resolution gate.

Two independent commands, deliberately kept out of the reviewed publication
writer so that gate work never edits publication-critical code:

``stage``
    Materialise the frozen 45-file manifest into a local Maven-layout
    directory, verifying every byte against the manifest digest. This
    simulates the post-publication repository without performing any PUT.

``ohos-test-contract``
    Guard the 1.7.3 trap. Mobile's OHOS leg builds from
    ``compose/shared/build.ohos.gradle.kts``; the non-OHOS leg declares
    ``kotlinx-coroutines-test:1.7.3``, a coordinate that is neither mirrored
    nor present on Raft. Zero occurrences in the OHOS file is exempt, but any
    occurrence must be exactly 1.8.0-KBA-002.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import urllib.request
from pathlib import Path

REQUIRED_OHOS_COROUTINES_TEST = "1.8.0-KBA-002"
FORBIDDEN_COPY_FROM_NON_OHOS = "1.7.3"
COORDINATE_RE = re.compile(
    r"org\.jetbrains\.kotlinx:kotlinx-coroutines-test(?:-[a-z0-9]+)?:([A-Za-z0-9.\-]+)"
)


class GateError(RuntimeError):
    pass


def _manifest_rows(manifest: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    columns: list[str] | None = None
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if line.startswith("# ") or not line.strip():
            continue
        fields = line.split("\t")
        if columns is None:
            columns = fields
            continue
        rows.append(dict(zip(columns, fields)))
    if not rows:
        raise GateError("manifest contains no data rows")
    return rows


def stage(manifest: Path, output: Path) -> int:
    rows = _manifest_rows(manifest)
    staged = 0
    for row in rows:
        url = row["authority"]
        target = output / row["path"]
        request = urllib.request.Request(
            url, headers={"User-Agent": "raft-task141-gate-stage"}
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            if response.status != 200:
                raise GateError(f"authority returned {response.status} for {url}")
            body = response.read()
        if len(body) != int(row["size"]):
            raise GateError(f"size mismatch staging {row['path']}")
        digest = hashlib.sha256(body).hexdigest()
        if digest != row["sha256"]:
            raise GateError(f"sha256 mismatch staging {row['path']}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
        staged += 1
    print(f"TASK141_STAGED files={staged} root={output}")
    return staged


def ohos_test_contract(build_file: Path) -> int:
    text = build_file.read_text(encoding="utf-8")
    versions = COORDINATE_RE.findall(text)
    if not versions:
        # The OHOS leg does not consume coroutines-test yet. Exempt by design:
        # this guard exists for whoever adds it first, not to force the add.
        print("TASK141_OHOS_TEST_CONTRACT_PASS occurrences=0 (exempt)")
        return 0
    wrong = [version for version in versions if version != REQUIRED_OHOS_COROUTINES_TEST]
    if wrong:
        raise GateError(
            "compose/shared/build.ohos.gradle.kts must declare "
            f"kotlinx-coroutines-test:{REQUIRED_OHOS_COROUTINES_TEST}; found "
            f"{sorted(set(wrong))}. The OHOS leg pins "
            "kotlinx-coroutines-core:1.8.0-KBA-002, so copying "
            f"{FORBIDDEN_COPY_FROM_NON_OHOS} from the non-OHOS build file "
            "requests a coordinate that is mirrored nowhere and absent from Raft."
        )
    print(f"TASK141_OHOS_TEST_CONTRACT_PASS occurrences={len(versions)}")
    return len(versions)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    stage_parser = sub.add_parser("stage")
    stage_parser.add_argument("--manifest", type=Path, required=True)
    stage_parser.add_argument("--output", type=Path, required=True)

    contract_parser = sub.add_parser("ohos-test-contract")
    contract_parser.add_argument("--build-file", type=Path, required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "stage":
            stage(args.manifest, args.output)
        else:
            ohos_test_contract(args.build_file)
    except GateError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
