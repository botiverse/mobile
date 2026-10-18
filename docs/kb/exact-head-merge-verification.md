# Verify the exact head before merging — not "the PR"

Owner: peng · Verified at: 2026-10-18 · Status: current

Merging "the PR" means merging whatever happens to be at the branch tip *right
now*. A reviewer approved an earlier commit, or a force-push landed between
review and merge — either way, "merged" no longer means "what was approved".
Exact-head verification pins three SHAs and proves they all say the same thing
before anything merges.

## Two SHAs people confuse, and why it matters

- **Approved head SHA** — the commit the reviewer actually looked at and CI
  ran on. This is the only commit whose review is valid.
- **Merge commit SHA** — the new squash/merge commit GitHub creates at merge
  time. It is not the approved head; its tree should *equal* the approved
  head's tree, but it is a different object.

"Did it merge the right thing?" is answered by comparing these two, not by
checking `merged=true` alone.

## The verification sequence

### 1. Read the approved head from the verdict

```bash
stamp pr view <N> --repo botiverse/mobile
# → "Review: approved at head <sha> by <name>"
# → "Stamp verdict: READY — can land now @ head <sha> base <sha>"
```

### 2. Independently fetch the live tip

Never trust the rendered PR page — it can lag or show an older ref. Check both
remotes yourself:

```bash
git ls-remote ssh://git@ssh.stamp.build:443/botiverse/mobile.git \
  refs/heads/agent/<branch>

gh api repos/botiverse/mobile/pulls/<N> --jq '.head.sha'
```

All three (verdict head, Stamp remote, GitHub head.sha) must return the same
SHA. If they differ, someone pushed — stop.

### 3. If the tip moved, see what moved

```bash
git fetch origin
git log --oneline <approved-sha>..origin/agent/<branch>
```

Non-empty = stale approval. Do not merge; ask the author what changed.

### 4. Merge with pinned SHAs

```bash
stamp pr merge <N> --repo botiverse/mobile \
  --auto \
  --head <verified-sha> \
  --base <base-sha>
```

Stamp refuses if the head moved after approval. That refusal is the guard
working, not a problem to route around.

### 5. After merge, check the tree, not the flag

`merged=true` tells you a merge commit exists. To check it is *the right* code:

```bash
gh api repos/botiverse/mobile/pulls/<N> \
  --jq '{merged, merge_commit_sha}'
git fetch origin
git diff <verified-sha>..<merge-commit-sha> --stat
# Should show only files the PR touched — nothing else.
```

## A real failure mode: closed/merged=false ≠ code absent

Stamp queues some merges by pushing directly to `staging`, not through GitHub's
merge button. In that path the PR page shows `closed` / `merged=false` — the
PR object itself was never "merged" from GitHub's point of view, **but the code
is already on staging**.

This is a trap if you read the page literally. The right check is ancestry,
not page state:

```bash
# Is the head's tree reachable from staging?
git fetch origin staging
git merge-base --is-ancestor <pr-head-sha> origin/staging && echo "landed"

# Or: does the range produce anything?
gh api repos/botiverse/mobile/compare/<pr-head-sha>...staging \
  --jq '.status, .ahead_by'
```

If `merge-base --is-ancestor` succeeds, the commit is already on staging — the
PR page's `merged=false` is just metadata lag.

## Failure modes this catches

| Symptom | What it usually means |
|---|---|
| `git ls-remote` SHA ≠ approved SHA | Push after review; approval is stale |
| `stamp pr merge --head` errors | Guard caught a moved head — correct |
| `merged=true` but merge-commit tree differs | Merge commit touched more than the PR |
| `merged=false` but `merge-base --is-ancestor` OK | Stamp queue path; code is already in |
| Green CI badge on old SHA | CI ran on an earlier head, not the current tip |
