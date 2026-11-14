# Mobile Release SOP

The one procedure for shipping Raft Mobile (Android on Hands and Google Play,
iOS on TestFlight and the App Store). Steps run in order. Every release has one
GitHub issue that is its plan and ledger.

The model is **fixed date and quality, variable scope**: a feature that misses
the cutoff leaves the train; the train does not lower its gate or slip its date
to keep it.

Skills that point here: `raft-release-versioning` (§2), `raft-release-changeset`
(§1, §4), `raft-android-release` (§5.1, §7), `raft-ios-release` (§5.2, §7).

## Roles

- **Release owner** (artin): date, scope, exceptions, and the calls kept
  below (rollback, data-changing, risky).
- **Mobile DRI**: release decisions, by delegation (below).
- **Release operator**: keeps the issue, cuts the branch, builds drafts,
  verifies bytes, runs only authorized mutations, writes receipts.
- **Feature owner**: scoped PR, changeset, tests, evidence.
- **Independent reviewer**: not the author of the exact source being reviewed.

### Release decisions (delegated, product owner decision 2026-11-14)

The release owner delegated release decisions to the mobile DRI (product owner
decision, 2026-11-14). In this SOP, a **release go** is the mobile DRI's go. It covers:

- Hands `main` activation;
- TestFlight external (Beta App Review, tester notification);
- App Store submission and release;
- Google Play promotion beyond internal, including production;
- release notes approval (§4) and announcements (§7.6).

The DRI gives a go only when all three hold, and writes them in the release
issue with the go:

1. **No new crashes** on the exact build (smoke, §6.2; Hands/Play/TestFlight
   crash reports for internal and preview).
2. **Related feedback fixed**: feedback this release claims to fix is fixed on
   the exact build, and no open feedback blocks it.
3. **Server dependencies live**: every server change the release relies on is
   deployed to production.

These still go to the **release owner**: rollback, freezing the update pointer, share
revocation, release deletion or removal (§7.7); anything that changes or
deletes user or store data (for example withdrawing an in-review Play change,
§7.3); scope exceptions; and any call the DRI judges risky or unclear. Record
the delegation in each release issue so operators and sub-agents don't stop to
wait for the release owner.

## Hard rules

These hold in every step. Each external mutation needs its own go; a go for one
never implies another.

1. **CI may build, sign, upload and create Hands drafts. Nothing else is
   implied.** Hands activation, Play track promotion beyond internal,
   TestFlight external, Beta App Review, tester notification, App Store
   submission and announcements are separate mutations, each needing an
   explicit release go (§Release decisions) for that exact build.
2. **No approval needed** for internal/preview channels: Hands `preview`, Play
   internal testing (release rule 2026-10-29), TestFlight internal (Botiverse
   group). Every external release (Hands `main`, Play closed/open/production,
   TestFlight external, App Store) needs a release go for that version and a
   smoke of the exact build first (artin, 2026-11-02; go delegated to the mobile
   DRI by product owner decision, 2026-11-14).
3. **One branch per semantic version**, never reused, force-pushed or rewound.
4. **One carrier source exact** (full 40-char SHA) for both platforms of a
   train. Marketing versions/build numbers may differ; embedded source may not.
5. **Any source change on the release branch is a new candidate.** All earlier
   artifacts, hashes, smoke results, notes readbacks and approvals are stale.
   Rebuild both platforms with strictly higher build coordinates.
6. **Version numbers are dispatch inputs.** Source keeps placeholders (Android
   `1.0.0`/`1000000`; iOS `MARKETING_VERSION=1.0`/`CURRENT_PROJECT_VERSION=1`).
   Don't rewrite them; don't fail preflight on them. Verify the signed artifact.
7. **Release notes come from reviewed files**, never commit subjects.
8. **Pinned private dependencies** (Hands SDK, KBA metadata) resolve only from
   Raft Artifacts via Gradle `exclusiveContent`. A version range, `latest`, or a
   fallback to gitlink/`mavenLocal`/local pod/source build for those coordinates
   blocks the candidate. A declared `mavenLocal()` alone is not a blocker.
9. **Never weaken a PASS.** A repository closeout may record an open product
   defect; it must not turn a source/packaging/merge-back PASS into a product
   PASS. Never record `NOT RUN` or a waiver as PASS.
10. **Ambiguous authorization or unnamed audience: ask.** Immediately before
    every mutation, fresh-read the authoritative state and latest owner
    directive; afterwards, read back terminal state.

## 0. Open the release issue

Before anything else (branch, freeze, CI dispatch), open an issue from the
**Mobile release** template (`.github/ISSUE_TEMPLATE/mobile-release.md`),
titled `Release X.Y.Z`, label `release`. Example: botiverse/mobile#4514
(1.23.0).

The issue is the **scope authority and the ledger**. It replaces the old Notion
"Mobile Release History" page and `change-logs/X.Y.Z/release-plan.md`; do not
create either. It holds:

- owner, operator, real calendar dates and timezone (scope lock, branch cut,
  target release). A relative `T-5` is not a date;
- delivery intent per platform (the maximum boundary for this train);
- the scope table (changeset → PR, owner, state) and every exception with
  approver, expiry and lift condition;
- branch and carrier source exact;
- per-platform build / draft / QA / approval / distribution state;
- receipts (run ids, Hands ids, SHA-256, Play/TestFlight receipts, message ids).

Post status updates as **issue comments**, not chat only. A chat receipt does
not replace the issue. If something is recorded late, say so; never tick a step
that was not done. Close the issue only when the train is closed (§9).

## 1. Scope and changesets

Scope states: `proposed`, `reserved`, `accepted`, `removed`, `shipped`. No
reservation, no inclusion. `reserved` holds capacity; only `accepted` items are
selected at scope lock. After cutoff, an item enters only through a recorded
exception (owner, evidence, risk, rollback).

Every user-visible change targeted at a version has a changeset:

```text
change-logs/X.Y.Z/changesets/<kebab-name>.md
```

- Only when a version was explicitly assigned; never guess "the next one".
- Ship it in the product PR; if the version was assigned after merge, open a
  follow-up changeset PR right away.
- Front matter (all required):

  ```markdown
  ---
  type: added | changed | improved | fixed | removed
  area: <area>
  platforms: [android, ios, desktop]   # only platforms that ship it
  pull_request: <product PR number>    # never TBD when merged
  rollout: full | canary | flag
  ---

  ## zh-CN
  <user-visible behaviour>

  ## en
  <same claims>
  ```

- User behaviour only: no class names, test lists, commit ids, PR numbers in
  prose, or unshipped work.
- Feature authors never edit `change-logs/X.Y.Z/<platform>/*.md`; the operator
  aggregates (§4).
- Before a version-targeted task goes to In Review: changeset present, copy
  matches the shipped scope, PR number and rollout accurate.

User-visible behaviour already on the cut commit must be in the scope table.
A missing changeset blocks scope lock.

At scope lock, remove candidates lacking terminal checks on their exact head,
independent review, required evidence, dependencies, or a complete changeset.

### 1.1 Open PRs and issues check (before every cut)

artin, 2026-11-12: every release starts by going through what is still open, so
nothing ready is left behind and nothing half-done slips in. Before §3:

```bash
stamp pr list --repo botiverse/mobile        # every open PR, any author
stamp issue list --repo botiverse/mobile     # open issues (bugs, release-targeted)
```

- **Every open PR** gets one outcome, recorded in the release issue's scope
  table: **merge now** (review on the exact head, terminal checks green,
  changeset for this version, then merge before the cut), **defer** (name the
  version and the reason, e.g. a known blocker), or **close**. External PRs
  are read for workflow, script, build-file and dependency changes before
  their CI is run. Dependency bumps (dependabot) are merged when green or
  closed with the reason.
- **Every open issue** that targets this version, or is a crash or regression,
  is either fixed in scope or moved to a named later version in the release
  issue. Nothing stays "open, no decision".
- **Merged PRs on the cut commit without a changeset**: add one (user-visible)
  or note why none is needed. The cut commit must match the scope table
  exactly; a PR that merged early for a later version is either accepted into
  this version (move its changeset) or reverted on the release branch, by the
  release owner's decision.

The cut waits until this list is empty of undecided items.

## 2. Version and bump rules

Pick the smallest bump that is honest (artin, 2026-11-02):

| Bump | When | Content | Cadence |
|---|---|---|---|
| minor `1.X.0` | New user-visible features | Branch cut from `main` | Weekly (weekday), once `main` full CI is green and the Alpha/internal build passed smoke |
| patch `1.X.Y` | Fixes to the live release: crash, data loss, regression, store-review blocker, security | Cherry-picks only; no features, no unrelated strings | As soon as the fix is verified |
| build (same version, new build number) | No user-visible change: re-sign, resubmission, CI/metadata fix, wrong-commit build, internal builds | Same content | Any time |

- A feature never ships as a patch or build. Flagged features may ship with the
  flag off.
- Every patch fix lands on `main` first (or at once), then is cherry-picked.
  Verify each cherry-pick against its merge commit, not by grepping strings.
- Changelog: full for a minor; short, fixes only, for a patch; none for a build.
- **One version line.** A number means the same code on every platform
  (Android, iOS, desktop); no platform reuses a number for different content.
  Live versions may differ: a one-platform fix bumps the shared patch and ships
  only there. The next minor realigns.

Coordinates:

```text
versionCode = major*10_000_000 + minor*100_000 + patch*1_000 + build
```

Compute with `scripts/ci/version_code.sh X Y Z BUILD` (1.2.2 build 0 →
`1020200`).

- **Android**: `versionName` = `X.Y.Z`; versionCode must be strictly greater
  than the active Hands version for that channel/arch, or devices never update.
  Each Hands channel's APK gets its own versionCode (§5.1).
- **iOS**: marketing version = train version since 1.12.0 (artin, 2026-09-27);
  `version_build` restarts at 1 per marketing version, so `CFBundleVersion` =
  the same formula (1.15.0 b1 → `11500001`). ASC rejects a reused build number.

## 3. Cut the release branch

Name: `release/X.Y.Z` (owner's naming convention for trains since 1.12; a patch
gets its own branch, e.g. `release/1.19.2`, even when cut from the previous
release branch). Create it from a reviewed commit reachable from `origin/main`
(or the previous release branch for a patch), never by moving an existing ref:

```bash
git push origin <reviewed-40-char-commit>:refs/heads/release/X.Y.Z
```

Record branch and commit in the issue. Known gaps with the slash name:
`scripts/create_android_release_branch.sh` creates the old `release-X.Y.Z`
form; `test.yml` (`release-*` full run) accepts only the dash form. Dispatch
`test.yml -f scope=full` on the branch by hand rather than renaming branches.
`scripts/create_ios_release_audit_tag.sh` accepts `release/X.Y.Z` (and the
legacy dash form).

After the cut:

- product work continues on `main`;
- a release blocker branches from the release branch and targets it, one
  blocker (plus strict dependencies) per PR;
- never merge `main` into the release branch; a fix PR that targeted `main` by
  mistake is retargeted or recreated;
- update the issue whenever scope changes.

## 4. Aggregate and review notes

From accepted changesets only, write per shipped platform:

```text
change-logs/X.Y.Z/android/{zh-CN,en,ja}.md
change-logs/X.Y.Z/ios/{zh-CN,en,ja}.md
```

- Sections in this order, empty ones omitted:
  `✨ **新功能** / ✨ **New** / ✨ **新機能**`,
  `🔧 **改进** / 🔧 **Improved** / 🔧 **改善**`,
  `🐛 **修复** / 🐛 **Fixed** / 🐛 **修正**`.
- Only behaviour shipped on that platform; bullets don't repeat the platform
  name; fold small related fixes into one line; soften partial capabilities.
- Same claims and section counts in all three locales.
- Terms match the live catalogs: `botiverse/slock`
  `packages/web/src/i18n/messages/` and
  `compose/shared/src/commonMain/composeResources/values-<locale>/strings.xml`;
  Japanese also `docs/i18n-ja-glossary.md`. Record the catalog exacts checked.
- Google Play: ≤500 characters per language.
- No PR numbers, implementation detail, CI history or unshipped work.

The workflows fail closed on an incomplete reviewed set. The mobile DRI
approves the files before any notes are applied to a draft.

## 5. Build drafts

Start from a clean checkout equal to the remote release branch. Record in the
issue: branch, carrier source exact, tree, required PRs, allocated coordinates.
The release workflows are the build/test gate for the candidate; don't run a
separate `Test` workflow for it. When the first draft is dispatched, start the
interval reminder (§8).

### 5.1 Android

Hands app `raft-android` (id `a78f3891-f980-4f48-80d7-2a58e93a99aa`,
`android-apk`). Channels: `main` (production), `preview` (prerelease/QA),
`debug`, `nightly`. Clients update when the active release's versionCode is
greater than theirs; drafts and cancelled releases are invisible.

**One APK per Hands channel** (Lagoon, 2026-10-23). The channel is compiled in
(`BuildConfig.HANDS_CHANNEL` from `SLOCK_HANDS_CHANNEL`, workflow input
`hands_channel`). Build `preview` with `--channel preview` and `main` with
`--channel main`, each with its own versionCode; never publish one APK to both.
(1.15.3 shipped a preview-built APK on `main`; 1.14.0 shipped the Play build on
`main`, whose installs can never update.) Ship every release to both channels.

Hands draft:

```bash
export SLOCK_RELEASE_BRANCH=release/X.Y.Z
scripts/release_android_hands_github.sh \
  --version X.Y.Z --build <n> --channel <main|preview> --variant release \
  --required-pr <N> [--required-pr <M>] \
  --preflight-report artifacts/release-preflight/X.Y.Z-<code>.md --wait
```

It dispatches `android-release.yml` and runs
`scripts/release_android_preflight.sh` in parallel, cancelling the run if
preflight fails (`--preflight-first` to serialize). Preflight fails closed
unless: the checkout is clean and equals the remote release branch; every
`--required-pr` is merged and an ancestor of the head; pinned coordinates
resolve per hard rule 8; the versionCode beats the active Hands version
(`--skip-hands-version-check` is offline-only and rejected on `main`). Manual
dispatches always create a draft; reusable QA callers stay build-only with
`create_hands_draft: false`.

Production defaults: `dex_packaging=compressed`, `jni_packaging=current`
(admitted with the 45 MiB APK cap, 0.60 DEX ratio cap, 16 KB ELF/ZIP). Other
values are diagnostics only. Output: signed aligned APK + SHA-256, R8 mapping
and native symbols (also attached to the Hands build), signed AAB +
`build-identity.json` (90 days), the Hands **draft**.

**Google Play carrier.** Every release also goes to Play **internal testing**
by default, like TestFlight internal (no extra approval). Dispatch
`android-release.yml` from the release branch with `android_variant=play`,
`upload_android_artifacts=true` and its own versionCode. CI signs the AAB with
the `SLOCK_ROTATED_*` upload key (cert SHA-256
`5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1`), verifies
it, and uploads AAB + Play APK + mapping/symbols to Hands as a sealed build. As
a signed-in Hands publisher (`hands login`; Play writes run under an
accountable human or agent identity, never CI's deploy token), run:

```bash
scripts/play_internal_promote.sh <hands_build_id>
```

It creates the Hands draft carrying the promotion, writes the acceptance
receipt and promotes to `internal`. **Never publish that Hands draft**: it would
put the Play carrier on a Hands channel. Record the Play receipt in the issue;
a green run or GitHub artifact is not a Play receipt; if the binding is
unavailable, record `AAB ready / Play upload NOT RUN`.

A sealed AAB/APK bundle cannot be replaced under the same versionCode. If an
unsigned AAB was sealed, raise the versionCode and rebuild; keep the original
as failed evidence (`aab_signing_repair` in `android-release.yml` only produces
verifiable signed files, never rewrites a sealed build).

### 5.2 iOS

Hands app `raft-ios`, bundle id `build.raft.app`, `ios-ipa`. macOS CI owns
Apple signing; Hands never receives certificates, profiles or keys. The ASC API
credential lives only in Hands; never export it. Hands is the only path to App
Store Connect.

```bash
stamp workflow run ios-release.yml --repo botiverse/mobile --ref release/X.Y.Z \
  -f version_major=X -f version_minor=Y -f version_patch=Z \
  -f version_build=<n> -f version_suffix= \
  -f release_notes_version=X.Y.Z \
  -f hands_channel=main -f export_method=app-store-connect \
  -f release_mode=draft \
  -f macos_runner=xcode-27-xlarge \
  -f xcode_app_path=/Applications/Xcode_27.1.app \
  -f testflight_internal=true \
  -f testflight_what_to_test=""
```

(`gh workflow run` with the same inputs also works.)

- `release_notes_version`: the `change-logs/` directory; independent of the
  marketing version.
- Xcode: all iOS builds use Xcode 27.1 on GitHub `xcode-27-xlarge` (owner
  decision 2026-10-28; iOS 27.1 SDK, iPhone Duo edge-to-edge; billed minutes).
  Avoid `xcode-27` (7 GB, the K/N link may OOM). Fallback only when the
  `xcode-27*` pool is unavailable:
  `-f macos_runner=blacksmith-12vcpu-macos-latest -f xcode_app_path=/Applications/Xcode_26.6.app`
  (iOS 26.5 SDK, letterboxed on iPhone Duo, recorded as `xcode_lane=opt-in`).
- `testflight_internal=true`: after the draft, upload to ASC and assign the
  Botiverse internal group only. That job is not idempotent: if it fails after
  the ASC upload, re-dispatch with a higher `version_build`.
- `testflight_what_to_test`: zh-Hans text; empty = build number + commit.
- **Hands upload**: `ios-release.yml` must install `@botiverse/hands-cli`
  >= 0.5.51, which uploads every asset directly to storage. Older CLIs send the
  IPA through Cloudflare, which rejects one request over 100 MiB (1.25.0's IPA
  was 106 MB). 0.5.49 added direct upload for iOS only. The workflows pin
  0.5.50 until 0.5.51 is on npm; bump the pins in `ios-release.yml` and
  `android-release.yml` then. Until that bump, keep the IPA under 100 MiB
  (§6.3).

The workflow validates: one `.app`; bundle id; versions equal the inputs and
`CFBundleVersion != 1`; `embedded.mobileprovision`; Compose resources;
`SDWebImageSVGCoder.framework`; app binary UUID matches the dSYM. A
`::warning::xcodebuild relinked the shared framework` means the slow path;
report it, the IPA is still valid. Record IPA/dSYM SHA-256.

Signing secrets: `SLOCK_IOS_DISTRIBUTION_CERTIFICATE_BASE64`/`_PASSWORD`,
`SLOCK_IOS_PROVISIONING_PROFILE_BASE64`, `SLOCK_IOS_APPLE_TEAM_ID` (optional
`_PROVISIONING_PROFILE_SPECIFIER`, `_CODE_SIGN_IDENTITY`,
`_EXPORT_OPTIONS_PLIST_BASE64`), plus `HANDS_BEARER_TOKEN`.

The first step after checkout decodes the profile (`security cms -D`) and
fails unless it grants every entitlement in
`compose/iosApp/iosApp/iosApp.entitlements` (Associated Domains
`applinks:app.raft.build` / `applinks:open.raft.build`, Sign In with Apple,
Push). Fix it in the Apple Developer portal: enable the named capability on
the `build.raft.app` App ID, edit + save the App Store profile, download it,
`base64 -i <file> | gh secret set SLOCK_IOS_PROVISIONING_PROFILE_BASE64`, and
re-run. Never strip the entitlement to get a build out; validation also fails
if the signed app lacks a declared domain.

## 6. Verify

### 6.1 Bytes

Android, record and independently check: run/artifact ids; APK and symbols size
and SHA-256; package, version, min/target SDK, signer lineage (v2/v3/v3.1);
`zipalign -P 16`, compressed DEX, every 64-bit ELF at 16 KiB; native Build IDs;
byte equality between GitHub and Hands assets.

iOS: run/artifact ids, IPA/dSYM size and SHA-256; bundle id, marketing
version, build number, minimum iOS; strict codesign, distribution authority,
team, profile, production entitlements; binary UUID = dSYM UUID; byte equality
GitHub vs Hands.

A metadata row is not byte verification: download and hash.

### 6.2 Smoke (hard gate before any external step)

The QA Acceptance default (login + home + one channel) is **not** release
smoke.

1. **Bind every receipt** to versionName + versionCode/build + source commit +
   artifact SHA-256 + signer + CI run. A PASS never transfers to another
   versionCode. On Android, reconcile announced SHA, re-download from the Hands
   `/download` link, bytes after `adb push` (re-hash), `/data/app` readback and
   `dumpsys package`; upgrades show `firstInstallTime` unchanged,
   `lastUpdateTime` advanced. Without adb, the owner can read
   `versionName` = `X.Y.Z+<short-sha>` under Settings → Apps → Raft; that is a
   cross-check, never a substitute for the versionCode/SHA half. Identity
   binding and the upload path are **never waivable**.
2. **Run the suite on the exact carrier**: `testbed run --all --apk <download-url> --release <id>` (the whole case table; artin 2026-09-04: the 3-step login/list/open smoke is not a release smoke; a cell not run is recorded `NOT RUN` with the reason). Receipts bind `versionCode` + SHA-256.
   A testbed case list is a dispatch list: `testbed run` makes a pending
   ticket that a device seat or CI agent must execute. Unrun or harness-blocked
   cases are `NOT RUN`/blocked, never PASS. Each case needs a terminal
   assertion; states are `PASS / FAIL / NOT RUN / NOT PROVIDED / NOT COVERED`.
3. **Required every train**: image picker → Done → composer preview → send →
   server/row readback (`select-and-upload-image`); upload size limits shown;
   an oversized sibling is dropped while the rest upload. File attachments are
   a separate case.
4. **Matrix** (freeze the manifest revision per train): identity & install
   (fresh install, in-place upgrade, `debuggable=false`); session & auth
   (logout revokes the token server-side, with a control arm); core surfaces
   (Home, channels, thread, composer send/receive, Activity, search, settings,
   long scroll); attachments (above, plus avatar, download, thumbnail, retry
   after network failure); stability (cold start min/median/max over ≥5
   samples, zero FATAL/ANR, resume); isolation (draft invisible on `main` until
   activation; same versionCode reports no update; control-arm negatives);
   accepted changesets.
5. **When the Xcode or SDK version changes**: screenshot the home bottom tab bar
   on a regular iPhone on iOS 26 and on iOS 27 (server button right of the
   capsule), not only iPhone Duo (#3889).
6. Keep grids separate per carrier/seat (Android cloud device, iOS Simulator,
   physical device); don't infer across them. Risky releases add a targeted
   regression and a fresh performance/visual artifact.

Alpha (`Android Alpha Validation`, `build.raft.app.alpha`) and internal
TestFlight builds from `main` are for pre-cut smoke and owner trials; they do
not replace smoke on the release carrier.

**Hotfix waiver**: only with a recorded owner exception in the issue
(`release_mode=hotfix`, waiver owner, scope, expiry/follow-up date, skipped
cases, compensating checks on the affected path, risk). Skipped cases read
`NOT RUN · hotfix waiver`. An affected core path is never skipped.

### 6.3 Trend check (before any external step)

Platform limits show up a release early (1.24.0: IPA 100.98 MB, iOS link
~36 min at 12g; 1.25.0 then OOMed at 12g and the IPA hit 106 MB). Compare the
candidate with the previous release and record a table in the release issue:

| Measure | Where | Previous | Candidate | Change |
|---|---|---|---|---|
| IPA size | `ios-release.yml` artifact / Hands asset | | | |
| APK size (`main`) | `android-release.yml` artifact | | | |
| AAB size (Play) | `android-release.yml` artifact | | | |
| iOS K/N release link time | step `Build shared framework (Release, iosArm64)` | | | |
| iOS K/N heap | step `Raise Gradle/Kotlin heap…` (setting) and any GC/OOM warning | | | |

- The IPA must stay under **100 MiB** unless the upload runs on Hands direct
  upload (hands-cli >= 0.5.49 for iOS, >= 0.5.51 for all assets; §5.2). Name
  the CLI version in the table.
- A size jump over ~5%, a link time over ~30 min, or any heap warning is a
  known risk: name it in the issue with an owner before the release go, and
  fix it in the next train rather than at the next limit.
- APK size has its own hard cap (45 MiB, §5.1).

## 7. Owner-approved steps

Each line below is its own mutation with its own go unless marked otherwise.
"Release go" means the mobile DRI's go (§Release decisions); rollback and
data-changing calls stay with the release owner.
Before each: fresh-read state and the latest directive, use exact revision /
build / group / source preconditions. After: read back and post in the issue.

### 7.1 Apply notes to each Hands draft

Fresh-read app, build, release, source, status, revision, scope, assets; write
`zh-CN`, `en`, `ja` at that revision
(`hands releases update <app> <id> --changelog-file zh-CN=… …`); fresh-read
again and require byte equality, unchanged source/scope, `status=draft`.

### 7.2 Android Hands activation (`preview`: no go needed; `main`: release go)

Authenticate: `hands whoami` must show `publisher` on `raft-android`
(`HANDS_BEARER_TOKEN` app deploy token, or `HANDS_SESSION_COOKIE` from
`raft integration login --service hands-4cc7a2`; treat as credentials, never
print). On a missing role, ask a Hands app admin for the named
`required_role`.

```bash
scripts/finalize_android_hands_release.sh --release <draft-id> \
  --version X.Y.Z --build <n> --channel <main|preview> \
  --zh-notes change-logs/X.Y.Z/android/zh-CN.md \
  --en-notes change-logs/X.Y.Z/android/en.md \
  --ja-notes change-logs/X.Y.Z/android/ja.md --confirm-publish
```

- Publish only through this script, never a bare `hands releases publish` or
  `hands builds publish-android`. It runs the pinned `@botiverse/hands-cli@0.5.32`
  and `scripts/check_android_hands_release_channel.sh`, which fails on a
  missing or mismatched build channel (enforced in CI by `Android release
  contract`). If you must publish by hand, run that guard first.
- `--confirm-publish` asserts the go was given; it is not the go.
- It verifies the public update endpoint returns the new versionCode, and warns
  if the other channel is still older.

Then push the annotated **`vX.Y.Z` audit tag** on the verified source commit
(required, artin 2026-09-27). Message: versionCode, Hands release id, APK
SHA-256, CI run. It never re-triggers a build; it runs `github-release.yml`,
which makes the GitHub Release: the published `main` APK (never an AAB, artin
2026-10-29), the iOS TestFlight build number, an unsigned Apple-silicon macOS
dmg and an iOS Simulator `.app` built from the tag.

### 7.2.1 Preview update test (every train, artin 2026-11-12)

Right after Hands `preview` is published and **before** `main` is published,
test the in-app update path on a real device or emulator. That device must
have the **previous** `preview` build installed and be signed in.

Before that, the automated `Android Update E2E` run (below) must be green on
the carrier source exact; it is a required step before `main`. Record its run
id in the release issue.

1. Open the app. The update prompt must appear and show the new version.
2. Tap update. The APK downloads and the system installer opens.
3. After install, the app opens on the new versionCode (Settings › About).
   It must still be signed in, with the same server and drafts.

Record the following in the release issue:
- the device
- the from → to versionCodes
- pass or fail, with a screenshot or recording of the prompt

A fail blocks `main`. The fix is a new candidate (Hard rule 5): rebuild both
platforms with higher build numbers, re-verify (§6) and republish `preview`.
Then repeat this test, starting from the previous train's `preview` build:
reinstall it if the device already took the failed one.

Checking only the public update endpoint does not count; the finalize script
already does that.

**Automated fixture run (Android).** `Android Update E2E`
(`.github/workflows/android-update-e2e.yml`) runs these three checks on a
GitHub-hosted emulator (API 34, no Testbed). It builds A and B from one commit
with `android-update-qa.yml`, publishes only B to Hands `raft-android/debug`,
scoped to a device group that holds only that run's emulator, and drives the
real prompt, download and system installer. Afterwards it removes the emulator
from the group and cancels B. It is **required before `main`** on every train
(run it on the carrier source exact; a red run blocks `main` like a failed
manual test), and you can run it whenever you need it:

```bash
stamp workflow run android-update-e2e.yml -R botiverse/mobile --ref main \
  -f source_sha=<40-char release candidate commit>
# Iterate on the test without rebuilding: reuse A/B from an earlier run
stamp workflow run android-update-e2e.yml -R botiverse/mobile --ref main \
  -f fixture_run_id=<run id>
```

The `android-update-e2e-evidence` artifact holds the screen recording, a
screenshot and UI dump for every step, logcat, and `summary.json`. A failure
names the step that broke. This run tests the update mechanics on fixture
builds. It does not replace the `preview` → `preview` test above: that one
uses the real previous train build and the real channel.

**Local state must survive restart and update.** Key local state (composer
drafts, settings, session/login, current server) needs tests that kill the
process or update the app and then assert the state is still there. In-process
unit and screenshot tests are not enough: a cold-start bug deleted every draft
from 1.18 to 1.25 and only this e2e's "draft still there" check caught it
(#4762). A change that adds or moves key local state adds such an assertion
(the e2e, or an instrumented restart test), and the new assertion must fail
without the fix.

### 7.3 Google Play beyond internal

Closed, open and production tracks each need a release go:
`hands releases play-promote <app> <release> --track closed --note …`
(`closed` = the binding's configured track, `alpha`). Read back
`/distributions/play` and `/receipts`: receipt/edit id, package, versionCode,
track, AAB SHA-256, source, run, review state.

**Review status.** Hands now reports Play review status (what is in review,
for which track and versionCode) and alerts when it cannot read it. Read it
before every promotion. Hands' status read failed (403) for five days during
1.25 with no alert, and "changes in review" was blamed on the data-safety form
when the 1.24.0 production release was in review. If the status is missing or
stale, fix the read first; don't guess.

**Withdraw and replace.** When an earlier change still in review blocks the
promotion:

```bash
hands releases play-promote <app> <release> --track production --note … \
  --cancel-in-review <versionCode>[,<versionCode>…]
```

Google withdraws **all** changes in review for the app, not only the named
versionCodes: other tracks, store listing, and policy forms (data safety,
content rating) too. Withdrawn changes must be resubmitted. It needs explicit
confirmation for that exact call: name what is in review (from Hands' review
status), the versionCodes, and what will have to be resubmitted, and get
the release owner's go (a data-changing call, §Release decisions). Record the confirmation
and the readback in the issue.

### 7.4 TestFlight: internal first, external only after the exact build was tested

(artin, 2026-09-27; external go delegated to the mobile DRI by product owner
decision, 2026-11-14.)

**Internal** (default after every iOS draft; no per-build go). With
`testflight_internal=true`, or by hand:

```bash
hands --json api POST /api/apps/<appId>/builds/<buildId>/testflight-upload
hands --json api GET  /api/apps/<appId>/testflight-uploads/<uploadId>   # until COMPLETE
hands builds testflight-publish raft-ios <buildId> --distribution internal \
  --group-id e10b6b3e-000d-4cbe-a160-2a9275f76977 \
  --what-to-test-file zh-Hans=wtt.txt --bundle-id build.raft.app
```

Never pass `--notify-testers` on internal (`NOTIFY_EXTERNAL_ONLY`). Done =
`processing_state=VALID`, Botiverse assigned,
`internal_build_state=IN_BETA_TESTING`. A bare "publish iOS" / "publish to
TestFlight" means exactly this. Report to the mobile DRI and **stop**.

**External** (Insiders, Beta App Review, notification): only after a separate
release go from the mobile DRI, given after that exact build was tested (smoke,
§6.2) and the criteria in §Release decisions hold; an earlier publish
instruction never carries over. **Cadence** (artin, 2026-10-23): Android ships
every release; iOS internal gets every release; iOS external is submitted only
after the previous external submission was approved; releases in between stay
internal. Never withdraw a pending review to replace it. External What to Test
covers everything since the last external release.

```bash
hands builds testflight-publish raft-ios <buildId> --distribution external \
  --group-id <external-group-id> \
  --what-to-test-file en-US=./wtt.en.txt --what-to-test-file zh-Hans=./wtt.zh.txt \
  --notify-testers --json          # NOT --wait: keep the POST receipt
hands builds testflight-status raft-ios <buildId> --distribution external --json   # bounded recheck
```

Once external is authorized, it is complete only when testers are notified.
`upload only`, `submit for review` or `do not notify` stop earlier and are
repeated in the receipt.

| Observed | Say | Complete? |
|---|---|---|
| Upload `COMPLETE`, ASC `VALID` | uploaded and processed | No |
| Groups assigned | assigned to groups | No |
| `BETA_APPROVED` | approved, awaiting notification | No |
| `auto_notify_enabled=true` before approval | notification scheduled | No |
| `notification=sent`/`already_sent` **and** `external_build_state=IN_BETA_TESTING` | testers notified / available in TestFlight | Yes |

`notification`: `not_requested` and `scheduled` are not completion. If
`scheduled`, wait for `IN_BETA_TESTING`, replay the same publish without
`--wait`, keep `already_sent`, fresh-read again. If either half is missing
after the bounded recheck, report `notification unverified`. Don't infer
notification from `BETA_APPROVED`, group assignment, `public_link_enabled` or
`auto_notify_enabled=false`.

**iOS audit tag**, after ASC upload `COMPLETE` (no Apple messages) and the
processed build `VALID`:

```bash
scripts/create_ios_release_audit_tag.sh --version X.Y.Z --build <CFBundleVersion> \
  --source <40-char source> --release-branch <branch> \
  --hands-build <id> --hands-release <id> --asc-build-upload <id>
# review, then the same with --confirm-push
```

The helper reads Hands only through your logged-in `hands` CLI (`hands whoami`
must show a role on `raft-ios`; read-only `hands --json api GET` and
`hands builds testflight-status`), not Raft integration actions. After a push
through Stamp it polls until the tag is published before checking the peel.

Format `ios-v<marketing>-b<CFBundleVersion>`; never an unqualified `vX.Y.Z`
for iOS; never move, delete or reuse a tag. Verify the `^{}` peel equals the
IPA source commit.

### 7.5 App Store (release go)

Submit, and release after approval, only with a release go for that version. Upload App Store screenshots
(including iPad 13") through Hands, not by hand in App Store Connect:
`hands app-store screenshots push raft-ios --version-name X.Y.Z --dir <dir>` (dry run first).
App Store production is never implied by TestFlight.

### 7.6 Announce (release go)

Announce only what is reachable on each platform, in `#raft-mobile`: four
separate top-level messages (Android zh/en, iOS zh/en). Japanese posts only if
artin asks and approves a template. Use the templates below verbatim except
bracketed parts and reviewed bullets. Android: map bullets into `✨/🔧/🐛`,
omit empty sections. iOS: drop section headings, keep every reviewed bullet in
order in one list (a shorter subset is new copy that needs review). Never paste
receipts, hashes, rollout or CI status. The Android link is always
`https://hands.build/apps/raft-android/latest` (owner decision 2026-10-21);
open it first to confirm it shows the announced version. Record message ids.

Android zh-CN:

```markdown
# Raft Android [marketing version] 已发布

Raft Android [marketing version] 现已上线。已经安装 Raft 的用户可以在「设置 → 关于 → 检查更新」中直接升级。

- 下载更新：<https://hands.build/apps/raft-android/latest>

✨ **新功能**

- [reviewed item]

🔧 **改进**

- [reviewed item]

🐛 **修复**

- [reviewed item]

遇到问题，欢迎通过「设置 → 发送反馈」告诉我们。感谢大家一直以来的使用与反馈。
```

Android en:

```markdown
# Raft Android [marketing version] is now available

Raft Android [marketing version] is now available. If you already have Raft installed, update directly from Settings > About > Check for updates.

- Download: <https://hands.build/apps/raft-android/latest>

✨ **New**

- [reviewed item]

🔧 **Improved**

- [reviewed item]

🐛 **Fixed**

- [reviewed item]

If you run into an issue, please use Settings > Send feedback. Thank you for using Raft and sharing your feedback.
```

iOS zh-CN:

```markdown
📱 Raft iOS [marketing version] ([build number]) 已在 TestFlight 提供测试

本次更新包括：

- [reviewed item]

TestFlight：<[current verified join URL]>

说明：这是 TestFlight 测试版本，不是 App Store 正式发布。版本号以 TestFlight 中显示的 `[marketing version] ([build number])` 为准。
```

iOS en:

```markdown
📱 Raft iOS [marketing version] ([build number]) is now available on TestFlight

Highlights:

- [reviewed item]

TestFlight: <[current verified join URL]>

This is a TestFlight beta, not an App Store production release. The canonical TestFlight version is `[marketing version] ([build number])`.
```

### 7.7 Defects after distribution

Bind the report to exact bytes, source and UUIDs; mark the product claim `RED`
or `UNKNOWN` without rewriting packaging evidence; record incident and owner in
the issue; don't expand rollout under a live hold. Freezing the update pointer,
rollback, share revocation, release deletion and removal are separate
mutations decided by artin. Follow the Hands
[Staged rollouts](https://hands.build/docs/public-api-reference#staged-rollouts)
contract and record revision/rollout before and after, the readbacks it
requires, and the lift condition. Resuming needs a new directive naming release
and rollout. A containment guard is not a root-cause fix.

## 8. Interval reminder

When the first platform draft is dispatched, schedule a recurring **10-minute
Raft interval reminder** on the release thread. Each firing fresh-reads every
in-flight Android/iOS run, Hands draft/readback and TestFlight processing
state, posts a short status (and updates the issue when state changed). Cancel
it only when every dispatched platform is terminal and receipts are in the
issue, or the release owner or mobile DRI stops the train. In-flight carriers without
a reminder is a process defect. The reminder authorizes nothing.

## 9. Closeout, merge-back, branch lock

1. **Merge back** after publication: PR from the release branch to `main`
   through Stamp. Resolve conflicts keeping both shipped fixes and newer `main`
   work; never replace `main` with the older tree. Required checks green,
   reviewed. **Squash-merge** (the repo allows only squash/rebase); the `vX.Y.Z`
   tag is the record of what shipped (artin, 2026-11-07). Verify content, not
   ancestry: `git diff vX.Y.Z origin/main` shows no release change that `main`
   lacks. A known-invalid release-only fix may be excluded only with its
   incident and successor recorded. If publication is abandoned, don't merge
   the branch; re-triage each fix for `main`.
2. **Close the version** when artin says so: no more merges or builds for it.
   Reconcile accepted release-only fixes into `main` by reviewed PRs; close
   redundant backports.
3. **Lock the branch**: a GitHub ruleset with exact-ref update, deletion and
   non-fast-forward restrictions and no bypass actors. Record rule id, owner
   and revocation condition in the issue. Reopening needs a new owner
   directive. Keep the branch and artifacts; delete only temporary worktrees,
   credentials and downloads. (Example: artin 2026-09-28 closed
   `release/1.12.0`, ruleset `24130656`; fixes went to `main` and `1.12.1` got
   its own branch.)
4. The next version starts from a fresh reviewed `main` commit and a new
   branch. Store-review follow-up stays separate from a new carrier; open
   defects stay open until a later candidate passes product verification.
5. Post the final summary in the issue and `#raft-mobile`: install link,
   platform availability (internal TestFlight, external review pending and
   public are distinct states), open limitations. **Close the issue.**

## Abort rules

- No real date or unaccepted scope: don't cut the branch.
- An open PR or issue without a recorded outcome (§1.1): don't cut the branch.
- Release head moved: all evidence stale; supersede and rebuild both
  platforms with higher builds. Never move the branch back.
- Revision, scope, group or source mismatch: stop and fresh-read.
- Artifact/hash/signing/UUID mismatch: stop; don't repair metadata around it.
- versionCode not above the active version: raise the build or the version.
- Malformed draft: supersede with a higher build; never touch the active
  `main` release to test visibility (use `preview`).
- Apple processing not `VALID`: stop before distribution.
- Anything uncertain (deps, notes, smoke): stay draft-only.
- Live material regression: stop rollout expansion; record the hold.
