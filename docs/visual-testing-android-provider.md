# Android Visual Testing Provider

The canonical visual-testing CLI, case manifest, shared token manifest, React
provider runner, diff/report/site generator, and package metadata live in the
React/source checkout:

```text
<slock-source>/packages/visual-testing/
```

Do not create a retired in-repo Slock source directory inside this Android
repo. The source repo is external and an in-repo directory would show up as
untracked local state.
Point the shim at an external checkout with `SLOCK_SOURCE_REPO` or directly at
the CLI with `SLOCK_VISUAL_TESTING_CLI`.

The Android repo is the Android/KMP provider consumer. It owns Android connected
capture code, visual fixture source-set gating, result directories, and
generated report artifacts. Do not add a second CLI or manifest copy under this
repo.

Use the Android compatibility shim from the Android repo root:

```bash
export SLOCK_SOURCE_REPO=/path/to/slock-source
git -C "$SLOCK_SOURCE_REPO" rev-parse HEAD  # record this SHA with evidence
node scripts/slock-visual-testing.mjs scan
node scripts/slock-visual-testing.mjs validate --manifest shared
node scripts/slock-visual-testing.mjs token-audit
node scripts/slock-visual-testing.mjs capture --providers react
node scripts/slock-visual-testing.mjs capture --providers android --command './scripts/capture-android-visuals.sh'
node scripts/slock-visual-testing.mjs diff --baseline react --current android
node scripts/slock-visual-testing.mjs report --baseline react --current android
node scripts/slock-visual-testing.mjs site --baseline react --current android
node scripts/slock-visual-testing.mjs publish-gh-pages --baseline react --current android --dry-run
```

## GitHub Pages Publish Target

Publish Android/KMP visual reports to the mobile repository Pages branch, not to
the React/web repository. From the Android repo, always dry-run first and verify
the target says `origin/gh-pages:visual-testing` with the mobile URL:

```bash
node scripts/slock-visual-testing.mjs publish-gh-pages \
  --baseline react \
  --current android \
  --repo-dir ~/AndroidStudioProjects/Slock \
  --dry-run
```

Expected hosted report:

```text
https://botiverse.github.io/mobile/visual-testing/
```

The local Android repo's writable `origin` is `botiverse/mobile`. The older
`bytemain/slock-android` remote is archived/read-only and must not be used for
publishing new reports. Do not publish Android/KMP reports to
`botiverse/slock`, whose Pages URL is for the React/web repo.

The shim resolves the CLI from `SLOCK_VISUAL_TESTING_CLI`, `SLOCK_SOURCE_REPO`,
or a known external checkout, then sets the React repo root for the upstream CLI:

```text
<slock-source>/packages/visual-testing/src/cli.mjs
```

Run this after updating the external source checkout if the upstream package
changes:

```bash
SLOCK_SOURCE_REPO=/path/to/slock-source \
node scripts/slock-visual-testing.mjs validate --manifest shared
```

## Android / KMP Connected Captures

Most Android baselines should be captured through dedicated KMP visual pages,
not by manually navigating the app to an equivalent state. The connected test
entry point is:

```text
compose/androidApp/src/androidTest/java/build/raft/app/visual/KmpVisualScreenshotCaptureTest.kt
```

Each test method launches `RaftLegacyHostActivity` with a `compose_*_visual` page
and deterministic `pageData`, captures the app window, crops/normalizes to dp
pixels, validates that the image is non-blank, and writes both PNG and metadata.

KMP visual fixture pages are build-gated. Normal debug/release builds do not
compile or register `compose_*_visual` routes. Any connected visual capture
Gradle invocation must opt in with either `-PslockVisualFixtures=true` or
`SLOCK_VISUAL_FIXTURES=true`; prefer the Gradle property in checked-in
commands.

Run a focused case from the Android repo:

```bash
cd compose
ANDROID_SERIAL=emulator-5560 ./gradlew --no-daemon --console=plain \
  -PslockVisualFixtures=true \
  :androidApp:connectedConnectedTestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=build.raft.app.visual.KmpVisualScreenshotCaptureTest#captureThreadMessageRow
```

Normal product builds intentionally omit the flag:

```bash
cd compose
./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin
./gradlew :androidApp:assembleDebug
```

The shared Gradle task `:shared:verifyNoProductionVisualPages` enforces this
boundary by failing if production source sets contain visual `@Page`
registrations.

Artifacts are mirrored for `adb pull` here:

```text
/sdcard/Download/slock-visual-testing/android/<caseId>.png
/sdcard/Download/slock-visual-testing/android/<caseId>.metadata.json
```

Copy both files into the visual-testing result tree before diffing:

```bash
adb -s emulator-5560 pull \
  /sdcard/Download/slock-visual-testing/android/components.thread.message.row.png \
  visual-testing-results/android/
adb -s emulator-5560 pull \
  /sdcard/Download/slock-visual-testing/android/components.thread.message.row.metadata.json \
  visual-testing-results/android/
```

Only fall back to manual device navigation when a case has no KMP visual page,
or when the goal is explicitly to validate a full end-to-end interaction. If a
case cannot be rendered through a real provider, mark it blocked and create a
follow-up case/task instead of checking in a fake baseline.
