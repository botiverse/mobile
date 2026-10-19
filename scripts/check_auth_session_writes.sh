#!/usr/bin/env bash
# #466 no-bypass gate: outside the auth-session store itself, session mutation MUST go through the
# atomic verbs (replaceAuthenticatedSession / rotateTokensIfMatch / updateMetadata / clearSession),
# never the low-level whole-record writers saveSession(...) / updateTokens(...). Those capture-then-
# whole-save primitives are exactly what let a stale snapshot clobber a coordinator-rotated token
# (the 1.3.0 auto-logout regression). New callers must not reintroduce that pattern.
set -euo pipefail

# :core holds the UI-free data foundation split out of :shared; both commonMain trees are checked.
source_roots=(
  "compose/shared/src/commonMain/kotlin"
  "compose/core/src/commonMain/kotlin"
)
# The store implementation legitimately uses the low-level primitives internally.
store_file="build/raft/app/data/AuthSessionStore.kt"
# Bypass writers forbidden outside the store. updateSelectedServer is intentionally allowed — it is
# now an atomic metadata verb.
forbidden_pattern='\.(saveSession|updateTokens)[[:space:]]*\('
# Escape hatch for a reviewed, genuinely-atomic exception (same convention as common-map-compat).
allow_marker='auth-session-write: allow-bypass'

usage() {
  cat <<'EOF'
Usage: scripts/check_auth_session_writes.sh [--source-root <path>] [--self-test]

Fails if a :shared or :core commonMain file other than the auth-session store calls .saveSession(...) or
.updateTokens(...). Route session writes through replaceAuthenticatedSession / rotateTokensIfMatch /
updateMetadata / clearSession instead. For a reviewed exception, put "auth-session-write: allow-bypass
<reason>" on the call or the preceding line.
EOF
}

fail() {
  echo "::error::$1" >&2
  exit 1
}

check_source_root() {
  local root="$1"
  local failed=0
  local file relative line_number line previous_line

  [ -d "$root" ] || fail "commonMain source root not found: $root"

  while IFS= read -r -d '' file; do
    relative="${file#${root}/}"
    [ "$relative" = "$store_file" ] && continue

    while IFS=: read -r line_number line; do
      previous_line=""
      if [ "$line_number" -gt 1 ]; then
        previous_line="$(sed -n "$((line_number - 1))p" "$file")"
      fi
      if [[ "$line" == *"$allow_marker"* ]] || [[ "$previous_line" == *"$allow_marker"* ]]; then
        continue
      fi
      echo "::error file=${file},line=${line_number}::auth-session write bypass — route through the atomic verbs (replaceAuthenticatedSession / rotateTokensIfMatch / updateMetadata / clearSession) instead of saveSession(...)/updateTokens(...). For a reviewed exception, add '${allow_marker} <reason>' on the call or preceding line." >&2
      failed=1
    done < <(rg -n "$forbidden_pattern" "$file" || true)
  done < <(
    rg -l -0 "$forbidden_pattern" "$root" -g '*.kt' || true
  )

  return "$failed"
}

self_test() {
  local fixture_root fixture_file
  fixture_root="$(mktemp -d)"
  trap "rm -rf '$fixture_root'" EXIT
  mkdir -p "$fixture_root/build/raft/app/data"

  # The store file itself is exempt.
  fixture_file="$fixture_root/build/raft/app/data/AuthSessionStore.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app.data

fun impl(store: Any, session: Any) {
    store.saveSession(session)
}
EOF
  check_source_root "$fixture_root"
  rm "$fixture_file"

  # A verb-based writer is fine.
  fixture_file="$fixture_root/build/raft/app/data/GoodWriter.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app.data

suspend fun good(store: Any) {
    store.updateMetadata(expectedAccountId = null) { it }
    store.updateSelectedServer(serverId = null, serverSlug = null)
}
EOF
  check_source_root "$fixture_root"
  rm "$fixture_file"

  # A bypass writer must fail.
  fixture_file="$fixture_root/build/raft/app/data/BadWriter.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app.data

suspend fun bad(store: Any, session: Any) {
    store.saveSession(session)
}
EOF
  if check_source_root "$fixture_root" >/dev/null 2>&1; then
    fail "self-test expected an external saveSession bypass to fail"
  fi
  rm "$fixture_file"

  # A reviewed exception with the marker is allowed.
  fixture_file="$fixture_root/build/raft/app/data/AllowedBypass.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app.data

suspend fun migrationOnly(store: Any, session: Any) {
    // auth-session-write: allow-bypass one-shot legacy migration, no concurrent writer.
    store.saveSession(session)
}
EOF
  check_source_root "$fixture_root"
  rm "$fixture_file"

  echo "check_auth_session_writes self-test passed"
}

case "${1:-}" in
  --source-root)
    [ "$#" -eq 2 ] || { usage; exit 2; }
    check_source_root "$2"
    ;;
  --self-test)
    [ "$#" -eq 1 ] || { usage; exit 2; }
    self_test
    ;;
  -h|--help)
    usage
    ;;
  "")
    status=0
    for root in "${source_roots[@]}"; do
      check_source_root "$root" || status=1
    done
    exit "$status"
    ;;
  *)
    usage
    exit 2
    ;;
esac
