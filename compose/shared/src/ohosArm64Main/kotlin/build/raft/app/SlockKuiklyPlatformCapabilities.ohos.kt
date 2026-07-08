package build.raft.app

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = true

// No native controller on the OHOS video host yet; shared controls stay.
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = false

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ohos"

// task #401 A-bridge: core-render-ohos has no slockInlineCode chip yet, so
// wrap long code runs via markdown-layer zero-width spaces. Threshold matches
// the Android render's char-level atomization (runs longer than 16 chars).
// Set back to null when the native OHOS chip lands.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = 16
