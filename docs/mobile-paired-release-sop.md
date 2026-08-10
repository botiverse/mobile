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
  -f release_mode=draft \
  -f publish_hands=true
```

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

Then run the plan's acceptance matrix. At minimum cover fresh install, supported
upgrade paths, authentication/session, Home/Activity/Thread, attachments,
composer/IME, crash scans, and every accepted changeset. Mark unrun cases
`NOT RUN`; never infer physical-device behavior from Simulator or compile.

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
| TestFlight upload | Upload verified Hands IPA | Yes |
| TestFlight internal | Assign named internal group | Yes |
| TestFlight external | Assign named group, What to Test, Beta Review, notification | Yes |
| App Store production | Submit/publish production version | Yes |
| Community | Post approved announcement | Yes |

Immediately before every mutation, fresh-read the authoritative state and the
latest owner directive. Use exact revision, scope, build, group, and source
preconditions. Read back terminal state afterward.

Android and iOS do not need the same delivery mechanism. A valid paired train
may end with Android Hands active and iOS Hands draft while the same IPA is in
named TestFlight groups. The receipt must state that intent explicitly.

## 9. Announce Only A Reachable Build

Store approved community copy under:

```text
change-logs/X.Y.Z/community-announcement.zh-CN.md
change-logs/X.Y.Z/community-announcement.en.md
```

Announce only platforms that reached their intended community boundary. For
iOS TestFlight, fresh-read the exact build and audience, include marketing
version/build number and the current verified join URL, and state that it is a
TestFlight beta rather than an App Store production release. Do not expose
internal tasks, CI detail, or unshipped work. Record the final message ids.

## 10. Handle Post-Distribution Defects Honestly

When a shipped or distributed carrier produces a material regression:

1. Bind the report to exact artifact bytes, source marker, and binary UUIDs.
2. Mark the affected product claim `RED` or `UNKNOWN`; do not rewrite the
   original packaging evidence.
3. Record the canonical incident and successor owner in the receipt.
4. Do not expand rollout while a live safety hold applies.
5. A containment guard is not a root-cause fix unless the causal failure and
   the corrected contract are proven.

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
