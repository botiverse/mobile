#!/usr/bin/env python3
"""Build and atomically validate an Android visual Testbox preflight bundle."""

from __future__ import annotations

import argparse
import binascii
import copy
import hashlib
import json
import re
import stat
import struct
import subprocess
import sys
import zlib
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any


SCHEMA_VERSION = "testbox-preflight-manifest.v1"
SCHEMA_REF = (
    "https://raw.githubusercontent.com/botiverse/mobile/main/"
    "docs/qa-acceptance/testbox-preflight/testbox-preflight-manifest.v1.schema.json"
)
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = (
    REPOSITORY_ROOT
    / "docs/qa-acceptance/testbox-preflight/testbox-preflight-manifest.v1.schema.json"
)
sys.path.insert(0, str(REPOSITORY_ROOT / "scripts"))
from visual_android_case_filter import enumerate_capture_tests  # noqa: E402
BEGIN_TESTBOX_PIN = "233448af4bfdc6fca509a7f0974411ac6d8a8043"
RUN_TESTBOX_PIN = "5ca05834db1d3813554d1dd109e5f2087a8d7cbc"
ZERO_SHA256 = "0" * 64
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_OID_RE = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
FORBIDDEN_PATH_RE = re.compile(
    r"(?:^|/)(?:\.env(?:\.|$)|github-packages\.properties$|adbkey(?:\.pub)?$|"
    r".*(?:credential|keystore|private[-_]?key|shared_prefs|databases|token).*)",
    re.IGNORECASE,
)
FORBIDDEN_CONTENT_RES = [
    re.compile(rb"sk_(?:agent|machine)_[A-Za-z0-9_-]+"),
    re.compile(rb"gh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(rb"githubPackagesToken\s*="),
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
]
LOG_TAG_PATTERN = rb"(?:VisualCapture|TestRunner|System\.out|Task139Reuse)"
ALLOWED_LOG_LINE_RE = re.compile(
    rb"(?:^|\s)[VDIWEF]/" + LOG_TAG_PATTERN + rb"(?:\(|:|\s)"
    rb"|\s[VDIWEF]\s+" + LOG_TAG_PATTERN + rb"\s*:"
)
SCOPED_LOG_LINE_RE = re.compile(
    rb"^\s*(?P<epoch>[0-9]+(?:\.[0-9]+)?)\s+(?P<pid>[0-9]+)\s+[0-9]+\s+"
    rb"[VDIWEF]\s+(?P<tag>VisualCapture|TestRunner|System\.out|Task139Reuse)\s*:"
)
PHASE1_WIDTH_PX = 1170
PHASE1_HEIGHT_PX = 2532
MAX_RAW_PNG_BYTES = 32 * 1024 * 1024
MAX_PNG_CHUNK_BYTES = 16 * 1024 * 1024


class ManifestError(ValueError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_keys(value: dict[str, Any], keys: set[str], context: str) -> None:
    actual = set(value)
    missing = keys - actual
    unknown = actual - keys
    if missing or unknown:
        raise ManifestError(
            f"{context} key mismatch: missing={sorted(missing)} unknown={sorted(unknown)}"
        )


def require_sha256(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise ManifestError(f"{context} must be a lowercase SHA-256")
    return value


def require_git_oid(value: Any, context: str) -> str:
    if not isinstance(value, str) or not GIT_OID_RE.fullmatch(value):
        raise ManifestError(f"{context} must be a full lowercase Git object ID")
    return value


def require_utc(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestError(f"{context} must be an ISO-8601 UTC timestamp ending in Z")
    try:
        datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ManifestError(f"{context} is not a valid UTC timestamp") from exc
    return value


def classify(path: PurePosixPath) -> tuple[str, str]:
    text = path.as_posix()
    name = path.name
    if text.startswith("android/"):
        if name.endswith(".failure.png"):
            return "failure_png", "support"
        if name.endswith(".failure.json"):
            return "failure_json", "support"
        if name.endswith(".metadata.json"):
            return "metadata_json", "support"
        if name.endswith(".png"):
            return "raw_png", "raw"
    if text.startswith("derived/") and name.endswith(".png"):
        return "derived_png", "derived"
    if text.startswith("derived/") and name.endswith(".json"):
        return "derived_json", "derived"
    if text.startswith("controls/") and name.endswith(".json"):
        return "control_receipt", "support"
    if text == "controls/testbox-stop.txt":
        return "testbox_stop_receipt", "support"
    if text.startswith("instrumentation/") and name.endswith(".txt"):
        return "instrumentation_stdout", "support"
    if text == "instrumentation/processes.tsv":
        return "instrumentation_processes", "support"
    if re.fullmatch(r"logcat-shard-[0-9]+\.txt", text):
        return "scoped_logcat", "support"
    if re.fullmatch(r"instr-rc-[0-9]+\.txt", text):
        return "instrumentation_rc", "support"
    if re.fullmatch(r"(?:started|ended|expected)-methods-[0-9]+\.txt", text):
        return "test_list", "support"
    if re.fullmatch(r"(?:actual|expected)-cases-[0-9]+\.txt", text):
        return "test_list", "support"
    if re.fullmatch(r"(?:actual|expected)-case-map-[0-9]+\.json", text):
        return "case_map", "support"
    if text == "android-visual-runner-receipt.json":
        return "runner_receipt", "support"
    if text == "device-reset.txt":
        return "device_reset", "support"
    if text in {"app-install.txt", "test-install.txt"}:
        return "install_receipt", "support"
    raise ManifestError(f"evidence file is outside the allowlist: {text}")


def scan_sensitive(path: Path, relative: PurePosixPath, kind: str) -> None:
    text = relative.as_posix()
    if FORBIDDEN_PATH_RE.search(text):
        raise ManifestError(f"sensitive path rejected: {text}")
    if kind == "scoped_logcat":
        with path.open("rb") as source:
            for line_number, line in enumerate(source, start=1):
                for pattern in FORBIDDEN_CONTENT_RES:
                    if pattern.search(line):
                        raise ManifestError(f"sensitive content rejected: {text}")
                if (
                    not line.strip()
                    or line.startswith(b"TAIL_DROP_GUARD:")
                    or line.startswith(b"--------- beginning of ")
                ):
                    continue
                if ALLOWED_LOG_LINE_RE.search(line) is None:
                    raise ManifestError(
                        f"non-allowlisted logcat tag at {text}:{line_number}"
                    )
        return
    overlap = b""
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            window = overlap + chunk
            for pattern in FORBIDDEN_CONTENT_RES:
                if pattern.search(window):
                    raise ManifestError(f"sensitive content rejected: {text}")
            overlap = window[-4096:]


def decode_png_identity(path: Path) -> tuple[int, int, int]:
    file_size = path.stat().st_size
    if file_size > MAX_RAW_PNG_BYTES:
        raise ManifestError(f"raw PNG exceeds the Phase 1 byte limit: {path.name}")
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ManifestError(f"raw evidence is not a PNG: {path.name}")
    offset = 8
    width = height = bit_depth = color_type = 0
    compressed = bytearray()
    saw_iend = False
    saw_ihdr = False
    while offset + 12 <= len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        if length > MAX_PNG_CHUNK_BYTES:
            raise ManifestError(f"PNG chunk exceeds the byte limit: {path.name}")
        chunk_type = data[offset + 4 : offset + 8]
        chunk_data = data[offset + 8 : offset + 8 + length]
        if offset + 12 + length > len(data):
            raise ManifestError(f"truncated PNG chunk: {path.name}")
        expected_crc = struct.unpack(">I", data[offset + 8 + length : offset + 12 + length])[0]
        actual_crc = binascii.crc32(chunk_type + chunk_data) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise ManifestError(f"PNG chunk CRC mismatch: {path.name}")
        if chunk_type == b"IHDR":
            if saw_ihdr or length != 13:
                raise ManifestError(f"invalid PNG IHDR structure: {path.name}")
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", chunk_data
            )
            saw_ihdr = True
            if (
                (width, height) != (PHASE1_WIDTH_PX, PHASE1_HEIGHT_PX)
                or bit_depth != 8
                or color_type not in {2, 6}
                or compression != 0
                or filtering != 0
                or interlace != 0
            ):
                raise ManifestError(f"unsupported PNG geometry/format: {path.name}")
        elif chunk_type == b"IDAT":
            if not saw_ihdr:
                raise ManifestError(f"PNG IDAT precedes IHDR: {path.name}")
            if len(compressed) + length > MAX_RAW_PNG_BYTES:
                raise ManifestError(f"PNG compressed payload exceeds the byte limit: {path.name}")
            compressed.extend(chunk_data)
        elif chunk_type == b"IEND":
            if length != 0:
                raise ManifestError(f"invalid PNG IEND structure: {path.name}")
            saw_iend = True
            offset += 12 + length
            break
        offset += 12 + length
    if not saw_iend or offset != len(data) or not saw_ihdr or not compressed:
        raise ManifestError(f"unsupported PNG geometry/format: {path.name}")
    channels = 3 if color_type == 2 else 4
    stride = width * channels
    expected_decoded_bytes = height * (stride + 1)
    try:
        decompressor = zlib.decompressobj()
        raw = decompressor.decompress(bytes(compressed), expected_decoded_bytes + 1)
    except zlib.error as exc:
        raise ManifestError(f"invalid PNG deflate stream: {path.name}") from exc
    if (
        len(raw) != expected_decoded_bytes
        or decompressor.unconsumed_tail
        or not decompressor.eof
        or decompressor.unused_data
    ):
        raise ManifestError(f"PNG scanline size mismatch: {path.name}")
    previous = bytearray(stride)
    unique: set[bytes] = set()
    cursor = 0
    for _ in range(height):
        filter_type = raw[cursor]
        cursor += 1
        encoded = raw[cursor : cursor + stride]
        cursor += stride
        decoded = bytearray(stride)
        for index, byte in enumerate(encoded):
            left = decoded[index - channels] if index >= channels else 0
            up = previous[index]
            upper_left = previous[index - channels] if index >= channels else 0
            if filter_type == 0:
                value = byte
            elif filter_type == 1:
                value = (byte + left) & 0xFF
            elif filter_type == 2:
                value = (byte + up) & 0xFF
            elif filter_type == 3:
                value = (byte + ((left + up) // 2)) & 0xFF
            elif filter_type == 4:
                predictor = left + up - upper_left
                distances = (abs(predictor - left), abs(predictor - up), abs(predictor - upper_left))
                base = (left, up, upper_left)[distances.index(min(distances))]
                value = (byte + base) & 0xFF
            else:
                raise ManifestError(f"unsupported PNG filter {filter_type}: {path.name}")
            decoded[index] = value
        if len(unique) < 2:
            for index in range(0, stride, channels):
                unique.add(bytes(decoded[index : index + channels]))
                if len(unique) >= 2:
                    break
        previous = decoded
    if len(unique) < 2:
        raise ManifestError(f"PNG contains no visible pixel variation: {path.name}")
    return width, height, len(unique)


def artifact_entries(evidence_dir: Path, excluded: set[Path]) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for path in sorted(evidence_dir.rglob("*")):
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode):
            raise ManifestError(f"symlink evidence is forbidden: {path.relative_to(evidence_dir)}")
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise ManifestError(f"non-regular evidence is forbidden: {path.relative_to(evidence_dir)}")
        resolved = path.resolve(strict=True)
        try:
            resolved.relative_to(evidence_dir)
        except ValueError as exc:
            raise ManifestError(f"evidence path escapes root: {path}") from exc
        if path.resolve() in excluded or path.name == "SHA256SUMS":
            continue
        relative = PurePosixPath(path.relative_to(evidence_dir).as_posix())
        kind, role = classify(relative)
        scan_sensitive(path, relative, kind)
        if kind == "raw_png":
            decode_png_identity(path)
        captured = datetime.fromtimestamp(path.stat().st_mtime, tz=timezone.utc)
        entries.append(
            {
                "path": relative.as_posix(),
                "kind": kind,
                "role": role,
                "size_bytes": path.stat().st_size,
                "sha256": sha256_file(path),
                "captured_at_utc": captured.isoformat(timespec="seconds").replace("+00:00", "Z"),
            }
        )
    if not entries:
        raise ManifestError("evidence directory is empty")
    return entries


def validate_metadata(metadata: dict[str, Any]) -> None:
    require_keys(
        metadata,
        {"source", "case", "binaries", "testbox", "device", "execution", "transformations"},
        "metadata",
    )
    source = metadata["source"]
    require_keys(
        source,
        {
            "repository", "commit_sha", "tree_sha", "resolved_ref", "resolved_ref_sha",
            "dirty", "diff_sha256", "workflow", "harness_commit_sha", "executor_commit_sha",
        },
        "source",
    )
    for key in ("commit_sha", "tree_sha", "resolved_ref_sha", "harness_commit_sha", "executor_commit_sha"):
        require_git_oid(source[key], f"source.{key}")
    if source["resolved_ref_sha"] != source["commit_sha"]:
        raise ManifestError("resolved_ref_sha must exactly equal commit_sha")
    if source["harness_commit_sha"] != source["commit_sha"] or source["executor_commit_sha"] != source["commit_sha"]:
        raise ManifestError("harness/executor commit must equal the captured source commit")
    if source["dirty"] is False and source["diff_sha256"] is not None:
        raise ManifestError("clean source must use diff_sha256=null")
    if source["dirty"] is True:
        raise ManifestError("Phase 1 sample must be captured from a clean immutable exact")
    workflow = source["workflow"]
    require_keys(workflow, {"path", "blob_sha", "action_pins"}, "source.workflow")
    if workflow["path"] != ".github/workflows/visual-android-testbox.yml":
        raise ManifestError("unexpected Testbox workflow path")
    require_git_oid(workflow["blob_sha"], "source.workflow.blob_sha")
    require_keys(workflow["action_pins"], {"begin_testbox", "run_testbox"}, "action_pins")
    if workflow["action_pins"] != {
        "begin_testbox": BEGIN_TESTBOX_PIN,
        "run_testbox": RUN_TESTBOX_PIN,
    }:
        raise ManifestError("Blacksmith action pins do not match the reviewed immutable pins")

    def git_output(*args: str) -> str:
        try:
            return subprocess.check_output(
                ["git", "-C", str(REPOSITORY_ROOT), *args],
                text=True,
                stderr=subprocess.PIPE,
            ).strip()
        except subprocess.CalledProcessError as exc:
            raise ManifestError(f"unable to resolve source Git object: {' '.join(args)}") from exc

    if git_output("rev-parse", f"{source['commit_sha']}^{{tree}}") != source["tree_sha"]:
        raise ManifestError("source tree_sha does not match the committed Git object")
    workflow_object = f"{source['commit_sha']}:{workflow['path']}"
    if git_output("rev-parse", workflow_object) != workflow["blob_sha"]:
        raise ManifestError("workflow blob_sha does not match the committed workflow bytes")
    harness_path = (
        "compose/androidApp/src/androidTest/java/build/raft/app/visual/"
        "KmpVisualScreenshotCaptureTest.kt"
    )
    try:
        harness_source = subprocess.check_output(
            ["git", "-C", str(REPOSITORY_ROOT), "show", f"{source['commit_sha']}:{harness_path}"],
            text=True,
            stderr=subprocess.PIPE,
        )
        committed_cases = {
            str(entry["caseId"]): entry for entry in enumerate_capture_tests(harness_source)
        }
    except (subprocess.CalledProcessError, ValueError) as exc:
        raise ManifestError("unable to enumerate the committed canonical visual harness") from exc

    case = metadata["case"]
    require_keys(
        case,
        {
            "id", "testbox_eligible", "revision", "harness_source_body_sha256",
            "rendered_goal_sha256", "provider_source", "real_product_route", "fixture",
            "fixture_revision", "fixture_sensitivity_review",
        },
        "case",
    )
    if case["testbox_eligible"] is not True or case["real_product_route"] is not True:
        raise ManifestError("case is not explicitly Testbox-eligible on a real product route")
    if case["id"] != "components.settings.appearance.page":
        raise ManifestError("Phase 1 accepts only the frozen appearance real-route sample")
    require_sha256(case["rendered_goal_sha256"], "case.rendered_goal_sha256")
    require_sha256(case["harness_source_body_sha256"], "case.harness_source_body_sha256")
    rendered_goal = (
        "route=compose_settings_route_visual;destination=appearance;"
        f"fixture={case['fixture']};fixtureRevision={case['fixture_revision']};"
        "viewport=1170x2532@480"
    )
    if sha256_bytes(rendered_goal.encode("utf-8")) != case["rendered_goal_sha256"]:
        raise ManifestError("rendered_goal_sha256 does not match the frozen real-route goal")
    if case["fixture_sensitivity_review"] != "non_sensitive_synthetic":
        raise ManifestError("PNG OCR exemption requires a source-reviewed non-sensitive synthetic fixture")
    committed_case = committed_cases.get(case["id"])
    if committed_case is None:
        raise ManifestError("case is absent from the committed canonical harness")
    if committed_case["sourceBodySha256"] != case["harness_source_body_sha256"]:
        raise ManifestError("case harness body hash does not match committed source bytes")
    if committed_case["testboxEligible"] is not True:
        raise ManifestError("case is not TESTBOX_ELIGIBLE in committed source")
    committed_contract = {
        "routePageName": "compose_settings_route_visual",
        "fixtureKey": case["fixture"],
        "fixtureRevision": case["fixture_revision"],
        "renderedGoalSha256": case["rendered_goal_sha256"],
        "readyToken": "settings_route_visual_ready destination=appearance fixtureKey=canonical",
        "realProductRoute": True,
    }
    for key, expected in committed_contract.items():
        if committed_case.get(key) != expected:
            raise ManifestError(f"committed case source does not prove {key}")

    require_keys(metadata["binaries"], {"app", "instrumentation"}, "binaries")
    apk_keys = {
        "path", "size_bytes", "source_sha256", "installed_sha256", "package_name",
        "version_code", "version_name", "min_sdk", "target_sdk", "signer_sha256", "lineage_sha256",
    }
    for label in ("app", "instrumentation"):
        apk = metadata["binaries"][label]
        require_keys(apk, apk_keys, f"binaries.{label}")
        source_hash = require_sha256(apk["source_sha256"], f"binaries.{label}.source_sha256")
        installed_hash = require_sha256(apk["installed_sha256"], f"binaries.{label}.installed_sha256")
        if source_hash != installed_hash:
            raise ManifestError(f"{label} APK source/installed hash mismatch")
        require_sha256(apk["signer_sha256"], f"binaries.{label}.signer_sha256")
        if apk["lineage_sha256"] is not None:
            require_sha256(apk["lineage_sha256"], f"binaries.{label}.lineage_sha256")

    testbox = metadata["testbox"]
    require_keys(testbox, {"id", "actions_run_id", "workflow", "job", "cli_version", "explicit_stop_result", "stop_receipt_sha256"}, "testbox")
    if not str(testbox["id"]).startswith("tbx_"):
        raise ManifestError("invalid Testbox ID")
    if testbox["workflow"] != "visual-android-testbox.yml" or testbox["job"] != "android_visual":
        raise ManifestError("unexpected Testbox workflow/job")
    if testbox["explicit_stop_result"] != "completed":
        raise ManifestError("Testbox must be explicitly stopped before manifest acceptance")
    require_sha256(testbox["stop_receipt_sha256"], "testbox.stop_receipt_sha256")

    device = metadata["device"]
    require_keys(
        device,
        {
            "avd_name", "system_image", "build_fingerprint", "boot_id", "api_level", "abi",
            "kvm", "renderer", "viewport", "insets", "orientation", "locale", "timezone",
            "theme", "font_scale", "animation_scales",
        },
        "device",
    )
    require_keys(device["viewport"], {"width_px", "height_px", "density_dpi"}, "device.viewport")
    if device["viewport"] != {"width_px": 1170, "height_px": 2532, "density_dpi": 480}:
        raise ManifestError("device viewport must be the frozen 1170x2532@480 geometry")
    if device["api_level"] != 34 or device["abi"] != "x86_64" or device["kvm"] is not True:
        raise ManifestError("device API/ABI/KVM does not match the Phase 1 executor contract")

    execution = metadata["execution"]
    execution_keys = {
        "attempt", "retry", "shard_index", "shard_total", "expected_test_list_sha256",
        "executed_test_list_sha256", "runner_receipt_sha256", "started_at_utc", "finished_at_utc",
        "started_monotonic_ns", "finished_monotonic_ns", "instrumentation_rc", "junit_summary",
        "failure_json_count", "tail_drop_methods", "crash_count", "anr_count", "harness_status",
        "settle_oracle", "baseline_sha256", "comparison_tool", "thresholds_sha256", "mask_sha256",
        "controls",
    }
    require_keys(execution, execution_keys, "execution")
    for key in ("expected_test_list_sha256", "executed_test_list_sha256", "runner_receipt_sha256"):
        require_sha256(execution[key], f"execution.{key}")
    for key in ("baseline_sha256", "thresholds_sha256", "mask_sha256"):
        if execution[key] is not None:
            require_sha256(execution[key], f"execution.{key}")
    require_utc(execution["started_at_utc"], "execution.started_at_utc")
    require_utc(execution["finished_at_utc"], "execution.finished_at_utc")
    started_wall = datetime.fromisoformat(execution["started_at_utc"].replace("Z", "+00:00"))
    finished_wall = datetime.fromisoformat(execution["finished_at_utc"].replace("Z", "+00:00"))
    if started_wall > finished_wall or execution["started_monotonic_ns"] > execution["finished_monotonic_ns"]:
        raise ManifestError("execution timeline runs backwards")
    if not 0 <= execution["shard_index"] < execution["shard_total"]:
        raise ManifestError("execution shard index is outside shard total")
    if execution["expected_test_list_sha256"] != execution["executed_test_list_sha256"]:
        raise ManifestError("complete primary sample requires exact expected/executed test-list equality")
    if execution["harness_status"] != "complete":
        raise ManifestError("primary sample must be harness complete")
    if execution["settle_oracle"] != "route-ready-token+two-identical-frames":
        raise ManifestError("primary sample does not use the frozen ready+stability settle oracle")
    if execution["instrumentation_rc"] != 0 or execution["failure_json_count"] != 0 or execution["tail_drop_methods"]:
        raise ManifestError("primary sample contains incomplete/failure signals")
    controls = execution["controls"]
    require_keys(controls, {"known_green", "known_red"}, "execution.controls")
    control_keys = {"injected_fault", "expected_harness_status", "observed_harness_status", "evidence_sha256"}
    for label in ("known_green", "known_red"):
        require_keys(controls[label], control_keys, f"execution.controls.{label}")
        require_sha256(controls[label]["evidence_sha256"], f"execution.controls.{label}.evidence_sha256")
    if controls["known_green"]["injected_fault"] is not None:
        raise ManifestError("known-green control cannot inject a fault")
    if controls["known_green"]["expected_harness_status"] != "complete" or controls["known_green"]["observed_harness_status"] != "complete":
        raise ManifestError("known-green control did not remain complete")
    if not controls["known_red"]["injected_fault"]:
        raise ManifestError("known-red control must identify its injected fault")
    if controls["known_red"]["expected_harness_status"] != "harness_incomplete" or controls["known_red"]["observed_harness_status"] != "harness_incomplete":
        raise ManifestError("known-red control was not caught as harness_incomplete")


def validate_receipt_binding(
    evidence_dir: Path,
    metadata: dict[str, Any],
    artifacts: list[dict[str, Any]],
) -> None:
    receipt_path = evidence_dir / "android-visual-runner-receipt.json"
    if not receipt_path.is_file():
        raise ManifestError("primary android-visual-runner-receipt.json is missing")
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if receipt.get("schema_version") != "android-visual-runner-receipt.v3":
        raise ManifestError("primary runner receipt has an unknown schema_version")
    execution = metadata["execution"]
    if sha256_file(receipt_path) != execution["runner_receipt_sha256"]:
        raise ManifestError("primary runner receipt hash does not match execution metadata")
    if receipt.get("harness_status") != execution["harness_status"]:
        raise ManifestError("primary runner harness_status does not match execution metadata")
    if receipt.get("failure_reasons"):
        raise ManifestError("complete primary runner receipt contains failure reasons")

    provenance = receipt.get("provenance", {})
    source = metadata["source"]
    provenance_bindings = [
        (provenance.get("repository"), source["repository"], "repository"),
        (provenance.get("commit_sha"), source["commit_sha"], "commit SHA"),
        (provenance.get("tree_sha"), source["tree_sha"], "tree SHA"),
        (provenance.get("resolved_ref"), source["resolved_ref"], "resolved ref"),
        (provenance.get("resolved_ref_sha"), source["resolved_ref_sha"], "resolved ref SHA"),
        (provenance.get("dirty"), source["dirty"], "dirty state"),
        (provenance.get("diff_sha256"), source["diff_sha256"], "diff hash"),
        (provenance.get("workflow_path"), source["workflow"]["path"], "workflow path"),
        (provenance.get("workflow_blob_sha"), source["workflow"]["blob_sha"], "workflow blob"),
        (provenance.get("action_pins"), source["workflow"]["action_pins"], "action pins"),
        (provenance.get("harness_commit_sha"), source["harness_commit_sha"], "harness commit"),
        (provenance.get("executor_commit_sha"), source["executor_commit_sha"], "executor commit"),
    ]
    for actual, expected, label in provenance_bindings:
        if actual != expected:
            raise ManifestError(f"runner receipt {label} does not match source metadata")

    receipt_testbox = receipt.get("testbox", {})
    testbox = metadata["testbox"]
    for receipt_key, metadata_key in (
        ("id", "id"), ("actions_run_id", "actions_run_id"), ("workflow", "workflow"),
        ("job", "job"), ("cli_version", "cli_version"),
    ):
        if receipt_testbox.get(receipt_key) != testbox[metadata_key]:
            raise ManifestError(f"runner receipt Testbox {receipt_key} does not match metadata")

    receipt_execution = receipt.get("execution", {})
    receipt_device = receipt.get("device", {})
    receipt_apks = receipt.get("apks", {})
    receipt_timeline = receipt.get("timeline", {})
    bindings = [
        (receipt_execution.get("instrumentation_rc"), execution["instrumentation_rc"], "instrumentation_rc"),
        (receipt_execution.get("failure_json_count"), execution["failure_json_count"], "failure_json_count"),
        (receipt_execution.get("tail_drop_methods"), execution["tail_drop_methods"], "tail_drop_methods"),
        (receipt_execution.get("expected_methods_sha256"), execution["expected_test_list_sha256"], "expected test-list hash"),
        (receipt_execution.get("started_methods_sha256"), execution["executed_test_list_sha256"], "executed test-list hash"),
        (receipt_execution.get("attempt"), execution["attempt"], "attempt"),
        (receipt_execution.get("retry"), execution["retry"], "retry"),
        (receipt_execution.get("shard_index"), execution["shard_index"], "shard index"),
        (receipt_execution.get("shard_total"), execution["shard_total"], "shard total"),
        (receipt_timeline.get("started_at_utc"), execution["started_at_utc"], "wall start"),
        (receipt_timeline.get("finished_at_utc"), execution["finished_at_utc"], "wall finish"),
        (receipt_timeline.get("host_started_monotonic_ns"), execution["started_monotonic_ns"], "host monotonic start"),
        (receipt_timeline.get("host_finished_monotonic_ns"), execution["finished_monotonic_ns"], "host monotonic finish"),
        (receipt_device.get("build_fingerprint"), metadata["device"]["build_fingerprint"], "build fingerprint"),
        (receipt_device.get("boot_id"), metadata["device"]["boot_id"], "boot ID"),
        (receipt_device.get("api_level"), metadata["device"]["api_level"], "API level"),
        (receipt_device.get("abi"), metadata["device"]["abi"], "ABI"),
        (receipt_device.get("avd_name"), metadata["device"]["avd_name"], "AVD name"),
        (receipt_device.get("system_image"), metadata["device"]["system_image"], "system image"),
        (receipt_device.get("kvm"), metadata["device"]["kvm"], "KVM state"),
        (receipt_device.get("renderer"), metadata["device"]["renderer"], "renderer"),
        (receipt_device.get("locale"), metadata["device"]["locale"], "locale"),
        (receipt_device.get("timezone"), metadata["device"]["timezone"], "timezone"),
        (receipt_device.get("orientation"), metadata["device"]["orientation"], "orientation"),
        (receipt_device.get("font_scale"), metadata["device"]["font_scale"], "font scale"),
        (receipt_device.get("theme"), metadata["device"]["theme"], "theme"),
        (receipt_device.get("animation_scales"), metadata["device"]["animation_scales"], "animation scales"),
        (receipt_device.get("insets_snapshot_sha256"), metadata["device"]["insets"], "insets snapshot"),
    ]
    for actual, expected, label in bindings:
        if actual != expected:
            raise ManifestError(f"runner receipt {label} does not match metadata")
    if (
        receipt_execution.get("expected_count") != 1
        or receipt_execution.get("started_count") != 1
        or receipt_execution.get("ended_count") != 1
        or receipt_execution.get("missing_expected_methods")
        or receipt_execution.get("unexpected_started_methods")
        or receipt_execution.get("missing_artifact_cases")
    ):
        raise ManifestError("primary real-route receipt is not an exact one-case completion")
    for label in ("app", "instrumentation"):
        receipt_apk = receipt_apks.get(label, {})
        apk = metadata["binaries"][label]
        for field in (
            "size_bytes", "source_sha256", "installed_sha256", "package_name",
            "version_code", "version_name", "min_sdk", "target_sdk", "signer_sha256",
            "lineage_sha256",
        ):
            if receipt_apk.get(field) != apk[field]:
                raise ManifestError(f"runner receipt {label}.{field} does not match binary metadata")
    if receipt_apks.get("instrumentation", {}).get("target_package") != metadata["binaries"]["app"]["package_name"]:
        raise ManifestError("instrumentation targetPackage does not match app package")

    case = metadata["case"]
    actual_cases = receipt.get("cases", {}).get("actual", [])
    expected_cases = receipt.get("cases", {}).get("expected", [])
    if actual_cases != expected_cases or len(actual_cases) != 1:
        raise ManifestError("primary real-route sample must execute exactly one expected case")
    receipt_case = actual_cases[0]
    case_bindings = [
        (receipt_case.get("caseId"), case["id"], "case id"),
        (receipt_case.get("testboxEligible"), case["testbox_eligible"], "Testbox eligibility"),
        (receipt_case.get("sourceBodySha256"), case["harness_source_body_sha256"], "harness body hash"),
        (receipt_case.get("routePageName"), "compose_settings_route_visual", "route page"),
        (receipt_case.get("fixtureKey"), case["fixture"], "fixture key"),
        (receipt_case.get("fixtureRevision"), case["fixture_revision"], "fixture revision"),
        (receipt_case.get("renderedGoalSha256"), case["rendered_goal_sha256"], "rendered goal"),
        (receipt_case.get("readyToken"), "settings_route_visual_ready destination=appearance fixtureKey=canonical", "ready token"),
        (receipt_case.get("realProductRoute"), True, "real product route"),
    ]
    for actual, expected, label in case_bindings:
        if actual != expected:
            raise ManifestError(f"runner receipt {label} does not match case metadata")

    by_hash: dict[str, list[dict[str, Any]]] = {}
    for artifact in artifacts:
        by_hash.setdefault(artifact["sha256"], []).append(artifact)
    test_list_hashes = {
        artifact["sha256"] for artifact in artifacts if artifact["kind"] == "test_list"
    }
    if execution["expected_test_list_sha256"] not in test_list_hashes or execution["executed_test_list_sha256"] not in test_list_hashes:
        raise ManifestError("expected/executed test-list file is missing from the bundle")
    stop_candidates = [
        artifact for artifact in by_hash.get(testbox["stop_receipt_sha256"], [])
        if artifact["kind"] == "testbox_stop_receipt"
    ]
    if len(stop_candidates) != 1:
        raise ManifestError("Testbox stop must bind exactly one raw stop receipt")
    stop_text = (evidence_dir / stop_candidates[0]["path"]).read_text(encoding="utf-8")
    if testbox["id"] not in stop_text or re.search(r"(?i)stop(?:ped|\s+completed|.*success)", stop_text) is None:
        raise ManifestError("Testbox stop receipt does not prove the exact session stopped")

    for control_name in ("known_green", "known_red"):
        control = execution["controls"][control_name]
        candidates = [
            artifact for artifact in by_hash.get(control["evidence_sha256"], [])
            if artifact["kind"] == "control_receipt"
        ]
        if len(candidates) != 1:
            raise ManifestError(f"{control_name} must bind exactly one control receipt")
        control_path = evidence_dir / candidates[0]["path"]
        control_receipt = json.loads(control_path.read_text(encoding="utf-8"))
        if control_receipt.get("schema_version") != "android-visual-runner-receipt.v3":
            raise ManifestError(f"{control_name} has an unknown runner receipt schema")
        if control_receipt.get("harness_status") != control["observed_harness_status"]:
            raise ManifestError(f"{control_name} receipt status does not match metadata")
        observed_fault = control_receipt.get("control", {}).get("injected_fault")
        if observed_fault != control["injected_fault"]:
            raise ManifestError(f"{control_name} injected fault does not match its receipt")
        control_execution = control_receipt.get("execution", {})
        if control_name == "known_green":
            if control_receipt.get("failure_reasons") or control_execution.get("expected_methods_sha256") != control_execution.get("started_methods_sha256"):
                raise ManifestError("known-green receipt is not an exact complete execution")
        else:
            if observed_fault != "expected_method_mismatch":
                raise ManifestError("known-red must inject expected_method_mismatch")
            if "expected_execution_mismatch" not in control_receipt.get("failure_reasons", []):
                raise ManifestError("known-red receipt did not catch expected execution mismatch")
            if not control_execution.get("missing_expected_methods") and not control_execution.get("unexpected_started_methods"):
                raise ManifestError("known-red receipt has no factual expected/executed mismatch")

    case_id = metadata["case"]["id"]
    required_case_files = {
        f"android/{case_id}.png",
        f"android/{case_id}.metadata.json",
    }
    artifact_paths = {artifact["path"] for artifact in artifacts}
    if not required_case_files <= artifact_paths:
        raise ManifestError("real-route sample is missing its raw full-frame PNG or metadata")
    png_path = evidence_dir / f"android/{case_id}.png"
    width, height, _ = decode_png_identity(png_path)
    case_metadata = json.loads(
        (evidence_dir / f"android/{case_id}.metadata.json").read_text(encoding="utf-8")
    )
    route = case_metadata.get("route", {})
    viewport = case_metadata.get("viewport", {})
    crop = case_metadata.get("crop", {})
    capture = case_metadata.get("capture", {})
    diagnostics = case_metadata.get("diagnostics", {})
    metadata_bindings = [
        (case_metadata.get("caseId"), case_id, "case metadata id"),
        (case_metadata.get("provider"), "android", "provider"),
        (case_metadata.get("providerType"), case["provider_source"], "provider source"),
        (route.get("pageName"), "compose_settings_route_visual", "real route page"),
        (route.get("fixtureKey"), case["fixture"], "fixture key"),
        (route.get("fixtureRevision"), case["fixture_revision"], "fixture revision"),
        (route.get("renderedGoalSha256"), case["rendered_goal_sha256"], "rendered goal"),
        (route.get("realProductRoute"), True, "real-route marker"),
        (viewport.get("width"), metadata["device"]["viewport"]["width_px"], "PNG viewport width"),
        (viewport.get("height"), metadata["device"]["viewport"]["height_px"], "PNG viewport height"),
        (diagnostics.get("displayWidthPx"), metadata["device"]["viewport"]["width_px"], "display width"),
        (diagnostics.get("displayHeightPx"), metadata["device"]["viewport"]["height_px"], "display height"),
        (diagnostics.get("densityDpi"), metadata["device"]["viewport"]["density_dpi"], "display density"),
        (diagnostics.get("targetPackage"), metadata["binaries"]["app"]["package_name"], "target package"),
    ]
    for actual, expected, label in metadata_bindings:
        if actual != expected:
            raise ManifestError(f"{label} is not bound to the manifest")
    if (width, height) != (viewport.get("width"), viewport.get("height")):
        raise ManifestError("decoded PNG dimensions do not match capture metadata")
    if viewport.get("density") != 1:
        raise ManifestError("capture metadata viewport density must remain provider pixel density 1")
    if crop.get("contract") != "viewport-bounds" or crop.get("rect") != {"x": 0, "y": 0, "width": 390, "height": 844}:
        raise ManifestError("case crop does not match the frozen full-frame contract")
    if capture.get("readyTokenObserved") is not True or capture.get("stableFrameCount", 0) < 2:
        raise ManifestError("case did not prove ready-token plus stable-frame settle oracle")
    if case_metadata.get("clampedToShortWindow") is not False:
        raise ManifestError("clamped/short-window capture is not admissible")
    if not isinstance(diagnostics.get("processId"), int) or diagnostics["processId"] <= 0:
        raise ManifestError("case metadata lacks a factual app process ID")
    if not isinstance(diagnostics.get("processUid"), int) or diagnostics["processUid"] <= 0:
        raise ManifestError("case metadata lacks a factual app process UID")

    capture_identity = case_metadata.get("captureIdentity", {})
    require_keys(
        capture_identity,
        {"attempt", "generation", "bootId", "capturedAtEpochMs", "capturedAtMonotonicNs"},
        "case captureIdentity",
    )
    generation = capture_identity["generation"]
    captured_epoch_ms = capture_identity["capturedAtEpochMs"]
    captured_monotonic_ns = capture_identity["capturedAtMonotonicNs"]
    if capture_identity["attempt"] != execution["attempt"]:
        raise ManifestError("case capture attempt does not match the runner attempt")
    if not isinstance(generation, str) or re.fullmatch(r"[0-9a-f]{32}", generation) is None:
        raise ManifestError("case capture generation is not a 128-bit lowercase token")
    if capture_identity["bootId"] != metadata["device"]["boot_id"]:
        raise ManifestError("case capture boot ID does not match the runner device")
    if not isinstance(captured_epoch_ms, int) or not isinstance(captured_monotonic_ns, int):
        raise ManifestError("case capture timestamps must be integers")
    if case_metadata.get("capturedAt") != str(captured_epoch_ms):
        raise ManifestError("case capturedAt does not mirror capturedAtEpochMs")

    start_epoch_ms = receipt_timeline.get("started_at_epoch_ms")
    finish_epoch_ms = receipt_timeline.get("finished_at_epoch_ms")
    host_start_monotonic_ns = receipt_timeline.get("host_started_monotonic_ns")
    host_finish_monotonic_ns = receipt_timeline.get("host_finished_monotonic_ns")
    device_start_monotonic_ns = receipt_timeline.get("device_started_monotonic_ns")
    device_finish_monotonic_ns = receipt_timeline.get("device_finished_monotonic_ns")
    if not all(isinstance(value, int) and value > 0 for value in (
        start_epoch_ms,
        finish_epoch_ms,
        host_start_monotonic_ns,
        host_finish_monotonic_ns,
        device_start_monotonic_ns,
        device_finish_monotonic_ns,
    )):
        raise ManifestError("runner receipt lacks factual wall/host/device timing")
    if host_start_monotonic_ns > host_finish_monotonic_ns:
        raise ManifestError("runner host monotonic timeline runs backwards")
    if device_start_monotonic_ns > device_finish_monotonic_ns:
        raise ManifestError("runner device monotonic timeline runs backwards")
    if not (start_epoch_ms <= captured_epoch_ms <= finish_epoch_ms):
        raise ManifestError("case capture wall time is outside the attempt window")
    if not (device_start_monotonic_ns <= captured_monotonic_ns <= device_finish_monotonic_ns):
        raise ManifestError("case capture device monotonic time is outside the attempt window")
    for utc_value, epoch_ms, label in (
        (receipt_timeline.get("started_at_utc"), start_epoch_ms, "start"),
        (receipt_timeline.get("finished_at_utc"), finish_epoch_ms, "finish"),
    ):
        parsed_utc = datetime.fromisoformat(require_utc(utc_value, f"runner {label} UTC")[:-1] + "+00:00")
        floor_ms = int(parsed_utc.timestamp()) * 1000
        if not floor_ms <= epoch_ms < floor_ms + 1000:
            raise ManifestError(f"runner {label} wall clock forms disagree")

    inventory = receipt.get("evidence_inventory")
    if not isinstance(inventory, list) or len(inventory) != 1:
        raise ManifestError("complete primary receipt must inventory exactly one case")
    item = inventory[0]
    require_keys(
        item,
        {
            "case_id", "png", "metadata", "captured_at_utc", "captured_at_epoch_ms",
            "captured_at_monotonic_ns", "boot_id", "process_id", "process_uid",
            "generation", "attempt",
        },
        "runner evidence inventory entry",
    )
    if item["case_id"] != case_id:
        raise ManifestError("runner evidence inventory case does not match the sample")
    for label, expected_path in (
        ("png", f"android/{case_id}.png"),
        ("metadata", f"android/{case_id}.metadata.json"),
    ):
        file_entry = item[label]
        require_keys(file_entry, {"path", "size_bytes", "sha256"}, f"runner inventory {label}")
        if file_entry["path"] != expected_path:
            raise ManifestError(f"runner inventory {label} path does not match the case")
        artifact = next((entry for entry in artifacts if entry["path"] == expected_path), None)
        if artifact is None:
            raise ManifestError(f"runner inventory {label} artifact is missing")
        if file_entry["size_bytes"] != artifact["size_bytes"] or file_entry["sha256"] != artifact["sha256"]:
            raise ManifestError(f"runner inventory {label} bytes do not match the bundle")
    require_utc(item["captured_at_utc"], "runner inventory captured_at_utc")
    captured_utc_ms = int(datetime.fromisoformat(item["captured_at_utc"][:-1] + "+00:00").timestamp() * 1000)
    if captured_utc_ms != captured_epoch_ms:
        raise ManifestError("runner inventory captured UTC does not match capture metadata")
    inventory_bindings = [
        (item["captured_at_epoch_ms"], captured_epoch_ms, "wall capture time"),
        (item["captured_at_monotonic_ns"], captured_monotonic_ns, "monotonic capture time"),
        (item["boot_id"], capture_identity["bootId"], "boot ID"),
        (item["process_id"], diagnostics["processId"], "process ID"),
        (item["process_uid"], diagnostics["processUid"], "process UID"),
        (item["generation"], generation, "generation"),
        (item["attempt"], execution["attempt"], "attempt"),
    ]
    for actual, expected, label in inventory_bindings:
        if actual != expected:
            raise ManifestError(f"runner inventory {label} does not match capture metadata")
    for artifact in artifacts:
        if artifact["path"] in {f"android/{case_id}.png", f"android/{case_id}.metadata.json"}:
            artifact["captured_at_utc"] = item["captured_at_utc"]

    log_scope = receipt.get("log_scope", {})
    require_keys(
        log_scope,
        {
            "target_package", "target_uid", "process_ids", "boot_id", "generation", "format",
            "allowed_tags", "line_count", "foreign_pid_lines_dropped",
            "outside_window_lines_dropped", "collection_finished_at_epoch_ms",
            "device_collection_finished_monotonic_ns",
        },
        "runner log_scope",
    )
    scoped_pids = log_scope["process_ids"]
    if (
        log_scope["target_package"] != metadata["binaries"]["app"]["package_name"]
        or log_scope["target_uid"] != diagnostics["processUid"]
        or scoped_pids != [diagnostics["processId"]]
        or log_scope["boot_id"] != capture_identity["bootId"]
        or log_scope["generation"] != generation
        or log_scope["format"] != "epoch"
        or log_scope["allowed_tags"] != ["VisualCapture", "TestRunner", "System.out", "Task139Reuse"]
    ):
        raise ManifestError("runner log scope does not match the captured app process/generation")
    collection_epoch_ms = log_scope["collection_finished_at_epoch_ms"]
    collection_monotonic_ns = log_scope["device_collection_finished_monotonic_ns"]
    if not (
        isinstance(collection_epoch_ms, int)
        and captured_epoch_ms <= collection_epoch_ms <= finish_epoch_ms
        and isinstance(collection_monotonic_ns, int)
        and captured_monotonic_ns <= collection_monotonic_ns <= device_finish_monotonic_ns
    ):
        raise ManifestError("log collection time is outside the capture attempt")

    log_artifacts = [artifact for artifact in artifacts if artifact["kind"] == "scoped_logcat"]
    if len(log_artifacts) != 1:
        raise ManifestError("primary sample must contain exactly one scoped logcat")
    log_lines = (evidence_dir / log_artifacts[0]["path"]).read_bytes().splitlines()
    observed_line_count = 0
    start_methods: list[str] = []
    end_methods: list[str] = []
    identity_token = f"attempt={execution['attempt']} generation={generation}".encode()
    for line in log_lines:
        if not line.strip() or line.startswith(b"--------- beginning of "):
            continue
        if line.startswith(b"TAIL_DROP_GUARD:"):
            raise ManifestError("complete primary sample contains a tail-drop guard")
        match = SCOPED_LOG_LINE_RE.match(line)
        if match is None:
            raise ManifestError("scoped logcat line lacks epoch/PID identity")
        observed_line_count += 1
        line_epoch_ms = int(float(match.group("epoch")) * 1000)
        line_pid = int(match.group("pid"))
        if not start_epoch_ms <= line_epoch_ms <= collection_epoch_ms:
            raise ManifestError("scoped logcat contains a line outside the attempt window")
        if line_pid not in scoped_pids:
            raise ManifestError("scoped logcat contains a foreign process ID")
        if match.group("tag") == b"VisualCapture" and b"CASE_" in line:
            if identity_token not in line:
                raise ManifestError("VisualCapture line has a stale or foreign generation")
            start_match = re.search(rb"CASE_START ([A-Za-z0-9_]+)", line)
            end_match = re.search(rb"CASE_END ([A-Za-z0-9_]+)", line)
            if start_match:
                start_methods.append(start_match.group(1).decode())
            if end_match:
                end_methods.append(end_match.group(1).decode())
    if observed_line_count != log_scope["line_count"]:
        raise ManifestError("scoped logcat line count does not match the runner receipt")
    if start_methods != [receipt_case["method"]] or end_methods != [receipt_case["method"]]:
        raise ManifestError("scoped logcat does not prove the exact case start/end lifecycle")


def validate_artifact_graph(manifest: dict[str, Any]) -> None:
    artifacts = manifest["artifacts"]
    artifact_hashes = {item["sha256"] for item in artifacts}
    if not any(item["role"] == "raw" for item in artifacts):
        raise ManifestError("bundle has no raw full-frame PNG")
    receipt_hashes = {item["sha256"] for item in artifacts if item["kind"] in {"runner_receipt", "control_receipt"}}
    execution = manifest["execution"]
    required_receipts = {
        execution["runner_receipt_sha256"],
        execution["controls"]["known_green"]["evidence_sha256"],
        execution["controls"]["known_red"]["evidence_sha256"],
    }
    if not required_receipts <= receipt_hashes:
        raise ManifestError("primary/known-green/known-red receipt hash is missing from artifacts")

    transformations = manifest["transformations"]
    output_map: dict[str, list[str]] = {}
    for index, transform in enumerate(transformations):
        require_keys(
            transform,
            {"id", "version", "input_sha256", "output_sha256", "started_at_utc", "finished_at_utc", "started_monotonic_ns", "finished_monotonic_ns", "rc"},
            f"transformations[{index}]",
        )
        for sha in transform["input_sha256"]:
            require_sha256(sha, f"transformations[{index}].input_sha256")
            if sha not in artifact_hashes:
                raise ManifestError(f"transformation input is absent: {sha}")
        output = require_sha256(transform["output_sha256"], f"transformations[{index}].output_sha256")
        if output not in artifact_hashes:
            raise ManifestError(f"transformation output is absent: {output}")
        if transform["rc"] != 0:
            raise ManifestError("derived transformation has nonzero exit code")
        require_utc(transform["started_at_utc"], f"transformations[{index}].started_at_utc")
        require_utc(transform["finished_at_utc"], f"transformations[{index}].finished_at_utc")
        if output in output_map:
            raise ManifestError(f"derived artifact has multiple producers: {output}")
        output_map[output] = list(transform["input_sha256"])

    raw_hashes = {item["sha256"] for item in artifacts if item["role"] == "raw"}

    def reaches_raw(sha: str, visiting: set[str]) -> bool:
        if sha in raw_hashes:
            return True
        if sha in visiting or sha not in output_map:
            return False
        return any(reaches_raw(parent, visiting | {sha}) for parent in output_map[sha])

    for item in artifacts:
        if item["role"] == "derived":
            if item["sha256"] not in output_map:
                raise ManifestError(f"orphaned derived artifact: {item['path']}")
            if not reaches_raw(item["sha256"], set()):
                raise ManifestError(f"derived artifact has no raw ancestor: {item['path']}")


def canonical_manifest_hash(manifest: dict[str, Any]) -> str:
    normalized = copy.deepcopy(manifest)
    normalized["integrity"]["canonical_json_sha256"] = ZERO_SHA256
    encoded = json.dumps(
        normalized, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return sha256_bytes(encoded)


def schema_type_matches(value: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "null":
        return value is None
    raise ManifestError(f"schema uses unsupported type {expected!r}")


def resolve_schema_ref(root_schema: dict[str, Any], reference: str) -> dict[str, Any]:
    if not reference.startswith("#/"):
        raise ManifestError(f"schema uses unsupported external $ref: {reference}")
    value: Any = root_schema
    for raw_part in reference[2:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if not isinstance(value, dict) or part not in value:
            raise ManifestError(f"schema contains an unresolved $ref: {reference}")
        value = value[part]
    if not isinstance(value, dict):
        raise ManifestError(f"schema $ref does not resolve to an object: {reference}")
    return value


def validate_against_schema(
    value: Any,
    schema: dict[str, Any],
    root_schema: dict[str, Any],
    path: str = "$",
) -> None:
    if "$ref" in schema:
        validate_against_schema(
            value,
            resolve_schema_ref(root_schema, schema["$ref"]),
            root_schema,
            path,
        )
        return
    if "anyOf" in schema:
        errors: list[str] = []
        for candidate in schema["anyOf"]:
            try:
                validate_against_schema(value, candidate, root_schema, path)
                break
            except ManifestError as exc:
                errors.append(str(exc))
        else:
            raise ManifestError(f"{path} did not match anyOf: {'; '.join(errors)}")
        return
    if "const" in schema and value != schema["const"]:
        raise ManifestError(f"{path} must equal schema const {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        raise ManifestError(f"{path} has unknown enum value {value!r}")
    if "type" in schema:
        expected_types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(schema_type_matches(value, expected) for expected in expected_types):
            raise ManifestError(f"{path} has wrong type; expected {expected_types}")
    if isinstance(value, dict):
        required = set(schema.get("required", []))
        missing = required - set(value)
        if missing:
            raise ManifestError(f"{path} is missing schema-required keys {sorted(missing)}")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            unknown = set(value) - set(properties)
            if unknown:
                raise ManifestError(f"{path} has schema-unknown keys {sorted(unknown)}")
        for key, child in value.items():
            if key in properties:
                validate_against_schema(child, properties[key], root_schema, f"{path}.{key}")
    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            raise ManifestError(f"{path} has fewer than {schema['minItems']} items")
        if "items" in schema:
            for index, child in enumerate(value):
                validate_against_schema(child, schema["items"], root_schema, f"{path}[{index}]")
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            raise ManifestError(f"{path} is shorter than schema minLength")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            raise ManifestError(f"{path} does not match schema pattern")
        if schema.get("format") == "date-time":
            try:
                datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ManifestError(f"{path} is not a valid date-time") from exc
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            raise ManifestError(f"{path} is below schema minimum {schema['minimum']}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    try:
        evidence_dir = args.evidence_dir.resolve(strict=True)
        output = args.output.resolve()
        metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        if not isinstance(metadata, dict):
            raise ManifestError("metadata root must be an object")
        validate_metadata(metadata)

        sums_path = evidence_dir / "SHA256SUMS"
        excluded = {output, sums_path.resolve()}
        artifacts = artifact_entries(evidence_dir, excluded)
        validate_receipt_binding(evidence_dir, metadata, artifacts)
        sums = "".join(
            f"{item['sha256']}  {item['path']}\n" for item in sorted(artifacts, key=lambda item: item["path"])
        )
        sums_bytes = sums.encode("utf-8")

        manifest = {
            "$schema": SCHEMA_REF,
            "schema_version": SCHEMA_VERSION,
            "evidence_class": "testbox_preflight",
            "terminal_eligible": False,
            "matrix_coverage": False,
            "product_verdict": "not_allowed",
            "coordinate_id": None,
            **metadata,
            "artifacts": artifacts,
            "integrity": {
                "canonicalization": "json-sort-keys-compact-utf8-with-canonical-hash-zeroed",
                "canonical_json_sha256": ZERO_SHA256,
                "schema_sha256": sha256_file(SCHEMA_PATH),
                "sha256sums_path": "SHA256SUMS",
                "sha256sums_sha256": sha256_bytes(sums_bytes),
                "sensitive_scan": {
                    "result": "pass",
                    "scanner": "streaming-byte-pattern-v1",
                    "scanned_roles": ["all evidence bytes for literal credential patterns", "all evidence paths", "scoped logcat tag grammar"],
                    "pixel_ocr": False,
                    "pixel_exemption": "only source-reviewed non-sensitive synthetic fixtures are eligible; PNG structure/content is decoded but pixels are not OCR-scanned",
                },
                "validator": {
                    "name": "build_testbox_preflight_manifest.py",
                    "version": "1",
                    "result": "pass",
                },
            },
        }
        validate_artifact_graph(manifest)
        manifest["integrity"]["canonical_json_sha256"] = canonical_manifest_hash(manifest)
        validate_against_schema(manifest, schema, schema)
        output.parent.mkdir(parents=True, exist_ok=True)
        sums_tmp = sums_path.with_name(f".{sums_path.name}.tmp")
        output_tmp = output.with_name(f".{output.name}.tmp")
        sums_tmp.write_bytes(sums_bytes)
        output_tmp.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        sums_tmp.replace(sums_path)
        output_tmp.replace(output)
        print(
            f"testbox preflight manifest: PASS artifacts={len(artifacts)} "
            f"canonical_sha256={manifest['integrity']['canonical_json_sha256']}"
        )
        return 0
    except (OSError, json.JSONDecodeError, ManifestError) as exc:
        print(f"testbox preflight manifest: REJECTED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
