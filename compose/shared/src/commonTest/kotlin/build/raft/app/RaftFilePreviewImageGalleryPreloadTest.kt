package build.raft.app

import build.raft.app.data.DataRequestPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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

    private fun RaftFilePreviewImageGalleryResolutionState.begin(
        payload: RaftFilePreviewPayload,
        preload: Boolean = false
    ): RaftFilePreviewImageGalleryResolutionState = beginResolution(payload, preload).state

    @Test
    fun preloadsNextThenPreviousOnlyOnceTheShownImageHasItsRequest() {
        val shown = pendingAt(1)

        assertEquals(
            emptyList(),
            shown.imageGalleryPreloadPayloads(RaftFilePreviewImageGalleryResolutionState()),
            "the shown image starts first"
        )

        val state = RaftFilePreviewImageGalleryResolutionState().begin(shown)
        assertEquals(listOf("att-3", "att-1"), shown.imageGalleryPreloadPayloads(state).map { it.attachmentId })
    }

    @Test
    fun preloadSkipsResolvedInFlightAndAlreadyAttemptedNeighbours() {
        val first = pendingAt(0)
        val third = pendingAt(2)
        var state = RaftFilePreviewImageGalleryResolutionState().begin(first)
        state = state.completeResolution(first, resolved(first))
        val shown = pendingAt(1)
        state = state.begin(shown)
        assertEquals(listOf("att-3"), shown.imageGalleryPreloadPayloads(state).map { it.attachmentId }, "att-1 is resolved")

        state = state.begin(third, preload = true)
        assertEquals(emptyList(), shown.imageGalleryPreloadPayloads(state), "att-3 is in flight")

        // A thumbnail-only result is not cached; the preload is not retried in the background.
        state = state.completeResolution(third, third.copy(kind = "image", messageDesc = null))
        assertEquals(emptyList(), shown.imageGalleryPreloadPayloads(state), "att-3 was attempted")
    }

    @Test
    fun preloadNeverCancelsAndWaitsWhileSlotsAreBusy() {
        val state = RaftFilePreviewImageGalleryResolutionState().begin(pendingAt(0)).begin(pendingAt(1))

        val start = state.beginResolution(pendingAt(2), preload = true)

        assertNull(start.requestPayload)
        assertNull(start.cancelAttachmentId)
        assertEquals(state, start.state)
    }

    @Test
    fun shownImageTakesThePreloadSlotBeforeAnEarlierImage() {
        // att-1 shown earlier (still loading) and att-2 preloading; the user jumps to att-3.
        val state = RaftFilePreviewImageGalleryResolutionState().begin(pendingAt(0)).begin(pendingAt(1), preload = true)

        val start = state.beginResolution(pendingAt(2))

        assertEquals("att-3", start.requestPayload?.attachmentId)
        assertEquals("att-2", start.cancelAttachmentId)
        assertEquals(listOf("att-1", "att-3"), start.state.inFlightAttachmentIds)
        assertEquals(emptySet(), start.state.preloadAttachmentIds)
        assertEquals(emptySet(), start.state.preloadAttemptedAttachmentIds, "a cancelled preload may run again")
    }

    @Test
    fun swipingOntoAPreloadingImagePromotesItWithoutRestarting() {
        val shown = pendingAt(0)
        val next = pendingAt(1)
        var state = RaftFilePreviewImageGalleryResolutionState().begin(shown).begin(next, preload = true)

        val promoted = state.beginResolution(next)
        state = promoted.state
        assertNull(promoted.requestPayload, "the preload request keeps running")
        assertEquals(emptySet(), state.preloadAttachmentIds)

        // Now a shown image: a later jump cancels the oldest request, not a preload-first pick of att-2.
        val jump = state.beginResolution(pendingAt(3))
        assertEquals("att-1", jump.cancelAttachmentId)
        assertEquals(listOf("att-2", "att-4"), jump.state.inFlightAttachmentIds)
    }

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
    fun neighbourPreloadsResolveInTheBackgroundLaneAndTheTappedImageInTheForegroundLane() {
        assertEquals(DataRequestPriority.Background, raftFilePreviewImageResolutionPriority(preload = true))
        assertEquals(DataRequestPriority.Foreground, raftFilePreviewImageResolutionPriority(preload = false))
    }
}
