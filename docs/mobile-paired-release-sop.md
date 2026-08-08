# Mobile Paired Release SOP

Use this procedure when Android and iOS are shipped from one Mobile candidate.
Platform-specific build and signing rules remain in
[Android Release SOP](android-release-sop.md) and
[iOS Hands Release Practices](ios-hands-release-practices-2026-07-09.md).
This document defines the shared ordering, authority, and receipt contract.

## Invariants

1. Freeze one full 40-character source commit from `release-X.Y.Z`. Android
   and iOS artifacts must both report that exact source.
2. CI may build, sign, upload, create Hands drafts, and create review shares.
   It must not activate a Hands release without a fresh owner instruction.
3. A release-branch change invalidates all artifacts, hashes, runtime results,
   notes readback, and approvals tied to the previous source. Rebuild both
   platforms with strictly higher build coordinates.
4. Hands activation, TestFlight upload, TestFlight group distribution, Beta
   App Review, tester notification, and App Store production are separate
   mutations. Authorization for one does not imply another.
5. Release notes are reviewed files, not raw commit subjects. The exact notes
   read back from each active release must be committed under `change-logs/`.

## 1. Record The Candidate

Before dispatching, record:

- release branch and exact source commit;
- immutable carrier ref retained for audit;
- selected release PRs and terminal exact-head Hosted run;
- Android version name, versionCode, and Hands channel;
- iOS marketing version, build number, export method, and Hands channel;
- intended Hands scope, normally `full:all`;
- the owner-approved publication boundary.

Do not assume Android and iOS use the same user-facing version string. They
must use the same source commit, while each platform keeps its own monotonic
build coordinate.

## 2. Build Drafts From The Exact Source

Run Android from a clean checkout that exactly equals the remote release
branch. The wrapper creates only a draft and runs the release preflight:

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

Dispatch iOS on the same remote ref with `release_mode=draft` and
`publish_hands=true`:

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

If the frozen source changes after either dispatch, do not reuse the draft or
an App Store Connect build number. Mark the old evidence superseded, raise the
affected build coordinates, and repeat both platform builds.

## 3. Verify Stored Bytes

For Android, record and independently verify:

- GitHub run and artifact ids, APK size and SHA-256;
- package/version/minSdk/targetSdk;
- signing lineage and v2/v3/v3.1 verification;
- `zipalign -P 16`, compressed DEX admission, and every 64-bit ELF at 16 KiB;
- native-symbol archive identity and expected Build IDs;
- byte equality between the GitHub artifact and Hands installable asset.

For iOS, record and independently verify:

- GitHub run and IPA/dSYM artifact ids, sizes, and SHA-256 values;
- bundle id, marketing version, build number, and minimum iOS;
- `codesign --verify --deep --strict`, distribution authority, team, profile,
  and production entitlements;
- app binary UUID equals the matching dSYM UUID;
- byte equality for both IPA and dSYM between GitHub and Hands.

A Hands metadata row is not a substitute for downloading and hashing both
sides.

## 4. Review And Apply Release Notes

Store all three reviewed locales for each platform:

```text
change-logs/X.Y.Z/android/{zh-CN,en,ja}.md
change-logs/X.Y.Z/ios/{zh-CN,en,ja}.md
```

Each locale must carry equivalent user-visible claims and matching section
counts. Platform directories may intentionally contain identical product-wide
notes, but the operator must still read back both Hands releases separately.

For each draft:

1. Fetch fresh release detail and record `revision`, `status`, source, and
   scopes.
2. Update notes with that exact revision precondition.
3. Fetch fresh detail again and require exact file equality, `status=draft`,
   unchanged source, and unchanged scopes.

Do not activate a release whose notes came only from build metadata or git
history.

## 5. Activate Hands Only After The Owner Go

For each platform, immediately before activation require:

- exact app, build, release, version, and source identity;
- `status=draft` and a fresh revision;
- reviewed zh-CN/en/ja notes;
- exact scope, normally `[full:all]`;
- explicit owner authorization to publish.

Publish with both revision and scope preconditions. Then perform a new detail
read and require:

- `status=active`;
- revision advanced exactly once;
- source, notes, scope, build, and version unchanged.

Treat Android and iOS as two independent mutations. A successful Android
activation does not authorize or prove iOS activation, and vice versa.

## 6. Deliver TestFlight At The Authorized Level

Always upload the already verified Hands IPA; never export the ASC credential
or bypass Hands.

### Upload Only

1. Start upload for the exact Hands iOS build.
2. Poll the Build Upload to `COMPLETE` with empty errors and warnings.
3. Read back the exact marketing version/build as `VALID`, `ready`, not
   expired, and with the expected audience.

This does not select beta groups or notify testers.

### Internal Distribution

Require the owner to name the internal group. Read back its stable id, assign
the exact build, and verify `IN_BETA_TESTING` plus the intended group. An
internal group with `has_access_to_all_builds=true` may already expose the
build automatically; record that fact instead of claiming a new assignment.

### External Distribution

Require explicit authorization naming the external group, What to Test
localization, Beta App Review submission, and notification intent. External
distribution may submit Beta App Review and notify testers after approval.
These side effects are never inferred from a generic request to upload.

None of these TestFlight operations publishes an App Store production
version. App Store production remains a separate owner-authorized procedure.

## 7. Draft And Approve The Community Announcement

After a platform reaches its owner-approved community delivery boundary and
has a terminal readback, draft the user-facing community announcements under
the release:

```text
change-logs/X.Y.Z/community-announcement.zh-CN.md
change-logs/X.Y.Z/community-announcement.en.md
```

Both Chinese and English are required. Additional locales may be added for the
audience, but each announcement must:

- cover only platforms that have reached the intended community boundary; do
  not pre-announce an internal TestFlight build or any other unreleased
  platform;
- derive every product claim from the approved platform notes and preserve the
  reviewed section structure instead of inventing a second feature summary;
- include the supported update path and feedback path where applicable;
- avoid build coordinates, CI detail, internal task language, and unshipped
  work;
- receive explicit release-owner approval before it is posted to a community
channel. Chinese and English may be posted together or as separate messages,
but the release receipt must identify the final message receipt for each.

Posting the announcement is a separate communication action, not an implied
side effect of Hands activation or TestFlight distribution. Record the final
announcement paths plus their channel/message receipts in the release receipt.
An unreleased platform gets its own announcement later, after it reaches its
separately authorized community boundary.

## 8. Preserve The Receipt And Merge Back

Commit a release receipt under `change-logs/X.Y.Z/` containing:

- frozen source/ref and terminal Hosted run;
- workflow, artifact, Hands build/release/asset, size, and hash identities;
- activation state, revision, scope, and approved note paths;
- TestFlight upload/distribution ids and terminal Apple readback;
- runtime evidence, accepted residuals, and explicit untested/excluded scope;
- superseded carrier coordinates;
- release-to-main PR and landed main exact.

After both Hands activations and the intended TestFlight boundary are
terminal, open a reviewed `release-X.Y.Z -> main` PR. Resolve conflicts by
preserving both shipped fixes and intentional newer main work. Before treating
merge-back as complete, scan the landed tree for conflict markers and run the
resource/code generation gates affected by conflict resolution.

Do not delete the immutable carrier ref. Remove only temporary local
worktrees, downloaded credentials, and short-lived verification material after
the receipt is durable.

## Abort Rules

- Source drift: stop, supersede, increment, rebuild both platforms.
- Revision or scope mismatch: stop without publishing and fetch fresh detail.
- Artifact/hash/signing mismatch: stop; do not repair metadata around bad
  bytes.
- Apple upload failure or non-`VALID` processing: stop before distribution.
- Unnamed TestFlight group or ambiguous notification intent: ask the owner;
  do not guess an internal/external mutation.
- Merge-back conflict markers or failed resource generation: fix main in a
  dedicated PR before rebasing unrelated work.
