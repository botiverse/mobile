# Commit Sign-off Policy

All Raft Mobile commits must carry a `Signed-off-by:` trailer with a `@mail.build` identity.

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

The hook rejects commit messages that do not include a `Signed-off-by:` trailer with a `@mail.build` email. CI also runs `scripts/check-commit-signoff.sh` over the commit range for pushes and pull requests.

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

For a squash created after the base branch advanced, the ledger uses five
fields: immutable squash, reviewed signed head, exact signer, reviewed source
parent, and immutable parent. The checker requires every commit in the reviewed
source range to have a real `@mail.build` sign-off; the declared source parent
to be the exact merge-base; path/status and per-path add/delete sets to match;
zero-context stable patch-id and normalized diff SHA-256 to match; and
`git merge-tree --write-tree` of immutable parent plus reviewed head to be clean
and exactly equal the immutable squash tree. Any mismatch fails closed.

## Guarded Squash Merge

Both Mobile and Slock configure GitHub squash bodies as `COMMIT_MESSAGES`.
The repository wrapper is the only allowed merge path. It forces squash mode so
GitHub preserves source commit trailers with real newlines:

```bash
scripts/guarded-squash-merge.sh botiverse/mobile <pr-number> <reviewed-full-sha>
```

The wrapper keeps `--match-head-commit`, verifies the repository still uses
`COMMIT_MESSAGES`, and intentionally accepts no custom subject or body. Keep
parent/tree/patch/CI/authorization receipts in the PR and Raft task thread. Do
not pass strings containing `\\n` to `gh pr merge --body`; shell quoting and gh
preserve those bytes literally, so a visual `Signed-off-by` substring is not a
Git trailer. Do not use the GitHub Merge button or `gh pr merge --merge`; a
two-parent GitHub merge commit does not preserve source trailers and turns the
next main-push DCO check red.

## PR-only Main Integration

GitHub `main` is protected. A valid signed commit is necessary but not enough
to publish code: new mobile work must go through a GitHub PR to `main`.

Use this flow:

1. Branch from current `origin/main`.
2. Commit with `git commit -s` and the correct `@mail.build` identity.
3. Validate the touched area locally.
4. Run `scripts/check-commit-signoff.sh <base> HEAD` for the PR range.
5. Push the topic branch and open a PR.
6. Wait for required GitHub checks and owner/reviewer approval, then merge only
   with `scripts/guarded-squash-merge.sh` and the reviewed full head SHA.

Do not directly merge finished work into a shared local `main` as the delivery
step, and do not run `git push origin main`. If local `main` is already ahead,
move the non-equivalent commits to PR branches and clean local `main` only
after confirming there are no unrelated dirty files.
