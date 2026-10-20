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

[[ "$(grep -Fc "secrets.${old_upper}_BEARER_TOKEN" .github/workflows/android-release.yml)" == "4" ]] || {
  echo "FAIL: Android external secret allowlist drifted" >&2
  exit 1
}
# The legacy key is external storage, not a repository-owned runtime name.
# Freeze every allowed consumer, including the signing-only repair downloader;
# merely increasing the count must not authorize use in another job or env.
ruby - "$old_upper" <<'RUBY'
require "yaml"

def secret_references(node, token, path = [], result = {})
  case node
  when Hash
    node.each { |key, value| secret_references(value, token, path + [key], result) }
  when Array
    node.each_with_index do |value, index|
      name = value.is_a?(Hash) ? (value["id"] || value["name"] || index) : index
      secret_references(value, token, path + [name], result)
    end
  when String
    result[path] = node if node.include?(token)
  end
  result
end

token = "secrets.#{ARGV.fetch(0)}_BEARER_TOKEN"
expression = "${{ " + token + " }}"
expected = {
  ["jobs", "build_android_release", "steps", "hands-artifacts", "env", "HANDS_BEARER_TOKEN"] => expression,
  ["jobs", "create_hands_draft", "env", "HANDS_BEARER_TOKEN"] => expression,
  ["jobs", "create_hands_draft", "env", "HANDS_AUTH_TOKEN"] => expression,
  ["jobs", "sign_existing_aab", "steps", "Download exact sealed AAB and APK", "env", "HANDS_BEARER_TOKEN"] => expression,
}
workflow = YAML.load_file(".github/workflows/android-release.yml")
actual = secret_references(workflow, token)
abort "FAIL: Android external secret consumer allowlist drifted" unless actual == expected

# Ensure the location-aware check still rejects extra, misplaced and missing
# usages even if a future edit happens to preserve the total reference count.
extra = Marshal.load(Marshal.dump(workflow))
extra["env"] ||= {}
extra["env"]["HANDS_BEARER_TOKEN"] = expression
missing = Marshal.load(Marshal.dump(workflow))
repair_step = missing.fetch("jobs").fetch("sign_existing_aab").fetch("steps").find { |step| step["name"] == "Download exact sealed AAB and APK" }
repair_step.fetch("env").delete("HANDS_BEARER_TOKEN")
misplaced = Marshal.load(Marshal.dump(missing))
misplaced["env"] ||= {}
misplaced["env"]["HANDS_BEARER_TOKEN"] = expression
[extra, missing, misplaced].each do |mutation|
  abort "FAIL: Android secret allowlist admitted an invalid consumer" if secret_references(mutation, token) == expected
end
RUBY

# iOS: the same location-aware freeze. The opt-in testflight_internal job
# (#3242) is the third allowed consumer; any other job or env must fail.
ruby - "$old_upper" <<'RUBY'
require "yaml"

def secret_references(node, token, path = [], result = {})
  case node
  when Hash
    node.each { |key, value| secret_references(value, token, path + [key], result) }
  when Array
    node.each_with_index do |value, index|
      name = value.is_a?(Hash) ? (value["id"] || value["name"] || index) : index
      secret_references(value, token, path + [name], result)
    end
  when String
    result[path] = node if node.include?(token)
  end
  result
end

token = "secrets.#{ARGV.fetch(0)}_IOS_BEARER_TOKEN"
expression = "${{ " + token + " }}"
expected = {
  ["jobs", "create_hands_draft", "env", "HANDS_BEARER_TOKEN"] => expression,
  ["jobs", "create_hands_draft", "env", "HANDS_AUTH_TOKEN"] => expression,
  ["jobs", "testflight_internal", "env", "HANDS_AUTH_TOKEN"] => expression,
}
workflow = YAML.load_file(".github/workflows/ios-release.yml")
actual = secret_references(workflow, token)
abort "FAIL: iOS external secret consumer allowlist drifted" unless actual == expected

extra = Marshal.load(Marshal.dump(workflow))
extra["env"] ||= {}
extra["env"]["HANDS_AUTH_TOKEN"] = expression
abort "FAIL: iOS secret allowlist admitted an invalid consumer" if secret_references(extra, token) == expected
RUBY

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
