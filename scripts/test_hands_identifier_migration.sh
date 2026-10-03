#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

# Historical prose and immutable upstream ABI names may still describe the old
# product. Repository-owned configuration, workflow, resource, and telemetry
# identifiers must use Hands naming exclusively. Split the old token so the
# gate itself cannot become the sole residual match.
old_upper='QUI'
old_upper+='VER'
old_lower='qui'
old_lower+='ver'
pattern="${old_upper}|SLOCK_${old_upper}|PUBLISH_${old_upper}|publish_${old_lower}|${old_lower}_channel|settings_${old_lower}|slock_${old_lower}|${old_lower}_(feedback|diagnostics)"
matches="$(
  git grep -n -E "${pattern}" -- . \
    ':(exclude)change-logs/**' \
    ':(exclude)docs/**' \
    ':(exclude)AGENTS.md' \
    ':(exclude)scripts/test_hands_identifier_migration.sh' || true
)"
unexpected=0
while IFS= read -r match; do
  [[ -n "${match}" ]] || continue
  case "${match}" in
    .github/workflows/android-release.yml:*"secrets.${old_upper}_BEARER_TOKEN"*) ;;
    .github/workflows/ios-release.yml:*"secrets.${old_upper}_IOS_BEARER_TOKEN"*) ;;
    *)
      echo "${match}"
      unexpected=1
      ;;
  esac
done <<< "${matches}"
if [[ "${unexpected}" == "1" ]]; then
  echo "FAIL: repository-owned pre-Hands identifiers remain" >&2
  exit 1
fi

[[ "$(grep -Fc "secrets.${old_upper}_BEARER_TOKEN" .github/workflows/android-release.yml)" == "3" ]] || {
  echo "FAIL: Android external secret allowlist drifted" >&2
  exit 1
}
[[ "$(grep -Fc "secrets.${old_upper}_IOS_BEARER_TOKEN" .github/workflows/ios-release.yml)" == "2" ]] || {
  echo "FAIL: iOS external secret allowlist drifted" >&2
  exit 1
}

for path in \
  "scripts/release_android_${old_lower}_github.sh" \
  "scripts/publish_android_${old_lower}_release.sh" \
  "scripts/prepare_android_${old_lower}_symbols.sh"; do
  if [[ -e "${path}" ]]; then
    echo "FAIL: removed compatibility path returned: ${path}" >&2
    exit 1
  fi
done

echo "Hands identifier migration contract: PASS"
