# Commit Sign-off Policy

> **Superseded (2026-10-09).** Agent Git identity, push, review and merge now go
> through Stamp; see "Agent Git identity comes from Stamp" and "Protected `main`"
> in `AGENTS.md`. The `@mail.build` sign-off rule and the guarded merge wrappers
> below are kept for history only.

> **Status (2026-09-19):** the `Commit Sign-off` CI check, the `commit-msg` git
> hook, `scripts/check-commit-signoff.sh`, `scripts/check-landed-commit-signoff.sh`
> and the attestation ledger were removed on artin's decision, and the guarded
> merge wrappers no longer inspect the signer (they still reject escaped newlines
> and empty subjects). The trailer stays the convention for agent commits.
> Commands below that call the removed scripts are historical.

Raft Mobile agent commits should carry a `Signed-off-by:` trailer with a `@mail.build` identity.

## Commit Identity

Agents should use the `raft-mobile-` mail.build prefix:

```bash
raft-mobile-cindy@mail.build
```

Use your own agent or human name in the address. Ask @Gogo in Raft if you do not have a mail.build identity yet.

Do not rely on repo-local `git config user.name/user.email` in a shared checkout like `/Users/artin/AndroidStudioProjects/Slock`. Multiple agents commit there, so the repo-local identity can be overwritten by the previous committer.

Use per-command identity for each commit:

```bash
git \
  -c user.name="Cindy" \
  -c user.email="raft-mobile-cindy@mail.build" \
  commit -s -m "fix(scope): describe the change"
```

In an isolated private clone or worktree, setting local Git identity is also acceptable:

```bash
git config user.name "Cindy"
git config user.email "raft-mobile-cindy@mail.build"
```

## Create Signed Commits

Use `git commit -s` for every new commit. In the shared main checkout, include the per-command identity:

```bash
git \
  -c user.name="Cindy" \
  -c user.email="raft-mobile-cindy@mail.build" \
  commit -s -m "fix(scope): describe the change"
```

To add a sign-off to the previous commit:

```bash
git \
  -c user.name="Cindy" \
  -c user.email="raft-mobile-cindy@mail.build" \
  commit --amend -s --no-edit
```

The required trailer looks like:

```text
Signed-off-by: Cindy <raft-mobile-cindy@mail.build>
```

## Install Local Hook

Run this once per clone or worktree:

```bash
scripts/install_git_hooks.sh
```

The hook and CI use `git interpret-trailers` through the repository validator.
Each commit must contain exactly one well-formed `Signed-off-by:` trailer with a
`@mail.build` email. Literal `\\n` or `\\r` bytes, missing trailers, duplicate
trailers, malformed trailers, and other email domains fail closed.

## Immutable Shipped History

Do not rewrite an already shipped or protected branch solely to repair a squash
or merge commit whose reviewed source head had a valid sign-off but whose
immutable GitHub-generated commit lost that trailer. Such a commit may use the
narrow forward-attestation ledger in `scripts/commit-signoff-attestations.tsv`.

Each exception is exact-bound to one full immutable commit SHA, one full signed
reviewed-source SHA, and that source commit's exact `@mail.build` signer. The
checker requires the source SHA to identify an exact commit object (not a tag
or other peelable object) and verifies the exact trailer. In the legacy
three-field format, a single-parent squash must be tree-identical to its source.
A legacy two-parent merge must name its reviewed second parent as the source,
`git merge-tree --write-tree` must prove the parents merge cleanly, and the
immutable merge tree must equal that clean synthetic tree. New two-parent
recovery entries use the strict nine-field format: immutable merge, reviewed
signed head, exact signer, reviewed source parent, immutable first parent,
immutable tree, changed-path count, insertions, and deletions. The checker also
requires every reviewed source commit to be signed, the declared source parent
to be the exact merge-base, exact path/status and per-path numstat equality,
stable patch-id and normalized diff equality, exact changed blobs, and clean
synthetic merge identity. Conflict resolutions, extra merge-only changes,
octopus merges, reversed/wrong source parents, short SHAs, wildcards,
duplicates, missing source objects, signer mismatches, and tree mismatches fail
closed. This mechanism is not an alternative to `git commit -s` for new work;
ledger additions require independent review and a signed commit.

The ledger is considered only when an immutable commit genuinely has no
`Signed-off-by` text. It cannot waive or hide a malformed, duplicate,
wrong-domain, or escaped-newline trailer. Those messages remain invalid even if
an older ledger row names the commit.

For a squash created after the base branch advanced, the ledger uses five
fields: immutable squash, reviewed signed head, exact signer, reviewed source
parent, and immutable parent. The checker requires every commit in the reviewed
source range to have a real `@mail.build` sign-off; the declared source parent
to be the exact merge-base; path/status and per-path add/delete sets to match;
zero-context stable patch-id and normalized diff SHA-256 to match; and
`git merge-tree --write-tree` of immutable parent plus reviewed head to be clean
and exactly equal the immutable squash tree. Any mismatch fails closed.

## Guarded Merge

Fully DCO-signed pull requests default to guarded rebase merge. Rebase keeps
each already validated source commit and its real trailer bytes:

```bash
scripts/guarded-rebase-merge.sh \
  botiverse/mobile <pr-number> <reviewed-full-sha>
```

The shared preflight fresh-reads the repository policy and pull request, checks
that the reviewed full SHA is still the live non-draft head, fetches every
source commit message, and requires each message to pass the exact trailer
validator before invoking the merge API. It also requires rebase and guarded
squash modes to be enabled and ordinary merge commits to be disabled.

Use squash only when the delivery contract specifically requires one landed
commit. Prepare the complete final commit message as an LF-only file (or pass it
on stdin with `-`), including subject, blank line, body, and the single trailer:

```bash
scripts/guarded-squash-merge.sh \
  botiverse/mobile <pr-number> <reviewed-full-sha> <message-file>
```

The squash wrapper validates the exact complete candidate with the strict PR
validator, then validates the exact reconstructed landing message with the
parsed-footer-only landing rule before splitting it into `--subject` and
`--body-file`; it never creates or validates a separate surrogate string. The
shared preflight still applies the strict source-commit validator to every
reviewed source commit. Do not use
`gh pr merge --squash --body ...`: shell quoting and GitHub CLI preserve a
visual `\\n` as literal bytes, so `\\n\\nSigned-off-by:` is not a Git trailer.
The repository policy scanner rejects inline squash bodies and any ordinary
`--merge` command in production scripts and workflows.

### Landing-side receipt and DCO scope

The PR/source validator intentionally remains strict: it requires both one
whole-message `Signed-off-by:` line and one parsed Git trailer. A squash landing
may legitimately contain historical sign-offs in the concatenated body, so the
push-side `scripts/check-landed-commit-signoff.sh` uses the separate
`scripts/validate-landed-commit-message.sh` validator. That landing validator
checks only the parsed trailer footer, requiring exactly one valid
`@mail.build` signer; an optional `Raft-Guarded-Squash:` marker does not affect
that count. Historical examples `7ffc900e` (whole-message 14, parsed 1) and
`ed60e3e6` (whole-message 4, parsed 1) demonstrate why the two validator scopes
must remain separate.

After a guarded squash returns, `scripts/guarded-squash-merge.sh` reads the
actual PR `mergeCommit` and its landed `%B`-equivalent raw commit message via
the GitHub API. Its receipt records the declared reviewed head, the PR's
observed head, the reviewed and landed tree SHAs, the actual landed commit SHA,
the reviewed-head merge base sampled before merge, the landed commit's first
parent (base at merge),
the reviewed-head `Signed-off-by` line, and SHA-256 values for the candidate and
landed messages. It also emits a byte comparison against the candidate message;
identical and different results are both recorded. The copied signer is
self-contained evidence, while the commit/tree SHAs remain its verification
anchors; neither substitutes for the other or for the preflight's validation
of every source commit. The receipt also records the landed signer after the
landing validator passes, plus `mergeable`, `mergeStateStatus`, and a
`mergeOutcome` classification. It records `tool_version` and `spec_version` so
future readers can interpret the fields against the exact wrapper contract
that produced them. The values are non-empty and format-bound
(`guarded-squash-merge.vN` and `guarded-squash-receipt.vN`); comments in a later
checkout are not a historical version anchor. Receipt paths are prepared and
probed before the merge, then written through a temporary file and atomic
rename.

When the reviewed and landed trees differ, the receipt classifies the result as
`EXPECTED_BASE_ADVANCED` when `baseAtMerge != baseAtReview`,
`SUSPICIOUS_BASE_UNCHANGED` when they are equal, or `UNRESOLVED_BASE` when the
first parent is unavailable. This is an audit interpretation rather than an
automatic rollback; exact PR-head identity remains the
`prHead == reviewedHead` check. An `UNRESOLVED_BASE` or
`SUSPICIOUS_BASE_UNCHANGED` result is not silently treated as pass: the receipt
marks `review=REQUIRED owner=independent-exact-head-reviewer`.

It then runs the landing validator. A post-merge failure is an alert/receipt
(and a non-zero wrapper result), not an automatic rollback: the commit already
exists on protected history and cannot be rewritten safely. The response is to
retain the receipt/diff, trace the reviewed PR head's signer evidence, and, if
needed, add a separate follow-up explanation. Use the immutable attestation ledger only when its existing
eligibility and independent-review requirements are satisfied; it cannot mask
duplicate or malformed trailers. Do not claim that a later commit repaired the
original trailer, and do not permanently block future merges on repairing an
immutable historical commit. Set
`GUARDED_MERGE_RECEIPT_FILE` when a durable text receipt is required. Store that
file outside any branch whose history may be rewritten. Do not remove the
external signer copy for a branch until that branch has an independently
verified non-rewrite guarantee; default-branch protection does not imply that
release branches have the same guarantee. Verify the current state rather than
relying on an old observation. For repository-sourced rulesets, use the
aggregate branch-rules endpoint, then inspect every ruleset that supplies
`non_fast_forward`:

```bash
gh api repos/<owner>/<repo>/rules/branches/<branch> \
  --jq '.[] | select(.type=="non_fast_forward") | {ruleset_id, ruleset_source_type}'
gh api repos/<owner>/<repo>/rulesets/<ruleset-id> \
  --jq '{enforcement, bypass_actors}'
```

Treat history as non-rewritable only when at least one applicable
`non_fast_forward` ruleset has `enforcement == "active"` and an empty
`bypass_actors` list. Do not use `enforcement != "disabled"`: GitHub's
`evaluate` mode records without blocking and would create a dangerous false
positive. When multiple rulesets overlap, inspect all of them; selecting only
the first is conservative (it can create a false negative), not a proof of
the guarantee.
If the source type is Organization, use the corresponding organization
ruleset surface instead. This check proves the current rule state, not past or
continuous history.

The post-merge PR readback also has an explicit one-way-safe state machine. A
`MERGED` result requires `state == MERGED` and a non-empty `mergeCommit`; a
non-zero merge CLI exit with those same facts is recorded as
`MERGED_WITH_CLI_ERROR`, not as an invitation to merge again. `NOT_MERGED` is
allowed only when affirmative current evidence exists: `state == CLOSED` with
`mergedAt == null` and no merge commit, or `mergeable == CONFLICTING` with no
merge commit. Every other unmerged-looking readback—including `OPEN + CLEAN`,
`OPEN + BLOCKED` with a non-required/red check, `UNKNOWN`, and an unavailable
readback—is `INDETERMINATE`. The wrapper stops and forbids an automatic retry;
it never turns absence of a merge commit into a negative conclusion.

That blocking-evidence list is intentionally scoped to the current mobile
ruleset, not copied from a generic GitHub recipe. Re-check the exact source
before changing the list (and whenever task #453 changes repository gates):

```bash
gh api repos/botiverse/mobile/rulesets/18537125 --jq '[.rules[].type]'
gh api repos/botiverse/mobile/rulesets/18537125 \
  --jq '.rules[] | select(.type == "pull_request") | .parameters'
```

The current observed ruleset has no required status checks and requires zero
approving reviews, so a red check, missing approval, or unresolved comment is
not affirmative evidence that a PR cannot merge. The receipt records the
readback fields and the selected state, keeping this scope decision visible
and re-auditable.

GitHub's `mergeable` value may be the explicit transient `UNKNOWN` (a current
spot check found it on 11 of 16 open PRs in this repository). Therefore a large
number of `INDETERMINATE` receipts is expected, not evidence that the predicate
is broken. The wrapper performs one authoritative
readback: `UNKNOWN` is classified as `INDETERMINATE`, never as conflict
evidence. A later operator may re-read GitHub to obtain a newly computed value,
but repeated reads are not part of this one-way-safe state machine and a value
that remains `UNKNOWN` cannot be upgraded by the number of attempts. Re-checking
may obtain a value; it may not manufacture a conclusion.

The landing validator deliberately has a different input contract from the PR
validator: whole-message `Signed-off-by == 1` is valid for one source commit,
but is not a meaningful predicate for a concatenated squash message. The
landing rule therefore removes that inapplicable predicate rather than trading
known detection for permissiveness; parsed-footer `0` and `2` remain rejected.

The wrapper's preflight and post-merge receipt cover only callers that use the
wrapper. `scripts/check-merge-command-policy.sh` prevents repository-owned
automation from adding a direct `pr merge` invocation (including an indirect
command variable), but a human can still type that command interactively.
Enforcing the merge method for that path requires a
GitHub ruleset/repository-setting change tracked separately; this repository
change does not claim to close that external gate.

The resulting coverage is intentionally explicit:

| Path | Merge-before check | Merge-after receipt | Covered here |
| --- | --- | --- | --- |
| Guarded wrapper | Yes | Yes | Yes |
| Repository-owned automation bypass | Policy scan rejects it | None | Yes (static source only) |
| Interactive merge command | No | No | Detection only (CI on push); prevention requires ruleset |

Do not use `gh pr merge --merge` or the GitHub Merge button. Ordinary merge
commits remain disabled unless a future repository-owned path can construct and
validate a signed merge commit before writing the protected branch. Changing
GitHub repository settings is a separate owner/admin operation after the
source exact, independent review, and Hosted checks pass; these scripts do not
mutate settings.

## PR-only Main Integration

GitHub `main` is protected. A valid signed commit is necessary but not enough
to publish code: new mobile work must go through a GitHub PR to `main`.

Use this flow:

1. Branch from current `origin/main`.
2. Commit with `git commit -s` and the correct `@mail.build` identity.
3. Validate the touched area locally.
4. Run `scripts/check-commit-signoff.sh <base> HEAD` for the PR range.
5. Push the topic branch and open a PR.
6. Wait for the required GitHub checks and exact-bound independent review.
7. Use `scripts/guarded-rebase-merge.sh` with the reviewed full head SHA by
   default. Use guarded squash only with an explicitly reviewed complete
   message file when the delivery contract requires one landed commit.

Do not directly merge finished work into a shared local `main` as the delivery
step, and do not run `git push origin main`. If local `main` is already ahead,
move the non-equivalent commits to PR branches and clean local `main` only
after confirming there are no unrelated dirty files.
