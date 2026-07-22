# iOS Hands Release Practices

This document records the Slock iOS release boundary for Hands.

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
versionCode = x * 1_000_000 + y * 10_000 + z * 100 + build
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
`MARKETING_VERSION=1.0` / `CURRENT_PROJECT_VERSION=1`.

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

The lane can optionally publish to Hands with `publish_hands=true`. The publish
helper fails fast if the installed CLI package does not expose
`builds publish-ios`. The signed IPA and dSYM stay available as workflow
artifacts, so the CI side is not blocked by the Hands receiver rollout or npm
publication timing.

Reviewed bilingual release notes live at
`change-logs/<version>/ios/{zh-CN,en}.md`. The workflow sends both files to
Hands when the pair exists, falls back to the historical root-level pair for
older versions, and uses the generated git history only when neither reviewed
pair is available. The platform directory already supplies the context, so
individual bullets do not repeat "iOS".

The Hands CLI package is `@botiverse/hands-cli` and the binary is `hands`.
CI sets `HANDS_API=https://hands.build` so large IPA uploads go directly to
Hands instead of through the legacy Quiver reverse proxy. CI continues to use
the already configured `QUIVER_BEARER_TOKEN` secret while the deployment naming
finishes migrating. The helper also accepts `HANDS_*` env names for local/future
compatibility, but this workflow does not require a new Hands-named secret.

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

`publish_hands=true` registers the signed IPA and dSYM before server-side
submission. Hands remains the sole TestFlight delivery boundary after CI has
published those artifacts.

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
- `QUIVER_BEARER_TOKEN`: required only when `publish_hands=true`. This is the
  current configured Hands bearer token secret for the mobile release lane.

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
