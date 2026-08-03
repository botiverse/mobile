#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "usage: git-diff-path-state.sh <base> <head> -- <path> [<path> ...]" >&2
  exit 64
}

test "$#" -ge 4 || usage
base="$1"
head="$2"
test "$3" = "--" || usage
shift 3

# Bind the operation to the checkout containing this helper. Job containers can
# expose the source files while ambient Git discovery or repository-location
# variables point somewhere else.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "$script_dir/../.." && pwd -P)"

# Preserve git diff's tri-state result for the caller: 0 means unchanged,
# 1 means changed, and every other status is an error that must fail closed.
exec env \
  -u GIT_DIR \
  -u GIT_WORK_TREE \
  -u GIT_COMMON_DIR \
  -u GIT_INDEX_FILE \
  -u GIT_OBJECT_DIRECTORY \
  -u GIT_ALTERNATE_OBJECT_DIRECTORIES \
  -u GIT_CEILING_DIRECTORIES \
  -u GIT_DISCOVERY_ACROSS_FILESYSTEM \
  git -C "$repo_root" diff --quiet --no-ext-diff --no-renames "$base" "$head" -- "$@"
