package build.raft.app.settings

import android.net.Uri
import androidx.compose.runtime.Composable
import java.io.File

private const val SCREENSHOT_FEEDBACK_DIR_SEGMENT = "screenshot-feedback"

internal actual fun raftDeleteScreenshotFeedbackTempFile(fileRef: String) {
    val trimmed = fileRef.trim()
    if (trimmed.isEmpty()) return
    val path =
        if (trimmed.startsWith("/")) {
            trimmed
        } else {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return
            if (!uri.scheme.equals("file", ignoreCase = true)) return
            uri.path ?: return
        }
    val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return
    if (file.parentFile?.name != SCREENSHOT_FEEDBACK_DIR_SEGMENT) return
    runCatching {
        if (file.isFile) file.delete()
    }
}

@Composable
internal actual fun RaftPlatformScreenshotFeedbackObserverEffect(
    enabled: Boolean,
    pageContextProvider: () -> RaftScreenshotFeedbackPageContext,
) {
    // Android drives screenshot capture directly from RaftCmpActivity (API 34 ScreenCaptureCallback + PixelCopy).
}
