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

// task #401 temporary char-wrap bridge. The markdown layer interleaves U+200B
// break opportunities into long inline-code runs on platforms whose native
// layout does not yet atomize them. This is independent from chrome ownership:
// iOS has native chrome but still needs this line-breaking bridge.
internal expect fun slockInlineCodeCharWrapBridgeThreshold(): Int?

/**
 * Whether markdown must synthesize NBSP characters at inline-code edges.
 *
 * This is separate from [slockInlineCodeCharWrapBridgeThreshold]: a platform
 * may own native chip padding while still needing U+200B break opportunities
 * for long code runs.
 */
internal expect fun slockInlineCodeUsesEdgePaddingBridge(): Boolean

// task #92 close-out: feedback attachment size cap follows what each
// platform's Hands SDK can actually deliver. Android/iOS stream >10MB
// attachments through a presigned R2 PUT up to 200MB; the OHOS SDK still
// reads the whole file into one ArrayBuffer, so it stays at 50MB until it
// learns to stream.
internal expect fun slockFeedbackAttachmentMaxBytes(): Long
