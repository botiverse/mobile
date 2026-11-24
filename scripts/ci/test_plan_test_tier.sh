#!/usr/bin/env bash
# Table test for scripts/ci/plan_test_tier.sh: event -> platforms, iOS lanes,
# Android readback jobs and the Android unit tier. Run by the Test workflow's plan job.

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
designsystem="$(paths designsystem compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/RaftButton.kt)"
designsystem_mixed="$(paths designsystem_mixed compose/shared/src/commonMain/kotlin/A.kt compose/designsystem/screenshots/vcase/vcase.manifest.json)"
deps_bump="$(paths deps_bump compose/gradle/libs.versions.toml)"
module_build="$(paths module_build compose/shared/build.gradle.kts compose/shared/src/commonMain/kotlin/A.kt)"
third_party_patch="$(paths third_party_patch third_party/compose-foundation-1.12.1/README.md compose/shared/src/commonMain/kotlin/A.kt)"

B='["github-xcode27.0"]'
BG='["github-xcode27.0","github-xcode27.1"]'
Q='["github-xcode27.1"]'

# expect <name> <expected "android ios xcode270 xcode271 lanes device attachment ios-tests queue-candidate queue-reuse"> -- planner args
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
    /^ios_lanes=/{l=$2} /^run_device_suite=/{d=$2}
    /^attachment_api_levels=/{t=$2} /^run_ios_tests=/{s=$2}
    /^queue_candidate=/{q=$2} /^queue_reuse=/{r=$2}
    END{print a, i, z, x, l, d, t, s, q, r}')"
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

# expect_android <name> <expected "android_unit_scope run_designsystem_tests run_roborazzi"> -- planner args
expect_android() {
  local name="$1" want="$2"
  shift 3
  local out got
  if ! out="$("$planner" "$@" 2>&1)"; then
    echo "FAIL $name: planner exited non-zero: $out"
    failures=$((failures + 1))
    return
  fi
  got="$(printf '%s\n' "$out" | awk -F= '/^android_unit_scope=/{u=$2} /^run_designsystem_tests=/{d=$2} /^run_roborazzi=/{r=$2} END{print u, d, r}')"
  if test "$got" != "$want"; then
    echo "FAIL $name: want android unit tier '$want', got '$got'"
    failures=$((failures + 1))
  else
    echo "ok   $name -> android unit tier $got"
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

#                                    android ios  27.0 27.1  lanes device attach ios-tests
# ios-tests (iOS test steps in the iOS compile job) is true only on the full
# tier: nightly, release-* pushes, scope=full, premerge into a release base and
# full-ci PRs. The merge queue, scope=ios and main pushes compile only, except
# that a queue batch or main push touching iOS test sources runs the tests.
# Stamp merge queue (2026-11-19): no iOS on pull_request or on Stamp's premerge
# dispatch into main; the queue candidate (Stamp's managed draft PR, head branch
# stamp-mq/<batch>) compiles iOS on the Xcode 27.1 lane alone, runs Android too
# and sets queue_candidate (no-op emulator jobs). A main push whose SHA carries
# a verified stamp/queue-tested status (8th arg true) plans queue_reuse only.
expect "pr commonMain"              "true false false false [] false [] false false false"        -- pull_request "$common" false feature
expect "pr iosApp (no iOS on PRs)"  "false false false false [] false [] false false false"       -- pull_request "$ios_app" false feature
expect "pr docs-only"               "false false false false [] false [] false false false"       -- pull_request "$docs" false feature
expect "pr diff unknown (Android)"  "true false false false [] false [] false false false"        -- pull_request "$unknown" false feature
expect "pr full-ci (no 27.1)"       "true true true false $B true [31] true false false"          -- pull_request "$common" true feature
expect "pr full-ci iosApp (no 27.1)" "true true true false $B true [31] true false false"         -- pull_request "$ios_app" true feature
expect "merge_group"                "true true true false $B false [] false false false"          -- merge_group "$empty" false gh-readonly-queue/main/x
expect "queue candidate commonMain"     "true true false true $Q false [] false true false"          -- pull_request "$common" false stamp-mq/b1
expect "queue candidate docs-only"      "true true false true $Q false [] false true false"          -- pull_request "$docs" false stamp-mq/b1
expect "queue candidate android-only"   "true true false true $Q false [] false true false"          -- pull_request "$android_only" false stamp-mq/b1
expect "queue candidate iosApp"         "true true false true $Q false [] false true false"          -- pull_request "$ios_app" false stamp-mq/b1
expect "queue candidate iosTest"        "true true false true $Q false [] true true false"           -- pull_request "$ios_test" false stamp-mq/b1
expect "queue candidate iosAppTests"    "true true false true $Q false [] true true false"           -- pull_request "$ios_app_tests" false stamp-mq/b1
expect "queue candidate diff unknown"   "true true false true $Q false [] false true false"          -- pull_request "$unknown" false stamp-mq/b1
expect "queue candidate nested id"      "true true false true $Q false [] false true false"          -- pull_request "$common" false stamp-mq/main/42
expect "queue candidate full-ci label" "true true false true $Q false [] false true false"  -- pull_request "$common" true stamp-mq/b1
expect "pr from look-alike branch"  "true false false false [] false [] false false false" -- pull_request "$common" false feature/stamp-mq/x
expect "main push queue-tested reuse" "false false false false [] false [] false false true" -- push "$ios_main" false main "" "" "" true
expect "main push reuse docs-only"  "false false false false [] false [] false false true" -- push "$docs" false main "" "" "" true
expect "main push not queue-tested" "false true true false $B false [] false false false" -- push "$ios_main" false main "" "" "" false
expect "release push ignores no reuse" "true true true true $BG false [] true false false" -- push "$common" false release-1.20 "" "" "" false
expect "main push commonMain"       "true false false false [] false [] false false false"        -- push "$common" false main
expect "main push iosMain"          "false true true false $B false [] false false false"         -- push "$ios_main" false main
expect "main push iosApp (no 27.1)" "false true true false $B false [] false false false"         -- push "$ios_app" false main
expect "main push iosTest"          "false true true false $B false [] true false false"          -- push "$ios_test" false main
expect "main push ui-cmp iosTest"   "true true true false $B false [] true false false"           -- push "$ios_test_other" false main
expect "main push iosAppTests"      "false true true false $B false [] true false false"          -- push "$ios_app_tests" false main
expect "main push iosAppUITests"    "false true true false $B false [] true false false"          -- push "$ios_ui_tests" false main
expect "main push android-only"     "true false false false [] false [] false false false"        -- push "$android_only" false main
expect "release push"               "true true true true $BG false [] true false false"           -- push "$common" false release-1.20
expect "release push iosApp"        "true true true true $BG false [] true false false"           -- push "$ios_app" false release-1.20
expect "schedule"                   "true true true true $BG true [29,31,34] true false false"     -- schedule "$empty" false main
expect "dispatch scope=pr"          "true false false false [] false [] false false false"        -- workflow_dispatch "$empty" false feature pr
expect "dispatch scope=ios"         "false true true false $B false [] false false false"         -- workflow_dispatch "$empty" false feature ios
expect "dispatch scope=ios xcode"   "false true true false $B false [] false false false"         -- workflow_dispatch "$empty" false feature ios /Applications/Xcode_27.1.app
expect "dispatch scope=full (no 27.1)" "true true true false $B true [29,31,34] true false false"  -- workflow_dispatch "$empty" false feature full
expect "premerge commonMain"        "false false false false [] false [] false false false"       -- workflow_dispatch "$common" false feature premerge "" main
expect "premerge iosApp (queue)"    "false false false false [] false [] false false false"       -- workflow_dispatch "$ios_app" false feature premerge "" main
expect "premerge iosMain (queue)"   "false false false false [] false [] false false false"       -- workflow_dispatch "$ios_main" false feature premerge "" main
expect "premerge iosTest (queue)"   "false false false false [] false [] false false false"       -- workflow_dispatch "$ios_test" false feature premerge "" main
expect "premerge iosAppTests (queue)" "false false false false [] false [] false false false"     -- workflow_dispatch "$ios_app_tests" false feature premerge "" main
expect "pr iosTest (no iOS on PRs)" "false false false false [] false [] false false false"       -- pull_request "$ios_test" false feature
expect "premerge android-only"      "false false false false [] false [] false false false"       -- workflow_dispatch "$android_only" false feature premerge "" main
expect "premerge docs-only"         "false false false false [] false [] false false false"       -- workflow_dispatch "$docs" false feature premerge "" main
expect "premerge ci-control (queue)" "false false false false [] false [] false false false"      -- workflow_dispatch "$ci_control" false feature premerge "" main
expect "premerge diff unknown"      "true false false false [] false [] false false false"        -- workflow_dispatch "$unknown" false feature premerge "" main
expect "premerge release/ base"     "true true true false $B true [29,31,34] true false false"     -- workflow_dispatch "$android_only" false feature premerge "" release/1.20.0
expect "premerge release- base"     "true true true false $B true [29,31,34] true false false"     -- workflow_dispatch "$docs" false feature premerge "" release-1.20.0
expect_reason "queue candidate reason"  "merge-queue-candidate"                   -- pull_request "$common" false stamp-mq/b1
expect_reason "queue reuse reason"      "main-push-queue-tested-reuse"            -- push "$ios_main" false main "" "" "" true
expect_reason "premerge reason iosMain" "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$ios_main" false feature premerge "" main
expect_reason "premerge reason no-ios"  "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$android_only" false feature premerge "" main
expect_reason "premerge reason common"  "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$common" false feature premerge "" main
expect_reason "premerge reason release" "workflow_dispatch-premerge:release-full" -- workflow_dispatch "$common" false feature premerge "" release/1.20.0
expect_reason "premerge reason unknown" "workflow_dispatch-premerge:diff-unavailable-fail-closed" -- workflow_dispatch "$unknown" false feature premerge "" main

# Android unit & Roborazzi tier (artin 2026-11-22 & 2026-11-24, #proj-mobile:#340).
# - jvm = compile everything, plain JVM unit tests only (no Robolectric); the
#   design system's non-screenshot unit & Compose UI logic tests only when
#   compose/designsystem/ changed or the diff is unknown.
# - run_roborazzi=false on all ordinary PRs (including designsystem and
#   build/deps PRs), scope=pr, and main premerge with unknown diff: Roborazzi
#   / vcase screenshot golden verification runs only post-merge (main,
#   release-*), on the nightly schedule, merge queue, full-ci PRs, scope=full,
#   premerge into a release base, and record_screenshots=true.
# - all = the whole non-screenshot suite (and both golden sets when
#   run_roborazzi=true). none = no Android job.
# 9th arg: the record_screenshots dispatch input.
#                                          scope designsystem roborazzi
expect_android "pr commonMain"             "jvm false false"  -- pull_request "$common" false feature
expect_android "pr android-only"           "jvm false false"  -- pull_request "$android_only" false feature
expect_android "pr designsystem"           "jvm true false"   -- pull_request "$designsystem" false feature
expect_android "pr designsystem + shared"  "jvm true false"   -- pull_request "$designsystem_mixed" false feature
expect_android "pr ci-control"             "jvm false false"  -- pull_request "$ci_control" false feature
expect_android "pr version catalog bump"   "all true false"   -- pull_request "$deps_bump" false feature
expect_android "pr module build script"    "all true false"   -- pull_request "$module_build" false feature
expect_android "pr third_party + shared"   "all true false"   -- pull_request "$third_party_patch" false feature
expect_android "pr diff unknown"           "jvm true false"   -- pull_request "$unknown" false feature
expect_android "pr docs-only (no Android)" "none false false" -- pull_request "$docs" false feature
expect_android "pr look-alike branch"      "jvm false false"  -- pull_request "$common" false feature/stamp-mq/x
expect_android "pr full-ci"                "all true true"    -- pull_request "$common" true feature
expect_android "queue candidate"           "all true true"    -- pull_request "$common" false stamp-mq/b1
expect_android "queue candidate docs-only" "all true true"    -- pull_request "$docs" false stamp-mq/b1
expect_android "merge_group"               "all true true"    -- merge_group "$empty" false gh-readonly-queue/main/x
expect_android "main push commonMain"      "all true true"    -- push "$common" false main
expect_android "main push designsystem"    "all true true"    -- push "$designsystem" false main
expect_android "main push android-only"    "all true true"    -- push "$android_only" false main
expect_android "main push iosMain"         "none false false" -- push "$ios_main" false main
expect_android "main push queue reuse"     "none false false" -- push "$common" false main "" "" "" true
expect_android "release push"              "all true true"    -- push "$docs" false release-1.20
expect_android "schedule"                  "all true true"    -- schedule "$empty" false main
expect_android "dispatch scope=pr"         "jvm true false"   -- workflow_dispatch "$empty" false feature pr
expect_android "dispatch scope=pr record"  "all true true"    -- workflow_dispatch "$empty" false feature pr "" "" false true
expect_android "dispatch scope=full"       "all true true"    -- workflow_dispatch "$empty" false feature full
expect_android "dispatch scope=full record" "all true true"   -- workflow_dispatch "$empty" false feature full "" "" false true
expect_android "dispatch scope=ios"        "none false false" -- workflow_dispatch "$empty" false feature ios
expect_android "dispatch scope=ios record" "none false false" -- workflow_dispatch "$empty" false feature ios "" "" false true
expect_android "premerge commonMain"       "none false false" -- workflow_dispatch "$common" false feature premerge "" main
expect_android "premerge diff unknown"     "jvm true false"   -- workflow_dispatch "$unknown" false feature premerge "" main
expect_android "premerge release/ base"    "all true true"    -- workflow_dispatch "$android_only" false feature premerge "" release/1.20.0

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
expect_reject "record on pull_request"        -- pull_request "$common" false feature "" "" "" false true
expect_reject "record on main push"           -- push "$common" false main "" "" "" false true
expect_reject "record non-boolean"            -- workflow_dispatch "$empty" false feature pr "" "" false yes

if test "$failures" -ne 0; then
  echo "plan_test_tier self-test: $failures FAILED" >&2
  exit 1
fi
echo "plan_test_tier self-test: PASS"
