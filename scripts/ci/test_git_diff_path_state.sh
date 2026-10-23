#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT/scripts/ci/git-diff-path-state.sh"
WORKFLOW="$ROOT/.github/workflows/test.yml"
SAFE_WORKSPACE_ACTION="$ROOT/.github/actions/git-safe-workspace/action.yml"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

THREAD_PATHS=(
  compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt
  compose/shared/src/commonMain/kotlin/build/raft/app/LazyViewportController.kt
  compose/shared/src/commonMain/kotlin/build/raft/app/ThreadNewerPagination.kt
  compose/androidApp/src/androidTest/java/build/raft/app/shared/RaftThreadNewerAutoPaginationConnectedTest.kt
  compose/shared/src/visualFixtures/kotlin/build/raft/app/ThreadNewerAutoPaginationVisualFixture.kt
)

repo="$TMP_ROOT/repo"
git init -q "$repo"
git -C "$repo" config user.name ci-fixture
git -C "$repo" config user.email ci-fixture@example.invalid
mkdir -p \
  "$repo/compose/shared/src/commonMain/kotlin/build/raft/app" \
  "$repo/compose/shared/src/visualFixtures/kotlin/build/raft/app" \
  "$repo/docs"
printf 'thread base\n' > "$repo/${THREAD_PATHS[0]}"
printf 'fixture base\n' > "$repo/${THREAD_PATHS[4]}"
git -C "$repo" add .
git -C "$repo" commit -qm base
base="$(git -C "$repo" rev-parse HEAD)"

printf 'unrelated\n' > "$repo/docs/unrelated.md"
git -C "$repo" add docs/unrelated.md
git -C "$repo" commit -qm unrelated
unrelated="$(git -C "$repo" rev-parse HEAD)"

printf 'thread changed\n' >> "$repo/${THREAD_PATHS[0]}"
git -C "$repo" add "${THREAD_PATHS[0]}"
git -C "$repo" commit -qm thread-changed
thread_changed="$(git -C "$repo" rev-parse HEAD)"

printf 'fixture changed\n' >> "$repo/${THREAD_PATHS[4]}"
git -C "$repo" add "${THREAD_PATHS[4]}"
git -C "$repo" commit -qm fixture-changed
fixture_changed="$(git -C "$repo" rev-parse HEAD)"

fixture_helper="$repo/scripts/ci/git-diff-path-state.sh"
mkdir -p "$(dirname "$fixture_helper")"
cp "$HELPER" "$fixture_helper"

capture_rc() {
  local base_ref="$1"
  local head_ref="$2"
  shift 2
  local rc=0
  (cd "$repo" && "$fixture_helper" "$base_ref" "$head_ref" -- "$@") \
    >/dev/null 2>>"$TMP_ROOT/git-diff.stderr" || rc=$?
  printf '%s' "$rc"
}

assert_rc() {
  local name="$1"
  local expected="$2"
  shift 2
  local actual
  actual="$(capture_rc "$@")"
  test "$actual" -eq "$expected" || fail "$name expected rc=$expected, got rc=$actual"
  printf 'ok %s rc=%s\n' "$name" "$actual"
}

assert_error_rc() {
  local name="$1"
  shift
  local actual
  actual="$(capture_rc "$@")"
  test "$actual" -gt 1 || fail "$name expected rc>1, got rc=$actual"
  printf 'ok %s rc=%s\n' "$name" "$actual"
}

assert_rc thread-unchanged 0 "$base" "$unrelated" "${THREAD_PATHS[@]}"
assert_rc thread-changed 1 "$unrelated" "$thread_changed" "${THREAD_PATHS[@]}"
assert_rc fixture-changed 1 "$unrelated" "$fixture_changed" "${THREAD_PATHS[@]}"
assert_error_rc thread-diff-error definitely-not-a-commit "$fixture_changed" "${THREAD_PATHS[@]}"

capture_outside_rc() {
  local base_ref="$1"
  local head_ref="$2"
  shift 2
  local rc=0
  (cd "$TMP_ROOT" && "$fixture_helper" "$base_ref" "$head_ref" -- "$@") \
    >/dev/null 2>>"$TMP_ROOT/git-diff.stderr" || rc=$?
  printf '%s' "$rc"
}

assert_outside_rc() {
  local name="$1"
  local expected="$2"
  shift 2
  local actual
  actual="$(capture_outside_rc "$@")"
  test "$actual" -eq "$expected" || fail "$name expected rc=$expected, got rc=$actual"
  printf 'ok %s rc=%s\n' "$name" "$actual"
}

assert_outside_rc outside-repo-unchanged 0 "$base" "$unrelated" "${THREAD_PATHS[@]}"
assert_outside_rc outside-repo-changed 1 "$unrelated" "$thread_changed" "${THREAD_PATHS[@]}"
assert_outside_rc outside-repo-fixture-changed 1 "$unrelated" "$fixture_changed" "${THREAD_PATHS[@]}"

capture_poisoned_env_rc() {
  local base_ref="$1"
  local head_ref="$2"
  shift 2
  local rc=0
  (
    cd "$TMP_ROOT"
    GIT_DIR="$TMP_ROOT/not-a-git-dir" \
      GIT_WORK_TREE="$TMP_ROOT/not-a-work-tree" \
      GIT_COMMON_DIR="$TMP_ROOT/not-a-common-dir" \
      GIT_INDEX_FILE="$TMP_ROOT/not-an-index" \
      GIT_OBJECT_DIRECTORY="$TMP_ROOT/not-an-object-dir" \
      GIT_ALTERNATE_OBJECT_DIRECTORIES="$TMP_ROOT/not-an-alternate-dir" \
      GIT_CEILING_DIRECTORIES="$TMP_ROOT" \
      GIT_DISCOVERY_ACROSS_FILESYSTEM=false \
      "$fixture_helper" "$base_ref" "$head_ref" -- "$@"
  ) >/dev/null 2>>"$TMP_ROOT/git-diff.stderr" || rc=$?
  printf '%s' "$rc"
}

assert_poisoned_env_rc() {
  local name="$1"
  local expected="$2"
  shift 2
  local actual
  actual="$(capture_poisoned_env_rc "$@")"
  test "$actual" -eq "$expected" || fail "$name expected rc=$expected, got rc=$actual"
  printf 'ok %s rc=%s\n' "$name" "$actual"
}

assert_poisoned_env_rc poisoned-env-unchanged 0 "$base" "$unrelated" "${THREAD_PATHS[@]}"
assert_poisoned_env_rc poisoned-env-changed 1 "$unrelated" "$thread_changed" "${THREAD_PATHS[@]}"
assert_poisoned_env_rc poisoned-env-fixture-changed 1 "$unrelated" "$fixture_changed" "${THREAD_PATHS[@]}"

ROOT="$ROOT" \
  WORKFLOW="$WORKFLOW" \
  SAFE_WORKSPACE_ACTION="$SAFE_WORKSPACE_ACTION" \
  ruby -ropen3 -rtmpdir -ryaml <<'RUBY'
root = ENV.fetch("ROOT")
workflow_path = ENV.fetch("WORKFLOW")
safe_workspace_action_path = ENV.fetch("SAFE_WORKSPACE_ACTION")

def fail_contract(message)
  raise message
end

def find_step(workflow, name)
  matches = workflow.fetch("jobs").values.flat_map do |job|
    Array(job["steps"]).select { |step| step["name"] == name }
  end
  fail_contract("expected one #{name.inspect} step, found #{matches.length}") unless matches.length == 1
  matches.first.fetch("run")
end

# configure.sh itself is exercised by .github/actions/git-safe-workspace/test_configure.sh;
# these checks keep the action defaulting to the workspace-only scope and
# running that tested script.
def validate_git_safe_action_source(action)
  fail_contract("git-safe-workspace default scope is no longer workspace-only") unless
    action.dig("inputs", "scope", "default") == "workspace"
  action_steps = action.dig("runs", "steps") || []
  fail_contract("git-safe-workspace action bypasses its checked-in script") unless
    action_steps.any? { |step| step["run"] == 'bash "${GITHUB_ACTION_PATH}/configure.sh"' }
end

def wildcard_body(script)
  start = script.index(/^\s*\*\)\s*$/)
  fail_contract("missing wildcard error arm") unless start
  finish = script.index(/^\s*;;\s*$/, start)
  fail_contract("unterminated wildcard error arm") unless finish
  script[start...finish]
end

def validate_consumer(script, variable, paths)
  fail_contract("consumer bypasses the tri-state helper") unless
    script.include?("scripts/ci/git-diff-path-state.sh")
  direct_git_diff = script.lines.any? do |line|
    line.match?(/^\s*(?:if |elif )?git diff\b/)
  end
  fail_contract("consumer reintroduced a direct git diff") if direct_git_diff
  fail_contract("consumer does not capture the raw diff status") unless
    script.include?("diff_rc=0") && script.include?("|| diff_rc=$?")
  fail_contract("rc=0 no longer maps to unchanged") unless
    script.match?(/^\s*0\) #{Regexp.escape(variable)}=false ;;\s*$/)
  fail_contract("rc=1 no longer maps to changed") unless
    script.match?(/^\s*1\) #{Regexp.escape(variable)}=true ;;\s*$/)
  error_arm = wildcard_body(script)
  fail_contract("rc>1 no longer runs the affected gate") unless
    error_arm.match?(/^\s*#{Regexp.escape(variable)}=true\s*$/)
  fail_contract("rc>1 is no longer observable") unless error_arm.include?("::warning::")
  fail_contract("rc>1 exits before the affected gate") if
    error_arm.lines.any? { |line| line.match?(/^\s*exit\b/) }
  paths.each do |path|
    fail_contract("consumer lost path #{path}") unless script.include?(path)
  end
end

def validate_thread_non_pr(script)
  explicit_branch = <<~'SH'.strip
    if [ "$EVENT_NAME" != "pull_request" ]; then
      run_gate=true
    else
  SH
  fail_contract("Thread gate lacks an explicit non-PR run branch") unless
    script.include?(explicit_branch)
  prefix = script[0...script.index(explicit_branch)]
  fail_contract("Thread gate still relies on a case-external default") if
    prefix.lines.any? { |line| line.match?(/^\s*run_gate=/) }
end

def assert_thread_event_runs(script, root, event_name)
  Dir.mktmpdir("thread-gate-event") do |dir|
    github_env = File.join(dir, "github-env")
    stdout, stderr, status = Open3.capture3(
      {
        "EVENT_NAME" => event_name,
        "GITHUB_ENV" => github_env,
        "PR_BASE_SHA" => "unused-for-non-pr",
      },
      "bash",
      "-s",
      stdin_data: script,
      chdir: root,
    )
    fail_contract("Thread gate failed for #{event_name}: #{stderr}") unless status.success?
    output = File.read(github_env)
    fail_contract("Thread gate skipped for #{event_name}: #{output.inspect}") unless
      output.lines.include?("THREAD_NEWER_PAGINATION_GATE_REQUIRED=true\n")
    puts "ok thread-event-#{event_name}"
  end
end

def replace_once(text, from, to)
  fail_contract("mutation source missing: #{from}") unless text.include?(from)
  text.sub(from, to)
end

def mutate_error_arm(script, variable)
  body = wildcard_body(script)
  mutated = replace_once(body, "#{variable}=true", "#{variable}=false")
  script.sub(body, mutated)
end

def expect_failure(name)
  yield
rescue StandardError
  puts "ok mutation #{name}"
else
  fail_contract("mutation unexpectedly passed: #{name}")
end

safe_workspace_action = YAML.load_file(safe_workspace_action_path)
validate_git_safe_action_source(safe_workspace_action)

workflow = YAML.load_file(workflow_path)
thread_paths = %w[
  compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt
  compose/shared/src/commonMain/kotlin/build/raft/app/LazyViewportController.kt
  compose/shared/src/commonMain/kotlin/build/raft/app/ThreadNewerPagination.kt
  compose/androidApp/src/androidTest/java/build/raft/app/shared/RaftThreadNewerAutoPaginationConnectedTest.kt
  compose/shared/src/visualFixtures/kotlin/build/raft/app/ThreadNewerAutoPaginationVisualFixture.kt
]
thread = find_step(workflow, "Select Thread newer-pagination gate")
validate_consumer(thread, "run_gate", thread_paths)
validate_thread_non_pr(thread)
assert_thread_event_runs(thread, root, "push")
assert_thread_event_runs(thread, root, "workflow_dispatch")

expect_failure("git-safe-default-wildcard") do
  mutated = Marshal.load(Marshal.dump(safe_workspace_action))
  mutated.fetch("inputs").fetch("scope")["default"] = "all"
  validate_git_safe_action_source(mutated)
end
non_pr_branch = <<~'SH'.strip
  if [ "$EVENT_NAME" != "pull_request" ]; then
    run_gate=true
  else
SH
expect_failure("thread-non-pr-branch-removed") do
  old_default = <<~'SH'.strip
    run_gate=true
    if [ "$EVENT_NAME" = "pull_request" ]; then
  SH
  validate_thread_non_pr(replace_once(thread, non_pr_branch, old_default))
end
expect_failure("thread-non-pr-condition-flipped") do
  mutated = replace_once(
    thread,
    'if [ "$EVENT_NAME" != "pull_request" ]; then',
    'if [ "$EVENT_NAME" = "pull_request" ]; then',
  )
  validate_thread_non_pr(mutated)
end
expect_failure("thread-non-pr-default-flipped") do
  flipped = non_pr_branch.sub("run_gate=true", "run_gate=false")
  validate_thread_non_pr(replace_once(thread, non_pr_branch, flipped))
end

expect_failure("thread-unchanged-runs") do
  validate_consumer(replace_once(thread, "0) run_gate=false ;;", "0) run_gate=true ;;"), "run_gate", thread_paths)
end
expect_failure("thread-changed-skipped") do
  validate_consumer(replace_once(thread, "1) run_gate=true ;;", "1) run_gate=false ;;"), "run_gate", thread_paths)
end
expect_failure("thread-error-skipped") do
  validate_consumer(mutate_error_arm(thread, "run_gate"), "run_gate", thread_paths)
end
puts "git diff consumer workflow contract: PASS"
RUBY

printf 'git diff path-state contract: PASS\n'
