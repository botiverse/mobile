# New Agent Landing Guide

First page for agents joining Raft Mobile work.

## Start here

1. Read `AGENTS.md`, then load the skill that matches your task (AGENTS.md
   "Skills").
2. Set up Stamp once (`raft integration login --service stamp`,
   `stamp login && stamp whoami`) and use a Stamp checkout; push, PRs, reviews
   and merges go through Stamp (skill `raft-stamp-git`).
3. Check the task thread that brought you here and claim the task before
   doing any work.
4. Work in an isolated worktree branched from current `origin/main`.
5. Report progress in the task thread, not as a new root message.

## Repositories

| Purpose | Where |
| --- | --- |
| Raft Mobile (Android, iOS, desktop) | `botiverse/mobile` |
| Server and web source (contract oracle) | `botiverse/slock` (default branch `staging`), an external checkout pointed to by `SLOCK_SOURCE_REPO` |

For API payloads or Socket.IO events, read
`<slock-source>/packages/server/` and `<slock-source>/packages/shared/`, and
record the exact upstream SHA with any review, migration or fixture evidence.
Do not create a `third_party/slock-source` directory.

## Branch and push rules

- `main` is protected and PR-only; never push to it or merge into a shared
  local `main` as delivery.
- Do not revert or clean unrelated dirty files in a shared checkout.
- Every progress or completion report says whether the work is
  branch-only, in a PR (number/link), or merged (merge commit).
- Never paste credentials into Raft channels or threads.

## Build and validation

The Gradle root is `compose/`. Prefer hosted CI for heavy builds (skill
`raft-ci-validation`). Focused local checks:

```bash
cd compose
./gradlew :shared:compileDebugKotlinAndroid
./gradlew :androidApp:compileDebugKotlin
./gradlew :shared:testDebugUnitTest --tests 'build.raft.app.SomeTest'
```

Useful docs:

- Android emulator setup: `docs/android-emulator-setup.md`
- Mobile device automation: `docs/mobile-device-automation.md`
- Visual testing provider: `docs/visual-testing-android-provider.md`
- Release procedure: `docs/mobile-release-sop.md`; release retros: `docs/retros/` (latest: `docs/retros/2026-11-14-1.25.md`)

## Collaboration

- Claim tasks before doing work; if a claim fails as already assigned, do not
  duplicate the work.
- Reply in the task thread; avoid root channel updates unless the owner asks.
- Keep reports short: what changed, where it landed, what was validated, what
  remains.
