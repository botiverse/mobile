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
| Ordinary PR touching `compose/**` | yes | **no**, even for iOS paths (task #37) | no |
| PR with label `full-ci` | yes | yes | yes |
| PR touching only docs/other | no (skipped = NOT RUN) | no | no |
| Push to `main` | by path | only if iOS paths changed | no |
| Push to `release-*` | yes | yes | no |
| Nightly schedule | yes | yes | yes |
| `workflow_dispatch` `scope=pr` / `scope=ios` / `scope=full` | yes / **no** / yes | no / yes / yes | no / no / yes |
| `workflow_dispatch` `scope=premerge` (`base_ref`, default `main`) | **no** (fail-closed: yes if the diff is unavailable) | only if the diff vs merge-base can affect iOS | no |
| `workflow_dispatch` `scope=premerge` with `base_ref=release/*` | yes | yes | yes (= `scope=full`) |

iOS compile runs on Blacksmith with Xcode 27.0. A second lane, `iOS compile
(Xcode 27.1)` on GitHub `xcode-27` (~48 min, the most expensive runner), runs
only on the nightly schedule and `release-*` pushes (planner output
`run_ios_xcode271`) — not on full-ci PRs, `compose/iosApp/**` changes, main
pushes or any dispatch scope. 27.1-only SDK breakage surfaces in the nightly
and in `ios-release.yml`'s 27.1 build.

So an ordinary PR never proves the iOS build. When your change can break
Kotlin/Native or Swift (commonMain APIs, expect/actual, `compose/iosApp/**`,
the realtime Swift list), dispatch the iOS-only tier on your branch **once,
right before merge** (not after every push; re-run only if a later push touches
iOS or shared Kotlin code that could break the iOS build). It runs the plan job
and the Blacksmith `iOS compile` only — Android is already covered by the PR run:
```bash
bin/stamp workflow run test.yml --repo botiverse/mobile --ref <branch> -f scope=ios
gh run list --repo botiverse/mobile --workflow test.yml --branch <branch> --limit 3
```
Stamp's ready-to-merge check dispatches `-f scope=premerge -f base_ref=<PR base>`
on the PR head: same path classification as a pull_request against the
merge-base with `origin/<base_ref>`, iOS compile only when the diff can affect
iOS, no Android rerun. The plan prints `reason=workflow_dispatch-premerge:ios`,
`:no-ios`, `:release-full` or `:diff-unavailable-fail-closed`; with no iOS the
run is plan + `CI gate` only.
`scope=full` (Android device/emulator suite, canonical-final, attachment legs)
is only for release candidates or a deliberate full readback — never just to
check that iOS compiles.
`docs/ci-test-path-scoping.md` describes the path groups; its iOS rows predate
task #37 and the planner script is authoritative.

## 2. Report honestly

In the PR and task thread list the checks that ran and the ones skipped or not
run (e.g. "Android compile + unit: pass; iOS compile: not run (ordinary PR)").
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
