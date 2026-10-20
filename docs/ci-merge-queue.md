# Mobile CI merge queue

The source workflow and the live repository ruleset are separate changes. The
workflow must land and report a successful GitHub Actions `CI gate` check before
ruleset `18537125` is changed. Do not enable the ruleset from a pull-request
branch or from an unreviewed payload.

## Execution tiers

- A pull request with no Test-relevant paths reports `CI gate` after the plan
  and contract job only.
- An ordinary product pull request runs the Android compile/unit tier.
- A `full-ci` label runs the deterministic full tier and emulator/connected
  jobs before queue admission.
- A `merge_group` runs Android and iOS. Emulator/connected jobs remain excluded until their
  bounded infrastructure-abort retry contract has landed and has fresh flake
  evidence.
- `main`, `release-*`, scheduled, and manual Test runs execute every tier.

`CI gate` is the only Test workflow context intended to become required. Its
ruleset entry is bound to GitHub Actions app id `15368` so a same-named commit
status from another integration cannot satisfy it.

## Guarded enablement

The reviewed plan is `.github/rulesets/mobile-main-merge-queue.json`. Before
any mutation, fetch the live ruleset and compare only the API fields represented
by `expected_live_before`:

```bash
set -euo pipefail
plan=.github/rulesets/mobile-main-merge-queue.json
live=$(mktemp)
actual=$(mktemp)
expected=$(mktemp)
gh api repos/botiverse/mobile/rulesets/18537125 > "$live"
jq -S '{name,target,enforcement,bypass_actors,conditions,rules}' "$live" > "$actual"
jq -S '.expected_live_before' "$plan" > "$expected"
cmp "$expected" "$actual"
```

Abort on any mismatch. Confirm that the landed main commit has a successful
`CI gate` check whose app id is `15368`, then create the escape-hatch label from
the exact checked-in metadata if it is still absent. Extract the desired API
payload and update the ruleset only under a separate exact authorization:

```bash
set -euo pipefail
plan=.github/rulesets/mobile-main-merge-queue.json
payload=$(mktemp)
jq '.desired_payload' "$plan" > "$payload"
gh api --method PUT repos/botiverse/mobile/rulesets/18537125 --input "$payload"
```

Immediately read the ruleset back, project the same fields, and compare them to
`desired_payload`. The prior `expected_live_before` object is the exact rollback
payload. Do not add emulator contexts to `required_status_checks`; the stable
aggregate owns tier admission.

The first live canary must be a non-product pull request added normally to the
queue. It must produce a `merge_group` Test run, select
`merge-group-deterministic-full`, pass `CI gate`, squash exactly one pull
request, and leave a successful full readback on `main`. A missing check,
unexpected job, base drift, or queue timeout is a rollback condition.
