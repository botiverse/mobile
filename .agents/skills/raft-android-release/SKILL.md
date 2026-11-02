---
name: raft-android-release
description: Use when cutting, building, smoking, publishing or closing an Android release of Raft on Hands (raft-android) — release branch, versionCode allocation, draft-first dispatch, reviewed notes, smoke gate, owner-approved activation, audit tag and merge-back. For a quick installable QA build use the Android Alpha Validation workflow instead; for a paired Android+iOS train also follow raft-ios-release.
---

# Raft Android release (Hands)

CI builds, signs, uploads and creates a Hands **draft**. Activation is a
separate command run only after the owner's explicit go. Never activate,
announce, or move a release branch on your own judgement.

Authoritative detail (read the section before acting on it):
- `docs/android-release-sop.md` — order of operations, smoke cells, finalize.
- `docs/mobile-paired-release-sop.md` — train plan, Notion ledger, authority
  boundaries, announcement templates, closeout.
- `docs/android-hands-release-practices-2026-07-02.md` — versionCode formula,
  update-check model, notes format (its "GitHub Actions Release Dispatch"
  preflight list predates release branches; the SOP wins).

## 0. Before anything

- Create the version entry in the Notion "Mobile Release History" ledger and
  `change-logs/X.Y.Z/release-plan.md` (paired SOP §1, "Release closeout").
- Confirm every accepted item has a changeset (`raft-release-changeset`).
- Confirm the bump (minor / patch / build) and its allowed scope
  (`raft-release-versioning`).

## 1. Allocate coordinates

```text
versionCode = major*10_000_000 + minor*100_000 + patch*1_000 + build
```
Compute with `scripts/ci/version_code.sh X Y Z BUILD`. It must be strictly
greater than the active Hands version for the channel, or devices never update.
Coordinates are dispatch inputs; do not edit the `1.0.0`/`1000000` source
defaults.

## 2. Freeze the branch

```bash
scripts/create_android_release_branch.sh --version X.Y.Z --candidate <40-char reviewed main commit>
```
The script creates `release-X.Y.Z`, the form `test.yml` (`release-*`) and
`scripts/create_ios_release_audit_tag.sh` expect. Some recent trains used the
owner's slash names (`release/1.12.0`, `release/1.14.x`); use the name the
release plan records, and note that slash branches do not get the full
`release-*` Test run. Never force-push, rewind or reuse a closed release branch. Blocker
fixes branch from the release branch and target it; never merge `main` in.

## 3. Dispatch the draft

From a clean checkout exactly equal to the remote release branch:
```bash
export SLOCK_RELEASE_BRANCH=release-X.Y.Z
scripts/release_android_hands_github.sh \
  --version X.Y.Z --build <n> --channel main --variant release \
  --required-pr <N> [--required-pr <M>] \
  --preflight-report artifacts/release-preflight/X.Y.Z-<code>.md --wait
```
It dispatches `android-release.yml` and runs `scripts/release_android_preflight.sh`
in parallel (cancels the run if preflight fails; `--preflight-first` to
serialize). Do not run a separate `Test` workflow for the candidate.
Immediately schedule the 10-minute Raft interval reminder on the release thread.

Google Play carrier: dispatch with `android_variant=play`,
`upload_android_artifacts=true`, then the Hands Play binding steps (SOP §3.1).
A GitHub artifact is never a Play receipt.

## 4. Notes

Write `change-logs/X.Y.Z/android/{zh-CN,en,ja}.md` from the version's
changesets (not commit subjects): sections `✨ New / 🔧 Improved / 🐛 Fixed`,
user-visible only, same claims in all three locales, terms from the live
catalogs. Owner approves the files.

## 5. Smoke (hard gate)

- Bind every receipt to versionName + versionCode + source SHA + APK SHA-256 +
  signer + CI run; a PASS never transfers to another versionCode.
- Required every train: image picker → Done → preview → send → row readback,
  plus oversize-sibling admission. Login/home is NOT smoke.
- Mark cells `PASS / FAIL / NOT RUN / NOT COVERED`; never infer PASS.
  Hotfix waivers only with a recorded owner exception.

## 6. Activate (only after owner go)

```bash
hands whoami     # must have publisher on raft-android (HANDS_BEARER_TOKEN)
scripts/finalize_android_hands_release.sh --release <draft-id> \
  --version X.Y.Z --build <n> --channel main \
  --zh-notes change-logs/X.Y.Z/android/zh-CN.md \
  --en-notes change-logs/X.Y.Z/android/en.md \
  --ja-notes change-logs/X.Y.Z/android/ja.md --confirm-publish
```
Then push the annotated `vX.Y.Z` audit tag on the verified source commit
(message: versionCode, Hands release id, APK SHA-256, CI run). The tag never
rebuilds Android; it runs `github-release.yml` (GitHub Release: Hands APK, its
signed AAB backup, unsigned macOS dmg and the iOS Simulator app (Xcode 27.1,
Debug, arm64), both built from the tag).

## 7. Merge back and close

PR `release-X.Y.Z` → `main` through Stamp (`raft-stamp-git`), resolving
conflicts in favour of both shipped fixes and newer main work. Update the
ledger, announce in `#raft-mobile` with the paired SOP template, and lock the
branch when the owner closes the version.

## Abort rules

Release head moved → all evidence stale, restart at preflight. Malformed draft
→ supersede with a higher build. Anything uncertain → stay draft-only.
