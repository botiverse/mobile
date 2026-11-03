package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftViewerImageEditTest {
    @Test
    fun editedCopyIsAPngNamedAfterTheOriginal() {
        assertEquals("IMG_2042-edited.png", raftViewerEditedImageFileName("IMG_2042.jpg"))
        assertEquals("image-edited.png", raftViewerEditedImageFileName(""))
        val file = raftViewerEditedImageFile("file:///cache/raft_viewer_edit_1.png", "photo.heic", 3)
        assertEquals("viewer-edit-3", file.id)
        assertEquals("image/png", file.mimeType)
        assertEquals(file.fileRef, file.previewUri)
        // The composer treats it as its own temporary file, like a pasted image.
        assertTrue(file.isComposerImageOwnedTemporary())
    }

    @Test
    fun editRequestRoundTrips() {
        assertEquals("att-1", raftViewerImageEditAttachmentId(raftViewerImageEditEvent("att-1")))
        assertNull(raftViewerImageEditAttachmentId(null))
        assertNull(raftViewerImageEditAttachmentId("{}"))
    }

    @Test
    fun onlyConversationImagesOfferEdit() {
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
}
