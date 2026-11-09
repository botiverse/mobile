package build.raft.app.markdown

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun raftCleanCopyClipEntry(entry: ClipEntry): ClipEntry {
    val text = entry.getPlainText() ?: return entry
    val clean = text.raftCopyText()
    return if (clean == text) entry else ClipEntry.withPlainText(clean)
}
