#!/usr/bin/env bash
# Keeps the search snippet port and its vendored vectors on one pinned slock commit.
#
# Provenance: compose/shared/.../RaftSearchSnippet.kt ports `buildSearchSnippet`
# (packages/shared/src/searchSnippet.ts) and RaftSearchSnippetVectorTest.kt copies its
# slock test cases, both at github.com/botiverse/slock @ the SHA in
# RAFT_SEARCH_SNIPPET_SLOCK_SHA. The check is fully local: the SHA in the port, the vector
# test header and the test's own assertion must be the same 40-hex commit. To update, re-port
# the function, re-copy the cases and bump all three together.
set -euo pipefail

readonly PORT="compose/shared/src/commonMain/kotlin/build/raft/app/RaftSearchSnippet.kt"
readonly TEST="compose/shared/src/commonTest/kotlin/build/raft/app/RaftSearchSnippetVectorTest.kt"

for file in "$PORT" "$TEST"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing search snippet file: $file" >&2
    exit 1
  fi
done

port_sha="$(sed -n 's/^internal const val RAFT_SEARCH_SNIPPET_SLOCK_SHA = "\([0-9a-f]\{40\}\)"$/\1/p' "$PORT")"
header_sha="$(sed -n 's|^//   github.com/botiverse/slock PR #[0-9]* (merged to staging) @ \([0-9a-f]\{40\}\)$|\1|p' "$TEST")"
assert_sha="$(sed -n 's/.*assertEquals("\([0-9a-f]\{40\}\)", RAFT_SEARCH_SNIPPET_SLOCK_SHA).*/\1/p' "$TEST")"

if [[ -z "$port_sha" || -z "$header_sha" || -z "$assert_sha" ]]; then
  echo "Could not read the pinned slock SHA (port='$port_sha' header='$header_sha' assert='$assert_sha')" >&2
  exit 1
fi

if [[ "$port_sha" != "$header_sha" || "$port_sha" != "$assert_sha" ]]; then
  echo "Search snippet pins drifted: port=$port_sha header=$header_sha assert=$assert_sha" >&2
  exit 1
fi

echo "Search snippet vector pin verified: $port_sha"
