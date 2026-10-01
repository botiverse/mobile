package build.raft.app

import build.raft.app.data.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThreadComposerImageAnnotationGroupTest {
    @Test
    fun multiImageEditorUsesOneStableGroupIdentity() {
        assertEquals(
            "composer-image-thread-1-first-group",
            threadComposerImageAnnotationGroupId(
                actionId = "composer-image-thread-1-first",
                fileCount = 2
            )
        )
    }

    @Test
    fun singleImageEditorKeepsLegacyBlankGroupIdentity() {
        assertEquals(
            "",
            threadComposerImageAnnotationGroupId(
                actionId = "composer-image-thread-1-first",
                fileCount = 1
            )
        )
    }

    @Test
    fun blankActionIdNeverCreatesAGroupIdentity() {
        assertEquals("", threadComposerImageAnnotationGroupId(actionId = "  ", fileCount = 2))
    }

    @Test
    fun productionPayloadBindsEveryInitialGalleryItemToOneGroup() {
        val payload =
            assertNotNull(
                threadComposerImageAnnotationPayload(
                    threadInstanceKey = "thread-1",
                    actionId = "composer-image-thread-1-first",
                    files =
                        listOf(
                            imageFile("first", "file:///first.jpg"),
                            imageFile("second", "file:///second.jpg")
                        ),
                    originalImage = false
                )
            )

        assertEquals("composer-image-thread-1-first-group", payload.composerImageGroupId)
        assertEquals(2, payload.imageGallery.size)
        assertEquals("file:///first.jpg", payload.imageGallery[0].composerImageFileRef)
        assertEquals("file:///second.jpg", payload.imageGallery[1].composerImageFileRef)
        assertTrue(payload.composerImageCompressAfterEdit)
        assertTrue(payload.showsComposerOriginalImageOption())
        assertFalse(payload.composerOriginalImageSelected())
    }

    @Test
    fun productionPayloadKeepsSingleImageLegacyAndOriginalChoice() {
        val payload =
            assertNotNull(
                threadComposerImageAnnotationPayload(
                    threadInstanceKey = "thread-1",
                    actionId = "composer-image-thread-1-first",
                    files = listOf(imageFile("first", "file:///first.jpg")),
                    originalImage = true
                )
            )

        assertEquals("", payload.composerImageGroupId)
        assertEquals(1, payload.imageGallery.size)
        assertEquals(false, payload.composerImageCompressAfterEdit)
        assertTrue(payload.showsComposerOriginalImageOption())
        assertTrue(payload.composerOriginalImageSelected())
    }

    private fun imageFile(id: String, fileRef: String) =
        PlatformFile(
            id = id,
            fileName = "$id.jpg",
            mimeType = "image/jpeg",
            fileRef = fileRef,
            previewUri = fileRef,
            sizeBytes = 10L
        )
}
