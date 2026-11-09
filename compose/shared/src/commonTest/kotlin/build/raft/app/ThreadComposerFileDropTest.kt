package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Files dragged onto the composer (web MessageInput `handleDrop`) up to the picker's admission. */
class ThreadComposerFileDropTest {
    @Test
    fun dropKeepsEachFileOnceInOrderAndCountsFolders() {
        var released = 0
        val drop =
            threadComposerFileDrop(
                listOf(
                    ThreadComposerDroppedItem("/Users/a/notes.pdf"),
                    ThreadComposerDroppedItem("/Users/a/Photos", isFolder = true),
                    ThreadComposerDroppedItem(" /Users/a/notes.pdf "),
                    ThreadComposerDroppedItem("  "),
                    ThreadComposerDroppedItem("content://media/external/images/1"),
                ),
                release = { released++ },
            )
        assertEquals(listOf("/Users/a/notes.pdf", "content://media/external/images/1"), drop.fileRefs)
        assertEquals(1, drop.folderCount)
        drop.release()
        drop.release()
        assertEquals(1, released, "the platform grant is given back once")
    }

    @Test
    fun foldersGetTheWebNotice() {
        val cases =
            listOf(
                listOf(ThreadComposerDroppedItem("/a.txt")) to ThreadComposerFileDropFolderNotice.None,
                listOf(ThreadComposerDroppedItem("/dir", isFolder = true)) to ThreadComposerFileDropFolderNotice.FoldersCantUpload,
                listOf(ThreadComposerDroppedItem("/dir", isFolder = true), ThreadComposerDroppedItem("/a.txt")) to
                    ThreadComposerFileDropFolderNotice.FoldersSkipped,
            )
        cases.forEach { (items, notice) -> assertEquals(notice, threadComposerFileDrop(items).folderNotice(), "$items") }
    }

    @Test
    fun onlyFileDragsLightTheComposerUp() {
        listOf("text/plain", "text/html", "TEXT/PLAIN; charset=utf-8", "text/uri-list", "text/vnd.android.intent", "")
            .forEach { assertFalse(threadComposerDragMimeTypeIsFile(it), it) }
        listOf("image/png", "application/pdf", "video/mp4", "text/csv", "application/octet-stream")
            .forEach { assertTrue(threadComposerDragMimeTypeIsFile(it), it) }
    }

    @Test
    fun droppedImagesGoStraightToTheTrayLikeAFilePick() {
        // The web adds dropped files directly; only the image button / camera open the editor.
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(ThreadComposerDropAttachmentsAction, fileCount = 2, allFilesAreImages = true),
        )
        assertEquals(
            ThreadPickedAttachmentComposerAction.None,
            resolveThreadPickedAttachmentComposerAction(ThreadComposerDropAttachmentsAction, fileCount = 0),
        )
    }

    @Test
    fun aDropNeedsWriteAccessLikeTheAttachButtons() {
        assertTrue(threadComposerEventRequiresWriteAccess(ThreadComposerDropAttachmentsAction))
        assertFalse(threadComposerEventAllowed(writeActionsEnabled = false, action = ThreadComposerDropAttachmentsAction))
    }

    @Test
    fun aDropWithNothingInItIsRefusedAndReleased() {
        val state = ThreadComposerFileDropState()
        var delivered = 0
        var released = 0
        state.onDragEntered()
        assertFalse(state.onDropped(null) { delivered++ })
        assertFalse(state.dragging)
        state.onDragEntered()
        assertFalse(state.onDropped(threadComposerFileDrop(emptyList(), release = { released++ })) { delivered++ })
        assertEquals(0, delivered)
        assertEquals(1, released)
    }
}
