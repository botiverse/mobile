#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import zlib
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BUILDER = ROOT / "scripts/qa/build_testbox_preflight_manifest.py"
SCHEMA = ROOT / "docs/qa-acceptance/testbox-preflight/testbox-preflight-manifest.v1.schema.json"
SHA = "a" * 64
OID = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()
TREE = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD^{tree}"], text=True).strip()
WORKFLOW_BLOB = subprocess.check_output(
    ["git", "-C", str(ROOT), "rev-parse", "HEAD:.github/workflows/visual-android-testbox.yml"],
    text=True,
).strip()
CASE_ID = "components.settings.appearance.page"
METHOD = "captureSettingsAppearancePage"
SELECTOR = f"build.raft.app.visual.KmpVisualScreenshotCaptureTest#{METHOD}"
sys.path.insert(0, str(ROOT / "scripts"))
from visual_android_case_filter import enumerate_capture_tests  # noqa: E402

HARNESS_SOURCE = subprocess.check_output(
    [
        "git", "-C", str(ROOT), "show",
        "HEAD:compose/androidApp/src/androidTest/java/build/raft/app/visual/KmpVisualScreenshotCaptureTest.kt",
    ],
    text=True,
)
BODY_SHA = str(
    next(
        entry["sourceBodySha256"]
        for entry in enumerate_capture_tests(HARNESS_SOURCE)
        if entry["caseId"] == CASE_ID
    )
)
GOAL_SHA = "6f8210b1c05e7f09df898d6b38d7d3f0f98bd5b270d9f56d9975f33002f3d234"
START_EPOCH_MS = 1784592000000
FINISH_EPOCH_MS = START_EPOCH_MS + 60_000
CAPTURE_EPOCH_MS = START_EPOCH_MS + 10_000
HOST_START_MONOTONIC_NS = 9_000_000_000_000_000
HOST_FINISH_MONOTONIC_NS = HOST_START_MONOTONIC_NS + 60_000_000_000
DEVICE_START_MONOTONIC_NS = 1_000_000_000
CAPTURE_MONOTONIC_NS = 2_000_000_000
DEVICE_COLLECTION_MONOTONIC_NS = 3_000_000_000
DEVICE_FINISH_MONOTONIC_NS = 4_000_000_000
GENERATION = "b" * 32
BOOT_ID = "11111111-2222-3333-4444-555555555555"
PROCESS_ID = 1234
PROCESS_UID = 10123


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path: Path, data: str | bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, bytes):
        path.write_bytes(data)
    else:
        path.write_text(data, encoding="utf-8")


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    import binascii
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)


def write_test_png(path: Path, width: int = 1170, height: int = 2532, variant: int = 0) -> None:
    rows = []
    for y in range(height):
        first = bytes(((0x10 + variant) & 0xFF, 0x20, 0x30))
        second = bytes((0xF0, (0xD0 + variant) & 0xFF, 0x40))
        row = first * (width - 1) + (second if y == height // 2 else first)
        rows.append(b"\x00" + row)
    payload = (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
        + png_chunk(b"IEND", b"")
    )
    write(path, payload)


def case_map() -> list[dict[str, object]]:
    return [
        {
            "caseId": CASE_ID,
            "class": "build.raft.app.visual.KmpVisualScreenshotCaptureTest",
            "method": METHOD,
            "selector": SELECTOR,
            "sourceBodySha256": BODY_SHA,
            "testboxEligible": True,
            "routePageName": "compose_settings_route_visual",
            "fixtureKey": "canonical",
            "fixtureRevision": "settings-appearance-v1",
            "renderedGoalSha256": GOAL_SHA,
            "readyToken": "settings_route_visual_ready destination=appearance fixtureKey=canonical",
            "realProductRoute": True,
        }
    ]


def receipt(
    *,
    status: str,
    expected_hash: str,
    started_hash: str,
    fault: str | None,
    failure_reasons: list[str],
    missing_expected: list[str] | None = None,
    inventory: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "schema_version": "android-visual-runner-receipt.v3",
        "harness_status": status,
        "failure_phase": "final_verdict",
        "failure_reasons": failure_reasons,
        "timeline": {
            "started_at_utc": "2026-07-21T00:00:00Z",
            "started_at_epoch_ms": START_EPOCH_MS,
            "host_started_monotonic_ns": HOST_START_MONOTONIC_NS,
            "device_started_monotonic_ns": DEVICE_START_MONOTONIC_NS,
            "finished_at_utc": "2026-07-21T00:01:00Z",
            "finished_at_epoch_ms": FINISH_EPOCH_MS,
            "host_finished_monotonic_ns": HOST_FINISH_MONOTONIC_NS,
            "device_finished_monotonic_ns": DEVICE_FINISH_MONOTONIC_NS,
        },
        "provenance": {
            "repository": "git@github.com:botiverse/mobile.git",
            "commit_sha": OID,
            "tree_sha": TREE,
            "resolved_ref": "refs/heads/testbox-exact",
            "resolved_ref_sha": OID,
            "dirty": False,
            "diff_sha256": None,
            "workflow_path": ".github/workflows/visual-android-testbox.yml",
            "workflow_blob_sha": WORKFLOW_BLOB,
            "action_pins": {
                "begin_testbox": "233448af4bfdc6fca509a7f0974411ac6d8a8043",
                "run_testbox": "5ca05834db1d3813554d1dd109e5f2087a8d7cbc",
            },
            "harness_commit_sha": OID,
            "executor_commit_sha": OID,
        },
        "testbox": {
            "id": "tbx_test",
            "actions_run_id": "123",
            "workflow": "visual-android-testbox.yml",
            "job": "android_visual",
            "cli_version": "0.4.49",
        },
        "apks": {
            "app": {
                "path": "app.apk", "size_bytes": 1, "source_sha256": SHA,
                "installed_sha256": SHA, "package_name": "build.raft.app.connectedtest",
                "version_code": "1", "version_name": "1", "min_sdk": 26,
                "target_sdk": 36, "signer_sha256": SHA, "lineage_sha256": None,
            },
            "instrumentation": {
                "path": "test.apk", "size_bytes": 1, "source_sha256": SHA,
                "installed_sha256": SHA, "package_name": "build.raft.app.connectedtest.test",
                "version_code": "1", "version_name": "1", "min_sdk": 26,
                "target_sdk": 36, "signer_sha256": SHA, "lineage_sha256": None,
                "target_package": "build.raft.app.connectedtest",
            },
        },
        "cases": {"actual": case_map(), "expected": case_map()},
        "evidence_inventory": inventory or [],
        "execution": {
            "mode": "targeted", "method_filter": SELECTOR, "expected_method_filter": SELECTOR,
            "one_case_per_process": True, "attempt": 1, "retry": 0,
            "shard_index": 0, "shard_total": 1, "instrumentation_rc": 0,
            "failure_json_count": 0, "png_count": 1, "metadata_count": 1,
            "started_count": 1, "ended_count": 1, "expected_count": 1,
            "started_methods_sha256": started_hash, "ended_methods_sha256": started_hash,
            "expected_methods_sha256": expected_hash, "actual_cases_sha256": SHA,
            "expected_cases_sha256": SHA, "tail_drop_methods": [],
            "missing_expected_methods": missing_expected or [], "unexpected_started_methods": [],
            "missing_artifact_cases": [],
        },
        "control": {"injected_fault": fault},
        "log_scope": {
            "target_package": "build.raft.app.connectedtest", "target_uid": PROCESS_UID,
            "process_ids": [PROCESS_ID], "boot_id": BOOT_ID, "generation": GENERATION,
            "format": "epoch",
            "allowed_tags": ["VisualCapture", "TestRunner", "System.out", "Task139Reuse"],
            "line_count": 2, "foreign_pid_lines_dropped": 0,
            "outside_window_lines_dropped": 0,
            "collection_finished_at_epoch_ms": CAPTURE_EPOCH_MS + 1_000,
            "device_collection_finished_monotonic_ns": DEVICE_COLLECTION_MONOTONIC_NS,
        },
        "device": {
            "avd_name": "visual-testbox-api34", "system_image": "system-images;android-34;google_apis;x86_64",
            "kvm": True, "renderer": "swiftshader_indirect",
            "serial": "emulator-5554", "boot_id": BOOT_ID, "build_fingerprint": "google/test",
            "api_level": 34, "abi": "x86_64", "locale": "en-US", "timezone": "UTC",
            "wm_size": "Override size: 1170x2532;", "wm_density": "Override density: 480;",
            "orientation": "0", "font_scale": "1.0", "night_mode": "Night mode: no", "theme": "light",
            "animation_scales": "0,0,0", "insets_snapshot_sha256": SHA,
        },
    }


def evidence_inventory(evidence: Path) -> list[dict[str, object]]:
    png = evidence / f"android/{CASE_ID}.png"
    metadata = evidence / f"android/{CASE_ID}.metadata.json"
    parsed = json.loads(metadata.read_text(encoding="utf-8"))
    identity = parsed["captureIdentity"]
    diagnostics = parsed["diagnostics"]
    captured_ms = identity["capturedAtEpochMs"]
    return [
        {
            "case_id": CASE_ID,
            "png": {"path": f"android/{CASE_ID}.png", "size_bytes": png.stat().st_size, "sha256": digest(png)},
            "metadata": {
                "path": f"android/{CASE_ID}.metadata.json",
                "size_bytes": metadata.stat().st_size,
                "sha256": digest(metadata),
            },
            "captured_at_utc": datetime.fromtimestamp(captured_ms / 1000, tz=timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "captured_at_epoch_ms": captured_ms,
            "captured_at_monotonic_ns": identity["capturedAtMonotonicNs"],
            "boot_id": identity["bootId"],
            "process_id": diagnostics["processId"],
            "process_uid": diagnostics["processUid"],
            "generation": identity["generation"],
            "attempt": identity["attempt"],
        }
    ]


def main() -> int:
    json.loads(SCHEMA.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="testbox-manifest-test-") as tmp:
        root = Path(tmp)
        evidence = root / "evidence"
        write_test_png(evidence / f"android/{CASE_ID}.png")
        write(
            evidence / f"android/{CASE_ID}.metadata.json",
            json.dumps(
                {
                    "provider": "android",
                    "providerType": "android-kuikly-shared-page",
                    "source": "kmp-shared",
                    "caseId": CASE_ID,
                    "route": {
                        "pageName": "compose_settings_route_visual",
                        "fixtureKey": "canonical",
                        "fixtureRevision": "settings-appearance-v1",
                        "renderedGoalSha256": GOAL_SHA,
                        "realProductRoute": True,
                    },
                    "viewport": {"width": 1170, "height": 2532, "density": 1},
                    "systemBars": "hidden",
                    "crop": {
                        "mode": "manual-rect", "contract": "viewport-bounds",
                        "rect": {"x": 0, "y": 0, "width": 390, "height": 844},
                    },
                    "capture": {
                        "method": "app-window-root-draw-viewport-density-resize",
                        "fullscreen": True, "systemBars": "hidden",
                        "validation": {"uniqueColors": 2, "dominantRatio": 0.99},
                        "readyTokenObserved": True, "stableFrameCount": 2,
                    },
                    "diagnostics": {
                        "displayWidthPx": 1170, "displayHeightPx": 2532, "densityDpi": 480,
                        "rootWidthPx": 1170, "rootHeightPx": 2532, "hardwareAccelerated": True,
                        "totalViews": 10, "laidOutViews": 10, "visibleContentLeafViews": 5,
                        "targetPackage": "build.raft.app.connectedtest", "processId": PROCESS_ID,
                        "processUid": PROCESS_UID,
                    },
                    "captureIdentity": {
                        "attempt": 1, "generation": GENERATION, "bootId": BOOT_ID,
                        "capturedAtEpochMs": CAPTURE_EPOCH_MS,
                        "capturedAtMonotonicNs": CAPTURE_MONOTONIC_NS,
                    },
                    "clampedToShortWindow": False,
                    "capturedAt": str(CAPTURE_EPOCH_MS),
                }
            )
            + "\n",
        )
        write(
            evidence / "logcat-shard-0.txt",
            "--------- beginning of main\n"
            f"1784592010.100 {PROCESS_ID} {PROCESS_ID} I VisualCapture: CASE_START {METHOD} "
            f"attempt=1 generation={GENERATION}\n"
            f"1784592010.500 {PROCESS_ID} {PROCESS_ID} I VisualCapture: CASE_END {METHOD} status=OK "
            f"attempt=1 generation={GENERATION}\n",
        )
        for prefix in ("expected", "started", "ended"):
            write(evidence / f"{prefix}-methods-0.txt", METHOD + "\n")
        write(evidence / "actual-cases-0.txt", CASE_ID + "\n")
        write(evidence / "expected-cases-0.txt", CASE_ID + "\n")
        write(evidence / "actual-case-map-0.json", json.dumps(case_map()) + "\n")
        write(evidence / "expected-case-map-0.json", json.dumps(case_map()) + "\n")
        write(evidence / "instr-rc-0.txt", "0\n")
        write(evidence / "device-reset.txt", "before_focus=null\nafter_focus=null\n")
        write(evidence / "app-install.txt", "Success\n")
        write(evidence / "test-install.txt", "Success\n")
        write(evidence / "instrumentation/processes.tsv", "target-0\t0\t-e class selector\n")
        write(evidence / "instrumentation/target-0.txt", "OK (1 test)\n")
        write(evidence / "controls/testbox-stop.txt", "Testbox tbx_test stopped successfully\n")

        expected_hash = digest(evidence / "expected-methods-0.txt")
        executed_hash = digest(evidence / "started-methods-0.txt")
        inventory = evidence_inventory(evidence)
        primary = receipt(
            status="complete", expected_hash=expected_hash, started_hash=executed_hash,
            fault=None, failure_reasons=[], inventory=inventory,
        )
        write(evidence / "android-visual-runner-receipt.json", json.dumps(primary) + "\n")
        write(evidence / "controls/known-green.json", json.dumps(primary) + "\n")
        red = receipt(
            status="harness_incomplete",
            expected_hash=SHA,
            started_hash=executed_hash,
            fault="expected_method_mismatch",
            failure_reasons=["expected_execution_mismatch"],
            missing_expected=["captureRaftUiSegmentedControlStates"],
            inventory=inventory,
        )
        write(evidence / "controls/known-red.json", json.dumps(red) + "\n")

        runner_hash = digest(evidence / "android-visual-runner-receipt.json")
        green_hash = digest(evidence / "controls/known-green.json")
        red_hash = digest(evidence / "controls/known-red.json")
        stop_hash = digest(evidence / "controls/testbox-stop.txt")
        metadata = {
            "source": {
                "repository": "git@github.com:botiverse/mobile.git", "commit_sha": OID,
                "tree_sha": TREE, "resolved_ref": "refs/heads/testbox-exact",
                "resolved_ref_sha": OID, "dirty": False, "diff_sha256": None,
                "workflow": {
                    "path": ".github/workflows/visual-android-testbox.yml", "blob_sha": WORKFLOW_BLOB,
                    "action_pins": {
                        "begin_testbox": "233448af4bfdc6fca509a7f0974411ac6d8a8043",
                        "run_testbox": "5ca05834db1d3813554d1dd109e5f2087a8d7cbc",
                    },
                },
                "harness_commit_sha": OID, "executor_commit_sha": OID,
            },
            "case": {
                "id": CASE_ID, "testbox_eligible": True, "revision": "settings-appearance-v1",
                "harness_source_body_sha256": BODY_SHA, "rendered_goal_sha256": GOAL_SHA,
                "provider_source": "android-kuikly-shared-page", "real_product_route": True,
                "fixture": "canonical", "fixture_revision": "settings-appearance-v1",
                "fixture_sensitivity_review": "non_sensitive_synthetic",
            },
            "binaries": {
                "app": primary["apks"]["app"],
                "instrumentation": {key: value for key, value in primary["apks"]["instrumentation"].items() if key != "target_package"},
            },
            "testbox": {
                "id": "tbx_test", "actions_run_id": "123", "workflow": "visual-android-testbox.yml",
                "job": "android_visual", "cli_version": "0.4.49", "explicit_stop_result": "completed",
                "stop_receipt_sha256": stop_hash,
            },
            "device": {
                "avd_name": "visual-testbox-api34", "system_image": "system-images;android-34;google_apis;x86_64",
                "build_fingerprint": "google/test", "boot_id": BOOT_ID, "api_level": 34,
                "abi": "x86_64", "kvm": True, "renderer": "swiftshader_indirect",
                "viewport": {"width_px": 1170, "height_px": 2532, "density_dpi": 480},
                "insets": SHA, "orientation": "0", "locale": "en-US",
                "timezone": "UTC", "theme": "light", "font_scale": "1.0",
                "animation_scales": "0,0,0",
            },
            "execution": {
                "attempt": 1, "retry": 0, "shard_index": 0, "shard_total": 1,
                "expected_test_list_sha256": expected_hash, "executed_test_list_sha256": executed_hash,
                "runner_receipt_sha256": runner_hash, "started_at_utc": "2026-07-21T00:00:00Z",
                "finished_at_utc": "2026-07-21T00:01:00Z",
                "started_monotonic_ns": HOST_START_MONOTONIC_NS,
                "finished_monotonic_ns": HOST_FINISH_MONOTONIC_NS,
                "instrumentation_rc": 0, "junit_summary": "OK (1 test)",
                "failure_json_count": 0, "tail_drop_methods": [], "crash_count": 0, "anr_count": 0,
                "harness_status": "complete", "settle_oracle": "route-ready-token+two-identical-frames",
                "baseline_sha256": None, "comparison_tool": "capture-only", "thresholds_sha256": None,
                "mask_sha256": None,
                "controls": {
                    "known_green": {
                        "injected_fault": None, "expected_harness_status": "complete",
                        "observed_harness_status": "complete", "evidence_sha256": green_hash,
                    },
                    "known_red": {
                        "injected_fault": "expected_method_mismatch",
                        "expected_harness_status": "harness_incomplete",
                        "observed_harness_status": "harness_incomplete", "evidence_sha256": red_hash,
                    },
                },
            },
            "transformations": [],
        }
        metadata_path = root / "metadata.json"
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
        output = evidence / "testbox-preflight-manifest.v1.json"
        completed = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            check=True, text=True, capture_output=True,
        )
        assert "PASS" in completed.stdout, completed
        manifest = json.loads(output.read_text(encoding="utf-8"))
        assert manifest["evidence_class"] == "testbox_preflight"
        assert manifest["terminal_eligible"] is False
        assert manifest["integrity"]["sensitive_scan"]["result"] == "pass"
        accepted_manifest_hash = digest(output)
        accepted_sums_hash = digest(evidence / "SHA256SUMS")

        invalid_metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        invalid_metadata["execution"]["junit_summary"] = 123
        invalid_path = root / "invalid-metadata.json"
        invalid_path.write_text(json.dumps(invalid_metadata), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(invalid_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "wrong type" in rejected.stderr, rejected
        assert digest(output) == accepted_manifest_hash and digest(evidence / "SHA256SUMS") == accepted_sums_hash

        for label, mutate in (
            ("ref mismatch", lambda value: value["source"].__setitem__("resolved_ref_sha", "d" * 40)),
            ("shard bounds", lambda value: value["execution"].__setitem__("shard_index", 1)),
            ("reverse time", lambda value: value["execution"].__setitem__("finished_monotonic_ns", 0)),
            ("unknown enum", lambda value: value["execution"]["controls"]["known_red"].__setitem__("observed_harness_status", "unknown")),
        ):
            invalid = json.loads(metadata_path.read_text(encoding="utf-8"))
            mutate(invalid)
            invalid_path.write_text(json.dumps(invalid), encoding="utf-8")
            rejected = subprocess.run(
                [sys.executable, str(BUILDER), "--metadata", str(invalid_path), "--evidence-dir", str(evidence), "--output", str(output)],
                text=True, capture_output=True,
            )
            assert rejected.returncode == 1, (label, rejected)

        png_path = evidence / f"android/{CASE_ID}.png"
        real_png = png_path.read_bytes()
        png_path.write_bytes(b"raw-full-frame")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "not a PNG" in rejected.stderr, rejected
        png_path.write_bytes(real_png)

        png_path.write_bytes(real_png + b"ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "sensitive" in rejected.stderr, rejected
        png_path.write_bytes(real_png)

        write_test_png(png_path, variant=1)
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "inventory png bytes" in rejected.stderr, rejected
        png_path.write_bytes(real_png)

        case_metadata_path = evidence / f"android/{CASE_ID}.metadata.json"
        valid_case_metadata_bytes = case_metadata_path.read_bytes()
        valid_case_metadata = json.loads(valid_case_metadata_bytes)
        swapped_metadata = json.loads(json.dumps(valid_case_metadata))
        swapped_metadata["systemBars"] = "hidden-by-stale-attempt"
        case_metadata_path.write_text(json.dumps(swapped_metadata), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "inventory metadata bytes" in rejected.stderr, rejected
        case_metadata_path.write_bytes(valid_case_metadata_bytes)

        original_primary_bytes = (evidence / "android-visual-runner-receipt.json").read_bytes()
        original_green_bytes = (evidence / "controls/known-green.json").read_bytes()
        original_metadata_bytes = metadata_path.read_bytes()
        outside_metadata = json.loads(json.dumps(valid_case_metadata))
        outside_metadata["captureIdentity"]["capturedAtMonotonicNs"] = DEVICE_FINISH_MONOTONIC_NS + 1
        case_metadata_path.write_text(json.dumps(outside_metadata), encoding="utf-8")
        outside_inventory = evidence_inventory(evidence)
        outside_primary = receipt(
            status="complete", expected_hash=expected_hash, started_hash=executed_hash,
            fault=None, failure_reasons=[], inventory=outside_inventory,
        )
        write(evidence / "android-visual-runner-receipt.json", json.dumps(outside_primary) + "\n")
        write(evidence / "controls/known-green.json", json.dumps(outside_primary) + "\n")
        outside_bundle_metadata = json.loads(original_metadata_bytes)
        outside_bundle_metadata["execution"]["runner_receipt_sha256"] = digest(evidence / "android-visual-runner-receipt.json")
        outside_bundle_metadata["execution"]["controls"]["known_green"]["evidence_sha256"] = digest(evidence / "controls/known-green.json")
        metadata_path.write_text(json.dumps(outside_bundle_metadata), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "outside the attempt window" in rejected.stderr, rejected
        case_metadata_path.write_bytes(valid_case_metadata_bytes)
        (evidence / "android-visual-runner-receipt.json").write_bytes(original_primary_bytes)
        (evidence / "controls/known-green.json").write_bytes(original_green_bytes)
        metadata_path.write_bytes(original_metadata_bytes)

        reversed_host_primary = receipt(
            status="complete", expected_hash=expected_hash, started_hash=executed_hash,
            fault=None, failure_reasons=[], inventory=evidence_inventory(evidence),
        )
        reversed_host_primary["timeline"]["host_finished_monotonic_ns"] = HOST_START_MONOTONIC_NS - 1
        write(evidence / "android-visual-runner-receipt.json", json.dumps(reversed_host_primary) + "\n")
        write(evidence / "controls/known-green.json", json.dumps(reversed_host_primary) + "\n")
        reversed_host_bundle = json.loads(original_metadata_bytes)
        reversed_host_bundle["execution"]["finished_monotonic_ns"] = HOST_START_MONOTONIC_NS - 1
        reversed_host_bundle["execution"]["runner_receipt_sha256"] = digest(evidence / "android-visual-runner-receipt.json")
        reversed_host_bundle["execution"]["controls"]["known_green"]["evidence_sha256"] = digest(evidence / "controls/known-green.json")
        metadata_path.write_text(json.dumps(reversed_host_bundle), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "execution timeline runs backwards" in rejected.stderr, rejected
        (evidence / "android-visual-runner-receipt.json").write_bytes(original_primary_bytes)
        (evidence / "controls/known-green.json").write_bytes(original_green_bytes)
        metadata_path.write_bytes(original_metadata_bytes)

        wrong_boot_metadata = json.loads(json.dumps(valid_case_metadata))
        wrong_boot_metadata["captureIdentity"]["bootId"] = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        case_metadata_path.write_text(json.dumps(wrong_boot_metadata), encoding="utf-8")
        wrong_boot_primary = receipt(
            status="complete", expected_hash=expected_hash, started_hash=executed_hash,
            fault=None, failure_reasons=[], inventory=evidence_inventory(evidence),
        )
        write(evidence / "android-visual-runner-receipt.json", json.dumps(wrong_boot_primary) + "\n")
        write(evidence / "controls/known-green.json", json.dumps(wrong_boot_primary) + "\n")
        wrong_boot_bundle = json.loads(original_metadata_bytes)
        wrong_boot_bundle["execution"]["runner_receipt_sha256"] = digest(evidence / "android-visual-runner-receipt.json")
        wrong_boot_bundle["execution"]["controls"]["known_green"]["evidence_sha256"] = digest(evidence / "controls/known-green.json")
        metadata_path.write_text(json.dumps(wrong_boot_bundle), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "boot ID" in rejected.stderr, rejected
        case_metadata_path.write_bytes(valid_case_metadata_bytes)
        (evidence / "android-visual-runner-receipt.json").write_bytes(original_primary_bytes)
        (evidence / "controls/known-green.json").write_bytes(original_green_bytes)
        metadata_path.write_bytes(original_metadata_bytes)

        logcat_path = evidence / "logcat-shard-0.txt"
        valid_logcat = logcat_path.read_text(encoding="utf-8")
        logcat_path.write_text(valid_logcat.replace(f" {PROCESS_ID} {PROCESS_ID} ", " 9999 9999 ", 1), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "foreign process ID" in rejected.stderr, rejected
        logcat_path.write_text(valid_logcat, encoding="utf-8")

        logcat_path.write_text(valid_logcat.replace(GENERATION, "c" * 32, 1), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "stale or foreign generation" in rejected.stderr, rejected
        logcat_path.write_text(valid_logcat, encoding="utf-8")

        oversized_png = (
            b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 100_000, 100_000, 8, 2, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(b"\x00"))
            + png_chunk(b"IEND", b"")
        )
        png_path.write_bytes(oversized_png)
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "geometry/format" in rejected.stderr, rejected

        expected_width = 1170
        expected_height = 2532
        bomb_raw = b"\x00" * ((expected_width * 3 + 1) * expected_height + 1)
        bomb_png = (
            b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", struct.pack(">IIBBBBB", expected_width, expected_height, 8, 2, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(bomb_raw, 9))
            + png_chunk(b"IEND", b"")
        )
        png_path.write_bytes(bomb_png)
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "scanline size mismatch" in rejected.stderr, rejected
        png_path.write_bytes(real_png)

        clamped_metadata = dict(valid_case_metadata)
        clamped_metadata["clampedToShortWindow"] = True
        case_metadata_path.write_text(json.dumps(clamped_metadata), encoding="utf-8")
        clamped_inventory = evidence_inventory(evidence)
        clamped_primary = receipt(
            status="complete", expected_hash=expected_hash, started_hash=executed_hash,
            fault=None, failure_reasons=[], inventory=clamped_inventory,
        )
        write(evidence / "android-visual-runner-receipt.json", json.dumps(clamped_primary) + "\n")
        write(evidence / "controls/known-green.json", json.dumps(clamped_primary) + "\n")
        clamped_bundle_metadata = json.loads(original_metadata_bytes)
        clamped_bundle_metadata["execution"]["runner_receipt_sha256"] = digest(evidence / "android-visual-runner-receipt.json")
        clamped_bundle_metadata["execution"]["controls"]["known_green"]["evidence_sha256"] = digest(evidence / "controls/known-green.json")
        metadata_path.write_text(json.dumps(clamped_bundle_metadata), encoding="utf-8")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "clamped" in rejected.stderr, rejected
        case_metadata_path.write_bytes(valid_case_metadata_bytes)
        (evidence / "android-visual-runner-receipt.json").write_bytes(original_primary_bytes)
        (evidence / "controls/known-green.json").write_bytes(original_green_bytes)
        metadata_path.write_bytes(original_metadata_bytes)

        symlink = evidence / "android/symlink.png"
        symlink.symlink_to("/etc/hosts")
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "symlink" in rejected.stderr, rejected
        symlink.unlink()

        large = evidence / "android/large.metadata.json"
        large.write_bytes(b'ghp_abcdefghijklmnopqrstuvwxyz0123456789' + b"x" * (17 * 1024 * 1024))
        rejected = subprocess.run(
            [sys.executable, str(BUILDER), "--metadata", str(metadata_path), "--evidence-dir", str(evidence), "--output", str(output)],
            text=True, capture_output=True,
        )
        assert rejected.returncode == 1 and "sensitive" in rejected.stderr, rejected

    print("build_testbox_preflight_manifest test: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
