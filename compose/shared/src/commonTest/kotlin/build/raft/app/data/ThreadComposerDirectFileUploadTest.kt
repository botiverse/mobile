package build.raft.app.data

import build.raft.app.ThreadAttachmentUploadAttemptIdentity
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.runPortableCoroutineTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreadComposerDirectFileUploadTest {
    @Test
    fun capabilitiesMissingFailOpensToMultipart() {
        assertEquals(
            ThreadComposerDirectUploadDecision.LegacyMultipart,
            decideThreadComposerDirectUpload(capabilities = null, sizeBytes = 100L, cancelled = false),
        )
    }

    @Test
    fun cancelDoesNotFallback() {
        assertEquals(
            ThreadComposerDirectUploadDecision.Cancelled,
            decideThreadComposerDirectUpload(
                capabilities = enabled(threshold = 10L, maxBytes = 100L),
                sizeBytes = 50L,
                cancelled = true,
            ),
        )
    }

    @Test
    fun disabledOrMissingThresholdUsesMultipart() {
        assertEquals(
            ThreadComposerDirectUploadDecision.LegacyMultipart,
            decideThreadComposerDirectUpload(
                capabilities = enabled(threshold = 10L, maxBytes = 200L, directEnabled = false),
                sizeBytes = 50L,
                cancelled = false,
            ),
        )
        assertEquals(
            ThreadComposerDirectUploadDecision.LegacyMultipart,
            decideThreadComposerDirectUpload(
                capabilities = enabled(threshold = null, maxBytes = 200L),
                sizeBytes = 50L,
                cancelled = false,
            ),
        )
    }

    @Test
    fun thresholdBoundaryUsesDirectAtAndAbove() {
        val capabilities = enabled(threshold = 90L, maxBytes = 200L)
        assertEquals(
            ThreadComposerDirectUploadDecision.LegacyMultipart,
            decideThreadComposerDirectUpload(capabilities, sizeBytes = 89L, cancelled = false),
        )
        assertEquals(
            ThreadComposerDirectUploadDecision.Direct,
            decideThreadComposerDirectUpload(capabilities, sizeBytes = 90L, cancelled = false),
        )
    }

    @Test
    fun sizeAboveMaxBytesIsTooLargeEvenIfAboveThreshold() {
        val capabilities = enabled(threshold = 10L, maxBytes = 50L)
        assertEquals(
            ThreadComposerDirectUploadDecision.TooLarge,
            decideThreadComposerDirectUpload(capabilities, sizeBytes = 51L, cancelled = false),
        )
    }

    @Test
    fun fileRefOnlyUnknownSizeDoesNotMultipartBeforePrepare() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.preparedRefSizes["ref-1"] = 90L
        val unresolved =
            PlatformFile(
                id = "local-ref",
                fileName = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = null,
                bytes = ByteArray(0),
                fileRef = "ref-1",
            )
        val beforePrepare =
            decideThreadComposerDirectUpload(
                capabilities = enabled(threshold = 90L, maxBytes = 200L),
                sizeBytes = unresolved.sizeBytes ?: 0L,
                cancelled = false,
            )
        assertEquals(ThreadComposerDirectUploadDecision.LegacyMultipart, beforePrepare)

        val resolved =
            resolveThreadComposerDirectUpload(
                file = unresolved,
                capabilities = enabled(threshold = 90L, maxBytes = 200L),
                cancelled = false,
                prepare = gateway::prepare,
            )
        val upload = assertIs<DataResult.Content<ThreadComposerResolvedUpload>>(resolved).data
        assertEquals(ThreadComposerDirectUploadDecision.Direct, upload.decision)
        assertEquals(90L, upload.file.sizeBytes)
        assertEquals("ref-1", upload.file.fileRef)
    }

    @Test
    fun fileRefOnlyAboveMaxBytesIsTooLargeAfterPrepare() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.preparedRefSizes["ref-1"] = 51L
        val unresolved =
            PlatformFile(
                id = "local-ref",
                fileName = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = null,
                bytes = ByteArray(0),
                fileRef = "ref-1",
            )
        val resolved =
            resolveThreadComposerDirectUpload(
                file = unresolved,
                capabilities = enabled(threshold = 10L, maxBytes = 50L),
                cancelled = false,
                prepare = gateway::prepare,
            )
        val upload = assertIs<DataResult.Content<ThreadComposerResolvedUpload>>(resolved).data
        assertEquals(ThreadComposerDirectUploadDecision.TooLarge, upload.decision)
        assertEquals(51L, upload.file.sizeBytes)
    }

    @Test
    fun staleOversizeHintDoesNotRejectAfterPrepare() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.preparedRefSizes["ref-1"] = 90L
        val unresolved =
            PlatformFile(
                id = "local-ref",
                fileName = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 999_999L,
                bytes = ByteArray(0),
                fileRef = "ref-1",
            )
        val resolved =
            resolveThreadComposerDirectUpload(
                file = unresolved,
                capabilities = enabled(threshold = 90L, maxBytes = 200L),
                cancelled = false,
                prepare = gateway::prepare,
            )
        val upload = assertIs<DataResult.Content<ThreadComposerResolvedUpload>>(resolved).data
        assertEquals(ThreadComposerDirectUploadDecision.Direct, upload.decision)
        assertEquals(90L, upload.file.sizeBytes)
    }

    @Test
    fun staleUndersizeHintDoesNotSkipDirectAfterPrepare() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.preparedRefSizes["ref-1"] = 90L
        val unresolved =
            PlatformFile(
                id = "local-ref",
                fileName = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 1L,
                bytes = ByteArray(0),
                fileRef = "ref-1",
            )
        val resolved =
            resolveThreadComposerDirectUpload(
                file = unresolved,
                capabilities = enabled(threshold = 90L, maxBytes = 200L),
                cancelled = false,
                prepare = gateway::prepare,
            )
        val upload = assertIs<DataResult.Content<ThreadComposerResolvedUpload>>(resolved).data
        assertEquals(ThreadComposerDirectUploadDecision.Direct, upload.decision)
        assertEquals(90L, upload.file.sizeBytes)
    }

    @Test
    fun bytesBackedStaleHintUsesActualByteLength() = runBlocking {
        val unresolved =
            PlatformFile(
                id = "local-bytes",
                fileName = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 999_999L,
                bytes = ByteArray(90) { 1 },
            )
        val resolved =
            resolveThreadComposerDirectUpload(
                file = unresolved,
                capabilities = enabled(threshold = 90L, maxBytes = 200L),
                cancelled = false,
                prepare = ::prepareDirectAttachmentUploadFile,
            )
        val upload = assertIs<DataResult.Content<ThreadComposerResolvedUpload>>(resolved).data
        assertEquals(ThreadComposerDirectUploadDecision.Direct, upload.decision)
        assertEquals(90L, upload.file.sizeBytes)
    }

    @Test
    fun fileRefOnlyBelowThresholdUsesMultipartAfterPrepare() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.preparedRefSizes["ref-1"] = 89L
        val unresolved =
            PlatformFile(
                id = "local-ref",
                fileName = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = null,
                bytes = ByteArray(0),
                fileRef = "ref-1",
            )
        val resolved =
            resolveThreadComposerDirectUpload(
                file = unresolved,
                capabilities = enabled(threshold = 90L, maxBytes = 200L),
                cancelled = false,
                prepare = gateway::prepare,
            )
        val upload = assertIs<DataResult.Content<ThreadComposerResolvedUpload>>(resolved).data
        assertEquals(ThreadComposerDirectUploadDecision.LegacyMultipart, upload.decision)
        assertEquals(89L, upload.file.sizeBytes)
    }

    @Test
    fun putTimeoutRecoversSameSessionAndDoesNotCreateAnother() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.putResults.add(
            DataResult.Error(ScreenError(code = "network_request_failed", retryable = true)),
        )
        gateway.statusResults.add(
            DataResult.Content(
                AttachmentUploadSessionView(
                    uploadId = "11111111-1111-4111-8111-111111111111",
                    state = "pending",
                    expiresAt = "2026-09-05T00:00:00Z",
                    attachment = null,
                ),
            ),
        )
        val result =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        assertIs<DataResult.Content<Attachment>>(result)
        assertEquals(1, gateway.createRequests.size)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", gateway.createRequests.single().clientRequestId)
        assertEquals(1, gateway.putCalls)
        assertEquals(1, gateway.statusCalls)
        assertEquals(1, gateway.completeCalls)
    }

    @Test
    fun putUncertainObjectNotFoundDoesNotCreateNewSession() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.putResults.add(
            DataResult.Error(ScreenError(code = "network_request_failed", retryable = true)),
        )
        gateway.statusResults.add(
            DataResult.Content(
                AttachmentUploadSessionView(
                    uploadId = "11111111-1111-4111-8111-111111111111",
                    state = "pending",
                    expiresAt = "2026-09-05T00:00:00Z",
                    attachment = null,
                ),
            ),
        )
        gateway.completeResults.add(
            DataResult.Error(
                ScreenError(
                    code = "attachment_upload_session_complete_failed",
                    retryable = true,
                    serverCode = "UPLOAD_OBJECT_NOT_FOUND",
                ),
            ),
        )
        val result =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        val error = assertIs<DataResult.Error>(result).error
        assertEquals("UPLOAD_OBJECT_PUT_UNCERTAIN", error.code)
        assertEquals(1, gateway.createRequests.size)
        assertTrue(gateway.completeCalls >= 1)
        assertEquals(0, gateway.cancelCalls)
    }

    @Test
    fun statusCompletedWithForeignUploadIdFailsClosed() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.putResults.add(
            DataResult.Error(ScreenError(code = "network_request_failed", retryable = true)),
        )
        gateway.statusResults.add(
            DataResult.Content(
                AttachmentUploadSessionView(
                    uploadId = "33333333-3333-4333-8333-333333333333",
                    state = "completed",
                    expiresAt = "2026-09-05T00:00:00Z",
                    attachment = matchingAttachment(),
                ),
            ),
        )
        val result =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_upload_session_contract_invalid", error.code)
        assertEquals(1, gateway.createRequests.size)
        assertEquals(0, gateway.completeCalls)
        assertEquals(1, gateway.cancelCalls)
    }

    @Test
    fun statusCompletedWithForeignAttachmentIdFailsClosed() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.putResults.add(
            DataResult.Error(ScreenError(code = "network_request_failed", retryable = true)),
        )
        gateway.statusResults.add(
            DataResult.Content(
                AttachmentUploadSessionView(
                    uploadId = "11111111-1111-4111-8111-111111111111",
                    state = "completed",
                    expiresAt = "2026-09-05T00:00:00Z",
                    attachment = matchingAttachment(id = "44444444-4444-4444-8444-444444444444"),
                ),
            ),
        )
        val result =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_upload_session_contract_invalid", error.code)
        assertEquals(1, gateway.createRequests.size)
        assertEquals(0, gateway.completeCalls)
        assertEquals(1, gateway.cancelCalls)
    }

    @Test
    fun completeWithForeignUploadIdFailsClosed() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.completeResults.add(
            DataResult.Content(
                AttachmentUploadSessionCompleteResult(
                    uploadId = "33333333-3333-4333-8333-333333333333",
                    state = "completed",
                    attachment = matchingAttachment(),
                ),
            ),
        )
        val result =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_upload_session_contract_invalid", error.code)
        assertEquals(1, gateway.createRequests.size)
        assertEquals(1, gateway.completeCalls)
        assertEquals(1, gateway.cancelCalls)
    }

    @Test
    fun completeWithForeignAttachmentIdFailsClosed() = runBlocking {
        val gateway = FakeThreadGateway()
        gateway.completeResults.add(
            DataResult.Content(
                AttachmentUploadSessionCompleteResult(
                    uploadId = "11111111-1111-4111-8111-111111111111",
                    state = "completed",
                    attachment = matchingAttachment(id = "44444444-4444-4444-8444-444444444444"),
                ),
            ),
        )
        val result =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        val error = assertIs<DataResult.Error>(result).error
        assertEquals("attachment_upload_session_contract_invalid", error.code)
        assertEquals(1, gateway.createRequests.size)
        assertEquals(1, gateway.completeCalls)
        assertEquals(1, gateway.cancelCalls)
    }

    @Test
    fun cancelAfterCreateDeletesSameSessionAndDoesNotCreateAnother() = runBlocking {
        val gateway = FakeThreadGateway().apply { throwPutCancellation = true }
        var cancelled = false
        try {
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, gateway.createRequests.size)
        assertEquals(1, gateway.putCalls)
        assertEquals(1, gateway.cancelCalls)
        assertEquals(0, gateway.completeCalls)
        assertEquals("11111111-1111-4111-8111-111111111111", gateway.cancelledIds.single())
    }

    @Test
    fun concurrentTwoFilesKeepDistinctStableClientRequestIds() = runPortableCoroutineTest {
        val registry = ThreadComposerDirectUploadClientRequestIds()
        val first = ThreadAttachmentUploadAttemptIdentity("local-a", "draft-1")
        val second = ThreadAttachmentUploadAttemptIdentity("local-b", "draft-1")
        val firstIds = MutableList(32) { "" }
        val secondIds = MutableList(32) { "" }
        coroutineScope {
            repeat(32) { index ->
                launch(Dispatchers.Default) {
                    firstIds[index] = registry.idFor(first)
                }
                launch(Dispatchers.Default) {
                    secondIds[index] = registry.idFor(second)
                }
            }
        }
        val firstId = firstIds.first()
        val secondId = secondIds.first()
        assertTrue(firstId.isNotBlank())
        assertTrue(secondId.isNotBlank())
        assertTrue(firstId != secondId)
        assertTrue(firstIds.all { it == firstId })
        assertTrue(secondIds.all { it == secondId })
        assertEquals(firstId, registry.idFor(first))
        assertEquals(secondId, registry.idFor(second))
    }

    @Test
    fun jobCancelAfterCreateDeletesSameSessionUnderNonCancellable() = runPortableCoroutineTest {
        val gateway = FakeThreadGateway()
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.putAccepted = accepted
        gateway.putRelease = release
        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                gateway.uploadOneThreadFile(
                    channelId = ChannelId("channel-1"),
                    file = file(size = 100),
                    clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                )
                error("canceled upload unexpectedly completed")
            } catch (_: CancellationException) {
            }
        }
        accepted.await()
        job.cancel()
        release.complete(Unit)
        job.cancelAndJoin()
        assertEquals(1, gateway.createRequests.size)
        assertEquals(1, gateway.putCalls)
        assertEquals(1, gateway.cancelCalls)
        assertEquals(0, gateway.completeCalls)
        assertEquals("11111111-1111-4111-8111-111111111111", gateway.cancelledIds.single())
    }

    @Test
    fun secondFileFailureKeepsFirstSuccessOnSeparateSession() = runBlocking {
        val gateway = FakeThreadGateway()
        val first =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100, id = "local-1"),
                clientRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            )
        gateway.putResults.add(
            DataResult.Error(
                ScreenError(
                    code = "UPLOAD_FORBIDDEN",
                    retryable = false,
                    serverCode = "UPLOAD_FORBIDDEN",
                ),
            ),
        )
        val second =
            gateway.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(size = 100, id = "local-2"),
                clientRequestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            )
        assertIs<DataResult.Content<Attachment>>(first)
        val secondError = assertIs<DataResult.Error>(second).error
        assertEquals("UPLOAD_FORBIDDEN", secondError.code)
        assertEquals(2, gateway.createRequests.size)
        assertEquals(
            listOf(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ),
            gateway.createRequests.map { it.clientRequestId },
        )
        assertEquals(1, gateway.cancelCalls)
        assertEquals(1, gateway.completeCalls)
    }

    private fun enabled(
        threshold: Long?,
        maxBytes: Long,
        directEnabled: Boolean = true,
    ) =
        AttachmentUploadCapabilities(
            maxBytes = maxBytes,
            directUploadEnabled = directEnabled,
            directUploadThresholdBytes = threshold,
            sessionExpiresInSeconds = 900,
        )

    private fun file(size: Int, id: String = "local-1") =
        PlatformFile(
            id = id,
            fileName = "a.bin",
            mimeType = "application/octet-stream",
            sizeBytes = size.toLong(),
            bytes = ByteArray(size) { 1 },
        )

    private fun matchingAttachment(id: String = "22222222-2222-4222-8222-222222222222") =
        Attachment(
            id = AttachmentId(id),
            name = "a.bin",
            mimeType = "application/octet-stream",
            sizeLabel = "",
            sizeBytes = 100,
        )

    private class FakeThreadGateway : DirectAttachmentUploadSessionGateway {
        val createRequests = mutableListOf<AttachmentUploadSessionCreateRequest>()
        val putResults = ArrayDeque<DataResult<Unit>>()
        val statusResults = ArrayDeque<DataResult<AttachmentUploadSessionView>>()
        val completeResults = ArrayDeque<DataResult<AttachmentUploadSessionCompleteResult>>()
        var putCalls = 0
        var statusCalls = 0
        var completeCalls = 0
        var cancelCalls = 0
        var throwPutCancellation = false
        var lastCreatedId = "11111111-1111-4111-8111-111111111111"
        val cancelledIds = mutableListOf<String>()
        val preparedRefSizes = mutableMapOf<String, Long>()
        var createAccepted: CompletableDeferred<Unit>? = null
        var createRelease: CompletableDeferred<Unit>? = null
        var putAccepted: CompletableDeferred<Unit>? = null
        var putRelease: CompletableDeferred<Unit>? = null
        private var lastCompleteResult: DataResult<AttachmentUploadSessionCompleteResult>? = null

        override suspend fun prepare(file: PlatformFile): DataResult<PlatformFile> {
            val ref = file.fileRef?.trim()?.takeIf { it.isNotBlank() }
            if (ref != null) {
                val size =
                    preparedRefSizes[ref]
                        ?: return DataResult.Error(
                            ScreenError(code = "attachment_size_unavailable", retryable = false),
                        )
                return DataResult.Content(file.copy(sizeBytes = size))
            }
            return prepareDirectAttachmentUploadFile(file)
        }

        override suspend fun capabilities(): DataResult<AttachmentUploadCapabilities> =
            DataResult.Content(enabledForGateway())

        override suspend fun create(
            request: AttachmentUploadSessionCreateRequest,
        ): DataResult<AttachmentUploadSessionCreateResult> {
            createRequests += request
            createAccepted?.complete(Unit)
            createRelease?.await()
            return DataResult.Content(
                AttachmentUploadSessionCreateResult(
                    uploadId = lastCreatedId,
                    attachmentId = AttachmentId("22222222-2222-4222-8222-222222222222"),
                    state = "pending",
                    expiresAt = "2026-09-05T00:00:00Z",
                    upload = AttachmentUploadSessionPut(
                        method = "PUT",
                        url = "https://r2.example/object",
                        headers = mapOf("Content-Type" to "application/octet-stream", "If-None-Match" to "*"),
                    ),
                ),
            )
        }

        override suspend fun put(
            upload: AttachmentUploadSessionPut,
            file: PlatformFile,
            onUploadProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)?,
        ): DataResult<Unit> {
            putCalls += 1
            putAccepted?.complete(Unit)
            putRelease?.await()
            if (throwPutCancellation) throw CancellationException("cancelled")
            return putResults.removeFirstOrNull() ?: DataResult.Content(Unit)
        }

        override suspend fun complete(uploadId: String): DataResult<AttachmentUploadSessionCompleteResult> {
            completeCalls += 1
            val queued = completeResults.removeFirstOrNull()
            if (queued != null) {
                lastCompleteResult = queued
                return queued
            }
            return lastCompleteResult
                ?: DataResult.Content(
                    AttachmentUploadSessionCompleteResult(
                        uploadId = uploadId,
                        state = "completed",
                        attachment = Attachment(
                            id = AttachmentId("22222222-2222-4222-8222-222222222222"),
                            name = "a.bin",
                            mimeType = "application/octet-stream",
                            sizeLabel = "",
                            sizeBytes = 100,
                        ),
                    ),
                )
        }

        override suspend fun cancel(uploadId: String): DataResult<AttachmentUploadSessionView> {
            cancelCalls += 1
            cancelledIds += uploadId
            return DataResult.Content(
                AttachmentUploadSessionView(
                    uploadId = uploadId,
                    state = "canceled",
                    expiresAt = "2026-09-05T00:00:00Z",
                    attachment = null,
                ),
            )
        }

        override suspend fun status(uploadId: String): DataResult<AttachmentUploadSessionView> {
            statusCalls += 1
            return statusResults.removeFirstOrNull()
                ?: DataResult.Content(
                    AttachmentUploadSessionView(
                        uploadId = uploadId,
                        state = "pending",
                        expiresAt = "2026-09-05T00:00:00Z",
                        attachment = null,
                    ),
                )
        }

        private fun enabledForGateway() =
            AttachmentUploadCapabilities(
                maxBytes = 200,
                directUploadEnabled = true,
                directUploadThresholdBytes = 10,
                sessionExpiresInSeconds = 900,
            )
    }
}
