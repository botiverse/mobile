---
name: raft-ios-release
description: Use when building an iOS release of Raft (ios-release.yml) into a Hands draft (raft-ios), uploading it to TestFlight, distributing to the internal or external group, or creating the iOS audit tag. Covers the workflow inputs including macos_runner and testflight_internal.
---

# Raft iOS release + TestFlight

macOS CI signs; Hands stores the IPA/dSYM and is the only path to App Store
Connect. Every dispatch creates a Hands **draft**; CI never activates it.

Detail: `docs/ios-hands-release-practices-2026-07-09.md` (boundary,
TestFlight states, audit tag), `docs/mobile-paired-release-sop.md` (rule 9 /
9a: internal first, external only after artin tested; §8 completion states).

## 1. Coordinates

- Marketing version = train version (`1.15.0`), since 1.12.0.
- `version_build` restarts at 1 per marketing version; `CFBundleVersion` =
  `scripts/ci/version_code.sh X Y Z BUILD` (e.g. 1.15.0 b1 → `11500001`).
  It must be new for App Store Connect: a reused build number is rejected.
- Source defaults (`MARKETING_VERSION=1.0`, `CURRENT_PROJECT_VERSION=1`) stay;
  the workflow injects the values.

## 2. Dispatch

From the remote release branch (paired train) or the ref the owner named:
```bash
gh workflow run ios-release.yml --repo botiverse/mobile --ref <release-branch> \
  -f version_major=1 -f version_minor=15 -f version_patch=0 \
  -f version_build=1 -f version_suffix= \
  -f release_notes_version=1.15.0 \
  -f hands_channel=main -f export_method=app-store-connect \
  -f release_mode=draft \
  -f macos_runner=xcode-27-xlarge \
  -f xcode_app_path=/Applications/Xcode_27.1.app \
  -f testflight_internal=false \
  -f testflight_what_to_test=""
```

Inputs that matter:
- `release_notes_version` — directory under `change-logs/`; reads
  `change-logs/<v>/ios/{zh-CN,en,ja}.md` (fails closed on an incomplete set).
- `macos_runner` + `xcode_app_path` — default `xcode-27-xlarge` +
  `/Applications/Xcode_27.1.app` (iOS 27.1 SDK, iPhone Duo edge-to-edge; owner
  decision 2026-10-28: all iOS builds use Xcode 27.1). Only GitHub's `xcode-27*`
  images have Xcode 27.1 (`Xcode_27.1.app` is the real install there,
  `Xcode_27.1.0.app` a symlink the pin step rejects; no 26.x). GitHub-hosted
  macOS minutes are billed; non-Blacksmith runners get a 120 min timeout.
  `xcode-27` is the 7 GB variant (5g heap, the Kotlin/Native release link may
  OOM) — avoid it for releases.
- Manual fallback only (e.g. the `xcode-27*` pool is unavailable):
  `-f macos_runner=blacksmith-12vcpu-macos-latest -f xcode_app_path=/Applications/Xcode_26.6.app`
  (60 min timeout; `macos-latest-xlarge` also has 26.6). A 26.6 build uses the
  iOS 26.5 SDK and is **letterboxed on iPhone Duo**; it is recorded as a
  deliberate `xcode_lane=opt-in` build. The resolve job fails any other
  runner/Xcode pairing. The build summary and Hands `build-metadata.json`
  record `xcode_version`, `ios_sdk_version` and `xcode_lane`
  (`default` = 27.1).
- `testflight_internal=true` — after the draft, upload to ASC and assign the
  internal group **Botiverse** only (never external/App Store/activation).
  Use it only when the owner or the train's standing directive asked for
  internal TestFlight. `testflight_what_to_test` is zh-Hans text; empty uses
  build number + commit.

Schedule the 10-minute Raft interval reminder on the release thread when the
dispatch starts a train.

## 3. Watch the build

- `Build shared framework (Release, iosArm64)` links the K/N framework once in
  its own step; `Archive app` reuses it (`RAFT_IOS_REUSE_LINKED_FRAMEWORK=YES`).
  A `::warning::xcodebuild relinked the shared framework` means the archive
  took the slow double-link path — report it, the IPA is still valid.
- `Validate IPA + dSYM boundary` checks bundle id `build.raft.app`, versions,
  Compose resources (string catalogs, reaction atlas), dSYM UUID match. Record the IPA/dSYM SHA-256 from the
  step summary.
- The `testflight_internal` job is NOT idempotent: if it failed after the ASC
  upload, re-dispatch with a higher `version_build`; do not re-run the job.

## 4. TestFlight by hand (when not using the input)

```bash
hands --json builds list raft-ios            # match version_code AND source_commit
hands --json api POST /api/apps/<appId>/builds/<buildId>/testflight-upload
hands --json api GET  /api/apps/<appId>/testflight-uploads/<uploadId>   # until COMPLETE
hands --json builds testflight-groups raft-ios <buildId>
hands builds testflight-publish raft-ios <buildId> --distribution internal \
  --group-id <Botiverse id> --what-to-test-file zh-Hans=wtt.txt --bundle-id build.raft.app
```
- Internal: never pass `--notify-testers` (Hands returns `NOTIFY_EXTERNAL_ONLY`).
  Done = `processing_state=VALID`, group assigned,
  `internal_build_state=IN_BETA_TESTING`. Report to artin and stop.
- External: only after artin's explicit go for that exact build. Publish with
  `--distribution external --notify-testers` and WITHOUT `--wait` (keep the
  `notification=sent|already_sent` receipt), then poll
  `hands builds testflight-status raft-ios <buildId> --distribution external --json`
  until `external_build_state=IN_BETA_TESTING`. `BETA_APPROVED` or group
  assignment is not "published".

## 5. Audit tag

After ASC upload `COMPLETE` and processed build `VALID`:
```bash
scripts/create_ios_release_audit_tag.sh --version 1.15.0 --build 11500001 \
  --source <40-char source commit> --release-branch <release-branch> \
  --hands-build <id> --hands-release <id> --asc-build-upload <id>
# review output, then the same command with --confirm-push
```
Tag format `ios-v<marketing>-b<CFBundleVersion>`; never reuse `vX.Y.Z`.
The shared `vX.Y.Z` tag (Android closeout) runs `github-release.yml`: the GitHub
Release names the TestFlight build (no IPA) and attaches a Simulator-only `.app`
zip built from that tag's commit with Xcode 27.1 (`ios-simulator-app.yml`).
The helper only accepts `--release-branch release-X.Y.Z` (dash form). For a
train on a slash-named branch (`release/1.14.x`) it fails closed; ask the
release owner how to record the tag instead of renaming branches.

## Never

Export the ASC credential, activate the Hands draft, promote to external or App
Store, or announce, without the separate authorization each one needs.
