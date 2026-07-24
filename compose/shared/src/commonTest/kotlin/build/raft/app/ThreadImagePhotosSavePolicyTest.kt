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
            SlockFilePreviewPhotosActionState.Hidden,
            slockFilePreviewPhotosActionState(
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
            SlockFilePreviewPhotosActionState.Save,
            slockFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = null,
                status = "idle"
            )
        )
        assertEquals(
            SlockFilePreviewPhotosActionState.Saving,
            slockFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = "save_photos",
                status = "downloading"
            )
        )
        assertEquals(
            SlockFilePreviewPhotosActionState.Saving,
            slockFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = "save_photos",
                status = "paused"
            )
        )
        assertEquals(
            SlockFilePreviewPhotosActionState.Retry,
            slockFilePreviewPhotosActionState(
                supported = true,
                eligible = true,
                operation = "save_photos",
                status = "failed"
            )
        )
        assertEquals(
            SlockFilePreviewPhotosActionState.Busy,
            slockFilePreviewPhotosActionState(
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
            SlockFilePreviewExternalOperationIdentity(
                source = "thread_attachment",
                attachmentId = "image-a",
                path = "a.jpg",
                owner = owner(generation = 3L)
            )
        val visible = active.copy(attachmentId = "image-b", path = "b.jpg")

        assertEquals(true, slockFilePreviewShouldRetireExternalOperation(active, visible))
        assertEquals(false, slockFilePreviewShouldRetireExternalOperation(active, active))
    }

    @Test
    fun activePhotosOperationRetiresWhenOwnerGenerationChanges() {
        val active =
            SlockFilePreviewExternalOperationIdentity(
                source = "thread_attachment",
                attachmentId = "image-a",
                path = "a.jpg",
                owner = owner(generation = 3L)
            )
        val successorOwner = active.copy(owner = owner(generation = 4L))

        assertEquals(true, slockFilePreviewShouldRetireExternalOperation(active, successorOwner))
        assertEquals(true, slockFilePreviewShouldRetireExternalOperation(active, null))
    }
}
