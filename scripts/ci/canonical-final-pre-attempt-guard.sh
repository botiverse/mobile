#!/usr/bin/env bash
# Fail-closed admission guard for each canonical-final instrumentation attempt.
# It may wait through a transient ADB/setup outage while the target is stopped,
# but it never creates or repairs package/iptables state. A missing identity or
# egress guard is fatal before `am instrument` can start.
set -euo pipefail

if test "$#" -ne 6; then
  echo "usage: $0 <attempt> <target-package> <test-package> <target-uid> <chain> <receipt>" >&2
  exit 2
fi

RUN_ATTEMPT="$1"
PACKAGE="$2"
TEST_PACKAGE="$3"
TARGET_UID="$4"
CHAIN="$5"
RECEIPT="$6"
MAX_PROBES="${CANONICAL_FINAL_PRE_ATTEMPT_MAX_PROBES:-30}"
POLL_SECONDS="${CANONICAL_FINAL_PRE_ATTEMPT_POLL_SECONDS:-1}"
COMMAND_TIMEOUT_SECONDS="${CANONICAL_FINAL_PRE_ATTEMPT_COMMAND_TIMEOUT_SECONDS:-5}"
EXPECTED_INSTRUMENTATION="instrumentation:${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner (target=${PACKAGE})"

: > "$RECEIPT"
printf 'attempt=%s target_package=%s test_package=%s expected_uid=%s chain=%s\n' \
  "$RUN_ATTEMPT" "$PACKAGE" "$TEST_PACKAGE" "$TARGET_UID" "$CHAIN" >> "$RECEIPT"

fail() {
  local reason="$1"
  printf 'status=fatal reason=%s\n' "$reason" | tee -a "$RECEIPT" >&2
  exit 1
}

case "$RUN_ATTEMPT" in
  ''|*[!0-9]*|0) fail invalid_attempt ;;
esac
case "$TARGET_UID" in
  ''|*[!0-9]*) fail invalid_target_uid ;;
esac
case "$MAX_PROBES" in
  ''|*[!0-9]*|0) fail invalid_max_probes ;;
esac
case "$COMMAND_TIMEOUT_SECONDS" in
  ''|*[!0-9]*|0) fail invalid_command_timeout ;;
esac
case "$POLL_SECONDS" in
  ''|*[!0-9]*) fail invalid_poll_seconds ;;
esac
case "$PACKAGE:$TEST_PACKAGE:$CHAIN" in
  *[!A-Za-z0-9._:-]*) fail invalid_identity_argument ;;
esac
command -v timeout >/dev/null 2>&1 || fail timeout_command_missing

capture() {
  local output_name="$1"
  shift
  local captured capture_rc
  set +e
  captured="$("$@" 2>/dev/null)"
  capture_rc=$?
  set -e
  captured="${captured//$'\r'/}"
  printf -v "$output_name" '%s' "$captured"
  return "$capture_rc"
}

rule_has_pair() {
  local rule="$1"
  local option="$2"
  local value="$3"
  printf '%s\n' "$rule" | awk -v option="$option" -v value="$value" '
    {
      for (field = 1; field < NF; field++) {
        if ($field == option && $(field + 1) == value) {
          found = 1
        }
      }
    }
    END { exit found ? 0 : 1 }
  '
}

count_append_rules() {
  local ruleset="$1"
  local chain="$2"
  printf '%s\n' "$ruleset" | awk -v chain="$chain" '
    $1 == "-A" && $2 == chain { count++ }
    END { print count + 0 }
  '
}

append_rule_at() {
  local ruleset="$1"
  local chain="$2"
  local wanted="$3"
  printf '%s\n' "$ruleset" | awk -v chain="$chain" -v wanted="$wanted" '
    $1 == "-A" && $2 == chain {
      count++
      if (count == wanted) {
        print
        exit
      }
    }
  '
}

count_chain_transfers() {
  local ruleset="$1"
  local source_chain="$2"
  local destination_chain="$3"
  printf '%s\n' "$ruleset" | awk -v source="$source_chain" -v destination="$destination_chain" '
    $1 == "-A" && $2 == source {
      for (field = 3; field < NF; field++) {
        if (($field == "-j" || $field == "-g") && $(field + 1) == destination) {
          count++
        }
      }
    }
    END { print count + 0 }
  '
}

append_ruleset_receipt() {
  local label="$1"
  local ruleset="$2"
  printf '%s_begin\n%s\n%s_end\n' "$label" "$ruleset" "$label" >> "$RECEIPT"
}

transport_ready() {
  READINESS_ADB_STATE="unreadable"
  READINESS_ROOT_UID="unreadable"
  READINESS_BOOT_COMPLETED="unreadable"
  READINESS_PACKAGE_MANAGER="unavailable"

  timeout "$COMMAND_TIMEOUT_SECONDS" adb wait-for-device >/dev/null 2>&1 || return 1
  capture READINESS_ADB_STATE timeout "$COMMAND_TIMEOUT_SECONDS" adb get-state || return 1
  capture READINESS_ROOT_UID timeout "$COMMAND_TIMEOUT_SECONDS" adb shell id -u || return 1
  capture READINESS_BOOT_COMPLETED timeout "$COMMAND_TIMEOUT_SECONDS" adb shell getprop sys.boot_completed || return 1
  if timeout "$COMMAND_TIMEOUT_SECONDS" adb shell cmd package list packages >/dev/null 2>&1; then
    READINESS_PACKAGE_MANAGER="ready"
  else
    return 1
  fi
  test "$READINESS_ADB_STATE" = device || return 1
  test "$READINESS_ROOT_UID" = 0 || return 1
  test "$READINESS_BOOT_COMPLETED" = 1 || return 1
  return 0
}

sleep_before_next_probe() {
  local probe="$1"
  if test "$probe" -lt "$MAX_PROBES"; then
    sleep "$POLL_SECONDS"
  fi
}

for readiness_probe in $(seq 1 "$MAX_PROBES"); do
  if ! transport_ready; then
    printf 'probe=%s readiness=waiting adb_state=%s root_uid=%s boot_completed=%s package_manager=%s\n' \
      "$readiness_probe" "$READINESS_ADB_STATE" "$READINESS_ROOT_UID" \
      "$READINESS_BOOT_COMPLETED" "$READINESS_PACKAGE_MANAGER" >> "$RECEIPT"
    sleep_before_next_probe "$readiness_probe"
    continue
  fi

  # The old loop called this directly under set -e, so a recoverable post-reset
  # adbd hiccup aborted before classification. Make it part of bounded readiness.
  if ! timeout "$COMMAND_TIMEOUT_SECONDS" adb logcat -c >/dev/null 2>&1; then
    printf 'probe=%s readiness=waiting adb_state=%s root_uid=%s boot_completed=%s package_manager=%s logcat_clear=unavailable\n' \
      "$readiness_probe" "$READINESS_ADB_STATE" "$READINESS_ROOT_UID" \
      "$READINESS_BOOT_COMPLETED" "$READINESS_PACKAGE_MANAGER" >> "$RECEIPT"
    sleep_before_next_probe "$readiness_probe"
    continue
  fi

  VALIDATION_REASON=""
  TARGET_PATH_OUTPUT=""
  TEST_PATH_OUTPUT=""
  PACKAGE_UID_OUTPUT=""
  INSTRUMENTATION_OUTPUT=""
  TARGET_PID=""

  capture TARGET_PATH_OUTPUT timeout "$COMMAND_TIMEOUT_SECONDS" adb shell pm path "$PACKAGE" \
    || VALIDATION_REASON=target_package_unreadable
  if test -z "$VALIDATION_REASON"; then
    TARGET_PATH_COUNT="$(printf '%s\n' "$TARGET_PATH_OUTPUT" | awk '/^package:/ { count++ } END { print count + 0 }')"
    test "$TARGET_PATH_COUNT" -ge 1 || fail target_package_missing
  fi

  if test -z "$VALIDATION_REASON"; then
    capture TEST_PATH_OUTPUT timeout "$COMMAND_TIMEOUT_SECONDS" adb shell pm path "$TEST_PACKAGE" \
      || VALIDATION_REASON=test_package_unreadable
  fi
  if test -z "$VALIDATION_REASON"; then
    TEST_PATH_COUNT="$(printf '%s\n' "$TEST_PATH_OUTPUT" | awk '/^package:/ { count++ } END { print count + 0 }')"
    test "$TEST_PATH_COUNT" -ge 1 || fail test_package_missing
  fi

  if test -z "$VALIDATION_REASON"; then
    capture PACKAGE_UID_OUTPUT timeout "$COMMAND_TIMEOUT_SECONDS" adb shell cmd package list packages -U \
      || VALIDATION_REASON=target_uid_unreadable
  fi
  if test -z "$VALIDATION_REASON"; then
    OBSERVED_UID="$(printf '%s\n' "$PACKAGE_UID_OUTPUT" | awk -v package="package:${PACKAGE}" '
      $1 == package {
        for (field = 2; field <= NF; field++) {
          if ($field ~ /^uid:/) {
            sub(/^uid:/, "", $field)
            print $field
            exit
          }
        }
      }
    ')"
    test -n "$OBSERVED_UID" || fail target_uid_missing
    test "$OBSERVED_UID" = "$TARGET_UID" || fail target_uid_mismatch
  fi

  if test -z "$VALIDATION_REASON"; then
    capture INSTRUMENTATION_OUTPUT timeout "$COMMAND_TIMEOUT_SECONDS" adb shell pm list instrumentation \
      || VALIDATION_REASON=instrumentation_list_unreadable
  fi
  if test -z "$VALIDATION_REASON"; then
    INSTRUMENTATION_COUNT="$(printf '%s\n' "$INSTRUMENTATION_OUTPUT" | grep -Fxc "$EXPECTED_INSTRUMENTATION" || true)"
    test "$INSTRUMENTATION_COUNT" -eq 1 || fail instrumentation_identity_missing
  fi

  if test -z "$VALIDATION_REASON"; then
    capture TARGET_PID timeout "$COMMAND_TIMEOUT_SECONDS" adb shell "pidof '${PACKAGE}' 2>/dev/null || true" \
      || VALIDATION_REASON=target_pid_unreadable
  fi
  if test -z "$VALIDATION_REASON"; then
    test -z "$TARGET_PID" || fail target_running_before_attempt
    printf 'probe=%s readiness=ready logcat_clear=ready target_path_count=%s test_path_count=%s observed_uid=%s instrumentation_count=%s target_pid=stopped\n' \
      "$readiness_probe" "$TARGET_PATH_COUNT" "$TEST_PATH_COUNT" "$OBSERVED_UID" \
      "$INSTRUMENTATION_COUNT" >> "$RECEIPT"
  fi

  V4_OUTPUT_RULESET=""
  V6_OUTPUT_RULESET=""
  V4_CHAIN_RULESET=""
  V6_CHAIN_RULESET=""
  if test -z "$VALIDATION_REASON"; then
    if capture V4_OUTPUT_RULESET timeout "$COMMAND_TIMEOUT_SECONDS" adb shell iptables -w -S OUTPUT; then
      append_ruleset_receipt v4_output_rules "$V4_OUTPUT_RULESET"
    else
      VALIDATION_REASON=v4_output_rules_unreadable
    fi
  fi
  if test -z "$VALIDATION_REASON"; then
    if capture V6_OUTPUT_RULESET timeout "$COMMAND_TIMEOUT_SECONDS" adb shell ip6tables -w -S OUTPUT; then
      append_ruleset_receipt v6_output_rules "$V6_OUTPUT_RULESET"
    else
      VALIDATION_REASON=v6_output_rules_unreadable
    fi
  fi
  if test -z "$VALIDATION_REASON"; then
    if capture V4_CHAIN_RULESET timeout "$COMMAND_TIMEOUT_SECONDS" adb shell iptables -w -S "$CHAIN"; then
      append_ruleset_receipt v4_chain_rules "$V4_CHAIN_RULESET"
    else
      VALIDATION_REASON=v4_chain_rules_unreadable
    fi
  fi
  if test -z "$VALIDATION_REASON"; then
    if capture V6_CHAIN_RULESET timeout "$COMMAND_TIMEOUT_SECONDS" adb shell ip6tables -w -S "$CHAIN"; then
      append_ruleset_receipt v6_chain_rules "$V6_CHAIN_RULESET"
    else
      VALIDATION_REASON=v6_chain_rules_unreadable
    fi
  fi

  # `-C` proves the exact owner match exists. The full ordered `-S` ruleset
  # additionally proves that it is the first OUTPUT rule and the only transfer
  # to this custom chain, so no earlier ACCEPT/RETURN or duplicate can bypass it.
  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell iptables -w -C OUTPUT -m owner --uid-owner "$TARGET_UID" -j "$CHAIN" >/dev/null 2>&1; then
    VALIDATION_REASON=v4_output_hook_missing
  fi
  if test -z "$VALIDATION_REASON"; then
    V4_OUTPUT_TRANSFERS="$(count_chain_transfers "$V4_OUTPUT_RULESET" OUTPUT "$CHAIN")"
    V4_OUTPUT_FIRST="$(append_rule_at "$V4_OUTPUT_RULESET" OUTPUT 1)"
    if test "$V4_OUTPUT_TRANSFERS" -ne 1; then
      VALIDATION_REASON=v4_output_hook_not_unique
    elif ! rule_has_pair "$V4_OUTPUT_FIRST" -j "$CHAIN"; then
      VALIDATION_REASON=v4_output_hook_not_first
    else
      printf 'v4_output_hook=first_unique transfers=%s\n' "$V4_OUTPUT_TRANSFERS" >> "$RECEIPT"
    fi
  fi
  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell ip6tables -w -C OUTPUT -m owner --uid-owner "$TARGET_UID" -j "$CHAIN" >/dev/null 2>&1; then
    VALIDATION_REASON=v6_output_hook_missing
  fi
  if test -z "$VALIDATION_REASON"; then
    V6_OUTPUT_TRANSFERS="$(count_chain_transfers "$V6_OUTPUT_RULESET" OUTPUT "$CHAIN")"
    V6_OUTPUT_FIRST="$(append_rule_at "$V6_OUTPUT_RULESET" OUTPUT 1)"
    if test "$V6_OUTPUT_TRANSFERS" -ne 1; then
      VALIDATION_REASON=v6_output_hook_not_unique
    elif ! rule_has_pair "$V6_OUTPUT_FIRST" -j "$CHAIN"; then
      VALIDATION_REASON=v6_output_hook_not_first
    else
      printf 'v6_output_hook=first_unique transfers=%s\n' "$V6_OUTPUT_TRANSFERS" >> "$RECEIPT"
    fi
  fi

  # Presence alone is insufficient: an extra or reordered RETURN/ACCEPT could
  # bypass REJECT while its counter remains 0 0. Exact `-C` membership plus an
  # exact three-rule count and ordered classification proves the whole chain.
  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell iptables -w -C "$CHAIN" -o lo -j RETURN >/dev/null 2>&1; then
    VALIDATION_REASON=v4_loopback_rule_missing
  fi
  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell iptables -w -C "$CHAIN" -d 127.0.0.0/8 -j RETURN >/dev/null 2>&1; then
    VALIDATION_REASON=v4_loopback_destination_rule_missing
  fi
  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell iptables -w -C "$CHAIN" -j REJECT >/dev/null 2>&1; then
    VALIDATION_REASON=v4_reject_rule_missing
  fi
  if test -z "$VALIDATION_REASON"; then
    V4_CHAIN_RULE_COUNT="$(count_append_rules "$V4_CHAIN_RULESET" "$CHAIN")"
    V4_CHAIN_RULE_1="$(append_rule_at "$V4_CHAIN_RULESET" "$CHAIN" 1)"
    V4_CHAIN_RULE_2="$(append_rule_at "$V4_CHAIN_RULESET" "$CHAIN" 2)"
    V4_CHAIN_RULE_3="$(append_rule_at "$V4_CHAIN_RULESET" "$CHAIN" 3)"
    if test "$V4_CHAIN_RULE_COUNT" -ne 3; then
      VALIDATION_REASON=v4_chain_rule_count_mismatch
    elif ! rule_has_pair "$V4_CHAIN_RULE_1" -o lo \
      || ! rule_has_pair "$V4_CHAIN_RULE_1" -j RETURN \
      || ! rule_has_pair "$V4_CHAIN_RULE_2" -d 127.0.0.0/8 \
      || ! rule_has_pair "$V4_CHAIN_RULE_2" -j RETURN \
      || ! rule_has_pair "$V4_CHAIN_RULE_3" -j REJECT; then
      VALIDATION_REASON=v4_chain_rule_order_mismatch
    else
      printf 'v4_chain_rules=exact_ordered count=%s\n' "$V4_CHAIN_RULE_COUNT" >> "$RECEIPT"
    fi
  fi

  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell ip6tables -w -C "$CHAIN" -o lo -j RETURN >/dev/null 2>&1; then
    VALIDATION_REASON=v6_loopback_rule_missing
  fi
  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell ip6tables -w -C "$CHAIN" -d ::1/128 -j RETURN >/dev/null 2>&1; then
    VALIDATION_REASON=v6_loopback_destination_rule_missing
  fi
  if test -z "$VALIDATION_REASON" && ! timeout "$COMMAND_TIMEOUT_SECONDS" adb shell ip6tables -w -C "$CHAIN" -j REJECT >/dev/null 2>&1; then
    VALIDATION_REASON=v6_reject_rule_missing
  fi
  if test -z "$VALIDATION_REASON"; then
    V6_CHAIN_RULE_COUNT="$(count_append_rules "$V6_CHAIN_RULESET" "$CHAIN")"
    V6_CHAIN_RULE_1="$(append_rule_at "$V6_CHAIN_RULESET" "$CHAIN" 1)"
    V6_CHAIN_RULE_2="$(append_rule_at "$V6_CHAIN_RULESET" "$CHAIN" 2)"
    V6_CHAIN_RULE_3="$(append_rule_at "$V6_CHAIN_RULESET" "$CHAIN" 3)"
    if test "$V6_CHAIN_RULE_COUNT" -ne 3; then
      VALIDATION_REASON=v6_chain_rule_count_mismatch
    elif ! rule_has_pair "$V6_CHAIN_RULE_1" -o lo \
      || ! rule_has_pair "$V6_CHAIN_RULE_1" -j RETURN \
      || ! rule_has_pair "$V6_CHAIN_RULE_2" -d ::1/128 \
      || ! rule_has_pair "$V6_CHAIN_RULE_2" -j RETURN \
      || ! rule_has_pair "$V6_CHAIN_RULE_3" -j REJECT; then
      VALIDATION_REASON=v6_chain_rule_order_mismatch
    else
      printf 'v6_chain_rules=exact_ordered count=%s\n' "$V6_CHAIN_RULE_COUNT" >> "$RECEIPT"
    fi
  fi

  V4_CHAIN_OUTPUT=""
  V6_CHAIN_OUTPUT=""
  if test -z "$VALIDATION_REASON"; then
    capture V4_CHAIN_OUTPUT timeout "$COMMAND_TIMEOUT_SECONDS" adb shell iptables -w -L "$CHAIN" -n -v -x \
      || VALIDATION_REASON=v4_chain_unreadable
  fi
  if test -z "$VALIDATION_REASON"; then
    capture V6_CHAIN_OUTPUT timeout "$COMMAND_TIMEOUT_SECONDS" adb shell ip6tables -w -L "$CHAIN" -n -v -x \
      || VALIDATION_REASON=v6_chain_unreadable
  fi

  if test -n "$VALIDATION_REASON"; then
    # A command can fail because adbd vanished between readiness and validation.
    # Only that transport-loss case is retryable; if the device is still ready,
    # the missing/unreadable identity or guard is authoritative and immediately fatal.
    if transport_ready; then
      fail "$VALIDATION_REASON"
    fi
    printf 'probe=%s validation=waiting reason=%s\n' "$readiness_probe" "$VALIDATION_REASON" >> "$RECEIPT"
    sleep_before_next_probe "$readiness_probe"
    continue
  fi

  V4_REJECT="$(printf '%s\n' "$V4_CHAIN_OUTPUT" | awk '$3 == "REJECT" { print $1 " " $2 }')"
  V6_REJECT="$(printf '%s\n' "$V6_CHAIN_OUTPUT" | awk '$3 == "REJECT" { print $1 " " $2 }')"
  test -n "$V4_REJECT" || fail v4_reject_counter_unreadable
  test -n "$V6_REJECT" || fail v6_reject_counter_unreadable
  test "$V4_REJECT" = "0 0" || fail v4_reject_counter_nonzero
  test "$V6_REJECT" = "0 0" || fail v6_reject_counter_nonzero
  printf 'v4_chain=present v4_reject=[%s]\nv6_chain=present v6_reject=[%s]\nstatus=ready\n' \
    "$V4_REJECT" "$V6_REJECT" >> "$RECEIPT"
  exit 0
done

fail readiness_timeout
