package build.raft.app.markdown

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun raftCleanCopyClipEntry(entry: ClipEntry): ClipEntry {
    val transferable = entry.nativeClipEntry as? Transferable ?: return entry
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return entry
    val text = runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return entry
    val clean = text.raftCopyText()
    return if (clean == text) entry else ClipEntry(StringSelection(clean))
}
