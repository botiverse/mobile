#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="$ROOT/scripts/ci/canonical-final-pre-attempt-guard.sh"
CANONICAL="$ROOT/scripts/ci/canonical-final-actual-host.sh"
WORKFLOW="$ROOT/.github/workflows/test.yml"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

mkdir -p "$TMP_ROOT/bin"
cat > "$TMP_ROOT/bin/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail
: "${FAKE_ADB_STATE_DIR:?}"
mkdir -p "$FAKE_ADB_STATE_DIR"
printf '%s\n' "$*" >> "$FAKE_ADB_STATE_DIR/calls.txt"

TARGET_PACKAGE=build.raft.app.connectedtest
TEST_PACKAGE=build.raft.app.connectedtest.test
TARGET_UID=10123
CHAIN=CFH_TEST_1
MODE="${FAKE_ADB_MODE:-ready}"

if test "$1" = wait-for-device; then
  exit 0
fi
if test "$1" = get-state; then
  printf 'device\n'
  exit 0
fi
if test "$1" = logcat && test "${2:-}" = -c; then
  count=0
  if test -f "$FAKE_ADB_STATE_DIR/logcat-clear-count.txt"; then
    count="$(cat "$FAKE_ADB_STATE_DIR/logcat-clear-count.txt")"
  fi
  count=$((count + 1))
  printf '%s\n' "$count" > "$FAKE_ADB_STATE_DIR/logcat-clear-count.txt"
  if test "$count" -le "${FAKE_ADB_TRANSIENT_LOGCAT_FAILURES:-0}"; then
    exit 1
  fi
  exit 0
fi
if test "$1" != shell; then
  printf 'unexpected fake adb command: %s\n' "$*" >&2
  exit 64
fi
shift
command="$*"

print_v4_output_rules() {
  printf '%s\n' '-P OUTPUT ACCEPT'
  case "$MODE" in
    missing_v4_hook) ;;
    displaced_v4_hook)
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j ACCEPT"
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN"
      ;;
    duplicate_v4_hook)
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN"
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN"
      ;;
    *) printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN" ;;
  esac
}

print_v6_output_rules() {
  printf '%s\n' '-P OUTPUT ACCEPT'
  case "$MODE" in
    missing_v6_hook) ;;
    displaced_v6_hook)
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j RETURN"
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN"
      ;;
    duplicate_v6_hook)
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN"
      printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN"
      ;;
    *) printf '%s\n' "-A OUTPUT -m owner --uid-owner $TARGET_UID -j $CHAIN" ;;
  esac
}

print_v4_chain_rules() {
  printf '%s\n' "-N $CHAIN"
  case "$MODE" in
    missing_v4_chain) ;;
    extra_v4_chain_rule)
      printf '%s\n' \
        "-A $CHAIN -o lo -j RETURN" \
        "-A $CHAIN -d 127.0.0.0/8 -j RETURN" \
        "-A $CHAIN -j ACCEPT" \
        "-A $CHAIN -j REJECT --reject-with icmp-port-unreachable"
      ;;
    reordered_v4_chain_rules)
      printf '%s\n' \
        "-A $CHAIN -d 127.0.0.0/8 -j RETURN" \
        "-A $CHAIN -o lo -j RETURN" \
        "-A $CHAIN -j REJECT --reject-with icmp-port-unreachable"
      ;;
    *)
      printf '%s\n' \
        "-A $CHAIN -o lo -j RETURN" \
        "-A $CHAIN -d 127.0.0.0/8 -j RETURN" \
        "-A $CHAIN -j REJECT --reject-with icmp-port-unreachable"
      ;;
  esac
}

print_v6_chain_rules() {
  printf '%s\n' "-N $CHAIN"
  case "$MODE" in
    missing_v6_chain) ;;
    extra_v6_chain_rule)
      printf '%s\n' \
        "-A $CHAIN -o lo -j RETURN" \
        "-A $CHAIN -d ::1/128 -j RETURN" \
        "-A $CHAIN -j ACCEPT" \
        "-A $CHAIN -j REJECT --reject-with icmp6-port-unreachable"
      ;;
    reordered_v6_chain_rules)
      printf '%s\n' \
        "-A $CHAIN -d ::1/128 -j RETURN" \
        "-A $CHAIN -o lo -j RETURN" \
        "-A $CHAIN -j REJECT --reject-with icmp6-port-unreachable"
      ;;
    *)
      printf '%s\n' \
        "-A $CHAIN -o lo -j RETURN" \
        "-A $CHAIN -d ::1/128 -j RETURN" \
        "-A $CHAIN -j REJECT --reject-with icmp6-port-unreachable"
      ;;
  esac
}

case "$command" in
  'id -u') printf '0\n' ;;
  'getprop sys.boot_completed') printf '1\n' ;;
  'cmd package list packages')
    printf 'package:%s\npackage:%s\n' "$TARGET_PACKAGE" "$TEST_PACKAGE"
    ;;
  'cmd package list packages -U')
    printf 'package:%s uid:%s\npackage:%s uid:10124\n' "$TARGET_PACKAGE" "$TARGET_UID" "$TEST_PACKAGE"
    ;;
  "pm path $TARGET_PACKAGE") printf 'package:/data/app/target/base.apk\n' ;;
  "pm path $TEST_PACKAGE") printf 'package:/data/app/test/base.apk\n' ;;
  'pm list instrumentation')
    printf 'instrumentation:%s/androidx.test.runner.AndroidJUnitRunner (target=%s)\n' "$TEST_PACKAGE" "$TARGET_PACKAGE"
    ;;
  pidof*)
    # Empty output with rc=0 proves the target is stopped.
    ;;
  'iptables -w -S OUTPUT') print_v4_output_rules ;;
  'ip6tables -w -S OUTPUT') print_v6_output_rules ;;
  "iptables -w -S $CHAIN") print_v4_chain_rules ;;
  "ip6tables -w -S $CHAIN") print_v6_chain_rules ;;
  iptables\ -w\ -C\ OUTPUT*)
    test "$MODE" != missing_v4_hook
    ;;
  ip6tables\ -w\ -C\ OUTPUT*)
    test "$MODE" != missing_v6_hook
    ;;
  iptables\ -w\ -C*)
    test "$MODE" != missing_v4_chain
    ;;
  ip6tables\ -w\ -C*)
    test "$MODE" != missing_v6_chain
    ;;
  "iptables -w -L $CHAIN -n -v -x")
    printf 'Chain %s (1 references)\n pkts bytes target prot opt in out source destination\n0 0 RETURN all -- * lo 0.0.0.0/0 0.0.0.0/0\n0 0 RETURN all -- * * 0.0.0.0/0 127.0.0.0/8\n0 0 REJECT all -- * * 0.0.0.0/0 0.0.0.0/0\n' "$CHAIN"
    ;;
  "ip6tables -w -L $CHAIN -n -v -x")
    printf 'Chain %s (1 references)\n pkts bytes target prot opt in out source destination\n0 0 RETURN all -- * lo ::/0 ::/0\n0 0 RETURN all -- * * ::/0 ::1/128\n0 0 REJECT all -- * * ::/0 ::/0\n' "$CHAIN"
    ;;
  *'am instrument'*)
    printf 'instrumentation must never run from the pre-attempt guard\n' >&2
    exit 99
    ;;
  *)
    printf 'unexpected fake adb shell command: %s\n' "$command" >&2
    exit 65
    ;;
esac
FAKE_ADB
chmod +x "$TMP_ROOT/bin/adb"

run_guard() {
  local state_dir="$1"
  local mode="$2"
  local transient_logcat_failures="$3"
  local receipt="$4"
  FAKE_ADB_STATE_DIR="$state_dir" \
    FAKE_ADB_MODE="$mode" \
    FAKE_ADB_TRANSIENT_LOGCAT_FAILURES="$transient_logcat_failures" \
    CANONICAL_FINAL_PRE_ATTEMPT_MAX_PROBES=4 \
    CANONICAL_FINAL_PRE_ATTEMPT_POLL_SECONDS=0 \
    CANONICAL_FINAL_PRE_ATTEMPT_COMMAND_TIMEOUT_SECONDS=1 \
    PATH="$TMP_ROOT/bin:$PATH" \
    "$GUARD" 1 build.raft.app.connectedtest build.raft.app.connectedtest.test 10123 CFH_TEST_1 "$receipt"
}

# Positive arm: the cited `adb logcat -c` setup hiccup is retried while the
# target is stopped, then the exact package/UID/instrumentation and both guards
# are proven before admission.
TRANSIENT_STATE="$TMP_ROOT/transient"
TRANSIENT_RECEIPT="$TMP_ROOT/transient-receipt.txt"
run_guard "$TRANSIENT_STATE" ready 2 "$TRANSIENT_RECEIPT"
grep -Fq 'probe=1 readiness=waiting' "$TRANSIENT_RECEIPT"
grep -Fq 'logcat_clear=unavailable' "$TRANSIENT_RECEIPT"
grep -Fq 'probe=3 readiness=ready logcat_clear=ready' "$TRANSIENT_RECEIPT"
grep -Fq 'observed_uid=10123 instrumentation_count=1 target_pid=stopped' "$TRANSIENT_RECEIPT"
grep -Fq 'v4_output_rules_begin' "$TRANSIENT_RECEIPT"
grep -Fq -- '-A OUTPUT -m owner --uid-owner 10123 -j CFH_TEST_1' "$TRANSIENT_RECEIPT"
grep -Fq 'v4_output_rules_end' "$TRANSIENT_RECEIPT"
grep -Fq 'v6_output_rules_begin' "$TRANSIENT_RECEIPT"
grep -Fq 'v6_output_rules_end' "$TRANSIENT_RECEIPT"
grep -Fq 'v4_chain_rules_begin' "$TRANSIENT_RECEIPT"
grep -Fq 'v4_chain_rules_end' "$TRANSIENT_RECEIPT"
grep -Fq 'v6_chain_rules_begin' "$TRANSIENT_RECEIPT"
grep -Fq 'v6_chain_rules_end' "$TRANSIENT_RECEIPT"
grep -Fq 'v4_output_hook=first_unique transfers=1' "$TRANSIENT_RECEIPT"
grep -Fq 'v6_output_hook=first_unique transfers=1' "$TRANSIENT_RECEIPT"
grep -Fq 'v4_chain_rules=exact_ordered count=3' "$TRANSIENT_RECEIPT"
grep -Fq 'v6_chain_rules=exact_ordered count=3' "$TRANSIENT_RECEIPT"
grep -Fq 'v4_chain=present v4_reject=[0 0]' "$TRANSIENT_RECEIPT"
grep -Fq 'v6_chain=present v6_reject=[0 0]' "$TRANSIENT_RECEIPT"
grep -Fxq 'status=ready' "$TRANSIENT_RECEIPT"

# Negative arms: missing, displaced, or duplicate hooks and missing, extra, or
# reordered chain rules are fatal on both protocol families while ADB remains
# healthy. The guard never repairs the ruleset and never starts instrumentation.
for mode in \
  missing_v4_hook missing_v6_hook missing_v4_chain missing_v6_chain \
  displaced_v4_hook displaced_v6_hook duplicate_v4_hook duplicate_v6_hook \
  extra_v4_chain_rule extra_v6_chain_rule \
  reordered_v4_chain_rules reordered_v6_chain_rules; do
  state_dir="$TMP_ROOT/$mode"
  receipt="$TMP_ROOT/$mode-receipt.txt"
  if run_guard "$state_dir" "$mode" 0 "$receipt"; then
    fail "$mode unexpectedly passed"
  fi
  case "$mode" in
    missing_v4_hook) reason=v4_output_hook_missing ;;
    missing_v6_hook) reason=v6_output_hook_missing ;;
    missing_v4_chain) reason=v4_loopback_rule_missing ;;
    missing_v6_chain) reason=v6_loopback_rule_missing ;;
    displaced_v4_hook) reason=v4_output_hook_not_first ;;
    displaced_v6_hook) reason=v6_output_hook_not_first ;;
    duplicate_v4_hook) reason=v4_output_hook_not_unique ;;
    duplicate_v6_hook) reason=v6_output_hook_not_unique ;;
    extra_v4_chain_rule) reason=v4_chain_rule_count_mismatch ;;
    extra_v6_chain_rule) reason=v6_chain_rule_count_mismatch ;;
    reordered_v4_chain_rules) reason=v4_chain_rule_order_mismatch ;;
    reordered_v6_chain_rules) reason=v6_chain_rule_order_mismatch ;;
  esac
  grep -Fxq "status=fatal reason=$reason" "$receipt"
  grep -Fq 'v4_output_rules_begin' "$receipt"
  grep -Fq 'v6_output_rules_begin' "$receipt"
  grep -Fq 'v4_chain_rules_begin' "$receipt"
  grep -Fq 'v6_chain_rules_begin' "$receipt"
  if grep -Fq 'am instrument' "$state_dir/calls.txt"; then
    fail "$mode reached instrumentation"
  fi
  if grep -Eq 'shell ip6?tables -w -(N|A|I|R|F|X|Z|D)( |$)' "$state_dir/calls.txt"; then
    fail "$mode attempted to repair or mutate the guard"
  fi
done

# Structural wiring: the executable guard must be the first operation inside
# each attempt, before logcat collection and the target instrumentation command.
guard_line="$(grep -n '^  scripts/ci/canonical-final-pre-attempt-guard.sh' "$CANONICAL" | head -n 1 | cut -d: -f1)"
logcat_line="$(grep -n '^  adb logcat -v threadtime' "$CANONICAL" | head -n 1 | cut -d: -f1)"
instrument_line="$(grep -n 'adb shell am instrument' "$CANONICAL" | head -n 1 | cut -d: -f1)"
test -n "$guard_line" && test -n "$logcat_line" && test -n "$instrument_line"
test "$guard_line" -lt "$logcat_line"
test "$logcat_line" -lt "$instrument_line"
grep -Fq 'canonical-final-pre-attempt-*.txt' "$WORKFLOW"

echo 'canonical-final pre-attempt guard contract test PASSED'
