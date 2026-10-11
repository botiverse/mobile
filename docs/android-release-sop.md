# Android Release SOP

This is the operating procedure for a `raft-android` release on Hands. The
release lane is intentionally draft-first: CI may build, sign, upload, and
share a **draft**, but it must not activate a public release. Activation is a
separate, explicit, owner-approved command after reviewed notes and smoke.

When Android and iOS ship from the same frozen Mobile source, follow
[Mobile Paired Release SOP](mobile-paired-release-sop.md) for the shared
ordering, authority, TestFlight, receipt, and merge-back contract.

Each release is stabilized on a dedicated `release-X.Y.Z` branch. Product work
may continue on `main`, while only explicitly selected release fixes enter the
release branch. The release branch is merged back into `main` only after the
Hands release has been activated and verified successfully.

The versioning formula and user-facing changelog rules are defined in
[Android Hands Release Practices](android-hands-release-practices-2026-07-02.md).
This document defines the order of operations and the scripts that enforce it.

For version closeout, branch locking, next-version selection and the required
Notion release ledger, follow [Release closeout and Notion ledger](mobile-paired-release-sop.md#release-closeout-and-notion-ledger).
A closed release branch must not receive further fixes or new builds.

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
versionCode = major * 10_000_000 + minor * 100_000 + patch * 1_000 + build
```

Build `1` is required when rebuilding the same semantic version for a client
that may already have build `0` installed.

Those coordinates are **dispatch inputs**, not a source-tree requirement.
`compose/androidApp/build.gradle.kts` computes `versionName` / `versionCode`
from Gradle properties or `SLOCK_ANDROID_VERSION_*` environment variables,
with formula fallbacks that produce `1.0.0` / `1000000` when no inputs are
set. Do not treat those source defaults as a preflight blocker, and do not
rewrite them into the frozen tree. Allocate the release coordinates, pass
them to `android-release.yml` (`version_major` / `version_minor` /
`version_patch` / `version_build` / `version_suffix`), then verify the signed
artifact carries the allocated values.

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
3. The Android Release workflow itself is the candidate build/test gate; do
   not run a separate exact-head `Test` workflow for release.
4. The pinned Kuikly release has a valid create-last manifest with the exact
   coordinate closure. The release build then resolves those coordinates
   through the standard Gradle/CocoaPods/OHPM surfaces; there is no source
   checkout or Maven Local fallback **for those Kuikly coordinates** in the
   release lane. Full 920-object readback remains an explicit audit/recovery
   mode, not an ordinary build step. This Kuikly/Hands rule is enforced by
   `exclusiveContent` routing `com.tencent.kuikly-open` and `build.hands` to
   Raft Artifacts. Declaring `mavenLocal()` in Gradle repository lists is not
   by itself a blocker; it remains available for unrelated local-dev and
   shared-fork paths. Fail closed only when a pinned Kuikly/Hands coordinate
   would resolve from mavenLocal, a gitlink, a local Pod/HAR, or a source
   build.
5. The OHOS Hands dependency is complete and immutable. For the current public
   OHPM package mode, `oh-package.json5` must use an exact version, the lock
   must bind that version to the official `ohpm.openharmony.cn` HAR with a
   complete SHA-512 integrity value, and preflight downloads the pinned bytes
   to verify both integrity and the archive's `@botiverse/hands` name/version.
   Historical release branches that still use `file:./libs/hands.har` remain
   supported only when the local HAR is readable and its metadata agrees with
   the exact local manifest/lock record.
6. The candidate versionCode — computed from the allocated dispatch inputs,
   not from source-tree placeholders — is strictly greater than the active
   Hands version returned by the public update endpoint for the selected
   channel and Android architecture.

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

scripts/release_android_hands_github.sh \
  --version 1.2.2 \
  --build 0 \
  --channel main \
  --variant release \
  --required-pr 501 \
  --required-pr 505 \
  --preflight-report artifacts/release-preflight/1.2.2-1020200.md \
  --wait
```

Every manual release dispatch uploads the signed carrier and creates a Hands
draft. The canonical wrapper therefore has no skip-draft switch. Reusable QA
callers remain build-only by explicitly setting `create_hands_draft: false`;
they cannot enter the manual draft job. See [Android Hands release input
migration](android-hands-release-migration-2026-07-24.md).

As soon as this draft (or any sibling iOS/OHOS draft for the same train) is
dispatched, schedule a Raft interval reminder every 10 minutes on the release
thread. Each firing fresh-reads the GitHub run, Hands draft, and artifact
identity work still in flight, then posts status. Cancel it only when the
train is terminal or the owner stops it. Starting a release without this
reminder is a process defect. The reminder does not authorize activation.

Release smoke is not the QA Acceptance default (login + home + one channel).
Before activation, the exact APK (`versionName` + `versionCode` + SHA-256)
must run image picker → Done → composer preview → send → row readback, plus
oversize sibling admission. Identity receipts do not transfer across
versionCodes. See [Mobile Paired Release SOP](mobile-paired-release-sop.md)
section 6. A testbed case list is a dispatch list; it is not auto-executed.
A hotfix may skip the full matrix only as `NOT RUN · hotfix waiver` with owner,
scope, compensating check, and follow-up date. Identity receipts are not
waivable.

Android 1.8 production dispatches default to `dex_packaging=compressed` and
`jni_packaging=current`. The DEX choice is admitted only with the 45 MiB APK
cap, 0.60 aggregate DEX compression-ratio cap, 16 KB ELF/ZIP admission,
signing, install, and functional gates. `current|mmap` DEX and `legacy|modern`
JNI remain explicit diagnostic choices; they are not alternate production
recommendations. R8/resource shrinking remains off, no Baseline Profile is
added, and performance was not specially benchmarked for this release.

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
then create reviewed notes in three files. Do not paste raw commit subjects into
the release.

```text
change-logs/1.2.2/android/zh-CN.md
change-logs/1.2.2/android/en.md
change-logs/1.2.2/android/ja.md
```

The Android workflow requires all three files in this platform directory. It
falls back to the historical `change-logs/<version>/{zh-CN,en}.md` layout only
for older releases that predate platform-specific notes; a legacy Japanese file
is included when present.

Apply the house format from the release-practices document:

- user-visible changes only;
- sectioned New / Improved / Fixed content, omitting empty sections;
- zh-CN, English, and Japanese carry the same claims;
- align every shipped locale with its current canonical catalog before review.
  Shared Web/mobile terms come from `botiverse/slock` under
  `packages/web/src/i18n/messages/`; mobile-only terms and locales also use
  `compose/shared/src/commonMain/resources/MR/<locale>/strings.xml`. Reuse
  user-visible nouns and status labels; do not expose implementation terms such
  as chip or spinner unless the product itself displays them. Current zh-CN
  terms include `回应`, `任务状态`, and `处理中`; current Japanese terms
  include `リアクション`, `タスクステータス`, and `処理中`;
- exclude PR numbers, implementation detail, CI history, and unshipped work.

The release owner must approve these files before activation.

## 5. Smoke The Draft

Smoke is a **hard gate** before activation. Record the draft release id, share
URL, APK SHA-256, source commit, device or emulator, and the smoke manifest
revision used. The QA Acceptance default goal (login + home + one channel) is
**not** release smoke; a login/home pass must never be recorded as a complete
smoke pass.

### 5.1 Carrier identity binding (not waivable)

Every identity and functional receipt is bound to the exact carrier. A PASS on
one versionCode does not transfer to a later build of the same marketing
version. For each APK under test record and reconcile five points so the bytes
the owner approved are the bytes the device ran:

1. Announced SHA-256 (this SOP's publish step);
2. Independent re-download from the Hands share `/download` link;
3. Bytes after `adb push` onto the device (re-hash, do not trust the push
   exit code — silent truncation has shipped a half APK before);
4. `/data/app` readback of the package Android actually installed;
5. `dumpsys package` readback of `versionCode` / `versionName` /
   `firstInstallTime` / `lastUpdateTime`.

Bind every receipt to `versionName` + `versionCode` + source commit +
SHA-256 + signer digests + CI run id. An upgrade cell must additionally show
`firstInstallTime` unchanged and `lastUpdateTime` advanced.

**No-adb identity surface.** The release owner does not have adb. To verify the
installed build without `dumpsys`, the installed `versionName` carries the
source commit as SemVer build metadata (`1.12.0+<short-sha>`, appended by
`compose/androidApp/build.gradle.kts`) and is shown under Android Settings →
Apps → Raft → App details. A "clean" release means no pre-release suffix
(`1.12.0`, not `1.12.0-rc1`); the `+<short-sha>` part is always present on a
release build. An agent/QA seat with adb still
reconciles `versionCode` + SHA-256 + signers per the five-point chain above;
the owner-visible `versionName` is the cross-check that the UI reports the same
train. Never let a `versionName`-only reading stand in for the `versionCode`/
SHA-256 half of the binding.

### 5.2 The 46-cell smoke checklist

Freeze a case manifest and revision per train; each cell needs a terminal
assertion and one of `PASS` / `FAIL` / `NOT RUN` / `NOT PROVIDED` /
`NOT COVERED` / undecided — never an inferred PASS. The checklist is grouped so
a carrier or seat limit lands on a named cell, not on the whole gate. Cells
the build or device structurally cannot run are `NOT COVERED` with the seat
limitation written down, not silently dropped.

- **Identity & install (8):** announced-vs-downloaded SHA-256 equality; push
  byte-size + double-hash; `/data/app` readback equality; `versionCode`/
  `versionName` readback; signer-digest continuity (v2/v3 + v3.1 lineage);
  fresh install launches; in-place upgrade preserves `firstInstallTime` and
  advances `lastUpdateTime`; `debuggable=false` (release lineage, not a debug
  build).
- **Session & auth (6):** cold start reaches expected session state; login
  persists across force-stop/relaunch; logout revokes the token server-side
  (control-arm verified — a stale token must 401 while a same-moment other
  freshly minted token stays 200 — the positive arm proves the 401 is
  discriminative, not an auth-plane outage); no orphaned session after
  upgrade; auth-resume lands on
  the right surface; server selection preserved.
- **Core surfaces (12):** Home renders; channel list renders; open a channel;
  thread/timeline renders with history; composer opens; send a message and
  read it back on the row; receive a realtime message; Activity/notification
  surface; search; saved/pinned; settings pages render; scroll a long list
  without dropped tiles or blank cells.
- **Attachments & upload (8):** image picker → Done → composer preview → send
  → server/row readback (`select-and-upload-image`); upload size limits are
  readable; an oversized sibling is dropped while remaining files upload;
  file-attachment path is a separate cell (an image PASS does not cover it);
  avatar loads in list + on viewport scroll; download/save an attachment;
  attachment thumbnail/preview; retry after injected network failure.
- **Stability & performance (8):** cold-start latency recorded as a
  `min/median/max` range over ≥5 samples (never a single point); four core
  pages render within the recorded magnitude band; process `pid` stable across
  the run; zero `FATAL`/ANR; no crash on upgrade path; background→foreground
  resume repaints correctly; memory/battery within the recorded band; no
  watchdog/hang on a long scroll.
- **Isolation & negative cells (4):** draft stays isolated from the public
  `main` update endpoint until activation; a device already at the candidate
  versionCode reports no further update; a non-member/random principal gets a
  negative resolver; control-arm sanity (a deliberately wrong token/input
  fails) so the probes have discriminative power.

For a risky release, add the targeted regression and a fresh performance/visual
artifact before asking for activation.

### 5.3 Upload path

The Hands upload chain is part of the gate, not an afterthought: the signed
`androidApp-<variant>.apk` (never the misleading `-unsigned` intermediate),
metadata, and native symbols must land on the same draft. The draft's recorded
SHA-256, the announced APK SHA-256, and the §5.1 point-2 re-downloaded bytes
are one and the same check — not three requirements. A share page is optional
for smoke; the release must never be activated off an unverified upload. The
`/download` link must resolve to the exact carrier bytes.

### 5.4 Waiver

A **hotfix** train may skip the full matrix only with a recorded owner
exception: `release_mode=hotfix`, waiver owner, scope, expiry/follow-up date,
skipped cells, compensating checks on the affected path, and risk. Skipped
cells are `NOT RUN · hotfix waiver`, never PASS. The §5.1 carrier-identity
binding and the §5.3 upload path are **not** waivable, and an affected core
cell on the hotfix path is not silently skipped.

## 6. Activate Only On Explicit Approval

Once the owner has reviewed the notes and smoke evidence and explicitly asked
to publish, run the finalizer. It verifies the supplied draft reports the
expected versionCode, writes the three reviewed locales, activates that draft, and
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
falls back to `@botiverse/hands-cli@0.5.17` from the official npm registry when
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
  --ja-notes change-logs/1.2.2/android/ja.md \
  --confirm-publish
```

`--confirm-publish` is deliberately mandatory. It is an operator assertion
that the owner has given the release go; it is not a replacement for that go.

After the public verification passes, create and push the annotated `vX.Y.Z`
git audit tag. This is required, not optional (artin, 2026-09-27). The tag
must point at the verified source commit, its message should record the
versionCode, Hands release id, APK SHA-256 and CI run, and it must never be
used to re-trigger the Android release workflow. iOS uses a separate platform- and build-qualified audit tag because
its marketing version can stay fixed across TestFlight builds; follow
[iOS Hands Release Practices](ios-hands-release-practices-2026-07-09.md#post-delivery-git-audit-tag)
instead of reusing an Android `vX.Y.Z` tag.

## 7. Merge The Released Branch Back Into Main

Do not merge the release branch into `main` before publication. The successful
Hands release is the gate that turns the stabilization branch into shipped
history.

After activation and public endpoint verification:

1. Record the released source commit, versionCode, Hands release id, APK
   SHA-256, approved notes, smoke evidence, and the `vX.Y.Z` audit tag.
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


## AAB upload signing and signing-only repair

`bundleRelease` does not sign this repository's AAB. The release workflow signs
it with the existing `SLOCK_ROTATED_*` keystore secrets before immutable Hands
upload. `scripts/verify_android_aab.sh` verifies every application entry, checks
the certificate SHA-256, and runs `jarsigner -verify -strict` using the pinned
public leaf as its trust anchor. The uploader repeats this admission check before
its first network request. The AAB receipt, rather than the APK rotation lineage,
supplies `upload_key_cert_sha256`.

The expected key is the currently declared upload/rotated key
`5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1`.
A valid signature with this key does not prove that Google Play accepts it;
confirm the Play upload certificate or retain the store's actual upload receipt.

For an already sealed unsigned AAB, dispatch the existing `android-release.yml`
from a reviewed signing-workflow ref with the original product `source_sha` and
`aab_signing_repair` set to JSON containing exactly four string fields:

```json
{
  "hands_build_id": "<original immutable Hands build ID>",
  "source_run_id": "<original successful Android release run ID>",
  "aab_sha256": "<independently checked original AAB SHA-256>",
  "apk_sha256": "<independently checked original APK SHA-256>"
}
```

This mode skips the normal resolve, Gradle build and draft jobs. It checks the
original GitHub run and sealed Hands receipt, downloads with the existing Hands
secret, verifies both input hashes and sizes, and signs only the AAB. The helper
compares every non-signature ZIP entry with the original AAB. The unchanged APK,
signed AAB and provenance JSON are uploaded as a new GitHub artifact. The receipt
keeps original app source/run separate from the signing workflow SHA/run and
records `rebuilt: false`. It never alters the original sealed build, creates a
Hands draft, or publishes to Google Play.

Hands permits only one immutable AAB/APK bundle per app, package name and
`versionCode`. A sealed bundle cannot be replaced or recreated with newly signed
bytes under that same identity. `sign_existing_aab` produces verifiable signed
files only; it cannot write them back to an existing sealed bundle. If an
unsigned AAB has already been sealed, increase `versionCode` and use the normal
release build workflow to create and verify a new signed AAB/APK pair for
publication. Preserve the original bundle as failed release evidence.

Focused validation (JDK 17+, Python 3, no Gradle):

```bash
python3 scripts/test_android_aab_signing.py
python3 scripts/test_download_android_aab_repair.py
python3 scripts/test_upload_android_hands_artifacts.py
scripts/test_android_hands_release_contract.sh
```
