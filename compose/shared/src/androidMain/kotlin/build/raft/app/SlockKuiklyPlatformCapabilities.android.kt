package build.raft.app

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

internal actual fun slockReleaseNotesAppSlug(): String = "raft-android"

// Android's core-render-android draws the chip and char-wraps natively.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = null
