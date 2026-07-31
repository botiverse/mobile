#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "usage: plan_test_tier.sh <event-name> <changed-paths-file> <full-ci:true|false>" >&2
  echo "       plan_test_tier.sh --self-test" >&2
  exit 2
}

is_relevant_path() {
  case "$1" in
    .github/workflows/test.yml|compose/*|scripts/check_commonmain_map_compat.sh|scripts/check_markdown_spike_println_gate.sh|scripts/check_auth_session_writes.sh|scripts/check_attachment_mapper_formatting.sh|scripts/check_canonical_message_v2_reaction_vector.sh|scripts/check_read_state_behavior_vector.sh|scripts/check_read_state_sync_domain_registration.sh|scripts/audit_translucent_action_tokens.py|scripts/build_kuikly_fork.sh|scripts/prepare_*.sh|scripts/ci/*|third_party/kuikly-ui|third_party/ktor-ohos/*|third_party/stately|third_party/sqliter|third_party/sqldelight|third_party/kuikly-compose-markdown/*|third_party/markdown-parser/*|.github/rulesets/mobile-main-merge-queue.json)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

forces_deterministic_full() {
  case "$1" in
    .github/workflows/test.yml|scripts/ci/plan_test_tier.sh|scripts/ci/verify_test_tier_results.mjs|scripts/ci/test_ci_tiering_contract.sh|.github/rulesets/mobile-main-merge-queue.json)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

emit_plan() {
  local event_name="$1"
  local changed_paths_file="$2"
  local full_ci="$3"
  local run_basic=false
  local run_full=false
  local run_emulators=false
  local reason=""

  case "$full_ci" in
    true|false) ;;
    *)
      echo "full-ci flag must be true or false" >&2
      return 1
      ;;
  esac

  case "$event_name" in
    pull_request)
      test -f "$changed_paths_file"
      reason="pull-request-no-test-paths"
      while IFS= read -r path || test -n "$path"; do
        path="${path%$'\r'}"
        test -n "$path" || continue
        if is_relevant_path "$path"; then
          run_basic=true
          if test "$run_full" != true; then
            reason="pull-request-basic"
          fi
        fi
        if forces_deterministic_full "$path"; then
          run_basic=true
          run_full=true
          reason="pull-request-ci-control"
        fi
      done < "$changed_paths_file"
      if test "$full_ci" = true; then
        run_basic=true
        run_full=true
        run_emulators=true
        reason="pull-request-full-ci"
      fi
      ;;
    merge_group)
      run_basic=true
      run_full=true
      # Emulator/connected jobs stay out of the blocking queue until their
      # infra-abort retry contract has landed and has fresh flake evidence.
      run_emulators=false
      reason="merge-group-deterministic-full"
      ;;
    push|schedule|workflow_dispatch)
      run_basic=true
      run_full=true
      run_emulators=true
      reason="${event_name}-full-readback"
      ;;
    *)
      echo "unsupported Test workflow event: $event_name" >&2
      return 1
      ;;
  esac

  if test "$run_full" = true && test "$run_basic" != true; then
    echo "invalid CI plan: full tier requires the basic tier" >&2
    return 1
  fi
  if test "$run_emulators" = true && test "$run_full" != true; then
    echo "invalid CI plan: emulator tier requires the full tier" >&2
    return 1
  fi

  printf 'run_basic=%s\n' "$run_basic"
  printf 'run_full=%s\n' "$run_full"
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
    local paths="$4"
    printf '%s' "$paths" > "$work/paths"
    local actual
    actual="$(emit_plan "$event_name" "$work/paths" "$full_ci")"
    if test "$actual" != "$expected"; then
      printf 'expected plan:\n%s\nactual plan:\n%s\n' "$expected" "$actual" >&2
      return 1
    fi
  }

  assert_plan $'run_basic=false\nrun_full=false\nrun_emulators=false\nreason=pull-request-no-test-paths' \
    pull_request false $'docs/readme.md\n'
  assert_plan $'run_basic=true\nrun_full=false\nrun_emulators=false\nreason=pull-request-basic' \
    pull_request false $'compose/shared/src/commonMain/kotlin/Example.kt\n'
  assert_plan $'run_basic=true\nrun_full=true\nrun_emulators=false\nreason=pull-request-ci-control' \
    pull_request false $'.github/workflows/test.yml\n'
  assert_plan $'run_basic=true\nrun_full=true\nrun_emulators=false\nreason=pull-request-ci-control' \
    pull_request false $'.github/workflows/test.yml\ncompose/shared/src/commonMain/kotlin/Example.kt\n'
  assert_plan $'run_basic=true\nrun_full=true\nrun_emulators=true\nreason=pull-request-full-ci' \
    pull_request true $'docs/readme.md\n'
  assert_plan $'run_basic=true\nrun_full=true\nrun_emulators=false\nreason=merge-group-deterministic-full' \
    merge_group false ''
  assert_plan $'run_basic=true\nrun_full=true\nrun_emulators=true\nreason=push-full-readback' \
    push false ''
  if emit_plan pull_request "$work/paths" maybe >/dev/null 2>&1; then
    echo "invalid full-ci flag unexpectedly passed" >&2
    return 1
  fi
  if emit_plan issue_comment "$work/paths" false >/dev/null 2>&1; then
    echo "unsupported event unexpectedly passed" >&2
    return 1
  fi
  echo "CI tier planner self-test: PASS"
}

if test "${1:-}" = --self-test; then
  test "$#" -eq 1 || usage
  self_test
  exit 0
fi

test "$#" -eq 3 || usage
emit_plan "$1" "$2" "$3"
