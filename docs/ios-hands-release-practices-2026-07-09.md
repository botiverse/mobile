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

The submit route is currently app-admin gated, while status is viewer gated.
The intended automation contract is publisher-gated submit and viewer-gated
status through the Hands CLI and agent manifest. Until that contract is
available, an authorized Hands app admin must trigger submission; CI must not
work around the gap by exporting the ASC credential.

The workflow's current `upload_testflight` implementation invokes `altool` and
reads GitHub ASC secrets. That path is not the supported release architecture
and must remain disabled for production dispatches until it is replaced with
the Hands submit/status operation. `publish_hands=true` remains the supported
way to register the signed IPA and dSYM before server-side submission.

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

Do not add `ASC_API_KEY_ID`, `ASC_API_ISSUER_ID`, or
`ASC_API_KEY_P8_BASE64` to GitHub Actions. Do not export the `.p8` from Hands or
upload with `xcrun altool`. Hands is the single source of truth for the ASC
credential and performs the Apple upload server-side.

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
