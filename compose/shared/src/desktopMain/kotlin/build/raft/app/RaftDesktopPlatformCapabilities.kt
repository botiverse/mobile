package build.raft.app

import build.raft.app.components.RaftPullToRefreshStyle
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftDesktopClipboardAttachments
import java.text.BreakIterator
import java.util.Locale

// Desktop draws with Skia like iOS but has no system bars, haptics or native media hosts.
internal actual fun raftPlatformPullToRefreshStyle(): RaftPullToRefreshStyle = RaftPullToRefreshStyle.Material

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

internal actual fun raftPlatformClipboardHasAttachments(): Boolean = RaftDesktopClipboardAttachments.available()

internal actual fun raftPlatformReadClipboardAttachments(): List<PlatformFile> = RaftDesktopClipboardAttachments.read()

internal actual fun raftPlatformComposerMinimumBottomPaddingDp(): Float = 16f

internal actual fun raftPlatformSupportsAppleSignIn(): Boolean = false

internal actual fun raftPlatformSupportsBackgroundRealtime(): Boolean = false

internal actual fun raftPlatformAutoPromptsNotificationPermissionAfterSignIn(): Boolean = false

internal actual fun raftPlatformWebLoginEndsOnResume(): Boolean = true

internal actual fun raftPlatformDefaultAppTabBarRenderer(): String? = null

// TODO(desktop): no video host yet (RaftVideoPlayer is a placeholder).
internal actual fun raftPlatformUsesNativeVideoController(): Boolean = false

internal actual fun raftPlatformSupportsVideoAttachmentContainer(container: RaftPreviewableVideoContainer): Boolean = false

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

internal actual fun raftFeedbackWebViewClientKind(): String = "desktop"

internal actual fun raftSlugSlashCenteringOffsetDp(): Float = 0f

internal actual fun raftPlatformTabPagerStartEdgeInsetDp(): Float = 0f

internal actual fun raftPlatformTabPagerOverscrollEnabled(): Boolean = false

/** Desktop keeps its instant tab switch; see expect. */
internal actual fun raftPlatformAppShellTabFadeThrough(): Boolean = false
