package build.raft.app

internal expect fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean

internal expect fun slockPlatformUsesNativeLucideIconRotation(): Boolean

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
