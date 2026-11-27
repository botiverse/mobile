package build.raft.app

import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftViewerImageEditTest {
    @Test
    fun onlyConversationImagesOfferEdit() {
        RaftViewerImageEditOpener.threadKey = "thread-1"
        val image =
            RaftFilePreviewPayload(
                kind = "image",
                imageUrl = "https://example.test/a.png",
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "att-1",
            )
        assertTrue(image.offersViewerImageEdit())
        assertFalse(image.copy(kind = "pdf").offersViewerImageEdit())
        assertFalse(image.copy(source = "").offersViewerImageEdit())
        // A composer image (before sending) has its own editor contract.
        assertFalse(image.copy(composerImageActionId = "a").offersViewerImageEdit())
        assertFalse(image.copy(imageUrl = null).offersViewerImageEdit())
        // A viewer no conversation opened (restored after the process died) offers nothing.
        RaftViewerImageEditOpener.threadKey = ""
        assertFalse(image.offersViewerImageEdit())
    }

    @Test
    fun theShownGalleryImageIsTheOneEdited() {
        val payload =
            RaftFilePreviewPayload(
                kind = "image",
                attachmentId = "att-1",
                imageGallery = listOf(RaftFilePreviewGalleryImage(attachmentId = "att-1"), RaftFilePreviewGalleryImage(attachmentId = "att-2")),
                imageGalleryIndex = 1,
            )
        assertEquals("att-2", payload.viewerShownAttachmentId())
        assertEquals("att-1", payload.copy(imageGallery = emptyList()).viewerShownAttachmentId())
    }

    @Test
    fun desktopKeys() {
        val key = androidx.compose.ui.input.key.Key
        assertEquals(RaftComposerImageDesktopKey.Cancel, raftComposerImageDesktopKey(key.Escape, down = true, textTool = true))
        assertEquals(RaftComposerImageDesktopKey.Done, raftComposerImageDesktopKey(key.Enter, down = true, textTool = false))
        // In the text tool Return and Space type into the field.
        assertNull(raftComposerImageDesktopKey(key.Enter, down = true, textTool = true))
        assertNull(raftComposerImageDesktopKey(key.Spacebar, down = true, textTool = true))
        assertEquals(RaftComposerImageDesktopKey.SpaceDown, raftComposerImageDesktopKey(key.Spacebar, down = true, textTool = false))
        assertEquals(RaftComposerImageDesktopKey.SpaceUp, raftComposerImageDesktopKey(key.Spacebar, down = false, textTool = false))
    }
}
