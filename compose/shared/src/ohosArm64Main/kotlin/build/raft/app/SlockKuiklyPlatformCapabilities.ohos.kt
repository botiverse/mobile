package build.raft.app

import build.raft.app.ohosicu.slock_icu_first_grapheme_boundary_utf16
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = true

internal actual fun slockPlatformConsumesSnapshotsFromKuikly(): Boolean = true

internal actual fun slockPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy =
    ThreadImeProgrammaticViewportPolicy.SuppressWhileImeOpen

internal actual fun slockPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Enabled

internal actual fun slockPlatformSupportsMultiShellHost(): Boolean = false

internal actual fun slockPlatformSupportsConversationForwardGesture(): Boolean = false

internal actual fun slockPlatformSupportsAppleSignIn(): Boolean = false

internal actual fun slockPlatformDefaultAppTabBarRenderer(): String? = null

// OHOS phase 2 (video controller line, after Android #556 / iOS #11): the
// ArkUI `Video` host has its own mature native control bar (play/scrub/±/
// fullscreen, `controls` on) and seek is wired (seekToTimeMs → VideoController).
// Flip true so the shared minimal overlay is hidden and the HarmonyOS native
// player owns the surface — no more overlay-over-native-bar double controls.
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = true

internal actual fun slockPlatformSupportsVideoAttachmentContainer(
    container: SlockPreviewableVideoContainer
): Boolean =
    slockVideoPlayerFormatProfileSupports(
        profile = SlockVideoPlayerFormatProfile.OhosArkUiVideo,
        container = container
    )

internal actual fun slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = false

internal actual fun slockPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun slockPlatformSupportsPublicAttachmentDownloadReadback(): Boolean = true

internal actual fun slockPlatformSupportsPrivateAttachmentExport(): Boolean = true

internal actual fun slockPlatformPreservesEncodedFontWeightForFallback(): Boolean = true

internal actual fun slockReleaseNotesAppSlug(): String = "raft-ohos"

internal actual fun slockPlatformProductName(): String = "HarmonyOS"

// task #401 A-bridge: core-render-ohos has no slockInlineCode chip yet, so
// wrap long code runs via markdown-layer zero-width spaces. Threshold matches
// the Android render's char-level atomization (runs longer than 16 chars).
// Set back to null when the native OHOS chip lands.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = 16

internal actual fun slockInlineCodeUsesEdgePaddingBridge(): Boolean = true

// Null keeps the #1767 single-child full-range box fallback active when ICU
// cannot produce a valid boundary, so inline-code chrome is never dropped.
@OptIn(ExperimentalForeignApi::class)
internal actual fun slockFirstComposedCharacterEnd(text: String): Int? {
    if (text.isEmpty()) return null

    val utf16 = ShortArray(text.length) { index -> text[index].code.toShort() }
    return utf16.usePinned { pinned ->
        slock_icu_first_grapheme_boundary_utf16(
            pinned.addressOf(0).reinterpret(),
            text.length
        ).takeIf { it in 1..text.length }
    }
}

// Hands OHOS SDK reads attachments into a single ArrayBuffer — 50MB hard cap
// until it streams (see docs/sdk-parity/ohos.md in the hands repo).
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 50L * 1024L * 1024L

internal actual fun slockFeedbackWebViewClientKind(): String = "ohos"

internal actual fun slockSlugSlashCenteringOffsetDp(): Float = 0f

internal actual fun slockPlatformTabPagerStartEdgeInsetDp(): Float = 0f
