# Kuikly release-set pin upgrade recipe

Owner: Mahua · Verified at: 2026-10-18 · Status: current

How to bump the immutable Kuikly release set (e.g. `2.28.0-raft.N` → `raft.N+1`) in this repo. The same recipe has been used for several consecutive bumps (#3025, #3073).

## 1. Bump the pin family atomically

Replace the version string in all of, and only, these files:

- `compose/buildSrc/src/main/java/KotlinBuildVar.kt`
- `compose/gradle/libs.versions.toml`
- `compose/gradle/kuikly-release-closure.json` (37 GAVs)
- `scripts/ci/verify_kuikly_release_manifest.sh`
- `scripts/ci/fetch_kuikly_release_artifact.py`
- `scripts/ci/verify_kuikly_ohos_renderer.sh`
- `scripts/ci/test_kuikly_raft_consumer_contract.py`
- `compose/iosApp/Podfile`
- `compose/ohosApp/entry/oh-package.json5`
- `compose/ohosApp/scripts/test_image_decode_policy_source_contract.mjs`
- `THIRD_PARTY_NOTICES.md`

Do **not** hand-edit `compose/iosApp/Podfile.lock` or `compose/ohosApp/entry/oh-package-lock.json5` — they are regenerated in step 3. Never touch dated historical docs.

## 2. Run the source gates

- `scripts/ci/verify_kuikly_ohos_renderer.sh` — downloads and verifies the OHOS HAR (prints its sha256).
- `scripts/ci/verify_kuikly_release_manifest.sh` — must end in `ALL_COMPLETE_EXACT`.
- `scripts/ci/test_kuikly_raft_consumer_contract.py` — static consumer contract (fails until step 3 regenerates the lockfiles).
- `scripts/ci/check_source_constraints.py` and `git diff --check`.

## 3. Regenerate the native lockfiles for real

- iOS: `cd compose && ./gradlew :shared:generateDummyFramework --no-daemon`, then `cd iosApp && pod install`. If `pod install` also rewrites `iosApp.xcodeproj/project.pbxproj`, revert that file — it is pre-existing drift, not part of the pin.
- OHOS: `ohpm install` in `compose/ohosApp/entry` (ohpm from DevEco Studio's toolchain on PATH).

## 4. Changeset and review

Add a changeset under `change-logs/<version>/changesets/` describing the user-visible effect and linking the upstream renderer change. PR, review by a non-author, hosted CI, squash merge. Runtime verification of the new renderer on device is a separate gate owned by QA — record in the PR body which fixture and criterion will be used.

## Failure modes seen

- Skipping the real `pod install` / `ohpm install` and editing lockfiles by hand: the consumer contract and podspec checksums will not match.
- `pod install` pbxproj drift: revert it, do not commit it "because it changed anyway".
