package build.raft.app

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

// Media3 PlayerView ships the mature controller (task #556 phase 2).
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = true

internal actual fun slockReleaseNotesAppSlug(): String = "raft-android"

// Android's core-render-android draws the chip and char-wraps natively.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = null

// Hands Android SDK 0.10.x uploads >10MB attachments via presigned R2 PUT.
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 200L * 1024L * 1024L
