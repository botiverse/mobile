#!/usr/bin/env bash
# Fail when compose/shared commonMain gains a raw `.clickable(` / `.combinedClickable(`.
# A hand-rolled tap takes the design system's press, hover and min-press-time states through
# `Modifier.raftClickable` (compose/designsystem .../components/RaftClickable.kt, #4379). The
# sites that need a raw clickable (scrims, tap sinks, drags, message rows, hidden shortcuts,
# rows that draw their own press state) say why in a comment and are counted per file in
# scripts/check_commonmain_raw_clickable.baseline. Only a count above its baseline fails;
# a lower count passes with a notice to lower the baseline.
set -euo pipefail

source_root="compose/shared/src/commonMain/kotlin"
baseline_file="scripts/check_commonmain_raw_clickable.baseline"

usage() {
  cat <<'EOF'
Usage: scripts/check_commonmain_raw_clickable.sh [--source-root <path> --baseline <file>] [--print-baseline] [--self-test]
EOF
}

fail() {
  echo "::error::$1" >&2
  exit 1
}

# Lines that call clickable/combinedClickable: `.clickable(`, `.clickable {` and a bare
# `clickable(` inside a Modifier extension. Comment lines and declarations do not count.
raw_clickable_lines() {
  local root="$1"
  grep -rnE '(^|[^[:alnum:]_])(clickable|combinedClickable)[[:space:]]*[({]' "$root" --include='*.kt' |
    grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' |
    grep -vE '^[^:]+:[0-9]+:.*(^|[^[:alnum:]_])fun[[:space:]]' || true
}

# "<count> <path>" per file, path relative to the source root, sorted by path.
raw_clickable_counts() {
  local root="$1"
  raw_clickable_lines "$root" | cut -d: -f1 | sed "s#^${root}/##" | LC_ALL=C sort | uniq -c | awk '{print $1, $2}'
}

check_source_root() {
  local root="$1"
  local baseline="$2"
  local failed=0
  local kind count allowed path

  [ -d "$root" ] || fail "commonMain source root not found: $root"
  [ -f "$baseline" ] || fail "raw clickable baseline not found: $baseline"

  # "over|under|gone <count> <allowed> <path>" for each file whose count differs from its baseline.
  while read -r kind count allowed path; do
    case "$kind" in
      over)
        echo "::error file=${root}/${path}::${path} has ${count} raw .clickable(/.combinedClickable( call(s), baseline ${allowed}." >&2
        raw_clickable_lines "$root" | grep -F "${root}/${path}:" | sed 's/^/::error::  /' >&2 || true
        failed=1
        ;;
      under)
        echo "::notice file=${root}/${path}::${path} is down to ${count} raw clickable call(s) (baseline ${allowed}); lower it in ${baseline}."
        ;;
      gone)
        echo "::notice::${path} has no raw clickable call left (baseline ${allowed}); remove it from ${baseline}."
        ;;
    esac
  done < <(
    raw_clickable_counts "$root" | awk '
      NR == FNR { if ($0 !~ /^#/ && NF == 2) allowed[$2] = $1; next }
      {
        seen[$2] = 1
        limit = ($2 in allowed) ? allowed[$2] : 0
        if ($1 + 0 > limit + 0) print "over", $1, limit, $2
        else if ($1 + 0 < limit + 0) print "under", $1, limit, $2
      }
      END { for (p in allowed) if (!(p in seen)) print "gone", 0, allowed[p], p }
    ' "$baseline" -
  )

  if [ "$failed" -ne 0 ]; then
    echo "::error::Use Modifier.raftClickable(onClick, role, onClickLabel, shape, tier, ...) from build.raft.designsystem.components" >&2
    echo "::error::instead of a raw clickable: it gives the design system's press, hover and screen-reader role." >&2
    echo "::error::If the site truly needs a raw clickable (scrim, tap sink, drag, a row that draws its own press state," >&2
    echo "::error::a hidden shortcut with no press), add a comment saying why and raise its count in ${baseline}." >&2
  fi
  return "$failed"
}

self_test() {
  local fixture_root fixture_dir baseline
  fixture_root="$(mktemp -d)"
  trap "rm -rf '$fixture_root'" EXIT
  fixture_dir="$fixture_root/kotlin/build/raft/app"
  mkdir -p "$fixture_dir"
  baseline="$fixture_root/baseline"

  cat > "$fixture_dir/Allowed.kt" <<'EOF'
package build.raft.app

// A scrim: owns every tap under it. Swapping to .clickable() would not change this comment's count.
fun scrim() = Modifier.clickable(interactionSource = source, indication = null) {}
fun row() = Modifier.raftClickable(onClick = {})
EOF
  printf '# count path\n1 build/raft/app/Allowed.kt\n' > "$baseline"
  check_source_root "$fixture_root/kotlin" "$baseline" >/dev/null

  cat > "$fixture_dir/New.kt" <<'EOF'
package build.raft.app

fun tap() = Modifier.clickable { open() }
EOF
  if check_source_root "$fixture_root/kotlin" "$baseline" >/dev/null 2>&1; then
    fail "self-test expected a new file with a raw clickable to fail"
  fi
  rm "$fixture_dir/New.kt"

  cat >> "$fixture_dir/Allowed.kt" <<'EOF'
fun more() = Modifier.combinedClickable(onClick = {}, onLongClick = {})
EOF
  if check_source_root "$fixture_root/kotlin" "$baseline" >/dev/null 2>&1; then
    fail "self-test expected a count above the baseline to fail"
  fi

  printf '2 build/raft/app/Allowed.kt\n' > "$baseline"
  check_source_root "$fixture_root/kotlin" "$baseline" >/dev/null

  printf '5 build/raft/app/Allowed.kt\n' > "$baseline"
  check_source_root "$fixture_root/kotlin" "$baseline" >/dev/null
  echo "check_commonmain_raw_clickable self-test passed"
}

case "${1:-}" in
  --source-root)
    [ "$#" -eq 4 ] && [ "$3" = "--baseline" ] || { usage; exit 2; }
    check_source_root "$2" "$4"
    ;;
  --print-baseline)
    [ "$#" -eq 1 ] || { usage; exit 2; }
    echo "# Raw .clickable(/.combinedClickable( calls per file in ${source_root} (count path)."
    echo "# Regenerate: scripts/check_commonmain_raw_clickable.sh --print-baseline > ${baseline_file}"
    raw_clickable_counts "$source_root"
    ;;
  --self-test)
    [ "$#" -eq 1 ] || { usage; exit 2; }
    self_test
    ;;
  -h|--help)
    usage
    ;;
  "")
    check_source_root "$source_root" "$baseline_file"
    ;;
  *)
    usage
    exit 2
    ;;
esac
