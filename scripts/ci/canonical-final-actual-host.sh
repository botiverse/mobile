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

# Bounded infra-abort retry (task #28 blockers #1/#3). Order per attempt:
# classify + archive first; then the exact-zero egress gate fail-fasts on EVERY
# attempt (a non-loopback REJECT is a real leak, and an unreadable counter cannot
# prove no leak — neither is ever retried or -Z-erased, so an abort can never mask
# a leak); only a pure infra-abort (assert exit 2) with a PROVABLY clean counter
# retries, and the pre-retry state reset (pm clear + iptables -Z) is verified, not
# best-effort. Before every attempt, a bounded fail-closed admission guard proves
# device/package/UID/instrumentation readiness plus both v4/v6 owner hooks and
# exact-zero chains; it never silently recreates missing guard state. Evidence
# capture runs under `set +e` so a device that went offline still reaches the gate
# instead of aborting mid-capture.
CANONICAL_FINAL_ATTEMPTS="${INSTRUMENTATION_MAX_ATTEMPTS:-3}"
CANONICAL_FINAL_BACKOFF="${INSTRUMENTATION_RETRY_BACKOFF_SECONDS:-10}"
CANONICAL_FINAL_OK=0
: > canonical-final-retry-receipt.txt
for run_attempt in $(seq 1 "$CANONICAL_FINAL_ATTEMPTS"); do
  scripts/ci/canonical-final-pre-attempt-guard.sh \
    "$run_attempt" "$PACKAGE" "$TEST_PACKAGE" "$TARGET_UID" "$CHAIN" \
    "canonical-final-pre-attempt-${run_attempt}.txt"
  adb logcat -v threadtime > canonical-final-logcat-live.txt &
  LOGCAT_PID=$!
  (set +e; adb shell am instrument -w -r -e class "$TEST_CLASS" "$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" > canonical-final-instrumentation.txt 2>&1; echo "$?" > canonical-final-instrumentation-status.txt) &
  INSTRUMENT_PID=$!
  PID_DURING=""
  for pid_probe in $(seq 1 200); do
    PID_DURING="$(adb shell pidof "$PACKAGE" | tr -d '\r' 2>/dev/null || true)"
    if test -n "$PID_DURING"; then
      break
    fi
    sleep 0.1
  done
  printf 'pid_during=%s\n' "$PID_DURING" >> canonical-final-process.txt
  wait "$INSTRUMENT_PID" || true
  INSTRUMENT_STATUS="$(cat canonical-final-instrumentation-status.txt 2>/dev/null || echo unknown)"
  kill "$LOGCAT_PID" 2>/dev/null || true
  wait "$LOGCAT_PID" 2>/dev/null || true
  LOGCAT_PID=""
  # Evidence capture is best-effort (set +e): a device that went offline must not
  # abort here before the egress gate and classification run.
  set +e
  cat canonical-final-instrumentation.txt
  adb shell iptables -w -L "$CHAIN" -n -v -x > canonical-final-iptables-after.txt 2>/dev/null
  adb shell ip6tables -w -L "$CHAIN" -n -v -x > canonical-final-ip6tables-after.txt 2>/dev/null
  adb logcat -d -v threadtime > canonical-final-logcat.txt 2>/dev/null
  adb shell ps -A > canonical-final-process-list.txt 2>/dev/null
  adb shell pm list instrumentation > canonical-final-instrumentation-list-after.txt 2>/dev/null
  grep 'canonical_final_actual_host_trace' canonical-final-logcat.txt > canonical-final-trace.txt 2>/dev/null
  scripts/ci/assert-instrumentation-ok.sh canonical-final-instrumentation.txt 3
  CANONICAL_FINAL_ASSERT_RC=$?
  # Egress counters are readable ONLY if the REJECT rule line is present; an
  # absent line (device offline) stays "unreadable" and is treated as a leak.
  REJECT_V4="unreadable"
  REJECT_V6="unreadable"
  if grep -q REJECT canonical-final-iptables-after.txt 2>/dev/null; then
    REJECT_V4="$(awk '$3 == "REJECT" { print $1 " " $2 }' canonical-final-iptables-after.txt)"
  fi
  if grep -q REJECT canonical-final-ip6tables-after.txt 2>/dev/null; then
    REJECT_V6="$(awk '$3 == "REJECT" { print $1 " " $2 }' canonical-final-ip6tables-after.txt)"
  fi
  set -e
  # Archive every per-attempt evidence file (not just the body) so a retry never
  # overwrites the prior attempt's logcat / counters / status — the receipt stays
  # auditable across attempts.
  for evidence_file in \
    canonical-final-instrumentation.txt \
    canonical-final-instrumentation-status.txt \
    canonical-final-logcat.txt \
    canonical-final-iptables-after.txt \
    canonical-final-ip6tables-after.txt; do
    cp -f "$evidence_file" "${evidence_file}.attempt-${run_attempt}"
  done
  printf 'attempt=%s instrument_status=%s assert_rc=%s reject_v4=[%s] reject_v6=[%s]\n' \
    "$run_attempt" "$INSTRUMENT_STATUS" "$CANONICAL_FINAL_ASSERT_RC" "$REJECT_V4" "$REJECT_V6" \
    | tee -a canonical-final-retry-receipt.txt

  # SECURITY egress gate — ALWAYS fail-fast, independent of infra classification.
  # A non-zero reject is a real egress leak; an unreadable counter cannot prove no
  # leak. Neither is retried or -Z-erased, so an abort can never mask a leak.
  if test "$REJECT_V4" != "0 0" || test "$REJECT_V6" != "0 0"; then
    echo "canonical-final egress gate FAILED on attempt ${run_attempt}: reject_v4=[${REJECT_V4}] reject_v6=[${REJECT_V6}] (leak or unprovable counter — fatal, never retried)" >&2
    exit 1
  fi

  # Egress provably clean (counter==0). Only a pure infra-abort may retry.
  if test "$CANONICAL_FINAL_ASSERT_RC" -eq 2; then
    echo "::warning::canonical-final infra-abort on attempt ${run_attempt}/${CANONICAL_FINAL_ATTEMPTS} (instrument_status=${INSTRUMENT_STATUS}, egress clean); retrying" >&2
    if test "$run_attempt" -lt "$CANONICAL_FINAL_ATTEMPTS"; then
      # Order matters (task #28 blocker): the snapshot above was clean, but if the
      # target survived the abort it could emit a REJECT during the reset window.
      # So (1) stop the target FIRST to end its egress capability, then (2) RE-READ
      # the counters and STRICTLY require 0 0 — a leak in the snapshot->stop window
      # is fatal and is NEVER -Z-erased, then (3) zero + verify, and only (4) backoff
      # once the process is gone (so nothing can leak during the sleep).
      adb shell am force-stop "$PACKAGE"
      adb shell pm clear "$PACKAGE" | tee "canonical-final-pm-clear-retry-${run_attempt}.txt"
      grep -Fxq Success "canonical-final-pm-clear-retry-${run_attempt}.txt"
      adb shell iptables -w -L "$CHAIN" -n -v -x > "canonical-final-iptables-postclear-${run_attempt}.txt"
      adb shell ip6tables -w -L "$CHAIN" -n -v -x > "canonical-final-ip6tables-postclear-${run_attempt}.txt"
      grep -q REJECT "canonical-final-iptables-postclear-${run_attempt}.txt"
      grep -q REJECT "canonical-final-ip6tables-postclear-${run_attempt}.txt"
      test "$(awk '$3 == "REJECT" { print $1 " " $2 }' "canonical-final-iptables-postclear-${run_attempt}.txt")" = "0 0"
      test "$(awk '$3 == "REJECT" { print $1 " " $2 }' "canonical-final-ip6tables-postclear-${run_attempt}.txt")" = "0 0"
      # Counters proven still-zero after the target is gone — now zero + verify.
      adb shell iptables -w -Z "$CHAIN"
      adb shell ip6tables -w -Z "$CHAIN"
      adb shell iptables -w -L "$CHAIN" -n -v -x > "canonical-final-iptables-reset-${run_attempt}.txt"
      adb shell ip6tables -w -L "$CHAIN" -n -v -x > "canonical-final-ip6tables-reset-${run_attempt}.txt"
      test "$(awk '$3 == "REJECT" { print $1 " " $2 }' "canonical-final-iptables-reset-${run_attempt}.txt")" = "0 0"
      test "$(awk '$3 == "REJECT" { print $1 " " $2 }' "canonical-final-ip6tables-reset-${run_attempt}.txt")" = "0 0"
      if test "$CANONICAL_FINAL_BACKOFF" -gt 0; then
        sleep "$CANONICAL_FINAL_BACKOFF"
      fi
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
