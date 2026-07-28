package build.raft.app

import android.os.Build
import java.text.BreakIterator
import java.util.Locale

internal actual fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean = true

internal actual fun slockPlatformUsesNativeLucideIconRotation(): Boolean = true

internal actual fun prepareSlockPlatformSnapshotConsumption() {
    checkNotNull(androidXSnapshotDispatcherStarter) {
        "AndroidX snapshot dispatcher must be installed before Kuikly page initialization"
    }.invoke()
}

private var androidXSnapshotDispatcherStarter: (() -> Unit)? = null

fun installSlockAndroidXSnapshotDispatcherStarter(starter: () -> Unit) {
    androidXSnapshotDispatcherStarter = starter
}

internal actual fun slockPlatformConsumesSnapshotsFromKuikly(): Boolean = false

internal actual fun slockPlatformAppShellChildPagePrewarmMode(): AppShellChildPagePrewarmMode =
    AppShellChildPagePrewarmMode.Canary

internal actual fun slockPlatformSupportsMultiShellHost(): Boolean = true

internal actual fun slockPlatformDefaultAppTabBarRenderer(): String? = null

// Media3 PlayerView ships the mature controller (task #556 phase 2).
internal actual fun slockPlatformUsesNativeVideoController(): Boolean = true

internal actual fun slockPlatformSupportsPushPreviewPrivacySetting(): Boolean = false

internal actual fun slockPlatformSupportsSaveImageToPhotos(): Boolean = true

internal actual fun slockPlatformSupportsPublicAttachmentDownloadReadback(): Boolean = true

internal actual fun slockReleaseNotesAppSlug(): String = "raft-android"

// Android char-wraps ordinary background spans natively. Edge-only boxes
// provide the Web 4px inset without restoring the atomic legacy chip.
internal actual fun slockInlineCodeCharWrapBridgeThreshold(): Int? = null

internal actual fun slockInlineCodeUsesEdgePaddingBridge(): Boolean = true

internal actual fun slockFirstComposedCharacterEnd(text: String): Int? {
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

// Hands Android SDK 0.10.x uploads >10MB attachments via presigned R2 PUT.
internal actual fun slockFeedbackAttachmentMaxBytes(): Long = 200L * 1024L * 1024L

internal actual fun slockSlugSlashCenteringOffsetDp(): Float = -1.5f
