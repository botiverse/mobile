#!/usr/bin/env bash
# Canonical-final actual-host regression under an exact-zero egress gate.
# Extracted from test.yml (task #18) so it can be shellchecked and run locally;
# invoked by the android-canonical-final-actual-host job inside the emulator runner.
set -euo pipefail
PACKAGE=build.raft.app.connectedtest
TEST_PACKAGE=build.raft.app.connectedtest.test
TEST_CLASS=build.raft.app.shared.RealtimeCanonicalFinalActualHostConnectedTest
CHAIN="CFH_${GITHUB_RUN_ID: -8}_${GITHUB_RUN_ATTEMPT}"
TARGET_UID=""
INSTRUMENT_PID=""
LOGCAT_PID=""
cleanup() {
  set +eu
  if test -n "${LOGCAT_PID:-}"; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
  fi
  if test -n "${TARGET_UID:-}"; then
    adb shell iptables -w -D OUTPUT -m owner --uid-owner "$TARGET_UID" -j "$CHAIN"
    adb shell ip6tables -w -D OUTPUT -m owner --uid-owner "$TARGET_UID" -j "$CHAIN"
  fi
  adb shell iptables -w -F "$CHAIN"
  adb shell iptables -w -X "$CHAIN"
  adb shell ip6tables -w -F "$CHAIN"
  adb shell ip6tables -w -X "$CHAIN"
}
trap cleanup EXIT

adb wait-for-device
# `adb root` restarts adbd; right after boot the restart can race the client
# ("unable to connect for root: closed" / "device offline" — run 29831219834).
# Retry until the shell is actually root; the hard `id -u` gate below still
# fails the job if root is never reached.
for attempt in $(seq 1 60); do
  adb root >/dev/null 2>&1 || true
  adb wait-for-device >/dev/null 2>&1 || true
  if test "$(adb shell id -u 2>/dev/null | tr -d '\r')" = 0; then
    break
  fi
  sleep 1
done
adb wait-for-device
for attempt in $(seq 1 120); do
  if test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 && adb shell cmd package list packages >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb shell cmd package list packages >/dev/null
test "$(adb shell id -u | tr -d '\r')" = 0
test "$(find android-connected-test-apks -type f -name '*connectedTest.apk' -not -name '*androidTest*' | wc -l)" -eq 1
test "$(find android-connected-test-apks -type f -name '*androidTest.apk' | wc -l)" -eq 1
APP_APK="$(find android-connected-test-apks -type f -name '*connectedTest.apk' -not -name '*androidTest*' | head -n 1)"
APP_TEST_APK="$(find android-connected-test-apks -type f -name '*androidTest.apk' | head -n 1)"
AAPT_BIN="$(find "$ANDROID_HOME/build-tools" -type f -name aapt | sort -V | tail -n 1)"
test -x "$AAPT_BIN"
"$AAPT_BIN" dump badging "$APP_APK" | tee canonical-final-app-apk-badging.txt
"$AAPT_BIN" dump badging "$APP_TEST_APK" | tee canonical-final-test-apk-badging.txt
grep -Fq "package: name='$PACKAGE'" canonical-final-app-apk-badging.txt
grep -Fq "package: name='$TEST_PACKAGE'" canonical-final-test-apk-badging.txt
adb install -r "$APP_APK"
adb install -r "$APP_TEST_APK"
for attempt in $(seq 1 60); do
  if adb shell pm path "$PACKAGE" | grep -Fq package: && adb shell pm path "$TEST_PACKAGE" | grep -Fq package:; then
    break
  fi
  sleep 0.5
done
adb shell pm path "$PACKAGE" | tee canonical-final-target-package-path.txt
adb shell pm path "$TEST_PACKAGE" | tee canonical-final-test-package-path.txt
grep -Fq package: canonical-final-target-package-path.txt
grep -Fq package: canonical-final-test-package-path.txt
adb shell pm list instrumentation | tee canonical-final-instrumentation-list.txt
grep -Fq "instrumentation:$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" canonical-final-instrumentation-list.txt
adb shell dumpsys package "$PACKAGE" > canonical-final-target-package.txt
adb shell dumpsys package "$TEST_PACKAGE" > canonical-final-test-package.txt

PID_BEFORE="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
printf 'pid_before=%s\n' "$PID_BEFORE" > canonical-final-process.txt
adb shell pm clear "$PACKAGE" | tee canonical-final-pm-clear.txt
grep -Fxq Success canonical-final-pm-clear.txt
PID_AFTER_CLEAR="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
printf 'pid_after_clear=%s\n' "$PID_AFTER_CLEAR" >> canonical-final-process.txt
test -z "$PID_AFTER_CLEAR"

TARGET_UID="$(adb shell cmd package list packages -U | grep -F "package:$PACKAGE uid:" | head -n 1 | sed 's/.*uid://' | tr -d '\r')"
test -n "$TARGET_UID"
printf 'target_uid=%s\nchain=%s\n' "$TARGET_UID" "$CHAIN" > canonical-final-iptables-identity.txt
adb shell iptables -w -N "$CHAIN"
adb shell iptables -w -A "$CHAIN" -o lo -j RETURN
adb shell iptables -w -A "$CHAIN" -d 127.0.0.0/8 -j RETURN
adb shell iptables -w -A "$CHAIN" -j REJECT
adb shell iptables -w -I OUTPUT 1 -m owner --uid-owner "$TARGET_UID" -j "$CHAIN"
adb shell iptables -w -C OUTPUT -m owner --uid-owner "$TARGET_UID" -j "$CHAIN"
adb shell ip6tables -w -N "$CHAIN"
adb shell ip6tables -w -A "$CHAIN" -o lo -j RETURN
adb shell ip6tables -w -A "$CHAIN" -d ::1/128 -j RETURN
adb shell ip6tables -w -A "$CHAIN" -j REJECT
adb shell ip6tables -w -I OUTPUT 1 -m owner --uid-owner "$TARGET_UID" -j "$CHAIN"
adb shell ip6tables -w -C OUTPUT -m owner --uid-owner "$TARGET_UID" -j "$CHAIN"
adb shell iptables -w -L "$CHAIN" -n -v -x > canonical-final-iptables-before.txt
adb shell ip6tables -w -L "$CHAIN" -n -v -x > canonical-final-ip6tables-before.txt
test "$(awk '$3 == "REJECT" { print $1 " " $2 }' canonical-final-iptables-before.txt)" = "0 0"
test "$(awk '$3 == "REJECT" { print $1 " " $2 }' canonical-final-ip6tables-before.txt)" = "0 0"

# Bounded infra-abort retry (task #28 blocker #3): a lone emulator/ADB/system
# crash here must not red this required job like a real regression once the
# emulator lane is admitted to merge_group. Retry ONLY on assert exit 2
# (infra-abort); a real test failure or an egress breach fails fast. Each
# attempt resets the app (pm clear) and zeroes the egress counters (-Z; the
# UID-owner chain itself survives pm clear) so the reject-counter verification
# stays clean per attempt.
CANONICAL_FINAL_ATTEMPTS="${INSTRUMENTATION_MAX_ATTEMPTS:-3}"
CANONICAL_FINAL_BACKOFF="${INSTRUMENTATION_RETRY_BACKOFF_SECONDS:-10}"
CANONICAL_FINAL_OK=0
for run_attempt in $(seq 1 "$CANONICAL_FINAL_ATTEMPTS"); do
  adb shell pm clear "$PACKAGE" >/dev/null 2>&1 || true
  adb shell iptables -w -Z "$CHAIN" >/dev/null 2>&1 || true
  adb shell ip6tables -w -Z "$CHAIN" >/dev/null 2>&1 || true
  adb logcat -c
  adb logcat -v threadtime > canonical-final-logcat-live.txt &
  LOGCAT_PID=$!
  (set +e; adb shell am instrument -w -r -e class "$TEST_CLASS" "$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" > canonical-final-instrumentation.txt 2>&1; echo "$?" > canonical-final-instrumentation-status.txt) &
  INSTRUMENT_PID=$!
  PID_DURING=""
  for pid_probe in $(seq 1 200); do
    PID_DURING="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
    if test -n "$PID_DURING"; then
      break
    fi
    sleep 0.1
  done
  printf 'pid_during=%s\n' "$PID_DURING" >> canonical-final-process.txt
  wait "$INSTRUMENT_PID"
  INSTRUMENT_STATUS="$(cat canonical-final-instrumentation-status.txt)"
  kill "$LOGCAT_PID" 2>/dev/null || true
  wait "$LOGCAT_PID" 2>/dev/null || true
  LOGCAT_PID=""
  cat canonical-final-instrumentation.txt
  adb shell iptables -w -L "$CHAIN" -n -v -x > canonical-final-iptables-after.txt
  adb shell ip6tables -w -L "$CHAIN" -n -v -x > canonical-final-ip6tables-after.txt
  adb logcat -d -v threadtime > canonical-final-logcat.txt
  adb shell ps -A > canonical-final-process-list.txt
  adb shell pm list instrumentation > canonical-final-instrumentation-list-after.txt
  grep 'canonical_final_actual_host_trace' canonical-final-logcat.txt > canonical-final-trace.txt || true
  # Classify without aborting (script is set -e): only exit 2 is a retryable
  # infra-abort. Shared min-count contract fail-closes on failure markers,
  # skipped/ignored/assumption and zero-test runs; the class owns 3 tests.
  set +e
  scripts/ci/assert-instrumentation-ok.sh canonical-final-instrumentation.txt 3
  CANONICAL_FINAL_ASSERT_RC=$?
  set -e
  if test "$CANONICAL_FINAL_ASSERT_RC" -eq 2; then
    cp -f canonical-final-instrumentation.txt "canonical-final-instrumentation.txt.attempt-${run_attempt}" 2>/dev/null || true
    echo "::warning::canonical-final infra-abort on attempt ${run_attempt}/${CANONICAL_FINAL_ATTEMPTS} (instrument_status=${INSTRUMENT_STATUS}); evidence archived" >&2
    if test "$run_attempt" -lt "$CANONICAL_FINAL_ATTEMPTS" && test "$CANONICAL_FINAL_BACKOFF" -gt 0; then
      sleep "$CANONICAL_FINAL_BACKOFF"
    fi
    continue
  fi
  # Not an infra-abort — enforce every real gate, fail-fast on any breach.
  if test -n "$PID_BEFORE"; then
    test "$PID_DURING" != "$PID_BEFORE"
  fi
  test -n "$PID_DURING"
  test "$INSTRUMENT_STATUS" = 0
  test "$CANONICAL_FINAL_ASSERT_RC" -eq 0
  test "$(awk '$3 == "REJECT" { print $1 " " $2 }' canonical-final-iptables-after.txt)" = "0 0"
  test "$(awk '$3 == "REJECT" { print $1 " " $2 }' canonical-final-ip6tables-after.txt)" = "0 0"
  CANONICAL_FINAL_OK=1
  break
done
test "$CANONICAL_FINAL_OK" = 1

python3 - <<'PY'
import hashlib
import json

marker = "canonical_final_actual_host_trace payload="
groups = {}
with open("canonical-final-trace.txt", encoding="utf-8") as source:
    for raw in source:
        start = raw.index(marker) + len(marker)
        end = raw.rindex(" sha256=")
        payload_text = raw[start:end]
        digest = raw[end + len(" sha256="):].strip()
        assert len(payload_text.encode("utf-8")) <= 3000
        assert "host-access-" not in payload_text
        assert "host-refresh-" not in payload_text
        assert "authorization" not in payload_text.lower()
        assert hashlib.sha256(payload_text.encode()).hexdigest() == digest
        payload = json.loads(payload_text)
        groups.setdefault(payload["runNonce"], []).append((payload, digest))
assert len(groups) == 1, groups.keys()
entries = next(iter(groups.values()))
assert [entry[0]["seq"] for entry in entries] == list(range(1, len(entries) + 1))
terminals = [entry for entry in entries if entry[0]["terminal"]]
assert len(terminals) == 1 and entries[-1] == terminals[0]
phases = entries[:-1]
terminal = terminals[0][0]
assert terminal["success"] is True
assert terminal["phaseCount"] == len(phases)
assert terminal["lastSeq"] == len(phases)
chain = "|".join(entry[1] for entry in phases)
assert hashlib.sha256(chain.encode()).hexdigest() == terminal["chainSha256"]
PY
