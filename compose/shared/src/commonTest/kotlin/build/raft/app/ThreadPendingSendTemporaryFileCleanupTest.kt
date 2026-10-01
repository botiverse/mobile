package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadPendingSendTemporaryFileCleanupTest {
    @Test
    fun selectsEveryOwnedPickerCameraEditorAndCompressorRefButRejectsExternalFiles() {
        val attachments =
            listOf(
                attachment(
                    id = "picker",
                    source = "platform_picker",
                    fileRef = "  cache/picked-a.jpg  ",
                    mimeType = "image/jpeg"
                ),
                attachment(
                    id = "camera",
                    source = "platform_camera",
                    fileRef = "cache/camera-a.jpg",
                    mimeType = "image/jpeg"
                ),
                attachment(
                    id = "duplicate",
                    source = "platform_picker",
                    fileRef = "cache/picked-a.jpg",
                    mimeType = "image/jpeg"
                ),
                // A confirmed edit/compression replacement leaves both the editor source and
                // generated JPEG owned until the send reaches a terminal outcome.
                attachment(
                    id = "editor",
                    source = "composer_image_editor",
                    fileRef = "cache/edited.png",
                    mimeType = "image/png"
                ),
                attachment(
                    id = "compressor",
                    source = "composer_image_compressor",
                    fileRef = "cache/compressed.jpg",
                    mimeType = "image/jpeg"
                ),
                // Pending-send ownership is generic: a picker-tagged video still owns a
                // process-local handle and must be retired after a terminal send.
                attachment(
                    id = "video",
                    source = "platform_picker",
                    fileRef = "cache/clip.mp4",
                    fileName = "clip.mp4",
                    mimeType = "video/mp4"
                ),
                attachment(
                    id = "video-mime-image-name",
                    source = "platform_picker",
                    fileRef = "cache/not-an-image.jpg",
                    fileName = "not-an-image.jpg",
                    mimeType = "video/mp4"
                ),
                attachment(
                    id = "image-mime-video-name",
                    source = "platform_picker",
                    fileRef = "cache/not-a-video.mp4",
                    fileName = "not-a-video.mp4",
                    mimeType = "image/jpeg"
                ),
                attachment(
                    id = "image-mime-video-flv",
                    source = "platform_picker",
                    fileRef = "cache/not-a-video.flv",
                    fileName = "not-a-video.jpg",
                    mimeType = "image/jpeg"
                ),
                attachment(
                    id = "ordinary-file",
                    source = "platform_picker",
                    fileRef = "cache/notes.pdf",
                    fileName = "notes.pdf",
                    mimeType = "application/pdf"
                ),
                attachment(
                    id = "external",
                    source = "external_share",
                    fileRef = "cache/external.jpg",
                    mimeType = "image/jpeg"
                ),
                attachment(
                    id = "remote",
                    source = "server_attachment",
                    fileRef = "cache/remote.jpg",
                    mimeType = "image/jpeg"
                ),
                attachment(
                    id = "legacy-editor",
                    source = "image_editor",
                    fileRef = "cache/unowned-editor.jpg",
                    mimeType = "image/jpeg"
                ),
                attachment("blank", "platform_picker", "   "),
                attachment("not-sent", "platform_picker", "cache/not-sent.jpg")
            )

        assertEquals(
            listOf(
                "cache/picked-a.jpg",
                "cache/camera-a.jpg",
                "cache/edited.png",
                "cache/compressed.jpg",
                "cache/clip.mp4",
                "cache/not-an-image.jpg",
                "cache/not-a-video.mp4",
                "cache/not-a-video.flv",
                "cache/notes.pdf"
            ),
            threadPendingSendOwnedTemporaryFileRefs(
                attachments = attachments,
                attachmentIds =
                    listOf(
                        "picker",
                        "camera",
                        "duplicate",
                        "editor",
                        "compressor",
                        "video",
                        "video-mime-image-name",
                        "image-mime-video-name",
                        "image-mime-video-flv",
                        "ordinary-file",
                        "external",
                        "remote",
                        "legacy-editor",
                        "blank"
                    )
            )
        )
    }

    private fun attachment(
        id: String,
        source: String,
        fileRef: String,
        fileName: String = "$id.jpg",
        mimeType: String? = null
    ): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = id,
            fileName = fileName,
            fileRef = fileRef,
            mimeType = mimeType,
            source = source
        )
}
