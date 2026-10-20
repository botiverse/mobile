---
name: raft-stamp-git
description: Use whenever an agent pushes a branch, opens a PR, reviews/approves, or merges in botiverse/mobile — all of it goes through Stamp under the agent's own identity, never the shared gh login. Also covers re-approval after a push or rebase.
---

# Git through Stamp (botiverse/mobile)

`main` is protected and PR-only. Push, PR, approval and merge go through Stamp
(https://stamp.build/llms.txt). Detail: AGENTS.md "Agent Git identity comes
from Stamp" and "Protected `main`: PR-only integration";
`docs/kb/exact-head-merge-verification.md`. `docs/commit-signoff-policy.md` and
the git sections of `docs/new-agent-landing.md` are superseded.

## 1. One-time setup

```bash
raft integration login --service stamp
stamp login && stamp whoami          # principalType=agent, your own name
stamp repo clone botiverse/mobile [DIR]
# or, in an existing clone:
git remote set-url origin ssh://git@ssh.stamp.build:443/github/botiverse/mobile.git
stamp repo use .
```
`repo use` writes commit identity + Stamp SSH settings for the whole clone (all
its worktrees). A `git@github.com:` remote in that clone stops working; fetch
and push through the Stamp remote. In a clone shared between agents, pass
`-c user.name=... -c user.email=...` per commit.

## 2. Branch, commit, push

```bash
git fetch origin
git worktree add ../<dir> -b agent/<you>/<topic> origin/main
# ... change, validate ...
git commit -m "fix(scope): describe the change"         # Conventional Commits; -s optional
git push origin HEAD:refs/heads/agent/<you>/<topic>     # prints a receipt ID
stamp receipt get <ID>                                   # gateway + GitHub publication status
```
Never push to `main`. Commit types: `fix feat docs test refactor perf chore`;
visual parity fixes use `fix(visual): ...`. Set `GIT_PAGER=cat` and
`GIT_EDITOR=true` for non-interactive commands.

## 3. Open the PR

```bash
stamp pr create --receipt <ID> --base main --title "type(scope): ..." --body-file PR.md
```
Refer to the PR by its Stamp page first:
`https://stamp.build/github/botiverse/mobile/pull/<N>`. In the PR and task
thread name which CI checks ran and which were skipped (`raft-ci-validation`).

## 4. Review and approve (reviewer, never the author)

```bash
stamp pr review <N> --repo botiverse/mobile      # exact head, base, merge-base, diff commands
stamp pr diff <N> --repo botiverse/mobile
stamp pr checks <N> --repo botiverse/mobile
stamp pr approve <N> --repo botiverse/mobile --head <HEAD_SHA> --base <BASE_SHA>
```
The approval counts only for that exact head. A GO given in chat must become a
Stamp approval. A HOLD must name the behaviour that breaks; round cap is 3
(AGENTS.md "Peer Review Convention").

## 5. Merge (author)

```bash
stamp pr view <N> --repo botiverse/mobile            # recorded base SHA
stamp pr merge <N> --repo botiverse/mobile --head <HEAD_SHA> --base <BASE_SHA> --method squash
# or register auto-merge once approved:
stamp pr merge <N> --repo botiverse/mobile --head <HEAD_SHA> --base <BASE_SHA> --auto --method squash
```
- `--base` is the PR's recorded base, not the tip of `main`.
- `PR_HEAD_MOVED` / `PR_BASE_MOVED`: read the current values Stamp prints,
  re-review if needed, retry with them.
- No GitHub Merge button, `gh pr merge`, or shared `gh` account.
- Default gate: Stamp approval on the exact head + READY. Owner acceptance is a
  gate only when the task or a live hold says so; then don't register `--auto`.

## 6. After a new push

Any push voids the approval and auto-merge. For a pure rebase the reviewer may
re-approve after checking the patch ids match:
```bash
git diff <old-base> <old-head> | git patch-id --stable
git diff <new-base> <new-head> | git patch-id --stable
```
After merge, fast-forward or reset only clean local checkouts.
