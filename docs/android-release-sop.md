# Android Release SOP

This is the operating procedure for a `raft-android` release on Hands. The
release lane is intentionally draft-first: CI may build, sign, upload, and
share a **draft**, but it must not activate a public release. Activation is a
separate, explicit, owner-approved command after reviewed notes and smoke.

The versioning formula and user-facing changelog rules are defined in
[Android Quiver Release Practices](android-quiver-release-practices-2026-07-02.md).
This document defines the order of operations and the scripts that enforce it.

## Roles

- **Release owner** selects the candidate PRs, approves the user-facing
  changelog, reviews smoke evidence, and supplies the explicit publish go.
- **Release operator** runs the preflight, dispatches the draft workflow,
  records artifacts and smoke evidence, and runs the final publish command
  only after the owner's go.
- **Feature owners** ensure each selected PR is merged, its dependencies are
  already on `main`, and its intended user-visible behavior is represented in
  the notes.

## 1. Freeze The Candidate

Write down the intended version, versionCode build component, channel, and the
specific PRs selected for this release. Do not use "all open PRs" as a release
criterion: unrelated in-progress PRs must not block a candidate, while a PR
that is selected must be explicitly accounted for.

For example, version `1.2.2` build `0` has versionCode `1020200`:

```text
versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + build
```

Build `1` is required when rebuilding the same semantic version for a client
that may already have build `0` installed.

## 2. Run The Preflight

Start from a clean local checkout of `main` that exactly equals `origin/main`.
Do not run the release from a feature branch, detached worktree, or a local
main that has not been fast-forwarded.

```bash
git switch main
git pull --ff-only origin main

scripts/release_android_preflight.sh \
  --version 1.2.2 \
  --build 0 \
  --channel main \
  --required-pr 501 \
  --required-pr 505 \
  --report artifacts/release-preflight/1.2.2-1020200.md
```

The preflight fails closed when any of these facts is false:

1. The checkout is clean `main` and exactly matches `origin/main`.
2. Every `--required-pr` is merged and its merge commit is an ancestor of the
   release head.
3. The release head has successful `Commit Sign-off` and `Test` GitHub
   workflows.
4. The checked-in Kuikly gitlink is reachable from `origin/staging2`; the
   operator must not publish a detached/orphan Kuikly commit during release.
5. The OHOS Hands dependency is complete: `libs/hands.har` is readable,
   identifies as `@botiverse/hands`, and is referenced by both
   `oh-package.json5` and `oh-package-lock.json5`.
6. The candidate versionCode is strictly greater than the active Hands version
   returned by the public update endpoint for the selected channel and Android
   architecture.

The preflight is read-only apart from fetching remote refs and writing its
optional report. It never uploads artifacts, creates a tag, or activates a
release. `--skip-hands-version-check` exists only for offline diagnostics and
is rejected for the `main` channel.

## 3. Create A Draft

Use the dispatch wrapper from the same clean checkout. By default it dispatches
the cancellable `Android Release` draft workflow first, then runs the full
preflight concurrently. If preflight fails, the wrapper cancels the exact
GitHub Actions run it dispatched and exits nonzero. This overlaps the long
build with read-only validation; it does not relax any release gate.

For a diagnosis that must prove preflight before a workflow is dispatched, pass
`--preflight-first`. The wrapper has no active-publish mode.

```bash
scripts/release_android_quiver_github.sh \
  --version 1.2.2 \
  --build 0 \
  --channel main \
  --variant release \
  --required-pr 501 \
  --required-pr 505 \
  --preflight-report artifacts/release-preflight/1.2.2-1020200.md \
  --wait
```

Expected workflow output:

- signed, aligned APK with a verified SHA-256;
- workflow APK and R8 mapping artifacts;
- a Hands **draft** release and optional temporary share page;
- a raw commit-derived changelog only as authoring input.

The release workflow no longer triggers from tags, activates a release, or
creates a release tag. A tag is a post-publication audit record, never a build
trigger or a way to bypass the draft gate.

## 4. Write And Review Notes

Use the workflow's raw changelog and the selected PR list as source material,
then create reviewed notes in two files. Do not paste raw commit subjects into
the release.

```text
artifacts/release-notes/1.2.2/zh-CN.md
artifacts/release-notes/1.2.2/en.md
```

Apply the house format from the release-practices document:

- user-visible changes only;
- sectioned New / Improved / Fixed content, omitting empty sections;
- zh-CN and English carry the same claims;
- exclude PR numbers, implementation detail, CI history, and unshipped work.

The release owner must approve these files before activation.

## 5. Smoke The Draft

Record the draft release id, share URL, APK SHA-256, source commit, and device
or emulator used. At minimum validate:

1. Fresh install and launch reach the expected app/session state.
2. The selected user-visible changes work on the candidate package.
3. Updating from a lower versionCode reaches the Android package installer.
4. A device already at the candidate versionCode reports no further update.
5. Crash/deobfuscation support artifacts exist when the build produced them.
6. Draft visibility remains isolated from the public `main` update endpoint.

For a risky release, add the targeted regression and a fresh performance/visual
artifact before asking for activation.

## 6. Activate Only On Explicit Approval

Once the owner has reviewed the notes and smoke evidence and explicitly asked
to publish, run the finalizer. It verifies the supplied draft reports the
expected versionCode, writes the bilingual notes, activates that draft, and
requires the public update endpoint to return the exact new versionCode.

```bash
scripts/finalize_android_hands_release.sh \
  --release <hands-draft-release-id> \
  --version 1.2.2 \
  --build 0 \
  --channel main \
  --zh-notes artifacts/release-notes/1.2.2/zh-CN.md \
  --en-notes artifacts/release-notes/1.2.2/en.md \
  --confirm-publish
```

`--confirm-publish` is deliberately mandatory. It is an operator assertion
that the owner has given the release go; it is not a replacement for that go.

After the public verification passes, create the annotated `vX.Y.Z` tag only
if the release owner wants a git audit tag. The tag must point at the verified
source commit and must never be used to re-trigger the Android release
workflow.

## Abort And Rebuild Rules

- If the candidate changes after the preflight, start again from step 1.
- If a draft is malformed, cancel/supersede the draft and build a new version
  code; do not overwrite the active main release to test visibility.
- If the versionCode is not greater than the active Hands version, increase the
  build component or select a new semantic version before dispatching.
- If a dependency gate, notes, or smoke result is uncertain, keep the release
  draft-only. No active package is preferable to an unexplained package.
