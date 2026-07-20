# Android Release SOP

This is the operating procedure for a `raft-android` release on Hands. The
release lane is intentionally draft-first: CI may build, sign, upload, and
share a **draft**, but it must not activate a public release. Activation is a
separate, explicit, owner-approved command after reviewed notes and smoke.

Each release is stabilized on a dedicated `release-X.Y.Z` branch. Product work
may continue on `main`, while only explicitly selected release fixes enter the
release branch. The release branch is merged back into `main` only after the
Hands release has been activated and verified successfully.

The versioning formula and user-facing changelog rules are defined in
[Android Quiver Release Practices](android-quiver-release-practices-2026-07-02.md).
This document defines the order of operations and the scripts that enforce it.

## Roles

- **Release owner** selects the candidate PRs, approves the user-facing
  changelog, reviews smoke evidence, and supplies the explicit publish go.
- **Release operator** runs the preflight, dispatches the draft workflow,
  records artifacts and smoke evidence, and runs the final publish command
  only after the owner's go.
- **Fix owners** branch from the current release branch, target their PR back
  to that release branch, keep changes narrowly scoped to an accepted release
  blocker, and provide source, hosted, and targeted regression evidence.

## 1. Freeze The Candidate

Write down the intended version, versionCode build component, channel, and the
specific PRs selected for this release. Do not use "all open PRs" as a release
criterion: unrelated in-progress PRs must not block a candidate, while a PR
that is selected must be explicitly accounted for.

After the selected candidate head has passed its exact-head source and hosted
gates, create a remote branch named `release-X.Y.Z` at that exact commit. The
branch is a stabilization boundary, not a snapshot that follows `main`.

```bash
scripts/create_android_release_branch.sh \
  --version 1.5.0 \
  --candidate <reviewed-exact-main-commit>
```

Fail if the remote branch already exists at a different commit. Moving an
existing release branch backwards or force-pushing it destroys release audit
history and is prohibited. The helper is idempotent only when the existing
remote branch already points at the requested exact commit.

Once frozen:

1. New product work continues on `main` and does not enter the release.
2. A release-blocker fix branches from `origin/release-X.Y.Z` and its PR base
   is `release-X.Y.Z`, not `main`.
3. Do not merge `main` into the release branch to pick up unrelated changes.
   Bring in only reviewed blocker fixes and their strictly required
   dependencies through explicit PRs.
4. Every merged fix creates a new candidate head. Previous Alpha, RC,
   preflight, checksum, smoke, and approval evidence becomes stale.
5. Keep each independent blocker in its own PR so it can be reviewed, reverted,
   and validated without coupling unrelated fixes.

For example, version `1.2.2` build `0` has versionCode `1020200`:

```text
versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + build
```

Build `1` is required when rebuilding the same semantic version for a client
that may already have build `0` installed.

## 2. Run The Preflight

Start from a clean local checkout of the release branch that exactly equals
its remote. Do not run the release from `main`, a feature branch, a detached
worktree, or a local release branch that is ahead of or behind its remote.

```bash
export SLOCK_RELEASE_BRANCH=release-1.2.2

git fetch origin "${SLOCK_RELEASE_BRANCH}" --prune
git switch "${SLOCK_RELEASE_BRANCH}"
git pull --ff-only origin "${SLOCK_RELEASE_BRANCH}"

scripts/release_android_preflight.sh \
  --version 1.2.2 \
  --build 0 \
  --channel main \
  --required-pr 501 \
  --required-pr 505 \
  --report artifacts/release-preflight/1.2.2-1020200.md
```

The preflight fails closed when any of these facts is false:

1. The checkout is the configured clean release branch and exactly matches
   `origin/release-X.Y.Z`.
2. Every `--required-pr` is merged and its merge commit is an ancestor of the
   release head.
3. The release head has a successful `Commit Sign-off` workflow. The Android
   Release workflow itself is the candidate build/test gate; do not run a
   separate exact-head `Test` workflow for release.
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

`Android Release` is the only compile/test workflow required for the candidate.
Its own failure prevents draft creation, so triggering a separate `Test`
workflow would only duplicate the same build signal.

For a diagnosis that must prove preflight before a workflow is dispatched, pass
`--preflight-first`. The wrapper has no active-publish mode.

```bash
export SLOCK_RELEASE_BRANCH=release-1.2.2

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
change-logs/1.2.2/android/zh-CN.md
change-logs/1.2.2/android/en.md
```

The Android workflow prefers this platform directory. It falls back to the
historical `change-logs/<version>/{zh-CN,en}.md` layout only for older releases
that predate platform-specific notes.

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

### Authenticate Hands For Finalization

The draft build runs in GitHub Actions with a repository secret, but the local
finalizer needs its own Hands CLI authentication. Before finalizing, run
`hands whoami` and require it to identify an account that has the `publisher`
role for `raft-android`.

For agents and CI, the recommended path is an app-scoped publisher deploy
token. Create it under Hands App -> Settings -> Deploy Tokens, transfer it only
through an authorized private channel, and keep it in the process environment:

```bash
export HANDS_BEARER_TOKEN=<raft-android-publisher-deploy-token>
export HANDS_API=https://hands.build
hands whoami
```

Hands CLI `0.5.2` or newer is required for `HANDS_BEARER_TOKEN`. The finalizer
falls back to `@botiverse/hands-cli@0.5.2` from the official npm registry when
`hands` is not installed. This avoids delayed package-mirror propagation;
override `HANDS_NPM_REGISTRY` only for a trusted registry that already carries
the required CLI version.

A Raft agent may instead use its integration session cookie when the Hands
service owner has enabled that path:

```bash
raft integration login --service hands-4cc7a2
export HANDS_SESSION_COOKIE='quiver_session=<session-cookie-value>'
hands whoami
```

The cookie value is the `cookies[0].pair` value in the agent-owned Hands
integration session under
`~/.slock/integration-sessions/<agent-id>/integrations/hands-4cc7a2.json`.
Treat it as a credential: never print it in logs, commit it, or paste it into a
public channel. Humans may use `hands login` and the browser session flow.

Authentication and authorization are separate. A CLI `0.5.2+` authentication
failure explains the supported agent token/session options. An authenticated
request that lacks app permission returns `required_role` and `next_action`;
ask a Hands app admin for the named role instead of retrying blindly.

```bash
scripts/finalize_android_hands_release.sh \
  --release <hands-draft-release-id> \
  --version 1.2.2 \
  --build 0 \
  --channel main \
  --zh-notes change-logs/1.2.2/android/zh-CN.md \
  --en-notes change-logs/1.2.2/android/en.md \
  --confirm-publish
```

`--confirm-publish` is deliberately mandatory. It is an operator assertion
that the owner has given the release go; it is not a replacement for that go.

After the public verification passes, create the annotated `vX.Y.Z` tag only
if the release owner wants a git audit tag. The tag must point at the verified
source commit and must never be used to re-trigger the Android release
workflow.

## 7. Merge The Released Branch Back Into Main

Do not merge the release branch into `main` before publication. The successful
Hands release is the gate that turns the stabilization branch into shipped
history.

After activation and public endpoint verification:

1. Record the released source commit, versionCode, Hands release id, APK
   SHA-256, approved notes, smoke evidence, and optional audit tag.
2. Open a PR from `release-X.Y.Z` to `main`. Do not push directly to `main`.
3. Review the complete merge diff. `main` may have advanced during
   stabilization, so resolve conflicts by preserving both the shipped fixes
   and intentional newer product work. Never resolve by replacing `main` with
   the older release tree.
4. Run the normal required hosted gates on the merge PR. Merge only after the
   PR is reviewed, green, and cleanly mergeable.
5. Verify the released source commit is an ancestor of `origin/main`, then
   close release-only tasks and Hands feedback using the shipped evidence.
6. Delete `release-X.Y.Z` only after the merge-back is verified and no release
   audit or rollback operation still needs the branch.

If publication is abandoned, do not merge the release branch merely to recover
its fixes. Re-triage each fix for `main` and use explicit PRs with current-main
validation.

## Abort And Rebuild Rules

- If the release-branch head changes after preflight, invalidate all evidence
  bound to the previous head and restart from step 2. Do not move the branch
  back to an older candidate.
- If a draft is malformed, cancel/supersede the draft and build a new version
  code; do not overwrite the active main release to test visibility.
- If the versionCode is not greater than the active Hands version, increase the
  build component or select a new semantic version before dispatching.
- If a dependency gate, notes, or smoke result is uncertain, keep the release
  draft-only. No active package is preferable to an unexplained package.
- If a fix PR accidentally targets `main`, do not merge it and then merge
  `main` into the release branch. Retarget or recreate the PR from the release
  branch so the stabilization diff remains explicit.
