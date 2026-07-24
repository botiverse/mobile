#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

for script in \
  scripts/resolve_android_publish_hands_input.sh \
  scripts/test_resolve_android_publish_hands_input.sh \
  scripts/release_android_hands_github.sh \
  scripts/release_android_quiver_github.sh \
  scripts/publish_android_hands_release.sh \
  scripts/publish_android_quiver_release.sh \
  scripts/prepare_android_hands_symbols.sh \
  scripts/prepare_android_quiver_symbols.sh; do
  bash -n "${script}"
done

scripts/test_resolve_android_publish_hands_input.sh

env -u SLOCK_PUBLISH_HANDS -u SLOCK_PUBLISH_QUIVER \
  scripts/release_android_hands_github.sh --help |
  grep -q 'publish_hands=true' || fail "canonical wrapper help does not expose publish_hands"
if env SLOCK_PUBLISH_HANDS=true SLOCK_PUBLISH_QUIVER=false \
    scripts/release_android_hands_github.sh --help >/dev/null 2>&1; then
  fail "canonical wrapper accepted conflicting publish env aliases"
fi

ruby -e '
  require "yaml"
  workflow = YAML.load_file(".github/workflows/android-release.yml")
  YAML.load_file(".github/workflows/android-release-contract.yml")

  inputs = workflow.fetch(true).fetch("workflow_dispatch").fetch("inputs")
  raise "missing publish_hands input" unless inputs.key?("publish_hands")
  raise "missing legacy publish_quiver alias" unless inputs.key?("publish_quiver")

  jobs = workflow.fetch("jobs")
  raise "missing publish_hands job" unless jobs.key?("publish_hands")
  raise "legacy publish_quiver job remains" if jobs.key?("publish_quiver")
  publish_job = jobs.fetch("publish_hands")
  unless publish_job.fetch("if").include?("needs.resolve.outputs.publish_hands == '\''true'\''")
    raise "publish job is not gated by publish_hands"
  end
  share_step = publish_job.fetch("steps").find { |step| step["id"] == "create-share-page" }
  raise "missing independent share step" unless share_step
  unless share_step.fetch("if") == "needs.resolve.outputs.create_share_page == '\''true'\''"
    raise "share step is not independently gated"
  end
'

grep -Eq '^      publish_hands:$' .github/workflows/android-release.yml ||
  fail "android-release.yml is missing the canonical publish_hands input"
grep -Eq '^  publish_hands:$' .github/workflows/android-release.yml ||
  fail "android-release.yml is missing the canonical publish_hands job"
grep -Eq 'needs\.resolve\.outputs\.publish_hands' .github/workflows/android-release.yml ||
  fail "publish job is not gated by the canonical publish_hands output"
grep -Eq -- '-f "publish_hands=\$\{publish_hands\}"' scripts/release_android_hands_github.sh ||
  fail "canonical dispatch wrapper does not send publish_hands"

if grep -En 'needs\.resolve\.outputs\.publish_quiver|steps\.p\.outputs\.publish_quiver|^-?  publish_quiver:$' \
    .github/workflows/android-release.yml; then
  fail "deprecated publish_quiver is still used as an operational workflow output or job"
fi
if grep -En -- '-f "publish_quiver=' scripts/release_android_hands_github.sh; then
  fail "canonical dispatch wrapper still sends publish_quiver"
fi

mapfile -t residual_files < <(git grep -l 'publish_quiver' -- . | sort)
for path in "${residual_files[@]}"; do
  case "${path}" in
    .github/workflows/android-release.yml|\
    docs/android-hands-release-migration-2026-07-24.md|\
    docs/android-release-sop.md|\
    scripts/resolve_android_publish_hands_input.sh|\
    scripts/test_resolve_android_publish_hands_input.sh|\
    scripts/test_android_hands_release_contract.sh)
      ;;
    *) fail "publish_quiver remains outside the compatibility shim/migration contract: ${path}" ;;
  esac
done

for canonical_path in \
  docs/android-hands-release-practices-2026-07-02.md \
  scripts/release_android_hands_github.sh \
  scripts/publish_android_hands_release.sh \
  scripts/prepare_android_hands_symbols.sh; do
  [ -e "${canonical_path}" ] || fail "missing canonical Hands path: ${canonical_path}"
done

echo "Android Hands release workflow contract: PASS"
