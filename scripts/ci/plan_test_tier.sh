#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "usage: plan_test_tier.sh <event-name> <changed-paths-file> <full-ci:true|false> <ref-name>" >&2
  echo "       plan_test_tier.sh --self-test" >&2
  exit 2
}

# A missing before/head object, a shallow checkout, or any other failure to
# produce a trustworthy diff is represented by this synthetic path. It is
# deliberately impossible in the repository and always selects every platform.
UNKNOWN_PATH_SENTINEL="__CI_CHANGED_PATHS_UNKNOWN__"

is_deterministic_all_path() {
  case "$1" in
    "$UNKNOWN_PATH_SENTINEL"|.github/workflows/test.yml|scripts/ci/plan_test_tier.sh|scripts/ci/verify_test_tier_results.mjs|scripts/ci/test_ci_tiering_contract.sh|third_party/kuikly-ui|.github/rulesets/mobile-main-merge-queue.json)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

mark_all_platforms() {
  run_android=true
  run_ios=true
  run_ohos=true
}

# Task #26 option A (artin 2026-08-09): main pushes keep the iOS compile only
# when the push can plausibly affect iOS: iOS-specific source, the Kuikly
# gitlink, or CI-control paths. Ordinary commonMain pushes fall back to the
# nightly iOS readback instead of recompiling iOS on every push. Unknown
# changed paths stay fail-closed (classify already marked all platforms and
# this function keeps them).
main_push_keeps_ios() {
  local changed_paths_file="$1"
  local path
  while IFS= read -r path || test -n "$path"; do
    path="${path%$'\r'}"
    test -n "$path" || continue
    if is_deterministic_all_path "$path"; then
      return 0
    fi
    case "$path" in
      compose/iosApp/*|compose/shared/src/iosMain/*|compose/shared/src/iosTest/*|compose/shared/src/nativeMain/*|compose/shared/src/nativeInterop/*)
        return 0
        ;;
    esac
  done < "$changed_paths_file"
  return 1
}


# Classify one repository path. The function mutates the platform booleans in
# the caller and returns 0 only when Test owns coverage for that path.
#
# Shared and build-system paths are intentionally fail-closed to all platforms.
# A platform-only path selects only that platform. A gitlink never gets inferred
# from its top-level name: third_party/kuikly-ui is handled above as an explicit
# deterministic-all control path because its external commit range is unbounded.
classify_relevant_path() {
  case "$1" in
    compose/androidApp/*|compose/shared/src/androidMain/*|compose/shared/src/androidUnitTest/*|compose/shared/src/androidInstrumentedTest/*)
      run_android=true
      ;;
    compose/iosApp/*|compose/shared/src/iosMain/*|compose/shared/src/iosTest/*)
      run_ios=true
      ;;
    compose/ohosApp/*|compose/ohosBridge/*|compose/shared/src/ohosArm64Main/*|compose/shared/src/ohosArm64Test/*)
      run_ohos=true
      ;;
    compose/shared/src/nativeMain/*|compose/shared/src/nativeInterop/*)
      run_ios=true
      run_ohos=true
      ;;
    compose/*)
      # commonMain/commonTest, shared build files, version catalogs, Gradle
      # configuration, generated contracts and unrecognized new source sets can
      # affect every target. New layouts therefore fail closed instead of being
      # silently treated as irrelevant.
      mark_all_platforms
      ;;
    .gitmodules|.github/actions/*|.github/gradle-cache-epoch|.github/gradle-cache-quarantine)
      mark_all_platforms
      ;;
    scripts/check_commonmain_map_compat.sh|scripts/check_markdown_spike_println_gate.sh|scripts/check_auth_session_writes.sh|scripts/check_attachment_mapper_formatting.sh|scripts/check_canonical_message_v2_reaction_vector.sh|scripts/check_read_state_behavior_vector.sh|scripts/check_read_state_sync_domain_registration.sh|scripts/audit_translucent_action_tokens.py|scripts/build_kuikly_fork.sh|scripts/prepare_*.sh|scripts/test_kuikly_plugin_source_contract.mjs|scripts/test_mr_resource_staging_source_contract.mjs|scripts/test_process_network_lifecycle_source_contract.mjs|scripts/ci/*)
      mark_all_platforms
      ;;
    third_party/ktor-ohos/*|third_party/stately|third_party/sqliter|third_party/sqldelight|third_party/kuikly-compose-markdown/*|third_party/markdown-parser/*)
      # These pins/sources participate in shared metadata or native dependency
      # preparation. Treat them as cross-platform unless a future, separately
      # reviewed classifier proves a narrower contract.
      mark_all_platforms
      ;;
    *)
      return 1
      ;;
  esac
  return 0
}

platform_summary() {
  local summary=""
  test "$run_android" = true && summary="android"
  if test "$run_ios" = true; then
    test -n "$summary" && summary+=","
    summary+="ios"
  fi
  if test "$run_ohos" = true; then
    test -n "$summary" && summary+=","
    summary+="ohos"
  fi
  test -n "$summary" || summary="none"
  printf '%s' "$summary"
}

classify_changed_paths() {
  local changed_paths_file="$1"
  local event_prefix="$2"
  local path
  local saw_ci_control=false
  local saw_relevant=false
  local saw_unknown=false

  test -f "$changed_paths_file"
  while IFS= read -r path || test -n "$path"; do
    path="${path%$'\r'}"
    test -n "$path" || continue
    if is_deterministic_all_path "$path"; then
      mark_all_platforms
      saw_relevant=true
      saw_ci_control=true
      test "$path" = "$UNKNOWN_PATH_SENTINEL" && saw_unknown=true
    elif classify_relevant_path "$path"; then
      saw_relevant=true
    fi
  done < "$changed_paths_file"

  if test "$saw_unknown" = true; then
    reason="${event_prefix}-diff-unavailable-fail-closed"
  elif test "$saw_ci_control" = true; then
    reason="${event_prefix}-ci-control"
  elif test "$saw_relevant" = true; then
    reason="${event_prefix}-paths:$(platform_summary)"
  else
    reason="${event_prefix}-no-test-paths"
  fi
}

emit_plan() {
  local event_name="$1"
  local changed_paths_file="$2"
  local full_ci="$3"
  local ref_name="$4"
  run_android=false
  run_ios=false
  run_ohos=false
  local run_emulators=false
  reason=""

  case "$full_ci" in
    true|false) ;;
    *)
      echo "full-ci flag must be true or false" >&2
      return 1
      ;;
  esac

  case "$event_name" in
    pull_request)
      classify_changed_paths "$changed_paths_file" "pull-request"
      if test "$full_ci" = true; then
        mark_all_platforms
        run_emulators=true
        reason="pull-request-full-ci"
      fi
      ;;
    merge_group)
      mark_all_platforms
      # Emulator/connected jobs stay out of the blocking queue until their
      # infra-abort retry contract has landed and has fresh flake evidence.
      run_emulators=false
      reason="merge-group-deterministic-all"
      ;;
    push)
      case "$ref_name" in
        main)
          classify_changed_paths "$changed_paths_file" "main-push"
          # Task #26 option A: iOS compile only when the push can affect iOS;
          # ordinary pushes rely on the nightly iOS readback. Fail-closed:
          # unknown paths were already marked all-platform by classify and are
          # kept here.
          if test "$run_ios" = true && ! main_push_keeps_ios "$changed_paths_file"; then
            run_ios=false
            case "$reason" in
              main-push-paths:*) reason="main-push-paths:$(platform_summary)" ;;
            esac
          fi
          # Preserve post-merge Android device readback whenever the changed
          # paths select Android. iOS-only, OHOS-only and irrelevant pushes no
          # longer spend emulator capacity.
          test "$run_android" = true && run_emulators=true
          ;;
        release-*)
          # Preserve the release branch's deterministic full readback. Release
          # validation is not reduced by source-path inference.
          mark_all_platforms
          run_emulators=true
          reason="release-push-full-readback"
          ;;
        *)
          echo "unsupported Test workflow push ref: $ref_name" >&2
          return 1
          ;;
      esac
      ;;
    schedule|workflow_dispatch)
      mark_all_platforms
      run_emulators=true
      reason="${event_name}-full-readback"
      ;;
    *)
      echo "unsupported Test workflow event: $event_name" >&2
      return 1
      ;;
  esac

  if test "$run_emulators" = true && test "$run_android" != true; then
    echo "invalid CI plan: emulator jobs require Android" >&2
    return 1
  fi

  printf 'run_android=%s\n' "$run_android"
  printf 'run_ios=%s\n' "$run_ios"
  printf 'run_ohos=%s\n' "$run_ohos"
  printf 'run_emulators=%s\n' "$run_emulators"
  printf 'reason=%s\n' "$reason"
}

self_test() {
  local work
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN

  assert_plan() {
    local expected="$1"
    local event_name="$2"
    local full_ci="$3"
    local ref_name="$4"
    local paths="$5"
    printf '%s' "$paths" > "$work/paths"
    local actual
    actual="$(emit_plan "$event_name" "$work/paths" "$full_ci" "$ref_name")"
    if test "$actual" != "$expected"; then
      printf 'expected plan:\n%s\nactual plan:\n%s\n' "$expected" "$actual" >&2
      return 1
    fi
  }

  local none=$'run_android=false\nrun_ios=false\nrun_ohos=false\nrun_emulators=false'
  local android=$'run_android=true\nrun_ios=false\nrun_ohos=false\nrun_emulators=false'
  local ios=$'run_android=false\nrun_ios=true\nrun_ohos=false\nrun_emulators=false'
  local ohos=$'run_android=false\nrun_ios=false\nrun_ohos=true\nrun_emulators=false'
  local native=$'run_android=false\nrun_ios=true\nrun_ohos=true\nrun_emulators=false'
  local all=$'run_android=true\nrun_ios=true\nrun_ohos=true\nrun_emulators=false'
  local all_emulators=$'run_android=true\nrun_ios=true\nrun_ohos=true\nrun_emulators=true'

  assert_plan "$none"$'\nreason=pull-request-no-test-paths' \
    pull_request false feature $'docs/readme.md\n'
  assert_plan "$android"$'\nreason=pull-request-paths:android' \
    pull_request false feature $'compose/androidApp/src/main/kotlin/Example.kt\n'
  assert_plan "$ios"$'\nreason=pull-request-paths:ios' \
    pull_request false feature $'compose/iosApp/iosApp/Example.swift\n'
  assert_plan "$ohos"$'\nreason=pull-request-paths:ohos' \
    pull_request false feature $'compose/ohosApp/entry/src/main/ets/Example.ets\n'
  assert_plan "$native"$'\nreason=pull-request-paths:ios,ohos' \
    pull_request false feature $'compose/shared/src/nativeMain/kotlin/Example.kt\n'
  assert_plan "$all"$'\nreason=pull-request-paths:android,ios,ohos' \
    pull_request false feature $'compose/shared/src/commonMain/kotlin/Example.kt\n'
  assert_plan "$all"$'\nreason=pull-request-paths:android,ios,ohos' \
    pull_request false feature $'compose/gradle/libs.versions.toml\n'
  assert_plan "$all"$'\nreason=pull-request-paths:android,ios,ohos' \
    pull_request false feature $'scripts/test_process_network_lifecycle_source_contract.mjs\n'
  assert_plan "$all"$'\nreason=pull-request-ci-control' \
    pull_request false feature $'.github/workflows/test.yml\n'
  assert_plan "$all"$'\nreason=pull-request-ci-control' \
    pull_request false feature $'third_party/kuikly-ui\n'
  assert_plan "$all"$'\nreason=pull-request-diff-unavailable-fail-closed' \
    pull_request false feature "$UNKNOWN_PATH_SENTINEL"$'\n'
  assert_plan $'run_android=true\nrun_ios=true\nrun_ohos=false\nrun_emulators=false\nreason=pull-request-paths:android,ios' \
    pull_request false feature $'compose/androidApp/src/main/kotlin/Example.kt\ncompose/iosApp/iosApp/Example.swift\n'
  assert_plan "$all_emulators"$'\nreason=pull-request-full-ci' \
    pull_request true feature $'docs/readme.md\n'
  assert_plan "$all"$'\nreason=merge-group-deterministic-all' \
    merge_group false gh-readonly-queue/main/pr-1 ''
  assert_plan "$none"$'\nreason=main-push-no-test-paths' \
    push false main $'docs/readme.md\n'
  assert_plan $'run_android=true\nrun_ios=false\nrun_ohos=false\nrun_emulators=true\nreason=main-push-paths:android' \
    push false main $'compose/androidApp/src/main/kotlin/Example.kt\n'
  assert_plan $'run_android=false\nrun_ios=true\nrun_ohos=false\nrun_emulators=false\nreason=main-push-paths:ios' \
    push false main $'compose/iosApp/iosApp/Example.swift\n'
  assert_plan "$all_emulators"$'\nreason=main-push-diff-unavailable-fail-closed' \
    push false main "$UNKNOWN_PATH_SENTINEL"$'\n'
  # Task #26 option A: ordinary main pushes (commonMain catch-all) no longer
  # compile iOS; the nightly readback covers them.
  assert_plan $'run_android=true\nrun_ios=false\nrun_ohos=true\nrun_emulators=true\nreason=main-push-paths:android,ohos' \
    push false main $'compose/shared/src/commonMain/kotlin/Example.kt\n'
  # Pushes that can affect iOS keep the iOS compile.
  assert_plan "$all_emulators"$'\nreason=main-push-paths:android,ios,ohos' \
    push false main $'compose/shared/src/commonMain/kotlin/Example.kt\ncompose/iosApp/iosApp/Example.swift\n'
  assert_plan "$all_emulators"$'\nreason=main-push-ci-control' \
    push false main $'third_party/kuikly-ui\n'
  assert_plan "$all_emulators"$'\nreason=main-push-ci-control' \
    push false main $'.github/workflows/test.yml\n'
  assert_plan "$all_emulators"$'\nreason=release-push-full-readback' \
    push false release-1.9.0 $'docs/readme.md\n'
  assert_plan "$all_emulators"$'\nreason=schedule-full-readback' \
    schedule false main ''
  if emit_plan pull_request "$work/paths" maybe feature >/dev/null 2>&1; then
    echo "invalid full-ci flag unexpectedly passed" >&2
    return 1
  fi
  if emit_plan push "$work/paths" false feature >/dev/null 2>&1; then
    echo "unsupported push ref unexpectedly passed" >&2
    return 1
  fi
  if emit_plan issue_comment "$work/paths" false feature >/dev/null 2>&1; then
    echo "unsupported event unexpectedly passed" >&2
    return 1
  fi
  echo "CI path planner self-test: PASS"
}

if test "${1:-}" = --self-test; then
  test "$#" -eq 1 || usage
  self_test
  exit 0
fi

test "$#" -eq 4 || usage
emit_plan "$1" "$2" "$3" "$4"
