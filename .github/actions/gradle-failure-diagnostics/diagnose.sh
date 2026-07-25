#!/usr/bin/env bash
# Bounded disk diagnostics for failed Gradle jobs, wired as the last step with
# `if: failure()` so the disk-state evidence for cache/disk postmortems is
# already in the job log (#781: truncated AGP jar, missing KSP loader pieces
# needed a manual diagnostic rerun).
#
# Never fails the job itself: the runner invokes composite `shell: bash` steps
# with -eo pipefail and exports that via SHELLOPTS, so both are explicitly
# disabled here instead of relying on the absence of `set -e`.
#
# Portable by contract: Bash 3.2 (macOS runners) and GNU/BSD userland — no
# mapfile, no find -printf, no sort -h; du -sk + sort -rn (numeric) instead.
# Every section is bounded to its top 20 lines.
set +e +o pipefail

gradle_home="${GRADLE_USER_HOME:-${HOME}/.gradle}"

section() {
  printf '\n===== %s =====\n' "$1"
}

# du -sk + sort -rn works on GNU and BSD alike; awk renders the KB as MB.
du_top() {
  du -sk "$@" 2>/dev/null | sort -rn | head -n 20 | awk '{printf "%8.1f MB  %s\n", $1/1024, $2}'
}

section "df -h"
df -h | head -n 20

section "df -i"
df -i | head -n 20

section "Gradle home top-level usage (${gradle_home})"
du_top "${gradle_home}"/*

section "Gradle caches breakdown (${gradle_home}/caches)"
du_top "${gradle_home}"/caches/*

# The #781 cache-poisoning signature was jars truncated well below 10k.
section "Zero-byte / truncated jars under ${gradle_home}/caches (<10k)"
find "${gradle_home}/caches" -name '*.jar' -size -10k 2>/dev/null | head -n 20

if [[ -n "${GITHUB_WORKSPACE:-}" ]]; then
  section "compose/*/build dir sizes (${GITHUB_WORKSPACE})"
  du_top "${GITHUB_WORKSPACE}"/compose/*/build
fi

section "Memory"
if command -v free >/dev/null 2>&1; then
  free -h
elif command -v vm_stat >/dev/null 2>&1; then
  vm_stat | head -n 10
else
  echo "(neither free nor vm_stat available)"
fi

exit 0
