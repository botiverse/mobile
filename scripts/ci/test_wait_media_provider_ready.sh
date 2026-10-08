#!/usr/bin/env bash
# Failure-path checks for wait-media-provider-ready.sh and run-public-downloads-authority.sh
# with a stub `adb` on PATH.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# STUB_CONTENT: ok|empty_result|throws|hang  (what `content call scan_volume` prints)
# STUB_SCAN:    before|after_call|never       (when "Scanned external_primary" is logged)
# STUB_BOOT:    ok|hang                        (wait-for-device behaviour)
cat > "$work/adb" <<'STUB'
#!/usr/bin/env bash
state="$STUB_STATE"
case "$1 ${2:-} ${3:-}" in
  "shell content call")
    case "$STUB_CONTENT" in
      ok) touch "$state/called"; echo "Result: Bundle[{}]" ;;
      empty_result) echo "Result: Bundle[{}]" ;;   # e.g. "Failed to find volume" swallowed
      throws) echo "Error while accessing provider:media"; echo "java.lang.SecurityException: denied" ;;
      hang) sleep 30 ;;
    esac
    exit 0 ;;
esac
case "$1 ${2:-}" in
  "logcat -d")
    if [ "$STUB_SCAN" = before ] || { [ "$STUB_SCAN" = after_call ] && [ -e "$state/called" ]; }; then
      echo "I MediaProvider: Scanned external_primary due to REASON_MOUNTED"
    fi
    exit 0 ;;
  "wait-for-device ")
    [ "$STUB_BOOT" = hang ] && sleep 30
    exit 0 ;;
  "shell am")
    echo x >> "$state/instrumented"; echo "OK (1 test)"; exit 0 ;;
  "shell getprop") echo 1; exit 0 ;;
esac
exit 0
STUB
chmod +x "$work/adb"

wait_ready() {
  rm -rf "$work/state"; mkdir -p "$work/state"
  PATH="$work:$PATH" STUB_STATE="$work/state" STUB_CONTENT="$1" STUB_SCAN="$2" STUB_BOOT=ok \
    MEDIA_READY_TIMEOUT=3 MEDIA_READY_CALL_TIMEOUT=2 MEDIA_READY_POLL_INTERVAL=1 \
    "$here/wait-media-provider-ready.sh"
}

out="$(wait_ready hang before)" || fail "boot scan already logged must succeed: $out"
grep -q 'media_ready=logcat .*scan_call_rc=skipped' <<<"$out" || fail "logged boot scan must not need a call: $out"

out="$(wait_ready ok after_call)" || fail "demanded scan completion must succeed: $out"
grep -q 'media_ready=logcat' <<<"$out" || fail "expected logcat completion signal: $out"

# A clean Result without a logged completed scan (missing volume swallowed) is not readiness.
if out="$(wait_ready empty_result never)"; then fail "clean Result without a scan must fail: $out"; fi
grep -q 'media_ready=timeout' <<<"$out" || fail "expected timeout: $out"

if out="$(wait_ready throws never)"; then fail "provider exception with exit 0 must fail: $out"; fi
grep -q 'SecurityException' <<<"$out" || fail "timeout must report the scan call output: $out"

start=$SECONDS
if out="$(wait_ready hang never)"; then fail "hung scan call must fail: $out"; fi
[ $((SECONDS - start)) -le 8 ] || fail "hung scan call exceeded the deadline: $((SECONDS - start))s"

# Runner: a boot timeout in reboot/ab-fresh modes fails the iteration and never instruments it.
for mode in reboot ab-fresh; do
  rm -rf "$work/state" "$work/run"; mkdir -p "$work/state" "$work/run"
  set +e
  (cd "$work/run" && PATH="$work:$PATH" STUB_STATE="$work/state" STUB_CONTENT=ok STUB_SCAN=before \
    STUB_BOOT=hang BOOT_TIMEOUT=2 MEDIA_READY_TIMEOUT=3 \
    "$here/run-public-downloads-authority.sh" 31 2 "$mode" >"$work/run/out.txt" 2>&1)
  rc=$?
  set -e
  [ "$rc" -ne 0 ] || fail "$mode: boot timeout must fail the run: $(cat "$work/run/out.txt")"
  grep -q 'boot=wait_for_device_timeout rc=1' "$work/run/attachment-public-downloads-api31-repeat-summary.txt" ||
    fail "$mode: boot failure must be recorded: $(cat "$work/run/attachment-public-downloads-api31-repeat-summary.txt")"
  grep -q 'boot_failures=' "$work/run/attachment-public-downloads-api31-repeat-summary.txt" || fail "$mode: missing boot_failures"
  # reboot mode runs iteration 1 without a reboot; the failed-boot iteration 2 must not instrument.
  expected=0; [ "$mode" = reboot ] && expected=1
  ran=0; [ -e "$work/state/instrumented" ] && ran="$(wc -l < "$work/state/instrumented")"
  [ "$ran" -eq "$expected" ] || fail "$mode: instrumentation ran $ran times, expected $expected"
done

echo "ok: wait-media-provider-ready and runner failure paths"
