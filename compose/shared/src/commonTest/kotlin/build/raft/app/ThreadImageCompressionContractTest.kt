package build.raft.app

import build.raft.app.data.PlatformFile
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import kotlinx.serialization.ExperimentalSerializationApi
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
    fun originalImageChoiceResetsWhenSameInstanceMovesToAnotherDraftKey() {
        val store = SharedThreadComposerDraftStore()
        val composer = KuiklyThreadComposerPayload(instanceId = "composer-1")

        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = composer,
                draftKey = "send=channel-a|parent=_|mention=channel-a"
            )
        )
        store.dispatch(SharedThreadComposerDraftIntent.SetOriginalImage(true))
        assertTrue(store.state.value.originalImage)

        // A reused host instance is not the same product session when its draft identity changes.
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = composer.copy(seedText = "other draft"),
                draftKey = "send=channel-b|parent=_|mention=channel-b"
            )
        )

        assertFalse(store.state.value.originalImage)
        assertEquals("send=channel-b|parent=_|mention=channel-b", store.state.value.draftKey)
    }

    @Test
    fun originalImageChoiceSurvivesSendAndFailureRetryButClearsOnSuccessfulTerminalSend() {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer =
                    KuiklyThreadComposerPayload(
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
                errorCode = SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE
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
    fun composerUiStateCarriesSessionOnlyImagePolicy() {
        val uiState =
            KuiklyThreadComposerPayload(
                instanceId = "composer-ui",
                originalImageOptionEnabled = true,
                originalImage = true
            ).toThreadComposerUiState()

        assertTrue(uiState.originalImageOptionEnabled)
        assertTrue(uiState.originalImage)
    }

    @Test
    fun originalImageConfirmScreenTogglesCompressionWithoutComposerChrome() {
        val confirmPayload =
            SlockFilePreviewPayload(
                composerImageActionId = "composer-image-confirm",
                composerImageFileRef = "file:///picked/photo.png",
                composerImageCompressAfterEdit = true,
                composerImageOriginalOptionEnabled = true,
                composerImageCanComplete = true,
                composerImageCanEdit = true,
            )

        assertTrue(confirmPayload.showsComposerOriginalImageOption())
        assertFalse(confirmPayload.composerOriginalImageSelected())

        val originalSelected = confirmPayload.withComposerOriginalImage(true)
        assertTrue(originalSelected.composerOriginalImageSelected())
        assertFalse(originalSelected.composerImageCompressAfterEdit)

        val compressedAgain = originalSelected.withComposerOriginalImage(false)
        assertFalse(compressedAgain.composerOriginalImageSelected())
        assertTrue(compressedAgain.composerImageCompressAfterEdit)

        val feedbackPayload =
            SlockFilePreviewPayload(
                composerImageActionId = "feedback-image-group-a",
                composerImageFileRef = "file:///picked/photo.png",
                composerImageCompressAfterEdit = true,
                composerImageCanComplete = true,
                composerImageCanEdit = true,
            )
        assertFalse(feedbackPayload.showsComposerOriginalImageOption())
        assertFalse(feedbackPayload.composerOriginalImageSelected())
    }

    @Test
    fun composerImageEventMetadataUsesClosedSourceAndCompressionVocabulary() {
        assertEquals(
            SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE,
            slockFilePreviewComposerImageEventSource(compressed = true)
        )
        assertEquals(
            SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE,
            slockFilePreviewComposerImageEventSource(compressed = false)
        )

        assertTrue(
            slockFilePreviewComposerImageEventMetadataIsValid(
                source = SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE,
                compressed = true,
                mimeType = "image/jpeg"
            )
        )
        assertTrue(
            slockFilePreviewComposerImageEventMetadataIsValid(
                source = SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE,
                compressed = false,
                mimeType = "image/png"
            )
        )
    }

    @Test
    fun composerImageEventMetadataRejectsMissingUnknownOrMismatchedFinalization() {
        val invalidCases =
            listOf(
                Triple(SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE, false, "image/jpeg"),
                Triple(SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE, true, "image/png"),
                Triple(SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE, true, "image/png"),
                Triple("platform_picker", false, "image/png"),
                Triple("", false, "image/png"),
                Triple(SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE, true, "image/jpeg; charset=binary"),
                Triple(SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE, true, "IMAGE/JPEG"),
                Triple(SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE, false, "video/mp4"),
                Triple(SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE, false, "")
            )

        invalidCases.forEachIndexed { index, (source, compressed, mimeType) ->
            assertFalse(
                slockFilePreviewComposerImageEventMetadataIsValid(
                    source = source,
                    compressed = compressed,
                    mimeType = mimeType
                ),
                "invalid composer image metadata case $index must fail closed"
            )
        }
    }

    @Test
    fun composerImageGalleryEventsFailClosedForOneMalformedMember() {
        val payload =
            SlockFilePreviewPayload(
                composerImageActionId = "gallery-action",
                composerImageGroupId = "gallery-group",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(
                            attachmentId = "image-1",
                            name = "one.png",
                            mimeType = "image/png",
                            composerImageFileRef = "file:///cache/one.png"
                        ),
                        SlockFilePreviewGalleryImage(
                            attachmentId = "image-2",
                            name = "two.png",
                            mimeType = "image/png",
                            // A missing ref makes this member undeclarable.
                            composerImageFileRef = ""
                        )
                    )
            )

        val events =
            slockFilePreviewComposerImageEventsForPayload(
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
            SlockFilePreviewPayload(
                composerImageActionId = "gallery-action",
                composerImageGroupId = "gallery-group",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(
                            attachmentId = "image-1",
                            name = "one.png",
                            mimeType = "image/png",
                            composerImageFileRef = "file:///cache/one.png"
                        ),
                        SlockFilePreviewGalleryImage(
                            attachmentId = "image-2",
                            name = "two.png",
                            mimeType = "image/png",
                            composerImageFileRef = "file:///cache/two.png"
                        )
                    )
            )

        val events =
            slockFilePreviewComposerImageEventsForPayload(
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
    fun composerImageEventsPreserveLegacyNoGroupAndGroupedSingleN1Semantics() {
        val legacyPayload =
            SlockFilePreviewPayload(
                composerImageActionId = "legacy-action",
                composerImageFileRef = "file:///cache/legacy.png"
            )
        var legacyGroupTotal: Int? = null
        var legacyGroupId: String? = null
        val legacyEvents =
            slockFilePreviewComposerImageEventsForPayload(
                eventPayload = legacyPayload,
                singleEvent = { groupId, _, groupTotal ->
                    legacyGroupId = groupId
                    legacyGroupTotal = groupTotal
                    JSONObject().put("fileRef", legacyPayload.composerImageFileRef)
                },
                galleryEvent = { _, _, _, _ -> error("legacy payload must use single branch") }
            )

        assertEquals(1, legacyEvents.size)
        assertEquals("", legacyGroupId)
        assertEquals(0, legacyGroupTotal, "blank groupId keeps legacy no-group total semantics")

        val groupedSinglePayload =
            legacyPayload.copy(composerImageGroupId = "single-group")
        var groupedSingleTotal: Int? = null
        val groupedSingleEvents =
            slockFilePreviewComposerImageEventsForPayload(
                eventPayload = groupedSinglePayload,
                singleEvent = { _, groupIndex, groupTotal ->
                    assertEquals(0, groupIndex)
                    groupedSingleTotal = groupTotal
                    JSONObject().put("fileRef", groupedSinglePayload.composerImageFileRef)
                },
                galleryEvent = { _, _, _, _ -> error("single payload must use single branch") }
            )

        assertEquals(1, groupedSingleEvents.size)
        assertEquals(1, groupedSingleTotal, "grouped single image must emit explicit N=1")
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun composerLaunchWireAndThreadLaunchWireExcludeImagePolicyContent() {
        val composerNames =
            (0 until WireThreadComposerLaunchPayload.serializer().descriptor.elementsCount)
                .map { index -> WireThreadComposerLaunchPayload.serializer().descriptor.getElementName(index) }
                .toSet()
        assertFalse("originalImage" in composerNames)
        assertFalse("originalImageOptionEnabled" in composerNames)
        assertFalse("pendingAttachments" in composerNames)

        val threadNames =
            (0 until WireThreadLaunchPayload.serializer().descriptor.elementsCount)
                .map { index -> WireThreadLaunchPayload.serializer().descriptor.getElementName(index) }
                .toSet()
        assertFalse("originalImage" in threadNames)
        assertFalse("pendingAttachments" in threadNames)
    }

    @Test
    fun composerLaunchParserRejectsImagePolicyAsContentInsteadOfPersistingItInRoute() {
        val parsed =
            parseThreadComposerPayload(
                payload = """{"originalImage":true,"seedText":"stale"}""",
                fallbackPageData = "{}",
                instanceId = "composer-route"
            )

        assertEquals("composer-route", parsed.instanceId)
        assertEquals("failed", parsed.composerMode)
        assertFalse(parsed.originalImage)
        assertEquals("", parsed.seedText)
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

        val success = assertIs<SlockFilePreviewCompressedImageResult.Success>(
            parseSlockFilePreviewCompressedImageResult(response)
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
            val failure = assertIs<SlockFilePreviewCompressedImageResult.Failure>(
                parseSlockFilePreviewCompressedImageResult(response)
            )
            assertEquals(
                SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE,
                failure.errorCode,
                "malformed compression response case $index must fail closed"
            )
        }
    }

    @Test
    fun compressionFailureCodeIsPreservedAndAnyReturnedRefIsMarkedForRetirement() {
        val response =
            compressionResponse(
                code = 409,
                fileRef = "file:///cache/should-not-be-adopted.jpg",
                source = "composer_image_compressor"
            ).put("errorCode", SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE)

        val failure = assertIs<SlockFilePreviewCompressedImageResult.Failure>(
            parseSlockFilePreviewCompressedImageResult(response)
        )
        assertEquals(SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE, failure.errorCode)
        assertEquals(
            setOf("file:///cache/should-not-be-adopted.jpg"),
            slockFilePreviewCompressionResponseFileRefs(response)
        )
    }

    @Test
    fun imageCompressionOperationGateRejectsDuplicateAndLateCallbacks() {
        val gate = SlockFilePreviewComposerImageOperationGate { generation -> "req-$generation" }
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
    fun imageCompressionOperationGateRetirementInvalidatesOutstandingAndFutureRequests() {
        val gate = SlockFilePreviewComposerImageOperationGate()
        val token = requireNotNull(gate.begin("request-1"))
        gate.retire()

        assertFalse(gate.isCurrent(token))
        assertFalse(gate.complete(token))
        assertFalse(gate.cancel(token))
        assertNull(gate.activeToken())
        assertNull(gate.begin("request-after-retire"))
    }

    @Test
    fun strictPickerClassifierHandlesMimeParametersCaseAndImageVideoBoundaryPairs() {
        val accepted =
            listOf(
                "photo.jpg" to "image/jpeg",
                "PHOTO.JPEG" to "IMAGE/JPEG; charset=utf-8",
                "screen.PNG?token=1" to "image/png",
                "vector.svg" to "image/svg+xml"
            )
        accepted.forEach { (fileName, mimeType) ->
            assertTrue(
                slockComposerImagePickerIsStrictImage(fileName, mimeType),
                "$fileName / $mimeType should be a strict image"
            )
        }

        val rejected =
            listOf(
                "photo.jpg" to "video/mp4", // image extension + video MIME
                "clip.mp4" to "image/jpeg", // video extension + image MIME
                "clip.flv" to "image/jpeg", // every shared video suffix remains excluded
                "clip.MOV" to "video/quicktime",
                "photo" to "image/jpeg", // MIME alone is not enough for picker output
                "photo.jpg" to "application/octet-stream",
                "photo.mp4" to "image/png"
            )
        rejected.forEach { (fileName, mimeType) ->
            assertFalse(
                slockComposerImagePickerIsStrictImage(fileName, mimeType),
                "$fileName / $mimeType must not enter image compression"
            )
        }
    }

    @Test
    fun mixedImageAndVideoSelectionNeverOpensImageAnnotation() {
        val files =
            listOf(
                PlatformFile("photo", "photo.jpg", mimeType = "image/jpeg"),
                PlatformFile("clip", "clip.mp4", mimeType = "video/mp4")
            )
        assertFalse(slockComposerImagePickerFilesAreStrictImages(files))
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = ThreadComposerAttachImageAction,
                fileCount = files.size,
                allFilesAreImages = slockComposerImagePickerFilesAreStrictImages(files)
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
                allFilesAreImages = slockComposerImagePickerFilesAreStrictImages(listOf(source))
            )
        )
    }

    @Test
    fun persistedComposerAttachmentKeepsFileHandleSeparateFromPreviewUri() {
        val attachment =
            SharedThreadComposerAttachment(
                id = "picked-image",
                fileName = "photo.png",
                mimeType = "image/png",
                previewUri = "file:///cache/thumbnail.png",
                fileRef = "content://picker/photo"
            )

        val file = attachment.toComposerImagePlatformFile()

        assertEquals("content://picker/photo", file.fileRef)
        assertEquals("file:///cache/thumbnail.png", file.previewUri)
        assertEquals("image/png", file.mimeType)
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
