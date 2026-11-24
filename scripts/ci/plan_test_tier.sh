#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "usage: plan_test_tier.sh <event-name> <changed-paths-file> <full-ci:true|false> <ref-name> [dispatch-scope:pr|ios|premerge|full] [xcode-app-path] [premerge-base-ref] [queue-tested:true|false] [record-screenshots:true|false]" >&2
  echo "  ref-name: the PR head branch for pull_request (stamp-mq/* = Stamp merge-queue candidate), else github.ref_name" >&2
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
# Stamp merge queue (2026-11-19): Stamp's premerge dispatch no longer consults
# it either; the queue candidate (draft PR from stamp-mq/*) compiles iOS on every batch.
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
      compose/iosApp/*|compose/shared/src/iosMain/*|compose/shared/src/nativeMain/*|compose/shared/src/nativeInterop/*|compose/core/src/iosMain/*|compose/realtime-socket/src/iosMain/*|compose/ui-cmp/src/iosMain/*|compose/web-container/src/iosMain/*|*/iosTest/*)
        return 0
        ;;
    esac
  done < "$changed_paths_file"
  return 1
}

# iOS test sources: any iosTest source set, the iosAppTests XCTest bundle and
# the iosAppUITests harness. A change to one of these is about the iOS tests,
# so an iOS compile that would otherwise skip the tests (premerge, main push)
# runs them; a test-only iOS change must not go green on a compile alone.
change_touches_ios_tests() {
  local changed_paths_file="$1"
  local path
  while IFS= read -r path || test -n "$path"; do
    path="${path%$'\r'}"
    case "$path" in
      */iosTest/*|compose/iosApp/iosAppTests/*|compose/iosApp/iosAppUITests/*)
        return 0
        ;;
    esac
  done < "$changed_paths_file"
  return 1
}

main_push_keeps_ios() {
  change_keeps_ios "$@"
}

# The design system's tests (:designsystem:desktopTest without screenshot goldens
# on ordinary PRs, :designsystem:verifyRoborazziDesktop on full/post-merge tiers)
# on the jvm Android unit tier: only when the change touches compose/designsystem/.
# An unavailable diff fails closed to running them.
change_touches_designsystem() {
  local changed_paths_file="$1"
  local path
  while IFS= read -r path || test -n "$path"; do
    path="${path%$'\r'}"
    case "$path" in
      "$UNKNOWN_PATH_SENTINEL"|compose/designsystem/*)
        return 0
        ;;
    esac
  done < "$changed_paths_file"
  return 1
}

# Build and dependency files (version catalog, Gradle wrapper and properties,
# build scripts, buildSrc, the patched third_party Compose sources) change how
# every test runs: an ordinary pull request that touches one keeps the full
# non-screenshot Android unit suite (android_unit_scope=all, run_roborazzi=false),
# while Roborazzi screenshot golden verification stays on post-merge / merge
# queue / nightly / full-ci / record_screenshots.
change_touches_build_or_deps() {
  local changed_paths_file="$1"
  local path
  while IFS= read -r path || test -n "$path"; do
    path="${path%$'\r'}"
    case "$path" in
      compose/gradle/*|compose/gradle.properties|compose/*.gradle.kts|compose/*/build.gradle.kts|compose/buildSrc/*|third_party/*)
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
    scripts/check_auth_session_writes.sh|scripts/check_canonical_message_v2_reaction_vector.sh|scripts/check_server_activity_unread_fixture.sh|scripts/check_read_state_behavior_vector.sh|scripts/check_human_pixel_avatar_vector.sh|scripts/generate_human_pixel_avatars.mjs|scripts/check_notification_copy_vector.sh|scripts/prepare_*.sh|scripts/ci/*)
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
  local premerge_base_ref="${7:-}"
  local queue_tested="${8:-false}"
  local record_screenshots="${9:-false}"
  run_android=false
  run_ios=false
  local run_device_suite=false
  local attachment_api_levels="[]"
  # iOS compile lanes. The Xcode 27.0 lane (run_ios_xcode270) is the default
  # for every iOS run except the merge queue. The Xcode 27.1 lane
  # (run_ios_xcode271, the release SDK) runs on the nightly schedule and
  # release-* pushes next to it, and alone on the Stamp merge queue batch
  # candidate (draft PR from stamp-mq/*). Both run on GitHub's standard xcode-27 image, free on
  # this public repo but one of the org's 5 concurrent macOS slots each.
  local run_ios_xcode270=true
  local run_ios_xcode271=false
  # iOS tests in the iOS compile job (step 1 of the mobile test prune, Lagoon
  # 2026-11-13, artin's go-ahead): the K/N commonTest compile + iosTest
  # simulator run and the iosAppTests and adaptive-editor XCTests run only on
  # the full tier: nightly schedule, release-*
  # pushes, scope=full, premerge into a release base and full-ci PRs. Every
  # other iOS run (premerge, scope=ios, main push, merge queue) compiles the
  # iOS targets, links the framework and builds the host app only; iOS-only
  # behaviour regressions surface in the nightly within 24h. Exception: a
  # merge-queue batch or main push whose diff touches iOS test sources
  # (change_touches_ios_tests) runs the iOS tests too.
  local run_ios_tests=false
  local ios_lanes="[]"
  # Stamp merge queue (2026-11-19). queue_candidate: this pull_request run is
  # the queue's managed draft PR (head stamp-mq/<batch>); Stamp accepts the
  # batch only when every job of the run concluded success, so the emulator
  # jobs the plan does not select run as no-op successes instead of skipping.
  # queue_reuse: a main push whose SHA carries a verified stamp/queue-tested
  # status (scripts/ci/check_queue_tested.py); only queue-ci-reuse runs.
  local queue_candidate=false
  local queue_reuse=false
  # Android unit-test & Roborazzi screenshot tier (artin 2026-11-22: "PRs run
  # only a little, like slock"; #proj-mobile:#340, artin 2026-11-24: move
  # Roborazzi screenshot golden verification out of PR CI to post-merge /
  # nightly).
  # - android_unit_scope=jvm, the ordinary PR tier (pull_request without
  #   full-ci and not touching build/deps, scope=pr, a premerge into main that
  #   reruns Android): compiles every main and test source set as usual, then
  #   runs only the plain JVM unit tests (-Praft.unitTest.skipRobolectric=true)
  #   and :designsystem:desktopTest without screenshot goldens when
  #   run_designsystem_tests=true (the change touches compose/designsystem/).
  # - run_roborazzi=false on all ordinary PRs (including designsystem and
  #   build/deps PRs), scope=pr, and main premerge with unknown diff: passes
  #   -Praft.unitTest.skipScreenshots=true so neither :designsystem vcase
  #   goldens nor :shared Roborazzi goldens run on PR CI; unit and Compose UI
  #   logic tests still run.
  # - android_unit_scope=all + run_roborazzi=true on every post-merge /
  #   merge-queue / release / full / record run: main and release-* pushes,
  #   nightly schedule, full-ci PRs, scope=full, premerge into a release base,
  #   the merge-queue candidate, merge_group, and record_screenshots=true.
  # - none: the plan runs no Android job.
  local android_unit_scope=all
  local run_designsystem_tests=true
  local run_roborazzi=true
  reason=""

  case "$full_ci" in
    true|false) ;;
    *)
      echo "full-ci flag must be true or false" >&2
      return 1
      ;;
  esac

  case "$queue_tested" in
    true|false) ;;
    *)
      echo "queue-tested flag must be true or false" >&2
      return 1
      ;;
  esac
  if test "$queue_tested" = true && ! { test "$event_name" = push && test "$ref_name" = main; }; then
    echo "queue-tested reuse applies only to push to main (got ${event_name} ${ref_name})" >&2
    return 1
  fi

  case "$record_screenshots" in
    true|false) ;;
    *)
      echo "record-screenshots flag must be true or false" >&2
      return 1
      ;;
  esac
  if test "$record_screenshots" = true && test "$event_name" != workflow_dispatch; then
    echo "record-screenshots is a workflow_dispatch input (got ${event_name})" >&2
    return 1
  fi

  case "$event_name" in
    pull_request)
      classify_changed_paths "$changed_paths_file" "pull-request"
      case "$ref_name" in
        stamp-mq/*)
          # Stamp merge queue candidate (managed draft PR, 2026-11-19): up to
          # 10 approved PRs, each squashed onto main's tip; main fast-forwards
          # to this exact head only when every job of this run succeeded. It
          # is the pre-merge check for everything that lands on main, so it
          # runs every platform whatever the diff: Linux gates, Android
          # compile & tests, and the iOS compile on the release SDK (Xcode
          # 27.1 lane only, GitHub xcode-27). Compile + link + host app; the
          # iOS tests only when the batch touches iOS test sources. No
          # emulator suite (its jobs run as no-op successes, see above).
          mark_all_platforms
          queue_candidate=true
          run_ios_xcode270=false
          run_ios_xcode271=true
          if change_touches_ios_tests "$changed_paths_file"; then
            run_ios_tests=true
          fi
          reason="merge-queue-candidate"
          ;;
      esac
      if test "$queue_candidate" = true; then
        :
      elif test "$full_ci" = true; then
        mark_all_platforms
        # Task #26 closure: full-ci PR keeps the device suite but only the
        # API31 attachment leg.
        run_device_suite=true
        attachment_api_levels="[31]"
        run_ios_tests=true
        reason="pull-request-full-ci"
      else
        # Task #37 (artin 2026-10-09): ordinary PRs never compile iOS,
        # including PRs that touch iOS-specific paths. Stamp merge queue
        # (2026-11-19): the pre-merge iOS compile runs on the queue batch
        # candidate (draft PR from stamp-mq/*, Xcode 27.1) instead. The full-ci label remains
        # the explicit escape hatch. An unavailable diff fails closed to
        # Android (the PR-time platform); iOS is still compiled by the queue.
        run_ios=false
        case "$reason" in
          pull-request-diff-unavailable-fail-closed) run_android=true ;;
          pull-request-paths:*) reason="pull-request-paths:$(platform_summary)" ;;
        esac
        # artin 2026-11-22 & 2026-11-24 (#proj-mobile:#340): compile
        # everything, run plain JVM / UI logic unit tests; Roborazzi screenshot
        # goldens never run on ordinary PRs (they run in the merge queue, on
        # main, nightly, release, full-ci, scope=full and record_screenshots).
        android_unit_scope=jvm
        run_roborazzi=false
        if change_touches_build_or_deps "$changed_paths_file"; then
          android_unit_scope=all
        elif ! change_touches_designsystem "$changed_paths_file"; then
          run_designsystem_tests=false
        fi
      fi
      ;;
    merge_group)
      mark_all_platforms
      # Emulator/connected jobs stay out of the blocking queue.
      run_device_suite=false
      attachment_api_levels="[]"
      reason="merge-group-deterministic-all"
      ;;
    push)
      case "$ref_name" in
        main)
          if test "$queue_tested" = true; then
            # Stamp fast-forwarded main to a merge-queue candidate whose
            # Test run (every job green, first attempt, built on this push's
            # before SHA) is verified by check_queue_tested.py. Nothing to
            # re-run on the same SHA: only the queue-ci-reuse job reports.
            queue_reuse=true
            reason="main-push-queue-tested-reuse"
            run_device_suite=false
            attachment_api_levels="[]"
          else
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
            # A main push touching iOS test sources runs the iOS tests.
            if test "$run_ios" = true && change_touches_ios_tests "$changed_paths_file"; then
              run_ios_tests=true
            fi
            # Task #26 closure: routine main pushes no longer run the device
            # suite; nightly and explicit manual dispatch keep the readback.
            run_device_suite=false
            attachment_api_levels="[]"
          fi
          ;;
        release-*)
          # Release branches keep the platform readback, but routine release
          # pushes no longer run the device suite (nightly/manual keep it).
          mark_all_platforms
          run_device_suite=false
          attachment_api_levels="[]"
          run_ios_xcode271=true
          run_ios_tests=true
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
      # Task #26 closure: nightly keeps the full device suite and all
      # three attachment API legs.
      run_device_suite=true
      attachment_api_levels="[29,31,34]"
      run_ios_xcode271=true
      run_ios_tests=true
      reason="schedule-full-readback"
      ;;
    workflow_dispatch)
      # Task #37 (artin 2026-10-09): manual dispatch defaults to the
      # PR-equivalent tier instead of an unconditional full readback. A
      # dispatch has no trustworthy PR diff, so the pr tier is a fixed set:
      # Linux gates + Android compile, no iOS, no device suite. scope=full
      # keeps the deliberate full readback. scope=ios (2026-11-05) is the
      # pre-merge iOS check: plan + the Xcode 27.0 iOS compile only
      # (Android is already covered by the PR-tier run), compile + link + host
      # app build without the iOS tests (run_ios_tests=false). An empty/unknown scope
      # is rejected so the choice is always explicit in the workflow input.
      case "$dispatch_scope" in
        pr)
          run_android=true
          run_ios=false
          run_device_suite=false
          attachment_api_levels="[]"
          # The PR tier's Android unit tests (no Roborazzi goldens; without a
          # diff the design system's non-screenshot unit & UI tests run).
          android_unit_scope=jvm
          run_roborazzi=false
          reason="workflow_dispatch-pr-tier"
          ;;
        ios)
          run_android=false
          run_ios=true
          run_device_suite=false
          attachment_api_levels="[]"
          reason="workflow_dispatch-ios-tier"
          ;;
        premerge)
          # Stamp's ready-to-merge check (artin 2026-11-11): dispatched once on
          # the PR's exact head. Changed paths are head vs its merge-base with
          # the PR base (premerge-base-ref). Stamp merge queue (2026-11-19):
          # into main it no longer compiles iOS; the queue candidate (draft PR from
          # stamp-mq/*) does, on the combined batch commit. Android is not
          # rerun: the PR-tier run already compiled and tested it. An
          # unavailable diff fails closed to Android only. A PR into a release
          # branch (not queued) gets the scope=full readback instead.
          case "$premerge_base_ref" in
            "")
              echo "workflow_dispatch scope=premerge requires the base ref (input base_ref)" >&2
              return 1
              ;;
            release/*|release-*)
              mark_all_platforms
              run_device_suite=true
              attachment_api_levels="[29,31,34]"
              run_ios_tests=true
              reason="workflow_dispatch-premerge:release-full"
              ;;
            *)
              classify_changed_paths "$changed_paths_file" "workflow_dispatch-premerge"
              echo "premerge base=${premerge_base_ref} classification=${reason} platforms=$(platform_summary)" >&2
              run_device_suite=false
              attachment_api_levels="[]"
              run_ios=false
              case "$reason" in
                workflow_dispatch-premerge-diff-unavailable-fail-closed)
                  run_android=true
                  # The PR tier's Android unit tests (no Roborazzi goldens; the
                  # design system's non-screenshot unit & UI tests run: no diff).
                  android_unit_scope=jvm
                  run_roborazzi=false
                  reason="workflow_dispatch-premerge:diff-unavailable-fail-closed"
                  ;;
                *)
                  run_android=false
                  reason="workflow_dispatch-premerge:no-ios"
                  ;;
              esac
              ;;
          esac
          ;;
        full)
          mark_all_platforms
          run_device_suite=true
          attachment_api_levels="[29,31,34]"
          run_ios_tests=true
          reason="workflow_dispatch-full-readback"
          ;;
        *)
          echo "workflow_dispatch scope must be pr, ios, premerge or full, got: '${dispatch_scope}'" >&2
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
  if test "$attachment_api_levels" != "[]" && test "$run_android" != true; then
    echo "invalid CI plan: attachment legs require Android" >&2
    return 1
  fi
  # xcode_app_path (workflow_dispatch) only reaches the iOS compile job. A plan that skips iOS
  # (scope=pr, docs-only PRs, ...) would go green without ever compiling with that Xcode, so refuse it.
  if test -n "$xcode_app_path" && test "$run_ios" != true; then
    echo "::error::xcode_app_path=${xcode_app_path} is set but this plan skips the iOS compile (run_ios=${run_ios}, reason=${reason}). Re-dispatch with scope=ios or scope=full, or clear xcode_app_path." >&2
    return 1
  fi

  # iOS compile lanes (matrix of the test.yml `ios` job), both on GitHub's
  # standard xcode-27 image (free on this public repo; GitHub's standard Macs
  # share a 5-job concurrency cap with ios-simulator-app.yml /
  # qa-acceptance-ios.yml). Xcode 27.0 lane: every iOS run but the merge queue.
  # Xcode 27.1 lane: nightly and release-* pushes (with 27.0), and the merge
  # queue batch (alone).
  # Defensive clamp: no branch above sets run_ios_xcode271/run_ios_tests
  # without iOS today, but both only make sense inside the iOS job, so a future
  # branch that drops iOS cannot leave them true (verify_test_tier_results.mjs
  # rejects either flag without run_ios).
  if test "$run_ios" != true; then
    run_ios_xcode270=false
    run_ios_xcode271=false
    run_ios_tests=false
  fi
  if test "$run_ios_xcode270" = true && test "$run_ios_xcode271" = true; then
    ios_lanes='["github-xcode27.0","github-xcode27.1"]'
  elif test "$run_ios_xcode270" = true; then
    ios_lanes='["github-xcode27.0"]'
  elif test "$run_ios_xcode271" = true; then
    ios_lanes='["github-xcode27.1"]'
  fi

  # Recording goldens needs every screenshot test, whatever the scope.
  if test "$record_screenshots" = true; then
    android_unit_scope=all
    run_designsystem_tests=true
    run_roborazzi=true
  fi
  if test "$run_android" != true; then
    android_unit_scope=none
    run_designsystem_tests=false
    run_roborazzi=false
  fi
  if test "$run_roborazzi" = true && test "$android_unit_scope" != all; then
    echo "invalid CI plan: Roborazzi requires android_unit_scope=all" >&2
    return 1
  fi

  printf 'run_android=%s\n' "$run_android"
  printf 'run_ios=%s\n' "$run_ios"
  printf 'run_ios_xcode270=%s\n' "$run_ios_xcode270"
  printf 'run_ios_xcode271=%s\n' "$run_ios_xcode271"
  printf 'ios_lanes=%s\n' "$ios_lanes"
  printf 'run_ios_tests=%s\n' "$run_ios_tests"
  printf 'run_device_suite=%s\n' "$run_device_suite"
  printf 'attachment_api_levels=%s\n' "$attachment_api_levels"
  printf 'queue_candidate=%s\n' "$queue_candidate"
  printf 'queue_reuse=%s\n' "$queue_reuse"
  printf 'android_unit_scope=%s\n' "$android_unit_scope"
  printf 'run_designsystem_tests=%s\n' "$run_designsystem_tests"
  printf 'run_roborazzi=%s\n' "$run_roborazzi"
  printf 'reason=%s\n' "$reason"
}

test "$#" -ge 4 && test "$#" -le 9 || usage
emit_plan "$1" "$2" "$3" "$4" "${5:-}" "${6:-}" "${7:-}" "${8:-false}" "${9:-false}"
