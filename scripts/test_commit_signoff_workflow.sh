#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="${repo_root}/.github/workflows/commit-signoff.yml"

ruby - "$workflow" <<'RUBY'
require "yaml"

workflow = YAML.load_file(ARGV.fetch(0))
events = workflow.fetch(true)
push_branches = events.fetch("push").fetch("branches")
raise "push validation must cover main" unless push_branches.include?("main")
raise "push validation must cover release-*" unless push_branches.include?("release-*")

steps = workflow.fetch("jobs").fetch("signoff").fetch("steps")
tests = steps.find { |step| step["name"] == "Test sign-off policy" }
raise "missing sign-off policy self-test step" unless tests
test_run = tests.fetch("run")
raise "strict PR validator tests are not wired" unless test_run.include?("scripts/test_validate_commit_message.sh")
raise "landing validator tests are not wired" unless test_run.include?("scripts/test_validate_landed_commit_message.sh")

pr_step = steps.find { |step| step["name"] == "Check PR/source sign-off" }
raise "missing PR/source sign-off step" unless pr_step
raise "PR/source step is not excluded from push" unless pr_step.fetch("if") == "github.event_name != 'push'"
unless pr_step.fetch("run").start_with?("scripts/check-commit-signoff.sh ")
  raise "PR/source step does not use the strict checker"
end

landing_step = steps.find { |step| step["name"] == "Check landed sign-off" }
raise "missing landed sign-off step" unless landing_step
raise "landing step is not push-only" unless landing_step.fetch("if") == "github.event_name == 'push'"
unless landing_step.fetch("run").start_with?("scripts/check-landed-commit-signoff.sh ")
  raise "push step does not use the landing-only checker"
end
RUBY

echo "Commit Sign-off workflow event routing tests passed."
