---
name: slock-android-packaging
description: "Use when building, signing, validating, uploading, or reporting a Slock Android release APK artifact, especially for #artifacts package requests or #raft-mobile handoffs."
---

# Slock Android Packaging

This skill standardizes Slock Android APK artifact production from the local
repository state through signing, verification, upload, and smoke-test handoff.

Read `references/android-release-apk.md` before running packaging commands.

## Operating Rules

- Claim the package task before starting. Report progress in the task thread.
- Package from current local `main` unless the user names another commit.
- Use a clean throwaway worktree or detached checkout for builds. If local
  `main` advances before the final report, discard the stale APK and rebuild
  from the new local `main`.
- Do not push remote `main` unless the owner explicitly asks for that push.
- Do not commit, print, or post signing secrets, keystore passwords, `.env`
  files, Gradle properties containing secrets, or credential-shaped tokens.
- If packaging exposes a build break in local `main`, fix it in a separate
  worktree/branch, validate, merge to local `main`, then restart packaging from
  a clean current-main worktree.

## Workflow

1. Identify the source commit.
   - Record `git rev-parse --short HEAD`, local `main` status, and whether
     remote `main` is pushed.
   - Prefer a detached worktree named with the task id and commit.
2. Prepare dependencies.
   - Initialize/update only the submodules needed by the build when recursive
     update is blocked by local fork mappings.
   - Verify submodule SHAs with `git submodule status`.
3. Run preflight checks.
   - `git diff --check HEAD`
   - From `compose/`: `./gradlew :shared:generateSqlDelightInterface`
4. Build the release APK.
   - From `compose/`: `./gradlew :androidApp:assembleRelease`
   - Keep Gradle steps sequential unless you know the caches are safe.
5. Sign externally if the release output is unsigned.
   - The app module currently has no release signing config in Gradle.
   - Use the existing authorized local signing setup on the builder machine.
   - Never expose keystore aliases, passwords, or secret property contents.
6. Verify the final APK.
   - `zipalign -c -p 4`
   - `apksigner verify --verbose --print-certs`
   - `aapt dump badging`
   - `shasum -a 256`
7. Upload and report.
   - Upload the final signed APK with `raft attachment upload`.
   - Report filename, attachment id, source commit, APK SHA-256, size, package,
     versionCode, versionName, targetSdk, signer certificate SHA-256, signature
     schemes, validation commands, local-main status, and remote-main status.
   - If also sending to #raft-mobile, upload a fresh attachment id there.
8. Request async performance smoke.
   - After every future package/APK upload, ask @Codex-Kuikly-Perf in the
     artifact thread to smoke test login, conversation flow, basic navigation,
     and obvious performance/function regressions.
   - Use #artifacts msg `851c65cf` as the authorization source for test account
     access, but do not repost credentials.

## Reporting Standard

Every final handoff must make the git state explicit:

- artifact-only or code-change task;
- source worktree path and source commit;
- whether work is branch-only, merged into local `main`, or not applicable;
- local `main` commit if merged or used for artifact source;
- whether remote `main` was pushed or remains unchanged.

Use the report template in `references/android-release-apk.md`.
