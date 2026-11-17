package build.raft.app

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.platform.testTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftComposerDropOverlay

// Drag files onto the composer to attach them (web MessageInput `handleDragEnter` /
// `handleDrop`, botiverse/slock #121): only file drags light the composer up ("Drop files to
// attach"); a drop hands the files to the same path as the attach button. Text drags are left to
// the text field. Folders are skipped, with the web's notice.

/** The composer action for a drop (the picker's `attachFile` counterpart; never the image editor). */
internal const val ThreadComposerDropAttachmentsAction = "dropAttachments"

internal const val ThreadComposerFileDropOverlayTag = "thread_composer_drop_overlay"

/** One dropped item as the platform reports it: a host file ref and whether it is a folder. */
internal data class ThreadComposerDroppedItem(val fileRef: String, val isFolder: Boolean = false)

/**
 * One drop on the composer, taken from the platform drag event while it is delivered.
 * [fileRefs] are what the host's `threadAttachmentDrop` reads (desktop: absolute paths; Android:
 * `content:` URIs), each once, in drop order. [release] gives back what the platform granted
 * for reading them (Android's drag-and-drop permissions); call it once the host has answered.
 */
internal class ThreadComposerFileDrop(
    val fileRefs: List<String>,
    val folderCount: Int,
    private val onRelease: () -> Unit = {},
) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/** Distinct, non-blank file refs; folders counted, not attached (web `webkitGetAsEntry().isDirectory`). */
internal fun threadComposerFileDrop(
    items: List<ThreadComposerDroppedItem>,
    release: () -> Unit = {},
): ThreadComposerFileDrop {
    val folders = items.count { it.isFolder }
    val refs = items.filterNot { it.isFolder }.map { it.fileRef.trim() }.filter { it.isNotEmpty() }.distinct()
    return ThreadComposerFileDrop(fileRefs = refs, folderCount = folders, onRelease = release)
}

internal enum class ThreadComposerFileDropFolderNotice { None, FoldersCantUpload, FoldersSkipped }

/** Web: only folders -> "Folders can't be uploaded"; files and folders -> "Folders were skipped". */
internal fun ThreadComposerFileDrop.folderNotice(): ThreadComposerFileDropFolderNotice =
    when {
        folderCount == 0 -> ThreadComposerFileDropFolderNotice.None
        fileRefs.isEmpty() -> ThreadComposerFileDropFolderNotice.FoldersCantUpload
        else -> ThreadComposerFileDropFolderNotice.FoldersSkipped
    }

/**
 * Whether a drag's advertised MIME type is a file rather than text (Android `ClipDescription`):
 * plain / HTML text, links and intents are text drags, which the text field takes.
 */
internal fun threadComposerDragMimeTypeIsFile(mimeType: String): Boolean {
    val type = mimeType.substringBefore(';').trim().lowercase()
    if (type.isEmpty()) return false
    return type !in ThreadComposerTextDragMimeTypes
}

private val ThreadComposerTextDragMimeTypes =
    setOf("text/plain", "text/html", "text/uri-list", "text/vnd.android.intent")

/** Whether this platform delivers external file drags to the composer (iOS: not yet). */
internal expect fun raftPlatformSupportsComposerFileDrop(): Boolean

/** Whether [event] carries files (checked when a drag starts, before anything can be read). */
internal expect fun raftDragAndDropEventHasFiles(event: DragAndDropEvent): Boolean

/**
 * Reads the dropped files of [event] while the drop is delivered (Android must ask for the read
 * permission then); null when it carries none.
 */
@Composable
internal expect fun rememberRaftComposerFileDropReader(): (DragAndDropEvent) -> ThreadComposerFileDrop?

/** Whether files are being dragged over the composer; the drop target drives it. */
@Stable
internal class ThreadComposerFileDropState {
    var dragging by mutableStateOf(false)
        private set

    fun onDragEntered() {
        if (!dragging) dragging = true
    }

    /** Left the composer, or the drag ended anywhere. */
    fun onDragLeft() {
        if (dragging) dragging = false
    }

    /** A drop: the overlay goes; a drop with files or folders goes to [onDropFiles]. */
    fun onDropped(drop: ThreadComposerFileDrop?, onDropFiles: (ThreadComposerFileDrop) -> Unit): Boolean {
        onDragLeft()
        if (drop == null || (drop.fileRefs.isEmpty() && drop.folderCount == 0)) {
            drop?.release()
            return false
        }
        onDropFiles(drop)
        return true
    }
}

/**
 * The composer's drop zone: [content] with the drop target on it and, while files are dragged
 * over it, raft-ui's `ComposerDropOverlay` over all of it. With [enabled] false (a read-only or
 * attachment-less composer, iOS) nothing is installed and file drags pass by, as the web's
 * compact composer swallows them.
 */
@Composable
internal fun ThreadComposerFileDropZone(
    enabled: Boolean,
    onDropFiles: (ThreadComposerFileDrop) -> Unit,
    modifier: Modifier = Modifier,
    state: ThreadComposerFileDropState = remember { ThreadComposerFileDropState() },
    content: @Composable () -> Unit,
) {
    val active = enabled && raftPlatformSupportsComposerFileDrop()
    LaunchedEffect(active) { if (!active) state.onDragLeft() }
    val target =
        if (active) {
            val reader = rememberRaftComposerFileDropReader()
            val currentReader by rememberUpdatedState(reader)
            val currentOnDropFiles by rememberUpdatedState(onDropFiles)
            val dropTarget =
                remember(state) {
                    object : DragAndDropTarget {
                        override fun onDrop(event: DragAndDropEvent): Boolean =
                            state.onDropped(runCatching { currentReader(event) }.getOrNull(), currentOnDropFiles)

                        override fun onEntered(event: DragAndDropEvent) = state.onDragEntered()

                        override fun onExited(event: DragAndDropEvent) = state.onDragLeft()

                        override fun onEnded(event: DragAndDropEvent) = state.onDragLeft()
                    }
                }
            Modifier.dragAndDropTarget(
                shouldStartDragAndDrop = { event -> runCatching { raftDragAndDropEventHasFiles(event) }.getOrDefault(false) },
                target = dropTarget,
            )
        } else {
            Modifier
        }
    Box(modifier.then(target)) {
        content()
        if (active && state.dragging) {
            RaftComposerDropOverlay(
                label = stringResource(Res.string.thread_composer_drop_to_attach),
                modifier = Modifier.matchParentSize().testTag(ThreadComposerFileDropOverlayTag),
            )
        }
    }
}
