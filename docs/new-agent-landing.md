# New Agent Landing Guide

This is the first-page guide for agents joining Raft Mobile work. It points to
the current repositories, commit/sign-off setup, and collaboration rules that
matter before touching code.

## Start Here

1. Read `AGENTS.md` in this repository.
2. Read `docs/commit-signoff-policy.md`.
3. On a new machine, verify GitHub CLI and git SSH access before starting.
4. Check the task thread that brought you here, then claim the task before
   doing any work.
5. Create or reuse an isolated worktree for code changes.
6. Report progress in the task thread, not as a new root message.

## New Machine GitHub Setup

Before cloning, fetching submodules, pushing branches, or dispatching release
workflows, make sure both GitHub CLI auth and git SSH auth work.

Check GitHub CLI:

```bash
gh auth status
```

If it is not logged in, use the normal GitHub CLI flow:

```bash
gh auth login
```

Prefer SSH for git operations. Confirm SSH access:

```bash
ssh -T git@github.com
```

Expected result is GitHub accepting the key and saying shell access is not
provided. If SSH fails, create or register a key before cloning/pushing:

```bash
ssh-keygen -t ed25519 -C "<your-email-or-agent>@mail.build"
eval "$(ssh-agent -s)"
ssh-add ~/.ssh/id_ed25519
gh ssh-key add ~/.ssh/id_ed25519.pub --title "$(hostname)-raft-mobile"
ssh -T git@github.com
```

Do not paste SSH private keys, GitHub tokens, `NPM_TOKEN`,
`CLOUDFLARE_API_TOKEN`, or other credentials into public Raft channels.

## Main Repositories

| Purpose | Local path | Remote / source |
| --- | --- | --- |
| Raft Mobile / KMP app | `/Users/artin/AndroidStudioProjects/Slock` | `origin = git@github.com:botiverse/mobile.git` |
| Mobile worktrees | `/Users/artin/AndroidStudioProjects/Slock-worktrees` | Local worktrees based on the mobile repo |
| React / server source reference | External checkout via `SLOCK_SOURCE_REPO` | `github.com/botiverse/slock` (default branch `staging`) |
| Kuikly release consumed by mobile | `compose/gradle/kuikly-release-closure.json` | Immutable Raft Maven release manifest |

The mobile repo vendors no KMP fork sources. HarmonyOS (OHOS) support is
frozen: `1.14.x` is the last OHOS release and is built only from the
`release/1.14.x` branch; `main` carries no OHOS app, bridge, scripts, or docs.

When API payloads or Socket.IO events are in question, use the TypeScript
server source as the contract oracle. Mobile no longer vendors that repository
as a submodule. Use an external `botiverse/slock` checkout (default branch
`staging`) and set `SLOCK_SOURCE_REPO` to its path; `SLOCK_REACT_REPO_DIR` is an
accepted compatibility alias. Read `<slock-source>/packages/server/` and
`<slock-source>/packages/shared/`, and pin an exact upstream commit whenever
the result is used as review, migration, or fixture evidence. Record the
resolved SHA with that evidence; a moving `staging` checkout is not an exact
contract snapshot. Do not create a `third_party/slock-source` directory in the
Mobile repository.

## Git Sign-off

Agent commits should carry a `Signed-off-by:` trailer with a `@mail.build`
identity. No CI check or git hook enforces it any more.

In the shared canonical checkout, do not rely on repo-local `git config
user.name` or `user.email`; another agent may have changed it. Pass identity per
commit:

```bash
git \
  -c user.name="<YourName>" \
  -c user.email="raft-mobile-<your-name>@mail.build" \
  commit -s -m "fix(scope): describe the change"
```

Example:

```bash
git \
  -c user.name="Cindy" \
  -c user.email="raft-mobile-cindy@mail.build" \
  commit -s -m "docs(repo): add new agent landing guide"
```

To fix the previous commit:

```bash
git \
  -c user.name="<YourName>" \
  -c user.email="raft-mobile-<your-name>@mail.build" \
  commit --amend -s --no-edit
```

## Branch, Worktree, and Push Rules

- Do code work in an isolated worktree when practical.
- Branch from current `origin/main` and open a GitHub PR to `main` after
  validation. GitHub `main` is protected and PR-only.
- Do not merge finished work into shared local `main` as the delivery step.
  If local `main` is ahead, treat it as reconciliation debt and move
  non-equivalent commits to PR branches.
- Do not push remote `main` directly. Use `gh pr create`, then use the
  repository guarded rebase wrapper after required checks and exact-bound
  review pass. Squash requires the guarded squash wrapper and a complete
  validated message file; ordinary merge mode and raw inline squash bodies are
  forbidden. See `docs/commit-signoff-policy.md`.
- Do not revert or clean unrelated dirty files. If the canonical checkout is
  dirty, inspect carefully and preserve other agents' changes.
- Every meaningful progress or completion report must say whether the work is
  branch/worktree-only, in PR, merged to remote `main`, or not applicable. If
  a PR exists, include the PR number/link; if merged, include the remote merge
  commit.

## Build and Validation Pointers

The Gradle root for the app is usually `compose/`:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
```

Common focused checks:

```bash
./gradlew :shared:compileDebugKotlinAndroid
./gradlew :androidApp:compileDebugKotlin
./gradlew :shared:testDebugUnitTest --tests 'build.raft.app.SomeTest'
```

Useful docs:

- Android emulator setup: `docs/android-emulator-setup.md`
- Mobile device automation: `docs/mobile-device-automation.md`
- Visual testing provider: `docs/visual-testing-android-provider.md`
- Release lane notes: `docs/android-release-lane-pi-perfprofiler.md`

## Collaboration Rules

- Claim tasks before doing work.
- Reply in the task thread. Avoid root channel updates unless the owner asks or
  the message needs broad visibility.
- Do not post root messages in `#all` for reconciliation; write the work-log
  summary and DM the owner instead.
- If a task claim fails as already assigned, do not reply and do not duplicate
  the work.
- Keep reports short and actionable: what changed, where it landed, what was
  validated, and what remains.
- Never paste credentials in public channels or public-channel threads.

## Core Engineering Rules

- API contracts must use explicit shared DTOs/parsers in
  `compose/shared/src/commonMain/kotlin/build/raft/app/data/`.
- Verify wire payloads against the server/shared TypeScript source before adding
  DTO fields or test fixtures.
- Route, launch, and Kuikly `pageData` payloads carry identity and intent only.
  Product content/state must load through MVI stores and repositories.
- For Kuikly overlays, keep anchor geometry in pixels; use dp only for visual
  sizes and spacing.

## First-Day Checklist

- Confirm `gh auth status` works.
- Confirm `ssh -T git@github.com` works.
- Confirm you know the task channel and thread target.
- Run `git status --short --branch` in the repo you will touch.
- Install the sign-off hook in your worktree.
- Use per-commit `user.name` / `user.email` with `commit -s`.
- Run focused validation before reporting.
- Report local-main / remote-main status explicitly.
