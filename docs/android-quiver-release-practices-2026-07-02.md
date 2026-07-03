# Android Quiver Release Practices

This document records the current Slock Android release/update conventions for
Quiver. It covers the app/channel model, Android versioning, SDK integration,
release artifacts, and validation scope.

## Quiver App

Slock Android releases use the Quiver app:

- App slug: `raft-android`
- App id: `a78f3891-f980-4f48-80d7-2a58e93a99aa`
- Product type: `android-apk`

Current channels:

| Channel | Purpose |
| --- | --- |
| `main` | Production/release builds. |
| `debug` | Debug builds and local update testing. |
| `preview` | Isolated prerelease or QA release validation. |
| `nightly` | Nightly/continuous internal builds. |

Debug builds should default to `debug`. Release/prod builds should default to
`main`. Keep `SLOCK_QUIVER_CHANNEL` as an override so QA can force `preview`,
`nightly`, or `debug` independent of build type.

## Update Check Model

Android update checks are based on Android `versionCode`, not on a separate
hidden Quiver update code.

Client flow:

1. The app reads its installed `BuildConfig.VERSION_CODE`.
2. The app calls:

   ```text
   GET /public/v2/apps/raft-android/updates/check
     ?channel=<channel>
     &product_type=android-apk
     &current_version_code=<BuildConfig.VERSION_CODE>
     &platform=android
     &arch=<device arch>
     &filetype=apk
   ```

3. Quiver finds the latest active release for the app/channel/product type and
   a compatible installable APK asset.
4. Quiver returns `update_available=true` only when:

   ```text
   latest.version_code > current_version_code
   ```

5. Draft, cancelled, or superseded releases are not visible to the public update
   endpoint.

This means a rebuilt APK with the same `versionCode` will not be treated as an
update by an installed client, even if the APK bytes changed.

## Versioning Rule

Use a `versionCode` with a build/revision component:

```text
versionCode = x * 1_000_000 + y * 10_000 + z * 100 + build
versionName = x.y.z[-suffix]
```

Examples:

| Version name | Build | Version code |
| --- | ---: | ---: |
| `1.0.3` | `0` | `1000300` |
| `1.0.3-rc1` | `1` | `1000301` |
| `1.0.3-rc2` | `2` | `1000302` |
| `1.1.0` | `0` | `1010000` |
| `2.0.0` | `0` | `2000000` |

The `build` component allows multiple internal, beta, or RC packages for the
same semantic version while keeping Android's required integer strictly
increasing.

Build tooling should expose Gradle properties for these parts, for example:

```text
-PversionX=1
-PversionY=0
-PversionZ=3
-PversionBuild=1
-PversionSuffix=rc1
```

The APK's Android manifest/build config and the Quiver release metadata must use
the same computed `versionCode`.

## SDK Integration

Slock Android should consume the Quiver Android SDK rather than maintaining a
duplicate update-check/download client.

Dependency:

```kotlin
implementation("io.quiver:quiver-android-updater:0.1.0")
```

Maven repository:

```kotlin
fun org.gradle.api.artifacts.dsl.RepositoryHandler.githubPackagesQuiver() {
    maven {
        name = "GitHubPackagesQuiver"
        url = uri("https://maven.pkg.github.com/oranix-io/quiver")
        content {
            includeModuleByRegex("io\\.quiver", ".*")
        }
        credentials {
            val (user, token) = githubPackagesCredentials()
            username = user
            password = token
        }
    }
}
```

GitHub Packages requires authentication even for package downloads that are
associated with public repositories. Reuse the existing
`compose/github-packages.properties` keys:

```properties
githubPackagesUsername=bytemain
githubPackagesToken=<owner token>
```

The token must be a GitHub personal access token that can read packages from
`oranix-io/quiver`.

SDK configuration for Slock Android:

| Parameter | Value |
| --- | --- |
| `baseUrl` | `https://quiver.oranix.io` |
| `appSlug` | `raft-android` |
| `channel` | `debug` for debug builds, `main` for release builds, overrideable |
| `productType` | `android-apk` |
| `platform` | `android` |
| `arch` | Device arch, currently validated with `arm64-v8a` |

Slock currently uses the SDK `QuiverClient` for public update resolution, then
queues the install from the app side. The app-side queue uses Android
`DownloadManager`, registers the completion receiver with
`ContextCompat.RECEIVER_EXPORTED`, filters by the exact captured `downloadId`,
and then opens the system package installer. `RECEIVER_NOT_EXPORTED` does not
receive `DownloadManager.ACTION_DOWNLOAD_COMPLETE` on the Android 16 validation
emulator because the broadcast is delivered by the platform Downloads provider
process.

Unknown-source install permission must be preflighted in the app before
starting install. The Quiver SDK `0.1.0` install path should be revisited in a
future SDK `0.1.1+` so the receiver/exported-state and public signed URL
requirements live in the SDK instead of Slock app glue.

If SHA-256 or size validation is required before install, add that capability to
the Quiver Android SDK and publish a follow-up SDK version rather than
reintroducing a duplicate Slock-side downloader.

## Release Artifacts

Quiver must distinguish installable artifacts from support artifacts.

Installable artifacts:

- `apk`: returned by public update check and installed by the client.
- `aab`: useful for Play/archival flows, not normally installed directly by the
  in-app updater.

Support artifacts:

- `proguard-mapping`: R8/ProGuard mapping files such as `mapping.txt` or
  `mapping.zip`.
- `native-symbols`: native debug symbols or unstripped symbol bundles.
- `metadata-file`: raw build metadata JSON, if uploaded.
- `source-map`: future RN/H5 source maps.

Public update endpoints must only return installable artifacts. Support
artifacts should be downloadable only from authenticated admin/publisher UI/API
surfaces.

Build provenance should be first-class on the Quiver `builds` model, not only an
uploaded file. Recommended fields:

- `source_commit`
- `source_branch`
- `build_time`
- `ci_provider`
- `ci_run_id`
- `ci_url`
- `metadata_json`

Slock provides a release helper:

```bash
npm install -g @oranix/quiver-cli
scripts/publish_android_quiver_release.sh --channel main --build <build>
```

The helper:

- builds `:androidApp:assemble<Variant>` unless `SLOCK_SKIP_GRADLE_BUILD=1`
- computes the configured version name/code
- writes `artifacts/android-quiver/<version>-<code>-<git>/build-metadata.json`
- reuses `scripts/prepare_android_bugly_symbols.sh` to stage mapping/native
  symbol archives when present
- calls the npm-published Quiver CLI `builds publish-android` command to upload
  the APK as an
  `installable` asset plus support artifacts as `proguard-mapping`,
  `native-symbols`, and `metadata-file`

The helper looks for `quiver` on `PATH` by default. Override `QUIVER_CLI` only
for local development, for example `QUIVER_CLI=/path/to/quiver/packages/cli/dist/index.js`.
If `quiver` is not installed globally, the helper falls back to
`npm exec --yes --package @oranix/quiver-cli@0.1.0 -- quiver`; override the
package pin with `QUIVER_CLI_PACKAGE`.

## GitHub Actions Release Dispatch

Use the dispatch wrapper for production Android releases:

```bash
scripts/release_android_quiver_github.sh --version 1.0.1 --build 0 --channel main --variant release --wait
```

The wrapper performs the release preflight that is easy to forget manually:

- verifies the local Slock branch is `main`
- ensures the checked-out `third_party/kuikly-ui` commit is reachable from its
  configured remote branch, pushing it if needed
- pushes Slock `main` to `origin/main`
- dispatches `.github/workflows/android-release.yml` with the requested
  version/channel/variant
- optionally waits for the GitHub Actions run to finish

Defaults match the current production lane: `versionName=1.0.1`,
`versionCode=1000100`, Quiver channel `main`, Android variant `release`,
`publish_quiver=true`, and `create_share_page=true`.

## Validation Scope

Server-side validation already passed for the current `raft-android` release
pipeline:

- `current_version_code=0` with `arch=arm64-v8a` returns an update.
- `current_version_code=1` and `2` return no update when latest is version code
  `1`.
- Missing arch falls back to the available `arm64-v8a` APK asset.
- Mismatched arch such as `armeabi-v7a` returns no compatible asset.
- Draft release is invisible.
- Published release is visible.
- Cancelled release is invisible.
- Testing draft/publish/cancel visibility should use `preview` or another
  isolated channel, not the live `main` release.

Client/device validation passed on `emulator-5554`:

- Low-version client installed as `build.raft.app`, `versionCode=1000000`.
- Settings > Developer triggered update check against Quiver `debug`.
- Missing unknown-source permission opened the app-specific settings path.
- With permission granted, `DownloadManager` downloaded the signed public
  `/public/r2/...&sig=...` APK URL successfully.
- Android system package installer opened with an update prompt.
- Installed package upgraded to `versionCode=1000001`.
- Follow-up public update check with `current_version_code=1000001` returned
  `update_available=false`.

## Operational Rules

- Do not publish an APK to Quiver with a `versionCode` lower than or equal to
  the intended installed client version.
- Do not cancel the real `main` active release for visibility tests.
- Keep channel selection overrideable for QA.
- Keep GitHub Packages credentials out of chat and source control.
- Preserve mapping/native symbols for crash analysis once the build produces
  them and Quiver supports support artifacts.
