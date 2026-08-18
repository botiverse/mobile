package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadPendingSendTemporaryFileCleanupTest {
    @Test
    fun selectsOwnedPickerAndCameraRefsForAttachmentsIncludedInSend() {
        val attachments =
            listOf(
                attachment("picker", "platform_picker", "  cache/picked-a.jpg  "),
                attachment("camera", "platform_camera", "cache/camera-a.jpg"),
                attachment("duplicate", "platform_picker", "cache/picked-a.jpg"),
                attachment("external", "external_share", "cache/external.jpg"),
                attachment("editor", "image_editor", "cache/edited.jpg"),
                attachment("blank", "platform_picker", "   "),
                attachment("not-sent", "platform_picker", "cache/not-sent.jpg")
            )

        assertEquals(
            listOf("cache/picked-a.jpg", "cache/camera-a.jpg"),
            threadPendingSendOwnedTemporaryFileRefs(
                attachments = attachments,
                attachmentIds = listOf("picker", "camera", "duplicate", "external", "editor", "blank")
            )
        )
    }

    private fun attachment(id: String, source: String, fileRef: String): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = id,
            fileName = "$id.jpg",
            fileRef = fileRef,
            source = source
        )
}
