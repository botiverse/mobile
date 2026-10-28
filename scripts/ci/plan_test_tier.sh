#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "usage: plan_test_tier.sh <event-name> <changed-paths-file> <full-ci:true|false> <ref-name> [dispatch-scope:pr|full] [xcode-app-path]" >&2
  exit 2
}

# A missing before/head object, a shallow checkout, or any other failure to
# produce a trustworthy diff is represented by this synthetic path. It is
# deliberately impossible in the repository and always selects every platform.
UNKNOWN_PATH_SENTINEL="__CI_CHANGED_PATHS_UNKNOWN__"

is_deterministic_all_path() {
  case "$1" in
    "$UNKNOWN_PATH_SENTINEL"|.github/workflows/test.yml|scripts/ci/plan_test_tier.sh|scripts/ci/verify_test_tier_results.mjs|.github/rulesets/mobile-main-merge-queue.json)
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
}

# Task #26 option A (artin 2026-08-09): main pushes keep the iOS compile only
# when the push can plausibly affect iOS: iOS-specific source or CI-control
# paths. Ordinary commonMain pushes fall back to the nightly iOS readback
# instead of recompiling iOS on every push. Unknown changed paths stay
# fail-closed (classify already marked all platforms and this function keeps
# them).
# Task #37 (artin 2026-10-09): this gate is no longer consulted for pull
# requests — ordinary PRs never compile iOS at all (see emit_plan); it remains
# the main-push path gate. Explicit full-ci PRs are the PR-side escape hatch.
change_keeps_ios() {
  local changed_paths_file="$1"
  local path
  while IFS= read -r path || test -n "$path"; do
    path="${path%$'\r'}"
    test -n "$path" || continue
    if is_deterministic_all_path "$path"; then
      return 0
    fi
    case "$path" in
      compose/iosApp/*|compose/shared/src/iosMain/*|compose/shared/src/iosTest/*|compose/shared/src/nativeMain/*|compose/shared/src/nativeInterop/*|compose/core/src/iosMain/*|compose/core/src/iosTest/*|compose/ui-cmp/src/iosMain/*)
        return 0
        ;;
    esac
  done < "$changed_paths_file"
  return 1
}

main_push_keeps_ios() {
  change_keeps_ios "$@"
}


# Classify one repository path. The function mutates the platform booleans in
# the caller and returns 0 only when Test owns coverage for that path.
#
# Shared and build-system paths are intentionally fail-closed to all platforms.
# A platform-only path selects only that platform.
classify_relevant_path() {
  case "$1" in
    compose/androidApp/*|compose/shared/src/androidMain/*|compose/shared/src/androidUnitTest/*|compose/shared/src/androidInstrumentedTest/*)
      run_android=true
      ;;
    compose/iosApp/*|compose/shared/src/iosMain/*|compose/shared/src/iosTest/*)
      run_ios=true
      ;;
    compose/shared/src/nativeMain/*|compose/shared/src/nativeInterop/*)
      run_ios=true
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
    scripts/check_commonmain_map_compat.sh|scripts/check_auth_session_writes.sh|scripts/check_canonical_message_v2_reaction_vector.sh|scripts/check_server_activity_unread_fixture.sh|scripts/check_read_state_behavior_vector.sh|scripts/check_search_snippet_vector.sh|scripts/prepare_*.sh|scripts/ci/*)
      mark_all_platforms
      ;;
    third_party/kuikly-compose-markdown/*|third_party/markdown-parser/*)
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
  local dispatch_scope="${5:-}"
  local xcode_app_path="${6:-}"
  run_android=false
  run_ios=false
  local run_device_suite=false
  local run_canonical_final=false
  local attachment_api_levels="[]"
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
        # Task #26 closure: full-ci PR keeps the device suite but only the
        # API31 attachment leg; canonical-final stays nightly/manual only.
        run_device_suite=true
        run_canonical_final=false
        attachment_api_levels="[31]"
        reason="pull-request-full-ci"
      else
        # Task #37 (artin 2026-10-09): iOS compile runs only on the trunk
        # evidence chain (main/release pushes + nightly). Ordinary PRs never
        # compile iOS, including PRs that touch iOS-specific paths; an iOS
        # regression is caught by the main push readback (~12 min) and handled
        # fix-forward. The full-ci label remains the explicit escape hatch.
        # Fail-closed exception: an unavailable diff still selects every
        # platform, because "we don't know what changed" must not silently
        # skip coverage.
        case "$reason" in
          pull-request-diff-unavailable-fail-closed)
            ;;
          *)
            run_ios=false
            case "$reason" in
              pull-request-paths:*) reason="pull-request-paths:$(platform_summary)" ;;
            esac
            ;;
        esac
      fi
      ;;
    merge_group)
      mark_all_platforms
      # Emulator/connected jobs stay out of the blocking queue.
      run_device_suite=false
      run_canonical_final=false
      attachment_api_levels="[]"
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
          if test "$run_ios" = true && ! change_keeps_ios "$changed_paths_file"; then
            run_ios=false
            case "$reason" in
              main-push-paths:*) reason="main-push-paths:$(platform_summary)" ;;
            esac
          fi
          # Task #26 closure: routine main pushes no longer run the device
          # suite; nightly and explicit manual dispatch keep the readback.
          run_device_suite=false
          run_canonical_final=false
          attachment_api_levels="[]"
          ;;
        release-*)
          # Release branches keep the platform readback, but routine release
          # pushes no longer run the device suite (nightly/manual keep it).
          mark_all_platforms
          run_device_suite=false
          run_canonical_final=false
          attachment_api_levels="[]"
          reason="release-push-full-readback"
          ;;
        *)
          echo "unsupported Test workflow push ref: $ref_name" >&2
          return 1
          ;;
      esac
      ;;
    schedule)
      mark_all_platforms
      # Task #26 closure: nightly keeps the full device suite, the
      # canonical-final readback, and all three attachment API legs.
      run_device_suite=true
      run_canonical_final=true
      attachment_api_levels="[29,31,34]"
      reason="schedule-full-readback"
      ;;
    workflow_dispatch)
      # Task #37 (artin 2026-10-09): manual dispatch defaults to the
      # PR-equivalent tier instead of an unconditional full readback. A
      # dispatch has no trustworthy PR diff, so the pr tier is a fixed set:
      # Linux gates + Android compile, no iOS, no device suite. scope=full
      # keeps the deliberate full readback. An empty/unknown scope is rejected
      # so the choice is always explicit in the workflow input.
      case "$dispatch_scope" in
        pr)
          run_android=true
          run_ios=false
          run_device_suite=false
          run_canonical_final=false
          attachment_api_levels="[]"
          reason="workflow_dispatch-pr-tier"
          ;;
        full)
          mark_all_platforms
          run_device_suite=true
          run_canonical_final=true
          attachment_api_levels="[29,31,34]"
          reason="workflow_dispatch-full-readback"
          ;;
        *)
          echo "workflow_dispatch scope must be pr or full, got: '${dispatch_scope}'" >&2
          return 1
          ;;
      esac
      ;;
    *)
      echo "unsupported Test workflow event: $event_name" >&2
      return 1
      ;;
  esac

  if test "$run_device_suite" = true && test "$run_android" != true; then
    echo "invalid CI plan: device suite requires Android" >&2
    return 1
  fi
  if test "$run_canonical_final" = true && test "$run_android" != true; then
    echo "invalid CI plan: canonical-final requires Android" >&2
    return 1
  fi
  if test "$attachment_api_levels" != "[]" && test "$run_android" != true; then
    echo "invalid CI plan: attachment legs require Android" >&2
    return 1
  fi
  # xcode_app_path (workflow_dispatch) only reaches the iOS compile job. A plan that skips iOS
  # (scope=pr) would go green without ever compiling with that Xcode, so refuse it.
  if test -n "$xcode_app_path" && test "$run_ios" != true; then
    echo "::error::xcode_app_path=${xcode_app_path} is set but this plan skips the iOS compile (run_ios=${run_ios}, reason=${reason}). Re-dispatch with scope=full, or clear xcode_app_path." >&2
    return 1
  fi

  printf 'run_android=%s\n' "$run_android"
  printf 'run_ios=%s\n' "$run_ios"
  printf 'run_device_suite=%s\n' "$run_device_suite"
  printf 'run_canonical_final=%s\n' "$run_canonical_final"
  printf 'attachment_api_levels=%s\n' "$attachment_api_levels"
  printf 'reason=%s\n' "$reason"
}

test "$#" -ge 4 && test "$#" -le 6 || usage
emit_plan "$1" "$2" "$3" "$4" "${5:-}" "${6:-}"
