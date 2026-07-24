#!/usr/bin/env bash
# Fail-closed reader for the repository Gradle cache policy.
#
#   .github/gradle-cache-epoch       exactly one line matching [A-Za-z0-9_.-]{1,32}; no comments.
#   .github/gradle-cache-quarantine  blank/# lines ignored; each other line must be an
#                                    allowlisted gradle/actions bundle name.
#
# Exports for the following gradle/actions/setup-gradle step:
#   GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX=<epoch>-   (rotates the top-level Gradle User Home key;
#                                                    verified against gradle/actions@v5 source —
#                                                    extracted-entry keys are NOT covered)
#   GRADLE_BUILD_ACTION_SKIP_RESTORE=<b1,b2>        (only when the quarantine file lists bundles)
#
# Operating procedure: docs/ci-gradle-cache-epoch-runbook.md (task #786).
set -euo pipefail

repo_root="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
epoch_file="${repo_root}/.github/gradle-cache-epoch"
quarantine_file="${repo_root}/.github/gradle-cache-quarantine"
github_env="${GITHUB_ENV:?GITHUB_ENV is required}"

fail() { echo "gradle-cache-policy: $*" >&2; exit 1; }

[[ -f "${epoch_file}" ]] || fail "missing ${epoch_file}"
[[ -f "${quarantine_file}" ]] || fail "missing ${quarantine_file}"

epoch_line_count="$(wc -l < "${epoch_file}")"
epoch="$(head -n 1 "${epoch_file}")"
[[ "${epoch_line_count}" -eq 1 ]] || fail "epoch file must contain exactly one line, found ${epoch_line_count}"
[[ "${epoch}" =~ ^[A-Za-z0-9_.-]{1,32}$ ]] || fail "epoch value is not a single [A-Za-z0-9_.-]{1,32} token"

allowlist=(dependencies transforms)
bundles=()
while IFS= read -r line; do
  trimmed="${line//[[:space:]]/}"
  [[ -z "${trimmed}" || "${trimmed}" == \#* ]] && continue
  [[ "${trimmed}" == "${line}" ]] || fail "quarantine line contains whitespace: '${line}'"
  allowed=false
  for candidate in "${allowlist[@]}"; do
    [[ "${trimmed}" == "${candidate}" ]] && allowed=true
  done
  [[ "${allowed}" == true ]] || fail "quarantine bundle '${trimmed}' is not in the allowlist (${allowlist[*]})"
  for existing in ${bundles[@]+"${bundles[@]}"}; do
    [[ "${trimmed}" == "${existing}" ]] && fail "duplicate quarantine bundle '${trimmed}'"
  done
  bundles+=("${trimmed}")
done < "${quarantine_file}"

echo "GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX=${epoch}-" >> "${github_env}"
summary="epoch=${epoch}"
if [[ "${#bundles[@]}" -gt 0 ]]; then
  joined="$(IFS=,; echo "${bundles[*]}")"
  echo "GRADLE_BUILD_ACTION_SKIP_RESTORE=${joined}" >> "${github_env}"
  summary+=" quarantine=${joined}"
else
  summary+=" quarantine=(none)"
fi
echo "gradle-cache-policy: ${summary}"
