package build.raft.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFStringGetRangeOfComposedCharactersAtIndex
import platform.CoreFoundation.CFStringRef
import platform.Foundation.NSProcessInfo

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

internal actual fun slockPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Canary

internal actual fun slockPlatformSupportsMultiShellHost(): Boolean = false

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

internal actual fun slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = true

internal actual fun slockPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ios"

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
    return CFStringGetRangeOfComposedCharactersAtIndex(text as CFStringRef, 0)
        .useContents { (location + length).toInt() }
        .takeIf { it in 1..text.length }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun slockLastComposedCharacterStart(text: String): Int? {
    if (text.isEmpty()) return null
    return CFStringGetRangeOfComposedCharactersAtIndex(text as CFStringRef, text.lastIndex.toLong())
        .useContents { location.toInt() }
        .takeIf { it in 0 until text.length }
}

// Hands iOS SDK ios-v0.2.x uploads >10MB attachments via presigned R2 PUT.
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 200L * 1024L * 1024L

internal actual fun slockSlugSlashCenteringOffsetDp(): Float = 0f
