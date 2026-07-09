package build.raft.app

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

// task #11: the iOS video host embeds AVPlayerViewController — the system's
// mature controller (scrub, ±15s, fullscreen) owns the surface, so the
// shared minimal controls stay out of its way (same switch as Android's
// Media3 PlayerView, task #556 phase 2).
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = true

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ios"

// task #401 A-bridge: core-render-ios has no slockInlineCode chip yet, so
// wrap long code runs via markdown-layer zero-width spaces. Threshold matches
// the Android render's char-level atomization (runs longer than 16 chars).
// Set back to null when the native iOS chip lands (task #402).
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = 16

// Hands iOS SDK ios-v0.2.x uploads >10MB attachments via presigned R2 PUT.
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 200L * 1024L * 1024L
