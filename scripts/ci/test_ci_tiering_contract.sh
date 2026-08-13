#!/usr/bin/env bash

set -euo pipefail

scripts/ci/plan_test_tier.sh --self-test
node scripts/ci/verify_test_tier_results.mjs --self-test
bash -n scripts/ci/plan_test_tier.sh scripts/ci/test_ci_tiering_contract.sh

ruby -ryaml -rjson <<'RUBY'
workflow = YAML.load_file(".github/workflows/test.yml")
events = workflow.fetch(true)
pull_request = events.fetch("pull_request")
raise "pull_request path filters would suppress the stable CI gate" if pull_request.key?("paths")
types = pull_request.fetch("types")
%w[opened reopened synchronize ready_for_review labeled unlabeled].each do |type|
  raise "pull_request trigger misses #{type}" unless types.include?(type)
end
raise "merge_group checks_requested trigger missing" unless events.dig("merge_group", "types") == ["checks_requested"]
branches = events.dig("push", "branches")
raise "main path-scoped readback trigger missing" unless branches.include?("main")
raise "release full readback trigger missing" unless branches.include?("release-*")
raise "push path filters would suppress the stable CI gate" if events.fetch("push").key?("paths")

jobs = workflow.fetch("jobs")
expected_jobs = %w[
  plan kuikly-artifacts fork-deps android android-device
  android-minimum-supported-attachment-runtime android-canonical-final-actual-host
  ios ohos-receipt-selftest ohos ohos-host ci-gate
]
raise "Test workflow job set drifted" unless jobs.keys == expected_jobs
raise "stable required context changed" unless jobs.dig("ci-gate", "name") == "CI gate"
raise "aggregate must survive failed/skipped dependencies" unless jobs.dig("ci-gate", "if").include?("always()")
raise "aggregate does not execute checked-in verifier" unless jobs.dig("ci-gate", "steps").any? { |step| step["run"]&.include?("verify_test_tier_results.mjs") }

plan_job = jobs.fetch("plan")
raise "plan job must have read-only source access" unless plan_job.dig("permissions", "contents") == "read"
raise "plan job cannot publish carry-in artifacts to PRs" unless plan_job.dig("permissions", "pull-requests") == "write"
plan_steps = plan_job.fetch("steps")
raise "gitlink gate fixture suite is not hosted" unless plan_steps.any? { |step| step["run"]&.include?("test_gitlink_carry_in_gate.py") }
raise "gitlink gate is not wired to exact PR base/head" unless plan_steps.any? do |step|
  run = step["run"].to_s
  run.include?("gitlink_carry_in_gate.py") && run.include?('--base "$BASE_SHA"') && run.include?('--head "$HEAD_SHA"')
end
publish_step = plan_steps.find { |step| step.fetch("name", "").include?("Publish gitlink carry-in audit") }
raise "gitlink audit is not published into the PR" unless publish_step
raise "gitlink publication must survive an audit RED" unless publish_step.fetch("if", "").include?("always()")
raise "gitlink publication regressed to unreliable PR file patches" if publish_step.fetch("run", "").include?(".patch")

expected_needs = {
  "kuikly-artifacts" => ["plan"],
  "fork-deps" => ["plan"],
  "android" => ["plan", "kuikly-artifacts"],
  "android-device" => ["plan", "android"],
  "android-minimum-supported-attachment-runtime" => ["plan", "android"],
  "android-canonical-final-actual-host" => ["plan", "android"],
  "ios" => ["plan", "kuikly-artifacts"],
  "ohos-receipt-selftest" => ["plan"],
  "ohos" => ["plan", "kuikly-artifacts", "fork-deps"],
  "ohos-host" => ["plan", "kuikly-artifacts", "fork-deps"],
}
expected_needs.each do |job, needs|
  raise "#{job} needs drifted" unless Array(jobs.dig(job, "needs")) == needs
end
raise "Kuikly manifest gate must follow every Kuikly consumer platform" unless
  jobs.dig("kuikly-artifacts", "if").include?("run_android") &&
  jobs.dig("kuikly-artifacts", "if").include?("run_ios") &&
  jobs.dig("kuikly-artifacts", "if").include?("run_ohos")
raise "Android compile is not Android-path gated" unless jobs.dig("android", "if").include?("run_android")
raise "iOS compile is not iOS-path gated" unless jobs.dig("ios", "if").include?("run_ios")
%w[fork-deps ohos-receipt-selftest ohos ohos-host].each do |job|
  raise "#{job} is not OHOS-path gated" unless jobs.dig(job, "if").include?("run_ohos")
end
%w[android-device android-minimum-supported-attachment-runtime android-canonical-final-actual-host].each do |job|
  raise "#{job} is not emulator-tier gated" unless jobs.dig(job, "if").include?("run_emulators")
end
kuikly_steps = jobs.fetch("kuikly-artifacts").fetch("steps")
raise "Kuikly manifest admission gate is not hosted" unless kuikly_steps.any? do |step|
  step["run"]&.include?("verify_kuikly_release_manifest.sh")
end
raise "Kuikly gate regressed to gitlink/source publication" if kuikly_steps.any? do |step|
  run = step["run"].to_s
  run.include?("third_party/kuikly-ui") || run.include?("build_kuikly_fork.sh") || run.include?("mavenLocal")
end

plan_outputs = jobs.fetch("plan").fetch("outputs").keys
raise "platform plan outputs drifted" unless plan_outputs == %w[run_android run_ios run_ohos run_emulators reason]
plan_script = jobs.fetch("plan").fetch("steps").find { |step| step["id"] == "plan" }.fetch("run")
raise "PR diff does not use merge-base semantics" unless plan_script.include?('write_changed_paths "$BASE_SHA" "$HEAD_SHA" merge-base')
raise "main push diff is not directly path-scoped" unless plan_script.include?('write_changed_paths "$PUSH_BEFORE_SHA" "$PUSH_HEAD_SHA" direct')
raise "renames can hide the removed platform path" unless plan_script.include?("--no-renames")
raise "unavailable diffs do not fail closed" unless plan_script.include?("__CI_CHANGED_PATHS_UNKNOWN__")

plan = JSON.parse(File.read(".github/rulesets/mobile-main-merge-queue.json"))
raise "ruleset plan schema drifted" unless plan.fetch("schema") == "botiverse-mobile-merge-queue-plan/v1"
raise "wrong ruleset target" unless plan.fetch("repository") == "botiverse/mobile" && plan.fetch("ruleset_id") == 18_537_125
raise "full-ci label contract drifted" unless plan.fetch("full_ci_label") == {
  "name" => "full-ci",
  "color" => "5319E7",
  "description" => "Run the full Mobile CI matrix before merge queue admission",
}
before = plan.fetch("expected_live_before")
desired = plan.fetch("desired_payload")
raise "ruleset identity changed" unless desired.values_at("name", "target", "enforcement", "conditions", "bypass_actors") == before.values_at("name", "target", "enforcement", "conditions", "bypass_actors")
before_types = before.fetch("rules").map { |rule| rule.fetch("type") }
desired_types = desired.fetch("rules").map { |rule| rule.fetch("type") }
raise "live ruleset baseline drifted" unless before_types == %w[deletion non_fast_forward pull_request]
raise "desired ruleset delta is not exact" unless desired_types == %w[deletion non_fast_forward pull_request required_status_checks merge_queue]
raise "existing PR policy changed" unless desired.fetch("rules").first(3) == before.fetch("rules")
checks = desired.fetch("rules").find { |rule| rule["type"] == "required_status_checks" }.fetch("parameters")
raise "CI gate context/app drifted" unless checks.fetch("required_status_checks") == [{ "context" => "CI gate", "integration_id" => 15_368 }]
raise "merge queue should replace strict branch freshness" unless checks.fetch("strict_required_status_checks_policy") == false
queue = desired.fetch("rules").find { |rule| rule["type"] == "merge_queue" }.fetch("parameters")
raise "queue must start fail-closed" unless queue.fetch("grouping_strategy") == "ALLGREEN"
raise "queue must squash" unless queue.fetch("merge_method") == "SQUASH"
raise "queue must start one-build/one-merge" unless queue.fetch("max_entries_to_build") == 1 && queue.fetch("max_entries_to_merge") == 1
raise "queue timeout is shorter than the deterministic matrix" unless queue.fetch("check_response_timeout_minutes") >= 120
puts "CI tiering workflow/ruleset contract: PASS"
RUBY
