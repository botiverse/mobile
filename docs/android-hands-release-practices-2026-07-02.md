# Android Hands Release Practices

> **Note (2026-10-20):** the "GitHub Actions Release Dispatch" preflight list below predates release branches and OHOS removal; `docs/android-release-sop.md` is authoritative for the order of operations (skill `raft-android-release`).

This document records the current Slock Android release/update conventions for
Hands. It covers the app/channel model, Android versioning, SDK integration,
release artifacts, and validation scope.

## Hands App

Slock Android releases use the Hands app:

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
`main`. Keep `SLOCK_HANDS_CHANNEL` as the release-helper override so QA can
force `preview`, `nightly`, or `debug` independent of build type.

## Update Check Model

Android update checks are based on Android `versionCode`, not on a separate
hidden Hands update code.

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

3. Hands finds the latest active release for the app/channel/product type and
   a compatible installable APK asset.
4. Hands returns `update_available=true` only when:

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
versionCode = x * 10_000_000 + y * 100_000 + z * 1_000 + build
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

The APK's Android manifest/build config and the Hands release metadata must use
the same computed `versionCode`.

Checked-in source defaults of `versionName=1.0.0` / `versionCode=1000000` are
the formula fallback when no Gradle property or `SLOCK_ANDROID_VERSION_*`
input is set. Release preflight records and injects the allocated coordinates
at CI dispatch; it does not require rewriting those placeholders into the
frozen tree.

## SDK Integration

Slock Android should consume the configured Android updater SDK rather than
maintaining a duplicate update-check/download client. The current Maven
artifact and `QuiverClient` class retain legacy identifiers; they do not change
the Hands release workflow naming contract.

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

Hands must distinguish installable artifacts from support artifacts.

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

Build provenance should be first-class on the Hands `builds` model, not only an
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
npm install -g @botiverse/hands-cli
scripts/publish_android_hands_release.sh --channel main --build <build>
```

The helper:

- builds `:androidApp:assemble<Variant>` unless `SLOCK_SKIP_GRADLE_BUILD=1`
- computes the configured version name/code
- writes `artifacts/android-hands/<version>-<code>-<git>/build-metadata.json`
- reuses `scripts/prepare_android_hands_symbols.sh` to stage mapping/native
  symbol archives when present
- calls the npm-published Hands CLI `builds publish-android` command to upload
  the APK as an
  `installable` asset plus support artifacts as `proguard-mapping`,
  `native-symbols`, and `metadata-file`

The helper looks for `hands` on `PATH` by default. Override `HANDS_CLI` only for
local development. If it is not installed globally, the helper falls back to
`npm exec --yes --package @botiverse/hands-cli@0.5.4 -- hands`; override the
package pin with `HANDS_CLI_PACKAGE`.

## GitHub Actions Release Dispatch

The full operational order is [Android Release SOP](android-release-sop.md).
The dispatch wrapper creates a Hands **draft** and cannot activate a public
release. By default it dispatches the draft build and runs preflight in
parallel; a failed preflight cancels the run it just dispatched:

```bash
scripts/release_android_hands_github.sh \
  --version 1.0.1 --build 0 --channel main --variant release \
  --required-pr 123 --wait
```

The wrapper delegates to `scripts/release_android_preflight.sh`, which performs
the release preflight that is easy to forget manually:

- verifies the local Slock branch is `main`
- verifies each selected `--required-pr` is merged and included in the release
  head
- verifies the pinned Kuikly release manifest admission before the normal
  resolver build; it never builds or publishes Kuikly source during a Mobile release
- verifies the OHOS `hands.har` artifact and both manifest/lock references
- verifies local Slock `main` matches `origin/main`; the wrapper does not push
  Slock `main`. First merge the release candidate through a GitHub PR, then
  dispatch the release workflow from the protected remote `main` commit.
- the `Android Release` workflow is the candidate build/test gate, so a
  separate exact-head `Test` run is not required
- requires the computed versionCode to be higher than the active Hands version
  for the selected channel
- dispatches `.github/workflows/android-release.yml` as a draft with the
  requested version/channel/variant
- optionally waits for the GitHub Actions run to finish

Defaults match the draft production lane: `versionName=1.0.1`,
`versionCode=1000100`, Hands channel `main`, Android variant `release`, a
Hands draft upload, and a temporary share page. The owner-approved publish
command is intentionally a separate post-smoke step.

## Release Notes (Changelog) Format

User-facing release notes follow one house format. CI/dispatch creates the draft
with a raw commit-derived changelog; a release owner rewrites it into this
format, writes it onto the Hands draft, and only then publishes.

**Sectioned, emoji-headed, trilingual.** Up to three sections, each a bold header
followed by bullets. Keep this order; omit a section if it has nothing.

| Section | zh-CN header | en header | ja header |
| --- | --- | --- | --- |
| New features | `✨ **新功能**` | `✨ **New**` | `✨ **新機能**` |
| Improvements | `🔧 **改进**` | `🔧 **Improved**` | `🔧 **改善**` |
| Fixes | `🐛 **修复**` | `🐛 **Fixed**` | `🐛 **修正**` |

**Trilingual storage.** The changelog is a JSON object keyed by locale:

```json
{ "zh-CN": "✨ **新功能**\n- …", "en": "✨ **New**\n- …", "ja": "✨ **新機能**\n- …" }
```

Clients receive the note matching their system locale; keys are normalized
(`zh` / `cn` / `zh-cn` → `zh-CN`, `en` → `en`, `ja` → `ja`) and fall back to English, then to
the first entry. Write it with the Hands CLI
(`hands releases update <app> <releaseId> --changelog-file zh-CN=zh.md --changelog-file en=en.md --changelog-file ja=ja.md`)
or `PATCH /api/apps/:appId/releases/:id` with a `release_notes` object.

**Platform-owned source files.** New releases store the reviewed Android set at
`change-logs/<version>/android/{zh-CN,en,ja}.md`. The Android release workflow
uses that directory first and reads the old root-level pair only as a fallback
for historical versions. Because the file already identifies the platform,
bullets describe the behavior directly instead of repeating "Android" on each
line.

**User-facing only.** Describe what changed for a user, not how. Exclude
internal / CI / build / refactor items, PR and commit numbers, and
platform-internal detail; fold many small related fixes into one line. Soften
partial or not-yet-open capabilities (e.g. a Billing page that only lists plans
→ "套餐与账单 / Plans & Billing", not "订阅 / subscriptions").

**Canonical product terminology.** Before final review, compare each shipped
locale's nouns, actions, and status labels with its current catalog. Shared
Web/mobile concepts come from `botiverse/slock` under
`packages/web/src/i18n/messages/`; mobile-only concepts and locales also use
`compose/shared/src/commonMain/composeResources/values-<locale>/strings.xml`. Use the
catalog's user-visible wording and avoid implementation labels such as chip,
badge, or spinner unless they appear in the product UI. Record every source
exact and key checked in the review receipt. Current zh-CN terms include
`回应`, `任务状态`, and `处理中`; current Japanese terms include
`リアクション`, `タスクステータス`, and `処理中`. The live catalogs remain
authoritative.

**Content source.** Diff the commits since the last *released* `versionCode` and
translate them into user language — never ship the raw commit list.

**Draft-first, publish on explicit word.** The version's `versionCode` must be
strictly greater than the live one (see [Versioning Rule](#versioning-rule)) or
existing devices never receive it. Sequence: dispatch draft → rewrite the
changelog in this format → `PATCH` it onto the draft → hold for smoke → publish
(`POST /api/apps/:appId/releases/:id/publish`) only on the owner's explicit go,
then verify `latest?channel=main` returns the new `versionCode`.

**Example — 1.0.4 / versionCode 1000400 (zh-CN, abbreviated):**

```text
✨ **新功能**
- 设置新增「更新说明」「开源许可」「套餐与账单」独立页面，方便查看版本更新、开源协议与套餐信息
- 新增全局公告弹窗；前台消息提醒支持轻扫收起

🔧 **改进**
- 统一 Home、话题、动态与通知的会话身份解析，减少打开跳错会话或标签显示错误
- 优化 Markdown 行内代码显示：更接近网页端样式，长代码按字符换行

🐛 **修复**
- 修复取消表情回应后，移动端未及时同步消失的问题
- 修复「更新说明」页加载顺序导致的闪烁或底部白条问题
```

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
- Settings > Developer triggered update check against Hands `debug`.
- Missing unknown-source permission opened the app-specific settings path.
- With permission granted, `DownloadManager` downloaded the signed public
  `/public/r2/...&sig=...` APK URL successfully.
- Android system package installer opened with an update prompt.
- Installed package upgraded to `versionCode=1000001`.
- Follow-up public update check with `current_version_code=1000001` returned
  `update_available=false`.

## Operational Rules

- Do not publish an APK to Hands with a `versionCode` lower than or equal to
  the intended installed client version.
- Do not cancel the real `main` active release for visibility tests.
- Keep channel selection overrideable for QA.
- Keep GitHub Packages credentials out of chat and source control.
- Preserve mapping/native symbols for crash analysis once the build produces
  them and Hands supports support artifacts.
