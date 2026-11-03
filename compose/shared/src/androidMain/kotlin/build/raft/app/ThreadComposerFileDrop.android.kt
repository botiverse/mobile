package build.raft.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext

// Files dragged in from another app (split screen, desktop windowing, a tablet's taskbar): the
// drag's ClipData `content:` URIs. Reading another app's URIs needs the activity's drag-and-drop
// permissions, asked for while the drop is delivered and released once the host has copied the
// files into its picker cache (RaftCmpActivity `threadAttachmentDrop`).
internal actual fun raftPlatformSupportsComposerFileDrop(): Boolean = true

internal actual fun raftDragAndDropEventHasFiles(event: DragAndDropEvent): Boolean {
    val description = event.toAndroidDragEvent().clipDescription ?: return false
    return (0 until description.mimeTypeCount).any { index ->
        threadComposerDragMimeTypeIsFile(description.getMimeType(index).orEmpty())
    }
}

@Composable
internal actual fun rememberRaftComposerFileDropReader(): (DragAndDropEvent) -> ThreadComposerFileDrop? {
    val context = LocalContext.current
    return remember(context) { { event -> raftAndroidComposerFileDrop(context, event) } }
}

private fun raftAndroidComposerFileDrop(context: Context, event: DragAndDropEvent): ThreadComposerFileDrop? {
    val dragEvent = event.toAndroidDragEvent()
    val clip = dragEvent.clipData ?: return null
    val uris = (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }.filter { it.scheme == "content" }
    if (uris.isEmpty()) return null
    val permissions = context.raftFindActivity()?.requestDragAndDropPermissions(dragEvent)
    val items =
        uris.map { uri ->
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ThreadComposerDroppedItem(uri.toString(), isFolder = type == DocumentsContract.Document.MIME_TYPE_DIR)
        }
    return threadComposerFileDrop(items, release = { runCatching { permissions?.release() } })
}

private tailrec fun Context.raftFindActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.raftFindActivity()
        else -> null
    }
