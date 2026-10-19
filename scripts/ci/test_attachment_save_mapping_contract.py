#!/usr/bin/env python3
"""Static contract: attachment save failures map to one fixed bridge status on
every native host.

Incident-born gate (task #356 / PR #2467 review): the shared download
coordinator reports right-cause errorKind values (storage_write /
storage_prepare / storage_publish for local storage failures, anything else for
download/network failures, cancelled for stops). Each native bridge must fold
those into exactly "storage_failed", "download_failed", or pass "cancelled"
through unchanged. If a host drops the cancelled pass-through or maps a
storage kind to download_failed, users see the wrong toast and the wrong
retry affordance.

The Android mapping additionally has an executable JVM regression:
  compose/androidApp/src/test/java/build/raft/app/shared/
      AttachmentDownloadFailurePayloadTest.kt
and a wrapper-level regression:
  compose/androidApp/src/test/java/build/raft/app/shared/
      AttachmentSavePreparationFailurePayloadTest.kt
This source contract locks the production mapping expressions on both hosts
so the iOS bridge (which is not JVM-unit-testable here) still fails closed
when the mapping drifts.

Usage:
  test_attachment_save_mapping_contract.py                enforce
  test_attachment_save_mapping_contract.py --self-test    synthetic mutation arms
"""

import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

FILES = {
    "android": Path("compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt"),
    "ios": Path("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m"),
}

# Each marker must appear in the named host source. The android cancelled arm
# is in the AttachmentDownloadStoppedException catch, not in the failure-payload
# helper, so it is asserted separately from the storage/download ternary.
MARKERS = {
    "android": [
        'failure.errorKind in setOf("storage_write", "storage_prepare", "storage_publish")',
        'if (storageFailure) "storage_failed" else "download_failed"',
        '"status" to "cancelled"',
        '"message" to "Download stopped"',
        'attachmentSavePreparationFailurePayload(throwable)',
        'is AttachmentDownloadStoppedException ->',
    ],
    "ios": [
        'BOOL storageFailure =',
        '[errorKind isEqualToString:@"storage_write"] ||',
        '[errorKind isEqualToString:@"storage_prepare"] ||',
        '[errorKind isEqualToString:@"storage_publish"];',
        '[result.status isEqualToString:@"cancelled"]',
        '? @"cancelled"',
        ': (storageFailure ? @"storage_failed" : @"download_failed")',
    ],
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def enforce(base: Path) -> int:
    failures = []
    for host, relative in FILES.items():
        path = base / relative
        if not path.is_file():
            failures.append((host, path, "<file missing>"))
            continue
        text = read(path)
        for marker in MARKERS[host]:
            if marker not in text:
                failures.append((host, path, marker))
    if failures:
        print("attachment save mapping contract: FAIL")
        for host, path, marker in failures:
            print(f"  {host}: {path}: missing marker {marker!r}")
        return 1
    print("attachment save mapping contract: PASS")
    return 0


def self_test() -> int:
    """Remove one marker from a synthetic host and prove the gate turns red."""
    print("attachment save mapping contract self-test: running mutation arms")
    for host, markers in MARKERS.items():
        text = read(REPO_ROOT / FILES[host])
        if not markers:
            print(f"  {host}: no markers declared; cannot mutate")
            return 1
        mutant = text.replace(markers[0], "MUTATED_MARKER", 1)
        if markers[0] in mutant:
            print(f"  {host}: marker {markers[0]!r} appeared more than once; mutation was not unique")
            return 1
        with tempfile.TemporaryDirectory() as tmp:
            tmp_root = Path(tmp)
            (tmp_root / "compose").mkdir()
            # enforce() only reads the file named by FILES[host], so materialize
            # just that one path under the synthetic root.
            target = tmp_root / FILES[host]
            target.parent.mkdir(parents=True)
            target.write_text(mutant, encoding="utf-8")
            if enforce(tmp_root) != 1:
                print(f"  {host}: removing {markers[0]!r} did not turn the gate red")
                return 1
    print("attachment save mapping contract self-test: PASS")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        raise SystemExit(self_test())
    raise SystemExit(enforce(REPO_ROOT))
