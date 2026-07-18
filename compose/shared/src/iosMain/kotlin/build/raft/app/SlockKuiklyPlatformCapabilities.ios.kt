package build.raft.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

internal actual fun slockPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Disabled

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

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ios"

// iOS atomizes inline code into one native attachment per grapheme. TextKit can
// therefore wrap at atom boundaries without shared U+200B compatibility chars.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = null

// Native atom-chain bounds own the same 4/15 inner + 2/15 outer geometry as
// reference-chip attachments, so shared must not synthesize NBSP padding.
internal actual fun slockInlineCodeUsesEdgePaddingBridge(): Boolean = false

// Hands iOS SDK ios-v0.2.x uploads >10MB attachments via presigned R2 PUT.
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 200L * 1024L * 1024L

internal actual fun slockSlugSlashCenteringOffsetDp(): Float = 0f
