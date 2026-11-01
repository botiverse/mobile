package build.raft.app

import androidx.lifecycle.Lifecycle
import build.raft.app.components.RaftPullToRefreshStyle
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftDesktopClipboardAttachments
import java.text.BreakIterator
import java.util.Locale

// Desktop draws with Skia like iOS but has no system bars, haptics or native media hosts.
// No pull gesture on desktop: wheel / trackpad overscroll must not drag a pull indicator in.
internal actual fun raftPlatformPullToRefreshStyle(): RaftPullToRefreshStyle = RaftPullToRefreshStyle.None

// An unfocused window stays STARTED and keeps showing its pages; minimized / hidden is CREATED.
internal actual fun raftPlatformPageVisibleLifecycleState(): Lifecycle.State = Lifecycle.State.STARTED

internal actual fun raftPlatformUsesNativeLucideIconRotation(): Boolean = false

internal actual fun raftPlatformConsumesSnapshotsFromKuikly(): Boolean = true

internal actual fun raftPlatformThreadImeProgrammaticViewportPolicy(): ThreadImeProgrammaticViewportPolicy =
    ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen

internal actual fun raftPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Canary

internal actual fun raftPlatformSupportsMultiShellHost(): Boolean = false

internal actual fun raftPlatformSupportsConversationForwardGesture(): Boolean = false

internal actual fun raftPlatformComposerEnterSendsByDefault(): Boolean = true

internal actual fun raftPlatformMessageTextPointerSelectable(): Boolean = true

internal actual fun raftPlatformHasFinePointer(): Boolean = true

internal actual fun raftPlatformShortcutsUseCommand(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

internal actual fun raftPlatformSupportsInAppBrowser(): Boolean = false

internal actual fun raftPlatformClipboardHasAttachments(): Boolean = RaftDesktopClipboardAttachments.available()

internal actual fun raftPlatformReadClipboardAttachments(): List<PlatformFile> = RaftDesktopClipboardAttachments.read()

internal actual fun raftPlatformComposerMinimumBottomPaddingDp(): Float = 16f

internal actual fun raftPlatformSupportsAppleSignIn(): Boolean = false

internal actual fun raftPlatformSupportsBackgroundRealtime(): Boolean = false

internal actual fun raftPlatformAutoPromptsNotificationPermissionAfterSignIn(): Boolean = false

internal actual fun raftPlatformWebLoginEndsOnResume(): Boolean = true

internal actual fun raftPlatformDefaultAppTabBarRenderer(): String? = null

// The desktop video host (RaftVideoPlayer.desktop.kt) draws its own full controller (play / pause,
// seek, time, volume, full screen, Space), so the shared minimal controls stay out of its way, as
// with Android's Media3 PlayerView and iOS's AVPlayerViewController.
internal actual fun raftPlatformUsesNativeVideoController(): Boolean = RaftDesktopVideoSupport.available

// ComposeMediaPlayer's macOS backend rejects media without a video track: audio attachments keep
// the "not available" notice and the shared audio card (RaftDesktopVideoSupport.route).
internal actual fun raftPlatformPlaysAudioOnlyMedia(): Boolean = false

// Video attachments preview only where the player runs (macOS 14+ with its native bridge loaded);
// elsewhere they stay download cards. AVFoundation demuxes the same containers as iOS's AVPlayer.
internal actual fun raftPlatformSupportsVideoAttachmentContainer(container: RaftPreviewableVideoContainer): Boolean =
    RaftDesktopVideoSupport.supportsContainer(container, RaftDesktopVideoSupport.available)

internal actual fun raftPlatformSupportsPushPreviewPrivacySetting(): Boolean = false

internal actual fun raftPlatformSupportsSaveImageToPhotos(): Boolean = false

internal actual fun raftPlatformSupportsPublicAttachmentDownloadReadback(): Boolean = false

internal actual fun raftPlatformSupportsPrivateAttachmentExport(): Boolean = false

internal actual fun raftPlatformPreservesEncodedFontWeightForFallback(): Boolean = true

// TODO(desktop): no Hands release stream for desktop yet.
internal actual fun raftReleaseNotesAppSlug(): String = "raft-desktop"

internal actual fun raftPlatformProductName(): String = "Desktop"

internal actual fun raftInlineCodeCharWrapBridgeThreshold(): Int? = null

internal actual fun raftInlineCodeUsesEdgePaddingBridge(): Boolean = true

internal actual fun raftFirstComposedCharacterEnd(text: String): Int? {
    if (text.isEmpty()) return null
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    iterator.first()
    return iterator.next().takeUnless { it == BreakIterator.DONE }?.takeIf { it in 1..text.length }
}

internal actual fun raftFeedbackAttachmentMaxBytes(): Long = 50L * 1024L * 1024L

// TODO(desktop): RaftDesktopSettingsCapability has no `submitFeedback` yet.
internal actual fun raftPlatformSupportsHandsFeedbackSubmit(): Boolean = false

internal actual fun raftFeedbackWebViewClientKind(): String = "desktop"

internal actual fun raftSlugSlashCenteringOffsetDp(): Float = 0f

internal actual fun raftPlatformTabPagerStartEdgeInsetDp(): Float = 0f

internal actual fun raftPlatformTabPagerOverscrollEnabled(): Boolean = false

/** Desktop keeps its instant tab switch; see expect. */
internal actual fun raftPlatformAppShellTabFadeThrough(): Boolean = false
