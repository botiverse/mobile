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
android_only="$(paths android_only compose/androidApp/src/main/A.kt)"
docs="$(paths docs docs/readme.md)"
unknown="$(paths unknown __CI_CHANGED_PATHS_UNKNOWN__)"
ci_control="$(paths ci_control .github/workflows/test.yml)"

B='["blacksmith-xcode27.0"]'
BG='["blacksmith-xcode27.0","github-xcode27.1"]'

# expect <name> <expected "android ios xcode271 lanes device canonical attachment"> -- planner args
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
    /^run_android=/{a=$2} /^run_ios=/{i=$2} /^run_ios_xcode271=/{x=$2} /^ios_lanes=/{l=$2}
    /^run_device_suite=/{d=$2} /^run_canonical_final=/{c=$2} /^attachment_api_levels=/{t=$2}
    END{print a, i, x, l, d, c, t}')"
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

#                                    android ios  27.1  lanes device canonical attach
expect "pr commonMain"              "true false false [] false false []"        -- pull_request "$common" false feature
expect "pr iosApp (no iOS on PRs)"  "false false false [] false false []"       -- pull_request "$ios_app" false feature
expect "pr docs-only"               "false false false [] false false []"       -- pull_request "$docs" false feature
expect "pr diff unknown"            "true true false $B false false []"         -- pull_request "$unknown" false feature
expect "pr full-ci (no 27.1)"       "true true false $B true false [31]"        -- pull_request "$common" true feature
expect "pr full-ci iosApp (no 27.1)" "true true false $B true false [31]"       -- pull_request "$ios_app" true feature
expect "merge_group"                "true true false $B false false []"         -- merge_group "$empty" false gh-readonly-queue/main/x
expect "main push commonMain"       "true false false [] false false []"        -- push "$common" false main
expect "main push iosMain"          "false true false $B false false []"        -- push "$ios_main" false main
expect "main push iosApp (no 27.1)" "false true false $B false false []"        -- push "$ios_app" false main
expect "main push android-only"     "true false false [] false false []"        -- push "$android_only" false main
expect "release push"               "true true true $BG false false []"         -- push "$common" false release-1.20
expect "release push iosApp"        "true true true $BG false false []"         -- push "$ios_app" false release-1.20
expect "schedule"                   "true true true $BG true true [29,31,34]"   -- schedule "$empty" false main
expect "dispatch scope=pr"          "true false false [] false false []"        -- workflow_dispatch "$empty" false feature pr
expect "dispatch scope=ios"         "false true false $B false false []"        -- workflow_dispatch "$empty" false feature ios
expect "dispatch scope=ios xcode"   "false true false $B false false []"        -- workflow_dispatch "$empty" false feature ios /Applications/Xcode_27.1.app
expect "dispatch scope=full (no 27.1)" "true true false $B true true [29,31,34]" -- workflow_dispatch "$empty" false feature full
expect "premerge commonMain"        "false false false [] false false []"        -- workflow_dispatch "$common" false feature premerge "" main
expect "premerge iosApp"            "false true false $B false false []"        -- workflow_dispatch "$ios_app" false feature premerge "" main
expect "premerge iosMain"            "false true false $B false false []"        -- workflow_dispatch "$ios_main" false feature premerge "" main
expect "premerge android-only"      "false false false [] false false []"       -- workflow_dispatch "$android_only" false feature premerge "" main
expect "premerge docs-only"         "false false false [] false false []"       -- workflow_dispatch "$docs" false feature premerge "" main
expect "premerge ci-control"        "false true false $B false false []"        -- workflow_dispatch "$ci_control" false feature premerge "" main
expect "premerge diff unknown"      "true true false $B false false []"         -- workflow_dispatch "$unknown" false feature premerge "" main
expect "premerge release/ base"     "true true false $B true true [29,31,34]"   -- workflow_dispatch "$android_only" false feature premerge "" release/1.20.0
expect "premerge release- base"     "true true false $B true true [29,31,34]"   -- workflow_dispatch "$docs" false feature premerge "" release-1.20.0
expect "premerge iosApp xcode"      "false true false $B false false []"        -- workflow_dispatch "$ios_app" false feature premerge /Applications/Xcode_27.1.app main
expect_reason "premerge reason ios"     "workflow_dispatch-premerge:ios"          -- workflow_dispatch "$ios_main" false feature premerge "" main
expect_reason "premerge reason no-ios"  "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$android_only" false feature premerge "" main
expect_reason "premerge reason common"  "workflow_dispatch-premerge:no-ios"       -- workflow_dispatch "$common" false feature premerge "" main
expect_reason "premerge reason release" "workflow_dispatch-premerge:release-full" -- workflow_dispatch "$common" false feature premerge "" release/1.20.0
expect_reason "premerge reason unknown" "workflow_dispatch-premerge:diff-unavailable-fail-closed" -- workflow_dispatch "$unknown" false feature premerge "" main

expect_reject "dispatch empty scope"          -- workflow_dispatch "$empty" false feature ""
expect_reject "dispatch unknown scope"        -- workflow_dispatch "$empty" false feature nightly
expect_reject "dispatch scope=pr with xcode"  -- workflow_dispatch "$empty" false feature pr /Applications/Xcode_27.1.app
expect_reject "premerge without base ref"    -- workflow_dispatch "$common" false feature premerge
expect_reject "premerge no-ios with xcode"   -- workflow_dispatch "$android_only" false feature premerge /Applications/Xcode_27.1.app main
expect_reject "push to unsupported ref"       -- push "$common" false feature

if test "$failures" -ne 0; then
  echo "plan_test_tier self-test: $failures FAILED" >&2
  exit 1
fi
echo "plan_test_tier self-test: PASS"
