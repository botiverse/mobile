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
  require "json"
  require "yaml"
  workflow = YAML.load_file(".github/workflows/android-release.yml")
  update_workflow = YAML.load_file(".github/workflows/android-update-qa.yml")
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

  call_inputs = workflow.fetch(true).fetch("workflow_call").fetch("inputs")
  raise "reusable release workflow lacks source_sha" unless call_inputs.key?("source_sha")
  raise "reusable release workflow lacks unique artifact_name" unless call_inputs.key?("artifact_name")

  update_inputs = update_workflow.fetch(true).fetch("workflow_dispatch").fetch("inputs")
  raise "update workflow lacks required source_sha" unless update_inputs.fetch("source_sha").fetch("required")
  update_jobs = update_workflow.fetch("jobs")
  %w[validate baseline target manifest].each do |name|
    raise "update workflow missing #{name} job" unless update_jobs.key?(name)
  end
  %w[baseline target].each do |name|
    call = update_jobs.fetch(name)
    raise "#{name} does not reuse android-release" unless call.fetch("uses") == "./.github/workflows/android-release.yml"
    raise "#{name} can publish Hands" unless call.fetch("with").fetch("publish_hands") == "false"
    raise "#{name} is not release variant" unless call.fetch("with").fetch("android_variant") == "release"
    raise "#{name} is not debug carrier" unless call.fetch("with").fetch("quiver_channel") == "debug"
  end
  manifest_run = update_jobs.fetch("manifest").fetch("steps").find { |step| step["name"] == "Create immutable build-pair manifest" }.fetch("run")
  raise "update manifest does not fail closed on external writes" unless manifest_run.include?("external_writes: false")
  raise "update manifest lacks ordered signer lineage" unless manifest_run.include?("signer_lineage_sha256")
  raise "update manifest lacks v3.1 verification" unless manifest_run.include?("verified_v31: true")

  release_verify = jobs.fetch("build_android_release").fetch("steps").find { |step| step["name"] == "Verify APK" }.fetch("run")
  raise "release identity output does not canonicalize lineage oldest-to-current" unless release_verify.include?("| reverse")

  fixture_schema = JSON.parse(File.read("docs/qa-acceptance/android-app-update/android-app-update-fixture.v1.schema.json"))
  required = fixture_schema.fetch("required")
  %w[baseline target distribution execution rollback].each do |name|
    raise "fixture schema does not require #{name}" unless required.include?(name)
  end
  coords = fixture_schema.fetch("properties").fetch("execution").fetch("properties").fetch("required_coordinates").fetch("const")
  raise "fixture schema does not freeze Android 12 hard + Android 14 control" unless coords == %w[android12-update-hard android14-update-control]
  lineage = fixture_schema.fetch("$defs").fetch("artifact").fetch("properties").fetch("signer_lineage_sha256")
  raise "signer lineage ordering is undocumented" unless lineage.fetch("description").include?("oldest-to-current")
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
