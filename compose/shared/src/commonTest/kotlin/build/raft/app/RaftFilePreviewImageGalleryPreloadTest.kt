package build.raft.app

import build.raft.app.image.raftAttachmentOriginalImageCacheKey
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

    @Test
    fun singleImageGalleryEmitsNoKeepAliveEntriesToAvoidDuplicateDecode() {
        val singleComposer =
            RaftFilePreviewPayload(
                kind = "image",
                imageUrl = "file:///tmp/photo.jpg",
                attachmentId = "local-1",
                composerImageActionId = "composer-image-1",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "local-1",
                            name = "photo.jpg",
                            thumbnailUrl = "file:///tmp/photo.jpg",
                        )
                    ),
                imageGalleryIndex = 0,
            )
        val singleThreadAttachment =
            RaftFilePreviewPayload(
                kind = "image",
                imageUrl = "https://cdn.example/full-1.jpg",
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "att-1",
                imageGallery = listOf(gallery.first()),
                imageGalleryIndex = 0,
            )

        assertEquals(emptyList(), singleComposer.imageGalleryKeepAliveEntries())
        assertEquals(emptyList(), singleThreadAttachment.imageGalleryKeepAliveEntries())
    }

    @Test
    fun multiImageGalleryKeepAliveRetainsOnlyImmediateNeighborsNotCurrentPage() {
        val current = resolved(pendingAt(1)).copy(imageGallery = gallery, imageGalleryIndex = 1)
        val resolvedById =
            mapOf(
                "att-1" to resolved(pendingAt(0)),
                "att-2" to current,
                "att-3" to resolved(pendingAt(2)),
                "att-4" to resolved(pendingAt(3)),
            )

        val entries = current.imageGalleryKeepAliveEntries(resolvedById)

        assertEquals(
            listOf(
                "full-att-1" to raftAttachmentOriginalImageCacheKey("att-1"),
                "full-att-3" to raftAttachmentOriginalImageCacheKey("att-3"),
                "thumb-1" to null,
                "thumb-3" to null,
            ),
            entries,
        )
    }
}
