# Mobile Release Train SOP

Use this procedure when Mobile ships Android and iOS from one release train.
Platform-specific signing and build rules remain in
[Android Release SOP](android-release-sop.md) and
[iOS Hands Release Practices](ios-hands-release-practices-2026-07-09.md).
This document owns scope selection, branch policy, cross-platform ordering,
authorization boundaries, receipts, and merge-back.

The operating model is **fixed date and quality, variable scope**. A feature
that misses the evidence cutoff leaves the train; the train does not lower its
quality gate or silently move its date to keep that feature.

## Release closeout and Notion ledger

The first release operation, before branch creation, freeze or CI dispatch,
is to create the version instance in
[Mobile Release History](https://app.notion.com/p/36c5aa6ce9a74939b56c5913dbc94075)
from its Mobile Release SOP template. Record owner, scope, branch and exact
source; maintain platform build, QA, approval and distribution states during
execution. A chat receipt does not replace this ledger. If a record is added
late, label it retrospective; never mark missing pre-release steps complete.
After an authorized release, update the ledger and publish the installation
link, platform availability and unresolved limitations in `#raft-mobile`.
Internal TestFlight, external review pending and public availability are
separate states.

When the owner closes a version, stop both merges and new builds for that
version. Reconcile accepted release-only fixes into `main` through reviewed
PRs, then close redundant backports. Protect the closed release branch with
an active exact-ref update/deletion/non-fast-forward restriction and no bypass
actors. Record the rule, accountable owner and explicit revocation condition;
reopening requires a new owner directive. Preserve the branch and artifacts.
For the next version, select a reviewed `main` commit and create a new release
branch at that time; do not reuse the closed branch. Existing store-review
follow-up remains separate from building a new carrier, and open defects stay
open until the next candidate passes the required product verification.

Current owner decision (artin, 2026-09-28 17:22–17:24 UTC):
`release/1.12.0` is closed to merges and new builds. All subsequent fixes go to
`main`; the next version is `1.12.1`, with `release/1.12.1` created when its
candidate is selected. GitHub ruleset `24130656` locks the old branch until
explicitly revoked by the release owner. Android `11200904` remains a test
draft and is no longer awaiting publication. The existing slash-named
branches above are the owner's explicit naming convention for these trains.

## Roles

- **Release owner** sets the date, delivery intent, scope, and exceptions.
- **Release operator** maintains the plan, freezes exact source, builds drafts,
  verifies bytes, executes only authorized mutations, and writes the receipt.
- **Feature owner** supplies a scoped PR, changeset, tests, review, dependencies,
  rollout plan, and targeted product evidence.
- **Independent reviewer** audits the exact source or receipt without inheriting
  the feature owner's correctness judgment.

One person may hold multiple roles, but source author and independent reviewer
must remain separate for the exact being reviewed.

## Non-Negotiable Rules

1. Every semantic version gets its own immutable stabilization branch named
   `release-X.Y.Z`. A patch release such as `1.9.1` uses
   `release-1.9.1`, even when it branches from `release-1.9.0`. Reusing a
   family branch requires a recorded owner exception in the release plan and
   receipt; it is never the default.
2. Android and iOS carriers for one paired train use one full 40-character
   **carrier source exact**. Platform marketing versions and build numbers may
   differ, but both artifacts must embed that same source.
3. Every source change on the release branch creates a new candidate. All
   earlier artifacts, hashes, runtime results, notes readbacks, and source
   approvals become stale. Rebuild with strictly higher platform coordinates.
4. CI may build, sign, upload, and create Hands drafts. Hands activation,
   TestFlight upload, internal distribution, external distribution, Beta App
   Review, tester notification, App Store production, and announcements are
   separate mutations. Authorization for one never implies another.
5. Release notes come from reviewed versioned files, not commit subjects.
6. A repository closeout may record an open product defect. It must not rename
   a source, packaging, or merge-back PASS into a product PASS.
7. Private fork dependencies used by a carrier come from an immutable Raft
   Artifacts release set. Record the exact version, source commit/tree/tag,
   complete manifest, and per-artifact bytes and SHA-256. A missing manifest,
   incomplete platform set, hash mismatch, version range, `latest`, or silent
   fallback of those pinned coordinates to a gitlink, `mavenLocal`, local pod,
   HAR, or source build blocks the candidate. A source checkout may remain as
   a provenance anchor, but it is not a production dependency fallback.
   Kuikly (`com.tencent.kuikly-open`) and Hands (`build.hands`) are routed
   with Gradle `exclusiveContent` to Raft Artifacts, so they cannot fall
   through to `mavenLocal()`. Declaring `mavenLocal()` in repository lists is
   therefore not a preflight blocker. Other shared forks keep their own
   provenance rules and are not covered by deleting that repository entry.
8. Platform versionName / versionCode / CFBundleVersion values are **CI
   dispatch inputs**. Frozen source may keep placeholder defaults
   (`1.0.0` / `1000000` on Android and OHOS; iOS `MARKETING_VERSION=1.0` /
   `CURRENT_PROJECT_VERSION=1`). Do not rewrite those placeholders into the
   freeze tree, and do not fail preflight merely because the source defaults
   are still present. The version gate is: allocate monotonic coordinates,
   inject them at workflow dispatch, then verify the signed artifacts. Android,
   OHOS and iOS all use the train semantic version (artin, 2026-09-27: iOS
   1.12.0 ships as marketing version `1.12.0` build 1, not `1.0.0`). iOS
   dispatches `version_major/minor/patch` = the train version and
   `version_build` restarting at 1 per marketing version; the resulting
   `CFBundleVersion` comes from `scripts/ci/version_code.sh` and stays
   monotonic across trains.
9. TestFlight is **internal first, external only after artin has tested**
   (artin, 2026-09-27). When the release owner says **publish iOS** or
   **publish to TestFlight** without naming an audience, the contract is:
   upload the verified Hands IPA, assign it to the named internal group
   (Botiverse `e10b6b3e-000d-4cbe-a160-2a9275f76977`) with What to Test,
   fresh-read `internal_build_state=IN_BETA_TESTING`, then report to artin
   and **stop**. External groups (What to Test, Beta App Review, tester
   notification) require a separate, explicit go from artin given after he
   has tested that exact build; an earlier publish instruction never carries
   over to external. Once external is authorized, that contract ends only
   after the selected external testers have actually been notified; upload
   completion, ASC `VALID`, group assignment, What to Test, Beta App Review
   approval, or notification eligibility are intermediate states. A narrower
   instruction such as `upload only` or `do not notify` still overrides and
   must be repeated in the receipt.
9a. A standing owner directive may make **TestFlight internal** the default
    after every successful iOS Hands draft. Record the named internal group
    in the release plan. Each later iOS rebuild on that train is uploaded and
    assigned to that internal group without a per-build go. This still does
    not authorize Insiders, external testers, App Store production, Hands
    activation, or a community announcement. For internal groups, Hands
    `notify_testers` is `NOTIFY_EXTERNAL_ONLY`; completion is
    `processing_state=VALID`, the named internal group assigned, and
    `internal_build_state=IN_BETA_TESTING` (Apple `auto_notify_enabled` may
    be the only notify path).
10. Starting a release train requires a Raft **interval reminder**. As soon as
    the first platform draft is dispatched, the operator schedules a recurring
    10-minute reminder on the release thread. Each firing must fresh-read every
    in-flight Android, iOS, and OHOS run, Hands draft/readback, and any
    TestFlight processing state, then post a short status in that thread.
    Cancel the reminder only after every dispatched platform is terminal and
    the receipt is written, or the owner explicitly stops the train. A release
    with in-flight carriers and no interval reminder is a process defect.

## 1. Open The Release Plan

Before creating a release branch, add:

```text
change-logs/X.Y.Z/release-plan.md
```

The plan is the train's scope authority. It records:

- target release date and timezone;
- reservation cutoff, scope lock, branch cut, and release-candidate dates;
- Android/iOS/OHOS delivery intent and the maximum authorized boundary;
- candidate PR or changeset, feature owner, support owner, dependencies,
  platforms, rollout, risk, and required evidence;
- current state: `proposed`, `reserved`, `accepted`, `removed`, or `shipped`;
- every exception, accountable approver, expiry, and lift condition.

No reservation means no release inclusion. `reserved` means capacity is held,
not that the feature is ready. Only `accepted` items may be selected at scope
lock. Emergency fixes may enter after cutoff only through a recorded exception
with owner, evidence, risk, and rollback.

The release owner must fill real calendar dates before branch creation. A
relative `T-5` schedule is a planning aid, not a substitute for a date.

## 2. Require A Changeset For Every User-Visible Candidate

Each candidate adds:

```text
change-logs/X.Y.Z/changesets/<kebab-name>.md
```

Front matter includes `type`, `area`, `platforms`, `pull_request`, and
`rollout`. The body contains equivalent `zh-CN` and `en` user behavior; add
other locales when required. Do not include implementation detail, commit ids,
test narration, or unshipped work.

A PR may land on `main` without being accepted for the train, but the release
plan must account for any user-visible behavior already present in the branch
cut candidate. Missing changesets are a scope-lock blocker, not an invitation
to reconstruct notes from git subjects at build time.

## 3. Select And Freeze The Branch Point

At scope lock:

1. Remove candidates that lack terminal exact-head checks, independent review,
   required product evidence, dependencies, or a complete changeset.
2. Record the accepted PRs and their merge commits in the plan.
3. Choose a reviewed commit reachable from `origin/main`, or from the previous
   patch branch when creating a patch train.
4. Create the dedicated branch:

```bash
scripts/create_android_release_branch.sh \
  --version X.Y.Z \
  --candidate <reviewed-40-character-commit>
```

The helper must fail if the remote branch already exists at another commit.
Never force-push, rewind, or repurpose a release branch.

After branch cut:

- new product work continues on `main`;
- a release blocker branches from `origin/release-X.Y.Z` and targets that
  release branch;
- do not merge `main` into release to collect unrelated work;
- bring in one reviewed blocker and its strict dependencies per PR;
- update the release plan whenever scope changes.

## 4. Aggregate And Review Platform Notes

After scope is accepted, aggregate changesets into all three reviewed locales:

```text
change-logs/X.Y.Z/android/{zh-CN,en,ja}.md
change-logs/X.Y.Z/ios/{zh-CN,en,ja}.md
```

Platform notes contain only behavior shipped on that platform. Claims and
section counts must be semantically equivalent across locales. Git history may
help find omissions but may not be published as notes.

Android's semantic version normally equals `X.Y.Z`. iOS may keep a separate
marketing version, so iOS dispatch must pass `release_notes_version=X.Y.Z`.
An incomplete reviewed locale set fails closed; generated history is allowed
only when no reviewed locale file exists, and is not acceptable for a planned
production train.

## 5. Build Drafts From One Exact Source

Start from a clean checkout equal to `origin/release-X.Y.Z`. Record branch,
carrier source exact, tree, required PRs, and terminal exact-source checks.

Before dispatch, allocate and record each platform's version coordinates as
workflow inputs. Source-tree placeholders are expected. Then resolve the
plan's Raft Artifacts Kuikly/Hands coordinates from an empty cache using only
that exact manifest. Require the resolved Android/KMP, Apple, OHOS, plugin,
and renderer set selected by the plan to be complete and byte-identical to
that manifest. Record the dependency release version, source exact, manifest
digest, and resolved artifact digests in the candidate receipt. Publishing
the dependency set and consuming it are separate gates; neither is inferred
from a source merge. An empty-cache proof is for those pinned coordinates,
not a requirement to delete `mavenLocal()` from Gradle settings.

When the first platform draft is dispatched, schedule the 10-minute Raft
interval reminder on the release thread before walking away. Keep it running
until all dispatched platforms are terminal. The reminder does not authorize
activation.

Android:

```bash
export SLOCK_RELEASE_BRANCH=release-X.Y.Z

scripts/release_android_hands_github.sh \
  --version X.Y.Z \
  --build <android-build-component> \
  --channel main \
  --variant release \
  --required-pr <number> \
  --wait
```

iOS on the same remote ref:

```bash
gh workflow run ios-release.yml \
  --repo botiverse/mobile \
  --ref release-X.Y.Z \
  -f version_major=<ios-major> \
  -f version_minor=<ios-minor> \
  -f version_patch=<ios-patch> \
  -f version_build=<ios-build-component> \
  -f version_suffix= \
  -f release_notes_version=X.Y.Z \
  -f hands_channel=main \
  -f export_method=app-store-connect \
  -f release_mode=draft
```

For the Google Play carrier, use the same exact release-ref checkout and
source identity but dispatch `android_variant=play` with
`upload_android_artifacts=true`. The workflow retains the signed AAB and
matching Play APK and performs CI verification; it does not create a Hands
release or submit to Google Play automatically. After the artifact and smoke
gates pass, use the verified Hands Google Play binding and app `publisher`
role: declare/upload/complete the AAB+APK pair, create a Hands draft, attach a
passing acceptance receipt to the exact AAB, then run
`hands releases play-promote <app> <release> --track closed --note ...` (or the
equivalent `/distributions/play/promote` API). Read back the Play receipt/edit,
package, versionCode, provider track, AAB SHA, source SHA, CI run, and review
state. `closed` resolves through the binding to the configured closed-test
track; keep store upload, closed-test submission, production rollout, and
Hands activation separately approved. A missing binding or failed-closed
operation is recorded as `AAB ready / Play upload NOT RUN` or the exact failed
receipt; a green CI run, GitHub artifact, or Hands draft never substitutes for
a Play receipt.

Draft creation does not authorize activation or distribution. If source drifts
after either dispatch, supersede both platform carriers and raise both build
coordinates before rebuilding.

## 6. Verify Stored Bytes And Candidate Behavior

For Android, record and independently verify:

- run and artifact ids, APK and symbols size/SHA-256;
- package, version, min/target SDK, signer lineage, v2/v3/v3.1;
- `zipalign -P 16`, compressed DEX admission, every 64-bit ELF at 16 KiB;
- expected native Build IDs;
- byte equality between GitHub and Hands installable/symbol assets.

For iOS, record and independently verify:

- run and IPA/dSYM artifact ids, size/SHA-256;
- bundle id, marketing version, build number, and minimum iOS;
- strict codesign, distribution authority, team, profile, and production
  entitlements;
- app binary UUID equals dSYM UUID;
- byte equality between GitHub and Hands IPA/dSYM assets.

Then run **release smoke**. The QA Acceptance workflow default goal is login +
home channels + open one channel. That default is **not** release smoke. A
login/home pass must never be recorded as a complete smoke pass.

Release smoke is a hard gate before activation:

1. Bind every identity and functional receipt to `versionName` + `versionCode` +
   source commit + artifact SHA-256 + signature + CI/run id. A PASS on one
   versionCode does not transfer to a later build of the same marketing version.
2. Freeze a case manifest/revision. Each case needs a terminal assertion.
   Testbed's case library is a dispatch list, not an automated suite.
   `testbed run` creates a pending ticket; a device seat or CI agent still has
   to execute it. Unrun, un-dispatched, or harness-blocked core cases are
   `NOT RUN` or blocked — they do not aggregate into PASS.
3. Required every train, on the exact release APK/IPA/HAP: image
   picker → Done → composer preview → send → server or message-row readback
   (`select-and-upload-image` or the platform equivalent). Also prove upload
   size limits are readable and that an oversized sibling is dropped while
   remaining files still upload. A file-attachment path is separate; an image
   PASS does not cover it.
4. Keep platform/carrier grids separate (Android cloud device, iOS Simulator,
   OHOS emulator, physical device). Do not infer across them. Record
   preconditions, duration, fixture/device capability, and failure class.

A broader matrix (fresh install, upgrades, auth, Home/Activity/Thread,
composer/IME, crash scans, accepted changesets) remains in the plan. Until a
named seat has timed one image-upload case on the release carrier, do not treat
the full testbed table as a hard time-box. Physical OHOS stays `NOT RUN` unless
a physical seat exists.

A **hotfix** train may skip the full smoke matrix only with a recorded owner
exception. Record `release_mode=hotfix`, waiver owner, scope, expiry or
follow-up date, skipped cases, compensating checks on the affected path, and
risk. Skipped cases are `NOT RUN · hotfix waiver`. Never record a waiver as
PASS. Identity/signature receipts on the exact versionCode + SHA-256 are
**not** waivable. An affected core path (for example image upload on an upload
hotfix) is not silently skipped.

## 7. Apply Notes With Revision Preconditions

For each Hands draft:

1. Fresh-read app, build, release, source, status, revision, scope, and assets.
2. Update `zh-CN`, `en`, and `ja` notes using that exact revision.
3. Fresh-read again and require byte equality with the reviewed files,
   unchanged source/scope, and `status=draft`.

A metadata row is not byte verification. Download and hash both stores.

## 8. Execute Only The Planned Delivery Boundary

The release plan names each platform's intended boundary. Examples:

| Boundary | Mutation | Separate authorization required |
| --- | --- | --- |
| Android Hands | Activate exact draft and scope | Yes |
| iOS Hands | Activate exact draft | Yes |
| TestFlight upload | Upload verified Hands IPA | Yes, unless the plan has standing internal-after-draft |
| TestFlight internal | Assign named internal group | Yes, unless the plan has standing internal-after-draft for that named group |
| TestFlight external | Assign named group, What to Test, Beta Review, notification | Yes — only after artin has tested the exact build internally |
| App Store production | Submit/publish production version | Yes |
| Community | Post approved announcement | Yes |

Immediately before every mutation, fresh-read the authoritative state and the
latest owner directive. Use exact revision, scope, build, group, and source
preconditions. Read back terminal state afterward.

### TestFlight completion states

Use the following vocabulary in plans, updates, and receipts:

| Observed state | Allowed wording | Publish complete? |
| --- | --- | --- |
| Build Upload `COMPLETE`, ASC build `VALID` | uploaded and processed | No |
| Named groups assigned | assigned to groups | No |
| External state `BETA_APPROVED` | approved, awaiting tester notification | No |
| `auto_notify_enabled=true` before approval | notification scheduled after approval | No, until the build reaches testing |
| Publish response `notification=sent` or `already_sent`, followed by external state `IN_BETA_TESTING` | testers notified / available in TestFlight | Yes |

`auto_notify_enabled=false` is not proof that no notification has ever been
sent: after approval Hands can use Apple's manual build-beta-notification
endpoint. Conversely, `BETA_APPROVED`, assigned groups, or a public-link-enabled
group is not proof that testers were notified. For an already-approved build,
the release operator must execute the exact external publish with
`notify_testers=true` (CLI: `--notify-testers`) and preserve the returned
`notification=sent|already_sent` operation receipt. Run that mutating command
without `--wait`: the current CLI's `--wait --json` output is the later GET
status and does not preserve the POST operation receipt. Then use the separate
read-only `testflight-status` command in a bounded recheck to fresh-read the
exact build and require `external_build_state=IN_BETA_TESTING`. If either
receipt is missing after the bounded recheck, report `notification unverified`;
do not say `published`, `sent`, or `available`.

Treat the complete `notification` enum explicitly: `not_requested` means no
notification was requested, `scheduled` means notification is pending, `sent`
means the notification operation succeeded, and `already_sent` is the
idempotent-success result. Only the last two can satisfy the operation-receipt
half of the completion contract. If the first receipt is `scheduled`, wait for
the exact build to reach `IN_BETA_TESTING`, then replay the same external
publish without `--wait`, preserve its `already_sent` receipt, and fresh-read
the exact status once more before reporting completion.

Internal groups and external groups are separate audiences. The release plan
must name which audience the owner's publish instruction covers. If it says
only `publish iOS/TestFlight`, the Raft Mobile default is the named internal
group only (rule 9); do not promote to external until artin has tested the
exact build and explicitly approves external distribution. App Store
production remains a separate mutation and is never implied by TestFlight
publication.

Android and iOS do not need the same delivery mechanism. A valid paired train
may end with Android Hands active and iOS Hands draft while the same IPA is in
named TestFlight groups. The receipt must state that intent explicitly.

## 9. Announce Only A Reachable Build

Store approved community copy under:

```text
change-logs/X.Y.Z/android/zh-CN.md
change-logs/X.Y.Z/android/en.md
change-logs/X.Y.Z/android/ja.md
change-logs/X.Y.Z/ios/zh-CN.md
change-logs/X.Y.Z/ios/en.md
change-logs/X.Y.Z/ios/ja.md
```

Announce only platforms that reached their intended community boundary. For
iOS TestFlight, fresh-read the exact build and audience, include marketing
version/build number and the current verified join URL, and state that it is a
TestFlight beta rather than an App Store production release. Do not expose
internal tasks, CI detail, or unshipped work. Record the final message ids.

### Fixed `#raft-mobile` announcement templates

The four templates below preserve the release owner's selected historical
Android and iOS Chinese/English public messages as the canonical channel
presentation format. The templates own titles, introductory sentences, link
labels, closing sentences, section labels and icons, section order, and
platform-specific delivery wording. The platform locale files own the reviewed
user-visible bullet text and its order; their Hands-specific section icons are
not channel presentation authority. Replace only bracketed placeholders and
the reviewed bullet text. Do not substitute a Hands/CI receipt, release ID,
artifact hash, rollout percentage, internal gate, or engineering status report
for these announcements.

Android Chinese:

```markdown
# Raft Android [marketing version] 已发布

Raft Android [marketing version] 现已上线。已经安装 Raft 的用户可以在「设置 → 关于 → 检查更新」中直接升级。

- 下载更新：<[verified Hands share URL]>

✨ **新功能**

- [reviewed user-visible item]

🔧 **改进**

- [reviewed user-visible item]

🐛 **修复**

- [reviewed user-visible item]

遇到问题，欢迎通过「设置 → 发送反馈」告诉我们。感谢大家一直以来的使用与反馈。
```

Android English:

```markdown
# Raft Android [marketing version] is now available

Raft Android [marketing version] is now available. If you already have Raft installed, update directly from Settings > About > Check for updates.

- Download: <[verified Hands share URL]>

✨ **New**

- [reviewed user-visible item]

🔧 **Improved**

- [reviewed user-visible item]

🐛 **Fixed**

- [reviewed user-visible item]

If you run into an issue, please use Settings > Send feedback. Thank you for using Raft and sharing your feedback.
```

iOS Chinese:

```markdown
📱 Raft iOS [marketing version] ([build number]) 已在 TestFlight 提供测试

本次更新包括：

- [reviewed user-visible item]

TestFlight：<[current verified join URL]>

说明：这是 TestFlight 测试版本，不是 App Store 正式发布。版本号以 TestFlight 中显示的 `[marketing version] ([build number])` 为准。
```

iOS English:

```markdown
📱 Raft iOS [marketing version] ([build number]) is now available on TestFlight

Highlights:

- [reviewed user-visible item]

TestFlight: <[current verified join URL]>

This is a TestFlight beta, not an App Store production release. The canonical TestFlight version is `[marketing version] ([build number])`.
```

For Android, map the reviewed New/Improved/Fixed bullet groups into the
template's `✨` / `🔧` / `🐛` sections. Omit a section only when its reviewed
locale file has no items in that category; never leave a placeholder or invent
filler. For iOS, remove only the locale file's section headings and place every
reviewed bullet in its original order into one flat list under
`本次更新包括：` / `Highlights:`. Do not select, summarize, drop, rewrite, or
reorder bullets during that transformation. A shorter highlights subset is a
new announcement artifact and requires its own explicit copy review. The
English `Highlights:` label is retained as historical template wording; it is
not an instruction to select a subset.

Publish Chinese and English as four separate top-level messages, one message
per platform and locale. Draft them from the platform's reviewed locale files,
then compare presentation against these templates and bullet text/order against
the locale files before posting.
The reviewed `ja.md` files remain the Japanese Hands release-note source; they
are not a fifth or sixth `#raft-mobile` announcement unless the release owner
explicitly requests Japanese channel posts and approves their templates.

## 10. Handle Post-Distribution Defects Honestly

When a shipped or distributed carrier produces a material regression:

1. Bind the report to exact artifact bytes, source marker, and binary UUIDs.
2. Mark the affected product claim `RED` or `UNKNOWN`; do not rewrite the
   original packaging evidence.
3. Record the canonical incident and successor owner in the receipt.
4. Do not expand rollout while a live safety hold applies.
5. A containment guard is not a root-cause fix unless the causal failure and
   the corrected contract are proven.

### Freeze and resume Hands updates

The release owner named in the release plan decides whether an incident
requires freezing the public update pointer. The release operator may execute
that decision only against a fresh-read exact release and revision. A freeze, rollback, share revocation,
release deletion, and removal of an installed build are different mutations;
authorization for one never implies another.

Hands owns the resolver and rollout mechanics. Follow the authoritative
[`Staged rollouts`](https://hands.build/docs/public-api-reference#staged-rollouts)
contract when setting rollout to zero or resuming distribution. Do not copy or
reinterpret those API semantics in this repository.

The Mobile incident/release receipt must record:

- the release-owner directive and exact affected release/source;
- release revision and rollout before and after the authorized mutation;
- the resolver, probe-identity, eligible-release, and share readbacks required
  by the authoritative Hands `Staged rollouts` contract;
- the incident owner, open blocker, and explicit lift condition.

Resuming distribution requires a new release-owner directive naming the target
release and rollout. Execute the resume and preserve its readbacks exactly as
required by the authoritative Hands `Staged rollouts` contract.

Repository closeout may continue so shipped source and evidence are durable,
but the receipt must preserve the open product blocker and its lift condition.

## 11. Write The Receipt And Merge Back

Create `change-logs/X.Y.Z/release-receipt.md` with:

- release plan and accepted/removed scope;
- carrier source/ref/tree and exact checks;
- workflow, artifact, build, release, asset, size, hash, signing, and UUID ids;
- Hands activation revision/scope and TestFlight/App Store terminal states;
- acceptance evidence, unrun scope, accepted residuals, and live blockers;
- announcements and superseded carriers;
- merge-back PR and landed main exact.

Do not advance the frozen release branch merely to add the post-release
receipt. Create an integration branch from fresh `origin/main`. Prefer a real
two-parent merge with `origin/release-X.Y.Z`, resolve conflicts by preserving
shipped fixes and intentional newer main work, and add the receipt on that
integration branch.

If the immutable protected release ancestry contains malformed commit metadata
that the repository's current sign-off policy intentionally refuses to attest,
do not weaken the checker, add a waiver, or rewrite shipped history. Use a
signed single-parent content integration from fresh `origin/main` instead. Its
receipt must freeze the release ref/source/tree, the failed two-parent attempt
and exact failing check/commit, the reviewed signed source when available, and
the reconciled path/blob result. Independent review must prove that the
single-parent integration carries the intended release content with no
unexplained drift. This exception is for immutable-history incompatibility,
not convenience.

A known-invalid release-only fix may be excluded from either merge result only
with the incident and successor recorded in the receipt.

Open the integration branch as a protected PR to `main`. Require exact Hosted,
independent review, conflict-marker scans, generated-resource checks, and
affected platform compile/tests. After merge, record the landed SHA in the
receipt through a final docs follow-up when necessary. Keep an immutable
carrier ref; delete only temporary worktrees, credentials, and verification
downloads.

## Abort Rules

- Missing real release date or unaccepted scope: do not cut the branch.
- Source drift: supersede and rebuild both platforms.
- Revision, scope, group, or source mismatch: stop and fresh-read.
- Artifact/hash/signing/UUID mismatch: stop; do not repair metadata around it.
- Apple processing not `VALID`: stop before distribution.
- Ambiguous authorization or unnamed audience: ask; do not infer.
- Live material product regression: stop rollout expansion and record the hold.
- Merge-back conflict marker or failed affected gate: fix in the integration
  PR before landing.
