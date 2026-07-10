# Android Alpha Build

The `alpha` Android build type produces a package that can be installed beside the production app:

- Application ID: `build.raft.app.alpha`
- Launcher name: `Raft Alpha`
- Gradle task: `:androidApp:assembleAlpha`
- APK: `compose/androidApp/build/outputs/apk/alpha/androidApp-alpha.apk`

Build with the normal checked-in Kuikly Android runtime:

```bash
cd compose
./gradlew :androidApp:assembleAlpha
```

To validate an unpublished Kuikly Android runtime while keeping the production Kuikly Gradle plugin, pass the complete Maven version through the explicit alpha override:

```bash
cd compose
./gradlew :androidApp:assembleAlpha \
  -PSLOCK_KUIKLY_ANDROID_RUNTIME_VERSION=2.21.0-task576.5-2.1.21
```

The override covers only Kuikly Android runtime modules (`core`, annotations/KSP, `compose`, and `core-render-android`). It does not replace `core-gradle-plugin`. Use a unique Maven version for every candidate to avoid mutable local artifact ambiguity.

The build fails fast if this override is present for a non-alpha task, so an inherited shell/CI environment cannot silently replace the Kuikly runtime in `assembleDebug` or `assembleRelease`. `dependencyInsight` and `dependencies` remain available for read-only alpha resolution checks.

Install or update the alpha app:

```bash
adb install -r compose/androidApp/build/outputs/apk/alpha/androidApp-alpha.apk
```
