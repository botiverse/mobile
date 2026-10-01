#!/usr/bin/env bash
# Runs the Android first-character baseline emulator test suite with failure
# isolation, per-class outcome collection, and tri-state manifest reporting.
#
# Extracted from .github/workflows/test.yml (Task #36 / Task #465):
# Previously, sequential execution under `set -e` in GitHub Actions aborted on
# the first failure, swallowing all downstream tests and hiding their results.
# This runner executes each test independently, captures diagnostics and exit
# codes, records three states per class (passed / failed / not_run), and defers
# the final exit status until all declared tests have completed.
#
# Usage:
#   run-first-character-emulator-suite.sh [test_id...]
#
# Environment variables:
#   SOURCE_EXACT                           Git commit SHA for IME setup
#   ANDROID_EMULATOR_SYSTEM_IMAGE_PACKAGE  System image package (default: system-images;android-34;google_apis;x86_64)
#   RUNNER_LABEL                           CI runner label (default: blacksmith-4vcpu-ubuntu-2404)
#   THREAD_NEWER_PAGINATION_GATE_REQUIRED  "true" to run thread-newer-auto-pagination (default: false)
#   GITHUB_EVENT_NAME                      GitHub Actions event name (default: push)
#   SUMMARY_FILE                           Path to write summary manifest (default: first-character-baseline-suite-summary.txt)
#   SKIP_EMULATOR_PREP                     Set to "true" to skip IME & display setup (for testing)
#   SKIP_APK_INSTALL                       Set to "true" to skip APK installation (for testing)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUMMARY_FILE="${SUMMARY_FILE:-first-character-baseline-suite-summary.txt}"
SUMMARY_GENERATED=0

# Declared test definitions:
# id | runner_type | retries | output_file | test_class | condition
# runner_type:
#   connected -> build.raft.app.connectedtest.test/androidx.test.runner.AndroidJUnitRunner
#   shared    -> build.raft.app.shared.test/androidx.test.runner.AndroidJUnitRunner
RUNNER_CONNECTED="build.raft.app.connectedtest.test/androidx.test.runner.AndroidJUnitRunner"
RUNNER_SHARED="build.raft.app.shared.test/androidx.test.runner.AndroidJUnitRunner"

DECLARED_TEST_IDS=(
  "inactive-host-rendering"
  "first-character-baseline"
  "ime-visibility-probe"
  "composer-session-retirement"
  "thread-composer-suggestion-interaction"
  "multiline-text-field-value"
  "attachment-private-file-provider"
  "attachment-atomic-sink"
  "attachment-download-runtime"
  "performance-trace-feedback"
  "child-page-visibility"
  "pager-scroll-probe"
  "release-notes-section-gap"
  "thread-align-right-bounds"
  "diagnostics-caller-pipeline"
  "durable-partition-migration"
  "thread-newer-auto-pagination"
  "member-detail-dispose-save"
  "member-detail-compose-removal"
)

# Test metadata maps
declare -A TEST_CLASS
declare -A TEST_RUNNER
declare -A TEST_RETRIES
declare -A TEST_OUTPUT
declare -A TEST_CONDITION

# Per-test result maps: tri-state status (passed / failed / not_run)
declare -A TEST_STATUS
declare -A TEST_REASON
declare -A TEST_EXIT_CODE
declare -A TEST_DURATION

init_test_metadata() {
  TEST_CLASS["inactive-host-rendering"]="build.raft.app.shared.SlockKuiklyInactiveHostRenderingTest"
  TEST_RUNNER["inactive-host-rendering"]="$RUNNER_CONNECTED"
  TEST_RETRIES["inactive-host-rendering"]=1
  TEST_OUTPUT["inactive-host-rendering"]="inactive-host-rendering-instrumentation.txt"
  TEST_CONDITION["inactive-host-rendering"]="always"

  TEST_CLASS["first-character-baseline"]="build.raft.app.shared.SlockComposerFirstCharacterBaselineTest"
  TEST_RUNNER["first-character-baseline"]="$RUNNER_CONNECTED"
  TEST_RETRIES["first-character-baseline"]=1
  TEST_OUTPUT["first-character-baseline"]="first-character-baseline-instrumentation.txt"
  TEST_CONDITION["first-character-baseline"]="always"

  TEST_CLASS["ime-visibility-probe"]="build.raft.app.shared.AndroidImeVisibilityProbeConnectedTest"
  TEST_RUNNER["ime-visibility-probe"]="$RUNNER_CONNECTED"
  TEST_RETRIES["ime-visibility-probe"]=1
  TEST_OUTPUT["ime-visibility-probe"]="ime-visibility-probe-instrumentation.txt"
  TEST_CONDITION["ime-visibility-probe"]="always"

  TEST_CLASS["composer-session-retirement"]="build.raft.app.shared.SlockThreadImePaintConnectedTest#sendRetiresEditorSessionWithoutLosingFocusImeOrFirstComposition"
  TEST_RUNNER["composer-session-retirement"]="$RUNNER_CONNECTED"
  TEST_RETRIES["composer-session-retirement"]=1
  TEST_OUTPUT["composer-session-retirement"]="composer-session-retirement-instrumentation.txt"
  TEST_CONDITION["composer-session-retirement"]="always"

  TEST_CLASS["thread-composer-suggestion-interaction"]="build.raft.app.shared.ThreadComposerSuggestionPopoverConnectedTest#openSuggestionsKeepTheEditorAndImageActionInteractive"
  TEST_RUNNER["thread-composer-suggestion-interaction"]="$RUNNER_CONNECTED"
  TEST_RETRIES["thread-composer-suggestion-interaction"]=1
  TEST_OUTPUT["thread-composer-suggestion-interaction"]="thread-composer-suggestion-interaction-instrumentation.txt"
  TEST_CONDITION["thread-composer-suggestion-interaction"]="always"

  TEST_CLASS["multiline-text-field-value"]="build.raft.app.shared.SlockMultilineTextFieldValueConnectedTest"
  TEST_RUNNER["multiline-text-field-value"]="$RUNNER_CONNECTED"
  TEST_RETRIES["multiline-text-field-value"]=1
  TEST_OUTPUT["multiline-text-field-value"]="multiline-text-field-value-instrumentation.txt"
  TEST_CONDITION["multiline-text-field-value"]="always"

  TEST_CLASS["attachment-private-file-provider"]="build.raft.app.shared.AndroidAttachmentPrivateFileProviderConnectedTest"
  TEST_RUNNER["attachment-private-file-provider"]="$RUNNER_CONNECTED"
  TEST_RETRIES["attachment-private-file-provider"]=1
  TEST_OUTPUT["attachment-private-file-provider"]="attachment-private-file-provider-instrumentation.txt"
  TEST_CONDITION["attachment-private-file-provider"]="always"

  TEST_CLASS["attachment-atomic-sink"]="build.raft.app.network.AndroidAttachmentDownloadAtomicSinkTest"
  TEST_RUNNER["attachment-atomic-sink"]="$RUNNER_SHARED"
  TEST_RETRIES["attachment-atomic-sink"]=2
  TEST_OUTPUT["attachment-atomic-sink"]="attachment-atomic-sink-instrumentation.txt"
  TEST_CONDITION["attachment-atomic-sink"]="always"

  TEST_CLASS["attachment-download-runtime"]="build.raft.app.network.AndroidAttachmentDownloadCoordinatorRuntimeTest"
  TEST_RUNNER["attachment-download-runtime"]="$RUNNER_SHARED"
  TEST_RETRIES["attachment-download-runtime"]=6
  TEST_OUTPUT["attachment-download-runtime"]="attachment-download-runtime-instrumentation.txt"
  TEST_CONDITION["attachment-download-runtime"]="always"

  TEST_CLASS["performance-trace-feedback"]="build.raft.app.core.data.diagnostics.PerformanceTraceFeedbackArchiveConnectedTest"
  TEST_RUNNER["performance-trace-feedback"]="$RUNNER_CONNECTED"
  TEST_RETRIES["performance-trace-feedback"]=1
  TEST_OUTPUT["performance-trace-feedback"]="performance-trace-feedback-instrumentation.txt"
  TEST_CONDITION["performance-trace-feedback"]="always"

  TEST_CLASS["child-page-visibility"]="build.raft.app.shared.SlockChildPageVisibilityConnectedTest"
  TEST_RUNNER["child-page-visibility"]="$RUNNER_CONNECTED"
  TEST_RETRIES["child-page-visibility"]=1
  TEST_OUTPUT["child-page-visibility"]="child-page-visibility-instrumentation.txt"
  TEST_CONDITION["child-page-visibility"]="always"

  TEST_CLASS["pager-scroll-probe"]="build.raft.app.shared.SlockPagerScrollProbeConnectedTest"
  TEST_RUNNER["pager-scroll-probe"]="$RUNNER_CONNECTED"
  TEST_RETRIES["pager-scroll-probe"]=1
  TEST_OUTPUT["pager-scroll-probe"]="pager-scroll-probe-instrumentation.txt"
  TEST_CONDITION["pager-scroll-probe"]="always"

  TEST_CLASS["release-notes-section-gap"]="build.raft.app.shared.SlockReleaseNotesSectionGapConnectedTest"
  TEST_RUNNER["release-notes-section-gap"]="$RUNNER_CONNECTED"
  TEST_RETRIES["release-notes-section-gap"]=1
  TEST_OUTPUT["release-notes-section-gap"]="release-notes-section-gap-instrumentation.txt"
  TEST_CONDITION["release-notes-section-gap"]="always"

  TEST_CLASS["thread-align-right-bounds"]="build.raft.app.shared.SlockThreadAlignRightBoundsConnectedTest"
  TEST_RUNNER["thread-align-right-bounds"]="$RUNNER_CONNECTED"
  TEST_RETRIES["thread-align-right-bounds"]=1
  TEST_OUTPUT["thread-align-right-bounds"]="thread-align-right-bounds-instrumentation.txt"
  TEST_CONDITION["thread-align-right-bounds"]="always"

  TEST_CLASS["diagnostics-caller-pipeline"]="build.raft.app.core.data.diagnostics.DiagnosticsCallerPipelineConnectedTest"
  TEST_RUNNER["diagnostics-caller-pipeline"]="$RUNNER_CONNECTED"
  TEST_RETRIES["diagnostics-caller-pipeline"]=1
  TEST_OUTPUT["diagnostics-caller-pipeline"]="diagnostics-caller-pipeline-instrumentation.txt"
  TEST_CONDITION["diagnostics-caller-pipeline"]="always"

  TEST_CLASS["durable-partition-migration"]="build.raft.app.data.DurablePartitionAndroidDriverMigrationTest"
  TEST_RUNNER["durable-partition-migration"]="$RUNNER_SHARED"
  TEST_RETRIES["durable-partition-migration"]=2
  TEST_OUTPUT["durable-partition-migration"]="durable-partition-migration-instrumentation.txt"
  TEST_CONDITION["durable-partition-migration"]="always"

  TEST_CLASS["thread-newer-auto-pagination"]="build.raft.app.shared.SlockThreadNewerAutoPaginationConnectedTest"
  TEST_RUNNER["thread-newer-auto-pagination"]="$RUNNER_CONNECTED"
  TEST_RETRIES["thread-newer-auto-pagination"]=1
  TEST_OUTPUT["thread-newer-auto-pagination"]="thread-newer-auto-pagination-instrumentation.txt"
  TEST_CONDITION["thread-newer-auto-pagination"]="thread_newer_pagination_gate"

  TEST_CLASS["member-detail-dispose-save"]="build.raft.app.shared.MemberDetailScrollDisposeSaveConnectedTest"
  TEST_RUNNER["member-detail-dispose-save"]="$RUNNER_CONNECTED"
  TEST_RETRIES["member-detail-dispose-save"]=1
  TEST_OUTPUT["member-detail-dispose-save"]="member-detail-dispose-save-instrumentation.txt"
  TEST_CONDITION["member-detail-dispose-save"]="always"

  TEST_CLASS["member-detail-compose-removal"]="build.raft.app.shared.MemberDetailComposeRemovalConnectedTest"
  TEST_RUNNER["member-detail-compose-removal"]="$RUNNER_CONNECTED"
  TEST_RETRIES["member-detail-compose-removal"]=1
  TEST_OUTPUT["member-detail-compose-removal"]="member-detail-compose-removal-instrumentation.txt"
  TEST_CONDITION["member-detail-compose-removal"]="always"

  for id in "${DECLARED_TEST_IDS[@]}"; do
    TEST_STATUS["$id"]="not_run"
    TEST_REASON["$id"]="unreached"
    TEST_EXIT_CODE["$id"]="-"
    TEST_DURATION["$id"]="-"
  done
}

# Pre-test hooks
run_pre_hook() {
  local id="$1"
  case "$id" in
    ime-visibility-probe|composer-session-retirement|attachment-private-file-provider|child-page-visibility)
      adb logcat -c || true
      ;;
    *)
      ;;
  esac
}

# Post-test diagnostics collectors (always run, even on failure)
run_post_hook() {
  local id="$1"
  case "$id" in
    ime-visibility-probe)
      adb shell dumpsys input_method > ime-visibility-probe-input-method.txt 2>/dev/null || true
      adb shell dumpsys window > ime-visibility-probe-window.txt 2>/dev/null || true
      adb shell dumpsys activity activities > ime-visibility-probe-activity.txt 2>/dev/null || true
      adb shell dumpsys power > ime-visibility-probe-power.txt 2>/dev/null || true
      adb shell dumpsys window policy > ime-visibility-probe-policy.txt 2>/dev/null || true
      adb logcat -d -v threadtime > ime-visibility-probe-logcat.txt 2>/dev/null || true
      ;;
    composer-session-retirement)
      adb shell dumpsys input_method > composer-session-retirement-input-method.txt 2>/dev/null || true
      adb shell dumpsys window > composer-session-retirement-window.txt 2>/dev/null || true
      adb shell dumpsys activity activities > composer-session-retirement-activity.txt 2>/dev/null || true
      adb shell dumpsys power > composer-session-retirement-power.txt 2>/dev/null || true
      adb shell dumpsys window policy > composer-session-retirement-policy.txt 2>/dev/null || true
      adb logcat -d -v threadtime > composer-session-retirement-logcat.txt 2>/dev/null || true
      ;;
    thread-composer-suggestion-interaction)
      for evidence in root.png screen.png hierarchy.xml window.txt activity.txt logcat.txt failure.txt; do
        adb exec-out run-as build.raft.app.connectedtest cat "files/thread-composer-suggestion-interaction-failure-$evidence" > "thread-composer-suggestion-interaction-failure-$evidence" 2>/dev/null || rm -f "thread-composer-suggestion-interaction-failure-$evidence"
      done
      adb exec-out screencap -p > thread-composer-suggestion-interaction-terminal-screen.png 2>/dev/null || true
      adb shell uiautomator dump /sdcard/thread-composer-suggestion-interaction-terminal-hierarchy.xml >/dev/null 2>&1 || true
      adb pull /sdcard/thread-composer-suggestion-interaction-terminal-hierarchy.xml thread-composer-suggestion-interaction-terminal-hierarchy.xml >/dev/null 2>&1 || rm -f thread-composer-suggestion-interaction-terminal-hierarchy.xml
      adb shell dumpsys window > thread-composer-suggestion-interaction-terminal-window.txt 2>/dev/null || true
      adb shell dumpsys activity activities > thread-composer-suggestion-interaction-terminal-activity.txt 2>/dev/null || true
      adb logcat -d -v threadtime > thread-composer-suggestion-interaction-terminal-logcat.txt 2>/dev/null || true
      ;;
    attachment-private-file-provider)
      adb logcat -d -v threadtime 'AndroidRuntime:E' 'ActivityTaskManager:I' 'TestRunner:E' '*:S' > attachment-private-file-provider-logcat.txt 2>/dev/null || true
      ;;
    child-page-visibility)
      adb exec-out run-as build.raft.app.connectedtest cat files/child-page-visibility-accepted.png > child-page-visibility-accepted.png 2>/dev/null || rm -f child-page-visibility-accepted.png
      adb exec-out screencap -p > child-page-visibility-terminal.png 2>/dev/null || true
      adb logcat -d -v threadtime > child-page-visibility-logcat.txt 2>/dev/null || true
      ;;
    member-detail-dispose-save)
      adb shell dumpsys activity activities > member-detail-dispose-save-activity.txt 2>/dev/null || true
      adb logcat -d -v threadtime -s MembersDetailScroll MemberDetailDisposeB1 TestRunner > member-detail-dispose-save-logcat.txt 2>/dev/null || true
      ;;
    member-detail-compose-removal)
      adb shell dumpsys activity activities > member-detail-compose-removal-activity.txt 2>/dev/null || true
      adb logcat -d -v threadtime -s MembersDetailScroll MemberDetailComposeRemoval TestRunner > member-detail-compose-removal-logcat.txt 2>/dev/null || true
      ;;
    *)
      ;;
  esac
}

# Post-test assertion verifiers
run_assertion_hook() {
  local id="$1"
  local output="${TEST_OUTPUT[$id]}"
  case "$id" in
    ime-visibility-probe)
      grep -Fq 'ANDROID_IME_VISIBILITY_PROBE_PASS' "$output" || return 1
      ;;
    composer-session-retirement)
      grep -Fq 'SLOCK_COMPOSER_SESSION_RETIREMENT_PASS editorRetained=true retainedFocused=true draftClearedAfterSend=true firstComposition=true firstSelection=true requestCount=1 imeSetupFailureDiagnostics=true' "$output" || return 1
      ;;
    thread-composer-suggestion-interaction)
      grep -Fq 'THREAD_COMPOSER_SUGGESTION_INTERACTION_PASS editorFocusedAfterTap=true text=#d imageAction=true outsideDismissed=true' "$output" || return 1
      ;;
    attachment-download-runtime)
      grep -Fq 'TASK2_RUNTIME scenario=slow_complete' "$output" || return 1
      grep -Fq 'TASK2_RUNTIME scenario=cancel_paths' "$output" || return 1
      grep -Fq 'TASK2_RUNTIME scenario=pause_resume' "$output" || return 1
      grep -Fq 'TASK2_RUNTIME scenario=whole_timeout' "$output" || return 1
      grep -Fq 'TASK2_RUNTIME scenario=publishing_replacement' "$output" || return 1
      grep -Fq 'TASK2_RUNTIME scenario=generation_safety' "$output" || return 1
      ;;
    thread-align-right-bounds)
      for band in long sent sending failed retry; do
        grep -Fq "ALIGN_RIGHT_GEOMETRY_${band}" "$output" || return 1
      done
      ;;
    *)
      ;;
  esac
  return 0
}

generate_summary() {
  if [[ "$SUMMARY_GENERATED" -eq 1 ]]; then
    return
  fi
  SUMMARY_GENERATED=1

  local total_declared=${#DECLARED_TEST_IDS[@]}
  local executed_count=0
  local passed_count=0
  local failed_count=0
  local not_run_count=0
  local conditional_skipped_count=0
  local unreached_count=0

  for id in "${DECLARED_TEST_IDS[@]}"; do
    case "${TEST_STATUS[$id]}" in
      passed)
        passed_count=$((passed_count + 1))
        executed_count=$((executed_count + 1))
        ;;
      failed)
        failed_count=$((failed_count + 1))
        executed_count=$((executed_count + 1))
        ;;
      not_run)
        not_run_count=$((not_run_count + 1))
        if [[ "${TEST_REASON[$id]}" =~ ^skipped: ]]; then
          conditional_skipped_count=$((conditional_skipped_count + 1))
        else
          unreached_count=$((unreached_count + 1))
        fi
        ;;
    esac
  done

  local suite_verdict="PASSED"
  if [[ "$failed_count" -gt 0 || "$unreached_count" -gt 0 ]]; then
    suite_verdict="FAILED"
  fi

  local summary_content
  summary_content=$(
    printf '%s\n' '================================================================================'
    printf '%s\n' 'Android Emulator Baseline Suite Execution Summary'
    printf '%s\n' '================================================================================'
    printf 'Total declared:  %d\n' "$total_declared"
    printf 'Executed:        %d (%d passed, %d failed)\n' "$executed_count" "$passed_count" "$failed_count"
    printf 'Not run:         %d (%d conditional skipped, %d unreached)\n' "$not_run_count" "$conditional_skipped_count" "$unreached_count"
    printf 'Suite verdict:   %s\n' "$suite_verdict"
    printf '%s\n' '--------------------------------------------------------------------------------'
    printf '%-9s %-40s %-5s %-7s %s\n' "STATE" "TEST ID" "EXIT" "TIME" "TARGET CLASS / REASON"
    printf '%s\n' '--------------------------------------------------------------------------------'
    for id in "${DECLARED_TEST_IDS[@]}"; do
      local status_upper
      status_upper="$(echo "${TEST_STATUS[$id]}" | tr '[:lower:]' '[:upper:]')"
      local target_info="${TEST_CLASS[$id]}"
      if [[ "${TEST_STATUS[$id]}" == "not_run" ]]; then
        target_info="${TEST_CLASS[$id]} (${TEST_REASON[$id]})"
      elif [[ "${TEST_STATUS[$id]}" == "failed" && "${TEST_REASON[$id]}" != "instrumentation_failed" ]]; then
        target_info="${TEST_CLASS[$id]} (${TEST_REASON[$id]})"
      fi
      printf '%-9s %-40s %-5s %-7s %s\n' \
        "$status_upper" \
        "$id" \
        "${TEST_EXIT_CODE[$id]}" \
        "${TEST_DURATION[$id]}" \
        "$target_info"
    done
    printf '%s\n' '================================================================================'
    printf '%s\n' '### BEGIN_TRI_STATE_MANIFEST ###'
    for id in "${DECLARED_TEST_IDS[@]}"; do
      printf 'status=%s test=%s rc=%s duration=%s class=%s reason=%s\n' \
        "${TEST_STATUS[$id]}" \
        "$id" \
        "${TEST_EXIT_CODE[$id]}" \
        "${TEST_DURATION[$id]}" \
        "${TEST_CLASS[$id]}" \
        "${TEST_REASON[$id]}"
    done
    printf '%s\n' '### END_TRI_STATE_MANIFEST ###'
  )

  printf '%s\n' "$summary_content"
  printf '%s\n' "$summary_content" > "$SUMMARY_FILE"
}

cleanup() {
  local exit_code=$?
  generate_summary
  exit "$exit_code"
}
trap cleanup EXIT

main() {
  init_test_metadata

  # Filter test list if specific test IDs passed on CLI
  local tests_to_run=()
  if [[ $# -gt 0 ]]; then
    for arg in "$@"; do
      if [[ -n "${TEST_CLASS[$arg]:-}" ]]; then
        tests_to_run+=("$arg")
      else
        echo "::error::Unknown test ID specified: $arg" >&2
        exit 2
      fi
    done
    for id in "${DECLARED_TEST_IDS[@]}"; do
      local is_selected=0
      for sel in "${tests_to_run[@]}"; do
        if [[ "$id" == "$sel" ]]; then
          is_selected=1
          break
        fi
      done
      if [[ "$is_selected" -eq 0 ]]; then
        TEST_STATUS["$id"]="not_run"
        TEST_REASON["$id"]="skipped:not_selected"
      fi
    done
  else
    tests_to_run=("${DECLARED_TEST_IDS[@]}")
  fi

  echo "=== Initializing Android emulator baseline suite ==="
  adb wait-for-device || {
    echo "::error::adb wait-for-device failed" >&2
    exit 1
  }

  if [[ "${SKIP_EMULATOR_PREP:-false}" != "true" ]]; then
    # Keep the display awake for the whole job. Several screenshot-based
    # connected tests (first-character baseline, child-page visibility,
    # pager scroll, thread align-right) poll for rendered colors and would
    # read all-black frames if the emulator sleeps mid-test.
    adb shell settings put system screen_off_timeout 2147483647 || true

    SOURCE_EXACT="${SOURCE_EXACT:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}" \
    ANDROID_EMULATOR_SYSTEM_IMAGE_PACKAGE="${ANDROID_EMULATOR_SYSTEM_IMAGE_PACKAGE:-system-images;android-34;google_apis;x86_64}" \
    RUNNER_LABEL="${RUNNER_LABEL:-blacksmith-4vcpu-ubuntu-2404}" \
    "$HERE/prepare_android_emulator_ime.sh" android-ime-environment.txt || {
      echo "::error::prepare_android_emulator_ime.sh failed" >&2
      exit 1
    }
  fi

  if [[ "${SKIP_APK_INSTALL:-false}" != "true" ]]; then
    local app_apk test_apk shared_apk
    app_apk="$(find android-connected-test-apks -type f -name '*connectedTest.apk' -not -name '*androidTest*' 2>/dev/null | head -n 1 || true)"
    test_apk="$(find android-connected-test-apks -type f -name '*androidTest.apk' 2>/dev/null | head -n 1 || true)"
    shared_apk="$(find android-shared-atomic-sink-test-apk -type f -name '*.apk' 2>/dev/null | head -n 1 || true)"

    if [[ -n "$app_apk" ]]; then
      adb install -r "$app_apk" || {
        echo "::error::adb install connectedTest APK failed: $app_apk" >&2
        exit 1
      }
    fi
    if [[ -n "$test_apk" ]]; then
      adb install -r "$test_apk" || {
        echo "::error::adb install androidTest APK failed: $test_apk" >&2
        exit 1
      }
    fi
    if [[ -n "$shared_apk" ]]; then
      adb install -r "$shared_apk" || {
        echo "::error::adb install shared atomic sink APK failed: $shared_apk" >&2
        exit 1
      }
      adb shell pm list instrumentation | tee attachment-atomic-sink-receipt.txt
      grep -Fq 'instrumentation:build.raft.app.shared.test/androidx.test.runner.AndroidJUnitRunner' attachment-atomic-sink-receipt.txt || {
        echo "::error::shared atomic sink runner registration check failed" >&2
        exit 1
      }
    fi
  fi

  echo "=== Running declared test suite (${#tests_to_run[@]} tests planned) ==="
  local index=0
  for id in "${tests_to_run[@]}"; do
    index=$((index + 1))
    local condition="${TEST_CONDITION[$id]}"

    # Check condition gating
    if [[ "$condition" == "thread_newer_pagination_gate" && "${THREAD_NEWER_PAGINATION_GATE_REQUIRED:-false}" != "true" ]]; then
      echo "[$index/${#tests_to_run[@]}] SKIPPED: $id (gate condition not met)"
      TEST_STATUS["$id"]="not_run"
      TEST_REASON["$id"]="skipped:condition_not_met"
      continue
    fi

    echo "--------------------------------------------------------------------------------"
    echo "[$index/${#tests_to_run[@]}] STARTING: $id"
    echo "  Class:   ${TEST_CLASS[$id]}"
    echo "  Runner:  ${TEST_RUNNER[$id]}"
    echo "  Retries: ${TEST_RETRIES[$id]}"
    echo "  Output:  ${TEST_OUTPUT[$id]}"

    run_pre_hook "$id"

    local start_time end_time duration
    start_time=$(date +%s)

    local run_rc=0
    "$HERE/run-instrumentation-with-retry.sh" \
      "${TEST_OUTPUT[$id]}" \
      "${TEST_RETRIES[$id]}" \
      -- adb shell am instrument -w -r -e class "${TEST_CLASS[$id]}" "${TEST_RUNNER[$id]}" || run_rc=$?

    # Always collect post-execution diagnostics
    run_post_hook "$id"

    local final_rc="$run_rc"
    local final_reason="passed"

    if [[ "$run_rc" -eq 0 ]]; then
      local assert_rc=0
      run_assertion_hook "$id" || assert_rc=$?
      if [[ "$assert_rc" -ne 0 ]]; then
        final_rc="$assert_rc"
        final_reason="assertion_failed"
        echo "::error::$id passed instrumentation but failed post-assertions" >&2
      fi
    else
      final_reason="instrumentation_failed"
      echo "::error::$id failed with exit code $run_rc" >&2
    fi

    end_time=$(date +%s)
    duration=$((end_time - start_time))

    TEST_EXIT_CODE["$id"]="$final_rc"
    TEST_DURATION["$id"]="${duration}s"
    if [[ "$final_rc" -eq 0 ]]; then
      TEST_STATUS["$id"]="passed"
      TEST_REASON["$id"]="passed"
      echo "[$index/${#tests_to_run[@]}] PASSED: $id (rc=0, duration=${duration}s)"
    else
      TEST_STATUS["$id"]="failed"
      TEST_REASON["$id"]="$final_reason"
      echo "[$index/${#tests_to_run[@]}] FAILED: $id (rc=$final_rc, reason=$final_reason, duration=${duration}s)"
    fi
  done

  # Thread-open budget TELEMETRY (Raft task #20 t2): harvest the frozen-v3
  # perf markers produced during the run. Baseline collection only on push/nightly.
  if [[ "${GITHUB_EVENT_NAME:-push}" != "pull_request" ]]; then
    adb logcat -d -v threadtime 2>/dev/null | grep -E "thread_first_content_budget|kuikly_perf_snapshot|thread_payload_ready_gate|thread_host_traversal|thread_first_content_ready|thread_content_compose_start|thread_refresh_result|thread_markdown_body_recompose" > thread-open-budget-telemetry.txt 2>/dev/null || true
  fi

  generate_summary

  # Final exit judgment: fail if any test failed or unreached
  for id in "${tests_to_run[@]}"; do
    if [[ "${TEST_STATUS[$id]}" == "failed" ]]; then
      exit 1
    fi
    if [[ "${TEST_STATUS[$id]}" == "not_run" && "${TEST_CONDITION[$id]}" == "always" ]]; then
      exit 1
    fi
  done

  exit 0
}

main "$@"
