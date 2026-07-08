package build.raft.app

internal expect fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean

internal expect fun slockPlatformUsesNativeLucideIconRotation(): Boolean

// task #556 phase 2: Android's video host renders Media3 PlayerView's
// built-in controller (tap-to-show play/pause, drag seek, ±10s, fullscreen),
// so the shared minimal controls must stay out of its way there. iOS/OHOS
// keep the shared controls until their native players grow an equivalent.
internal expect fun slockPlatformUsesNativeVideoController(): Boolean

// task #90: each platform is a separate Quiver app with its own release
// stream, so the release-notes slug must be injected per platform (like the
// build version), not hardcoded in commonMain. These MUST stay in sync with
// the canonical Quiver update-check slug each app module already ships:
//   Android → BuildConfig.QUIVER_APP_SLUG (compose/androidApp/build.gradle.kts)
//   OHOS    → appSlug in compose/ohosApp/.../EntryAbility.ets
//   iOS     → appSlug in compose/iosApp/.../iOSApp.swift
// (That value lives in the app modules for the native update-check and is not
// currently exposed to shared code; unifying would mean plumbing it through
// the launch payload.)
internal expect fun slockReleaseNotesAppSlug(): String

// task #401 TEMPORARY A-bridge: Android's Kuikly render draws the inline-code
// chip natively (atom advance + chrome) and wraps long code at character
// boundaries; the OHOS and iOS renders have no slockInlineCode handling yet,
// so long code runs there render as one unbreakable word. Until each native
// chip lands (OHOS core-render-ohos / iOS core-render-ios task #402), the
// markdown layer interleaves zero-width spaces into inline-code runs longer
// than this threshold so those platforms wrap like web/Android. Returns null
// where the native render already handles wrapping. Remove each platform's
// non-null value when its native chip ships.
internal expect fun slockInlineCodeCharWrapBridgeThreshold(): Int?
