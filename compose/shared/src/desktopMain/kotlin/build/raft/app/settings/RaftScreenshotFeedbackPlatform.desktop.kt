package build.raft.app

import androidx.compose.runtime.Composable
import java.io.File
import java.net.URI

private const val DESKTOP_SCREENSHOT_FEEDBACK_DIR_SEGMENT = "screenshot-feedback"

internal actual fun raftDeleteScreenshotFeedbackTempFile(fileRef: String) {
    val trimmed = fileRef.trim()
    if (trimmed.isEmpty()) return
    val file =
        runCatching {
            val candidate =
                if (trimmed.startsWith("file:", ignoreCase = true)) {
                    File(URI(trimmed))
                } else {
                    File(trimmed)
                }
            candidate.canonicalFile
        }.getOrNull() ?: return
    if (file.parentFile?.name != DESKTOP_SCREENSHOT_FEEDBACK_DIR_SEGMENT) return
    runCatching {
        if (file.isFile) file.delete()
    }
}

@Composable
internal actual fun RaftPlatformScreenshotFeedbackObserverEffect(
    enabled: Boolean,
    pageContextProvider: () -> RaftScreenshotFeedbackPageContext,
) {
    // Desktop does not install a system screenshot observer.
}
