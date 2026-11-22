package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Neighbour full-size preloads: ordering, priority against the shown image, and zoom identity. */
class RaftFilePreviewImageGalleryPreloadTest {
    private val gallery =
        (1..4).map { RaftFilePreviewGalleryImage(attachmentId = "att-$it", name = "$it.png", thumbnailUrl = "thumb-$it") }

    /** Pending (thumbnail-only) payload for gallery index [index], as the page shows it before resolving. */
    private fun pendingAt(index: Int): RaftFilePreviewPayload =
        RaftFilePreviewPayload(
            kind = "loading",
            requestedKind = "image",
            imageUrl = "thumb-1",
            source = "thread_attachment",
            attachmentId = "att-1",
            imageGallery = gallery
        ).let { if (index == 0) it else it.withImageGalleryIndex(index)!! }

    private fun resolved(payload: RaftFilePreviewPayload): RaftFilePreviewPayload =
        payload.copy(kind = "image", imageUrl = "full-${payload.attachmentId}", messageDesc = null)

    @Test
    fun zoomIdentityIgnoresTheThumbnailToFullSizeSwapButNotAnotherImage() {
        val thumbnail = pendingAt(1)
        val fullSize = resolved(thumbnail).copy(imageGallery = gallery, imageGalleryIndex = 1)

        assertEquals(thumbnail.raftFilePreviewImageZoomIdentity(), fullSize.raftFilePreviewImageZoomIdentity())
        assertNotEquals(thumbnail.raftFilePreviewImageZoomIdentity(), pendingAt(2).raftFilePreviewImageZoomIdentity())
        assertNotEquals(
            fullSize.raftFilePreviewImageZoomIdentity(),
            fullSize.withImageGalleryIndex(2, mapOf("att-3" to resolved(pendingAt(2))))!!.raftFilePreviewImageZoomIdentity()
        )
    }
}
