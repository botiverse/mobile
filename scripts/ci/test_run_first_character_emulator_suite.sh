#!/usr/bin/env bash
# Unit tests for run-first-character-emulator-suite.sh.
# Verifies failure isolation, tri-state tracking (passed/failed/not_run),
# post-execution diagnostics collection, and aggregate final verdict.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUITE_RUNNER="$ROOT/scripts/ci/run-first-character-emulator-suite.sh"
WORKFLOW="$ROOT/.github/workflows/test.yml"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  echo "ok: $*"
}

# 1. Syntax check
bash -n "$SUITE_RUNNER" || fail "Syntax error in $SUITE_RUNNER"
pass "syntax check passed"

# 2. Structural contract checks on test registry
grep -Fq 'inactive-host-rendering' "$SUITE_RUNNER" || fail "missing inactive-host-rendering"
grep -Fq 'first-character-baseline' "$SUITE_RUNNER" || fail "missing first-character-baseline"
grep -Fq 'ime-visibility-probe' "$SUITE_RUNNER" || fail "missing ime-visibility-probe"
grep -Fq 'composer-session-retirement' "$SUITE_RUNNER" || fail "missing composer-session-retirement"
grep -Fq 'thread-composer-suggestion-interaction' "$SUITE_RUNNER" || fail "missing thread-composer-suggestion-interaction"
grep -Fq 'multiline-text-field-value' "$SUITE_RUNNER" || fail "missing multiline-text-field-value"
grep -Fq 'attachment-private-file-provider' "$SUITE_RUNNER" || fail "missing attachment-private-file-provider"
grep -Fq 'attachment-atomic-sink' "$SUITE_RUNNER" || fail "missing attachment-atomic-sink"
grep -Fq 'attachment-download-runtime' "$SUITE_RUNNER" || fail "missing attachment-download-runtime"
grep -Fq 'performance-trace-feedback' "$SUITE_RUNNER" || fail "missing performance-trace-feedback"
grep -Fq 'child-page-visibility' "$SUITE_RUNNER" || fail "missing child-page-visibility"
grep -Fq 'pager-scroll-probe' "$SUITE_RUNNER" || fail "missing pager-scroll-probe"
grep -Fq 'release-notes-section-gap' "$SUITE_RUNNER" || fail "missing release-notes-section-gap"
grep -Fq 'thread-align-right-bounds' "$SUITE_RUNNER" || fail "missing thread-align-right-bounds"
grep -Fq 'diagnostics-caller-pipeline' "$SUITE_RUNNER" || fail "missing diagnostics-caller-pipeline"
grep -Fq 'durable-partition-migration' "$SUITE_RUNNER" || fail "missing durable-partition-migration"
grep -Fq 'thread-newer-auto-pagination' "$SUITE_RUNNER" || fail "missing thread-newer-auto-pagination"
pass "test registry structural checks passed"

# Setup mock environment for behavior testing
MOCK_BIN="$TMP_DIR/bin"
mkdir -p "$MOCK_BIN"

# Mock adb
cat > "$MOCK_BIN/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail
state_dir="${MOCK_STATE_DIR:?}"
mkdir -p "$state_dir"
echo "$*" >> "$state_dir/adb_calls.txt"

case "${1:-}" in
  wait-for-device)
    if [[ "${MOCK_ADB_WAIT_FAIL:-0}" == "1" ]]; then
      exit 1
    fi
    exit 0
    ;;
  shell)
    shift
    if [[ "${1:-}" == "settings" ]]; then
      exit 0
    elif [[ "${1:-}" == "dumpsys" ]]; then
      echo "mock dumpsys output for $*"
      exit 0
    elif [[ "${1:-}" == "uiautomator" ]]; then
      exit 0
    fi
    exit 0
    ;;
  logcat)
    if [[ "${MOCK_ADB_HANG:-0}" == "1" ]]; then
      sleep 60
      exit 0
    fi
    if [[ "${2:-}" == "-c" ]]; then
      exit 0
    fi
    echo "mock logcat line"
    exit 0
    ;;
  exec-out)
    shift
    if [[ "${1:-}" == "run-as" && "${2:-}" == "build.raft.app.connectedtest" ]]; then
      shift 2
      case "${1:-}" in
        ls)
          echo "launch-mock001"
          exit 0
          ;;
        cat)
          echo "mock-diagnostics-record"
          exit 0
          ;;
      esac
    fi
    echo "mock exec-out"
    exit 0
    ;;
  pull)
    touch "${3:-$state_dir/pulled_file}"
    exit 0
    ;;
  install)
    exit 0
    ;;
  *)
    exit 0
    ;;
esac
FAKE_ADB
chmod +x "$MOCK_BIN/adb"

# Mock run-instrumentation-with-retry.sh
cat > "$MOCK_BIN/run-instrumentation-with-retry.sh" <<'FAKE_RETRY'
#!/usr/bin/env bash
set -euo pipefail
state_dir="${MOCK_STATE_DIR:?}"
output_file="$1"
min_tests="$2"
shift 2
if [[ "${1:-}" == "--" ]]; then shift; fi

# Extract test class name from arguments
test_class=""
while [[ $# -gt 0 ]]; do
  if [[ "$1" == "-e" && "$2" == "class" ]]; then
    test_class="$3"
    break
  fi
  shift
done

echo "run-instrumentation class=$test_class min=$min_tests" >> "$state_dir/executed_classes.txt"

# Check if this test is configured to fail
failing_class="${MOCK_FAILING_CLASS:-}"
if [[ -n "$failing_class" && "$test_class" == *"$failing_class"* ]]; then
  echo "INSTRUMENTATION FAILED: $test_class" > "$output_file"
  exit 1
fi

# Populate appropriate assertions markers
case "$output_file" in
  ime-visibility-probe-instrumentation.txt)
    echo "ANDROID_IME_VISIBILITY_PROBE_PASS" > "$output_file"
    ;;
  composer-session-retirement-instrumentation.txt)
    echo "SLOCK_COMPOSER_SESSION_RETIREMENT_PASS editorRetained=true retainedFocused=true draftClearedAfterSend=true firstComposition=true firstSelection=true requestCount=1 imeSetupFailureDiagnostics=true" > "$output_file"
    ;;
  thread-composer-suggestion-interaction-instrumentation.txt)
    echo "THREAD_COMPOSER_SUGGESTION_INTERACTION_PASS editorFocusedAfterTap=true text=#d imageAction=true outsideDismissed=true" > "$output_file"
    ;;
  attachment-download-runtime-instrumentation.txt)
    if [[ "${MOCK_FAIL_ASSERTION:-0}" == "1" ]]; then
      echo "missing required markers" > "$output_file"
    else
      cat > "$output_file" <<'EOF'
TASK2_RUNTIME scenario=slow_complete
TASK2_RUNTIME scenario=cancel_paths
TASK2_RUNTIME scenario=pause_resume
TASK2_RUNTIME scenario=whole_timeout
TASK2_RUNTIME scenario=publishing_replacement
TASK2_RUNTIME scenario=generation_safety
EOF
    fi
    ;;
  thread-align-right-bounds-instrumentation.txt)
    cat > "$output_file" <<'EOF'
ALIGN_RIGHT_GEOMETRY_long
ALIGN_RIGHT_GEOMETRY_sent
ALIGN_RIGHT_GEOMETRY_sending
ALIGN_RIGHT_GEOMETRY_failed
ALIGN_RIGHT_GEOMETRY_retry
EOF
    ;;
  *)
    echo "Instrumentation OK: $test_class (1 test)" > "$output_file"
    ;;
esac
exit 0
FAKE_RETRY
chmod +x "$MOCK_BIN/run-instrumentation-with-retry.sh"

# Mock prepare_android_emulator_ime.sh
cat > "$MOCK_BIN/prepare_android_emulator_ime.sh" <<'FAKE_IME'
#!/usr/bin/env bash
touch "$1"
exit 0
FAKE_IME
chmod +x "$MOCK_BIN/prepare_android_emulator_ime.sh"

# 3. Test scenario: Full suite pass (with THREAD_NEWER_PAGINATION_GATE_REQUIRED=true)
TEST_DIR_PASS="$TMP_DIR/test_pass"
mkdir -p "$TEST_DIR_PASS"
(
  cd "$TEST_DIR_PASS"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_PASS/mock_state"
  export SKIP_EMULATOR_PREP=true
  export SKIP_APK_INSTALL=true
  export THREAD_NEWER_PAGINATION_GATE_REQUIRED=true
  export GITHUB_EVENT_NAME=push

  # Symlink runner dependencies so runner finds our mock scripts in its dir
  # (the runner looks in "$HERE/" for run-instrumentation-with-retry.sh and prepare_android_emulator_ime.sh)
  # For this unit test, run in a sandbox that has symlinks:
  mkdir -p "$TEST_DIR_PASS/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_PASS/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_PASS/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_PASS/scripts_ci/"
  chmod +x "$TEST_DIR_PASS/scripts_ci/"*

  "$TEST_DIR_PASS/scripts_ci/run-first-character-emulator-suite.sh" > suite_out.txt
) || fail "Full suite pass scenario returned non-zero"

test -f "$TEST_DIR_PASS/first-character-baseline-suite-summary.txt" || fail "summary file missing"
grep -Fq "Suite verdict:   PASSED" "$TEST_DIR_PASS/first-character-baseline-suite-summary.txt" || fail "expected PASSED verdict"
grep -Fq "Executed:        17 (17 passed, 0 failed)" "$TEST_DIR_PASS/first-character-baseline-suite-summary.txt" || fail "expected 17 passed"
grep -Fq "Not run:         0" "$TEST_DIR_PASS/first-character-baseline-suite-summary.txt" || fail "expected 0 not run"
grep -Fq "status=passed test=thread-newer-auto-pagination" "$TEST_DIR_PASS/first-character-baseline-suite-summary.txt" || fail "missing manifest row"

# task #58 observability: thread-newer-auto-pagination must collect store-side
# diagnostics evidence (pre/post launch listings, unfiltered raw logcat,
# scoped SlockKuikly logcat, per-launch diagnostics JSONL).
for f in   thread-newer-auto-pagination-logcat.txt   thread-newer-auto-pagination-logcat-slockkuikly.txt   thread-newer-auto-pagination-diagnostics-before.txt   thread-newer-auto-pagination-diagnostics-after.txt   thread-newer-auto-pagination-diagnostics.jsonl; do
  test -f "$TEST_DIR_PASS/$f" || fail "missing thread-newer collection file $f"
done
grep -Fq "launch-mock001" "$TEST_DIR_PASS/thread-newer-auto-pagination-diagnostics-after.txt" || fail "post launch listing missing mock launch"
grep -Fq "files/raft_diagnostics_logs/launch-mock001/raft-diagnostics.jsonl" "$TEST_DIR_PASS/thread-newer-auto-pagination-diagnostics.jsonl" || fail "diagnostics jsonl missing per-launch header"
grep -Fq "mock-diagnostics-record" "$TEST_DIR_PASS/thread-newer-auto-pagination-diagnostics.jsonl" || fail "diagnostics jsonl missing pulled records"
grep -Fq "logcat -c" "$TEST_DIR_PASS/mock_state/adb_calls.txt" || fail "thread-newer pre-hook did not clear logcat"
# upload manifest must carry every collected file
for f in   thread-newer-auto-pagination-logcat.txt   thread-newer-auto-pagination-logcat-slockkuikly.txt   thread-newer-auto-pagination-diagnostics-before.txt   thread-newer-auto-pagination-diagnostics-after.txt   thread-newer-auto-pagination-diagnostics.jsonl; do
  grep -Fq "$f" "$WORKFLOW" || fail "workflow upload manifest missing $f"
done
pass "full suite pass scenario verified"

# 4. Test scenario: Conditional skip (THREAD_NEWER_PAGINATION_GATE_REQUIRED=false)
TEST_DIR_SKIP="$TMP_DIR/test_skip"
mkdir -p "$TEST_DIR_SKIP"
(
  cd "$TEST_DIR_SKIP"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_SKIP/mock_state"
  export SKIP_EMULATOR_PREP=true
  export SKIP_APK_INSTALL=true
  export THREAD_NEWER_PAGINATION_GATE_REQUIRED=false
  export GITHUB_EVENT_NAME=pull_request

  mkdir -p "$TEST_DIR_SKIP/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_SKIP/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_SKIP/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_SKIP/scripts_ci/"
  chmod +x "$TEST_DIR_SKIP/scripts_ci/"*

  "$TEST_DIR_SKIP/scripts_ci/run-first-character-emulator-suite.sh" > suite_out.txt
) || fail "Conditional skip scenario returned non-zero"

grep -Fq "Suite verdict:   PASSED" "$TEST_DIR_SKIP/first-character-baseline-suite-summary.txt" || fail "expected PASSED verdict"
grep -Fq "Executed:        16 (16 passed, 0 failed)" "$TEST_DIR_SKIP/first-character-baseline-suite-summary.txt" || fail "expected 16 executed"
grep -Fq "Not run:         1 (1 conditional skipped, 0 unreached)" "$TEST_DIR_SKIP/first-character-baseline-suite-summary.txt" || fail "expected 1 conditional skipped"
grep -Fq "status=not_run test=thread-newer-auto-pagination rc=- duration=- class=build.raft.app.shared.SlockThreadNewerAutoPaginationConnectedTest reason=skipped:condition_not_met" "$TEST_DIR_SKIP/first-character-baseline-suite-summary.txt" || fail "missing manifest skipped row"
pass "conditional skip scenario verified"

# 5. Test scenario: Failure isolation (Task #465 flake in composer-session-retirement)
# Verifies that when composer-session-retirement fails:
# - Its post-test diagnostics (dumpsys, logcat) are still generated
# - All downstream tests (5 through 17) continue to execute!
# - Manifest records failure for composer-session-retirement but pass for downstream
# - Overall exit code is 1
TEST_DIR_FAIL="$TMP_DIR/test_fail"
mkdir -p "$TEST_DIR_FAIL"
rc_fail=0
(
  cd "$TEST_DIR_FAIL"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_FAIL/mock_state"
  export SKIP_EMULATOR_PREP=true
  export SKIP_APK_INSTALL=true
  export THREAD_NEWER_PAGINATION_GATE_REQUIRED=true
  export MOCK_FAILING_CLASS="SlockThreadImePaintConnectedTest"

  mkdir -p "$TEST_DIR_FAIL/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_FAIL/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_FAIL/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_FAIL/scripts_ci/"
  chmod +x "$TEST_DIR_FAIL/scripts_ci/"*

  "$TEST_DIR_FAIL/scripts_ci/run-first-character-emulator-suite.sh" > suite_out.txt
) || rc_fail=$?

test "$rc_fail" -eq 1 || fail "Expected exit 1 on failure, got $rc_fail"
grep -Fq "Suite verdict:   FAILED" "$TEST_DIR_FAIL/first-character-baseline-suite-summary.txt" || fail "expected FAILED verdict"
grep -Fq "Executed:        17 (16 passed, 1 failed)" "$TEST_DIR_FAIL/first-character-baseline-suite-summary.txt" || fail "expected 16 passed 1 failed"
grep -Fq "status=failed test=composer-session-retirement" "$TEST_DIR_FAIL/first-character-baseline-suite-summary.txt" || fail "expected failed status for composer-session-retirement"

# Verify that downstream tests executed despite the failure
grep -Fq "status=passed test=thread-composer-suggestion-interaction" "$TEST_DIR_FAIL/first-character-baseline-suite-summary.txt" || fail "test 5 was swallowed!"
grep -Fq "status=passed test=attachment-atomic-sink" "$TEST_DIR_FAIL/first-character-baseline-suite-summary.txt" || fail "test 8 was swallowed!"
grep -Fq "status=passed test=durable-partition-migration" "$TEST_DIR_FAIL/first-character-baseline-suite-summary.txt" || fail "test 16 was swallowed!"
grep -Fq "status=passed test=thread-newer-auto-pagination" "$TEST_DIR_FAIL/first-character-baseline-suite-summary.txt" || fail "test 17 was swallowed!"

# Verify post-execution diagnostics were generated for the failed test
test -f "$TEST_DIR_FAIL/composer-session-retirement-input-method.txt" || fail "diagnostics missing for failed test"
test -f "$TEST_DIR_FAIL/composer-session-retirement-window.txt" || fail "window diagnostics missing"
test -f "$TEST_DIR_FAIL/composer-session-retirement-logcat.txt" || fail "logcat missing"
pass "failure isolation scenario verified (downstream tests protected, diagnostics preserved)"

# 6. Test scenario: Post-assertion failure detection
TEST_DIR_ASSERT="$TMP_DIR/test_assert"
mkdir -p "$TEST_DIR_ASSERT"
rc_assert=0
(
  cd "$TEST_DIR_ASSERT"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_ASSERT/mock_state"
  export SKIP_EMULATOR_PREP=true
  export SKIP_APK_INSTALL=true
  export THREAD_NEWER_PAGINATION_GATE_REQUIRED=true
  export MOCK_FAIL_ASSERTION=1

  mkdir -p "$TEST_DIR_ASSERT/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_ASSERT/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_ASSERT/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_ASSERT/scripts_ci/"
  chmod +x "$TEST_DIR_ASSERT/scripts_ci/"*

  "$TEST_DIR_ASSERT/scripts_ci/run-first-character-emulator-suite.sh" > suite_out.txt
) || rc_assert=$?

test "$rc_assert" -eq 1 || fail "Expected exit 1 on assertion failure, got $rc_assert"
grep -Fq "status=failed test=attachment-download-runtime" "$TEST_DIR_ASSERT/first-character-baseline-suite-summary.txt" || fail "expected failed status for attachment-download-runtime"
grep -Fq "reason=assertion_failed" "$TEST_DIR_ASSERT/first-character-baseline-suite-summary.txt" || fail "expected assertion_failed reason"
pass "post-assertion failure scenario verified"

# 6b. Test scenario: thread-newer-auto-pagination failure still collects diagnostics
# (task #58: store/fetch write evidence must survive a failing run, collected
# before any cleanup and without guessing the newest launch dir)
TEST_DIR_TN="$TMP_DIR/test_thread_newer_fail"
mkdir -p "$TEST_DIR_TN"
rc_tn=0
(
  cd "$TEST_DIR_TN"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_TN/mock_state"
  export SKIP_EMULATOR_PREP=true
  export SKIP_APK_INSTALL=true
  export THREAD_NEWER_PAGINATION_GATE_REQUIRED=true
  export MOCK_FAILING_CLASS="SlockThreadNewerAutoPaginationConnectedTest"

  mkdir -p "$TEST_DIR_TN/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_TN/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_TN/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_TN/scripts_ci/"
  chmod +x "$TEST_DIR_TN/scripts_ci/"*

  "$TEST_DIR_TN/scripts_ci/run-first-character-emulator-suite.sh" "thread-newer-auto-pagination" > suite_out.txt
) || rc_tn=$?

test "$rc_tn" -eq 1 || fail "Expected exit 1 on thread-newer failure, got $rc_tn"
grep -Fq "status=failed test=thread-newer-auto-pagination" "$TEST_DIR_TN/first-character-baseline-suite-summary.txt" || fail "expected failed status for thread-newer-auto-pagination"
for f in \
  thread-newer-auto-pagination-logcat.txt \
  thread-newer-auto-pagination-logcat-slockkuikly.txt \
  thread-newer-auto-pagination-diagnostics-before.txt \
  thread-newer-auto-pagination-diagnostics-after.txt \
  thread-newer-auto-pagination-diagnostics.jsonl; do
  test -f "$TEST_DIR_TN/$f" || fail "failed run missing collection file $f"
done
grep -Fq "mock-diagnostics-record" "$TEST_DIR_TN/thread-newer-auto-pagination-diagnostics.jsonl" || fail "failed run diagnostics jsonl empty"
pass "thread-newer failure collection scenario verified"

# 6c. Test scenario: thread-newer SIGTERM mid-instrumentation still archives evidence
# (LiBai review: EXIT cleanup must collect the in-flight test's raw logcat and
# diagnostics when the run is killed before run_post_hook returns)
TEST_DIR_TERM="$TMP_DIR/test_thread_newer_term"
mkdir -p "$TEST_DIR_TERM"

# A retry shim that records its own pgid then sleeps: the outer harness
# SIGTERMs the whole suite process group while instrumentation is in flight,
# which is the real CI timeout path LiBai exercised.
cat > "$TEST_DIR_TERM/term-retry.sh" <<'FAKE_TERM'
#!/usr/bin/env bash
set -euo pipefail
output_file="$1"
echo "INSTRUMENTATION_STATUS: running" > "$output_file"
echo "ready" > "${MOCK_STATE_DIR:?}/instrumentation_started"
sleep 30
exit 1
FAKE_TERM
chmod +x "$TEST_DIR_TERM/term-retry.sh"

(
  cd "$TEST_DIR_TERM"
  mkdir -p scripts_ci
  cp "$SUITE_RUNNER" scripts_ci/run-first-character-emulator-suite.sh
  cp term-retry.sh scripts_ci/run-instrumentation-with-retry.sh
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" scripts_ci/
  chmod +x scripts_ci/*
)

# Run the suite in its own session/process group so we can SIGTERM the
# entire group (the real CI timeout path); env vars passed explicitly since
# setsid inherits only the exported environment.
mkdir -p "$TEST_DIR_TERM/mock_state"
setsid env PATH="$MOCK_BIN:$PATH" MOCK_STATE_DIR="$TEST_DIR_TERM/mock_state" \
  SKIP_EMULATOR_PREP=true SKIP_APK_INSTALL=true THREAD_NEWER_PAGINATION_GATE_REQUIRED=true \
  bash -c 'cd "$1" && bash scripts_ci/run-first-character-emulator-suite.sh "thread-newer-auto-pagination" > suite_out.txt 2>&1' _ "$TEST_DIR_TERM" &
suite_pid=$!

# Wait until the suite is inside instrumentation, then SIGTERM its process group.
for _ in $(seq 1 100); do
  [ -f "$TEST_DIR_TERM/mock_state/instrumentation_started" ] && break
  sleep 0.1
done
# SIGTERM the bash process actually running the suite (not the setsid/env
# wrapper — that would just orphan the suite). The instrumentation shim is
# left sleeping, matching the reviewer harness where TERM lands on the runner
# mid-instrumentation and the EXIT trap must still harvest evidence.
suite_bash_pid="$(pgrep -P "$suite_pid" -f "run-first-character-emulator-suite.sh" | head -n 1)"
if [ -z "$suite_bash_pid" ]; then
  suite_bash_pid="$(pgrep -f "scripts_ci/run-first-character-emulator-suite.sh" | head -n 1)"
fi
[ -n "$suite_bash_pid" ] || fail "could not resolve suite bash pid"
kill -TERM "$suite_bash_pid" 2>/dev/null || true
# Poll the artifacts: collection happens inside the trap and the files may
# appear a beat after the killed process exits.
for _ in $(seq 1 100); do
  [ -f "$TEST_DIR_TERM/thread-newer-auto-pagination-diagnostics.jsonl" ] && \
    [ -f "$TEST_DIR_TERM/thread-newer-auto-pagination-logcat.txt" ] && break
  sleep 0.1
done
wait "$suite_pid" 2>/dev/null || true

test -f "$TEST_DIR_TERM/thread-newer-auto-pagination-logcat.txt" || fail "SIGTERM path missing raw logcat"
test -f "$TEST_DIR_TERM/thread-newer-auto-pagination-diagnostics.jsonl" || fail "SIGTERM path missing diagnostics jsonl"
grep -Fq "mock-diagnostics-record" "$TEST_DIR_TERM/thread-newer-auto-pagination-diagnostics.jsonl" || fail "SIGTERM path diagnostics jsonl empty"
test -f "$TEST_DIR_TERM/first-character-baseline-suite-summary.txt" || fail "SIGTERM path missing summary"
pass "thread-newer SIGTERM evidence scenario verified"

# 6d. Test scenario: hung adb still lets cleanup finish inside the bound
# (LiBai review round 3: collection must not stall cleanup past the job kill)
TEST_DIR_HANG="$TMP_DIR/test_thread_newer_hang"
mkdir -p "$TEST_DIR_HANG"
rc_hang=0
(
  cd "$TEST_DIR_HANG"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_HANG/mock_state"
  export SKIP_EMULATOR_PREP=true
  export SKIP_APK_INSTALL=true
  export THREAD_NEWER_PAGINATION_GATE_REQUIRED=true
  export MOCK_ADB_HANG=1
  export THREAD_NEWER_ADB_TIMEOUT_SECONDS=1

  mkdir -p "$TEST_DIR_HANG/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_HANG/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_HANG/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_HANG/scripts_ci/"
  chmod +x "$TEST_DIR_HANG/scripts_ci/"*

  hang_start=$(date +%s)
  "$TEST_DIR_HANG/scripts_ci/run-first-character-emulator-suite.sh" "thread-newer-auto-pagination" > suite_out.txt
  echo "hang_elapsed=$(( $(date +%s) - hang_start ))" > "$TEST_DIR_HANG/hang_elapsed.txt"
) || rc_hang=$?

test "$rc_hang" -eq 0 || fail "hung-adb scenario exited $rc_hang, expected 0 (test itself passes; only collection degrades)"
hang_elapsed="$(grep -oE '[0-9]+' "$TEST_DIR_HANG/hang_elapsed.txt")"
[ "$hang_elapsed" -lt 30 ] || fail "hung-adb collection took ${hang_elapsed}s, bound exceeded"
test -f "$TEST_DIR_HANG/first-character-baseline-suite-summary.txt" || fail "summary missing under hung adb"
test -f "$TEST_DIR_HANG/thread-newer-auto-pagination-diagnostics.jsonl" || fail "jsonl missing under hung adb"
pass "hung-adb bounded collection scenario verified"

# 6e. Test scenario: overall harvest bound on TERM path marks collection
# incomplete instead of hanging the EXIT trap
TEST_DIR_HT="$TMP_DIR/test_thread_newer_hang_term"
mkdir -p "$TEST_DIR_HT"
cat > "$TEST_DIR_HT/term-retry.sh" <<'FAKE_TERM'
#!/usr/bin/env bash
echo "INSTRUMENTATION_STATUS: running" > "$1"
echo "ready" > "${MOCK_STATE_DIR:?}/instrumentation_started"
sleep 30
exit 1
FAKE_TERM
chmod +x "$TEST_DIR_HT/term-retry.sh"

mkdir -p "$TEST_DIR_HT/scripts_ci" "$TEST_DIR_HT/mock_state"
cp "$SUITE_RUNNER" "$TEST_DIR_HT/scripts_ci/run-first-character-emulator-suite.sh"
cp "$TEST_DIR_HT/term-retry.sh" "$TEST_DIR_HT/scripts_ci/run-instrumentation-with-retry.sh"
cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_HT/scripts_ci/"
chmod +x "$TEST_DIR_HT/scripts_ci/"*

setsid env PATH="$MOCK_BIN:$PATH" MOCK_STATE_DIR="$TEST_DIR_HT/mock_state" \
  SKIP_EMULATOR_PREP=true SKIP_APK_INSTALL=true THREAD_NEWER_PAGINATION_GATE_REQUIRED=true \
  MOCK_ADB_HANG=1 THREAD_NEWER_ADB_TIMEOUT_SECONDS=1 THREAD_NEWER_COLLECT_TIMEOUT_SECONDS=1 \
  bash -c 'cd "$1" && bash scripts_ci/run-first-character-emulator-suite.sh "thread-newer-auto-pagination" > suite_out.txt 2>&1' _ "$TEST_DIR_HT" &
ht_pid=$!
for _ in $(seq 1 100); do
  [ -f "$TEST_DIR_HT/mock_state/instrumentation_started" ] && break
  sleep 0.1
done
ht_suite_pid="$(pgrep -P "$ht_pid" -f "run-first-character-emulator-suite.sh" | head -n 1)"
[ -z "$ht_suite_pid" ] && ht_suite_pid="$(pgrep -f "test_thread_newer_hang_term.*run-first-character" | head -n 1)"
[ -n "$ht_suite_pid" ] || fail "hang-term: suite pid unresolved"
kill -TERM "$ht_suite_pid" 2>/dev/null || true
ht_wait=0
wait "$ht_pid" 2>/dev/null || ht_wait=$?
test -f "$TEST_DIR_HT/first-character-baseline-suite-summary.txt" || fail "hang-term: summary missing"
test -f "$TEST_DIR_HT/thread-newer-auto-pagination-collection-status.txt" || fail "hang-term: incomplete marker missing"
grep -Fq "COLLECTION_INCOMPLETE reason=abort_harvest_timeout" "$TEST_DIR_HT/thread-newer-auto-pagination-collection-status.txt" || fail "hang-term: marker missing reason"
pass "hung-adb abort-harvest bound scenario verified"


# 7. Test scenario: Premature abort / trap safety
TEST_DIR_TRAP="$TMP_DIR/test_trap"
mkdir -p "$TEST_DIR_TRAP"
rc_trap=0
(
  cd "$TEST_DIR_TRAP"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_TRAP/mock_state"
  export MOCK_ADB_WAIT_FAIL=1

  mkdir -p "$TEST_DIR_TRAP/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_TRAP/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_TRAP/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_TRAP/scripts_ci/"
  chmod +x "$TEST_DIR_TRAP/scripts_ci/"*

  "$TEST_DIR_TRAP/scripts_ci/run-first-character-emulator-suite.sh" > suite_out.txt 2>&1
) || rc_trap=$?

test "$rc_trap" -ne 0 || fail "Expected non-zero exit on wait-for-device abort"
test -f "$TEST_DIR_TRAP/first-character-baseline-suite-summary.txt" || fail "summary file missing on abort"
grep -Fq "Suite verdict:   FAILED" "$TEST_DIR_TRAP/first-character-baseline-suite-summary.txt" || fail "expected FAILED verdict on abort"
grep -Fq "Executed:        0" "$TEST_DIR_TRAP/first-character-baseline-suite-summary.txt" || fail "expected 0 executed"
grep -Fq "17 unreached" "$TEST_DIR_TRAP/first-character-baseline-suite-summary.txt" || fail "expected 17 unreached"
pass "premature abort trap safety verified"

# 8. Test scenario: Partial CLI selection
TEST_DIR_SELECT="$TMP_DIR/test_select"
mkdir -p "$TEST_DIR_SELECT"
rc_select=0
(
  cd "$TEST_DIR_SELECT"
  export PATH="$MOCK_BIN:$PATH"
  export MOCK_STATE_DIR="$TEST_DIR_SELECT/mock_state"

  mkdir -p "$TEST_DIR_SELECT/scripts_ci"
  cp "$SUITE_RUNNER" "$TEST_DIR_SELECT/scripts_ci/run-first-character-emulator-suite.sh"
  cp "$MOCK_BIN/run-instrumentation-with-retry.sh" "$TEST_DIR_SELECT/scripts_ci/"
  cp "$MOCK_BIN/prepare_android_emulator_ime.sh" "$TEST_DIR_SELECT/scripts_ci/"
  chmod +x "$TEST_DIR_SELECT/scripts_ci/"*

  "$TEST_DIR_SELECT/scripts_ci/run-first-character-emulator-suite.sh" "inactive-host-rendering" > suite_out.txt
) || rc_select=$?

test "$rc_select" -eq 0 || fail "Expected exit 0 for single selected test passing, got $rc_select"
grep -Fq "Suite verdict:   PASSED" "$TEST_DIR_SELECT/first-character-baseline-suite-summary.txt" || fail "expected PASSED verdict for single test selection"
grep -Fq "status=passed test=inactive-host-rendering" "$TEST_DIR_SELECT/first-character-baseline-suite-summary.txt" || fail "expected passed for selected test"
grep -Fq "status=not_run test=first-character-baseline rc=- duration=- class=build.raft.app.shared.SlockComposerFirstCharacterBaselineTest reason=skipped:not_selected" "$TEST_DIR_SELECT/first-character-baseline-suite-summary.txt" || fail "expected skipped:not_selected for unselected tests"
pass "CLI test selection scenario verified"

echo "=== All run-first-character-emulator-suite unit tests PASSED ==="
