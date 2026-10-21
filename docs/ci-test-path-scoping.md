# Test workflow path scoping

> **Partly stale (2026-10-20):** since task #37 ordinary PRs never run the iOS compile, even for iOS paths; `scripts/ci/plan_test_tier.sh` is authoritative (skill `raft-ci-validation`).

The `Test` workflow always starts for pull requests and protected-branch
pushes so the stable `CI gate` context can report. Its lightweight `plan` job
classifies the exact changed paths and starts only the selected platform jobs.
This follows the narrow workflow/self-change path pattern used by the KuiklyUI
fork without putting a top-level `paths` filter on a context that may be
required by the Mobile ruleset.

## Path groups

The planner has two platform groups, Android and iOS.

| Changed paths | Selected platform groups | Notes |
| --- | --- | --- |
| `compose/androidApp/**`, `compose/shared/src/android*/**` | Android | Includes Android unit and instrumented sources. |
| `compose/iosApp/**`, `compose/shared/src/ios*/**` | iOS | Runs the macOS compile without unrelated Android jobs. |
| `compose/shared/src/native*/**` | iOS | Native shared code only affects the iOS native targets. |
| Other `compose/**` paths | Android + iOS | Common/shared code, Gradle/version configuration and any new unrecognized source-set layout fail closed to all platforms. |
| Test-owned actions/helpers, dependency pins and `.gitmodules` | Android + iOS | Workflow inputs and shared dependency changes are treated as cross-platform. |
| `.github/workflows/test.yml`, the planner/verifier/contract, the Mobile ruleset plan | Android + iOS | CI control changes force the deterministic non-emulator matrix. |
| Anything outside the Test-owned groups | none | Expensive jobs are skipped; `plan` and `CI gate` still report. |

Pull requests use merge-base diff semantics, matching GitHub's changed-file
set; pushes compare the exact `before..head` range. Rename collapsing is
disabled so both the removed and added path participate in classification.
Mixed changes select the union of their groups. If the workflow cannot prove a
complete before/head diff (missing object, shallow history, or an all-zero
`before` SHA), it emits a synthetic unknown-path marker and fails closed to all
platforms.

## Event and no-op behavior

| Event | Selection |
| --- | --- |
| Ordinary pull request | Exact union of changed-path groups. |
| Pull request with `full-ci` | All platforms plus Android emulator jobs. |
| Merge queue | Deterministic all-platform matrix; emulator jobs remain excluded by the queue contract. |
| Push to `main` | Exact union of changed-path groups. Android-selected pushes retain the existing emulator readback; iOS-only and irrelevant pushes do not spend emulator capacity. An irrelevant-only push is an explicit no-op. |
| Push to `release-*` | Full all-platform and emulator readback, regardless of paths. |
| Schedule or manual dispatch | Full all-platform and emulator readback. |

For an irrelevant-only pull request or `main` push, the platform jobs remain
GitHub `skipped` (honest **NOT RUN**). The aggregate verifier checks that every
unselected job is in fact skipped, then lets the stable `CI gate` context
succeed. That aggregate success is evidence that the no-op plan was enforced;
it is not evidence that a skipped platform job ran green.
