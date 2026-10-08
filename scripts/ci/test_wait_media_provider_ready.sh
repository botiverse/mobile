#!/usr/bin/env bash
# Failure-path checks for wait-media-provider-ready.sh with a stub `adb` on PATH.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
subject="$here/wait-media-provider-ready.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# The stub's behaviour is selected by STUB_CONTENT (ok|null|silent|throws|hang) and STUB_LOGCAT (scanned|empty|hang).
cat > "$work/adb" <<'STUB'
#!/usr/bin/env bash
if [ "$1 $2 $3" = "shell content call" ]; then
  case "$STUB_CONTENT" in
    ok) echo "Result: Bundle[{}]" ;;
    null) echo "Result: null" ;;
    silent) ;;
    throws) echo "Error while accessing provider:media"; echo "java.lang.SecurityException: Permission Denial" ;;
    hang) sleep 30 ;;
  esac
  exit 0
fi
if [ "$1 $2" = "logcat -d" ]; then
  case "$STUB_LOGCAT" in
    scanned) echo "I MediaProvider: Scanned external_primary due to REASON_MOUNTED" ;;
    empty) ;;
    hang) sleep 30 ;;
  esac
  exit 0
fi
exit 0
STUB
chmod +x "$work/adb"

run() {
  PATH="$work:$PATH" STUB_CONTENT="$1" STUB_LOGCAT="$2" \
    MEDIA_READY_TIMEOUT=3 MEDIA_READY_CALL_TIMEOUT=2 MEDIA_READY_POLL_INTERVAL=1 \
    "$subject"
}

out="$(run ok empty)" || fail "verified scan_volume Result must succeed: $out"
grep -q 'media_ready=scan_volume' <<<"$out" || fail "expected scan_volume signal: $out"

# MediaProvider's scan_volume returns a null Bundle on success.
out="$(run null empty)" || fail "Result: null must count as a completed scan: $out"
grep -q 'media_ready=scan_volume' <<<"$out" || fail "expected scan_volume signal for null result: $out"

out="$(run silent scanned)" || fail "logcat fallback must succeed: $out"
grep -q 'media_ready=logcat' <<<"$out" || fail "expected logcat signal: $out"

if out="$(run throws empty)"; then fail "provider exception with exit 0 must not count as ready: $out"; fi
grep -q 'media_ready=timeout' <<<"$out" || fail "expected timeout: $out"
grep -q 'SecurityException' <<<"$out" || fail "timeout must report the scan call output: $out"

start=$SECONDS
if out="$(run hang hang)"; then fail "hung adb calls must end in failure: $out"; fi
[ $((SECONDS - start)) -le 8 ] || fail "hung adb calls exceeded the deadline: $((SECONDS - start))s"
grep -q 'media_ready=timeout' <<<"$out" || fail "expected timeout after hang: $out"

echo "ok: wait-media-provider-ready failure paths"
