# Visual Testing Build Gating Spec — 2026-06-26

## Implementation Status — 2026-06-28

Implemented in `ad3/task258-visual-gating`.

- `compose/shared/build.gradle.kts` defines `slockVisualFixtures` / `SLOCK_VISUAL_FIXTURES` and only adds `src/visualFixtures/kotlin` to `commonMain` when the opt-in is true.
- KMP visual `@Page` registrations now live in `compose/shared/src/visualFixtures/kotlin/build/raft/app/VisualFixturePages.kt`.
- Product `commonMain` keeps reusable visual fixture composables only where they wrap real product components; production builds no longer register the `compose_*_visual` pages.
- `RouterPage` no longer links visual-only fixture routes from the production developer router.
- `:shared:verifyNoProductionVisualPages` scans production source sets and is wired into `check`.
- `docs/visual-testing-android-provider.md` documents that Android connected captures must pass `-PslockVisualFixtures=true`; the CLI/manifest now live in `third_party/slock-source/packages/visual-testing`.

## Problem

KMP visual-testing pages such as `compose_thread_message_visual` are currently declared as ordinary `commonMain` Kuikly pages:

```kotlin
@Page(THREAD_MESSAGE_VISUAL_PAGE, supportInLocal = true)
internal class ComposeThreadMessageVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadMessageVisualScreen() }
    }
}
```

That makes screenshot fixtures easy to launch, but it also means:

- normal debug builds compile visual-only `@Page` registrations;
- release builds compile visual-only fixture screens unless minification strips them;
- the production page registry can accidentally learn routes that only exist for visual capture;
- future screenshot fixtures can drift into product code because the current convention is "put it beside the component".

The desired contract is stricter: product debug/release packages should not contain visual fixture pages or their fixture payloads. Only the visual-test package used by the visual capture pipeline should compile and register them.

## Decision

Use compile-time Gradle source-set gating. Do not use runtime `if (debug)` guards around `@Page` classes.

Runtime guards leave the class and KSP annotation in the build. The safe boundary is: visual-only files live outside `commonMain` / production Android source sets, and Gradle adds them only for the visual capture build.

## Recommended Shape

Create a dedicated fixture source directory under the shared module:

```text
compose/shared/src/visualFixtures/kotlin/build/raft/app/
```

Move visual-only code there:

- `@Page(..._visual...)` classes;
- visual-only route constants, when product code does not need them;
- visual fixture screen wrappers such as `ThreadMessageVisualScreen`;
- fixture payload builders and fake data used only by screenshots;
- capture-only adapters that exist only to crop or frame a component.

Keep product component implementations in `commonMain`, for example:

- `ThreadMessageItem`;
- `ThreadComposerContent`;
- `ThreadFilesContent`;
- `MembersAgentDetailContent`;
- shared design tokens and reusable components.

The fixture source directory is added to `commonMain` only when the visual capture build explicitly opts in:

```kotlin
// compose/shared/build.gradle.kts
val slockVisualFixturesEnabled =
    providers.gradleProperty("slockVisualFixtures")
        .orElse(providers.environmentVariable("SLOCK_VISUAL_FIXTURES"))
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

kotlin {
    sourceSets {
        val commonMain by getting {
            if (slockVisualFixturesEnabled) {
                kotlin.srcDir("src/visualFixtures/kotlin")
            }
        }
    }
}
```

The Android visual capture command then builds with the opt-in flag:

```bash
./gradlew \
  -PslockVisualFixtures=true \
  :androidApp:connectedConnectedTestAndroidTest
```

Normal local debug/release commands do not pass the flag:

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease
./gradlew :shared:compileDebugKotlinAndroid
./gradlew :shared:compileReleaseKotlinAndroid
```

In those builds the fixture files are not source inputs, so visual `@Page` annotations are invisible to KSP and no visual page classes are emitted.

## Optional Hardening

The property-gated source directory is the minimum viable boundary. A later hardening pass can add a dedicated Android build type if we want a visible package identity:

```kotlin
// compose/androidApp/build.gradle.kts
android {
    testBuildType = "visualTest"
    buildTypes {
        create("visualTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".visual"
            matchingFallbacks += listOf("debug")
            buildConfigField("Boolean", "SLOCK_VISUAL_FIXTURES", "true")
        }
    }
}
```

If this is added, the visual pipeline should build only the `visualTest` package and still pass `-PslockVisualFixtures=true` so the shared fixture source directory is present only for that Gradle invocation.

Do not rely on `BuildConfig.SLOCK_VISUAL_FIXTURES` alone for the `@Page` classes. `BuildConfig` is useful for host-side diagnostics or route allowlists, but it is a runtime signal. It cannot stop annotation processing or class emission.

## Migration Plan

1. Add the `slockVisualFixtures` Gradle property gate in `compose/shared/build.gradle.kts`.
2. Create `compose/shared/src/visualFixtures/kotlin/build/raft/app/`.
3. Move each visual-only `@Page` class and its fixture-only screen/payload code out of product files.
4. Keep public/product composables in `commonMain`; fixture files should call those real components instead of copying UI.
5. Update Android visual capture docs/scripts to pass `-PslockVisualFixtures=true`.
6. Add a verification task that fails if production source sets contain visual routes.
7. Run normal debug/release compile without the flag, then run visual captures with the flag.

## Suggested Verification Task

Add a lightweight Gradle or script check that scans production source directories:

```text
compose/shared/src/commonMain
compose/shared/src/androidMain
compose/androidApp/src/main
```

It should fail on either of these patterns outside `src/visualFixtures`:

```text
@Page(.*_VISUAL_PAGE
@Page(".*_visual"
```

It should allow production smoke/developer pages only if their route names do not end in `_visual` and they are explicitly documented as product-debug surfaces.

## Fixture Authoring Rules

- Visual fixtures must render real product components, not hand-drawn substitutes.
- Visual fixtures may own fake data and framing/cropping wrappers.
- Product components must not depend on visual fixture payload builders.
- New visual routes must live in `src/visualFixtures/kotlin` and be reachable only when `slockVisualFixtures=true`.
- If a visual fixture needs a helper that product code also needs, move only that helper to `commonMain`; keep sample data and `@Page` registration gated.

## Implemented Split Points

The first implementation moved the visual `@Page` registrations for these product source files into `VisualFixturePages.kt`:

- `ThreadPage.kt`: `ComposeThreadMessageVisualPage`, `ComposeThreadMessageRowVisualPage`, `ComposeThreadHeaderVisualPage`, `ComposeThreadReactionAtlasVisualPage`, channel panel visual pages, and related visual fixture screens/payloads.
- `ThreadComposerPage.kt`: `ComposeThreadComposerVisualPage` and composer fixture payloads.
- `ThreadFilesContent.kt`: `ComposeThreadFilesVisualPage` and file-list fixture data.
- `MembersPage.kt`: `ComposeMembersAgentDetailVisualPage` and visual-only member fixture data.
- `SettingsHomePage.kt`: bottom-tab, settings-row/page, home-search, saved, activity, create-channel, auth-choose-server, and app-shell visual pages.
- `SharedCreateAgentDialog.kt`: create-agent dialog visual page and sample payloads.
- `AuthPages.kt`: auth input visual pages.
- `TokenPaletteVisualPage.kt`: token palette visual fixture page registration.

`SlockLucideIconGalleryPage.kt` and `SlockTypographyProbePage.kt` remain ordinary product-debug developer surfaces because their routes do not end in `_visual` and the verifier intentionally allows documented product-debug pages.

## Expected Outcome

- `assembleDebug` and `assembleRelease` do not compile visual fixture pages.
- Visual capture builds still launch the same `compose_*_visual` page names by passing the explicit Gradle opt-in.
- The screenshot harness keeps its current page-name contract while product packages lose fixture-only classes, fake payloads, and accidental route registrations.
