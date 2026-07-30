#!/usr/bin/env bash
# Checked-in blocker teeth for the OHOS resource receipt (task #91).
#
# Proves two fail-close guarantees on the real scripts (not mocks):
#   1. crash-artifacts.sh is secret-safe: a sensitive JVM arg placed in an
#      hs_err log (command line / VM-arg position) does NOT reach the artifact,
#      while the diagnostic crash signature (signal / problematic frame /
#      version) IS retained.
#   2. classify.sh fails closed when the compile-phase receipt is missing:
#      it signs `indeterminate` with reason `no_phase`, never a healthy/no-failure.
#
# Exits nonzero on any failure so CI can gate on it. Self-contained: builds its
# own temp fixtures, cleans up, never touches the repo or network.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
fail=0

check_absent() { # file needle label
  if grep -aqF "$2" "$1" 2>/dev/null; then
    printf 'FAIL: %s — forbidden string %q WAS present in artifact\n' "$3" "$2"
    fail=1
  else
    printf 'PASS: %s — %q absent\n' "$3" "$2"
  fi
}
check_present() { # file needle label
  if grep -aqF "$2" "$1" 2>/dev/null; then
    printf 'PASS: %s — %q retained\n' "$3" "$2"
  else
    printf 'FAIL: %s — expected diagnostic %q MISSING from artifact\n' "$3" "$2"
    fail=1
  fi
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export OHOS_RECEIPT_DIR="$work/receipt"
mkdir -p "$OHOS_RECEIPT_DIR" "$work/ws"
export GITHUB_WORKSPACE="$work/ws"
export RUNNER_TEMP="$work/runner"
mkdir -p "$RUNNER_TEMP"
# A pre.env so the daemon-log `-newer` filter has a reference (not needed for the
# hs_err path, but keeps crash-artifacts.sh on its normal branch).
: > "$OHOS_RECEIPT_DIR/pre.env"

# --- Fixture 1: hs_err with a secret in command-line / VM-arg position ---------
hs="$work/ws/hs_err_pid1234.log"
cat > "$hs" <<'HSERR'
#
# A fatal error has been detected by the Java Runtime Environment:
#
#  SIGSEGV (0xb) at pc=0x00007f1234567890 pid=1234 tid=0x00007f9876543210
#
# JRE version: OpenJDK Runtime Environment (17.0.1+12)
# Java VM: OpenJDK 64-Bit Server VM (17.0.1+12, mixed mode, TOKEN=redactiontest789)
# Problematic frame:
# C  [libc.so.6+0x3f1b0]  __strlen_avx2+0x40
#
# Core dump will be written.
#
# Command line: -DGITHUB_PACKAGES_TOKEN=supersecretvalue123 -Xss4m -Xmx4g
# VM Arguments: -DTOKEN=secretvmvalue456 jvm_args...
# Environment Variables:
# GITHUB_PACKAGES_TOKEN=supersecretvalue123
HSERR

bash "$here/crash-artifacts.sh" >/dev/null 2>&1
art="$OHOS_RECEIPT_DIR/crash-artifacts.txt"

echo "== crash-artifact secret-safety =="
check_absent  "$art" "supersecretvalue123" "command-line/env token not leaked"
check_absent  "$art" "secretvmvalue456"    "VM-arg token not leaked (allowlist)"
check_absent  "$art" "redactiontest789"    "token on an allowlisted line is redacted"
check_absent  "$art" "Command line"         "command-line section not extracted"
check_absent  "$art" "VM Arguments"         "VM-arguments section not extracted"
check_present "$art" "SIGSEGV"              "crash signal"
check_present "$art" "Problematic frame"    "problematic-frame marker"
check_present "$art" "__strlen_avx2"        "problematic frame detail"
check_present "$art" "JRE version"          "JRE version"
check_present "$art" "Java VM"              "Java VM version line (redacted, retained)"

# --- Fixture 2: classifier fails closed on missing compile-phase receipt ------
echo "== classifier no-phase fail-close =="
# Fresh receipt dir with NO compile-phase.env (simulate runner/VM died early).
export OHOS_RECEIPT_DIR="$work/receipt-nophase"
mkdir -p "$OHOS_RECEIPT_DIR"
bash "$here/classify.sh" >/dev/null 2>&1
verdict_file="$OHOS_RECEIPT_DIR/verdict.env"
verdict="$(awk -F= '$1=="verdict"{print $2}' "$verdict_file" 2>/dev/null)"
reason="$(awk -F= '$1=="reason"{sub(/^[^=]*=/,"");print}' "$verdict_file" 2>/dev/null)"
if [ "$verdict" = "indeterminate" ]; then
  printf 'PASS: no-phase verdict is indeterminate\n'
else
  printf 'FAIL: no-phase verdict=%q (expected indeterminate)\n' "$verdict"
  fail=1
fi
case "$reason" in
  *no_phase*) printf 'PASS: no-phase reason carries no_phase\n' ;;
  *) printf 'FAIL: no-phase reason=%q (expected to contain no_phase)\n' "$reason"; fail=1 ;;
esac
if grep -aq "no_failure" "$verdict_file" 2>/dev/null; then
  printf 'FAIL: verdict emitted out-of-taxonomy no_failure\n'
  fail=1
else
  printf 'PASS: no out-of-taxonomy no_failure emitted\n'
fi

echo "----------------------------------------"
if [ "$fail" -eq 0 ]; then
  echo "OHOS resource receipt self-test: ALL PASS"
else
  echo "OHOS resource receipt self-test: FAILURES DETECTED"
fi
exit "$fail"
