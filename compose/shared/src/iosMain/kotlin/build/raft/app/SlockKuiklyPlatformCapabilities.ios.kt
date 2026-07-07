package build.raft.app

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = false

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ios"

// task #401 A-bridge: core-render-ios has no slockInlineCode chip yet, so
// wrap long code runs via markdown-layer zero-width spaces. Threshold matches
// the Android render's char-level atomization (runs longer than 16 chars).
// Set back to null when the native iOS chip lands (task #402).
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = 16
