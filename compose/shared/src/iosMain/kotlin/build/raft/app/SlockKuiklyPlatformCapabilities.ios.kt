package build.raft.app

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

// task #11: the iOS video host embeds AVPlayerViewController — the system's
// mature controller (scrub, ±15s, fullscreen) owns the surface, so the
// shared minimal controls stay out of its way (same switch as Android's
// Media3 PlayerView, task #556 phase 2).
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = true

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ios"

// iOS native render owns inline-code chrome/padding, but TextKit still needs
// markdown-layer U+200B break opportunities for #58 character-level wrapping.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = 16

// #439 landed native iOS chip chrome. Do not also inject U+00A0 edge padding:
// KRLabel would paint those compatibility characters inside the yellow fill.
internal actual fun slockInlineCodeUsesEdgePaddingBridge(): Boolean = false

// Hands iOS SDK ios-v0.2.x uploads >10MB attachments via presigned R2 PUT.
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 200L * 1024L * 1024L
