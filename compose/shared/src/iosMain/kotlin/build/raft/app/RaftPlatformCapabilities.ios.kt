package build.raft.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetRangeOfComposedCharactersAtIndex
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSProcessInfo
import build.raft.app.components.RaftPullToRefreshStyle

internal actual fun raftPlatformPullToRefreshStyle(): RaftPullToRefreshStyle =
    RaftPullToRefreshStyle.Cupertino

internal actual fun raftPlatformUsesNativeLucideIconRotation(): Boolean = false

internal actual fun raftPlatformConsumesSnapshotsFromKuikly(): Boolean = true

internal actual fun raftPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy =
    ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen

internal actual fun raftPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Canary

internal actual fun raftPlatformSupportsMultiShellHost(): Boolean = true

internal actual fun raftPlatformSupportsConversationForwardGesture(): Boolean = true

internal actual fun raftPlatformSupportsAppleSignIn(): Boolean = true

internal actual fun raftPlatformSupportsBackgroundRealtime(): Boolean = false

// Auth/server route rebuilds do not retain the Swift root's pageData. Keep the
// runtime gate in the platform default as well as ContentView so those rebuilt
// app-shell routes preserve Liquid Glass on iOS 26+ while older iOS versions
// still receive the shared fallback.
@OptIn(ExperimentalForeignApi::class)
internal actual fun raftPlatformDefaultAppTabBarRenderer(): String? =
    raftIosAppTabBarRendererForMajorVersion(
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }
    )

internal fun raftIosAppTabBarRendererForMajorVersion(majorVersion: Long): String =
    if (majorVersion >= 26L) "iosLiquidGlass" else "sharedCompose"

// task #11: the iOS video host embeds AVPlayerViewController — the system's
// mature controller (scrub, ±15s, fullscreen) owns the surface, so the
// shared minimal controls stay out of its way (same switch as Android's
// Media3 PlayerView, task #556 phase 2).
internal actual fun raftPlatformUsesNativeVideoController(): Boolean = true

internal actual fun raftPlatformSupportsVideoAttachmentContainer(
    container: RaftPreviewableVideoContainer
): Boolean =
    raftVideoPlayerFormatProfileSupports(
        profile = RaftVideoPlayerFormatProfile.IosAvPlayer,
        container = container
    )

internal actual fun raftPlatformSupportsPushPreviewPrivacySetting(): Boolean = true

internal actual fun raftPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun raftPlatformSupportsPublicAttachmentDownloadReadback(): Boolean = true

internal actual fun raftPlatformSupportsPrivateAttachmentExport(): Boolean = false

internal actual fun raftPlatformPreservesEncodedFontWeightForFallback(): Boolean = true

internal actual fun raftReleaseNotesAppSlug(): String = "raft-ios"

internal actual fun raftPlatformProductName(): String = "iOS"

// The current semantic metadata deliberately avoids Kuikly's legacy inline-code
// marker, so iOS receives the same ordinary wrapping background span as Android.
// TextKit wraps that span without shared U+200B compatibility characters.
internal actual fun raftInlineCodeCharWrapBridgeThreshold(): Int? = null

// Edge-only boxes provide the Web 4px inset while preserving the
// ordinary wrapping background span used by the shared renderer.
internal actual fun raftInlineCodeUsesEdgePaddingBridge(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
internal actual fun raftFirstComposedCharacterEnd(text: String): Int? {
    if (text.isEmpty()) return null
    val cfText =
        CFStringCreateWithCString(
            kCFAllocatorDefault,
            text,
            kCFStringEncodingUTF8
        ) ?: return null
    return try {
        CFStringGetRangeOfComposedCharactersAtIndex(cfText, 0)
            .useContents { (location + length).toInt() }
            .takeIf { it in 1..text.length }
    } finally {
        CFRelease(cfText)
    }
}

// Cross-platform Hands contract: one shared 50MB feedback attachment cap.
internal actual fun raftFeedbackAttachmentMaxBytes(): Long = 50L * 1024L * 1024L

internal actual fun raftFeedbackWebViewClientKind(): String = "ios"

internal actual fun raftSlugSlashCenteringOffsetDp(): Float = 0f

/** Native pop arbitration yields to paging while it can consume the drag. */
internal actual fun raftPlatformTabPagerStartEdgeInsetDp(): Float = 0f

/** Cupertino rubber band can stick at an offset when its spring is cancelled; see expect. */
internal actual fun raftPlatformTabPagerOverscrollEnabled(): Boolean = false
