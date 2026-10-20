package build.raft.app.data

import build.raft.app.runPortableCoroutineTest
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DirectAttachmentUploadCoordinatorTest {
    @Test
    fun explicitCreateCancellationIsUnknownAndFailsClosed() = runPortableCoroutineTest {
        val gateway = FakeGateway().apply { throwCreateCancellation = true }
        val diagnostics = mutableListOf<String>()
        var propagated = false
        try {
            coordinator(gateway, diagnostics::add).upload(ChannelId("channel-1"), listOf(file("local-1")))
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertEquals(0, gateway.cancelCalls)
        assertTrue(diagnostics.any { it.contains("phase=create_cancellation") })
    }

    @Test
    fun createCancellationReplaysSameIdempotencyKeyAndCompensates() = runPortableCoroutineTest {
        val gateway = FakeGateway().apply { throwCreateCancellationOnce = true }
        var propagated = false
        try {
            coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1")))
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertEquals(2, gateway.createRequests.size)
        assertEquals(
            gateway.createRequests[0].clientRequestId,
            gateway.createRequests[1].clientRequestId,
            "recovery must replay the exact idempotency key"
        )
        assertEquals(gateway.createRequests[0].clientRequestId, gateway.acceptedCreateRequestId)
        assertEquals(setOf(gateway.uploadId(1)), gateway.cancelledIds.toSet())
        assertEquals(0, gateway.putCalls)
        assertEquals(0, gateway.completeCalls)
    }

    @Test
    fun cancellationAfterServerAcceptWaitsForCreateThenCompensatesWithoutPutOrComplete() = runPortableCoroutineTest {
        val gateway = FakeGateway()
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.createAccepted = accepted
        gateway.createRelease = release
        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1")))
                error("canceled upload unexpectedly completed")
            } catch (_: CancellationException) {
                // expected: cancellation is rethrown after create is observed and compensated
            }
        }
        accepted.await()
        job.cancel()
        // NonCancellable create must be allowed to return so its reservation enters the ledger.
        release.complete(Unit)
        job.cancelAndJoin()

        assertEquals(setOf(gateway.uploadId(1)), gateway.cancelledIds.toSet())
        assertEquals(emptySet(), gateway.statusIds.toSet())
        assertEquals(0, gateway.putCalls)
        assertEquals(0, gateway.completeCalls)
    }

    @Test
    fun completeRetriesExactlyOnceForWhitelistedServerCode() = runBlocking {
        val gateway = FakeGateway()
        val diagnostics = mutableListOf<String>()
        gateway.completeResults += DataResult.Error(
            ScreenError(code = "server_unavailable", serverCode = "upload_object_not_found")
        )
        gateway.completeResults += DataResult.Content(
            gateway.completeResult(gateway.uploadId(1), gateway.attachmentId(1))
        )

        val result = DirectAttachmentUploadCoordinator(gateway = gateway, diagnosticsLogger = diagnostics::add)
            .upload(ChannelId("channel-1"), listOf(file("local-1")))

        assertIs<DataResult.Content<DirectAttachmentUploadBatch>>(result)
        assertEquals(2, gateway.completeCalls)
        assertTrue(gateway.cancelledIds.isEmpty())
    }

    @Test
    fun completeDoesNotRetryUnknownOrHttpOnlyFailure() = runBlocking {
        listOf(
            ScreenError(code = "server_unavailable", serverCode = "some_other_code"),
            ScreenError(code = "server_unavailable"),
        ).forEach { error ->
            val gateway = FakeGateway()
            gateway.completeResults += DataResult.Error(error)

            val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1")))

            assertIs<DataResult.Error>(result)
            assertEquals(1, gateway.completeCalls, "only explicit retry codes may retry")
        }
    }

    @Test
    fun siblingFailureCompensatesEveryCreatedSessionAndPublishesNoPartialBatch() = runBlocking {
        val gateway = FakeGateway()
        gateway.putFailureOrdinals += 2

        val result =
            coordinator(gateway).upload(
                ChannelId("channel-1"),
                listOf(file("local-1"), file("local-2")),
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals(2, gateway.createdIds.size)
        assertEquals(setOf(gateway.uploadId(1), gateway.uploadId(2)), gateway.cancelledIds.toSet())
        assertTrue(error.cleanupEvidence?.contains("safe=true") == true)
    }

    @Test
    fun cancelFailureStillProcessesSiblingsAndStatusCanProveSafeRetraction() = runBlocking {
        val gateway = FakeGateway()
        gateway.putFailureOrdinals += 2
        gateway.cancelFailureOrdinals += 2

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1"), file("local-2")))

        assertIs<DataResult.Error>(result)
        assertEquals(setOf(gateway.uploadId(1), gateway.uploadId(2)), gateway.cancelledIds.toSet())
        assertEquals(setOf(gateway.uploadId(2)), gateway.statusIds.toSet())
        assertTrue(result.error.cleanupEvidence?.contains("safe=true") == true)
    }

    @Test
    fun unknownCompensationStateFailsClosedAndRetainsEvidence() = runBlocking {
        val gateway = FakeGateway()
        gateway.putFailureOrdinals += 2
        gateway.cancelFailureOrdinals += 2
        gateway.statusFailureOrdinals += 2

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1"), file("local-2")))

        val error = assertIs<DataResult.Error>(result).error
        assertTrue(error.cleanupEvidence?.contains("safe=false") == true)
        assertTrue(error.cleanupEvidence?.contains("unknownReservation=false") == true)
        // A failure on one session must not prevent the other session from being attempted.
        assertEquals(setOf(gateway.uploadId(1), gateway.uploadId(2)), gateway.cancelledIds.toSet())
        assertEquals(setOf(gateway.uploadId(2)), gateway.statusIds.toSet())
    }

    @Test
    fun repeatedCompensationUsesStickyLedgerAndDoesNotCancelTwice() = runBlocking {
        val gateway = FakeGateway()
        gateway.putFailureOrdinals += 2
        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1"), file("local-2")))
        assertIs<DataResult.Error>(result)
        val firstCancelCount = gateway.cancelCalls

        // A failed upload does not return a batch handle, so exercise the same sticky ledger
        // contract through a successful batch whose later projection requires compensation.
        val successGateway = FakeGateway()
        val successCoordinator = coordinator(successGateway)
        val batch = assertIs<DataResult.Content<DirectAttachmentUploadBatch>>(
            successCoordinator.upload(ChannelId("channel-1"), listOf(file("local-1")))
        ).data
        assertTrue(successCoordinator.compensateBatch(batch))
        assertTrue(successCoordinator.compensateBatch(batch))
        assertEquals(1, successGateway.cancelCalls)
        assertTrue(firstCancelCount > 0)
    }

    @Test
    fun consumedCompletedReservationIsNotDeclaredSafe() = runBlocking {
        val gateway = FakeGateway()
        // Fail the second PUT while its reservation reports itself as consumed.  The
        // coordinator must not treat the completed/consumed view as a safe retract.
        gateway.putFailureOrdinals += 2
        gateway.consumedOrdinals += 2

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1"), file("local-2")))

        val error = assertIs<DataResult.Error>(result).error
        assertTrue(error.cleanupEvidence?.contains("safe=false") == true)
        assertTrue(error.cleanupEvidence?.contains("completed/consumed") == true)
    }

    @Test
    fun malformedCreateWithNoRecoverableIdMarksUnknownReservation() = runBlocking {
        val gateway = FakeGateway()
        gateway.createResults += DataResult.Error(ScreenError("network_parse_failed"))

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(file("local-1")))

        val error = assertIs<DataResult.Error>(result).error
        assertTrue(error.cleanupEvidence?.contains("unknownReservation=true") == true)
        assertTrue(error.cleanupEvidence?.contains("safe=false") == true)
        assertTrue(gateway.cancelledIds.isEmpty())
    }

    @Test
    fun fileRefOnlyIsPreparedWithResolverVerifiedSizeBeforeCreate() = runBlocking {
        val gateway = FakeGateway()
        val source =
            PlatformFile(
                id = "local-ref",
                fileName = "local-ref.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 0L,
                fileRef = "file:///private/cache/local-ref.jpg",
            )
        gateway.prepareResults +=
            DataResult.Content(
                source.copy(sizeBytes = 17L)
            )

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(source))

        assertIs<DataResult.Content<DirectAttachmentUploadBatch>>(result)
        assertEquals(listOf(source), gateway.preparedFiles)
        assertEquals(17L, gateway.createRequests.single().sizeBytes)
        assertEquals(0, gateway.createRequests.single().sizeBytes.compareTo(17L))
    }

    @Test
    fun fileRefOnlyPreflightFailureDoesNotCreateAReservation() = runBlocking {
        val gateway = FakeGateway()
        gateway.prepareResults +=
            DataResult.Error(
                ScreenError(
                    code = "attachment_size_unavailable",
                    retryable = false,
                )
            )
        val source =
            PlatformFile(
                id = "local-ref",
                fileName = "local-ref.jpg",
                mimeType = "image/jpeg",
                fileRef = "file:///private/cache/local-ref.jpg",
            )

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(source))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_size_unavailable", error.code)
        assertEquals(1, gateway.preparedFiles.size)
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun fileRefAndBytesConflictIsRejectedBeforePreflightOrCreate() = runBlocking {
        val gateway = FakeGateway()
        val conflicting =
            PlatformFile(
                id = "conflict",
                fileName = "conflict.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 3L,
                bytes = byteArrayOf(1, 2, 3),
                fileRef = "file:///private/cache/conflict.jpg",
            )

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(conflicting))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_file_input_conflict", error.code)
        assertTrue(gateway.preparedFiles.isEmpty())
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun resolverlessFileRefFallbackUsesCanonicalSizeAdmissionFailure() {
        val result =
            prepareDirectAttachmentUploadFile(
                PlatformFile(
                    id = "ref-only",
                    fileName = "photo.jpg",
                    mimeType = "image/jpeg",
                    fileRef = "file:///private/cache/photo.jpg",
                )
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_size_unavailable", error.code)
        assertTrue(!error.retryable)
    }

    @Test
    fun fallbackFileRefAndBytesConflictUsesCanonicalConflictFailure() {
        val result =
            prepareDirectAttachmentUploadFile(
                PlatformFile(
                    id = "conflict",
                    fileName = "photo.jpg",
                    mimeType = "image/jpeg",
                    bytes = byteArrayOf(1),
                    fileRef = "file:///private/cache/photo.jpg",
                )
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_file_input_conflict", error.code)
        assertTrue(!error.retryable)
    }

    @Test
    fun preparerOutputDriftFailsBeforeCreate() = runBlocking {
        val gateway = FakeGateway()
        val source = file("local-1")
        // A preparer must not change the caller's identity or body source while supplying a size.
        gateway.prepareResults +=
            DataResult.Content(
                source.copy(
                    id = "different-local-id",
                    sizeBytes = source.bytes.size.toLong(),
                )
            )

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(source))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_upload_file_preflight_invalid", error.code)
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun preparerBodyDriftFailsBeforeCreate() = runBlocking {
        val gateway = FakeGateway()
        val source = file("local-1")
        gateway.prepareResults +=
            DataResult.Content(
                source.copy(
                    bytes = byteArrayOf(9, 8, 7),
                    sizeBytes = 3L,
                )
            )

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(source))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_upload_file_preflight_invalid", error.code)
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun preparerPreviewAndSourceDriftFailsBeforeCreate() = runBlocking {
        val gateway = FakeGateway()
        val source =
            file("local-1").copy(
                previewUri = "file:///private/cache/source.jpg",
                source = "picker",
            )
        gateway.prepareResults +=
            DataResult.Content(
                source.copy(
                    previewUri = "file:///private/cache/other.jpg",
                    source = "unexpected",
                    sizeBytes = source.bytes.size.toLong(),
                )
            )

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(source))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_upload_file_preflight_invalid", error.code)
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun everyFileIsPreparedBeforeAnyReservation() = runBlocking {
        val gateway = FakeGateway()
        val first = file("local-1")
        val second = file("local-2")
        gateway.prepareResults += DataResult.Content(first.copy(sizeBytes = first.bytes.size.toLong()))
        gateway.prepareResults +=
            DataResult.Error(ScreenError(code = "attachment_size_unavailable", retryable = false))

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(first, second))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_size_unavailable", error.code)
        assertEquals(listOf(first, second), gateway.preparedFiles)
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun positiveBytesHintMismatchFailsBeforeCreate() = runBlocking {
        val gateway = FakeGateway()
        val source =
            PlatformFile(
                id = "mismatch",
                fileName = "mismatch.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 99L,
                bytes = byteArrayOf(1, 2, 3),
            )

        val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(source))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_size_mismatch", error.code)
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun bytesOnlyUsesExactByteLengthWhenCallerHintIsAbsentOrZero() = runBlocking {
        listOf(null, 0L).forEach { hint ->
            val gateway = FakeGateway()
            val bytes = byteArrayOf(1, 2, 3, 4)
            val source =
                PlatformFile(
                    id = "bytes-$hint",
                    fileName = "bytes.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = hint,
                    bytes = bytes,
                )

            val result = coordinator(gateway).upload(ChannelId("channel-1"), listOf(source))

            assertIs<DataResult.Content<DirectAttachmentUploadBatch>>(result)
            assertEquals(4L, gateway.createRequests.single().sizeBytes)
        }
    }

    private fun coordinator(
        gateway: FakeGateway,
        diagnostics: (String) -> Unit = {},
    ): DirectAttachmentUploadCoordinator =
        DirectAttachmentUploadCoordinator(
            gateway = gateway,
            diagnosticsLogger = diagnostics,
        )

    private fun file(id: String): PlatformFile =
        PlatformFile(
            id = id,
            fileName = "$id.jpg",
            mimeType = "image/jpeg",
            bytes = byteArrayOf(1, 2, 3),
        )

    private class FakeGateway : DirectAttachmentUploadSessionGateway {
        val createdIds = mutableListOf<String>()
        val createRequests = mutableListOf<AttachmentUploadSessionCreateRequest>()
        val preparedFiles = mutableListOf<PlatformFile>()
        val cancelledIds = mutableListOf<String>()
        val statusIds = mutableListOf<String>()
        var completeCalls = 0
        var putCalls = 0
        var cancelCalls = 0
        val createResults = ArrayDeque<DataResult<AttachmentUploadSessionCreateResult>>()
        val prepareResults = ArrayDeque<DataResult<PlatformFile>>()
        val completeResults = ArrayDeque<DataResult<AttachmentUploadSessionCompleteResult>>()
        var createAccepted: CompletableDeferred<Unit>? = null
        var createRelease: CompletableDeferred<Unit>? = null
        var throwCreateCancellation: Boolean = false
        var throwCreateCancellationOnce: Boolean = false
        var acceptedCreateRequestId: String? = null
        private val acceptedCreateIdsByRequest = mutableMapOf<String, String>()
        val putFailureOrdinals = mutableSetOf<Int>()
        val cancelFailureOrdinals = mutableSetOf<Int>()
        val statusFailureOrdinals = mutableSetOf<Int>()
        val consumedOrdinals = mutableSetOf<Int>()

        override suspend fun capabilities(): DataResult<AttachmentUploadCapabilities> =
            DataResult.Content(
                AttachmentUploadCapabilities(
                    maxBytes = 1024,
                    directUploadEnabled = true,
                )
            )

        override suspend fun create(
            request: AttachmentUploadSessionCreateRequest
        ): DataResult<AttachmentUploadSessionCreateResult> {
            createRequests += request
            if (throwCreateCancellation || throwCreateCancellationOnce) {
                throwCreateCancellationOnce = false
                if (request.clientRequestId !in acceptedCreateIdsByRequest) {
                    val index = createdIds.size + 1
                    val uploadId = uploadId(index)
                    createdIds += uploadId
                    acceptedCreateIdsByRequest[request.clientRequestId] = uploadId
                }
                acceptedCreateRequestId = request.clientRequestId
                throw CancellationException("create canceled after server acceptance")
            }
            acceptedCreateIdsByRequest[request.clientRequestId]?.let { uploadId ->
                return DataResult.Content(createResult(uploadId, attachmentId(ordinal(uploadId))))
            }
            val queued = createResults.removeFirstOrNull()
            if (queued != null) return queued
            val index = createdIds.size + 1
            val uploadId = uploadId(index)
            createdIds += uploadId
            createAccepted?.complete(Unit)
            createRelease?.await()
            return DataResult.Content(createResult(uploadId, attachmentId(index)))
        }

        override suspend fun prepare(file: PlatformFile): DataResult<PlatformFile> {
            preparedFiles += file
            return prepareResults.removeFirstOrNull() ?: prepareDirectAttachmentUploadFile(file)
        }

        override suspend fun put(upload: AttachmentUploadSessionPut, file: PlatformFile): DataResult<Unit> {
            putCalls += 1
            val id = upload.url.substringAfter("/upload/")
            return if (ordinal(id) in putFailureOrdinals) {
                DataResult.Error(ScreenError("put_failed"))
            } else {
                DataResult.Content(Unit)
            }
        }

        override suspend fun complete(uploadId: String): DataResult<AttachmentUploadSessionCompleteResult> {
            completeCalls += 1
            val queued = completeResults.removeFirstOrNull()
            return queued ?: DataResult.Content(completeResult(uploadId, "00000000-0000-4000-8000-000000000101"))
        }

        override suspend fun cancel(uploadId: String): DataResult<AttachmentUploadSessionView> {
            cancelCalls += 1
            cancelledIds += uploadId
            return if (ordinal(uploadId) in cancelFailureOrdinals) {
                DataResult.Error(ScreenError("cancel_transport"))
            } else if (ordinal(uploadId) in consumedOrdinals) {
                DataResult.Content(completedConsumedView(uploadId))
            } else {
                DataResult.Content(canceledView(uploadId))
            }
        }

        override suspend fun status(uploadId: String): DataResult<AttachmentUploadSessionView> {
            statusIds += uploadId
            return if (ordinal(uploadId) in statusFailureOrdinals) {
                DataResult.Error(ScreenError("status_transport"))
            } else if (ordinal(uploadId) in consumedOrdinals) {
                DataResult.Content(completedConsumedView(uploadId))
            } else {
                DataResult.Content(canceledView(uploadId))
            }
        }

        fun createResult(uploadId: String, attachmentId: String): AttachmentUploadSessionCreateResult =
            AttachmentUploadSessionCreateResult(
                uploadId = uploadId,
                attachmentId = AttachmentId(attachmentId),
                state = "pending",
                expiresAt = "2026-09-01T00:00:00Z",
                upload =
                    AttachmentUploadSessionPut(
                        method = "PUT",
                        url = "https://upload.test/upload/$uploadId",
                        headers = mapOf(
                            "Content-Type" to "image/jpeg",
                            "If-None-Match" to "*",
                        ),
                    ),
            )

        fun completeResult(uploadId: String, attachmentId: String): AttachmentUploadSessionCompleteResult =
            AttachmentUploadSessionCompleteResult(
                uploadId = uploadId,
                state = "completed",
                attachment =
                    Attachment(
                        id = AttachmentId(attachmentId),
                        name = "uploaded.jpg",
                        mimeType = "image/jpeg",
                        sizeLabel = "",
                        sizeBytes = 3,
                    ),
            )

        fun uploadId(index: Int): String =
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

        fun attachmentId(index: Int): String =
            "00000000-0000-4000-8000-${(100 + index).toString().padStart(12, '0')}"

        fun canceledView(uploadId: String): AttachmentUploadSessionView =
            AttachmentUploadSessionView(
                uploadId = uploadId,
                state = "canceled",
                expiresAt = "2026-09-01T00:00:00Z",
                reservationState = "canceled",
            )

        fun completedConsumedView(uploadId: String): AttachmentUploadSessionView =
            AttachmentUploadSessionView(
                uploadId = uploadId,
                state = "completed",
                expiresAt = "2026-09-01T00:00:00Z",
                reservationState = "consumed",
                attachment =
                    Attachment(
                        id = AttachmentId("00000000-0000-4000-8000-000000000101"),
                        name = "uploaded.jpg",
                        mimeType = "image/jpeg",
                        sizeLabel = "",
                        sizeBytes = 3,
                    ),
            )

        private fun ordinal(uploadId: String): Int =
            uploadId.substringAfterLast('-').toIntOrNull() ?: -1
    }
}
