package build.raft.app

import build.raft.app.thread.ThreadPickedAttachmentComposerAction
import build.raft.app.thread.resolveThreadPickedAttachmentComposerAction
import build.raft.app.thread.threadComposerEventAllowed
import build.raft.app.thread.threadComposerEventRequiresWriteAccess
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
}
