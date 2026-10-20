# Spec: Merge Outer Android App into KMP `compose/androidApp`

> **Historical (2026-10-20):** implemented — `compose/androidApp` is the only Android app. Kuikly references reflect the pre-CMP state.

**Status:** Draft  
**Author:** Codex analysis  
**Date:** 2026-06-05

---

## 1. Problem Statement

The Slock project currently has **two separate Gradle projects** that form one Android
application:

| Layer | Project root | Module | Role |
|---|---|---|---|
| Outer Android | `Slock/` | `:app` | Production app host (MainActivity, auth, network, realtime, UI, SQLDelight, tests) |
| Inner KMP | `Slock/compose/` | `:shared` | Cross-platform business logic (commonMain + androidMain/ohosArm64Main) |
| Inner KMP | `Slock/compose/` | `:androidApp` | Thin Kuikly dev shell — barely used in production |

The outer `:app` consumes `:shared` as a **MavenLocal artifact**
(`build.raft.app:shared-android:1.0.0`), not as a Gradle project dependency. This forces every
shared change to go through a publish round-trip:

```bash
compose/gradlew :shared:publishAndroidReleasePublicationToMavenLocal
./gradlew :app:compileDebugKotlin   # outer project
```

This is slow, fragile, and confusing for new developers. The outer `:app` and inner
`:androidApp` are **both Android application modules** — one is production, the other is a
vestigial shell.

**Goal**: Eliminate the outer Gradle project entirely. Make `compose/androidApp` the single
production Android host, depending on `:shared` as a direct project dependency.

---

## 2. Target Architecture

**Root decision**: keep `compose/` as the only Gradle project root and keep the
Gradle wrapper in `compose/`. The repository root remains a meta/root directory
for docs, scripts, `.agents/`, submodules, and non-Gradle project assets.

```
Slock/                          ← git repo root (no longer a Gradle project root)
├── compose/                    ← **NEW single Gradle project root**
│   ├── gradlew                 ← sole Gradle wrapper entry point
│   ├── settings.gradle.kts     ← includes :androidApp, :shared
│   ├── build.gradle.kts        ← root build config (plugins, tasks)
│   ├── buildSrc/
│   ├── gradle/
│   │   └── libs.versions.toml  ← merged Android/App catalog
│   ├── androidApp/             ← **MERGE TARGET** — full production Android app
│   │   ├── build.gradle.kts    ← merged build config from outer :app
│   │   ├── proguard-rules.pro
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml      ← merged
│   │       │   ├── java/build/raft/app/   ← all outer app source
│   │       │   ├── res/                     ← all outer app resources
│   │       │   └── assets/                  ← brand assets
│   │       ├── test/                        ← outer unit tests
│   │       ├── androidTest/                 ← outer instrumented tests
│   │       └── connectedTest/               ← outer connectedTest variant
│   ├── shared/                              ← unchanged
│   ├── ohosApp/                             ← unchanged
│   └── iosApp/                              ← unchanged
├── .agents/                    ← unchanged
├── docs/                       ← unchanged (updated references)
├── scripts/                    ← updated paths
├── third_party/                ← unchanged
├── .gitignore                  ← merged
├── .gitmodules                 ← unchanged
└── AGENTS.md                   ← updated
```

All outer Gradle scaffolding files are **removed**:
- `Slock/settings.gradle.kts`
- `Slock/build.gradle.kts`
- `Slock/gradle.properties`
- `Slock/gradle/`
- `Slock/gradlew` / `Slock/gradlew.bat`
- `Slock/local.properties`
- generated root-local Gradle/IDE state such as `Slock/.idea/`, `Slock/.kotlin/`,
  `Slock/.gradle/`, `Slock/build/` if present

The compose project's gradle wrapper (`compose/gradlew`) becomes the sole entry point.
Do **not** move `compose/gradlew` to the repository root in this migration; doing so
would reintroduce ambiguity about which directory is the Gradle project root.

---

## 3. What Moves Where

### 3.1 Source Code: `app/src/main/java/` → `compose/androidApp/src/main/java/`

All packages under `build.raft.app.*` move as-is. The `compose/androidApp` already has
`build.raft.app` sources that will be **replaced** by the production versions.

| Outer app file | Merge action |
|---|---|
| `RaftApplication.kt` | Take outer; drop inner `KRApplication.kt` |
| `MainActivity.kt` | Take outer |
| `RaftLegacyHostActivity.kt` | Take outer; drop inner `KuiklyRenderActivity.kt` |
| `RaftHostContract.kt` | Take outer |
| `SlockKuiklyNetworkModule.kt` | Take outer |
| `SlockKuiklyPerformanceDiagnostics.kt` | Take outer |
| `SlockKuiklyTextFieldViews.kt` | Take outer |
| `SlockLucideIconRenderView.kt` | Take outer |
| `LegacySharedShellHelpers.kt` | Take outer |
| `core/data/auth/*.kt` (7 files) | Take outer |
| `core/data/diagnostics/*.kt` (5 files) | Take outer |
| `core/data/local/*.kt` (3 files) | Take outer |
| `core/data/mock/*.kt` (3 files) | Take outer |
| `core/data/network/*.kt` (1 file) | Take outer |
| `core/data/preferences/*.kt` (1 file) | Take outer |
| `core/data/realtime/*.kt` (4 files) | Take outer |
| `core/data/sample/*.kt` (1 file) | Take outer |
| `core/data/storage/*.kt` (1 file) | Take outer |
| `core/model/RaftModels.kt` | Take outer; review overlap with shared models |
| `core/state/ScreenLoadState.kt` | Take outer |
| `feature/**/*.kt` | Take outer |
| `shared/**/*.kt` | Take outer |
| `ui/**/*.kt` | Take outer |
| Inner `compose/androidApp/.../adapter/*.kt` | **DROP** — outer `RaftAdapters` replaces them |
| Inner `compose/androidApp/.../module/*.kt` | **DROP** — outer `RaftBridgeModule` / `SlockKuiklyNetworkModule` replaces them |
| Inner `KRApplication.kt` | **DROP** — outer `RaftApplication.kt` replaces |
| Inner `KuiklyRenderActivity.kt` | **DROP** — outer `RaftLegacyHostActivity.kt` replaces |
| Inner `SkinIniFile.kt` | **DROP** — unused in production |

### 3.2 Resources: `app/src/main/res/` → `compose/androidApp/src/main/res/`

All drawables, mipmaps, fonts, layouts, values, XML configs move. Check for conflicts with
inner androidApp resources (the inner project has minimal resources):

| Outer resource | Merge action |
|---|---|
| `layout/activity_raft.xml` | Take outer; drop inner `activity_hr.xml` |
| `drawable/*.xml` | Take all outer |
| `mipmap-*/*` | Take all outer (launcher icons) |
| `values/*.xml` (themes, colors, strings) | Take outer |
| `values-zh-rCN/strings.xml` | Take outer |
| `values-v31/themes.xml` | Take outer |
| `xml/backup_rules.xml` | Take outer |
| `xml/data_extraction_rules.xml` | Take outer |
| `font/*` | Take outer (Space Grotesk fonts) |
| Inner `res/layout/activity_hr.xml` | **DROP** |
| Inner `res/values/styles.xml` | **DROP** → outer `themes.xml` replaces |
| Inner `res/xml/network_security_config.xml` | **Do not enable in production**; current file globally permits cleartext traffic |

### 3.3 AndroidManifest: merge

Take the outer manifest as base.

Do **not** blindly preserve the inner app's `network_security_config.xml` reference.
The current inner file enables `cleartextTrafficPermitted="true"` globally and was only
part of the Kuikly dev shell. The production manifest currently works without this
attribute. If local cleartext traffic is still needed for a debug/dev workflow, add a
debug-only manifest/resource override or a domain-scoped network security config instead
of enabling cleartext globally in production.

### 3.4 SQLDelight schemas

Only `:shared` owns SQLDelight. `:androidApp` must not contain `src/main/sqldelight`,
apply the SQLDelight plugin, or generate an app-local database.

### 3.5 Assets

Move `app/src/main/assets/brand/` → `compose/androidApp/src/main/assets/brand/`.

### 3.6 Test code

| Outer | → Inner |
|---|---|
| `app/src/test/java/` (21 files) | `compose/androidApp/src/test/java/` |
| `app/src/androidTest/java/` (8 files) | `compose/androidApp/src/androidTest/java/` |
| `app/src/connectedTest/res/` | `compose/androidApp/src/connectedTest/res/` |

### 3.7 ProGuard

Move `app/proguard-rules.pro` → `compose/androidApp/proguard-rules.pro`.

### 3.8 Build config

The `compose/androidApp/build.gradle.kts` is completely rewritten. Key changes:

1. **Namespace** changes from `build.raft.app` → `build.raft.app`
2. **applicationId** stays `build.raft.app`
3. **compileSdk** bumps from 34 → 36 (match outer)
4. **minSdk** is 29 (Android 10; raised from the migration-time value 24), **targetSdk** is 36
5. **Kotlin JVM target** bumps from 1.8 → 11
6. **Dependencies**: merge outer `app/build.gradle.kts` with `implementation(project(":shared"))`
7. **SQLDelight plugin** added with the outer database config
8. **Build types**: add `connectedTest` variant, `release` config, `buildConfig` feature
9. **connectedTest** source set configuration
10. **connectedTest** resolution strategy for lifecycle/savedstate
11. **Secrets/buildConfig**: preserve the `SLOCK_GOOGLE_SERVER_CLIENT_ID` Gradle
    property/environment fallback and both `BuildConfig` fields from the outer app

**Dependency changes**:
- Remove: all old inner androidApp deps (recyclerview 1.2.1, appcompat 1.3.1, picasso, glide)
- Keep: `implementation(project(":shared"))`
- Add: all outer `app/build.gradle.kts` dependencies, but with versions from the merged
  version catalog
- The Android host directly imports Kuikly Android render APIs
  (`com.tencent.kuikly.core.render.android.*`). Today `:shared` exposes
  `core-render-android` with an `api(...)` dependency, so those render types may be
  available through `implementation(project(":shared"))`. Validate this with
  `:androidApp:compileDebugKotlin`. If compilation shows missing Kuikly render types,
  add an explicit `implementation("com.tencent.kuikly-open:core-render-android:<kuiklyAndroidVersion>")`
  in `:androidApp`.
- Do not assume `com.tencent.kuikly-open:compose-android` comes transitively from
  `:shared`; the current shared build exposes `core-render-android`, while common
  Kuikly compose/runtime artifacts are implementation dependencies of `:shared`.

### 3.9 Version catalog

**Discovery**: `compose/gradle/libs.versions.toml` already exists (AGP 8.13.2, Kotlin 2.0.21)
but is **not used** — actual versions come from `buildSrc/src/main/java/KotlinBuildVar.kt`
(Kotlin 2.1.21, Kuikly 2.20.1-2.1.21). Meanwhile the outer `gradle/libs.versions.toml` has
Kotlin 2.3.20, AGP 8.13.2.

**Decision**: Consolidate into a single `compose/gradle/libs.versions.toml`:

1. **Start from outer catalog** (newer Kotlin 2.3.20, AGP 8.13.2, all AndroidX/OkHttp/etc.)
2. **Add Kuikly entries**:
   - `kuikly = "2.20.1"`
   - `kuiklyAndroid = "2.20.1-2.1.21"`
   - `kuiklyOhos = "2.20.1-2.0.21-ohos"`
   - `kuikly-core = { module = "com.tencent.kuikly-open:core", version.ref = "kuiklyAndroid" }`
   - `kuikly-core-render-android = { module = "com.tencent.kuikly-open:core-render-android", version.ref = "kuiklyAndroid" }`
3. **Add KMP deps**: Ktor, kotlinx-serialization, kotlinx-coroutines, sqldelight
4. **Keep `buildSrc/KotlinBuildVar.kt` for Phase 1 unless all references are migrated
   together**. The compose root buildscript, shared builds, and OHOS build
   currently use `Version`/`BuildPlugin`. Version catalogs cannot compute
   `"${kuikly}-${kotlin}"` dynamically, so either keep `buildSrc` or replace it with
   explicit full artifact versions everywhere in one commit.

If the Kotlin 2.3.20 bump breaks Kuikly compose compiler, fall back: keep outer catalog's
AGP/AndroidX versions, but pin Kotlin to 2.1.21 for all modules. The Kuikly compose plugin
is the gating factor.

### 3.10 Root build.gradle.kts

`compose/build.gradle.kts` needs updating:
- Use direct Gradle validation commands; the legacy wrapper task has been
  removed.
- Update plugin versions to match outer catalog
- Update all root-relative file references from `docs/...` and `scripts/...` to
  `../docs/...` and `../scripts/...`, because the task now runs with `compose/` as
  `layout.projectDirectory`.

### 3.11 settings.gradle.kts

`compose/settings.gradle.kts` needs updating:
- Update `pluginManagement` repos to include Google filtered repos (from outer)
- Keep the Tencent Maven and `mavenLocal()` repositories required by Kuikly/forked
  dependencies.
- Add `dependencyResolutionManagement` structure from the outer project, but do not
  enable `RepositoriesMode.FAIL_ON_PROJECT_REPOS` until project-level dependency
  repositories in compose build files are audited. `publishing.repositories` can stay
  project-local, but dependency repositories must not be broken accidentally.
- `rootProject.name` stays `"compose"` for the Gradle project. Android Studio should
  open `Slock/compose`.
- Remove `include(":h5App")`. The H5 target is not part of the product plan and the
  current workspace does not contain `compose/h5App/`.

### 3.12 gradle.properties

Merge properties from outer `gradle.properties` into `compose/gradle.properties`:
- JVM args, AndroidX, nonTransitiveRClass, kotlin code style

### 3.13 Scripts

Update all scripts that reference the outer project structure:
- The legacy shared-release validation wrapper has been removed; use direct
  `compose/gradlew` validation commands.
- `scripts/run_live_slock_db_flow.sh`: change the Gradle invocation from root
  `testDebugUnitTest` / `:app:*` style to `compose/gradlew :androidApp:testDebugUnitTest`
  with the same focused test filter.
- `scripts/capture_ohos_screenshot.sh` and OHOS helpers can keep using the repository
  root as their script root, but any Gradle invocation must be checked explicitly.
- Other scripts (`build_kuikly_fork.sh`, `sync_kuikly_ai_skills.sh`, etc.) should be
  audited. Do not blindly replace `$ROOT_DIR` with `$ROOT_DIR/compose`; many scripts
  intentionally operate on repo-root paths such as `.agents/` and `third_party/`.

### 3.14 AGENTS.md

Update all build/validation instructions:
- Remove `publishAndroidReleasePublicationToMavenLocal` step
- Change all `./gradlew :app:compileDebugKotlin` to `compose/gradlew :androidApp:compileDebugKotlin`
- Update `compose/shared` workflow to note it's now a direct project dependency

---

## 4. What Does NOT Move / Change

- **`compose/shared/`**: No changes needed. It already compiles for Android as a library.
- **`compose/ohosApp/`**: Unchanged.
- **`compose/iosApp/`**: Unchanged.
- **Mini app target**: Removed from the KMP project; do not include or validate
  `:miniApp` in the compose Gradle build.
- **`compose/h5App/`**: Deleted/not retained. Remove the include from
  `compose/settings.gradle.kts` and remove H5 from validation/docs/scripts.
- **`third_party/`**: All submodules unchanged.
- **`.agents/`**: Skills and rules unchanged.
- **`docs/`**: Audit docs preserved. Only the new spec doc added.
- **`.gitmodules`**: Unchanged (all submodules are under `third_party/` and `compose/` is
  not a submodule).

---

## 5. Risks & Mitigations

### 5.1 Namespace Collision
The inner `compose/androidApp` uses namespace `build.raft.app`; the shared module's
androidMain source set also lives under `build.raft.app`. After merge, the androidApp
will switch to `build.raft.app` namespace. Ensure no package conflicts.

**Mitigation**: The outer app already imports from `build.raft.app.*` — these will
resolve through the `:shared` project dependency normally.

### 5.2 Duplicate SQLDelight Database Names
The migration should avoid duplicate generated `RaftDatabase` classes by retiring the
Android-only SQLDelight schema. Only `:shared` should configure SQLDelight.

### 5.3 Kuikly Version Mismatch
The outer app references `com.tencent.kuikly-open:compose-android:2.20.1-2.1.21`. The
shared module references Kuikly via `buildSrc/KotlinBuildVar.kt`. These must match.

**Mitigation**: Both currently use the same version from `buildSrc`. After merge, the
`core-render-android` dependency may come transitively from `:shared` because it is
currently declared as an `api` dependency in `androidMain`. Validate this with
compilation. Do not describe `compose-android` as an `api` transitive dependency unless
the shared build is changed to expose it.

### 5.4 Kotlin/AGP Version Drift
Outer project: Kotlin 2.3.20 + AGP 8.13.2. Compose `buildSrc`: Kotlin 2.1.21 + AGP 7.4.2
(hardcoded in `build.gradle.kts`). Compose `libs.versions.toml`: AGP 8.13.2 + Kotlin 2.0.21
(exists but unused). Three different version sources — a mess.

**Mitigation**: The merge is the forcing function to consolidate. The outer project's AGP
8.13.2 should work (it's already in compose's unused version catalog). The risk is
Kotlin — Kuikly's compose compiler plugin at 2.20.1-2.1.21 may not support Kotlin 2.3.20.

**Action**: Test `compose/shared` compilation with outer's Kotlin 2.3.20 before
proceeding. If it fails, keep Kotlin at 2.1.21 across the board.

### 5.5 `compose/build.ohos.gradle.kts` and `compose/settings.ohos.gradle.kts`
The OHOS build uses separate Kotlin/Native toolchain versions. Merging version catalogs
must not break the OHOS build path.

**Mitigation**: The OHOS settings file (`settings.ohos.gradle.kts`) is completely separate
and uses its own `build.ohos.gradle.kts` with its own plugin versions. It is triggered via
`-c settings.ohos.gradle.kts`. Keep this path unchanged.

### 5.6 Build Script Migration
Legacy CI validators may reference the old structure.

**Mitigation**: Update scripts as part of the migration. All script paths change from
`$ROOT_DIR` (outer root) to `$ROOT_DIR/compose` or simply `$ROOT_DIR` if compose becomes
the root.

Because the repository root remains the script root, the safer rule is:
- script asset/doc/submodule paths stay relative to repo root;
- Gradle invocations run from `Slock/compose` using `compose/gradlew`;
- Gradle tasks copied from root to compose must use `../docs` and `../scripts`
  for repository-level files.

### 5.7 IDE Configuration
The outer `.idea/` directory has Android Studio project configuration. After migration,
Android Studio needs to re-import from `compose/`.

**Mitigation**: Document the new "Open" path (`compose/` directory) in AGENTS.md. Delete
outer `.idea/`.

---

## 6. Implementation Plan

### Phase 1: Preparation (non-destructive)

1. **Audit Kotlin 2.3.20 compatibility** with Kuikly compose compiler plugin.
   ```bash
   cd compose
   # Temporarily bump Kotlin to 2.3.20 in build.gradle.kts
   ./gradlew :shared:compileDebugKotlinAndroid
   ```
   If this fails, determine the maximum compatible Kotlin version.

2. **Create merged version catalog** at `compose/gradle/libs.versions.toml`.
   - Start from outer `gradle/libs.versions.toml`.
   - Add explicit Kuikly version strings (`kuiklyAndroid`, `kuiklyOhos`) from
     `compose/buildSrc`.
   - Add Ktor, kotlinx-serialization, kotlinx-coroutines, sqldelight entries.
   - Decide whether `buildSrc` remains for Phase 1. If removing it, replace every
     `Version.*` and `BuildPlugin.*` reference in the compose project in the same change.

3. **Update `compose/settings.gradle.kts`** to use the version catalog and add
   dependencyResolutionManagement.
   - Remove `include(":h5App")`; H5 is intentionally not retained.

4. **Update `compose/build.gradle.kts`** to use version catalog aliases and bump
   Kotlin/AGP.
   - When moving root verification tasks, fix `docs/...` and `scripts/...` paths to
     point outside `compose/`.

5. **Backup** the outer project (just a git commit on a branch is fine).

### Phase 2: androidApp Build Config

6. **Rewrite `compose/androidApp/build.gradle.kts`**:
   - Use the merged version catalog aliases.
   - Set namespace to `build.raft.app`.
   - Merge all plugins, android config, dependencies, sqldelight config.
   - Add `connectedTest` build type and source set.
   - Verify `implementation(project(":shared"))` is present.

### Phase 3: Source Migration

7. **Move source files** from `app/src/main/java/` to `compose/androidApp/src/main/java/`.
   - Preserve package structure.
   - Delete the old inner androidApp sources that are being replaced.

8. **Move resources** from `app/src/main/res/` to `compose/androidApp/src/main/res/`.
   - Merge XML files where conflicts exist.
   - Do not enable the inner global-cleartext network security config in production.
     Keep it only if converted to a debug-only/domain-scoped config.

9. **Move AndroidManifest** — use outer manifest as base, add inner entries.

10. **Move assets** from `app/src/main/assets/` to `compose/androidApp/src/main/assets/`.

11. **Keep SQLDelight shared-only**. `:androidApp` must not define its own schema or
    generated database.

12. **Move ProGuard** rules.

### Phase 4: Test Migration

13. **Move unit tests** from `app/src/test/` to `compose/androidApp/src/test/`.

14. **Move instrumented tests** from `app/src/androidTest/` to
    `compose/androidApp/src/androidTest/`.

15. **Move connectedTest resources**.

### Phase 5: Root Cleanup

16. **Update `compose/build.gradle.kts`** and validation docs to use direct Gradle
    commands instead of the removed wrapper.

17. **Merge `gradle.properties`** entries.

18. **Update scripts** to use new paths.

19. **Update AGENTS.md** and related docs.

20. **Delete outer Gradle scaffolding**:
    - `Slock/settings.gradle.kts`
    - `Slock/build.gradle.kts`
    - `Slock/gradle.properties`
    - `Slock/gradle/`
    - `Slock/gradlew`, `Slock/gradlew.bat`
    - `Slock/local.properties`
    - `Slock/app/` (entire directory, after migration confirmed)
    - `Slock/.idea/`
    - `Slock/.kotlin/`
    - `Slock/.gradle/`
    - `Slock/build/`
    Do not delete repository-level docs, scripts, `.agents`, or submodules.

21. **Keep `compose/gradlew` in `compose/`**. Do not move it to repo root in this
    migration.

### Phase 6: Validation

22. **Full build validation**:
    ```bash
    cd compose
    ./gradlew :shared:compileDebugKotlinAndroid
    ./gradlew :androidApp:compileDebugKotlin
    ./gradlew :androidApp:assembleDebug
    ```

23. **Test suite**:
    ```bash
    ./gradlew :androidApp:testDebugUnitTest
    ```

24. **OHOS validation**:
    ```bash
    ./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64
    ```

25. **Manual smoke test** on device/emulator.

---

## 7. Verification Checklist

- [ ] `compose/gradle/libs.versions.toml` exists and is complete
- [ ] Kuikly version source is single and explicit (`buildSrc` kept intentionally or all
      `Version`/`BuildPlugin` references migrated)
- [ ] `compose/settings.gradle.kts` includes `:androidApp`, `:shared`
- [ ] `compose/settings.gradle.kts` does not include `:h5App`
- [ ] `compose/build.gradle.kts` plugins use version catalog
- [ ] `compose/build.gradle.kts` root verification tasks use `../docs` / `../scripts`
      for repository-level files
- [ ] `compose/androidApp/build.gradle.kts` rewritten with merged config
- [ ] `compose/androidApp/build.gradle.kts` preserves `SLOCK_GOOGLE_SERVER_CLIENT_ID`
      and `BuildConfig` fields
- [ ] All outer `.kt` sources moved to `compose/androidApp/src/main/java/`
- [ ] All inner androidApp adapter/module sources removed
- [ ] All resources merged (no missing drawables, strings, themes)
- [ ] Inner global-cleartext `network_security_config.xml` is not enabled in production
- [ ] AndroidManifest merged correctly
- [ ] Android-only SQLDelight schema/migrations deleted; shared schema remains
- [ ] Assets moved
- [ ] Test sources moved
- [ ] ProGuard rules moved
- [ ] `:shared:compileDebugKotlinAndroid` passes
- [ ] `:androidApp:compileDebugKotlin` passes
- [ ] `:androidApp:assembleDebug` produces a valid APK
- [ ] `:androidApp:testDebugUnitTest` passes (or acceptable failure count)
- [ ] `:shared:compileKotlinOhosArm64` passes
- [ ] Direct Gradle validation commands are documented and pass
- [ ] Scripts directory updated with new paths
- [ ] AGENTS.md updated with new build instructions
- [ ] Outer Gradle scaffolding deleted
- [ ] git history preserved (use `git mv` for file moves)
- [ ] Manual APK install + smoke test passes

---

## 8. Open Questions

1. **Kotlin version**: What's the max Kotlin version Kuikly compose compiler plugin
   supports? Currently outer project uses 2.3.20, inner uses 2.1.21. Need to verify
   compatibility.

2. **Root location**: Should `compose/` become the repo root, or stay as a subdirectory
   with the gradle wrapper at the repo root? Either works; moving compose up one level is
   disruptive to git history.

   **Decision**: Keep `compose/` as subdirectory, keep gradlew there. Repo root
   becomes a "meta" directory with docs, scripts, `.agents/`, `third_party/`.

3. **H5/Mini App targets**: H5 and MiniApp are intentionally not retained; remove their
   includes and any target-specific validation/docs/scripts.

4. **Version catalog vs buildSrc**: Currently compose uses `buildSrc` for Kuikly versions.
   Should we keep both or consolidate entirely to version catalog?

   **Recommendation**: Keep `buildSrc` in the first structural migration unless version
   cleanup is done as a separate, fully validated change. If consolidating into the
   catalog, use explicit full version strings (`2.20.1-2.1.21`,
   `2.20.1-2.0.21-ohos`); do not rely on computed catalog versions.

5. **Gradle wrapper**: The outer project currently uses Gradle 8.13; the inner compose
   wrapper uses Gradle 8.5 while its Android plugins are pinned to AGP 7.4.2. After
   choosing the final AGP/Kotlin pair, update `compose/gradle/wrapper/gradle-wrapper.properties`
   to a compatible Gradle version. Keep the wrapper under `compose/`.

6. **`SkinIniFile.kt`**: This file in inner androidApp references skin/theme switching.
   Is this needed in production? If not, drop it.

---

## 9. References

- `AGENTS.md` — current build workflow and project constraints
- External `botiverse/slock` checkout (`SLOCK_SOURCE_REPO`) — upstream
  TypeScript server reference; record the exact source SHA used for any
  contract decision.
- `compose/buildSrc/` — current Kuikly version definitions
- `gradle/libs.versions.toml` — outer project version catalog
