# Third-party notices — Raft Mobile

Raft Mobile is licensed under FSL-1.1-ALv2 (see LICENSE). That grant covers Botiverse's own code only.
The components below are third-party works used under their own licenses. Licenses were read from the
published package metadata (Maven POM `<licenses>`, npm/OHPM registry `license`, CocoaPods trunk spec);
"vendored" means source is copied into this repository under `third_party/`.

## Vendored source (third_party/)
| Path | Upstream | Version / commit | License |
| --- | --- | --- | --- |
| third_party/markdown-parser | JetBrains/markdown (via botiverse/jetbrains-markdown fork raft/0.7.5-mobile) | 0.7.5, upstream 52368fb1, fork 295290f8 | Apache-2.0 (LICENSE present) |
| third_party/kuikly-compose-markdown | Kuikly-contrib/kuikly-compose-markdown (com.tencent.kuiklybase:markdown 0.4.0 sources) + embedded JetBrains/Saket Narayan Apache-2.0 files | 0.4.0 / 0.3.0-ohos | KuiklyUI license (Tencent) for Kuikly-authored files; Apache-2.0 for JetBrains (42 files) and Saket Narayan (6 files) |

## Git submodules (not vendored; referenced by URL)
| Path | Repository | License |
|---|---|---|
| .agents/skills/kuikly-ui-framework/references/KuiklyUI | Tencent-TDS/KuiklyUI | KuiklyUI license |

## Build-time dependencies — Maven (Android / KMP)
| Component | Version | License |
|---|---|---|
| com.tencent.kuikly-open:core / compose / core-render-android / core-annotations / core-ksp (Botiverse-built fork on maven.artifacts.botiverse.dev) | 2.28.0-raft.17-2.1.21 (Android) · 2.28.0-raft.17-2.0.21-ohos (OHOS) | KuiklyUI license — MIT-style grant; prohibits derivative work for dynamic code loading / live update without Tencent consent (Raft Mobile adds none) |
| com.tencent.kuiklybase:network | 0.1.0-raft.47 | Apache-2.0 |
| com.tencent.kuiklybase:resource-core | 0.1.0-raft.5 | Apache-2.0 |
| build.raft.kuiklybase:datetime | 0.1.0-raft.0 | Apache-2.0 |
| build.raft.mermaid:mermaid-core / mermaid-kuikly / mermaid-layout-api / mermaid-layout-simple | 0.1.11 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-core / -test | 1.7.3 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.6.2 | Apache-2.0 |
| org.jetbrains.kotlinx:atomicfu | 0.25.0 | Apache-2.0 |
| androidx.* (core-ktx 1.10.1, core-splashscreen 1.0.1, webkit 1.14.0, lifecycle-runtime-ktx 2.6.1, window 1.3.0, metrics-performance 1.0.0, activity-compose 1.8.0, compose BOM 2024.12.01 / runtime 1.8.3 / ui / material / material3, recyclerview 1.3.2, credentials 1.6.0 (+ play-services-auth), media3 1.6.1, profileinstaller 1.4.1) | as listed | Apache-2.0 |
| com.google.android.libraries.identity.googleid:googleid | 1.2.0 | **Android Software Development Kit License** (binary SDK; depend-only, do not redistribute the artifact in the repo) |
| com.google.firebase:firebase-bom / firebase-messaging | 34.15.0 / 25.0.1 | Apache-2.0 (BOM itself declares none) |
| com.squareup.okhttp3:okhttp | 4.12.0 | Apache-2.0 |
| com.google.code.gson:gson | 2.11.0 | Apache-2.0 |
| io.socket:socket.io-client | 2.1.2 | MIT |
| app.cash.sqldelight:runtime / android-driver / native-driver / sqlite-driver / coroutines-extensions | 2.1.0 | Apache-2.0 |
| app.cash.sqldelight OHOS fork (gradle-plugin, core, migrations, migration-env, dialect-api, compiler-env, sqlite-3-18-dialect, runtime, native-driver; ohosArm64) — bytemain/sqldelight branch slock-2.1.0-ohos @ 23cf5c45, published to Raft Artifacts | 2.1.0-KBA-003 | Apache-2.0 |
| com.caverock:androidsvg-aar | 1.4 | Apache-2.0 |
| com.tencent:mmkv | 2.4.0 | BSD-3-Clause |
| org.jetbrains:markdown (coordinate reference only; sources vendored) | 0.7.5 | Apache-2.0 |
| Test-only: junit 4.13.2 (EPL-1.0), androidx.test.* (Apache-2.0), org.robolectric:robolectric 4.14.1 (MIT), androidx.benchmark / uiautomator (Apache-2.0) | — | as listed |
| Gradle plugins: AGP 8.13.2, Kotlin 2.1.21, KSP, Compose plugin 1.7.3, SQLDelight 2.1.0, google-services 4.5.0 | — | Apache-2.0 (google-services plugin: Apache-2.0) |

## iOS — CocoaPods
| Pod | Version | License |
|---|---|---|
| OpenKuiklyIOSRender (Botiverse-built podspec) | 2.28.0-raft.17-2.1.21 | KuiklyUI license |
| Hands (oranix-io/hands, tag ios-v0.2.1) | 0.2.1 | MIT |
| MMKV / MMKVCore | 2.4.0 | BSD-3-Clause |
| SDWebImage | 5.21.7 | MIT |
| SDWebImageSVGCoder | 1.8.0 | MIT |
| Socket.IO-Client-Swift | 16.1.1 | MIT |
| Starscream | 4.0.8 | Apache-2.0 |

## HarmonyOS — OHPM
| Package | Version | License |
|---|---|---|
| @kuikly-open/render (Botiverse-built .har) | 2.28.0-raft.17-2.0.21-ohos | KuiklyUI license |
| @botiverse/hands | 0.3.5 | MIT |
| @ohos/apng | ^1.0.0-rc.8 | MIT |
| @kuiklybase/knoi | 0.0.4 | Apache-2.0 |
| @tencent/mmkv | 2.1.0 | BSD-3-Clause |
| @ohos/hypium 1.0.15 / @ohos/hamock 1.0.0-rc (dev) | — | Apache-2.0 (OpenHarmony test tooling) |

## Tooling — npm (compose/package.json; dev/CI only, not shipped)
MIT: console-error, console-info, console-warn, koa, koa-bodyparser, koa-router, koa-static, koa-whistle, mime-types, uuid, yargs, file-loader, gulp, gulp-zip, husky, querystring, shell · BSD-3-Clause: md5, shelljs · Unlicense: big-integer.

## Bundled font assets (compose/androidApp, compose/ohosApp, compose/iosApp)
| Family | Weights | Upstream | License |
| --- | --- | --- | --- |
| Space Grotesk | 300/400/500/600/700 (+variable) | google/fonts ofl/spacegrotesk — https://fonts.googleapis.com/css2?family=Space+Grotesk | SIL OFL 1.1 (`compose/androidApp/SpaceGrotesk-OFL.txt`) |
| Space Mono | 400/700 | google/fonts ofl/spacemono — https://fonts.googleapis.com/css2?family=Space+Mono | SIL OFL 1.1 (`compose/androidApp/SpaceMono-OFL.txt`) |
| Hanken Grotesk | 300/400/500/600/700 | google/fonts ofl/hankengrotesk (`HankenGrotesk[wght].ttf`) | SIL OFL 1.1 (`compose/androidApp/HankenGrotesk-OFL.txt`) |
| Geist | 300/400/500/600/700 | google/fonts ofl/geist (`Geist[wght].ttf`) | SIL OFL 1.1 (`compose/androidApp/Geist-OFL.txt`) |
| Inter | 300/400/500/600/700 | google/fonts ofl/inter (`Inter[opsz,wght].ttf`) | SIL OFL 1.1 (`compose/androidApp/Inter-OFL.txt`) |
| Geist Mono | 400/700 | google/fonts ofl/geistmono (`GeistMono[wght].ttf`) | SIL OFL 1.1 (`compose/androidApp/GeistMono-OFL.txt`) |

Sources were instanced to static weights with `fonttools varLib.instancer`. Details:
`compose/androidApp/FONTS-PROVENANCE.md`.

## Copyleft check
No GPL / LGPL / AGPL / MPL component found in any ecosystem. EPL-1.0 (junit) is test-only. The only non-OSI terms are the KuiklyUI license (permissive + no-hot-update clause) and Google's Android SDK license (binary dependency).

## Scope

This list covers direct dependencies pinned in the version catalog, build files, Podfile.lock and
oh-package.json5 files. Transitive dependencies inherit the licenses of their ecosystems above and are
enumerated by the build tooling (Gradle license report, CocoaPods acknowledgements, OHPM metadata).
