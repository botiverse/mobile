package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadImagePhotosSavePolicyTest {
    private fun owner(generation: Long) =
        AttachmentOwnerLease(
            shellInstanceId = "shell",
            scopeId = "scope",
            authEpoch = 1L,
            principalKey = AttachmentPrincipalKey("a".repeat(64)),
            generation = generation
        )

    @Test
    fun actionIsHiddenWhenPlatformDoesNotOwnPhotosCapability() {
        assertEquals(
            RaftFilePreviewPhotosActionState.Hidden,
            raftFilePreviewPhotosActionState(
                supported = false,
                eligible = true,
                operation = null,
                status = "idle"
            )
        )
    }

    @Test
    fun actionTransitionsFromSaveToSavingToRetry() {
        assertEquals(
            RaftFilePreviewPhotosActionState.Save,
            raftFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = null,
                status = "idle"
            )
        )
        assertEquals(
            RaftFilePreviewPhotosActionState.Saving,
            raftFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = "save_photos",
                status = "downloading"
            )
        )
        assertEquals(
            RaftFilePreviewPhotosActionState.Saving,
            raftFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = "save_photos",
                status = "paused"
            )
        )
        assertEquals(
            RaftFilePreviewPhotosActionState.Retry,
            raftFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = "save_photos",
                status = "failed"
            )
        )
        assertEquals(
            RaftFilePreviewPhotosActionState.Busy,
            raftFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = "open",
                status = "downloading"
            )
        )
    }

    @Test
    fun activePhotosOperationRetiresWhenGalleryAttachmentChanges() {
        val active =
            RaftFilePreviewExternalOperationIdentity(
                source = "thread_attachment",
                attachmentId = "image-a",
                path = "a.jpg",
                owner = owner(generation = 3L)
            )
        val visible = active.copy(attachmentId = "image-b", path = "b.jpg")

        assertEquals(true, raftFilePreviewShouldRetireExternalOperation(active, visible))
        assertEquals(false, raftFilePreviewShouldRetireExternalOperation(active, active))
    }

    @Test
    fun activePhotosOperationRetiresWhenOwnerGenerationChanges() {
        val active =
            RaftFilePreviewExternalOperationIdentity(
                source = "thread_attachment",
                attachmentId = "image-a",
                path = "a.jpg",
                owner = owner(generation = 3L)
            )
        val successorOwner = active.copy(owner = owner(generation = 4L))

        assertEquals(true, raftFilePreviewShouldRetireExternalOperation(active, successorOwner))
        assertEquals(true, raftFilePreviewShouldRetireExternalOperation(active, null))
    }
}
