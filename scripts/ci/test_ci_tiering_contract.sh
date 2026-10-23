#!/usr/bin/env bash

set -euo pipefail

scripts/ci/plan_test_tier.sh --self-test
node scripts/ci/verify_test_tier_results.mjs --self-test
bash -n scripts/ci/plan_test_tier.sh scripts/ci/test_ci_tiering_contract.sh

ruby -ryaml <<'RUBY'
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
raise "stable required context changed" unless jobs.dig("ci-gate", "name") == "CI gate"
raise "aggregate must survive failed/skipped dependencies" unless jobs.dig("ci-gate", "if").include?("always()")
raise "aggregate does not execute checked-in verifier" unless jobs.dig("ci-gate", "steps").any? { |step| step["run"]&.include?("verify_test_tier_results.mjs") }

plan_job = jobs.fetch("plan")
raise "plan job permissions are not the GraphQL PR-comment set" unless plan_job.fetch("permissions") == {
  "contents" => "read",
  "pull-requests" => "write",
}
plan_steps = plan_job.fetch("steps")
raise "gitlink gate is not wired to exact PR base/head" unless plan_steps.any? do |step|
  run = step["run"].to_s
  run.include?("gitlink_carry_in_gate.py") && run.include?('--base "$BASE_SHA"') && run.include?('--head "$HEAD_SHA"')
end

plan_script = plan_steps.find { |step| step["id"] == "plan" }.fetch("run")
raise "PR diff does not use merge-base semantics" unless plan_script.include?('write_changed_paths "$BASE_SHA" "$HEAD_SHA" merge-base')
raise "main push diff is not directly path-scoped" unless plan_script.include?('write_changed_paths "$PUSH_BEFORE_SHA" "$PUSH_HEAD_SHA" direct')
raise "renames can hide the removed platform path" unless plan_script.include?("--no-renames")
raise "unavailable diffs do not fail closed" unless plan_script.include?("__CI_CHANGED_PATHS_UNKNOWN__")

# workflow_dispatch scope=full must reach the planner.
raise "dispatch scope input is not wired to the planner" unless plan_script.include?('"$DISPATCH_SCOPE"')

puts "CI tiering workflow contract: PASS"
RUBY
