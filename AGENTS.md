# Slock Android Agent Notes

## Compose Shared Workflow

- The Android app consumes Kuikly Compose shared code as the Maven artifact
  `ai.slock.compose:shared-android:1.0.0` from `mavenLocal()`.
- Root `settings.gradle.kts` only includes `:app`; `compose/shared` is not in the app
  source graph.
- When changing code under package `ai.slock.compose.*`, make the source change in
  `compose/shared/src/commonMain/kotlin/...`, not in `app/src/main/java/...`.
- After changing `compose/shared`, publish it locally before compiling or packaging
  the Android app:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:publishAndroidReleasePublicationToMavenLocal
```

- Then run app validation from the repo root, for example:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
./gradlew :app:compileDebugKotlin
```

- Do not keep shadow copies of shared classes in `app/src/main/java/ai/slock/compose/...`.
  Temporary app-side shims can make one APK build, but they drift from the real shared
  artifact and should be removed before handoff.
