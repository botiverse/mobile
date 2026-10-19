#!/usr/bin/env bash
# Fail fast when commonMain calls Map default methods without the Native-safe
# same-name extension import. Android/JVM resolves the Java member without an
# import, while iOS (Kotlin/Native) only sees the extension and otherwise fails to compile.
set -euo pipefail

source_root="compose/shared/src/commonMain/kotlin"
core_source_root="compose/core/src/commonMain/kotlin"
ui_cmp_source_root="compose/ui-cmp/src/commonMain/kotlin"
compat_file="build/raft/app/base/CommonMapCompat.kt"

usage() {
  cat <<'EOF'
Usage: scripts/check_commonmain_map_compat.sh [--source-root <path>] [--self-test]
EOF
}

fail() {
  echo "::error::$1" >&2
  exit 1
}

check_method_import() {
  local file="$1"
  local method="$2"
  local import_line="import build.raft.app.base.${method}"

  if ! rg -q "^${import_line}$" "$file"; then
    echo "::error file=${file}::commonMain calls .${method}(...) without ${import_line}; import the K/N-safe helper." >&2
    return 1
  fi
}

has_file_local_merge_extension() {
  local file="$1"
  rg -q '^[[:space:]]*(private|internal|public)?[[:space:]]*fun[[:space:]]+[^[:space:]]+\.merge[[:space:]]*\(' "$file"
}

merge_calls_are_explicitly_non_map() {
  local file="$1"
  local line_number line previous_line

  while IFS=: read -r line_number line; do
    previous_line=""
    if [ "$line_number" -gt 1 ]; then
      previous_line="$(sed -n "$((line_number - 1))p" "$file")"
    fi
    if [[ "$line" != *"common-map-compat: allow-nonmap-merge"* ]] &&
      [[ "$previous_line" != *"common-map-compat: allow-nonmap-merge"* ]]; then
      return 1
    fi
  done < <(rg -n '\.merge[[:space:]]*\(' "$file")
  return 0
}

check_merge_import() {
  local file="$1"

  if rg -q '^import build\.raft\.app\.base\.merge$' "$file" ||
    has_file_local_merge_extension "$file" ||
    merge_calls_are_explicitly_non_map "$file"; then
    return 0
  fi

  echo "::error file=${file}::commonMain calls .merge(...) without import build.raft.app.base.merge. For an intentional non-Map merge, add // common-map-compat: allow-nonmap-merge on the call or preceding line." >&2
  return 1
}

check_source_root() {
  local root="$1"
  local failed=0
  local file relative

  [ -d "$root" ] || fail "commonMain source root not found: $root"

  while IFS= read -r -d '' file; do
    relative="${file#${root}/}"
    [ "$relative" = "$compat_file" ] && continue

    if rg -q '\.putIfAbsent[[:space:]]*\(' "$file"; then
      check_method_import "$file" "putIfAbsent" || failed=1
    fi

    if rg -q '\.computeIfAbsent[[:space:]]*\(' "$file"; then
      check_method_import "$file" "computeIfAbsent" || failed=1
    fi

    if rg -q '\.merge[[:space:]]*\(' "$file"; then
      check_merge_import "$file" || failed=1
    fi

    if rg -q '\.(compute|replaceAll)[[:space:]]*\(' "$file"; then
      echo "::error file=${file}::commonMain must not call JVM-only Map default methods compute/replaceAll; add a reviewed K/N-safe helper first." >&2
      failed=1
    fi
  done < <(
    rg -l -0 '\.(putIfAbsent|computeIfAbsent|merge|compute|replaceAll)[[:space:]]*\(' "$root" -g '*.kt' || true
  )

  return "$failed"
}

self_test() {
  local fixture_root fixture_file
  fixture_root="$(mktemp -d)"
  trap "rm -rf '$fixture_root'" EXIT
  mkdir -p "$fixture_root/build/raft/app/base" "$fixture_root/build/raft/app"

  fixture_file="$fixture_root/build/raft/app/Imported.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app

import build.raft.app.base.putIfAbsent

fun valid(values: MutableMap<String, String>) {
    values.putIfAbsent("key", "value")
}
EOF
  check_source_root "$fixture_root"

  fixture_file="$fixture_root/build/raft/app/MissingImport.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app

fun invalid(values: MutableMap<String, String>) {
    values.putIfAbsent("key", "value")
}
EOF
  if check_source_root "$fixture_root" >/dev/null 2>&1; then
    fail "self-test expected a missing commonMapCompat import to fail"
  fi
  rm "$fixture_file"

  fixture_file="$fixture_root/build/raft/app/Unsupported.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app

fun invalid(values: MutableMap<String, String>) {
    values.compute("key") { _, value -> value ?: "value" }
}
EOF
  if check_source_root "$fixture_root" >/dev/null 2>&1; then
    fail "self-test expected a JVM-only compute call to fail"
  fi
  rm "$fixture_file"

  fixture_file="$fixture_root/build/raft/app/NonMapMerge.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app

// common-map-compat: allow-nonmap-merge Flow.merge is not Map.merge.
fun validNonMapMerge(flow: Any): Any = flow.merge(flow)
EOF
  check_source_root "$fixture_root"
  rm "$fixture_file"

  fixture_file="$fixture_root/build/raft/app/LocalMerge.kt"
  cat > "$fixture_file" <<'EOF'
package build.raft.app

private fun String.merge(other: String): String = this + other

fun validLocalMerge(value: String): String = value.merge("suffix")
EOF
  check_source_root "$fixture_root"
}

case "${1:-}" in
  --source-root)
    [ "$#" -eq 2 ] || usage
    check_source_root "$2"
    ;;
  --self-test)
    [ "$#" -eq 1 ] || usage
    self_test
    ;;
  -h|--help)
    usage
    ;;
  "")
    status=0
    check_source_root "$source_root" || status=1
    check_source_root "$core_source_root" || status=1
    check_source_root "$ui_cmp_source_root" || status=1
    exit "$status"
    ;;
  *)
    usage
    exit 2
    ;;
esac
