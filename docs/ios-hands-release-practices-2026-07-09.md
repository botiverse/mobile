# iOS Hands Release Practices

This document records the Slock iOS release boundary for Hands.

When Android and iOS ship from the same frozen Mobile source, follow
[Mobile Paired Release SOP](mobile-paired-release-sop.md) for the shared
ordering, authority, activation, receipt, and merge-back contract.

## Boundary

macOS CI owns Apple signing. Hands never receives Apple certificates,
provisioning profiles, or private keys.

CI produces:

- signed `.ipa`: the installable release artifact
- `.dSYM.zip`: support artifact for iOS crash symbolication
- metadata: commit SHA, GitHub run URL, export method, version, and optional
  App Store Connect/TestFlight state

Hands stores, parses, audits, links release state, and uploads the signed IPA to
App Store Connect/TestFlight. Hands does not sign unsigned IPAs.

The App Store Connect (ASC) API credential is encrypted and stored in Hands.
It is not a mobile-repository or GitHub Actions secret, and the private `.p8`
key must never leave the Hands service boundary.

## App

- Hands app slug: `raft-ios`
- Bundle id: `build.raft.app`
- Product type: `ios-ipa`
- Main installable artifact: signed `.ipa`
- Symbol artifact: `.dSYM.zip`

## Versioning

Use the same release input shape as Android:

```text
versionCode = x * 10_000_000 + y * 100_000 + z * 1_000 + build
versionName = x.y.z[-suffix]
```

For iOS, CI passes those values into Xcode at archive time:

```text
MARKETING_VERSION=<versionName>
CURRENT_PROJECT_VERSION=<versionCode>
SLOCK_IOS_VERSION_NAME=<versionName>
SLOCK_IOS_VERSION_CODE=<versionCode>
```

`compose/iosApp/scripts/stamp_hands_build_metadata.sh` is the build phase that
stamps the processed `Info.plist` with the source commit under the
`HandsBuildCommit` key (what the Hands iOS SDK reads) and, when the env vars are
present, overrides `CFBundleShortVersionString` and `CFBundleVersion`. This
prevents release artifacts from falling back to the project defaults
`MARKETING_VERSION=1.0` / `CURRENT_PROJECT_VERSION=1`. Those project defaults
are expected in frozen source. Preflight must not fail because the Xcode
project still shows `1.0` / `1`; the version gate is dispatch injection plus
artifact readback. Since 1.12.0 (artin, 2026-09-27) iOS uses the train
semantic version as its marketing version (for example `1.12.0`, build 1 ⇒
`CFBundleVersion` `11200001`); builds up to 1.11.0 shipped as `1.0.0`.

## GitHub Actions

`.github/workflows/ios-release.yml` is the canonical CI lane.

Default dispatch behavior:

1. resolve version/channel/export/release parameters
2. build Kuikly/shared prerequisites
3. install signing certificate and provisioning profile from GitHub secrets
4. run `xcodebuild archive`
5. run `xcodebuild -exportArchive` to produce a signed `.ipa`
6. zip `*.dSYM`
7. validate IPA/dSYM boundary
8. upload workflow artifacts

Every dispatch uploads the signed IPA and dSYM to Hands and creates a draft.
The helper fails fast if the installed CLI package does not expose
`builds publish-ios`. CI never activates that draft; activation remains a
separate owner-authorized operation after review. If this dispatch starts the
train, schedule the Raft 10-minute interval reminder on the release thread
before walking away.

Reviewed trilingual release notes live at
`change-logs/<version>/ios/{zh-CN,en,ja}.md`. The workflow sends all three
files to Hands when the complete set exists, falls back to the historical
root-level three-file layout for older versions, and fails closed when either
reviewed layout is incomplete. Generated git history is used only when no
reviewed locale file exists. Dispatch must pass the Mobile train as
`release_notes_version`; it is intentionally independent from the iOS
marketing version. The platform directory already supplies the context, so
individual bullets do not repeat "iOS".

The Hands CLI package is `@botiverse/hands-cli` and the binary is `hands`.
CI sets `HANDS_API=https://hands.build` so large IPA uploads go directly to
Hands. Runtime consumers use `HANDS_BEARER_TOKEN`; the GitHub repository secret
keeps its existing external storage key. The helper accepts only `HANDS_*`
environment names.

Xcode export still uses the workflow `export_method` input, for example
`app-store-connect`. The publish helper maps that value to the Hands CLI
metadata vocabulary when needed, for example `app-store-connect` becomes
`app-store` for `hands builds publish-ios --export-method`.

## TestFlight Upload

TestFlight delivery is a Hands server-side operation. The supported sequence
is:

1. macOS CI builds and signs the IPA and dSYM.
2. `hands builds publish-ios` uploads both assets and creates the Hands build
   and release records.
3. Using the returned Hands build id, trigger:

   ```http
   POST /api/apps/:appId/builds/:buildId/testflight-upload
   ```

4. Hands reads the IPA asset from R2, uses the app's encrypted ASC credential,
   and streams the IPA to Apple's Build Upload API.
5. Poll the returned build upload id until a terminal state:

   ```http
   GET /api/apps/:appId/testflight-uploads/:buildUploadId
   ```

   The expected progression is `AWAITING_UPLOAD` to `PROCESSING`, then either
   `COMPLETE` or `FAILED`.

The Hands build must exist before submission. This preserves a durable build
and asset record before Apple accepts the upload. Release reporting should
include the Hands build and release ids, the ASC build upload id, the marketing
version and build number, and the terminal processing state. Do not include
credential values or sensitive response bodies.

The upload action is app-admin gated, while upload status is viewer gated.
After Apple processing, beta-group listing/status is viewer gated and the
separate TestFlight group-publish action is publisher gated. Use the supported
Hands CLI or Raft integration actions for each step; CI and operators must not
work around the boundary by exporting the ASC credential.

CI always registers the signed IPA and dSYM in a Hands draft before any
server-side submission. Hands remains the sole TestFlight delivery boundary
after CI has uploaded those artifacts.

When the release plan (or a standing owner directive for that train) says
iOS drafts go to TestFlight internal, do this immediately after the Hands
draft succeeds, without waiting for a per-build go:

1. `POST /api/apps/:appId/builds/:buildId/testflight-upload`.
2. Poll until the exact build is `processing_state=VALID`.
3. `hands builds testflight-publish` with `--distribution internal` and the
   named internal group id (Raft Mobile: Botiverse
   `e10b6b3e-000d-4cbe-a160-2a9275f76977`). Write What to Test. Do **not**
   pass `--notify-testers` (Hands returns `NOTIFY_EXTERNAL_ONLY` on
   internal).
4. Fresh-read `internal_build_state=IN_BETA_TESTING`. An internal
   all-builds group may already list the build; still write What to Test
   and record the readback.

Insiders, external testers, App Store, and Hands activation stay out of
this default.

## TestFlight Distribution And Tester Notification

An ASC build being `VALID` is upload/processing success, not TestFlight
publication. After processing:

1. Resolve the exact stable internal/external beta group IDs with
   `hands builds testflight-groups`.
2. Publish the exact build to the owner-selected audience with localized What
   to Test. External publication uses `--distribution external`.
3. Internal first: a bare `publish iOS` / `publish to TestFlight` means the
   named internal group only; stop and report to artin. External
   publication needs a separate explicit go from artin after he has tested
   the exact build. Once external is authorized, include `--notify-testers`;
   that is part of external publication, not an optional follow-up. Only
   `upload only`, `submit for review`, or an explicit `do not notify` stops
   earlier.
4. Preserve the mutating publish response. For an already-approved build it
   must report `notification=sent` (or `already_sent` on an idempotent replay).
   Do not combine this receipt-producing call with `--wait`: the current CLI's
   `--wait --json` output replaces the POST response with a later GET status,
   which does not preserve `notification` or `operation_id`.
5. Use the separate read-only `testflight-status` command for a bounded
   recheck. Require `external_build_state=IN_BETA_TESTING` before reporting
   `published`, `sent`, or `available in TestFlight`. If review is
   asynchronous, schedule the recheck and, after approval, invoke the same
   idempotent external publish with `--notify-testers` again.

The `notification` values are exhaustive: `not_requested` and `scheduled` are
not completion; `sent` and `already_sent` satisfy the operation-receipt half of
the completion contract. When the first response is `scheduled`, wait for the
exact build to reach `IN_BETA_TESTING`, then replay the same publish without
`--wait` to preserve the expected `already_sent` receipt, and fresh-read the
exact status again.

Example for an already-approved external build:

```bash
hands builds testflight-publish raft-ios <hands-build-id> \
  --distribution external \
  --group-id <external-group-id> \
  --what-to-test-file en-US=./testflight.en.txt \
  --what-to-test-file zh-Hans=./testflight.zh.txt \
  --notify-testers \
  --json

# Repeat this read-only command with a bounded interval/timeout until the
# exact build reaches IN_BETA_TESTING, or report notification unverified.
hands builds testflight-status raft-ios <hands-build-id> \
  --distribution external \
  --json
```

Do not infer notification from `BETA_APPROVED`, group assignment,
`public_link_enabled`, or `auto_notify_enabled=false`. The last value may mean
manual notification is required; Hands then uses Apple's official build beta
notification operation. Record marketing version/build number, Hands and ASC
build IDs, named external group IDs, localization readback, review state,
publish operation ID, `notification` result, and the final external build
state. Tester notification does not activate a Hands release and never implies
App Store production publication.

## Post-Delivery Git Audit Tag

An iOS release tag is an immutable audit pointer created **after** the exact
signed IPA has been stored in Hands, its exact App Store Connect Build Upload
has reached terminal `COMPLETE` with no Apple errors/warnings/infos, and the
corresponding processed build reads back as `VALID` and not expired. It is not
a workflow trigger and does not mean the build has been assigned to a
TestFlight group, testers have been notified, a Hands release has been
activated, or an App Store production release has been submitted.

Use this platform- and build-qualified format:

```text
ios-v<CFBundleShortVersionString>-b<CFBundleVersion>
```

For example, TestFlight `1.0.0 (1000005)` is
`ios-v1.0.0-b1000005`. Do not use an unqualified `v1.0.0` tag for iOS:
the marketing version can remain `1.0.0` across multiple TestFlight builds,
so it cannot identify the shipped bytes by itself. Historical `vX.Y.Z` tags
remain Android-era records and are not an iOS baseline.

The tag must point at the full 40-character `provenance.source_commit` stored
on the Hands build and stamped into the IPA, not at the current release-branch
tip or the latest `main`. The annotated message records at least:

- marketing version and build number;
- source commit and containing release ref;
- bundle id;
- Hands build and release ids;
- signed IPA SHA-256;
- App Store Connect Build Upload id and terminal upload state;
- App Store Connect processed build id and processing state.

The helper fetches `refs/heads/release-X.Y.Z` directly into the selected
remote-tracking ref. It then uses Raft Agent Login and the registered Hands
read-only actions to resolve the `raft-ios` app and read back the release,
build, IPA asset, exact Build Upload, and processed TestFlight build. Operator-
provided UUIDs, hashes, or status words are never treated as receipts.

Run the helper once in check-only mode, then repeat with the explicit push
acknowledgement:

```bash
scripts/create_ios_release_audit_tag.sh \
  --version 1.0.0 \
  --build 1000005 \
  --source 034db84238b593214cededed8c432d7c7d8085aa \
  --release-branch release-1.6.0 \
  --hands-build 52c7616c-a017-4def-b487-c2612970cd84 \
  --hands-release 84821c08-177f-4458-a2d1-c303c7f9e110 \
  --asc-build-upload 43584169-d2c2-4323-848f-40c44147fb4c

# After reviewing the printed identity:
scripts/create_ios_release_audit_tag.sh <same arguments> --confirm-push
```

The helper fails closed on short source SHAs, invalid identifiers, a commit-
like/local/tag source ref, a source outside the freshly fetched remote release
branch, any Hands app/build/release/source/version/IPA mismatch, a non-
`COMPLETE` or noisy Build Upload, a non-`VALID`/expired processed build, or an
existing local or remote tag. Never force-move, delete, or reuse an audit tag
to repair a bad baseline; stop and record an explicit correction with the
release owner.

After push, verify the remote annotated tag and its peeled commit separately:

```bash
git ls-remote --tags origin \
  refs/tags/ios-v1.0.0-b1000005 \
  'refs/tags/ios-v1.0.0-b1000005^{}'
```

The `^{}` line must equal the exact Hands/IPA source commit.

## Required Secrets

Required for build/sign:

- `SLOCK_IOS_DISTRIBUTION_CERTIFICATE_BASE64`: base64 encoded Apple
  Distribution `.p12`
- `SLOCK_IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`: `.p12` password
- `SLOCK_IOS_PROVISIONING_PROFILE_BASE64`: base64 encoded `.mobileprovision`
- `SLOCK_IOS_APPLE_TEAM_ID`: Apple team id

Optional:

- `SLOCK_IOS_PROVISIONING_PROFILE_SPECIFIER`: profile specifier override. If
  omitted, CI reads the profile name from the decoded `.mobileprovision`.
- `SLOCK_IOS_CODE_SIGN_IDENTITY`: defaults to `Apple Distribution`.
- `SLOCK_IOS_EXPORT_OPTIONS_PLIST_BASE64`: base64 encoded ExportOptions.plist.
  If omitted, CI generates one from the workflow input `export_method`.
- `HANDS_BEARER_TOKEN`: runtime variable required by every release dispatch so
  CI can upload the signed IPA and dSYM into a draft; CI sources it from the
  existing repository secret.

The ASC credential is managed only in Hands. GitHub Actions needs the Apple
signing material listed above and the Hands publishing token, but it does not
need the ASC upload credential.

## Validation

The release lane validates:

- IPA exists and contains one `.app`
- `CFBundleIdentifier == build.raft.app`
- `CFBundleShortVersionString` and `CFBundleVersion` equal the resolved release
  inputs and `CFBundleVersion != 1`
- `embedded.mobileprovision` exists
- KMP resources exist: `build.raft.app.resources.MR.bundle`,
  `Localizable.strings`, and `reaction_atlas_v1.png`
- `SDWebImageSVGCoder.framework` is present
- app binary UUID from `dwarfdump --uuid` has a matching UUID in the dSYM zip

These checks are intentionally local to the artifact boundary. Runtime smoke
tests still belong in the normal iOS validation lane.
