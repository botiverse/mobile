---
name: raft-ci-validation
description: Use when deciding how to validate a Raft Mobile change in hosted CI instead of local Gradle — which Test tier runs for a PR, how to force iOS or the full suite, getting an installable Alpha APK or iOS simulator app, reporting what ran vs skipped, and diagnosing a poisoned Gradle cache.
---

# Hosted CI validation

Default to hosted CI; keep local work to source inspection and cheap scripts.
Heavy local Gradle/Konan builds only when the owner asks or no hosted lane
exists (say so in the thread). If local Gradle fails on cache corruption or a
locked daemon, stop and report; don't retry. For a warm interactive VM see
`blacksmith-testbox` / `blacksmith-visual-testbox`.

## 1. What the `Test` workflow runs

`.github/workflows/test.yml` always reports the `CI gate` context; its `plan`
job (`scripts/ci/plan_test_tier.sh`) picks jobs:

| Event | Android | iOS compile | Device suite |
| --- | --- | --- | --- |
| Ordinary PR touching `compose/**` | yes, **plain JVM unit tests only** (no Robolectric, no goldens) | **no**, even for iOS paths (task #37) | no |
| PR with label `full-ci` | yes | yes (Xcode 27.0) | yes |
| PR touching only docs/other | no (skipped = NOT RUN) | no | no |
| **Merge-queue candidate** (Stamp's draft PR from `stamp-mq/*`) | yes | **yes, every batch** (Xcode 27.1 lane only) | no (emulator jobs run as no-op successes) |
| Push to `main` that is a verified queue fast-forward | no | no | no (only `queue-ci-reuse`) |
| Push to `main` | by path | only if iOS paths changed | no |
| Push to `release-*` | yes | yes (27.0 + 27.1) | no |
| Nightly schedule | yes | yes (27.0 + 27.1) | yes |
| `workflow_dispatch` `scope=pr` / `scope=ios` / `scope=full` | JVM unit tests only / **no** / yes | no / yes / yes | no / no / yes |
| `workflow_dispatch` `scope=premerge` (`base_ref`, default `main`) | **no** (fail-closed: yes if the diff is unavailable) | **no** (moved to the merge queue, 2026-11-19) | no |
| `workflow_dispatch` `scope=premerge` with `base_ref=release/*` | yes | yes | yes (= `scope=full`) |

Android unit & Roborazzi tier (artin 2026-11-22 & 2026-11-24, `#proj-mobile:#340`;
planner outputs `android_unit_scope`, `run_designsystem_tests`, `run_roborazzi`):
where the table says "plain JVM unit tests only" (also premerge into `main` when
it reruns Android), the Android job compiles everything it needs and runs only
plain JVM unit tests and non-screenshot UI logic tests:
`-Praft.unitTest.skipRobolectric=true` skips every Robolectric class in `:shared`
and `:androidApp`, and `-Praft.unitTest.skipScreenshots=true` skips every
Roborazzi / vcase screenshot golden class. `:designsystem:desktopTest` (unit +
Compose UI logic tests only, no `DesignSystemVCases` goldens) runs only when the
PR touches `compose/designsystem/` (always on `scope=pr`, which has no diff).
Ordinary build/deps PRs run the full non-screenshot suite (`android_unit_scope=all`,
`run_roborazzi=false`). Every post-merge / merge-queue / release / full /
`full-ci` / `record_screenshots` Android run has `run_roborazzi=true` and runs
the whole suite plus both golden sets. To run them on a PR before merge, add
`full-ci`, or dispatch `record_screenshots=true` to record goldens; AGENTS.md
"Android unit tiers in CI".

### The merge queue (pre-merge iOS compile)

Stamp's merge queue lands every PR into `main` (2026-11-19). Once a PR is
approved and its auto-merge is registered, Stamp puts it in the FIFO queue; up
to 10 ready PRs form a batch, each squashed onto main's tip, pushed to
`stamp-mq/<batch>` with a managed draft PR into `main`. `Test` runs on that
draft PR (`pull_request`) with plan `reason=merge-queue-candidate`: the Linux
gates in `plan`, `Android compile & tests`, and `iOS compile (Xcode 27.1)` on
GitHub's free `xcode-27` image, whatever the batch's diff. Stamp accepts a
candidate only when **every job** of its run succeeded (only `queue-ci-reuse`
may skip), so the emulator jobs run as no-op successes there (their steps are
gated on `RAFT_JOB_ACTIVE`; planner output `queue_candidate`). `CI gate` needs
every job and is the queue's required check. The queue's CI policy is the
default "test the final combined commit", so a batch uses one Mac. One iOS
compile (~33 min) per batch replaces the per-PR premerge compile.

CI reuse on main: before fast-forwarding, Stamp writes the commit status
`stamp/queue-tested` (`v1 run=<run id> base=<base sha>`) on the candidate SHA.
The main push run's plan job checks it with `scripts/ci/check_queue_tested.py`
(creator `stamp-build[bot]`, description shape, the named run is this
workflow's first-attempt green `pull_request` run on a `stamp-mq/` branch for
the same SHA with every job green, and the push's `before` equals the base).
If all hold, the plan is `reason=main-push-queue-tested-reuse` and only the
`queue-ci-reuse` job runs; on any doubt the push runs its usual tier. Nightly
and `release-*` never reuse.

- Where a PR stands: `stamp pr view <N> --repo botiverse/mobile` (`merge
  queue:` line) or `stamp pr queue status <N> --repo botiverse/mobile`.
- A failed batch: `stamp pr checks <N>` lists `merge queue runs:` with the
  `stamp run view ... --log-failed` command. `hintKind failed_alone` means the
  PR failed tested alone: fix, push, approve, register auto-merge again.
  `member_changed` means another PR of the batch failed: nothing to do.
- A red `main` holds the queue; a PR labelled `fix-red` (or a GitHub revert)
  is tested first.
- A PR that changes `test.yml`, `.github/actions` or other CI definition is
  tested alone in its own batch, by the workflow as that PR defines it.
- Do not push `stamp-mq/*` yourself (Stamp refuses it, `REF_RESERVED_FOR_STAMP`).
- `Test` must keep running on draft PRs: a workflow that skips drafts leaves
  the batch waiting until its timeout.
- Both iOS compile lanes run on GitHub's free `xcode-27` image only. There is
  no billed Blacksmith fallback: the `ios_runner` dispatch input was removed
  on 2026-11-22 (and Blacksmith macOS could not run the Xcode 27.1 lane: on
  `blacksmith-*-macos-27` `Xcode_27.1.app/Contents/Developer` is a symlink
  that `scripts/ci/select_ios_xcode.sh` refuses).
- Until the queue is enabled on `main` (`stamp queue status botiverse/mobile`)
  nothing compiles iOS before merge; the main push readback and the nightly
  catch it. The merge hold on `main` is off (owner decision, 2026-11-21): a
  red `main` does not stop merges, Stamp notifies the authors of the PRs
  merged since the last green run, and the break is fixed the same day.

### Lanes and iOS tests

The default iOS compile lane (`iOS compile`) runs on GitHub's standard
`xcode-27` image with Xcode 27.0 (free on this public repo since 2026-11-18;
was Blacksmith macOS): full-ci PRs, main pushes, `scope=ios` / `scope=full`,
nightly and `release-*`. GitHub allows 5 concurrent macOS jobs org-wide; if
that queue is backed up, a `scope=ios` dispatch waits for a free Mac (there is
no billed fallback). Dispatch `scope=ios` once, on the head you are about to
merge, not on every push. The Xcode 27.1 lane,
`iOS compile (Xcode 27.1)` on the same `xcode-27` image (~33 min compile-only,
~48 min with the iOS tests on its 3-core Mac), runs on the merge-queue batch
(alone) and beside the 27.0 lane on the nightly schedule and `release-*` pushes
(planner outputs `run_ios_xcode270` / `run_ios_xcode271`, pinned by the `CI
gate` verifier) — not on full-ci PRs, main pushes or any dispatch scope.

The iOS compile job runs the iOS tests (planner output `run_ios_tests`) only
on the full tier: nightly schedule, `release-*` pushes, `scope=full`, premerge
with a `release/*` base, and `full-ci` PRs. That is the K/N commonTest compile
(`compileTestKotlinIosSimulatorArm64`), the shared `iosTest` simulator run, the
`iosAppTests` XCTest bundle (including `RaftRouterLoginTransitionTests`) and
adaptive editor snapshots. The merge queue,
`scope=ios` and main pushes compile the iOS targets, link the shared framework
and build the host app only, so a green queue iOS compile is not an iOS test
result; iOS-only behaviour regressions surface in the nightly within 24h.
Exception: a queue candidate or main push whose diff touches iOS test sources
(`*/iosTest/*`, `compose/iosApp/iosAppTests/**`,
`compose/iosApp/iosAppUITests/**`) runs the iOS tests too. To run the iOS
tests on a PR before merge, add the `full-ci` label; do not dispatch
`scope=full` for that (see below, it is for release candidates).

So the PR run never proves the iOS build; the queue batch does, before main
moves. Do not dispatch `scope=ios` by hand before merge. Stamp's ready-to-merge
check still dispatches `-f scope=premerge -f base_ref=<PR base>` on the PR
head; into `main` it now runs plan + `CI gate` only
(`reason=workflow_dispatch-premerge:no-ios`; an unreadable diff reruns Android,
`:diff-unavailable-fail-closed`). With a `release/*` base it runs the full tier
(`:release-full`).
`scope=ios` stays for a deliberate one-off check (a risky expect/actual or
Kotlin/Native change): at most once, on the head you are about to merge, never
after a rebase alone:
```bash
bin/stamp workflow run test.yml --repo botiverse/mobile --ref <branch> -f scope=ios
```
`scope=full` (Android device/emulator suite, attachment legs)
is only for release candidates or a deliberate full readback — never just to
check that iOS compiles.
`docs/ci-test-path-scoping.md` describes the path groups; its iOS rows predate
task #37 and the planner script is authoritative.

## 2. Report honestly

In the PR and task thread list the checks that ran and the ones skipped or not
run (e.g. "Android compile + JVM unit: pass; Robolectric, goldens and iOS
compile: not run on the PR, run in the merge queue batch").
A skipped job counts as satisfied for the gate; it is not a green result.

## 3. Installable builds

- Android: `Android Alpha Validation` (`android-alpha.yml`): co-installable
  `build.raft.app.alpha`, label "Raft Alpha", verified APK artifact.
  ```bash
  gh workflow run android-alpha.yml --repo botiverse/mobile --ref <branch> \
    -f version_patch=0 -f version_build=<increasing 0-99>
  ```
  Don't edit Gradle locally to fake an alpha suffix.
- iOS simulator app: `ios-simulator-app.yml` (`-f source_sha=<sha>` optional);
  run it with `docs/kb/ios-simulator-build-and-launch.md` / the `mobile-use`
  skill.
- Visual captures: `raft-visual-parity`.

## 4. Failing for reasons that aren't your change

- Poisoned Gradle cache (byte-identical `EOFException` on a dependency jar,
  `Failed to transform ...`, `ClassNotFoundException` for existing classes,
  reproducing across PRs): follow `docs/ci-gradle-cache-epoch-runbook.md` —
  bump `.github/gradle-cache-epoch` for the top-level entry, or list the bundle
  in `.github/gradle-cache-quarantine`; one PR, separate reviewer.
- D8 dex merge OOM: `docs/kb/android-ci-d8-dex-heap-ceiling.md`.
- Visual run red only at `Upload react results`: see `raft-visual-parity` §4.
- Harness/infra defects are separate tasks and don't block a product PR
  (AGENTS.md "Peer Review Convention").
