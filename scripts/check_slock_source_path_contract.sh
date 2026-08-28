#!/usr/bin/env bash
set -euo pipefail

# The Mobile checkout no longer vendors botiverse/slock. Keep the historical
# exceptions explicit: a new file is governed by this check by default, even
# when its name happens to contain a date.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

retired_path="$(printf 'third_party/%s' 'slock-source')"
historical_files=$(cat <<'EOF'
docs/agent-detail-permissions-tab-spec-2026-06-25.md
docs/member-agent-app-logins-parity-spec-2026-06-24.md
docs/mobile-file-preview-mp4-text-spec-2026-06-26-update.md
docs/mobile-killed-app-push-android-server-contract-2026-07-01.md
docs/mobile-killed-app-push-ios-server-contract-2026-07-01.md
docs/mobile-killed-app-push-ohos-server-contract-2026-07-01.md
docs/mobile-native-push-notification-analysis-2026-06-25.md
docs/mobile-native-push-server-requirements-2026-07-02.md
docs/mobile-webview-server-contract-2026-07-02.md
docs/raft-ui-kmp-component-library-audit-2026-07-03.md
docs/raft-ui-kmp-product-gap-plan-2026-07-03.md
docs/react-kmp-feature-map-2026-06-20.md
docs/react-kmp-feature-map-2026-06-26.md
docs/react-kmp-realtime-alignment-2026-06-26.md
docs/react-kmp-visual-testing-alignment-2026-06-26.md
docs/realtime-event-contract-2026-06-23.md
docs/realtime-read-state-sync-spec-2026-06-23.md
docs/thread-reaction-sprite-atlas-spec-2026-06-25.md
docs/visual-testing-build-gating-spec-2026-06-26.md
EOF
)

is_historical_file() {
  grep -Fqx -- "$1" <<<"$historical_files"
}

is_allowed_policy_line() {
  local file="$1"
  local text="$2"

  case "$file:$text" in
    "AGENTS.md:  vendor it as \`$retired_path\` and \`.gitmodules\` has no entry for"|\
      "AGENTS.md:  \`$retired_path\` path in one of those files records the checkout"|\
      "docs/new-agent-landing.md:contract snapshot. Do not create a \`$retired_path\` directory in the")
      return 0
      ;;
  esac
  return 1
}

check_root() {
  local root="$1"
  local status=0
  local duplicates file line text matches

  if ! git -C "$root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    printf 'ERROR: source-path contract root is not a git worktree: %s\n' "$root" >&2
    return 1
  fi

  duplicates="$(sort <<<"$historical_files" | uniq -d)"
  if [ -n "$duplicates" ]; then
    printf 'ERROR: duplicate historical source-path exceptions:\n%s\n' "$duplicates" >&2
    status=1
  fi

  # An allowlist entry must remain a tracked, still-historical snapshot
  # containing the retired path. This prevents a stale exception from silently
  # growing or masking an untracked local file.
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    if [ ! -f "$root/$file" ]; then
      printf 'ERROR: historical source-path exception is missing: %s\n' "$file" >&2
      status=1
    elif ! git -C "$root" ls-files --error-unmatch -- "$file" >/dev/null 2>&1; then
      printf 'ERROR: historical source-path exception is not tracked: %s\n' "$file" >&2
      status=1
    elif ! grep -Fq -- "$retired_path" "$root/$file"; then
      printf 'ERROR: historical source-path exception no longer matches: %s\n' "$file" >&2
      status=1
    fi
  done <<<"$historical_files"

  # Scan every tracked or untracked, non-ignored Mobile file. git ls-files
  # deliberately avoids descending into gitlinks, while still making a new
  # file governed before it is added. The checker source is scanned too; its
  # pattern is assembled above so it needs no self-exemption.
  while IFS= read -r -d '' file; do
    [ -f "$root/$file" ] || continue
    grep -Fq -- "$retired_path" "$root/$file" || continue
    is_historical_file "$file" && continue

    matches="$(rg --text -n --no-messages --fixed-strings "$retired_path" "$root/$file" || true)"
    while IFS=: read -r line text; do
      [ -n "$line" ] || continue
      if ! is_allowed_policy_line "$file" "$text"; then
        printf 'ERROR: retired source path in active file %s:%s\n' "$file" "$line" >&2
        printf '       %s\n' "$text" >&2
        status=1
      fi
    done <<<"$matches"
  done < <(git -C "$root" ls-files -z --cached --others --exclude-standard)

  if [ "$status" -ne 0 ]; then
    printf 'Slock source-path contract: FAIL\n' >&2
    return "$status"
  fi

  printf 'Slock source-path contract: PASS (active paths clean; %s historical exceptions)\n' \
    "$(grep -c . <<<"$historical_files")"
}

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

self_test() (
  local fixture_root original_historical
  fixture_root="$(mktemp -d)"
  trap 'rm -rf "$fixture_root"' EXIT
  original_historical="$historical_files"

  git -C "$fixture_root" init -q
  mkdir -p "$fixture_root/docs" "$fixture_root/scripts"
  historical_files='docs/historical-a.md
docs/historical-b.md'
  printf 'old checkout: %s/packages/server\n' "$retired_path" > "$fixture_root/docs/historical-a.md"
  printf 'old checkout: %s/packages/shared\n' "$retired_path" > "$fixture_root/docs/historical-b.md"
  git -C "$fixture_root" add docs/historical-a.md docs/historical-b.md

  printf '%s\n' "  vendor it as \`${retired_path}\` and \`.gitmodules\` has no entry for" > "$fixture_root/AGENTS.md"
  printf '%s\n' "  \`${retired_path}\` path in one of those files records the checkout" >> "$fixture_root/AGENTS.md"
  printf '%s\n' "contract snapshot. Do not create a \`${retired_path}\` directory in the" > "$fixture_root/docs/new-agent-landing.md"
  git -C "$fixture_root" add AGENTS.md docs/new-agent-landing.md

  # The explicit historical set alone is valid.
  check_root "$fixture_root" >/dev/null || fail "self-test rejected explicit historical snapshots"

  # A new active file is governed before git add.
  printf 'run from %s/packages/server\n' "$retired_path" > "$fixture_root/scripts/active-probe.sh"
  if check_root "$fixture_root" >/dev/null 2>&1; then
    fail "self-test accepted a retired path in a new active file"
  fi
  rm "$fixture_root/scripts/active-probe.sh"

  # A date-shaped filename is not an implicit exemption.
  printf 'run from %s/packages/server\n' "$retired_path" > "$fixture_root/docs/active-2099-01-01.md"
  if check_root "$fixture_root" >/dev/null 2>&1; then
    fail "self-test accepted a retired path in a new dated active file"
  fi
  rm "$fixture_root/docs/active-2099-01-01.md"

  # Only the exact prohibition lines are permitted in the two current policy
  # files; the whole files are not exempt.
  printf 'new command: %s/packages/server\n' "$retired_path" > "$fixture_root/AGENTS.md"
  if check_root "$fixture_root" >/dev/null 2>&1; then
    fail "self-test accepted an unreviewed policy-file occurrence"
  fi
  rm "$fixture_root/AGENTS.md"

  # An allowlisted file must keep both its tracked identity and the evidence it
  # was allowlisted for.
  printf 'historical path removed\n' > "$fixture_root/docs/historical-b.md"
  if check_root "$fixture_root" >/dev/null 2>&1; then
    fail "self-test accepted a stale historical exception"
  fi

  historical_files="$original_historical"
  if grep -Fq -- "$retired_path" "${BASH_SOURCE[0]}"; then
    fail "checker source contains the retired path literally and would require self-exemption"
  fi
  printf '%s\n' \
    'Slock source-path contract self-test: PASS' \
    '  cases: historical-only, active-new-file, dated-active-new-file, policy-extra, stale-allowlist, self-scan'
)

case "${1:-}" in
  --self-test)
    [ "$#" -eq 1 ] || fail "usage: $0 [--self-test]"
    self_test
    ;;
  "")
    check_root "$ROOT"
    ;;
  *)
    fail "usage: $0 [--self-test]"
    ;;
esac
