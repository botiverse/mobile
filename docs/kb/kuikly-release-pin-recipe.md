# Kuikly release-set pin upgrade recipe

> **Stale (2026-10-19):** Kuikly UI left Mobile in the Compose Multiplatform switch. The closure, manifest gate and fetcher named below were deleted; nothing in the build consumes a Kuikly release any more. Kept for history only.

Owner: Mahua · Verified at: 2026-10-19 · Status: stale

How to bump the immutable Kuikly release set (e.g. `2.28.0-raft.N` → `raft.N+1`) in this repo. The same recipe has been used for several consecutive bumps (#3025, #3073). HarmonyOS (OHOS) was removed from `main` after the 1.14.x freeze, so `main` bumps no longer touch an OHOS HAR, `oh-package` files or `ohpm`; OHOS pin work happens only on `release/1.14.x`.

## 1. Bump the pin family atomically

Replace the version string in all of, and only, these files:

- `compose/buildSrc/src/main/java/KotlinBuildVar.kt`
- `compose/gradle/libs.versions.toml`
- `compose/gradle/kuikly-release-closure.json`
- `scripts/ci/verify_kuikly_release_manifest.sh`
- `scripts/ci/fetch_kuikly_release_artifact.py`
- `scripts/ci/test_kuikly_raft_consumer_contract.py`
- `compose/iosApp/Podfile`
- `THIRD_PARTY_NOTICES.md`

Do **not** hand-edit `compose/iosApp/Podfile.lock` — it is regenerated in step 3. Never touch dated historical docs.

## 2. Run the source gates

- `scripts/ci/verify_kuikly_release_manifest.sh` — must end in `ALL_COMPLETE_EXACT`.
- `scripts/ci/test_kuikly_raft_consumer_contract.py` — static consumer contract (fails until step 3 regenerates the lockfile).
- `scripts/ci/check_source_constraints.py` and `git diff --check`.

## 3. Regenerate the native lockfile for real

- iOS: `cd compose && ./gradlew :shared:generateDummyFramework --no-daemon`, then `cd iosApp && pod install`. If `pod install` also rewrites `iosApp.xcodeproj/project.pbxproj`, revert that file — it is pre-existing drift, not part of the pin. Reverting is safe: CI builds only the `iosApp` and `iosAppUITests` schemes, and the `iosAppTests` target (whose `Pods_iosAppTests.framework` reference `pod install` adds) is not built anywhere.

## 4. Changeset and review

Add a changeset under `change-logs/<version>/changesets/` describing the user-visible effect and linking the upstream renderer change. PR, review by a non-author, hosted CI, squash merge. Runtime verification of the new renderer on device is a separate gate owned by QA — record in the PR body which fixture and criterion will be used.

## Failure modes seen

- Skipping the real `pod install` and editing the lockfile by hand: the consumer contract and podspec checksums will not match.
- `pod install` pbxproj drift: revert it, do not commit it "because it changed anyway".
