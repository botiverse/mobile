package build.raft.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetRangeOfComposedCharactersAtIndex
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSProcessInfo

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

internal actual fun slockPlatformConsumesSnapshotsFromKuikly(): Boolean = true

internal actual fun slockPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy =
    ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen

internal actual fun slockPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Canary

internal actual fun slockPlatformSupportsMultiShellHost(): Boolean = false

internal actual fun slockPlatformSupportsConversationForwardGesture(): Boolean = true

internal actual fun slockPlatformSupportsAppleSignIn(): Boolean = true

// Auth/server route rebuilds do not retain the Swift root's pageData. Keep the
// runtime gate in the platform default as well as ContentView so those rebuilt
// app-shell routes preserve Liquid Glass on iOS 26+ while older iOS versions
// still receive the shared fallback.
@OptIn(ExperimentalForeignApi::class)
internal actual fun slockPlatformDefaultAppTabBarRenderer(): String? =
    slockIosAppTabBarRendererForMajorVersion(
        NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }
    )

internal fun slockIosAppTabBarRendererForMajorVersion(majorVersion: Long): String =
    if (majorVersion >= 26L) "iosLiquidGlass" else "sharedCompose"

// task #11: the iOS video host embeds AVPlayerViewController — the system's
// mature controller (scrub, ±15s, fullscreen) owns the surface, so the
// shared minimal controls stay out of its way (same switch as Android's
// Media3 PlayerView, task #556 phase 2).
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = true

internal actual fun slockPlatformSupportsVideoAttachmentContainer(
    container: SlockPreviewableVideoContainer
): Boolean =
    slockVideoPlayerFormatProfileSupports(
        profile = SlockVideoPlayerFormatProfile.IosAvPlayer,
        container = container
    )

internal actual fun slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = true

internal actual fun slockPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun slockPlatformSupportsPublicAttachmentDownloadReadback(): Boolean = true

internal actual fun slockPlatformSupportsPrivateAttachmentExport(): Boolean = false

internal actual fun slockPlatformPreservesEncodedFontWeightForFallback(): Boolean = false

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ios"

internal actual fun slockPlatformProductName(): String = "iOS"

// The current semantic metadata deliberately avoids Kuikly's legacy inline-code
// marker, so iOS receives the same ordinary wrapping background span as Android.
// TextKit wraps that span without shared U+200B compatibility characters.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = null

// Edge-only boxes provide the Web 4px inset while preserving the
// ordinary wrapping background span used by the shared renderer.
internal actual fun slockInlineCodeUsesEdgePaddingBridge(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
internal actual fun slockFirstComposedCharacterEnd(text: String): Int? {
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

// Cross-platform Hands contract is bounded by the OHOS SDK's current 50MB cap.
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 50L * 1024L * 1024L

internal actual fun slockFeedbackWebViewClientKind(): String = "ios"

internal actual fun slockSlugSlashCenteringOffsetDp(): Float = 0f

/** TabPager releases first-page rightward drags to native full-screen back. */
internal actual fun slockPlatformTabPagerStartEdgeInsetDp(): Float = 1_000_000f
