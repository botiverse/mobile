#!/usr/bin/env bash
set -Eeuo pipefail

APP_APK=""
TEST_APK=""
METHOD_FILTER="${VISUAL_METHOD_FILTER:-}"
EXPECTED_METHOD_FILTER="${VISUAL_EXPECTED_METHOD_FILTER:-}"
SHARD_INDEX="${VISUAL_SHARD_INDEX:-0}"
SHARD_TOTAL="${VISUAL_SHARD_TOTAL:-1}"
ATTEMPT="${VISUAL_ATTEMPT:-1}"
RETRY="${VISUAL_RETRY:-0}"
CAPTURE_LOCALE="${VISUAL_CAPTURE_LOCALE:-}"
OUTPUT_DIR="visual-testing-results"
ADB_BIN="${ADB:-adb}"
AAPT_BIN="${AAPT:-}"
APKSIGNER_BIN="${APKSIGNER:-}"
BLACKSMITH_CLI_VERSION="${BLACKSMITH_CLI_VERSION:-}"
INJECTED_FAULT="${VISUAL_INJECTED_FAULT:-}"
DEFER_VERDICT=0
ONE_CASE_PER_PROCESS=1
EXECUTION_MODE="targeted"
TEST_CLASS="build.raft.app.visual.KmpVisualScreenshotCaptureTest"
APP_PACKAGE="build.raft.app.connectedtest"
TEST_PACKAGE="build.raft.app.connectedtest.test"
INSTRUMENTATION_RUNNER="androidx.test.runner.AndroidJUnitRunner"
DEVICE_RESULTS="/sdcard/Download/slock-visual-testing/android"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASE_MAPPER="$SCRIPT_DIR/visual_android_case_filter.py"

usage() {
  cat <<'USAGE'
Usage: scripts/run_android_visual_capture.sh \
  --app-apk <path> --test-apk <path> [options]

Options:
  --method-filter <class#method[,class#method...]>  Targeted cases. Empty means
                                                   canonical full-suite shard.
  --expected-method-filter <selectors>             Independent expectation;
                                                   controls only.
  --shard-index <n> --shard-total <n>              Canonical full-suite shard.
  --attempt <n> --retry <n>                        Attempt identity.
  --capture-locale <en|zh-CN|ja>                   Force the app resource locale.
  --blacksmith-cli-version <version>               Required for Testbox sample.
  --injected-fault <name>                          Control-only fault identity.
  --output-dir <path>                              Default: visual-testing-results.
  --single-process-targets                         Run a targeted list in one process.
  --defer-verdict                                  Persist evidence and return success;
                                                   a later workflow step must fail.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app-apk) APP_APK="$2"; shift 2 ;;
    --test-apk) TEST_APK="$2"; shift 2 ;;
    --method-filter) METHOD_FILTER="$2"; shift 2 ;;
    --expected-method-filter) EXPECTED_METHOD_FILTER="$2"; shift 2 ;;
    --shard-index) SHARD_INDEX="$2"; shift 2 ;;
    --shard-total) SHARD_TOTAL="$2"; shift 2 ;;
    --attempt) ATTEMPT="$2"; shift 2 ;;
    --retry) RETRY="$2"; shift 2 ;;
    --capture-locale) CAPTURE_LOCALE="$2"; shift 2 ;;
    --blacksmith-cli-version) BLACKSMITH_CLI_VERSION="$2"; shift 2 ;;
    --injected-fault) INJECTED_FAULT="$2"; shift 2 ;;
    --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
    --single-process-targets) ONE_CASE_PER_PROCESS=0; shift ;;
    --defer-verdict) DEFER_VERDICT=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -f "$APP_APK" ]] || { echo "App APK not found: $APP_APK" >&2; exit 2; }
[[ -f "$TEST_APK" ]] || { echo "Instrumentation APK not found: $TEST_APK" >&2; exit 2; }
for value_name in SHARD_INDEX SHARD_TOTAL ATTEMPT RETRY; do
  value="${!value_name}"
  [[ "$value" =~ ^[0-9]+$ ]] || { echo "Invalid numeric $value_name: $value" >&2; exit 2; }
done
[[ "$SHARD_TOTAL" -ge 1 && "$SHARD_INDEX" -lt "$SHARD_TOTAL" ]] || {
  echo "Shard index must satisfy 0 <= index < total" >&2
  exit 2
}
[[ "$ATTEMPT" -ge 1 ]] || { echo "Attempt must be >= 1" >&2; exit 2; }
case "$CAPTURE_LOCALE" in
  ""|en|zh-CN|ja) ;;
  *) echo "Unsupported capture locale: $CAPTURE_LOCALE (expected en, zh-CN, or ja)" >&2; exit 2 ;;
esac
OUTPUT_DIR="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$OUTPUT_DIR")"
[[ -n "$OUTPUT_DIR" && "$OUTPUT_DIR" != "/" && "$OUTPUT_DIR" != "$(pwd -P)" ]] || {
  echo "Refusing unsafe output directory: $OUTPUT_DIR" >&2
  exit 2
}

mkdir -p "$OUTPUT_DIR"
find "$OUTPUT_DIR" -mindepth 1 -delete
mkdir -p "$OUTPUT_DIR/android" "$OUTPUT_DIR/instrumentation"

STARTED_AT_EPOCH_MS="$(date -u +%s%3N)"
STARTED_AT_UTC="$(python3 -c 'from datetime import datetime,timezone; import sys; print(datetime.fromtimestamp(int(sys.argv[1])/1000,tz=timezone.utc).isoformat(timespec="seconds").replace("+00:00","Z"))' "$STARTED_AT_EPOCH_MS")"
HOST_STARTED_MONOTONIC_NS="$(python3 -c 'import time; print(time.monotonic_ns())')"
DEVICE_STARTED_MONOTONIC_NS=0
DEVICE_FINISHED_MONOTONIC_NS=0
RUN_GENERATION="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
APP_SHA256="$(sha256sum "$APP_APK" | awk '{print $1}')"
TEST_SHA256="$(sha256sum "$TEST_APK" | awk '{print $1}')"
APP_SIZE_BYTES="$(stat -c '%s' "$APP_APK")"
TEST_SIZE_BYTES="$(stat -c '%s' "$TEST_APK")"
APP_INSTALLED_SHA256=""
TEST_INSTALLED_SHA256=""
APP_PACKAGE_NAME=""
TEST_PACKAGE_NAME=""
APP_VERSION_CODE=""
APP_VERSION_NAME=""
APP_MIN_SDK=0
APP_TARGET_SDK=0
APP_SIGNER_SHA256=""
TEST_VERSION_CODE=""
TEST_VERSION_NAME=""
TEST_MIN_SDK=0
TEST_TARGET_SDK=0
TEST_SIGNER_SHA256=""
TEST_TARGET_PACKAGE=""
TARGET_UID=0
DEVICE_SERIAL=""
BOOT_ID=""
PROCESS_IDS=""
LOG_SCOPE_LINE_COUNT=0
LOG_SCOPE_FOREIGN_DROPPED=0
LOG_SCOPE_OUTSIDE_WINDOW_DROPPED=0
CAPTURE_COLLECTION_EPOCH_MS=0
DEVICE_COLLECTION_MONOTONIC_NS=0
BUILD_FINGERPRINT=""
API_LEVEL=0
ABI=""
LOCALE=""
TIMEZONE=""
WM_SIZE=""
WM_DENSITY=""
ORIENTATION=""
FONT_SCALE=""
NIGHT_MODE=""
ANIMATION_SCALES=""
INSETS_SNAPSHOT_SHA256=""
DEVICE_THEME=""
TESTBOX_AVD_NAME_VALUE="${TESTBOX_AVD_NAME:-}"
TESTBOX_SYSTEM_IMAGE_VALUE="${TESTBOX_SYSTEM_IMAGE:-}"
TESTBOX_KVM_VALUE="${TESTBOX_KVM:-}"
TESTBOX_RENDERER_VALUE="${TESTBOX_RENDERER:-}"
CURRENT_PHASE="prepare"
INSTRUMENTATION_RC=1
FAILURE_COUNT=0
PNG_COUNT=0
METADATA_COUNT=0
STARTED_COUNT=0
ENDED_COUNT=0
EXPECTED_COUNT=0
HUNG=""
MISSING_EXPECTED=""
UNEXPECTED_STARTED=""
MISSING_ARTIFACT_CASES=""
HARNESS_STATUS="harness_incomplete"
FAILURE_REASONS=()
FINALIZING=0

RC_FILE="$OUTPUT_DIR/instr-rc-${SHARD_INDEX}.txt"
LOG_FILE="$OUTPUT_DIR/logcat-shard-${SHARD_INDEX}.txt"
STARTED_FILE="$OUTPUT_DIR/started-methods-${SHARD_INDEX}.txt"
ENDED_FILE="$OUTPUT_DIR/ended-methods-${SHARD_INDEX}.txt"
EXPECTED_FILE="$OUTPUT_DIR/expected-methods-${SHARD_INDEX}.txt"
ACTUAL_CASES_FILE="$OUTPUT_DIR/actual-cases-${SHARD_INDEX}.txt"
EXPECTED_CASES_FILE="$OUTPUT_DIR/expected-cases-${SHARD_INDEX}.txt"
ACTUAL_MAP_FILE="$OUTPUT_DIR/actual-case-map-${SHARD_INDEX}.json"
EXPECTED_MAP_FILE="$OUTPUT_DIR/expected-case-map-${SHARD_INDEX}.json"
PROCESS_RECEIPT="$OUTPUT_DIR/instrumentation/processes.tsv"
DEVICE_RESET_FILE="$OUTPUT_DIR/device-reset.txt"
for path in "$RC_FILE" "$LOG_FILE" "$STARTED_FILE" "$ENDED_FILE" "$EXPECTED_FILE" \
  "$ACTUAL_CASES_FILE" "$EXPECTED_CASES_FILE" "$PROCESS_RECEIPT" "$DEVICE_RESET_FILE"; do
  : > "$path"
done
printf '%s\n' "$INSTRUMENTATION_RC" > "$RC_FILE"

REPOSITORY="$(git remote get-url origin 2>/dev/null || true)"
SOURCE_COMMIT_SHA="$(git rev-parse HEAD 2>/dev/null || true)"
SOURCE_TREE_SHA="$(git rev-parse HEAD^{tree} 2>/dev/null || true)"
SOURCE_DIRTY=false
SOURCE_DIFF_SHA256=""
if [[ -n "$(git status --porcelain=v1 2>/dev/null || true)" ]]; then
  SOURCE_DIRTY=true
  SOURCE_DIFF_SHA256="$({ git diff --binary HEAD; git ls-files --others --exclude-standard -z | sort -z | xargs -0 -r sha256sum; } | sha256sum | awk '{print $1}')"
fi
RESOLVED_REF="${TESTBOX_RESOLVED_REF:-}"
RESOLVED_REF_SHA="${TESTBOX_RESOLVED_REF_SHA:-$SOURCE_COMMIT_SHA}"
WORKFLOW_BLOB_SHA="$(git rev-parse HEAD:.github/workflows/visual-android-testbox.yml 2>/dev/null || true)"
TESTBOX_ID_VALUE="${TESTBOX_ID:-}"
TESTBOX_ACTIONS_RUN_ID="${TESTBOX_ACTIONS_RUN_ID:-${GITHUB_RUN_ID:-}}"
TESTBOX_WORKFLOW="${TESTBOX_WORKFLOW:-}"
TESTBOX_JOB="${TESTBOX_JOB:-${GITHUB_JOB:-}}"

add_failure_reason() {
  local reason="$1"
  local existing
  for existing in "${FAILURE_REASONS[@]-}"; do
    [[ "$existing" == "$reason" ]] && return
  done
  FAILURE_REASONS+=("$reason")
}

best_effort_device_identity() {
  set +e
  DEVICE_SERIAL="$($ADB_BIN get-serialno 2>/dev/null | tr -d '\r')"
  observed_boot_id="$($ADB_BIN shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r')"
  [[ -n "$BOOT_ID" ]] || BOOT_ID="$observed_boot_id"
  BUILD_FINGERPRINT="$($ADB_BIN shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
  API_LEVEL="$($ADB_BIN shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  [[ "$API_LEVEL" =~ ^[0-9]+$ ]] || API_LEVEL=0
  ABI="$($ADB_BIN shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
  LOCALE="$($ADB_BIN shell getprop persist.sys.locale 2>/dev/null | tr -d '\r')"
  TIMEZONE="$($ADB_BIN shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')"
  WM_SIZE="$($ADB_BIN shell wm size 2>/dev/null | tr -d '\r' | tr '\n' ';')"
  WM_DENSITY="$($ADB_BIN shell wm density 2>/dev/null | tr -d '\r' | tr '\n' ';')"
  ORIENTATION="$($ADB_BIN shell settings get system user_rotation 2>/dev/null | tr -d '\r')"
  FONT_SCALE="$($ADB_BIN shell settings get system font_scale 2>/dev/null | tr -d '\r')"
  NIGHT_MODE="$($ADB_BIN shell cmd uimode night 2>/dev/null | tr -d '\r')"
  DEVICE_THEME="$(printf '%s' "$NIGHT_MODE" | grep -qi 'no' && echo light || echo unknown)"
  ANIMATION_SCALES="$($ADB_BIN shell 'settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale' 2>/dev/null | tr -d '\r' | tr '\n' ',' | sed 's/,$//')"
  INSETS_SNAPSHOT_SHA256="$($ADB_BIN shell dumpsys window insets 2>/dev/null | tr -d '\r' | sha256sum | awk '{print $1}')"
  set -e
}

read_device_monotonic_ns() {
  "$ADB_BIN" shell cat /proc/uptime 2>/dev/null | tr -d '\r' | python3 -c '
from decimal import Decimal, InvalidOperation
import sys

parts = sys.stdin.read().split()
if not parts:
    raise SystemExit(1)
try:
    value = int(Decimal(parts[0]) * 1_000_000_000)
except (InvalidOperation, ValueError):
    raise SystemExit(1)
if value <= 0:
    raise SystemExit(1)
print(value)
'
}

write_receipt() {
  local finished_at_utc finished_at_epoch_ms host_finished_monotonic_ns failure_reasons_csv
  finished_at_epoch_ms="$(date -u +%s%3N)"
  finished_at_utc="$(python3 -c 'from datetime import datetime,timezone; import sys; print(datetime.fromtimestamp(int(sys.argv[1])/1000,tz=timezone.utc).isoformat(timespec="seconds").replace("+00:00","Z"))' "$finished_at_epoch_ms")"
  host_finished_monotonic_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
  failure_reasons_csv="$(IFS=,; echo "${FAILURE_REASONS[*]-}")"
  best_effort_device_identity
  for path in "$STARTED_FILE" "$ENDED_FILE" "$EXPECTED_FILE" "$ACTUAL_CASES_FILE" "$EXPECTED_CASES_FILE"; do
    [[ -f "$path" ]] || : > "$path"
  done
  STARTED_COUNT="$(wc -l < "$STARTED_FILE" | tr -d ' ')"
  ENDED_COUNT="$(wc -l < "$ENDED_FILE" | tr -d ' ')"
  EXPECTED_COUNT="$(wc -l < "$EXPECTED_FILE" | tr -d ' ')"
  PNG_COUNT="$(find "$OUTPUT_DIR/android" -type f -name '*.png' ! -name '*.failure.png' | wc -l | tr -d ' ')"
  METADATA_COUNT="$(find "$OUTPUT_DIR/android" -type f -name '*.metadata.json' | wc -l | tr -d ' ')"
  FAILURE_COUNT="$(find "$OUTPUT_DIR/android" -type f -name '*.failure.json' | wc -l | tr -d ' ')"
  export STARTED_AT_UTC STARTED_AT_EPOCH_MS HOST_STARTED_MONOTONIC_NS DEVICE_STARTED_MONOTONIC_NS DEVICE_FINISHED_MONOTONIC_NS
  export FINISHED_AT_UTC="$finished_at_utc" FINISHED_AT_EPOCH_MS="$finished_at_epoch_ms" HOST_FINISHED_MONOTONIC_NS="$host_finished_monotonic_ns"
  export APP_APK TEST_APK APP_SHA256 TEST_SHA256 APP_INSTALLED_SHA256 TEST_INSTALLED_SHA256
  export APP_SIZE_BYTES TEST_SIZE_BYTES APP_VERSION_CODE APP_VERSION_NAME APP_MIN_SDK APP_TARGET_SDK APP_SIGNER_SHA256
  export TEST_VERSION_CODE TEST_VERSION_NAME TEST_MIN_SDK TEST_TARGET_SDK TEST_SIGNER_SHA256 TEST_TARGET_PACKAGE TARGET_UID
  export METHOD_FILTER EXPECTED_METHOD_FILTER SHARD_INDEX SHARD_TOTAL ATTEMPT RETRY CAPTURE_LOCALE ONE_CASE_PER_PROCESS EXECUTION_MODE
  export INSTRUMENTATION_RC FAILURE_COUNT PNG_COUNT METADATA_COUNT STARTED_COUNT ENDED_COUNT EXPECTED_COUNT
  export HUNG MISSING_EXPECTED UNEXPECTED_STARTED MISSING_ARTIFACT_CASES HARNESS_STATUS
  export FAILURE_REASONS_CSV="$failure_reasons_csv" CURRENT_PHASE INJECTED_FAULT
  export RUN_GENERATION PROCESS_IDS LOG_SCOPE_LINE_COUNT LOG_SCOPE_FOREIGN_DROPPED LOG_SCOPE_OUTSIDE_WINDOW_DROPPED
  export CAPTURE_COLLECTION_EPOCH_MS DEVICE_COLLECTION_MONOTONIC_NS OUTPUT_DIR
  export DEVICE_SERIAL BOOT_ID BUILD_FINGERPRINT API_LEVEL ABI LOCALE TIMEZONE WM_SIZE WM_DENSITY
  export ORIENTATION FONT_SCALE NIGHT_MODE ANIMATION_SCALES INSETS_SNAPSHOT_SHA256 DEVICE_THEME
  export TESTBOX_AVD_NAME_VALUE TESTBOX_SYSTEM_IMAGE_VALUE TESTBOX_KVM_VALUE TESTBOX_RENDERER_VALUE
  export REPOSITORY SOURCE_COMMIT_SHA SOURCE_TREE_SHA SOURCE_DIRTY SOURCE_DIFF_SHA256 RESOLVED_REF RESOLVED_REF_SHA WORKFLOW_BLOB_SHA
  export TESTBOX_ID_VALUE TESTBOX_ACTIONS_RUN_ID TESTBOX_WORKFLOW TESTBOX_JOB BLACKSMITH_CLI_VERSION
  export STARTED_FILE ENDED_FILE EXPECTED_FILE ACTUAL_CASES_FILE EXPECTED_CASES_FILE ACTUAL_MAP_FILE EXPECTED_MAP_FILE
  python3 - <<'PY' > "$OUTPUT_DIR/android-visual-runner-receipt.json"
import json
import os
import hashlib
from datetime import datetime, timezone
from pathlib import Path

def integer(name: str) -> int:
    value = os.environ.get(name, "0")
    return int(value) if value.isdigit() else 0

def digest(path_name: str) -> str:
    path = Path(path_name)
    if not path.is_file():
        return ""
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()

def load_map(path_name: str):
    path = Path(path_name)
    if not path.is_file() or not path.read_text(encoding="utf-8").strip():
        return []
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return []

def evidence_inventory(case_map):
    root = Path(os.environ["OUTPUT_DIR"])
    inventory = []
    for case in case_map:
        case_id = case.get("caseId", "")
        png = root / "android" / f"{case_id}.png"
        metadata = root / "android" / f"{case_id}.metadata.json"
        if not png.is_file() or not metadata.is_file():
            continue
        try:
            parsed = json.loads(metadata.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        identity = parsed.get("captureIdentity", {})
        captured_ms = identity.get("capturedAtEpochMs")
        captured_utc = ""
        if isinstance(captured_ms, int) and captured_ms >= 0:
            captured_utc = datetime.fromtimestamp(captured_ms / 1000, tz=timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        inventory.append({
            "case_id": case_id,
            "png": {"path": f"android/{case_id}.png", "size_bytes": png.stat().st_size, "sha256": digest(str(png))},
            "metadata": {"path": f"android/{case_id}.metadata.json", "size_bytes": metadata.stat().st_size, "sha256": digest(str(metadata))},
            "captured_at_utc": captured_utc,
            "captured_at_epoch_ms": captured_ms,
            "captured_at_monotonic_ns": identity.get("capturedAtMonotonicNs"),
            "boot_id": identity.get("bootId"),
            "process_id": parsed.get("diagnostics", {}).get("processId"),
            "process_uid": parsed.get("diagnostics", {}).get("processUid"),
            "generation": identity.get("generation"),
            "attempt": identity.get("attempt"),
            "app_locale": parsed.get("appLocale"),
            "string_desc_locale": parsed.get("stringDescLocale"),
        })
    return inventory

actual_case_map = load_map(os.environ["ACTUAL_MAP_FILE"])
expected_case_map = load_map(os.environ["EXPECTED_MAP_FILE"])
process_ids = sorted({int(value) for value in os.environ.get("PROCESS_IDS", "").split() if value.isdigit()})

payload = {
    "schema_version": "android-visual-runner-receipt.v3",
    "harness_status": os.environ["HARNESS_STATUS"],
    "failure_phase": os.environ["CURRENT_PHASE"],
    "failure_reasons": [x for x in os.environ["FAILURE_REASONS_CSV"].split(",") if x],
    "timeline": {
        "started_at_utc": os.environ["STARTED_AT_UTC"],
        "started_at_epoch_ms": integer("STARTED_AT_EPOCH_MS"),
        "host_started_monotonic_ns": integer("HOST_STARTED_MONOTONIC_NS"),
        "device_started_monotonic_ns": integer("DEVICE_STARTED_MONOTONIC_NS"),
        "finished_at_utc": os.environ["FINISHED_AT_UTC"],
        "finished_at_epoch_ms": integer("FINISHED_AT_EPOCH_MS"),
        "host_finished_monotonic_ns": integer("HOST_FINISHED_MONOTONIC_NS"),
        "device_finished_monotonic_ns": integer("DEVICE_FINISHED_MONOTONIC_NS"),
    },
    "provenance": {
        "repository": os.environ["REPOSITORY"],
        "commit_sha": os.environ["SOURCE_COMMIT_SHA"],
        "tree_sha": os.environ["SOURCE_TREE_SHA"],
        "resolved_ref": os.environ["RESOLVED_REF"],
        "resolved_ref_sha": os.environ["RESOLVED_REF_SHA"],
        "dirty": os.environ["SOURCE_DIRTY"] == "true",
        "diff_sha256": os.environ["SOURCE_DIFF_SHA256"] or None,
        "workflow_path": ".github/workflows/visual-android-testbox.yml",
        "workflow_blob_sha": os.environ["WORKFLOW_BLOB_SHA"],
        "action_pins": {
            "begin_testbox": "233448af4bfdc6fca509a7f0974411ac6d8a8043",
            "run_testbox": "5ca05834db1d3813554d1dd109e5f2087a8d7cbc",
        },
        "harness_commit_sha": os.environ["SOURCE_COMMIT_SHA"],
        "executor_commit_sha": os.environ["SOURCE_COMMIT_SHA"],
    },
    "testbox": {
        "id": os.environ["TESTBOX_ID_VALUE"],
        "actions_run_id": os.environ["TESTBOX_ACTIONS_RUN_ID"],
        "workflow": os.environ["TESTBOX_WORKFLOW"],
        "job": os.environ["TESTBOX_JOB"],
        "cli_version": os.environ["BLACKSMITH_CLI_VERSION"],
    },
    "apks": {
        "app": {
            "path": os.environ["APP_APK"], "size_bytes": integer("APP_SIZE_BYTES"),
            "source_sha256": os.environ["APP_SHA256"], "installed_sha256": os.environ["APP_INSTALLED_SHA256"],
            "package_name": "build.raft.app.connectedtest", "version_code": os.environ["APP_VERSION_CODE"],
            "version_name": os.environ["APP_VERSION_NAME"], "min_sdk": integer("APP_MIN_SDK"),
            "target_sdk": integer("APP_TARGET_SDK"), "signer_sha256": os.environ["APP_SIGNER_SHA256"],
            "lineage_sha256": None,
        },
        "instrumentation": {
            "path": os.environ["TEST_APK"], "size_bytes": integer("TEST_SIZE_BYTES"),
            "source_sha256": os.environ["TEST_SHA256"], "installed_sha256": os.environ["TEST_INSTALLED_SHA256"],
            "package_name": "build.raft.app.connectedtest.test", "version_code": os.environ["TEST_VERSION_CODE"],
            "version_name": os.environ["TEST_VERSION_NAME"], "min_sdk": integer("TEST_MIN_SDK"),
            "target_sdk": integer("TEST_TARGET_SDK"), "signer_sha256": os.environ["TEST_SIGNER_SHA256"],
            "lineage_sha256": None, "target_package": os.environ["TEST_TARGET_PACKAGE"],
        },
    },
    "cases": {"actual": actual_case_map, "expected": expected_case_map},
    "evidence_inventory": evidence_inventory(actual_case_map),
    "execution": {
        "mode": os.environ["EXECUTION_MODE"], "method_filter": os.environ["METHOD_FILTER"],
        "expected_method_filter": os.environ["EXPECTED_METHOD_FILTER"],
        "one_case_per_process": os.environ["ONE_CASE_PER_PROCESS"] == "1",
        "attempt": integer("ATTEMPT"), "retry": integer("RETRY"),
        "capture_locale": os.environ["CAPTURE_LOCALE"] or None,
        "shard_index": integer("SHARD_INDEX"), "shard_total": integer("SHARD_TOTAL"),
        "instrumentation_rc": integer("INSTRUMENTATION_RC"),
        "failure_json_count": integer("FAILURE_COUNT"), "png_count": integer("PNG_COUNT"),
        "metadata_count": integer("METADATA_COUNT"), "started_count": integer("STARTED_COUNT"),
        "ended_count": integer("ENDED_COUNT"), "expected_count": integer("EXPECTED_COUNT"),
        "started_methods_sha256": digest(os.environ["STARTED_FILE"]),
        "ended_methods_sha256": digest(os.environ["ENDED_FILE"]),
        "expected_methods_sha256": digest(os.environ["EXPECTED_FILE"]),
        "actual_cases_sha256": digest(os.environ["ACTUAL_CASES_FILE"]),
        "expected_cases_sha256": digest(os.environ["EXPECTED_CASES_FILE"]),
        "tail_drop_methods": os.environ["HUNG"].split(),
        "missing_expected_methods": os.environ["MISSING_EXPECTED"].split(),
        "unexpected_started_methods": os.environ["UNEXPECTED_STARTED"].split(),
        "missing_artifact_cases": os.environ["MISSING_ARTIFACT_CASES"].split(),
    },
    "control": {"injected_fault": os.environ["INJECTED_FAULT"] or None},
    "log_scope": {
        "target_package": "build.raft.app.connectedtest",
        "target_uid": integer("TARGET_UID"),
        "process_ids": process_ids,
        "boot_id": os.environ["BOOT_ID"],
        "generation": os.environ["RUN_GENERATION"],
        "format": "epoch",
        "allowed_tags": ["VisualCapture", "TestRunner", "System.out", "Task139Reuse"],
        "line_count": integer("LOG_SCOPE_LINE_COUNT"),
        "foreign_pid_lines_dropped": integer("LOG_SCOPE_FOREIGN_DROPPED"),
        "outside_window_lines_dropped": integer("LOG_SCOPE_OUTSIDE_WINDOW_DROPPED"),
        "collection_finished_at_epoch_ms": integer("CAPTURE_COLLECTION_EPOCH_MS"),
        "device_collection_finished_monotonic_ns": integer("DEVICE_COLLECTION_MONOTONIC_NS"),
    },
    "device": {
        "avd_name": os.environ["TESTBOX_AVD_NAME_VALUE"],
        "system_image": os.environ["TESTBOX_SYSTEM_IMAGE_VALUE"],
        "kvm": os.environ["TESTBOX_KVM_VALUE"] == "true",
        "renderer": os.environ["TESTBOX_RENDERER_VALUE"],
        "serial": os.environ["DEVICE_SERIAL"], "boot_id": os.environ["BOOT_ID"],
        "build_fingerprint": os.environ["BUILD_FINGERPRINT"], "api_level": integer("API_LEVEL"),
        "abi": os.environ["ABI"], "locale": os.environ["LOCALE"], "timezone": os.environ["TIMEZONE"],
        "wm_size": os.environ["WM_SIZE"], "wm_density": os.environ["WM_DENSITY"],
        "orientation": os.environ["ORIENTATION"], "font_scale": os.environ["FONT_SCALE"],
        "night_mode": os.environ["NIGHT_MODE"], "theme": os.environ["DEVICE_THEME"],
        "animation_scales": os.environ["ANIMATION_SCALES"],
        "insets_snapshot_sha256": os.environ["INSETS_SNAPSHOT_SHA256"],
    },
}
print(json.dumps(payload, indent=2, sort_keys=True))
PY
}

finish_early_failure() {
  local rc="$1"
  local reason="$2"
  local message="$3"
  trap - ERR
  set +e
  FINALIZING=1
  HARNESS_STATUS="harness_incomplete"
  INSTRUMENTATION_RC="$rc"
  add_failure_reason "$reason"
  printf '%s\n' "$INSTRUMENTATION_RC" > "$RC_FILE"
  printf 'HARNESS_PRECHECK_FAILURE phase=%s reason=%s message=%s\n' "$CURRENT_PHASE" "$reason" "$message" >> "$LOG_FILE"
  printf 'failure_phase=%s\nfailure_reason=%s\n' "$CURRENT_PHASE" "$reason" >> "$DEVICE_RESET_FILE"
  write_receipt
  echo "$message" >&2
  if [[ "$DEFER_VERDICT" -eq 1 ]]; then
    exit 0
  fi
  exit "$rc"
}

on_error() {
  local rc="$1"
  local line="$2"
  [[ "$FINALIZING" -eq 0 ]] || exit "$rc"
  finish_early_failure "$rc" "pre_instrumentation_${CURRENT_PHASE}" "capture preflight failed at phase=$CURRENT_PHASE line=$line rc=$rc"
}
trap 'on_error $? $LINENO' ERR

CURRENT_PHASE="resolve_case_contract"
if [[ -z "$METHOD_FILTER" ]]; then
  EXECUTION_MODE="canonical_shard"
  ONE_CASE_PER_PROCESS=0
  METHOD_FILTER="$(python3 "$CASE_MAPPER" --all --shard-index "$SHARD_INDEX" --shard-total "$SHARD_TOTAL")"
fi
[[ -n "$EXPECTED_METHOD_FILTER" ]] || EXPECTED_METHOD_FILTER="$METHOD_FILTER"
python3 "$CASE_MAPPER" --selectors "$METHOD_FILTER" --format json > "$ACTUAL_MAP_FILE"
python3 "$CASE_MAPPER" --selectors "$EXPECTED_METHOD_FILTER" --format json > "$EXPECTED_MAP_FILE"
python3 "$CASE_MAPPER" --selectors "$METHOD_FILTER" --format case-ids > "$ACTUAL_CASES_FILE"
python3 "$CASE_MAPPER" --selectors "$EXPECTED_METHOD_FILTER" --format case-ids > "$EXPECTED_CASES_FILE"
python3 "$CASE_MAPPER" --selectors "$EXPECTED_METHOD_FILTER" --format methods | sort -u > "$EXPECTED_FILE"

for filter_value in "$METHOD_FILTER" "$EXPECTED_METHOD_FILTER"; do
  IFS=',' read -r -a requested_selectors <<< "$filter_value"
  for selector in "${requested_selectors[@]}"; do
    selector="$(printf '%s' "$selector" | xargs)"
    [[ "$selector" =~ ^build\.raft\.app\.visual\.KmpVisualScreenshotCaptureTest#[A-Za-z0-9_]+$ ]] || {
      finish_early_failure 2 "invalid_selector" "Invalid visual instrumentation selector: $selector"
    }
  done
done

CURRENT_PHASE="apk_identity"
if [[ -z "$AAPT_BIN" || -z "$APKSIGNER_BIN" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    BUILD_TOOLS_DIR="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
    [[ -n "$AAPT_BIN" ]] || AAPT_BIN="$BUILD_TOOLS_DIR/aapt"
    [[ -n "$APKSIGNER_BIN" ]] || APKSIGNER_BIN="$BUILD_TOOLS_DIR/apksigner"
  fi
fi
[[ -x "$AAPT_BIN" && -x "$APKSIGNER_BIN" ]] || finish_early_failure 1 "apk_identity_tools_missing" "aapt/apksigner unavailable"

read_apk_identity() {
  local apk="$1"
  local prefix="$2"
  local badging="$OUTPUT_DIR/.${prefix}-badging.txt"
  local certs="$OUTPUT_DIR/.${prefix}-certs.txt"
  "$AAPT_BIN" dump badging "$apk" > "$badging"
  "$APKSIGNER_BIN" verify --print-certs "$apk" > "$certs"
  local package version_code version_name min_sdk target_sdk signer
  package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" "$badging" | head -n 1)"
  version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" "$badging" | head -n 1)"
  version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" "$badging" | head -n 1)"
  min_sdk="$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" "$badging" | head -n 1)"
  target_sdk="$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" "$badging" | head -n 1)"
  # apksigner prints the current signer in two forms depending on whether the
  # APK carries a rotated signing lineage and which build-tools version runs:
  #   Signer #1 certificate SHA-256 digest: ...
  #   Signer (minSdkVersion=33, maxSdkVersion=...) certificate SHA-256 digest: ...
  #   V3.1 Signer: (minSdkVersion=33, maxSdkVersion=...) certificate SHA-256 digest: ...
  # In all formats the first SHA-256 signer line is the signer Android uses on
  # the newest supported API range. Keep selecting that current signer, while
  # accepting the versioned lineage prefixes emitted by build-tools 36 and 37.
  signer="$(sed -n 's/^.*certificate SHA-256 digest:[[:space:]]*//p' "$certs" | head -n 1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
  printf -v "${prefix}_PACKAGE_NAME" '%s' "$package"
  printf -v "${prefix}_VERSION_CODE" '%s' "$version_code"
  printf -v "${prefix}_VERSION_NAME" '%s' "$version_name"
  printf -v "${prefix}_MIN_SDK" '%s' "$min_sdk"
  printf -v "${prefix}_TARGET_SDK" '%s' "$target_sdk"
  printf -v "${prefix}_SIGNER_SHA256" '%s' "$signer"
  rm -f "$badging" "$certs"
}
read_apk_identity "$APP_APK" APP
read_apk_identity "$TEST_APK" TEST
[[ "$APP_PACKAGE_NAME" == "$APP_PACKAGE" && "$TEST_PACKAGE_NAME" == "$TEST_PACKAGE" ]] || \
  finish_early_failure 1 "apk_package_mismatch" "APK package identity mismatch"
TEST_TARGET_PACKAGE="$($AAPT_BIN dump xmltree "$TEST_APK" AndroidManifest.xml | sed -n 's/.*android:targetPackage[^=]*="\([^"]*\)".*/\1/p' | head -n 1)"
[[ "$TEST_TARGET_PACKAGE" == "$APP_PACKAGE" ]] || finish_early_failure 1 "instrumentation_target_mismatch" "Instrumentation targetPackage mismatch"
[[ "$APP_SIGNER_SHA256" =~ ^[0-9a-f]{64}$ && "$TEST_SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]] || \
  finish_early_failure 1 "apk_signer_missing" "APK signer digest unavailable"
[[ "$APP_SIGNER_SHA256" == "$TEST_SIGNER_SHA256" ]] || finish_early_failure 1 "apk_signer_mismatch" "App/test signer mismatch"

CURRENT_PHASE="adb_wait"
"$ADB_BIN" wait-for-device
{
  echo "before_focus=$($ADB_BIN shell dumpsys window 2>/dev/null | tr -d '\r' | sed -n 's/^[[:space:]]*mCurrentFocus=//p' | head -n 1)"
  echo "before_ime=$($ADB_BIN shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
} > "$DEVICE_RESET_FILE"

CURRENT_PHASE="warm_device_reset"
"$ADB_BIN" shell am force-stop "$TEST_PACKAGE" || true
"$ADB_BIN" shell am force-stop "$APP_PACKAGE" || true
"$ADB_BIN" shell pm clear "$TEST_PACKAGE" >/dev/null 2>&1 || true
"$ADB_BIN" shell pm clear "$APP_PACKAGE" >/dev/null 2>&1 || true
"$ADB_BIN" shell rm -rf "$DEVICE_RESULTS"
"$ADB_BIN" shell mkdir -p "$DEVICE_RESULTS"
"$ADB_BIN" shell ime reset >/dev/null 2>&1 || true
"$ADB_BIN" shell input keyevent 4 >/dev/null 2>&1 || true
"$ADB_BIN" shell input keyevent 4 >/dev/null 2>&1 || true
"$ADB_BIN" logcat -G 16M
"$ADB_BIN" logcat -c

CURRENT_PHASE="visual_environment"
"$ADB_BIN" shell 'for i in 1 2 3 4 5; do wm size 1170x2532; sleep 1; if wm size | grep -q "Override size: 1170x2532"; then echo "wm size applied on try $i"; break; fi; wm size reset; sleep 1; done; wm size | grep -q "Override size: 1170x2532"'
"$ADB_BIN" shell wm density 480
"$ADB_BIN" shell settings put system accelerometer_rotation 0
"$ADB_BIN" shell settings put system user_rotation 0
"$ADB_BIN" shell settings put system font_scale 1.0
"$ADB_BIN" shell cmd uimode night no >/dev/null 2>&1 || true
"$ADB_BIN" shell settings put global window_animation_scale 0
"$ADB_BIN" shell settings put global transition_animation_scale 0
"$ADB_BIN" shell settings put global animator_duration_scale 0
# Keep the display awake for the whole capture run. CI emulators ship
# stay_on_while_plugged_in=0 with a finite screen_off_timeout (PR #1747's
# first-character finding), so a shard whose interaction case straddles the
# display sleep sees the keyguard/system window (package=android) hold input
# focus and eat injected gestures — the active-input-window gate then times
# out (nightly 30402296301 / proof 30496656826).
"$ADB_BIN" shell svc power stayon true || true
"$ADB_BIN" shell settings put system screen_off_timeout 2147483647 || true
"$ADB_BIN" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB_BIN" shell wm dismiss-keyguard >/dev/null 2>&1 || true
# Suppress system crash/ANR dialogs for the whole run. Chain-repro run
# 30501756752 named the input blocker: a leaked "Application Error:
# build.raft.app.connectedtest.test" system dialog stays above the fixture
# across instrumentation processes, eating every injected gesture while
# PixelCopy capture-only cases keep passing underneath — exactly the
# interaction-only failure pattern of nightly 30402296301.
"$ADB_BIN" shell settings put global hide_error_dialogs 1 || true
"$ADB_BIN" shell settings put global show_first_crash_dialog 0 >/dev/null 2>&1 || true
"$ADB_BIN" shell settings put secure show_first_crash_dialog_dev_option 0 >/dev/null 2>&1 || true
# hide_error_dialogs covers app crash dialogs but not every ANR dialog (proof
# run 30503233522 shard1 hit a launcher ANR dialog). Disable background ANR
# dialogs too; the await gate's per-poll BACK dismiss is the general backstop.
"$ADB_BIN" shell settings put secure anr_show_background 0 >/dev/null 2>&1 || true
"$ADB_BIN" shell settings put global send_action_app_error 0 >/dev/null 2>&1 || true

CURRENT_PHASE="install_app"
"$ADB_BIN" install -r "$APP_APK" | tee "$OUTPUT_DIR/app-install.txt"
CURRENT_PHASE="install_instrumentation"
"$ADB_BIN" install -r "$TEST_APK" | tee "$OUTPUT_DIR/test-install.txt"

installed_apk_sha256() {
  local package_name="$1"
  local label="$2"
  local installed_path device_hash pulled
  installed_path="$($ADB_BIN shell pm path "$package_name" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
  [[ -n "$installed_path" ]] || return 1
  device_hash="$($ADB_BIN shell sha256sum "$installed_path" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
  if [[ "$device_hash" =~ ^[0-9a-fA-F]{64}$ ]]; then
    printf '%s\n' "${device_hash,,}"
    return 0
  fi
  pulled="$OUTPUT_DIR/.${label}-installed.apk"
  "$ADB_BIN" pull "$installed_path" "$pulled" >/dev/null
  sha256sum "$pulled" | awk '{print $1}'
  rm -f "$pulled"
}

CURRENT_PHASE="verify_installed_apks"
APP_INSTALLED_SHA256="$(installed_apk_sha256 "$APP_PACKAGE" app)"
TEST_INSTALLED_SHA256="$(installed_apk_sha256 "$TEST_PACKAGE" test)"
[[ "$APP_INSTALLED_SHA256" == "$APP_SHA256" ]] || \
  finish_early_failure 1 "installed_app_hash_mismatch" "Installed app APK hash mismatch"
[[ "$TEST_INSTALLED_SHA256" == "$TEST_SHA256" ]] || \
  finish_early_failure 1 "installed_instrumentation_hash_mismatch" "Installed instrumentation APK hash mismatch"

CURRENT_PHASE="freeze_process_scope"
best_effort_device_identity
[[ -n "$BOOT_ID" ]] || finish_early_failure 1 "device_boot_id_missing" "Device boot ID unavailable"
TARGET_UID="$($ADB_BIN shell cmd package list packages -U "$APP_PACKAGE" 2>/dev/null | tr -d '\r' | awk -v package="$APP_PACKAGE" '
  $1 == "package:" package {
    for (field_index = 2; field_index <= NF; field_index++) {
      if ($field_index ~ /^uid:[0-9]+$/) {
        sub(/^uid:/, "", $field_index)
        print $field_index
        exit
      }
    }
  }
')"
[[ "$TARGET_UID" =~ ^[1-9][0-9]*$ ]] || finish_early_failure 1 "target_uid_missing" "Target package UID unavailable"

"$ADB_BIN" shell am force-stop "$TEST_PACKAGE" || true
"$ADB_BIN" shell am force-stop "$APP_PACKAGE" || true
{
  echo "after_focus=$($ADB_BIN shell dumpsys window 2>/dev/null | tr -d '\r' | sed -n 's/^[[:space:]]*mCurrentFocus=//p' | head -n 1)"
  echo "after_ime=$($ADB_BIN shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
} >> "$DEVICE_RESET_FILE"

CURRENT_PHASE="freeze_device_clock"
DEVICE_STARTED_MONOTONIC_NS="$(read_device_monotonic_ns)" || \
  finish_early_failure 1 "device_monotonic_start_missing" "Device monotonic start boundary unavailable"

run_instrumentation() {
  local label="$1"
  shift
  local stdout_file="$OUTPUT_DIR/instrumentation/${label}.txt"
  local rc
  trap - ERR
  set +e
  local locale_args=()
  if [[ -n "$CAPTURE_LOCALE" ]]; then
    locale_args=(-e capture_locale "$CAPTURE_LOCALE")
  fi
  "$ADB_BIN" shell am instrument -w \
    -e visualAttempt "$ATTEMPT" \
    -e visualGeneration "$RUN_GENERATION" \
    -e visualBootId "$BOOT_ID" \
    "${locale_args[@]}" \
    "$@" "${TEST_PACKAGE}/${INSTRUMENTATION_RUNNER}" | tee "$stdout_file"
  rc=${PIPESTATUS[0]}
  set -e
  trap 'on_error $? $LINENO' ERR
  printf '%s\t%s\t%s\n' "$label" "$rc" "$*" >> "$PROCESS_RECEIPT"
  if [[ "$rc" -ne 0 ]]; then
    INSTRUMENTATION_RC=1
  fi
}

CURRENT_PHASE="instrumentation"
INSTRUMENTATION_RC=0
if [[ "$ONE_CASE_PER_PROCESS" -eq 1 ]]; then
  IFS=',' read -r -a selectors <<< "$METHOD_FILTER"
  process_index=0
  for selector in "${selectors[@]}"; do
    selector="$(printf '%s' "$selector" | xargs)"
    [[ -n "$selector" ]] || continue
    # Each selector runs in a fresh instrumentation process, so its @BeforeClass
    # executes again. Reset the shared public artifact directory only for the
    # first process; later processes must preserve earlier PNG/metadata/timing
    # until the single evidence pull after the loop.
    reset_public_output=false
    if [[ "$process_index" -eq 0 ]]; then
      reset_public_output=true
    fi
    run_instrumentation "target-${process_index}" \
      -e visualResetPublicOutput "$reset_public_output" \
      -e class "$selector"
    process_index=$((process_index + 1))
  done
else
  run_instrumentation "${EXECUTION_MODE}-${SHARD_INDEX}" \
    -e visualResetPublicOutput true \
    -e class "$METHOD_FILTER"
fi
printf '%s\n' "$INSTRUMENTATION_RC" > "$RC_FILE"

CURRENT_PHASE="evidence_collection"
if ! "$ADB_BIN" pull "$DEVICE_RESULTS/." "$OUTPUT_DIR/android/"; then
  add_failure_reason "evidence_pull_failed"
fi
CAPTURE_COLLECTION_EPOCH_MS="$(date -u +%s%3N)"
DEVICE_FINISHED_MONOTONIC_NS="$(read_device_monotonic_ns)" || \
  finish_early_failure 1 "device_monotonic_finish_missing" "Device monotonic finish boundary unavailable"
DEVICE_COLLECTION_MONOTONIC_NS="$DEVICE_FINISHED_MONOTONIC_NS"
CURRENT_BOOT_ID="$($ADB_BIN shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r')"
if [[ "$CURRENT_BOOT_ID" != "$BOOT_ID" ]]; then
  add_failure_reason "device_boot_generation_changed"
fi

METADATA_PROCESS_IDS=""
if ! METADATA_PROCESS_IDS="$(python3 - "$OUTPUT_DIR" "$ACTUAL_CASES_FILE" "$STARTED_AT_EPOCH_MS" "$CAPTURE_COLLECTION_EPOCH_MS" "$DEVICE_STARTED_MONOTONIC_NS" "$DEVICE_FINISHED_MONOTONIC_NS" "$ATTEMPT" "$RUN_GENERATION" "$BOOT_ID" "$TARGET_UID" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
case_ids = [line.strip() for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines() if line.strip()]
start_ms, finish_ms = int(sys.argv[3]), int(sys.argv[4])
start_mono, finish_mono = int(sys.argv[5]), int(sys.argv[6])
attempt, generation, boot_id, target_uid = int(sys.argv[7]), sys.argv[8], sys.argv[9], int(sys.argv[10])
pids = set()
for case_id in case_ids:
    png = root / "android" / f"{case_id}.png"
    metadata_path = root / "android" / f"{case_id}.metadata.json"
    if not png.is_file() or not metadata_path.is_file():
        continue
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"invalid capture metadata for {case_id}: {exc}")
    identity = metadata.get("captureIdentity", {})
    diagnostics = metadata.get("diagnostics", {})
    captured_ms = identity.get("capturedAtEpochMs")
    captured_mono = identity.get("capturedAtMonotonicNs")
    process_id = diagnostics.get("processId")
    process_uid = diagnostics.get("processUid")
    checks = [
        (identity.get("attempt") == attempt, "attempt"),
        (identity.get("generation") == generation, "generation"),
        (identity.get("bootId") == boot_id, "boot ID"),
        (isinstance(captured_ms, int) and start_ms <= captured_ms <= finish_ms, "wall capture window"),
        (isinstance(captured_mono, int) and start_mono <= captured_mono <= finish_mono, "monotonic capture window"),
        (str(captured_ms) == metadata.get("capturedAt"), "capturedAt mirror"),
        (isinstance(process_id, int) and process_id > 0, "process ID"),
        (process_uid == target_uid, "process UID"),
        (diagnostics.get("targetPackage") == "build.raft.app.connectedtest", "target package"),
    ]
    for passed, label in checks:
        if not passed:
            raise SystemExit(f"capture identity {label} mismatch for {case_id}")
    pids.add(process_id)
print(" ".join(str(pid) for pid in sorted(pids)))
PY
)"; then
  add_failure_reason "artifact_provenance_incomplete"
  METADATA_PROCESS_IDS=""
fi

RAW_LOG_FILE="$OUTPUT_DIR/.raw-logcat.txt"
LOG_SCOPE_SUMMARY="$OUTPUT_DIR/.log-scope.json"
if "$ADB_BIN" logcat -d -v epoch -s VisualCapture:V TestRunner:V System.out:I Task139Reuse:I > "$RAW_LOG_FILE" && \
  python3 - "$RAW_LOG_FILE" "$LOG_FILE" "$LOG_SCOPE_SUMMARY" "$STARTED_AT_EPOCH_MS" "$CAPTURE_COLLECTION_EPOCH_MS" "$ATTEMPT" "$RUN_GENERATION" "$METADATA_PROCESS_IDS" <<'PY'
import json
import re
import sys
from pathlib import Path

raw_path, output_path, summary_path = map(Path, sys.argv[1:4])
start_ms, finish_ms = int(sys.argv[4]), int(sys.argv[5])
attempt, generation = int(sys.argv[6]), sys.argv[7]
metadata_pids = {int(value) for value in sys.argv[8].split() if value.isdigit()}
line_re = re.compile(r"^\s*(?P<epoch>[0-9]+(?:\.[0-9]+)?)\s+(?P<pid>[0-9]+)\s+[0-9]+\s+[VDIWEF]\s+(?P<tag>VisualCapture|TestRunner|System\.out|Task139Reuse)\s*:")
identity = f"attempt={attempt} generation={generation}"
parsed = []
generation_pids = set()
outside = 0
for line in raw_path.read_text(encoding="utf-8", errors="strict").splitlines():
    if not line.strip() or line.startswith("--------- beginning of "):
        parsed.append((None, None, line))
        continue
    match = line_re.match(line)
    if match is None:
        raise SystemExit(f"unparseable epoch logcat line: {line}")
    epoch_ms = int(float(match.group("epoch")) * 1000)
    pid = int(match.group("pid"))
    if not (start_ms <= epoch_ms <= finish_ms):
        outside += 1
        continue
    if match.group("tag") == "VisualCapture" and "CASE_" in line and identity in line:
        generation_pids.add(pid)
    parsed.append((epoch_ms, pid, line))
if metadata_pids - generation_pids:
    raise SystemExit(f"metadata PID lacks exact-generation CASE log: {sorted(metadata_pids - generation_pids)}")
allowed_pids = generation_pids
foreign = 0
kept = []
for epoch_ms, pid, line in parsed:
    if pid is None:
        kept.append(line)
    elif pid in allowed_pids:
        kept.append(line)
    else:
        foreign += 1
if not allowed_pids:
    raise SystemExit("no exact-generation VisualCapture process IDs were observed")
output_path.write_text("\n".join(kept) + "\n", encoding="utf-8")
summary_path.write_text(json.dumps({
    "process_ids": sorted(allowed_pids),
    "line_count": sum(1 for line in kept if line and not line.startswith("--------- beginning of ")),
    "foreign_pid_lines_dropped": foreign,
    "outside_window_lines_dropped": outside,
}) + "\n", encoding="utf-8")
PY
then
  PROCESS_IDS="$(python3 -c 'import json,sys; print(" ".join(map(str,json.load(open(sys.argv[1]))["process_ids"])))' "$LOG_SCOPE_SUMMARY")"
  LOG_SCOPE_LINE_COUNT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["line_count"])' "$LOG_SCOPE_SUMMARY")"
  LOG_SCOPE_FOREIGN_DROPPED="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["foreign_pid_lines_dropped"])' "$LOG_SCOPE_SUMMARY")"
  LOG_SCOPE_OUTSIDE_WINDOW_DROPPED="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["outside_window_lines_dropped"])' "$LOG_SCOPE_SUMMARY")"
else
  add_failure_reason "scoped_logcat_failed"
  : > "$LOG_FILE"
fi
rm -f "$RAW_LOG_FILE" "$LOG_SCOPE_SUMMARY"

grep -oE 'CASE_START [A-Za-z0-9_]+' "$LOG_FILE" 2>/dev/null | awk '{print $NF}' | sort -u > "$STARTED_FILE" || true
grep -oE 'CASE_END [A-Za-z0-9_]+' "$LOG_FILE" 2>/dev/null | awk '{print $NF}' | sort -u > "$ENDED_FILE" || true
HUNG="$(comm -23 "$STARTED_FILE" "$ENDED_FILE" | tr '\n' ' ')"
MISSING_EXPECTED="$(comm -23 "$EXPECTED_FILE" "$STARTED_FILE" | tr '\n' ' ')"
UNEXPECTED_STARTED="$(comm -13 "$EXPECTED_FILE" "$STARTED_FILE" | tr '\n' ' ')"

missing_artifact_cases=()
while IFS= read -r case_id; do
  [[ -n "$case_id" ]] || continue
  if [[ ! -s "$OUTPUT_DIR/android/${case_id}.png" || ! -s "$OUTPUT_DIR/android/${case_id}.metadata.json" ]]; then
    missing_artifact_cases+=("$case_id")
  else
    if ! python3 - "$OUTPUT_DIR/android/${case_id}.metadata.json" <<'PY'
import json, sys
metadata = json.loads(open(sys.argv[1], encoding="utf-8").read())
capture = metadata.get("capture", {})
if capture.get("readyTokenRequired") is True and (
    capture.get("readyTokenObserved") is not True or capture.get("stableFrameCount", 0) < 2
):
    raise SystemExit("required ready token/stable-frame oracle was not proven")
PY
    then
      add_failure_reason "readiness_oracle_failed"
    fi
  fi
done < "$ACTUAL_CASES_FILE"
MISSING_ARTIFACT_CASES="${missing_artifact_cases[*]-}"

FAILURE_COUNT="$(find "$OUTPUT_DIR/android" -type f -name '*.failure.json' | wc -l | tr -d ' ')"
STARTED_COUNT="$(wc -l < "$STARTED_FILE" | tr -d ' ')"
ENDED_COUNT="$(wc -l < "$ENDED_FILE" | tr -d ' ')"
EXPECTED_COUNT="$(wc -l < "$EXPECTED_FILE" | tr -d ' ')"
[[ "$INSTRUMENTATION_RC" -eq 0 ]] || add_failure_reason "instrumentation_rc"
[[ "$FAILURE_COUNT" -eq 0 ]] || add_failure_reason "failure_json"
[[ "$STARTED_COUNT" -gt 0 ]] || add_failure_reason "zero_tests"
if [[ -n "$HUNG" ]]; then
  add_failure_reason "tail_drop"
  printf 'TAIL_DROP_GUARD: shard=%s methods=%s instrumentation_rc=%s\n' "$SHARD_INDEX" "$HUNG" "$INSTRUMENTATION_RC" >> "$LOG_FILE"
fi
[[ -z "$MISSING_EXPECTED" && -z "$UNEXPECTED_STARTED" ]] || add_failure_reason "expected_execution_mismatch"
[[ -z "$MISSING_ARTIFACT_CASES" ]] || add_failure_reason "case_artifacts_incomplete"

CURRENT_PHASE="final_verdict"
if [[ "${#FAILURE_REASONS[@]}" -eq 0 ]]; then
  HARNESS_STATUS="complete"
else
  HARNESS_STATUS="harness_incomplete"
fi
trap - ERR
FINALIZING=1
write_receipt

FAILURE_REASONS_CSV="$(IFS=,; echo "${FAILURE_REASONS[*]-}")"
echo "android visual capture: status=$HARNESS_STATUS rc=$INSTRUMENTATION_RC started=$STARTED_COUNT ended=$ENDED_COUNT expected=$EXPECTED_COUNT missing_artifacts=[$MISSING_ARTIFACT_CASES] reasons=[$FAILURE_REASONS_CSV]"
if [[ "$HARNESS_STATUS" != "complete" ]]; then
  echo "Capture evidence was persisted before verdict; harness is incomplete: $FAILURE_REASONS_CSV" >&2
  [[ "$DEFER_VERDICT" -eq 1 ]] || exit 1
fi
