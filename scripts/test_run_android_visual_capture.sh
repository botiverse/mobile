#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d -t visual-runner-test-XXXXXX)"
cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

printf 'fake-app-apk\n' > "$TMP/app.apk"
printf 'fake-test-apk\n' > "$TMP/test.apk"
export FAKE_APP_SHA256="$(sha256sum "$TMP/app.apk" | awk '{print $1}')"
export FAKE_TEST_SHA256="$(sha256sum "$TMP/test.apk" | awk '{print $1}')"
export FAKE_ADB_STATE="$TMP/adb-state"
mkdir -p "$FAKE_ADB_STATE/device-results"

cat > "$TMP/adb" <<'FAKEADB'
#!/usr/bin/env bash
set -euo pipefail

state="${FAKE_ADB_STATE:?}"
cmd="${1:-}"
shift || true

case "$cmd" in
  wait-for-device) exit 0 ;;
  get-serialno) echo "emulator-5554"; exit 0 ;;
  install) echo "Success"; exit 0 ;;
  logcat)
    if [[ "${1:-}" == "-c" ]]; then
      rm -f "$state/logcat.txt"
      exit 0
    fi
    if [[ "${1:-}" == "-d" ]]; then
      cat "$state/logcat.txt" 2>/dev/null || true
    fi
    exit 0
    ;;
  pull)
    source_path="${1:-}"
    target_path="${2:-}"
    if [[ "$source_path" == "/sdcard/Download/slock-visual-testing/android/." ]]; then
      mkdir -p "$target_path"
      cp -R "$state/device-results/." "$target_path/"
      exit 0
    fi
    exit 1
    ;;
  shell)
    if [[ "$#" -eq 1 && "$1" == for\ i* ]]; then
      echo "wm size applied on try 1"
      echo "wm-size: APPLIED"
      echo "final: Physical size: 1440x3120 Override size: 1170x2532"
      exit 0
    fi
    case "${1:-}" in
      dumpsys)
        echo "  mCurrentFocus=null"
        ;;
      pm)
        case "${2:-}" in
          path)
            if [[ "${3:-}" == "build.raft.app.connectedtest" ]]; then
              echo "package:/data/app/app/base.apk"
            else
              echo "package:/data/app/test/base.apk"
            fi
            ;;
          clear) echo "Success" ;;
        esac
        ;;
      sha256sum)
        if [[ "${2:-}" == "/data/app/app/base.apk" ]]; then
          if [[ "${FAKE_BAD_APP_HASH:-0}" == "1" ]]; then
            echo "$(printf '0%.0s' {1..64})  ${2:-}"
          else
            echo "$FAKE_APP_SHA256  ${2:-}"
          fi
        else
          echo "$FAKE_TEST_SHA256  ${2:-}"
        fi
        ;;
      am)
        if [[ "${2:-}" == "instrument" ]]; then
          selector=""
          attempt=""
          generation=""
          capture_locale=""
          reset_public_output=""
          previous=""
          for arg in "$@"; do
            if [[ "$previous" == "class" ]]; then
              selector="$arg"
            elif [[ "$previous" == "visualAttempt" ]]; then
              attempt="$arg"
            elif [[ "$previous" == "visualGeneration" ]]; then
              generation="$arg"
            elif [[ "$previous" == "capture_locale" ]]; then
              capture_locale="$arg"
            elif [[ "$previous" == "visualResetPublicOutput" ]]; then
              reset_public_output="$arg"
            fi
            previous="$arg"
          done
          printf '%s\n' "$reset_public_output" >> "$state/reset-public-output-flags.txt"
          printf '%s\n' "$capture_locale" >> "$state/capture-locales.txt"
          # Model KmpVisualScreenshotCaptureTest.@BeforeClass: a fresh
          # instrumentation process clears the shared public artifact directory
          # only when the harness explicitly requests it. This reproduces the
          # real per-process deletion protocol rather than letting the fake ADB
          # accidentally preserve every target.
          if [[ "$reset_public_output" == "true" ]]; then
            rm -rf "$state/device-results"
            mkdir -p "$state/device-results"
            : > "$state/capture-timing.log"
          fi
          method="${selector##*#}"
          case "$method" in
            captureRaftUiSegmentedControlStates) case_id="library.raft-ui.segmented-control.states" ;;
            captureSettingsAppearancePage) case_id="components.settings.appearance.page" ;;
            *) case_id="case.${method}" ;;
          esac
          pid=4242
          uid=10123
          epoch="$(date -u +%s.%3N)"
          printf '%s %s %s I VisualCapture: CASE_START %s display=1170x2532 densityDpi=480 attempt=%s generation=%s\n' \
            "$epoch" "$pid" "$pid" "$method" "$attempt" "$generation" >> "$state/logcat.txt"
          if [[ "${FAKE_HANG_METHOD:-}" == "$method" ]]; then
            echo "INSTRUMENTATION_FAILED: timed out"
            exit 1
          fi
          epoch="$(date -u +%s.%3N)"
          printf '%s %s %s I VisualCapture: CASE_END %s status=OK durationMs=10 attempt=%s generation=%s\n' \
            "$epoch" "$pid" "$pid" "$method" "$attempt" "$generation" >> "$state/logcat.txt"
          printf 'START %s\nOK %s\n' "$method" "$method" >> "$state/capture-timing.log"
          cp "$state/capture-timing.log" "$state/device-results/_capture-timing.log"
          if [[ "${FAKE_MISSING_ARTIFACT_METHOD:-}" != "$method" ]]; then
            printf 'png\n' > "$state/device-results/${case_id}.png"
            captured_ms="$(date -u +%s%3N)"
            captured_mono="${FAKE_CAPTURE_MONOTONIC_NS:-150000000000}"
            locale_json=""
            case "$capture_locale" in
              en) locale_json=',"appLocale":"en","stringDescLocale":"en"' ;;
              zh-CN) locale_json=',"appLocale":"zh-Hans","stringDescLocale":"zh-CN"' ;;
              ja) locale_json=',"appLocale":"ja","stringDescLocale":"ja"' ;;
            esac
            ready_required="${FAKE_READY_TOKEN_REQUIRED:-false}"
            ready_observed="${FAKE_READY_TOKEN_OBSERVED:-false}"
            stable_frames="${FAKE_STABLE_FRAME_COUNT:-1}"
            printf '{"caseId":"%s","capturedAt":"%s"%s,"capture":{"readyTokenRequired":%s,"readyTokenObserved":%s,"stableFrameCount":%s},"captureIdentity":{"attempt":%s,"generation":"%s","bootId":"11111111-2222-3333-4444-555555555555","capturedAtEpochMs":%s,"capturedAtMonotonicNs":%s},"diagnostics":{"targetPackage":"build.raft.app.connectedtest","processId":%s,"processUid":%s}}\n' \
              "$case_id" "$captured_ms" "$locale_json" "$ready_required" "$ready_observed" "$stable_frames" "$attempt" "$generation" "$captured_ms" "$captured_mono" "$pid" "$uid" \
              > "$state/device-results/${case_id}.metadata.json"
          fi
          echo "OK (1 test)"
        fi
        ;;
      cat)
        if [[ "${2:-}" == "/proc/uptime" ]]; then
          count_file="$state/uptime-count"
          count=0
          [[ -f "$count_file" ]] && count="$(cat "$count_file")"
          count=$((count + 1))
          printf '%s\n' "$count" > "$count_file"
          if [[ "$count" -eq 1 ]]; then
            echo "${FAKE_DEVICE_START_UPTIME:-100.000000} 0.00"
          else
            echo "${FAKE_DEVICE_FINISH_UPTIME:-200.000000} 0.00"
          fi
        else
          echo "11111111-2222-3333-4444-555555555555"
        fi
        ;;
      getprop)
        case "${2:-}" in
          ro.build.fingerprint) echo "google/sdk_gphone64_x86_64/test" ;;
          ro.build.version.sdk) echo "34" ;;
          ro.product.cpu.abi) echo "x86_64" ;;
          persist.sys.locale) echo "en-US" ;;
          persist.sys.timezone) echo "UTC" ;;
        esac
        ;;
      wm)
        if [[ "${2:-}" == "size" ]]; then
          echo "Physical size: 1440x3120"
          echo "Override size: 1170x2532"
        elif [[ "${2:-}" == "density" ]]; then
          echo "Physical density: 560"
          echo "Override density: 480"
        fi
        ;;
      settings)
        if [[ "${2:-}" == "get" ]]; then
          case "${4:-}" in
            default_input_method) echo "com.android.inputmethod/.LatinIME" ;;
            user_rotation) echo "0" ;;
            font_scale) echo "1.0" ;;
          esac
        fi
        ;;
      cmd)
        if [[ "${2:-}" == "package" && "${3:-}" == "list" ]]; then
          # Android's substring filter can return the instrumentation package
          # before the exact target package. The runner must bind provenance to
          # the exact app package instead of taking the first UID in the list.
          echo "package:build.raft.app.connectedtest.test uid:10124"
          echo "package:build.raft.app.connectedtest uid:10123"
        elif [[ "${2:-}" == "uimode" && "${3:-}" == "night" ]]; then
          echo "Night mode: no"
        fi
        ;;
      *) ;;
    esac
    exit 0
    ;;
esac

echo "unsupported fake adb invocation: $cmd $*" >&2
exit 2
FAKEADB
chmod +x "$TMP/adb"

cat > "$TMP/aapt" <<'FAKEAAPT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "dump" && "$2" == "badging" ]]; then
  if [[ "$3" == *test.apk ]]; then
    echo "package: name='build.raft.app.connectedtest.test' versionCode='1' versionName='1.0'"
  else
    echo "package: name='build.raft.app.connectedtest' versionCode='1' versionName='1.0'"
  fi
  echo "sdkVersion:'26'"
  echo "targetSdkVersion:'36'"
  exit 0
fi
if [[ "$1" == "dump" && "$2" == "xmltree" ]]; then
  echo 'A: android:targetPackage(0x01010021)="build.raft.app.connectedtest" (Raw: "build.raft.app.connectedtest")'
  exit 0
fi
exit 2
FAKEAAPT
cat > "$TMP/apksigner" <<'FAKESIGNER'
#!/usr/bin/env bash
set -euo pipefail
apk="${@: -1}"
current="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
if [[ "${FAKE_SIGNER_STYLE:-rotated}" == "mismatch" && "$apk" == *test.apk ]]; then
  current="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
fi
case "${FAKE_SIGNER_STYLE:-v37}" in
  v37|mismatch)
    echo "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: $current"
    echo "V3.0 Signer: (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    ;;
  v36)
    echo "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: $current"
    echo "Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    ;;
  legacy)
    echo "Signer #1 certificate SHA-256 digest: $current"
    ;;
  missing)
    echo 'Signer #1 certificate DN: CN=Missing Digest'
    ;;
  *)
    exit 2
    ;;
esac
FAKESIGNER
chmod +x "$TMP/aapt" "$TMP/apksigner"

FILTER="build.raft.app.visual.KmpVisualScreenshotCaptureTest#captureRaftUiSegmentedControlStates,build.raft.app.visual.KmpVisualScreenshotCaptureTest#captureSettingsAppearancePage"
SINGLE_FILTER="build.raft.app.visual.KmpVisualScreenshotCaptureTest#captureRaftUiSegmentedControlStates"
COMMON_ENV=(ADB="$TMP/adb" AAPT="$TMP/aapt" APKSIGNER="$TMP/apksigner")
env "${COMMON_ENV[@]}" "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$FILTER" \
  --capture-locale ja \
  --output-dir "$TMP/pass"

[[ "$(wc -l < "$TMP/pass/instrumentation/processes.tsv" | tr -d ' ')" == "2" ]]
printf 'true\nfalse\n' > "$TMP/expected-reset-flags.txt"
cmp "$TMP/expected-reset-flags.txt" "$FAKE_ADB_STATE/reset-public-output-flags.txt"
printf 'ja\nja\n' > "$TMP/expected-capture-locales.txt"
cmp "$TMP/expected-capture-locales.txt" "$FAKE_ADB_STATE/capture-locales.txt"
grep -q '^START captureRaftUiSegmentedControlStates$' "$TMP/pass/android/_capture-timing.log"
grep -q '^START captureSettingsAppearancePage$' "$TMP/pass/android/_capture-timing.log"
python3 - "$TMP/pass/android-visual-runner-receipt.json" <<'PY'
import json
import sys

receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["schema_version"] == "android-visual-runner-receipt.v3", receipt
assert receipt["harness_status"] == "complete", receipt
assert receipt["failure_reasons"] == [], receipt
assert receipt["execution"]["one_case_per_process"] is True, receipt
assert receipt["execution"]["capture_locale"] == "ja", receipt
assert receipt["execution"]["started_count"] == 2, receipt
assert receipt["execution"]["ended_count"] == 2, receipt
assert receipt["execution"]["expected_count"] == 2, receipt
assert len(receipt["evidence_inventory"]) == 2, receipt
assert receipt["log_scope"]["process_ids"] == [4242], receipt
assert receipt["log_scope"]["target_uid"] == 10123, receipt
assert receipt["log_scope"]["generation"], receipt
assert receipt["timeline"]["device_started_monotonic_ns"] == 100_000_000_000, receipt
assert receipt["timeline"]["device_finished_monotonic_ns"] == 200_000_000_000, receipt
for item in receipt["evidence_inventory"]:
    assert item["app_locale"] == "ja", item
    assert item["string_desc_locale"] == "ja", item
    assert item["generation"] == receipt["log_scope"]["generation"], item
    assert item["boot_id"] == receipt["device"]["boot_id"], item
    assert receipt["timeline"]["started_at_epoch_ms"] <= item["captured_at_epoch_ms"] <= receipt["timeline"]["finished_at_epoch_ms"], item
    assert receipt["timeline"]["device_started_monotonic_ns"] <= item["captured_at_monotonic_ns"] <= receipt["timeline"]["device_finished_monotonic_ns"], item
assert receipt["apks"]["app"]["source_sha256"] == receipt["apks"]["app"]["installed_sha256"]
assert receipt["apks"]["instrumentation"]["source_sha256"] == receipt["apks"]["instrumentation"]["installed_sha256"]
assert receipt["apks"]["app"]["signer_sha256"] == "a" * 64, receipt
assert receipt["apks"]["instrumentation"]["signer_sha256"] == "a" * 64, receipt
PY

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$SINGLE_FILTER" \
  --single-process-targets \
  --output-dir "$TMP/single-process"
printf 'true\n' > "$TMP/expected-reset-flags.txt"
cmp "$TMP/expected-reset-flags.txt" "$FAKE_ADB_STATE/reset-public-output-flags.txt"
[[ "$(wc -l < "$TMP/single-process/instrumentation/processes.tsv" | tr -d ' ')" == "1" ]]

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" FAKE_SIGNER_STYLE=v36 FAKE_HANG_METHOD="captureSettingsAppearancePage" "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$FILTER" \
  --output-dir "$TMP/fail" \
  --defer-verdict

grep -q '^TAIL_DROP_GUARD:' "$TMP/fail/logcat-shard-0.txt"
python3 - "$TMP/fail/android-visual-runner-receipt.json" <<'PY'
import json
import sys

receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["harness_status"] == "harness_incomplete", receipt
assert "instrumentation_rc" in receipt["failure_reasons"], receipt
assert "tail_drop" in receipt["failure_reasons"], receipt
assert receipt["execution"]["tail_drop_methods"] == ["captureSettingsAppearancePage"], receipt
PY

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" FAKE_MISSING_ARTIFACT_METHOD="captureSettingsAppearancePage" "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$FILTER" \
  --output-dir "$TMP/missing-artifact" \
  --defer-verdict
python3 - "$TMP/missing-artifact/android-visual-runner-receipt.json" <<'PY'
import json, sys
receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["execution"]["started_count"] == 2, receipt
assert receipt["execution"]["ended_count"] == 2, receipt
assert receipt["execution"]["missing_artifact_cases"] == ["components.settings.appearance.page"], receipt
assert "case_artifacts_incomplete" in receipt["failure_reasons"], receipt
PY

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" \
  FAKE_READY_TOKEN_REQUIRED=true \
  FAKE_READY_TOKEN_OBSERVED=false \
  FAKE_STABLE_FRAME_COUNT=1 \
  "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$SINGLE_FILTER" \
  --output-dir "$TMP/readiness-failure" \
  --defer-verdict
python3 - "$TMP/readiness-failure/android-visual-runner-receipt.json" <<'PY'
import json, sys
receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["harness_status"] == "harness_incomplete", receipt
assert "readiness_oracle_failed" in receipt["failure_reasons"], receipt
PY

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --shard-index 0 \
  --shard-total 8 \
  --output-dir "$TMP/full-prefix" \
  --defer-verdict
python3 - "$TMP/full-prefix/android-visual-runner-receipt.json" <<'PY'
import json, sys
receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["execution"]["mode"] == "canonical_shard", receipt
assert receipt["execution"]["expected_count"] > 1, receipt
assert receipt["execution"]["started_count"] == 1, receipt
assert "expected_execution_mismatch" in receipt["failure_reasons"], receipt
PY

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" FAKE_SIGNER_STYLE=missing "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$FILTER" \
  --output-dir "$TMP/signer-missing" \
  --defer-verdict
python3 - "$TMP/signer-missing/android-visual-runner-receipt.json" <<'PY'
import json, sys
receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["harness_status"] == "harness_incomplete", receipt
assert receipt["failure_phase"] == "apk_identity", receipt
assert "apk_signer_missing" in receipt["failure_reasons"], receipt
PY

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" FAKE_SIGNER_STYLE=mismatch "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$FILTER" \
  --output-dir "$TMP/signer-mismatch" \
  --defer-verdict
python3 - "$TMP/signer-mismatch/android-visual-runner-receipt.json" <<'PY'
import json, sys
receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["harness_status"] == "harness_incomplete", receipt
assert receipt["failure_phase"] == "apk_identity", receipt
assert "apk_signer_mismatch" in receipt["failure_reasons"], receipt
PY

rm -rf "$FAKE_ADB_STATE"
mkdir -p "$FAKE_ADB_STATE/device-results"
env "${COMMON_ENV[@]}" FAKE_SIGNER_STYLE=legacy FAKE_BAD_APP_HASH=1 "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$FILTER" \
  --output-dir "$TMP/hash-failure" \
  --defer-verdict
test -s "$TMP/hash-failure/android-visual-runner-receipt.json"
test -s "$TMP/hash-failure/instr-rc-0.txt"
python3 - "$TMP/hash-failure/android-visual-runner-receipt.json" <<'PY'
import json, sys
receipt = json.load(open(sys.argv[1], encoding="utf-8"))
assert receipt["harness_status"] == "harness_incomplete", receipt
assert receipt["failure_phase"] == "verify_installed_apks", receipt
assert "installed_app_hash_mismatch" in receipt["failure_reasons"], receipt
PY

if env "${COMMON_ENV[@]}" "$ROOT/scripts/run_android_visual_capture.sh" \
  --app-apk "$TMP/app.apk" \
  --test-apk "$TMP/test.apk" \
  --method-filter "$SINGLE_FILTER" \
  --capture-locale fr \
  --output-dir "$TMP/unsupported-locale"; then
  echo "unsupported capture locale unexpectedly succeeded" >&2
  exit 1
fi

echo "run_android_visual_capture test: PASS"
