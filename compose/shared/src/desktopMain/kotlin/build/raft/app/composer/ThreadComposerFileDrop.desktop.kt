package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

// Finder (or any app) dragging files onto the window: AWT's file list. The host reads the paths
// as it reads the open panel's (RaftDesktopPickedAttachments), so a drop is a File pick.
internal actual fun raftPlatformSupportsComposerFileDrop(): Boolean = true

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun raftDragAndDropEventHasFiles(event: DragAndDropEvent): Boolean =
    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun rememberRaftComposerFileDropReader(): (DragAndDropEvent) -> ThreadComposerFileDrop? =
    { event -> raftDesktopComposerFileDrop(event.awtTransferable) }

/** The dropped file list of [transferable]; null when it has none. */
internal fun raftDesktopComposerFileDrop(transferable: Transferable): ThreadComposerFileDrop? {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    val files =
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            ?.filterIsInstance<File>()
            .orEmpty()
    if (files.isEmpty()) return null
    return threadComposerFileDrop(files.map { file -> ThreadComposerDroppedItem(file.absolutePath, isFolder = file.isDirectory) })
}
