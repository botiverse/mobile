package build.raft.app

import build.raft.app.components.RaftPullToRefreshStyle

/**
 * The platform's own pull-to-refresh, used when Settings → Mobile "Native pull-to-refresh" is on:
 * Android = Material 3, iOS = UIRefreshControl-style. Off = Raft's custom implementation.
 */
internal expect fun raftPlatformNativePullToRefreshStyle(): RaftPullToRefreshStyle

internal expect fun raftPlatformUsesNativeLucideIconRotation(): Boolean

internal expect fun raftPlatformConsumesSnapshotsFromKuikly(): Boolean

/**
 * Platform policy for the task #990 emergency Thread/IME viewport kill-switch.
 *
 * Android keeps the stop-loss because every captured white-screen cycle is on
 * that renderer. iOS restores the ordinary automatic correction path for
 * Hands 8f33b1f3, where the cross-platform stop-loss left the final message
 * behind the keyboard. OHOS remains on the existing stop-loss until it has its
 * own product evidence; task #1015 does not broaden that release decision.
 */
internal expect fun raftPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy

// The common idle pipeline is shared by every platform. OHOS enables the full
// measured prewarm set; Android/iOS run a one-controller canary until their
// cold-tab CPU/frame/memory evidence justifies enabling the complete set.
internal expect fun raftPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode

internal expect fun raftPlatformSupportsMultiShellHost(): Boolean

/** iOS is currently the only host with a native right-edge Forward recognizer. */
internal expect fun raftPlatformSupportsConversationForwardGesture(): Boolean

/** Sign in with Apple is a product capability of the iOS host only. */
internal expect fun raftPlatformSupportsAppleSignIn(): Boolean

// The iOS app shell always has the native UITabBar renderer available. Route
// launches created after auth/server changes do not pass the SwiftUI root's
// pageData, so shared needs a platform default when the host did not provide an
// explicit renderer. OHOS keeps returning null because its HDS renderer is
// capability-gated by the ArkTS host; Android also keeps its explicit/default
// shared renderer behavior.
internal expect fun raftPlatformDefaultAppTabBarRenderer(): String?

// task #556 phase 2: Android's video host renders Media3 PlayerView's
// built-in controller (tap-to-show play/pause, drag seek, ±10s, fullscreen),
// so the shared minimal controls must stay out of its way there. iOS/OHOS
// keep the shared controls until their native players grow an equivalent.
internal expect fun raftPlatformUsesNativeVideoController(): Boolean

/**
 * Whether the platform's concrete video host can demux the detected attachment container.
 *
 * This is intentionally a container gate, not a codec promise: Media3, AVPlayer, and ArkUI
 * still depend on the device decoder for the samples inside the container. Playback failure
 * therefore remains a first-class Retry + Download state in the shared preview.
 */
internal expect fun raftPlatformSupportsVideoAttachmentContainer(
    container: RaftPreviewableVideoContainer
): Boolean

internal expect fun raftPlatformSupportsPushPreviewPrivacySetting(): Boolean

// Each host owns its system gallery implementation: Android MediaStore Images, iOS PhotoKit
// add-only, and OHOS PhotoAccessHelper. Shared exposes one typed sent-image action/result contract.
internal expect fun raftPlatformSupportsSaveImageToPhotos(): Boolean

// Android exposes durable public Downloads through an exact journal + MediaStore readback.
// Android and OHOS expose durable downloaded-file readback; iOS does not yet have an equivalent
// committed native authority.
internal expect fun raftPlatformSupportsPublicAttachmentDownloadReadback(): Boolean

// OHOS keeps Download silent and durable in the app-private files directory. A separate Files
// action exports that committed private copy through the system document picker.
internal expect fun raftPlatformSupportsPrivateAttachmentExport(): Boolean

// OHOS needs the semantic weight alongside the physical Space Grotesk face so
// ArkUI applies the same weight to system-font fallback glyphs such as CJK.
internal expect fun raftPlatformPreservesEncodedFontWeightForFallback(): Boolean

// task #90: each platform is a separate Hands app with its own release
// stream, so the release-notes slug must be injected per platform (like the
// build version), not hardcoded in commonMain. These MUST stay in sync with
// the canonical Hands update-check slug each app module already ships:
//   Android → BuildConfig.HANDS_APP_SLUG (compose/androidApp/build.gradle.kts)
//   iOS     → appSlug in compose/iosApp/.../iOSApp.swift
// (That value lives in the app modules for the native update-check and is not
// currently exposed to shared code; unifying would mean plumbing it through
// the launch payload.)
internal expect fun raftReleaseNotesAppSlug(): String

/**
 * Product name used in shared settings copy that describes the installed host.
 * The copy stays localized in common resources while the platform name is
 * supplied by the concrete Android, iOS, or HarmonyOS host.
 */
internal expect fun raftPlatformProductName(): String

// task #401 temporary char-wrap bridge. The markdown layer interleaves U+200B
// break opportunities into long inline-code runs only on platforms whose
// ordinary background spans do not already wrap at character boundaries.
internal expect fun raftInlineCodeCharWrapBridgeThreshold(): Int?

/**
 * Whether markdown applies 4dp edge-only inline boxes to the first and last
 * code glyph while leaving the interior as an ordinary wrapping span.
 *
 * This is separate from [raftInlineCodeCharWrapBridgeThreshold]: a platform
 * may need explicit break opportunities independently from edge chrome.
 */
internal expect fun raftInlineCodeUsesEdgePaddingBridge(): Boolean

/**
 * Returns the first platform-standard composed-character boundary, or null
 * when this target has no standards-backed segmenter. Callers must fail closed
 * instead of approximating Unicode grapheme rules.
 */
internal expect fun raftFirstComposedCharacterEnd(text: String): Int?

/**
 * Vertical correction (dp) for the slug-input slash glyph so its ink center
 * matches react's (react measures dead-centered in the tile). Measured
 * empirically per platform on the pinned render build (PR #568 verification
 * run 29142637461): android renders the slash 1.5dp LOW under the even-split
 * HRLineHeightSpan (post-03ef5cf), so it needs -1.5dp (up). iOS/OHOS start at
 * 0 until their captures are measured - do NOT copy the android value blind;
 * their text stacks place glyphs differently.
 */
internal expect fun raftSlugSlashCenteringOffsetDp(): Float

// Hands feedback is one cross-platform product contract. The common cap must
// stay at the largest size every platform SDK can actually deliver; OHOS still
// reads one ArrayBuffer, so all three targets are capped at 50MB until it streams.
internal expect fun raftFeedbackAttachmentMaxBytes(): Long

/** Stable host identity injected into the standalone feedback WebView session metadata. */
internal expect fun raftFeedbackWebViewClientKind(): String

/**
 * task #232: start-edge region (in dp) a [build.raft.app.components.TabPager]
 * must leave to the platform's own edge gesture. iOS injects its interactive
 * edge-back width; platforms without a competing start-edge gesture return 0.
 * The shared component never hardcodes a platform's back-gesture geometry.
 */
internal expect fun raftPlatformTabPagerStartEdgeInsetDp(): Float

/**
 * Whether a [build.raft.app.components.TabPager] keeps the platform's edge overscroll. Android
 * keeps its stretch (drawn in place). iOS returns false: CMP's Cupertino overscroll TRANSLATES the
 * pager content (rubber band, unclipped) and only returns it to rest at the end of its spring; when
 * that spring is cancelled (a programmatic page sync, an interrupted fling) the offset stays, and
 * the whole Chat tab was left shifted sideways with its messages running off the screen edge.
 */
internal expect fun raftPlatformTabPagerOverscrollEnabled(): Boolean

/**
 * Whether the value-based text field needs [raftTextFieldMultiTapSelection] for touch
 * double-tap (word) / triple-tap (paragraph) selection. Android's androidx foundation 1.8.2 lacks
 * it (added in 1.10.0); CMP 1.8.2 iOS already implements it in its Cupertino text field pointer.
 */
internal expect fun raftPlatformTextFieldNeedsMultiTapBackport(): Boolean
