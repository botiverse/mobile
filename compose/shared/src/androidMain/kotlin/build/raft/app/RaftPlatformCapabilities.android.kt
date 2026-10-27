package build.raft.app

import android.os.Build
import java.text.BreakIterator
import java.util.Locale
import build.raft.app.components.RaftPullToRefreshStyle
import build.raft.app.data.PlatformFile

internal actual fun raftPlatformPullToRefreshStyle(): RaftPullToRefreshStyle =
    RaftPullToRefreshStyle.Material

internal actual fun raftPlatformUsesNativeLucideIconRotation(): Boolean = true

internal actual fun raftPlatformConsumesSnapshotsFromKuikly(): Boolean = true

internal actual fun raftPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy =
    // task #1010: the route-focus watcher now retires before composer IME
    // correction, so Android can restore the ordinary single-owner path.
    ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen

internal actual fun raftPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Canary

internal actual fun raftPlatformSupportsMultiShellHost(): Boolean = true

internal actual fun raftPlatformSupportsConversationForwardGesture(): Boolean = false

internal actual fun raftPlatformComposerEnterSendsByDefault(): Boolean = false

internal actual fun raftPlatformMessageTextPointerSelectable(): Boolean = false

internal actual fun raftPlatformClipboardHasAttachments(): Boolean = false

internal actual fun raftPlatformReadClipboardAttachments(): List<PlatformFile> = emptyList()

internal actual fun raftPlatformComposerMinimumBottomPaddingDp(): Float = 0f

internal actual fun raftPlatformSupportsAppleSignIn(): Boolean = false

internal actual fun raftPlatformSupportsBackgroundRealtime(): Boolean = true

internal actual fun raftPlatformAutoPromptsNotificationPermissionAfterSignIn(): Boolean = true

internal actual fun raftPlatformWebLoginEndsOnResume(): Boolean = true

internal actual fun raftPlatformDefaultAppTabBarRenderer(): String? = null

// Media3 PlayerView ships the mature controller (task #556 phase 2).
internal actual fun raftPlatformUsesNativeVideoController(): Boolean = true

internal actual fun raftPlatformSupportsVideoAttachmentContainer(
    container: RaftPreviewableVideoContainer
): Boolean =
    raftVideoPlayerFormatProfileSupports(
        profile = RaftVideoPlayerFormatProfile.AndroidMedia3,
        container = container
    )

// Android reports previewMode in its FCM registration and redacts in AndroidUnreadNotificationPoster,
// which also covers the no-GMS realtime path.
internal actual fun raftPlatformSupportsPushPreviewPrivacySetting(): Boolean = true

internal actual fun raftPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun raftPlatformSupportsPublicAttachmentDownloadReadback(): Boolean = true

internal actual fun raftPlatformSupportsPrivateAttachmentExport(): Boolean = false

internal actual fun raftPlatformPreservesEncodedFontWeightForFallback(): Boolean = true

internal actual fun raftReleaseNotesAppSlug(): String = "raft-android"

internal actual fun raftPlatformProductName(): String = "Android"

// Android char-wraps ordinary background spans natively. Edge-only boxes
// provide the Web 4px inset without restoring the atomic legacy chip.
internal actual fun raftInlineCodeCharWrapBridgeThreshold(): Int? = null

internal actual fun raftInlineCodeUsesEdgePaddingBridge(): Boolean = true

internal actual fun raftFirstComposedCharacterEnd(text: String): Int? {
    if (text.isEmpty()) return null
    val boundary =
        if (Build.VERSION.SDK_INT == 0) {
            // Local JVM tests expose Android APIs as stubs. The runtime path is
            // covered by androidInstrumentedTest against the device ICU data.
            BreakIterator.getCharacterInstance(Locale.ROOT).run {
                setText(text)
                first()
                next().takeUnless { it == BreakIterator.DONE }
            }
        } else {
            android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT).run {
                setText(text)
                first()
                next().takeUnless { it == android.icu.text.BreakIterator.DONE }
            }
        }
    return boundary?.takeIf { it in 1..text.length }
}

// Cross-platform Hands contract: one shared 50MB feedback attachment cap.
internal actual fun raftFeedbackAttachmentMaxBytes(): Long = 50L * 1024L * 1024L

internal actual fun raftFeedbackWebViewClientKind(): String = "android"

internal actual fun raftSlugSlashCenteringOffsetDp(): Float = -1.5f

internal actual fun raftPlatformTabPagerStartEdgeInsetDp(): Float = 0f

internal actual fun raftPlatformTabPagerOverscrollEnabled(): Boolean = true
