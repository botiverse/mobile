package build.raft.app

internal expect fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean

internal expect fun slockPlatformUsesNativeLucideIconRotation(): Boolean

internal expect fun slockPlatformConsumesSnapshotsFromKuikly(): Boolean

/**
 * Platform policy for the task #990 emergency Thread/IME viewport kill-switch.
 *
 * Android keeps the stop-loss because every captured white-screen cycle is on
 * that renderer. iOS restores the ordinary automatic correction path for
 * Hands 8f33b1f3, where the cross-platform stop-loss left the final message
 * behind the keyboard. OHOS remains on the existing stop-loss until it has its
 * own product evidence; task #1015 does not broaden that release decision.
 */
internal expect fun slockPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy

// The common idle pipeline is shared by every platform. OHOS enables the full
// measured prewarm set; Android/iOS run a one-controller canary until their
// cold-tab CPU/frame/memory evidence justifies enabling the complete set.
internal expect fun slockPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode

internal expect fun slockPlatformSupportsMultiShellHost(): Boolean

/** iOS is currently the only host with a native right-edge Forward recognizer. */
internal expect fun slockPlatformSupportsConversationForwardGesture(): Boolean

/** Sign in with Apple is a product capability of the iOS host only. */
internal expect fun slockPlatformSupportsAppleSignIn(): Boolean

// The iOS app shell always has the native UITabBar renderer available. Route
// launches created after auth/server changes do not pass the SwiftUI root's
// pageData, so shared needs a platform default when the host did not provide an
// explicit renderer. OHOS keeps returning null because its HDS renderer is
// capability-gated by the ArkTS host; Android also keeps its explicit/default
// shared renderer behavior.
internal expect fun slockPlatformDefaultAppTabBarRenderer(): String?

// task #556 phase 2: Android's video host renders Media3 PlayerView's
// built-in controller (tap-to-show play/pause, drag seek, ±10s, fullscreen),
// so the shared minimal controls must stay out of its way there. iOS/OHOS
// keep the shared controls until their native players grow an equivalent.
internal expect fun slockPlatformUsesNativeVideoController(): Boolean

/**
 * Whether the platform's concrete video host can demux the detected attachment container.
 *
 * This is intentionally a container gate, not a codec promise: Media3, AVPlayer, and ArkUI
 * still depend on the device decoder for the samples inside the container. Playback failure
 * therefore remains a first-class Retry + Download state in the shared preview.
 */
internal expect fun slockPlatformSupportsVideoAttachmentContainer(
    container: SlockPreviewableVideoContainer
): Boolean

internal expect fun slockPlatformSupportsPushPreviewPrivacySetting(): Boolean

// Each host owns its system gallery implementation: Android MediaStore Images, iOS PhotoKit
// add-only, and OHOS PhotoAccessHelper. Shared exposes one typed sent-image action/result contract.
internal expect fun slockPlatformSupportsSaveImageToPhotos(): Boolean

// Android exposes durable public Downloads through an exact journal + MediaStore readback.
// Android and OHOS expose durable downloaded-file readback; iOS does not yet have an equivalent
// committed native authority.
internal expect fun slockPlatformSupportsPublicAttachmentDownloadReadback(): Boolean

// OHOS keeps Download silent and durable in the app-private files directory. A separate Files
// action exports that committed private copy through the system document picker.
internal expect fun slockPlatformSupportsPrivateAttachmentExport(): Boolean

// OHOS needs the semantic weight alongside the physical Space Grotesk face so
// ArkUI applies the same weight to system-font fallback glyphs such as CJK.
internal expect fun slockPlatformPreservesEncodedFontWeightForFallback(): Boolean

// task #90: each platform is a separate Hands app with its own release
// stream, so the release-notes slug must be injected per platform (like the
// build version), not hardcoded in commonMain. These MUST stay in sync with
// the canonical Hands update-check slug each app module already ships:
//   Android → BuildConfig.HANDS_APP_SLUG (compose/androidApp/build.gradle.kts)
//   OHOS    → appSlug in compose/ohosApp/.../EntryAbility.ets
//   iOS     → appSlug in compose/iosApp/.../iOSApp.swift
// (That value lives in the app modules for the native update-check and is not
// currently exposed to shared code; unifying would mean plumbing it through
// the launch payload.)
internal expect fun slockReleaseNotesAppSlug(): String

// task #401 temporary char-wrap bridge. The markdown layer interleaves U+200B
// break opportunities into long inline-code runs only on platforms whose
// ordinary background spans do not already wrap at character boundaries.
internal expect fun slockInlineCodeCharWrapBridgeThreshold(): Int?

/**
 * Whether markdown applies 4dp edge-only inline boxes to the first and last
 * code glyph while leaving the interior as an ordinary wrapping span.
 *
 * This is separate from [slockInlineCodeCharWrapBridgeThreshold]: a platform
 * may need explicit break opportunities independently from edge chrome.
 */
internal expect fun slockInlineCodeUsesEdgePaddingBridge(): Boolean

/**
 * Returns the first platform-standard composed-character boundary, or null
 * when this target has no standards-backed segmenter. Callers must fail closed
 * instead of approximating Unicode grapheme rules.
 */
internal expect fun slockFirstComposedCharacterEnd(text: String): Int?

/**
 * Vertical correction (dp) for the slug-input slash glyph so its ink center
 * matches react's (react measures dead-centered in the tile). Measured
 * empirically per platform on the pinned render build (PR #568 verification
 * run 29142637461): android renders the slash 1.5dp LOW under the even-split
 * HRLineHeightSpan (post-03ef5cf), so it needs -1.5dp (up). iOS/OHOS start at
 * 0 until their captures are measured - do NOT copy the android value blind;
 * their text stacks place glyphs differently.
 */
internal expect fun slockSlugSlashCenteringOffsetDp(): Float

// Hands feedback is one cross-platform product contract. The common cap must
// stay at the largest size every platform SDK can actually deliver; OHOS still
// reads one ArrayBuffer, so all three targets are capped at 50MB until it streams.
internal expect fun slockFeedbackAttachmentMaxBytes(): Long

/**
 * task #232: start-edge region (in dp) a [build.raft.app.components.TabPager]
 * must leave to the platform's own edge gesture. iOS injects its interactive
 * edge-back width; platforms without a competing start-edge gesture return 0.
 * The shared component never hardcodes a platform's back-gesture geometry.
 */
internal expect fun slockPlatformTabPagerStartEdgeInsetDp(): Float
