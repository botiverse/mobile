package build.raft.app

import build.raft.app.data.PlatformFile
import build.raft.app.thread.ThreadPickedAttachmentComposerAction
import build.raft.app.thread.dispatch
import build.raft.app.thread.raftComposerImagePickerFilesAreStrictImages
import build.raft.app.thread.resolveThreadPickedAttachmentComposerAction
import build.raft.app.thread.shouldCompressThreadAttachmentPickerImages
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused, platform-neutral contracts for task #1066.
 *
 * Native hosts own bytes and codecs; common code owns the session policy, strict response
 * admission, and callback generation fence.  These tests deliberately exercise those narrow
 * boundaries rather than trying to mirror a host's bitmap/image framework.
 */
class ThreadImageCompressionContractTest {

    @Test
    fun originalImageChoiceSurvivesSendAndFailureRetryButClearsOnSuccessfulTerminalSend() {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    RaftThreadComposerPayload(
                        instanceId = "composer-send",
                        seedText = "hello"
                    ),
                draftKey = "send=channel-send|parent=_|mention=channel-send"
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.SetOriginalImage(true))

        // Attachment failure/retry is still one mounted session and must not silently change the
        // image policy selected by the user.
        val attachment =
            SharedThreadComposerAttachment(
                id = "picked-image",
                fileName = "photo.png",
                mimeType = "image/png",
                status = "uploading"
            )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(attachment)))
        store.dispatch(
            SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                attachmentIds = setOf(attachment.id),
                errorCode = RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(attachment.id))
        assertTrue(store.state.value.originalImage)

        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        assertTrue(store.state.value.sending)
        assertTrue(store.state.value.originalImage)

        store.dispatch(
            SharedThreadComposerDraftIntent.RestoreAfterFailure(
                seedText = "hello",
                seedAsTask = false,
                failureLabel = "Try again"
            )
        )
        assertFalse(store.state.value.sending)
        assertTrue(store.state.value.originalImage)

        store.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        store.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        assertFalse(store.state.value.originalImage)
    }

    @Test
    fun composerImageGalleryEventsFailClosedForOneMalformedMember() {
        val payload =
            RaftFilePreviewPayload(
                composerImageActionId = "gallery-action",
                composerImageGroupId = "gallery-group",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "image-1",
                            name = "one.png",
                            mimeType = "image/png",
                            composerImageFileRef = "file:///cache/one.png"
                        ),
                        RaftFilePreviewGalleryImage(
                            attachmentId = "image-2",
                            name = "two.png",
                            mimeType = "image/png",
                            // A missing ref makes this member undeclarable.
                            composerImageFileRef = ""
                        )
                    )
            )

        val events =
            raftFilePreviewComposerImageEventsForPayload(
                eventPayload = payload,
                singleEvent = { _, _, _ -> error("gallery payload must not use single branch") },
                galleryEvent = { index, image, groupId, groupTotal ->
                    image.composerImageFileRef
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            JSONObject().apply {
                                put("index", index)
                                put("groupId", groupId)
                                put("groupTotal", groupTotal)
                                put("fileRef", it)
                            }
                        }
                }
            )

        assertTrue(events.isEmpty(), "one malformed member must reject the complete gallery batch")
    }

    @Test
    fun composerImageGalleryEventsEmitTheCompleteNNBatch() {
        val payload =
            RaftFilePreviewPayload(
                composerImageActionId = "gallery-action",
                composerImageGroupId = "gallery-group",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "image-1",
                            name = "one.png",
                            mimeType = "image/png",
                            composerImageFileRef = "file:///cache/one.png"
                        ),
                        RaftFilePreviewGalleryImage(
                            attachmentId = "image-2",
                            name = "two.png",
                            mimeType = "image/png",
                            composerImageFileRef = "file:///cache/two.png"
                        )
                    )
            )

        val events =
            raftFilePreviewComposerImageEventsForPayload(
                eventPayload = payload,
                singleEvent = { _, _, _ -> error("gallery payload must not use single branch") },
                galleryEvent = { index, image, groupId, groupTotal ->
                    JSONObject().apply {
                        put("index", index)
                        put("groupId", groupId)
                        put("groupTotal", groupTotal)
                        put("fileRef", image.composerImageFileRef)
                    }
                }
            )

        assertEquals(2, events.size)
        assertEquals(listOf(0, 1), events.map { it.optInt("index") })
        assertEquals(listOf(2, 2), events.map { it.optInt("groupTotal") })
        assertEquals(listOf("gallery-group", "gallery-group"), events.map { it.optString("groupId") })
    }

    @Test
    fun compressedImageResponseRequiresOneComposerJpegAndPreservesMetadata() {
        val response =
            compressionResponse(
                code = 200,
                fileRef = "file:///cache/compressed.jpg",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                previewUri = "file:///cache/compressed.jpg",
                sizeBytes = 123L,
                source = "composer_image_compressor"
            )

        val success = assertIs<RaftFilePreviewCompressedImageResult.Success>(
            parseRaftFilePreviewCompressedImageResult(response)
        )
        assertEquals("file:///cache/compressed.jpg", success.file.fileRef)
        assertEquals("photo.jpg", success.file.fileName)
        assertEquals("image/jpeg", success.file.mimeType)
        assertEquals("file:///cache/compressed.jpg", success.file.previewUri)
        assertEquals(123L, success.file.sizeBytes)
        assertEquals("composer_image_compressor", success.file.source)
    }

    @Test
    fun compressedImageResponseFailsClosedForMissingBodyWrongSourceOrNonJpeg() {
        val cases =
            listOf(
                JSONObject().put("code", 200),
                compressionResponse(source = "platform_picker"),
                compressionResponse(mimeType = "image/png"),
                compressionResponse(mimeType = "IMAGE/JPEG; charset=binary"),
                compressionResponse(fileRef = " "),
                compressionResponse(fileName = " "),
                compressionResponse(fileCount = 2)
            )

        cases.forEachIndexed { index, response ->
            val failure = assertIs<RaftFilePreviewCompressedImageResult.Failure>(
                parseRaftFilePreviewCompressedImageResult(response)
            )
            assertEquals(
                RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE,
                failure.errorCode,
                "malformed compression response case $index must fail closed"
            )
        }
    }

    @Test
    fun imageCompressionOperationGateRejectsDuplicateAndLateCallbacks() {
        val gate = RaftFilePreviewComposerImageOperationGate { generation -> "req-$generation" }
        val first = requireNotNull(gate.begin())
        assertEquals(1L, first.generation)
        assertEquals("req-1", first.requestId)
        assertTrue(gate.isCurrent(first))
        assertNull(gate.begin("second-while-active"))

        assertTrue(gate.complete(first))
        assertFalse(gate.complete(first))
        assertFalse(gate.isCurrent(first))

        val second = requireNotNull(gate.begin("  req-explicit  "))
        assertEquals(2L, second.generation)
        assertEquals("req-explicit", second.requestId)
        assertFalse(gate.complete(first))
        assertTrue(gate.cancel(second))
        assertFalse(gate.cancel(second))
    }

    @Test
    fun mixedImageAndVideoSelectionNeverOpensImageAnnotation() {
        val files =
            listOf(
                PlatformFile("photo", "photo.jpg", mimeType = "image/jpeg"),
                PlatformFile("clip", "clip.mp4", mimeType = "video/mp4")
            )
        assertFalse(raftComposerImagePickerFilesAreStrictImages(files))
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = ThreadComposerAttachImageAction,
                fileCount = files.size,
                allFilesAreImages = raftComposerImagePickerFilesAreStrictImages(files)
            )
        )
    }

    @Test
    fun pickerCompressionIsOffForFilePicksAndImageCameraPicks() {
        assertFalse(
            shouldCompressThreadAttachmentPickerImages(
                action = "attachFile",
                originalImage = false
            )
        )
        assertFalse(
            shouldCompressThreadAttachmentPickerImages(
                action = "attachFile",
                originalImage = true
            )
        )
        assertFalse(
            shouldCompressThreadAttachmentPickerImages(
                action = ThreadComposerAttachImageAction,
                originalImage = false
            )
        )
        assertFalse(
            shouldCompressThreadAttachmentPickerImages(
                action = ThreadComposerCaptureImageAction,
                originalImage = false
            )
        )
    }

    @Test
    fun oversizedSourceImageStillRoutesToEditorBeforeAnySizeAdmission() {
        val source =
            PlatformFile(
                id = "large-source-image",
                fileName = "large.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 50_000_001L,
                fileRef = "file:///cache/large.jpg"
            )
        val sourceDecision = applyServerAttachmentUploadLimit(listOf(source), maxBytes = 50_000_000L)

        // The source is intentionally over the server limit.  Pure image picks still enter the
        // annotation editor; only its confirmed PNG/original or compressed JPEG is admitted by
        // uploadComposerAttachmentFiles later in the pipeline.
        assertTrue(sourceDecision.rejected.single() === source)
        assertEquals(
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation,
            resolveThreadPickedAttachmentComposerAction(
                action = ThreadComposerAttachImageAction,
                fileCount = 1,
                allFilesAreImages = raftComposerImagePickerFilesAreStrictImages(listOf(source))
            )
        )
    }

    private fun compressionResponse(
        code: Int = 200,
        fileRef: String? = "file:///cache/compressed.jpg",
        fileName: String = "photo.jpg",
        mimeType: String = "image/jpeg",
        previewUri: String? = "file:///cache/compressed.jpg",
        sizeBytes: Long = 123L,
        source: String = "composer_image_compressor",
        fileCount: Int = 1
    ): JSONObject {
        val files = JSONArray()
        repeat(fileCount) { index ->
            files.put(
                JSONObject().apply {
                    put("fileRef", if (index == 0) fileRef else "file:///cache/extra-$index.jpg")
                    put("fileName", if (index == 0) fileName else "extra-$index.jpg")
                    put("mimeType", if (index == 0) mimeType else "image/jpeg")
                    put("previewUri", if (index == 0) previewUri else "file:///cache/extra-$index.jpg")
                    put("sizeBytes", sizeBytes)
                    put("source", source)
                }
            )
        }
        return JSONObject().apply {
            put("code", code)
            put("bodyText", JSONObject().put("files", files).toString())
        }
    }
}
