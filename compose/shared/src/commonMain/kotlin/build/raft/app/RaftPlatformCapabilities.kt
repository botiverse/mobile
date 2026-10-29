package build.raft.app

import androidx.lifecycle.Lifecycle
import build.raft.app.components.RaftPullToRefreshStyle
import build.raft.app.data.PlatformFile

/** The platform's own pull-to-refresh feel: Android = Material 3, iOS = UIRefreshControl-style, desktop = none. */
internal expect fun raftPlatformPullToRefreshStyle(): RaftPullToRefreshStyle

/**
 * The host lifecycle state from which a page counts as shown (`RaftPagerHost`). Phones: RESUMED.
 * Desktop: STARTED, since an unfocused but visible window is STARTED there.
 */
internal expect fun raftPlatformPageVisibleLifecycleState(): Lifecycle.State

internal expect fun raftPlatformUsesNativeLucideIconRotation(): Boolean

internal expect fun raftPlatformConsumesSnapshotsFromKuikly(): Boolean

/**
 * Platform policy for the task #990 emergency Thread/IME viewport kill-switch.
 *
 * Android keeps the stop-loss because every captured white-screen cycle is on
 * that renderer. iOS restores the ordinary automatic correction path for
 * Hands 8f33b1f3, where the cross-platform stop-loss left the final message
 * behind the keyboard.
 */
internal expect fun raftPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy

// The common idle pipeline is shared by every platform. Android/iOS run a one-controller canary until their
// cold-tab CPU/frame/memory evidence justifies enabling the complete set.
internal expect fun raftPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode

internal expect fun raftPlatformSupportsMultiShellHost(): Boolean

/** iOS is currently the only host with a native right-edge Forward recognizer. */
internal expect fun raftPlatformSupportsConversationForwardGesture(): Boolean

/**
 * Default of the "Enter sends" composer preference (an explicit choice in settings wins). Desktop:
 * on, like the web on a fine pointer (Enter / Cmd+Enter send, Shift+Enter inserts a newline, Enter
 * while an IME composes commits the conversion). Phones keep Enter as a newline.
 */
internal expect fun raftPlatformComposerEnterSendsByDefault(): Boolean

/**
 * Message text is selectable with the pointer (drag, double-click a word, Cmd+C), like the web:
 * desktop turns on the in-place selection (ThreadInPlaceTextSelection.kt) without the New Message
 * Menu preference. Phones keep long-press and "Select text" behind that preference.
 */
internal expect fun raftPlatformMessageTextPointerSelectable(): Boolean

/**
 * The app is driven by a mouse or trackpad and a hardware keyboard, like the web on a fine pointer
 * (`(pointer: fine)`): the Compose desktop app. Settings follows the web's grouping there (the
 * device's app preferences sit under Appearance, web AppearanceSection, not a "Mobile" page) and
 * the composer copy talks about Enter. Phones and tablets keep their touch layout and copy.
 */
internal expect fun raftPlatformHasFinePointer(): Boolean

/**
 * An in-app browser page can show a web page. Desktop has no embedded web view yet
 * (RaftDesktopActuals `RaftSandboxWebViewHost`), so its web links always open in the default
 * browser and Settings does not offer the "Open web links" choice.
 */
internal expect fun raftPlatformSupportsInAppBrowser(): Boolean

/**
 * Desktop paste into the composer (web MessageInput `handlePaste`): whether the system clipboard
 * holds files or an image, and those as attachments. Phones paste text only (false / empty).
 */
internal expect fun raftPlatformClipboardHasAttachments(): Boolean

/** Blocking clipboard read (an image is written to a PNG); call off the UI thread. */
internal expect fun raftPlatformReadClipboardAttachments(): List<PlatformFile>

/**
 * Least space under the conversation composer. Phones: 0 (their padding follows the safe area /
 * keyboard). Desktop has neither, so it keeps the web composer's 16dp (`safe-bottom`, 1rem).
 */
internal expect fun raftPlatformComposerMinimumBottomPaddingDp(): Float

/** Sign in with Apple is a product capability of the iOS host only. */
internal expect fun raftPlatformSupportsAppleSignIn(): Boolean

/**
 * The "Background realtime" setting keeps the realtime connection alive from an Android
 * foreground service. iOS has no equivalent (it suspends the app and delivers messages through
 * APNs), so the setting is not offered there.
 */
internal expect fun raftPlatformSupportsBackgroundRealtime(): Boolean

/**
 * Whether the signed-in app shell asks once for the system notification permission itself
 * (artin 10-27). Android 13+ only asks when the app requests POST_NOTIFICATIONS; the iOS host
 * already requests authorization when a session becomes authenticated (`iOSApp.swift`), and the
 * Compose desktop app has no notification permission.
 */
internal expect fun raftPlatformAutoPromptsNotificationPermissionAfterSignIn(): Boolean

/**
 * Whether returning to the app after the browser sign-in opened (and no callback within the
 * grace) ends a pending sign-in. Android and the Compose desktop app hand off to an external
 * browser, so coming back is the only cancel signal. iOS signs in in an in-app
 * `ASWebAuthenticationSession` sheet whose consent prompt and presentation can pause and resume
 * the app while the sheet is still open; it reports a cancel itself (`reportBrowserClosed`).
 */
internal expect fun raftPlatformWebLoginEndsOnResume(): Boolean

// The iOS app shell always has the native UITabBar renderer available. Route
// launches created after auth/server changes do not pass the SwiftUI root's
// pageData, so shared needs a platform default when the host did not provide an
// explicit renderer. Android keeps its explicit/default shared renderer behavior.
internal expect fun raftPlatformDefaultAppTabBarRenderer(): String?

// task #556 phase 2: Android's video host renders Media3 PlayerView's
// built-in controller (tap-to-show play/pause, drag seek, ±10s, fullscreen),
// so the shared minimal controls must stay out of its way there. iOS
// keeps the shared controls until their native players grow an equivalent.
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

// Each host owns its system gallery implementation: Android MediaStore Images and iOS PhotoKit
// add-only. Shared exposes one typed sent-image action/result contract.
internal expect fun raftPlatformSupportsSaveImageToPhotos(): Boolean

// Android exposes durable public Downloads through an exact journal + MediaStore readback.
// iOS does not yet have an equivalent
// committed native authority.
internal expect fun raftPlatformSupportsPublicAttachmentDownloadReadback(): Boolean

// A host that keeps Download silent and durable in the app-private files directory exposes a
// separate Files action that exports the committed private copy through the system document picker.
internal expect fun raftPlatformSupportsPrivateAttachmentExport(): Boolean

// Keep the semantic weight alongside the physical Space Grotesk face so the platform's
// system-font fallback (CJK: PingFang / Noto Sans CJK) renders at the same weight, like the web's
// `"Space Grotesk", "PingFang SC", …` stack. With Compose Multiplatform every platform needs it:
// Skia (iOS) and Android pick the fallback face from the TextStyle weight, so a Normal weight
// drew every semibold/bold Chinese label at regular weight. The per-weight family holds one face
// at exactly that weight, so Latin text still matches it with no synthetic bolding.
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
 * supplied by the concrete Android or iOS host.
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
 * HRLineHeightSpan (post-03ef5cf), so it needs -1.5dp (up). iOS starts at
 * 0 until their captures are measured - do NOT copy the android value blind;
 * their text stacks place glyphs differently.
 */
internal expect fun raftSlugSlashCenteringOffsetDp(): Float

// Hands feedback is one cross-platform product contract. The common cap must
// stay at the largest size every platform SDK can actually deliver, so
// every target is capped at 50MB.
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
 * Whether switching the phone bottom tabs plays Material's fade through
 * ([build.raft.designsystem.motion.RaftMotion.fadeThrough]). Android only (artin 2026-10-29):
 * iOS switches at once like its system tab bar, and desktop keeps its instant switch.
 */
internal expect fun raftPlatformAppShellTabFadeThrough(): Boolean
