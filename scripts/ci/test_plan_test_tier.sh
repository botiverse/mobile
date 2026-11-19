#!/usr/bin/env bash
# Table test for scripts/ci/plan_test_tier.sh: event -> platforms, iOS lanes and
# Android readback jobs. Run by the Test workflow's plan job.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
planner="$here/plan_test_tier.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

paths() {
  local file="$tmp/$1"
  shift
  printf '%s\n' "$@" > "$file"
  printf '%s' "$file"
}

empty="$(paths empty)"
common="$(paths common compose/shared/src/commonMain/kotlin/A.kt)"
ios_app="$(paths ios_app compose/iosApp/iosApp/App.swift)"
ios_main="$(paths ios_main compose/shared/src/iosMain/kotlin/A.kt)"
ios_test="$(paths ios_test compose/shared/src/iosTest/kotlin/ATest.kt)"
ios_test_other="$(paths ios_test_other compose/ui-cmp/src/iosTest/kotlin/ATest.kt)"
ios_app_tests="$(paths ios_app_tests compose/iosApp/iosAppTests/RaftRouterLoginTransitionTests.swift)"
ios_ui_tests="$(paths ios_ui_tests compose/iosApp/iosAppUITests/HarnessTests.swift)"
android_only="$(paths android_only compose/androidApp/src/main/A.kt)"
docs="$(paths docs docs/readme.md)"
unknown="$(paths unknown __CI_CHANGED_PATHS_UNKNOWN__)"
ci_control="$(paths ci_control .github/workflows/test.yml)"

B='["github-xcode27.0"]'
BG='["github-xcode27.0","github-xcode27.1"]'
Q='["github-xcode27.1"]'

# expect <name> <expected "android ios xcode270 xcode271 lanes device canonical attachment ios-tests queue-candidate queue-reuse"> -- planner args
expect() {
  local name="$1" want="$2"
  shift 3
  local out got
  if ! out="$("$planner" "$@" 2>&1)"; then
    echo "FAIL $name: planner exited non-zero: $out"
    failures=$((failures + 1))
    return
  fi
  got="$(printf '%s\n' "$out" | awk -F= '
    /^run_android=/{a=$2} /^run_ios=/{i=$2} /^run_ios_xcode270=/{z=$2} /^run_ios_xcode271=/{x=$2}
    /^ios_lanes=/{l=$2} /^run_device_suite=/{d=$2} /^run_canonical_final=/{c=$2}
    /^attachment_api_levels=/{t=$2} /^run_ios_tests=/{s=$2}
    /^queue_candidate=/{q=$2} /^queue_reuse=/{r=$2}
    END{print a, i, z, x, l, d, c, t, s, q, r}')"
  if test "$got" != "$want"; then
    echo "FAIL $name"
    echo "  want: $want"
    echo "  got:  $got"
    failures=$((failures + 1))
  else
    echo "ok   $name -> $got"
  fi
}

expect_reason() {
  local name="$1" want="$2"
  shift 3
  local got
  got="$("$planner" "$@" 2>/dev/null | sed -n 's/^reason=//p')" || true
  if test "$got" != "$want"; then
    echo "FAIL $name: want reason=$want, got reason=$got"
    failures=$((failures + 1))
  else
    echo "ok   $name -> reason=$got"
  fi
}

expect_reject() {
  local name="$1"
  shift 2
  if "$planner" "$@" >/dev/null 2>&1; then
    echo "FAIL $name: planner accepted an invalid plan"
    failures=$((failures + 1))
  else
    echo "ok   $name -> rejected"
  fi
}

#                                    android ios  27.0 27.1  lanes device canonical attach ios-tests
# ios-tests (iOS test steps in the iOS compile job) is true only on the full
# tier: nightly, release-* pushes, scope=full, premerge into a release base and
# full-ci PRs. The merge queue, scope=ios and main pushes compile only, except
# that a queue batch or main push touching iOS test sources runs the tests.
# Stamp merge queue (2026-11-19): no iOS on pull_request or on Stamp's premerge
# dispatch into main; the queue candidate (Stamp's managed draft PR, head branch
# stamp-mq/<batch>) compiles iOS on the Xcode 27.1 lane alone, runs Android too
# and sets queue_candidate (no-op emulator jobs). A main push whose SHA carries
# a verified stamp/queue-tested status (8th arg true) plans queue_reuse only.
expect "pr commonMain"              "true false false false [] false false [] false false false"        -- pull_request "$common" false feature
expect "pr iosApp (no iOS on PRs)"  "false false false false [] false false [] false false false"       -- pull_request "$ios_app" false feature
expect "pr docs-only"               "false false false false [] false false [] false false false"       -- pull_request "$docs" false feature
expect "pr diff unknown (Android)"  "true false false false [] false false [] false false false"        -- pull_request "$unknown" false feature
expect "pr full-ci (no 27.1)"       "true true true false $B true false [31] true false false"          -- pull_request "$common" true feature
expect "pr full-ci iosApp (no 27.1)" "true true true false $B true false [31] true false false"         -- pull_request "$ios_app" true feature
expect "merge_group"                "true true true false $B false false [] false false false"          -- merge_group "$empty" false gh-readonly-queue/main/x
expect "queue candidate commonMain"     "true true false true $Q false false [] false true false"          -- pull_request "$common" false stamp-mq/b1
expect "queue candidate docs-only"      "true true false true $Q false false [] false true false"          -- pull_request "$docs" false stamp-mq/b1
expect "queue candidate android-only"   "true true false true $Q false false [] false true false"          -- pull_request "$android_only" false stamp-mq/b1
expect "queue candidate iosApp"         "true true false true $Q false false [] false true false"          -- pull_request "$ios_app" false stamp-mq/b1
expect "queue candidate iosTest"        "true true false true $Q false false [] true true false"           -- pull_request "$ios_test" false stamp-mq/b1
expect "queue candidate iosAppTests"    "true true false true $Q false false [] true true false"           -- pull_request "$ios_app_tests" false stamp-mq/b1
expect "queue candidate diff unknown"   "true true false true $Q false false [] false true false"          -- pull_request "$unknown" false stamp-mq/b1
expect "queue candidate nested id"      "true true false true $Q false false [] false true false"          -- pull_request "$common" false stamp-mq/main/42
expect "queue candidate full-ci label" "true true false true $Q false false [] false true false"  -- pull_request "$common" true stamp-mq/b1
expect "pr from look-alike branch"  "true false false false [] false false [] false false false" -- pull_request "$common" false feature/stamp-mq/x
expect "main push queue-tested reuse" "false false false false [] false false [] false false true" -- push "$ios_main" false main "" "" "" true
expect "main push reuse docs-only"  "false false false false [] false false [] false false true" -- push "$docs" false main "" "" "" true
expect "main push not queue-tested" "false true true false $B false false [] false false false" -- push "$ios_main" false main "" "" "" false
expect "release push ignores no reuse" "true true true true $BG false false [] true false false" -- push "$common" false release-1.20 "" "" "" false
expect "main push commonMain"       "true false false false [] false false [] false false false"        -- push "$common" false main
expect "main push iosMain"          "false true true false $B false false [] false false false"         -- push "$ios_main" false main
expect "main push iosApp (no 27.1)" "false true true false $B false false [] false false false"         -- push "$ios_app" false main
expect "main push iosTest"          "false true true false $B false false [] true false false"          -- push "$ios_test" false main
expect "main push ui-cmp iosTest"   "true true true false $B false false [] true false false"           -- push "$ios_test_other" false main
expect "main push iosAppTests"      "false true true false $B false false [] true false false"          -- push "$ios_app_tests" false main
expect "main push iosAppUITests"    "false true true false $B false false [] true false false"          -- push "$ios_ui_tests" false main
expect "main push android-only"     "true false false false [] false false [] false false false"        -- push "$android_only" false main
expect "release push"               "true true true true $BG false false [] true false false"           -- push "$common" false release-1.20
expect "release push iosApp"        "true true true true $BG false false [] true false false"           -- push "$ios_app" false release-1.20
expect "schedule"                   "true true true true $BG true true [29,31,34] true false false"     -- schedule "$empty" false main
expect "dispatch scope=pr"          "true false false false [] false false [] false false false"        -- workflow_dispatch "$empty" false feature pr
expect "dispatch scope=ios"         "false true true false $B false false [] false false false"         -- workflow_dispatch "$empty" false feature ios
expect "dispatch scope=ios xcode"   "false true true false $B false false [] false false false"         -- workflow_dispatch "$empty" false feature ios /Applications/Xcode_27.1.app
expect "dispatch scope=full (no 27.1)" "true true true false $B true true [29,31,34] true false false"  -- workflow_dispatch "$empty" false feature full
expect "premerge commonMain"        "false false false false [] false false [] false false false"       -- workflow_dispatch "$common" false feature premerge "" main
expect "premerge iosApp (queue)"    "false false false false [] false false [] false false false"       -- workflow_dispatch "$ios_app" false feature premerge "" main
expect "premerge iosMain (queue)"   "false false false false [] false false [] false false false"       -- workflow_dispatch "$ios_main" false feature premerge "" main
expect "premerge iosTest (queue)"   "false false false false [] false false [] false false false"       -- workflow_dispatch "$ios_test" false feature premerge "" main
expect "premerge iosAppTests (queue)" "false false false false [] false false [] false false false"     -- workflow_dispatch "$ios_app_tests" false feature premerge "" main
expect "pr iosTest (no iOS on PRs)" "false false false false [] false false [] false false false"       -- pull_request "$ios_test" false feature
expect "premerge android-only"      "false false false false [] false false [] false false false"       -- workflow_dispatch "$android_only" false feature premerge "" main
expect "premerge docs-only"         "false false false false [] false false [] false false false"       -- workflow_dispatch "$docs" false feature premerge "" main
expect "premerge ci-control (queue)" "false false false false [] false false [] false false false"      -- workflow_dispatch "$ci_control" false feature premerge "" main
expect "premerge diff unknown"      "true false false false [] false false [] false false false"        -- workflow_dispatch "$unknown" false feature premerge "" main
expect "premerge release/ base"     "true true true false $B true true [29,31,34] true false false"     -- workflow_dispatch "$android_only" false feature premerge "" release/1.20.0
expect "premerge release- base"     "true true true false $B true true [29,31,34] true false false"     -- workflow_dispatch "$docs" false feature premerge "" release-1.20.0
expect_reason "queue candidate reason"  "merge-queue-candidate"                   -- pull_request "$common" false stamp-mq/b1
expect_reason "queue reuse reason"      "main-push-queue-tested-reuse"            -- push "$ios_main" false main "" "" "" true
expect_reason "premerge reason iosMain" "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$ios_main" false feature premerge "" main
expect_reason "premerge reason no-ios"  "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$android_only" false feature premerge "" main
expect_reason "premerge reason common"  "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$common" false feature premerge "" main
expect_reason "premerge reason release" "workflow_dispatch-premerge:release-full" -- workflow_dispatch "$common" false feature premerge "" release/1.20.0
expect_reason "premerge reason unknown" "workflow_dispatch-premerge:diff-unavailable-fail-closed" -- workflow_dispatch "$unknown" false feature premerge "" main

expect_reject "dispatch empty scope"          -- workflow_dispatch "$empty" false feature ""
expect_reject "dispatch unknown scope"        -- workflow_dispatch "$empty" false feature nightly
expect_reject "dispatch scope=pr with xcode"  -- workflow_dispatch "$empty" false feature pr /Applications/Xcode_27.1.app
expect_reject "premerge without base ref"    -- workflow_dispatch "$common" false feature premerge
expect_reject "premerge no-ios with xcode"   -- workflow_dispatch "$android_only" false feature premerge /Applications/Xcode_27.1.app main
expect_reject "premerge iosApp with xcode"   -- workflow_dispatch "$ios_app" false feature premerge /Applications/Xcode_27.1.app main
expect_reject "push to stamp-mq (no push trigger)" -- push "$common" false stamp-mq/b1
expect_reject "queue-tested on release push"  -- push "$common" false release-1.20 "" "" "" true
expect_reject "queue-tested on schedule"      -- schedule "$empty" false main "" "" "" true
expect_reject "queue-tested on pull_request"  -- pull_request "$common" false stamp-mq/b1 "" "" "" true
expect_reject "queue-tested non-boolean"      -- push "$common" false main "" "" "" yes
expect_reject "push to unsupported ref"       -- push "$common" false feature

if test "$failures" -ne 0; then
  echo "plan_test_tier self-test: $failures FAILED" >&2
  exit 1
fi
echo "plan_test_tier self-test: PASS"
