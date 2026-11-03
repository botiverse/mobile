package build.raft.app.markdown

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun raftCleanCopyClipEntry(entry: ClipEntry): ClipEntry {
    val data = entry.clipData
    if (data.itemCount == 0) return entry
    var changed = false
    val items =
        List(data.itemCount) { index ->
            val item = data.getItemAt(index)
            val text = item.text?.toString()
            val clean = text?.raftCopyText()
            if (text == null || clean == text) {
                item
            } else {
                changed = true
                // Plain text only: the item's styled / HTML forms would still hold the marks.
                ClipData.Item(clean)
            }
        }
    if (!changed) return entry
    val cleaned = ClipData(data.description, items.first())
    items.drop(1).forEach(cleaned::addItem)
    return ClipEntry(cleaned)
}
