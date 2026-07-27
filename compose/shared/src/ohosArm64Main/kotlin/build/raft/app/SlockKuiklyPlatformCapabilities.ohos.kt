package build.raft.app

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = true

internal actual fun prepareSlockPlatformSnapshotConsumption() = Unit

internal actual fun slockPlatformConsumesSnapshotsFromKuikly(): Boolean = true

internal actual fun slockPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Enabled

internal actual fun slockPlatformSupportsMultiShellHost(): Boolean = false

internal actual fun slockPlatformDefaultAppTabBarRenderer(): String? = null

// OHOS phase 2 (video controller line, after Android #556 / iOS #11): the
// ArkUI `Video` host has its own mature native control bar (play/scrub/±/
// fullscreen, `controls` on) and seek is wired (seekToTimeMs → VideoController).
// Flip true so the shared minimal overlay is hidden and the HarmonyOS native
// player owns the surface — no more overlay-over-native-bar double controls.
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = true

internal actual fun slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = false

internal actual fun slockPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ohos"

// task #401 A-bridge: core-render-ohos has no slockInlineCode chip yet, so
// wrap long code runs via markdown-layer zero-width spaces. Threshold matches
// the Android render's char-level atomization (runs longer than 16 chars).
// Set back to null when the native OHOS chip lands.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = 16

internal actual fun slockInlineCodeUsesEdgePaddingBridge(): Boolean = true

// No standards-backed composed-character segmenter is exposed to shared OHOS
// yet. Fail closed: preserve exact text and skip the padding group.
internal actual fun slockFirstComposedCharacterEnd(text: String): Int? = null

// Hands OHOS SDK reads attachments into a single ArrayBuffer — 50MB hard cap
// until it streams (see docs/sdk-parity/ohos.md in the hands repo).
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 50L * 1024L * 1024L

internal actual fun slockSlugSlashCenteringOffsetDp(): Float = 0f
