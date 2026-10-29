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
  require "yaml"
  workflow = YAML.load_file(".github/workflows/android-release.yml")
  ios_workflow = YAML.load_file(".github/workflows/ios-release.yml")
  update_workflow = YAML.load_file(".github/workflows/android-update-qa.yml")

  inputs = workflow.fetch(true).fetch("workflow_dispatch").fetch("inputs")
  raise "manual release must not expose a skip-draft switch" if inputs.key?("publish_hands") || inputs.key?("create_hands_draft")
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

  ios_inputs = ios_workflow.fetch(true).fetch("workflow_dispatch").fetch("inputs")
  raise "iOS release still exposes publish_hands" if ios_inputs.key?("publish_hands")
  raise "iOS release mode is not draft-only" unless ios_inputs.fetch("release_mode").fetch("options") == ["draft"]
  ios_draft_job = ios_workflow.fetch("jobs").fetch("create_hands_draft")
  raise "iOS draft upload is still optional" if ios_draft_job.key?("if")
  raise "iOS draft job can activate" unless ios_draft_job.fetch("env").fetch("SLOCK_HANDS_DRAFT") == "1"
  ios_publish_run = ios_draft_job.fetch("steps").find { |step| step["id"] == "publish-hands-release" }.fetch("run")
  raise "iOS CI does not force a draft" unless ios_publish_run.include?("--draft") && !ios_publish_run.include?("--publish")

  # The update-QA build pair must stay debug-channel release builds that never
  # create a Hands draft.
  update_jobs = update_workflow.fetch("jobs")
  %w[baseline target].each do |name|
    call = update_jobs.fetch(name)
    raise "#{name} can create a Hands draft" unless call.fetch("with").fetch("create_hands_draft") == false
    raise "#{name} is not release variant" unless call.fetch("with").fetch("android_variant") == "release"
    raise "#{name} is not debug carrier" unless call.fetch("with").fetch("hands_channel") == "debug"
  end

  release_steps = jobs.fetch("build_android_release").fetch("steps")
  stage_index = release_steps.index { |step| step["name"] == "Stage workflow-owned release implementations" }
  product_index = release_steps.index { |step| step["name"] == "Checkout immutable product source" }
  restore_index = release_steps.index { |step| step["name"] == "Restore workflow-owned release implementations" }
  verify_index = release_steps.index { |step| step["name"] == "Verify APK" }
  raise "release workflow staging order is unsafe" unless stage_index && product_index && restore_index && verify_index &&
    stage_index < product_index && product_index < restore_index && restore_index < verify_index
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
  # Play builds upload too (Hands holds the AAB for Play promotion); only the
  # Hands draft job stays Play-free, checked above.
  raise "Hands upload step is not controlled by the resolved upload policy" unless hands_upload_if.include?("upload_android_artifacts")
  raise "Hands upload step skips Play builds" if hands_upload_if.include?("play")
  artifact_step = release_steps.find { |step| step["name"] == "Upload signed AAB and verification receipt" }
  raise "signed AAB artifact is not controlled by the resolved upload policy" unless artifact_step.fetch("if").to_s.include?("needs.resolve.outputs.upload_android_artifacts")
  # Every non-debug build keeps its signed AAB (Hands release builds: the backup bundle
  # github-release.yml attaches), bound to the run APK by build-identity.json.
  non_debug_gate = "needs.resolve.outputs.android_variant != " + 39.chr + "debug" + 39.chr
  %w[locate-aab sign-aab].each do |id|
    aab_step = release_steps.find { |step| step["id"] == id }
    raise "#{id} does not run for every non-debug build" unless aab_step && aab_step.fetch("if").to_s.include?(non_debug_gate)
  end
  raise "signed AAB artifact is not kept for every non-debug build" unless artifact_step.fetch("if").to_s.include?(non_debug_gate)
  raise "signed AAB artifact must be retained 90 days" unless artifact_step.fetch("with").fetch("retention-days") == 90
  identity_index = release_steps.index { |step| step["name"] == "Record AAB build identity" }
  raise "signed AAB lacks the build identity github-release.yml verifies" unless identity_index && identity_index > aab_sign_index &&
    release_steps.fetch(identity_index).fetch("run").include?("signed-aab/build-identity.json")
  raise "uploader bypasses signed AAB" unless upload_env.fetch("AAB_PATH") == "${{ steps.sign-aab.outputs.aab_path }}"
  raise "upload pin is not derived from verified AAB" unless upload_env.fetch("UPLOAD_KEY_CERT_SHA256") == "${{ steps.sign-aab.outputs.upload_key_cert_sha256 }}"
  raise "repair dispatch can schedule normal build" unless resolve_job.fetch("if").include?("inputs.aab_signing_repair == " + 39.chr + 39.chr)
  repair = jobs.fetch("sign_existing_aab")
  raise "repair is not dispatch-only" unless repair.fetch("if").include?("github.event_name == " + 39.chr + "workflow_dispatch" + 39.chr)
  repair_runs = repair.fetch("steps").map { |step| step.fetch("run", "") }.join("\n")
  raise "repair runs a build or publishes" if repair_runs.match?(/gradlew|upload_android_hands_artifacts|hands publish/)

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

  android_gradle = File.read("compose/androidApp/build.gradle.kts")
  raise "Play carrier leaves Hands APK updates enabled" unless android_gradle.include?(%q{buildConfigField("Boolean", "HANDS_APK_UPDATE_ENABLED", "false")})
  play_manifest = File.read("compose/androidApp/src/play/AndroidManifest.xml")
  raise "Play manifest does not remove REQUEST_INSTALL_PACKAGES" unless play_manifest.include?(%q{android.permission.REQUEST_INSTALL_PACKAGES}) && play_manifest.include?(%q{tools:node="remove"})
  main_manifest = File.read("compose/androidApp/src/main/AndroidManifest.xml")
  raise "Hands source manifest no longer declares REQUEST_INSTALL_PACKAGES" unless main_manifest.include?(%q{android.permission.REQUEST_INSTALL_PACKAGES})
  raise "Hands release manifest must retain the source permission" if File.exist?("compose/androidApp/src/release/AndroidManifest.xml") && File.read("compose/androidApp/src/release/AndroidManifest.xml").include?(%q{tools:node="remove"})
  raise "release resolver does not receive artifact-upload input" unless resolve_env.fetch("INPUT_UPLOAD_ANDROID_ARTIFACTS") == "${{ inputs.upload_android_artifacts }}"

  publish_script = File.read("scripts/publish_android_hands_release.sh")
  raise "Hands publisher can route the Play carrier" unless publish_script.include?("the Hands publisher cannot publish the Play carrier")

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
'

if grep -R -Eq 'publish_hands' docs; then
  fail "release documentation still references the removed publish_hands input"
fi
if grep -R -Eq --include='*.sh' --exclude='test_*' 'publish_hands' scripts; then
  fail "production scripts still reference the removed publish_hands input"
fi

python3 scripts/test_upload_android_hands_artifacts.py

echo "Android Hands release workflow contract: PASS"
