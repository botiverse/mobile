#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

for script in \
  scripts/release_android_hands_github.sh \
  scripts/publish_android_hands_release.sh \
  scripts/finalize_android_hands_release.sh \
  scripts/prepare_android_hands_symbols.sh \
  scripts/sign_android_aab.sh \
  scripts/verify_android_aab.sh \
  scripts/upload_android_hands_artifacts.sh; do
  bash -n "${script}"
done

PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_verify_android_dex_packaging.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_verify_android_elf_alignment.py

env -u SLOCK_CREATE_HANDS_DRAFT \
  scripts/release_android_hands_github.sh --help |
  grep -q 'create_hands_draft=true' || fail "canonical wrapper help does not expose draft creation"

ruby -e '
  require "json"
  require "rexml/document"
  require "yaml"
  workflow = YAML.load_file(".github/workflows/android-release.yml")
  ios_workflow = YAML.load_file(".github/workflows/ios-release.yml")
  update_workflow = YAML.load_file(".github/workflows/android-update-qa.yml")
  YAML.load_file(".github/workflows/android-release-contract.yml")

  inputs = workflow.fetch(true).fetch("workflow_dispatch").fetch("inputs")
  raise "manual release must not expose a skip-draft switch" if inputs.key?("publish_hands") || inputs.key?("create_hands_draft")
  dex_input = inputs.fetch("dex_packaging")
  raise "release workflow does not default to compressed DEX" unless dex_input.fetch("default") == "compressed"
  raise "release workflow DEX choices drifted" unless dex_input.fetch("options") == %w[current compressed mmap]
  jni_input = inputs.fetch("jni_packaging")
  raise "release workflow does not default to the current JNI control" unless jni_input.fetch("default") == "current"
  raise "release workflow JNI choices drifted" unless jni_input.fetch("options") == %w[current legacy modern]
  android_variant_input = inputs.fetch("android_variant")
  raise "release workflow must expose the Play carrier" unless android_variant_input.fetch("options") == %w[release play debug]
  upload_input = inputs.fetch("upload_android_artifacts")
  raise "manual artifact upload must default off" unless upload_input.fetch("default") == false

  jobs = workflow.fetch("jobs")
  resolve_job = jobs.fetch("resolve")
  resolve_input_step = resolve_job.fetch("steps").find { |step| step["id"] == "p" }
  raise "missing canonical release input resolver step" unless resolve_input_step
  resolve_env = resolve_input_step.fetch("env")
  unless resolve_env.fetch("INPUT_DEX_PACKAGING") == "${{ inputs.dex_packaging }}" &&
      resolve_env.fetch("INPUT_JNI_PACKAGING") == "${{ inputs.jni_packaging }}"
    raise "packaging inputs must cross into the resolver through step env"
  end
  resolve_run = resolve_input_step.fetch("run")
  unless resolve_run.include?("DEX_PACKAGING=\"${INPUT_DEX_PACKAGING}\"") &&
      resolve_run.include?("JNI_PACKAGING=\"${INPUT_JNI_PACKAGING}\"")
    raise "packaging resolver does not read env-bound inputs"
  end
  if resolve_run.include?("DEX_PACKAGING=\"${{ inputs.dex_packaging }}\"") ||
      resolve_run.include?("JNI_PACKAGING=\"${{ inputs.jni_packaging }}\"")
    raise "packaging input expression is injected into shell source"
  end
  raise "missing create_hands_draft job" unless jobs.key?("create_hands_draft")
  publish_job = jobs.fetch("create_hands_draft")
  expected_draft_gate = "github.event_name == '\''workflow_dispatch'\'' &&\n" \
    "needs.resolve.outputs.create_hands_draft == '\''true'\'' &&\n" \
    "needs.resolve.outputs.android_variant != '\''play'\''"
  unless publish_job.fetch("if").strip == expected_draft_gate
    raise "draft job is not restricted to opted-in manual dispatches"
  end
  share_step = publish_job.fetch("steps").find { |step| step["id"] == "create-share-page" }
  raise "missing independent share step" unless share_step
  unless share_step.fetch("if") == "needs.resolve.outputs.create_share_page == '\''true'\''"
    raise "share step is not independently gated"
  end
  publish_step = publish_job.fetch("steps").find { |step| step["id"] == "publish-hands-release" }
  publish_run = publish_step.fetch("run")
  raise "release workflow does not require reviewed Japanese notes" unless publish_run.include?("/ja.md") && publish_run.include?("SLOCK_RELEASE_CHANGELOG_JA_FILE")
  summary_run = publish_job.fetch("steps").find { |step| step["name"] == "Step summary" }.fetch("run")
  raise "release workflow summary drops Japanese notes" unless summary_run.include?("--changelog-file ja=ja.md")

  ios_inputs = ios_workflow.fetch(true).fetch("workflow_dispatch").fetch("inputs")
  raise "iOS release still exposes publish_hands" if ios_inputs.key?("publish_hands")
  raise "iOS release mode is not draft-only" unless ios_inputs.fetch("release_mode").fetch("options") == ["draft"]
  ios_draft_job = ios_workflow.fetch("jobs").fetch("create_hands_draft")
  raise "iOS draft upload is still optional" if ios_draft_job.key?("if")
  raise "iOS draft job can activate" unless ios_draft_job.fetch("env").fetch("SLOCK_HANDS_DRAFT") == "1"
  ios_publish_run = ios_draft_job.fetch("steps").find { |step| step["id"] == "publish-hands-release" }.fetch("run")
  raise "iOS CI does not force a draft" unless ios_publish_run.include?("--draft") && !ios_publish_run.include?("--publish")

  call_inputs = workflow.fetch(true).fetch("workflow_call").fetch("inputs")
  raise "reusable release workflow lacks source_sha" unless call_inputs.key?("source_sha")
  raise "reusable release workflow lacks unique artifact_name" unless call_inputs.key?("artifact_name")
  raise "reusable release workflow does not default compressed DEX" unless call_inputs.fetch("dex_packaging").fetch("default") == "compressed"
  raise "reusable JNI control does not default current" unless call_inputs.fetch("jni_packaging").fetch("default") == "current"

  update_inputs = update_workflow.fetch(true).fetch("workflow_dispatch").fetch("inputs")
  raise "update workflow lacks required source_sha" unless update_inputs.fetch("source_sha").fetch("required")
  update_permissions = update_workflow.fetch("permissions")
  raise "update caller does not preserve callee packages:read" unless update_permissions.fetch("packages") == "read"
  update_jobs = update_workflow.fetch("jobs")
  %w[validate baseline target manifest].each do |name|
    raise "update workflow missing #{name} job" unless update_jobs.key?(name)
  end
  %w[baseline target].each do |name|
    call = update_jobs.fetch(name)
    raise "#{name} does not reuse android-release" unless call.fetch("uses") == "./.github/workflows/android-release.yml"
    raise "#{name} can create a Hands draft" unless call.fetch("with").fetch("create_hands_draft") == false
    raise "#{name} is not release variant" unless call.fetch("with").fetch("android_variant") == "release"
    raise "#{name} is not debug carrier" unless call.fetch("with").fetch("hands_channel") == "debug"
  end
  manifest_run = update_jobs.fetch("manifest").fetch("steps").find { |step| step["name"] == "Create immutable build-pair manifest" }.fetch("run")
  raise "update manifest does not fail closed on external writes" unless manifest_run.include?("external_writes: false")
  raise "update manifest lacks ordered signer lineage" unless manifest_run.include?("signer_lineage_sha256")
  raise "update manifest lacks v3.1 verification" unless manifest_run.include?("verified_v31: true")

  release_steps = jobs.fetch("build_android_release").fetch("steps")
  stage_index = release_steps.index { |step| step["name"] == "Stage workflow-owned release implementations" }
  product_index = release_steps.index { |step| step["name"] == "Checkout immutable product source" }
  restore_index = release_steps.index { |step| step["name"] == "Restore workflow-owned release implementations" }
  verify_index = release_steps.index { |step| step["name"] == "Verify APK" }
  raise "release workflow lacks workflow-owned staging" unless stage_index
  raise "release workflow lacks immutable product checkout" unless product_index
  raise "release workflow lacks workflow-owned restore" unless restore_index
  raise "release workflow staging order is unsafe" unless stage_index < product_index && product_index < restore_index && restore_index < verify_index
  stage_run = release_steps.fetch(stage_index).fetch("run")
  unless stage_run.include?("scripts/verify_android_apk_with_rotation.sh")
    raise "release workflow does not stage the canonical rotation verifier"
  end
  unless stage_run.include?("scripts/verify_android_dex_packaging.py")
    raise "release workflow does not stage the DEX packaging verifier"
  end
  unless stage_run.include?("scripts/verify_android_elf_alignment.py")
    raise "release workflow does not stage the 16 KB ELF verifier"
  end

  aab_sign_index = release_steps.index { |step| step["id"] == "sign-aab" }
  upload_index = release_steps.index { |step| step["id"] == "hands-artifacts" }
  raise "AAB is uploaded before signing" unless aab_sign_index && aab_sign_index < upload_index
  %w[scripts/sign_android_aab.sh scripts/verify_android_aab.sh scripts/VerifyAndroidAab.java].each do |path|
    raise "immutable checkout loses AAB signing implementation" unless stage_run.include?(path)
  end
  upload_env = release_steps.fetch(upload_index).fetch("env")
  hands_upload_if = release_steps.fetch(upload_index).fetch("if").to_s
  raise "Hands upload step is not excluded for Play" unless hands_upload_if.include?("upload_android_artifacts") && hands_upload_if.include?("android_variant") && hands_upload_if.include?("play")
  artifact_step = release_steps.find { |step| step["name"] == "Upload signed AAB and verification receipt" }
  raise "signed AAB artifact step is missing" unless artifact_step
  raise "signed AAB artifact is not controlled by the resolved upload policy" unless artifact_step.fetch("if").to_s.include?("needs.resolve.outputs.upload_android_artifacts")
  raise "uploader bypasses signed AAB" unless upload_env.fetch("AAB_PATH") == "${{ steps.sign-aab.outputs.aab_path }}"
  raise "upload pin is not derived from verified AAB" unless upload_env.fetch("UPLOAD_KEY_CERT_SHA256") == "${{ steps.sign-aab.outputs.upload_key_cert_sha256 }}"
  raise "repair dispatch can schedule normal build" unless resolve_job.fetch("if").include?("inputs.aab_signing_repair == " + 39.chr + 39.chr)
  repair = jobs.fetch("sign_existing_aab")
  raise "repair is not dispatch-only" unless repair.fetch("if").include?("github.event_name == " + 39.chr + "workflow_dispatch" + 39.chr)
  repair_runs = repair.fetch("steps").map { |step| step.fetch("run", "") }.join("\n")
  raise "repair runs a build or publishes" if repair_runs.match?(/gradlew|upload_android_hands_artifacts|hands publish/)
  raise "repair lacks source/run provenance" unless repair_runs.include?("signing:") && repair_runs.include?("workflow_sha:") && repair_runs.include?("rebuilt:false")

  release_verify = release_steps.fetch(verify_index).fetch("run")
  unless release_verify.include?("scripts/verify_android_apk_with_rotation.sh")
    raise "release workflow bypasses the canonical API 24-32 rotation verifier"
  end
  unless release_verify.include?("scripts/verify_android_dex_packaging.py") &&
      release_verify.include?("--expect-dex-method deflated") &&
      release_verify.include?("--expect-dex-method stored") &&
      release_verify.include?("--max-apk-size-bytes 47185920") &&
      release_verify.include?("--max-dex-compression-ratio 0.60")
    raise "release workflow bypasses the DEX compression and size contract"
  end
  unless release_verify.include?("scripts/verify_android_elf_alignment.py") &&
      release_verify.include?("--expect-native-method") &&
      release_verify.include?("--require-abi arm64-v8a") &&
      release_verify.include?("--require-abi x86_64")
    raise "release workflow bypasses the 16 KB ELF/native packaging contract"
  end
  raise "release identity output does not canonicalize lineage oldest-to-current" unless release_verify.include?("| reverse")

  android_gradle = File.read("compose/androidApp/build.gradle.kts")
  raise "Android build does not define the Play carrier" unless android_gradle.include?(%q{create("play")})
  raise "Play carrier leaves Hands APK updates enabled" unless android_gradle.include?(%q{buildConfigField("Boolean", "HANDS_APK_UPDATE_ENABLED", "false")})
  play_manifest = File.read("compose/androidApp/src/play/AndroidManifest.xml")
  raise "Play manifest does not remove REQUEST_INSTALL_PACKAGES" unless play_manifest.include?(%q{android.permission.REQUEST_INSTALL_PACKAGES}) && play_manifest.include?(%q{tools:node="remove"})
  main_manifest = File.read("compose/androidApp/src/main/AndroidManifest.xml")
  raise "Hands source manifest no longer declares REQUEST_INSTALL_PACKAGES" unless main_manifest.include?(%q{android.permission.REQUEST_INSTALL_PACKAGES})
  raise "Hands release manifest must retain the source permission" if File.exist?("compose/androidApp/src/release/AndroidManifest.xml") && File.read("compose/androidApp/src/release/AndroidManifest.xml").include?(%q{tools:node="remove"})
  kuikly_activity = File.read("compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt")
  raise "Play carrier does not gate the Hands updater bridge" unless kuikly_activity.include?("BuildConfig.HANDS_APK_UPDATE_ENABLED") && kuikly_activity.include?("handsApkUpdateDisabledResponse()")
  resolve_outputs = resolve_job.fetch("outputs")
  raise "release resolver does not expose artifact-upload policy" unless resolve_outputs.key?("upload_android_artifacts")
  raise "release resolver does not receive artifact-upload input" unless resolve_env.fetch("INPUT_UPLOAD_ANDROID_ARTIFACTS") == "${{ inputs.upload_android_artifacts }}"
  raise "Play carrier does not require a retained signed AAB" unless resolve_run.include?("Play builds require upload_android_artifacts=true")
  unless android_gradle.include?("providers.gradleProperty(\"raftDexUseLegacyPackaging\")") &&
      android_gradle.include?("providers.gradleProperty(\"raftJniUseLegacyPackaging\")") &&
      android_gradle.match?(/packaging\s*\{.*?dex\s*\{.*?useLegacyPackaging\s*=\s*raftDexUseLegacyPackaging\.orNull/m) &&
      android_gradle.match?(/jniLibs\s*\{.*?useLegacyPackaging\s*=\s*raftJniUseLegacyPackaging\.orNull\s*\?:\s*true/m)
    raise "Android packaging does not expose the exact DEX/JNI matrix properties"
  end

  contract_paths = YAML.load_file(".github/workflows/android-release-contract.yml").fetch(true)
  %w[pull_request push].each do |event|
    paths = contract_paths.fetch(event).fetch("paths")
    unless paths.include?("compose/androidApp/build.gradle.kts") &&
        paths.include?("scripts/verify_android_dex_packaging.py") &&
        paths.include?("scripts/test_verify_android_dex_packaging.py") &&
        paths.include?("scripts/verify_android_elf_alignment.py") &&
        paths.include?("scripts/test_verify_android_elf_alignment.py")
      raise "Android release contract does not watch packaging/alignment inputs"
    end
  end

  fixture_schema = JSON.parse(File.read("docs/qa-acceptance/android-app-update/android-app-update-fixture.v1.schema.json"))
  required = fixture_schema.fetch("required")
  %w[baseline target distribution execution rollback].each do |name|
    raise "fixture schema does not require #{name}" unless required.include?(name)
  end
  execution = fixture_schema.fetch("properties").fetch("execution")
  raise "fixture schema does not require machine coverage" unless %w[required_coordinates coverage].all? { |name| execution.fetch("required").include?(name) }
  coords = execution.fetch("properties").fetch("required_coordinates").fetch("const")
  raise "fixture schema does not freeze owner-approved Android 14 denominator" unless coords == %w[android14-update-required]
  invalid_coords = [[], %w[android14], %w[android14-update-required extra], %w[android12-update-hard android14-update-control]]
  raise "fixture schema admits invalid Android coordinate set" if invalid_coords.any? { |candidate| candidate == coords }
  coverage = execution.fetch("properties").fetch("coverage")
  waiver = coverage.fetch("properties").fetch("waived_coordinates").fetch("const")
  expected_waiver = [{"coordinate" => "android12-update-hard", "status" => "NOT_RUN", "reason" => "owner-waived", "decision_ref" => "#反馈:1bc169a5:84dc5c7f"}]
  raise "fixture schema does not require exact Android 12 waiver" unless coverage.fetch("required").include?("waived_coordinates") && waiver == expected_waiver
  modes = fixture_schema.fetch("properties").fetch("mode").fetch("enum")
  raise "fixture schema lacks canonical DownloadManager full mode" unless modes.include?("full-downloadmanager")
  raise "fixture schema still permits superseded direct-downloader mode" if modes.include?("full-direct-downloader")
  update_readme = File.read("docs/qa-acceptance/android-app-update/README.md")
  raise "Android 14 denominator lacks owner-decision provenance" unless update_readme.include?("84dc5c7f")
  raise "Android 12 waiver is not explicitly NOT RUN" unless update_readme.include?("Android 12 is explicitly owner-waived") && update_readme.include?("`NOT RUN`")
  lineage = fixture_schema.fetch("$defs").fetch("artifact").fetch("properties").fetch("signer_lineage_sha256")
  raise "signer lineage ordering is undocumented" unless lineage.fetch("description").include?("oldest-to-current")

  full_case = YAML.load_file("docs/qa-acceptance/android-app-update/android-app-update-full-success.v1.yaml")
  expected_states = full_case.fetch("android").fetch("carrier_contract").fetch("expected_ui_states")
  raise "full update case does not require Retry" unless expected_states.include?("Retry")
  raise "full update case denominator drifted" unless full_case.fetch("android").fetch("device").fetch("required_coordinates") == ["android14-update-required"]
  case_waiver = full_case.fetch("android").fetch("device").fetch("waived_coordinates")
  raise "full update case lacks exact Android 12 waiver" unless case_waiver == expected_waiver
  invalid_waivers = [[], [expected_waiver.first.merge("status" => "PASS")], [expected_waiver.first.merge("reason" => "missing-capacity")]]
  raise "full update case admits missing or PASS Android 12 waiver" if invalid_waivers.any? { |candidate| candidate == case_waiver }
  raise "full update case does not retain App Update DownloadManager" unless full_case.to_s.include?("DownloadManager")
  expected_notes = full_case.fetch("expected").fetch("notes")
  raise "full update case misreports Android 12 waiver" unless expected_notes.include?("owner-waived") && expected_notes.include?("NOT RUN")
  step_ids = full_case.fetch("android").fetch("steps").map { |step| step.fetch("id") }
  %w[inject-network-failure verify-retry].each do |id|
    raise "full update case missing #{id} step" unless step_ids.include?(id)
  end
  assertion_ids = full_case.fetch("assertions").map { |assertion| assertion.fetch("id") }
  %w[download-failure-actionable-retry retry-target-b-exact].each do |id|
    raise "full update case missing #{id} assertion" unless assertion_ids.include?(id)
  end

  publish_script = File.read("scripts/publish_android_hands_release.sh")
  raise "Hands publisher can route the Play carrier" unless publish_script.include?("the Hands publisher cannot publish the Play carrier")
  raise "Hands publisher drops Japanese notes" unless publish_script.include?("--changelog-file \"ja=${SLOCK_RELEASE_CHANGELOG_JA_FILE}\"")
  finalizer = File.read("scripts/finalize_android_hands_release.sh")
  raise "Hands finalizer does not require Japanese notes" unless finalizer.include?("--ja-notes") && finalizer.include?("--changelog-file \"ja=${JA_NOTES}\"")
  sop = File.read("docs/android-release-sop.md")
  raise "Android release SOP does not document Japanese notes" unless sop.include?("change-logs/1.2.2/android/ja.md")
  terminology_contract = [
    "botiverse/slock",
    "packages/web/src/i18n/messages/",
    "compose/shared/src/commonMain/resources/MR/<locale>/strings.xml",
    "`回应`",
    "`任务状态`",
    "`处理中`",
    "`リアクション`",
    "`タスクステータス`",
    "`処理中`",
  ]
  raise "Android release SOP does not require canonical shipped-locale terminology" unless terminology_contract.all? { |term| sop.include?(term) }

  ja_resource = REXML::Document.new(File.read("compose/shared/src/commonMain/resources/MR/ja/strings.xml"))
  ja_strings = {}
  REXML::XPath.each(ja_resource, "/resources/string") do |element|
    ja_strings[element.attributes["name"]] = element.text.to_s
  end
  {
    "thread_error_task_status_unsupported" => "タスクステータス",
    "app_update_working" => "処理中",
  }.each do |key, canonical|
    value = ja_strings.fetch(key)
    raise "Japanese terminology source #{key} no longer contains #{canonical.inspect}" unless value.include?(canonical)
  end

  release_copy = Dir["change-logs/**/*.md"].to_h { |path| [path, File.read(path)] }
  deprecated_zh_terms = {
    "表情回应" => "回应",
    "任务状态芯片" => "任务状态",
    "等待中的芯片" => "处理中",
  }
  deprecated_zh_terms.each do |deprecated, canonical|
    offenders = release_copy.each_with_object([]) do |(path, copy), paths|
      paths << path if copy.include?(deprecated)
    end
    raise "release notes use #{deprecated.inspect}; align to Web term #{canonical.inspect}: #{offenders.join(", ")}" unless offenders.empty?
  end
  deprecated_ja_terms = {
    "タスク状態" => "タスクステータス",
    "保存済み表示" => "保存済み状態",
  }
  deprecated_ja_terms.each do |deprecated, canonical|
    offenders = release_copy.each_with_object([]) do |(path, copy), paths|
      paths << path if copy.include?(deprecated)
    end
    raise "release notes use #{deprecated.inspect}; align to Mobile term #{canonical.inspect}: #{offenders.join(", ")}" unless offenders.empty?
  end

  release_note_headers = {
    "zh-CN" => ["✨ **新功能**", "🔧 **改进**", "🐛 **修复**"],
    "en" => ["✨ **New**", "🔧 **Improved**", "🐛 **Fixed**"],
    "ja" => ["✨ **新機能**", "🔧 **改善**", "🐛 **修正**"],
  }
  release_note_counts = release_note_headers.map do |locale, headers|
    path = "change-logs/1.8.0/android/#{locale}.md"
    lines = File.readlines(path, chomp: true)
    indexes = headers.map { |header| lines.index(header) }
    raise "#{path} has missing or reordered sections" unless indexes.all? && indexes == indexes.sort
    headers.each_index.map do |index|
      finish = indexes[index + 1] || lines.length
      lines[(indexes[index] + 1)...finish].count { |line| line.start_with?("- ") }
    end
  end
  raise "1.8.0 release-note locales do not carry matching section claims" unless release_note_counts.uniq.length == 1
  raise "1.8.0 release notes contain an empty section" unless release_note_counts.first.all?(&:positive?)
'

grep -Eq '^      create_hands_draft:$' .github/workflows/android-release.yml ||
  fail "android-release.yml is missing the reusable build-only draft control"
grep -Eq '^  create_hands_draft:$' .github/workflows/android-release.yml ||
  fail "android-release.yml is missing the create_hands_draft job"
grep -Eq 'needs\.resolve\.outputs\.create_hands_draft' .github/workflows/android-release.yml ||
  fail "draft job is not gated by the canonical create_hands_draft output"
grep -Eq "github\.event_name == 'workflow_dispatch'" .github/workflows/android-release.yml ||
  fail "draft job is not structurally restricted to manual dispatches"
if grep -Eq 'publish_hands' .github/workflows/android-release.yml .github/workflows/ios-release.yml; then
  fail "release workflows still overload publish_hands"
fi
if grep -R -Eq 'publish_hands' docs; then
  fail "release documentation still references the removed publish_hands input"
fi
if grep -R -Eq --include='*.sh' --exclude='test_*' 'publish_hands' scripts; then
  fail "production scripts still reference the removed publish_hands input"
fi
grep -Eq -- '-f "dex_packaging=\$\{DEX_PACKAGING\}"' scripts/release_android_hands_github.sh ||
  fail "canonical dispatch wrapper does not send dex_packaging"
grep -Eq -- '-f "jni_packaging=\$\{JNI_PACKAGING\}"' scripts/release_android_hands_github.sh ||
  fail "canonical dispatch wrapper does not send jni_packaging"
grep -Fq 'SHARE_TTL_SECONDS="${SLOCK_SHARE_TTL_SECONDS:-}"' scripts/release_android_hands_github.sh ||
  fail "canonical dispatch wrapper does not default share TTL to omitted"
if grep -Fq 'SLOCK_SHARE_TTL_SECONDS:-604800' scripts/release_android_hands_github.sh; then
  fail "canonical dispatch wrapper still inherits the seven-day share TTL"
fi
grep -Fq 'if [ -n "${SHARE_TTL_SECONDS}" ]; then' scripts/release_android_hands_github.sh ||
  fail "canonical dispatch wrapper does not gate the optional share TTL"
grep -Fq 'workflow_fields+=( -f "share_ttl_seconds=${SHARE_TTL_SECONDS}" )' scripts/release_android_hands_github.sh ||
  fail "canonical dispatch wrapper does not send an explicitly requested share TTL"

for canonical_path in \
  docs/android-hands-release-practices-2026-07-02.md \
  scripts/release_android_hands_github.sh \
  scripts/publish_android_hands_release.sh \
  scripts/finalize_android_hands_release.sh \
  scripts/prepare_android_hands_symbols.sh \
  scripts/sign_android_aab.sh \
  scripts/verify_android_aab.sh \
  scripts/upload_android_hands_artifacts.sh; do
  [ -e "${canonical_path}" ] || fail "missing canonical Hands path: ${canonical_path}"
done

python3 scripts/test_upload_android_hands_artifacts.py

echo "Android Hands release workflow contract: PASS"
