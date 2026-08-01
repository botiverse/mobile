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
raise "main full readback trigger missing" unless branches.include?("main")
raise "release full readback trigger missing" unless branches.include?("release-*")
raise "push path filters would suppress release readback" if events.fetch("push").key?("paths")

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

expected_needs = {
  "kuikly-artifacts" => ["plan"],
  "fork-deps" => ["plan"],
  "android" => ["plan", "kuikly-artifacts"],
  "android-device" => ["plan", "android"],
  "android-minimum-supported-attachment-runtime" => ["plan", "android"],
  "android-canonical-final-actual-host" => ["plan", "android"],
  "ios" => ["plan"],
  "ohos-receipt-selftest" => ["plan"],
  "ohos" => ["plan", "kuikly-artifacts", "fork-deps"],
  "ohos-host" => ["plan", "kuikly-artifacts", "fork-deps"],
}
expected_needs.each do |job, needs|
  raise "#{job} needs drifted" unless Array(jobs.dig(job, "needs")) == needs
end
%w[kuikly-artifacts android ohos-receipt-selftest].each do |job|
  raise "#{job} is not basic-tier gated" unless jobs.dig(job, "if").include?("run_basic")
end
%w[fork-deps ios ohos ohos-host].each do |job|
  raise "#{job} is not full-tier gated" unless jobs.dig(job, "if").include?("run_full")
end
%w[android-device android-minimum-supported-attachment-runtime android-canonical-final-actual-host].each do |job|
  raise "#{job} is not emulator-tier gated" unless jobs.dig(job, "if").include?("run_emulators")
end
ohos_fork_steps = jobs.fetch("kuikly-artifacts").fetch("steps").select do |step|
  step.fetch("name", "").include?("OpenHarmony") || step.fetch("name", "").include?("ohos flavor")
end
raise "OHOS fork work disappeared from the full tier" if ohos_fork_steps.empty?
raise "basic PR tier still performs OHOS fork work" unless ohos_fork_steps.all? { |step| step.fetch("if", "").include?("run_full") }

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
