package build.raft.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.platform.ClipEntry
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewFeature
import build.raft.app.components.RaftPullToRefreshStyle
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.network.RAFT_ANDROID_TABLET_SMALLEST_WIDTH_DP

internal actual fun raftPlatformIsiOSAppOnMac(): Boolean = false

internal actual fun raftPlatformPullToRefreshStyle(): RaftPullToRefreshStyle =
    RaftPullToRefreshStyle.Material

internal actual fun raftPlatformPageVisibleLifecycleState(): Lifecycle.State = Lifecycle.State.RESUMED

internal actual fun raftPlatformUsesNativeLucideIconRotation(): Boolean = true

internal actual fun raftPlatformThreadImeProgrammaticViewportPolicy():
    ThreadImeProgrammaticViewportPolicy =
    // task #1010: the route-focus watcher now retires before composer IME
    // correction, so Android can restore the ordinary single-owner path.
    ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen

internal actual fun raftPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Canary

internal actual fun raftPlatformSupportsMultiShellHost(): Boolean = true

/**
 * Process-level cache for [PackageManager.FEATURE_SENSOR_HINGE_ANGLE]. Hardware feature support
 * never changes within an Android process lifetime, so caching avoids a synchronous
 * `PackageManager.hasSystemFeature` Binder IPC on every Settings recomposition. Keyed by
 * application [Context] identity so isolated Robolectric test contexts never share stale entries.
 */
internal object RaftAndroidHingeSensorCache {
    private data class Entry(val context: Context, val hasHingeAngle: Boolean)

    private val cached = AtomicReference<Entry?>(null)

    fun hasHingeAngle(
        context: Context,
        query: (Context) -> Boolean = { ctx ->
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
        },
    ): Boolean {
        val key = context.applicationContext ?: context
        cached.get()?.takeIf { it.context === key }?.let { return it.hasHingeAngle }
        val detected = runCatching { query(key) }.getOrNull() ?: return false
        cached.set(Entry(key, detected))
        return detected
    }

    internal fun resetForTest() {
        cached.set(null)
    }
}

/**
 * A tablet (the smallest width at least 600dp, Android's `sw600dp`, the same line the User-Agent
 * draws) or a foldable (a hinge sensor: folded, its outer screen is phone-sized, unfolded it is
 * large). Smallest width is read at each call (a foldable's smallest width follows its fold); the
 * hinge-angle hardware feature check is cached in [RaftAndroidHingeSensorCache].
 */
internal actual fun raftPlatformLargeScreenPossible(): Boolean {
    // Plain JVM unit tests have no Android framework: no large screen there.
    val smallestWidthDp = runCatching { Resources.getSystem().configuration.smallestScreenWidthDp }.getOrNull() ?: return false
    if (smallestWidthDp >= RAFT_ANDROID_TABLET_SMALLEST_WIDTH_DP) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val context = runCatching { RaftAndroidSqlDatabaseContext.require() }.getOrNull() ?: return false
    return RaftAndroidHingeSensorCache.hasHingeAngle(context)
}

internal actual fun raftPlatformSupportsConversationForwardGesture(): Boolean = false

internal actual fun raftPlatformComposerEnterSendsByDefault(): Boolean = false

internal actual fun raftPlatformMessageTextPointerSelectable(): Boolean = false

internal actual fun raftPlatformHasFinePointer(): Boolean = false

internal actual fun raftPlatformShortcutsUseCommand(): Boolean = false

internal actual fun raftPlatformSupportsInAppBrowser(): Boolean = true

internal actual fun raftPlatformSupportsEmbeddedOwnWebView(): Boolean = true

internal actual fun raftPlatformUsesEphemeralAuthWebView(): Boolean = false

internal actual fun raftPlatformSupportsIsolatedWebViewProfile(): Boolean =
    runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

internal actual fun raftPlatformClipboardHasAttachments(): Boolean = false

internal actual fun raftPlatformReadClipboardAttachments(): List<PlatformFile> = emptyList()

internal actual fun raftPlatformImagePasteClipEntry(): ClipEntry? = null

internal actual fun raftPlatformComposerUsesImagePasteClipboard(): Boolean = false

// As before: the key handler would take it, but Android's clipboard never reports attachments.
internal actual fun raftPlatformComposerKeyHandlerPastesAttachments(): Boolean = true

internal actual fun raftPlatformPastedImagesOpenEditor(): Boolean = false

internal actual fun raftPlatformComposerMinimumBottomPaddingDp(): Float = 0f

internal actual fun raftPlatformSupportsAppleSignIn(): Boolean = false

internal actual fun raftPlatformSupportsBackgroundRealtime(): Boolean = true

internal actual fun raftPlatformAutoPromptsNotificationPermissionAfterSignIn(): Boolean = true

internal actual fun raftPlatformWebLoginEndsOnResume(): Boolean = true

internal actual fun raftPlatformDefaultAppTabBarRenderer(): String? = null

// Media3 PlayerView ships the mature controller (task #556 phase 2).
internal actual fun raftPlatformUsesNativeVideoController(): Boolean = true

internal actual fun raftPlatformPlaysAudioOnlyMedia(): Boolean = true

internal actual fun raftPlatformSupportsVideoAttachmentContainer(
    container: RaftPreviewableVideoContainer
): Boolean =
    raftVideoPlayerFormatProfileSupports(
        profile = RaftVideoPlayerFormatProfile.AndroidMedia3,
        container = container
    )

internal actual fun raftPlatformVideoPlayerFormatProfile(): RaftVideoPlayerFormatProfile? =
    RaftVideoPlayerFormatProfile.AndroidMedia3

// Media3 plays every recognised container.
internal actual fun raftPlatformVlcVideoEngineAvailable(): Boolean = false

// Android reports previewMode in its FCM registration and redacts in AndroidUnreadNotificationPoster,
// which also covers the no-GMS realtime path.
internal actual fun raftPlatformSupportsPushPreviewPrivacySetting(): Boolean = true

internal actual fun raftPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun raftPlatformSupportsPublicAttachmentDownloadReadback(): Boolean = true

internal actual fun raftPlatformSupportsPrivateAttachmentExport(): Boolean = false

internal actual fun raftPlatformPreservesEncodedFontWeightForFallback(): Boolean = true

internal actual fun raftReleaseNotesAppSlug(): String = "raft-android"

internal actual fun raftPlatformHasStorageInfo(): Boolean = true

internal actual fun raftPlatformProductName(): String = "Android"

// Android char-wraps ordinary background spans natively. Edge-only boxes
// provide the Web 4px inset without restoring the atomic legacy chip.
internal actual fun raftInlineCodeCharWrapBridgeThreshold(): Int? = null

internal actual fun raftInlineCodeUsesEdgePaddingBridge(): Boolean = true

internal actual fun raftFirstComposedCharacterEnd(text: String): Int? {
    if (text.isEmpty()) return null
    val boundary =
        if (Build.VERSION.SDK_INT == 0) {
            // Local JVM tests expose Android APIs as stubs. The runtime path is
            // covered by androidInstrumentedTest against the device ICU data.
            BreakIterator.getCharacterInstance(Locale.ROOT).run {
                setText(text)
                first()
                next().takeUnless { it == BreakIterator.DONE }
            }
        } else {
            android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT).run {
                setText(text)
                first()
                next().takeUnless { it == android.icu.text.BreakIterator.DONE }
            }
        }
    return boundary?.takeIf { it in 1..text.length }
}

// Cross-platform Hands contract: one shared 50MB feedback attachment cap.
internal actual fun raftFeedbackAttachmentMaxBytes(): Long = 50L * 1024L * 1024L

internal actual fun raftFeedbackWebViewClientKind(): String = "android"

internal actual fun raftComposerCameraAvailable(): Boolean = true

internal actual fun raftSlugSlashCenteringOffsetDp(): Float = -1.5f

internal actual fun raftPlatformTabPagerStartEdgeInsetDp(): Float = 0f

internal actual fun raftPlatformTabPagerOverscrollEnabled(): Boolean = true

internal actual fun raftPlatformTabPagerSwipeEnabled(): Boolean = true

/** Bottom tabs switch at once, with no fade through (artin 10-30: "我不是很喜欢 删了吧"); see expect. */
internal actual fun raftPlatformAppShellTabFadeThrough(): Boolean = false

internal actual fun raftPlatformFollowsAppStoreBillingRules(): Boolean = false

internal actual fun raftPlatformSupportsAppShellServerStackPage(): Boolean = false
