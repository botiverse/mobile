@file:OptIn(ExperimentalCoroutinesApi::class, RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.AttachmentUploadCapabilitiesCache
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftThreadApi
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedMessagesPayload
import build.raft.app.data.SharedRaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentCommentAnchor
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.thread.ThreadAttachmentTransfers
import build.raft.app.thread.dispatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Completed composer draft attachment discard parity (`DELETE /api/attachments/:id/draft`,
 * botiverse/slock #17244):
 * - Transitions the tile to `removing` (blocking Send & preview, deduplicating repeat taps).
 * - Clears the tile, retires owned temporary refs, and refreshes upload quota on 204,
 *   404 (`attachment_not_found`), or 409 (`attachment_already_consumed`).
 * - Retains the server reservation ID in `discard_failed` (blocking Send) on transient failures so
 *   choosing Remove again retries the same ID.
 * - Leaves in-flight local upload cancellation (`orchestrator.remove`) unchanged.
 */
class ThreadComposerDiscardDraftAttachmentTest {
    @Test
    fun removingCompletedDraftAttachmentShowsRemovingBlocksSendDeduplicatesAndClearsOn204WithQuotaRefresh() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val applier = RaftComposerDraftUploadApplier()
        val store = mountComposerWithReadyAttachment(persistence, DraftA, ReadyId, fileRef = OwnedFileRef)

        val gate = CompletableDeferred<DataResult<Unit>>()
        val discardedIds = mutableListOf<String>()
        var quotaRefreshCalls = 0
        val retiredRefs = mutableListOf<String>()

        val first =
            dispatchThreadComposerAttachmentRemoval(
                attachmentId = ReadyId,
                currentState = store.state.value,
                attachmentUploads = uploads,
                dispatch = store::dispatch,
                discardCall = { id ->
                    discardedIds += id
                    gate.await()
                },
                onQuotaRefresh = { quotaRefreshCalls += 1 },
            )
        assertIs<ThreadComposerAttachmentRemovalDispatchResult.DiscardStarted>(first)
        runCurrent()

        // Tile enters removing state immediately: blocks Send & preview, and deduplicates second Remove tap.
        assertEquals(ThreadComposerAttachmentStatusRemoving, store.row(ReadyId).status)
        assertTrue(store.pending().hasRemovingThreadAttachment())
        assertTrue(store.pending().hasBlockingThreadAttachmentUpload())
        assertTrue(store.pending().readyThreadAttachmentIdsForSend().isEmpty())
        assertFalse(store.pending().single().canOpenComposerAttachmentPreview(composerEnabled = true))

        val duplicate =
            dispatchThreadComposerAttachmentRemoval(
                attachmentId = ReadyId,
                currentState = store.state.value,
                attachmentUploads = uploads,
                dispatch = store::dispatch,
                discardCall = { id ->
                    discardedIds += id
                    DataResult.Content(Unit)
                },
                onQuotaRefresh = { quotaRefreshCalls += 1 },
            )
        assertIs<ThreadComposerAttachmentRemovalDispatchResult.DiscardAlreadyInFlight>(duplicate)
        assertEquals(listOf(ReadyId), discardedIds)
        assertEquals(0, quotaRefreshCalls)

        // Confirm 204 success: quota refreshes and applier removes the tile & retires the temp file ref.
        gate.complete(DataResult.Content(Unit))
        runCurrent()
        assertEquals(1, quotaRefreshCalls)

        applier.applyDiscardsTo(
            store = store,
            uploads = uploads,
            draftKey = DraftA,
            onRetireFileRef = retiredRefs::add,
        )
        assertTrue(store.pending().isEmpty())
        assertTrue(ReadyId in store.state.value.removedAttachmentIds)
        assertEquals(listOf(OwnedFileRef), retiredRefs)
        assertNull(uploads.discard(DraftA, ReadyId))
    }

    @Test
    fun alreadyGone404AndAlreadyConsumed409ClearTileAndRefreshUploadQuotaCache() = runTest {
        val cases =
            listOf(
                404 to """{"code":"attachment_not_found","error":"Draft attachment not found."}""",
                409 to """{"code":"attachment_already_consumed","error":"This attachment has already been sent."}""",
            )
        cases.forEach { (statusCode, errorBody) ->
            val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
            val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
            val applier = RaftComposerDraftUploadApplier()
            val store = mountComposerWithReadyAttachment(persistence, DraftA, ReadyId)

            val sessionStore = authenticatedSessionStore()
            var capabilityFetches = 0
            val executor =
                RecordingExecutor { method, url ->
                    when {
                        method == "GET" && url.endsWith("/attachments/upload-capabilities") -> {
                            capabilityFetches += 1
                            RaftHttpExecutorResponse(
                                code = 200,
                                bodyText = """{"directUploadEnabled":true,"maxBytes":1048576,"sessionExpiresInSeconds":600}""",
                                errorBodyText = null,
                            )
                        }
                        method == "DELETE" && url.endsWith("/attachments/$ReadyId/draft") ->
                            RaftHttpExecutorResponse(code = statusCode, bodyText = null, errorBodyText = errorBody)
                        else -> error("unexpected request: $method $url")
                    }
                }
            val repository =
                RealConversationRepository(
                    sessionStore = sessionStore,
                    networkClient =
                        SharedRaftNetworkClient(
                            sessionStore = sessionStore,
                            baseUrl = ApiBaseUrl,
                            executor = executor,
                            requestScheduler = RaftRequestScheduler(),
                        ),
                    routeEnvironmentIdProvider = { ApiBaseUrl },
                )
            val cache = AttachmentUploadCapabilitiesCache(nowMillis = { 1_000L })
            val transfers =
                ThreadAttachmentTransfers(
                    api = UnusedThreadApi,
                    sessionStore = sessionStore,
                    conversationRepository = repository,
                    apiEnvironmentIdProvider = { ApiBaseUrl },
                    attachmentUploadCapabilitiesCache = cache,
                )
            val operation = assertNotNull(transfers.captureDirectAttachmentUploadOperation())
            assertIs<DataResult.Content<AttachmentUploadCapabilities>>(transfers.attachmentUploadCapabilities(operation))
            assertNotNull(transfers.peekAttachmentUploadCapabilities(operation))
            assertEquals(1, capabilityFetches)

            val result =
                dispatchThreadComposerAttachmentRemoval(
                    attachmentId = ReadyId,
                    currentState = store.state.value,
                    attachmentUploads = uploads,
                    dispatch = store::dispatch,
                    discardCall = { id -> transfers.discardDraftAttachment(ServerA, id) },
                    onQuotaRefresh = { transfers.refreshAttachmentUploadCapabilities() },
                )
            assertIs<ThreadComposerAttachmentRemovalDispatchResult.DiscardStarted>(result)
            runCurrent()

            applier.applyDiscardsTo(store = store, uploads = uploads, draftKey = DraftA)
            assertTrue(store.pending().isEmpty(), "status=$statusCode should clear stale tile")
            assertEquals(2, capabilityFetches, "status=$statusCode should refresh upload capabilities/quota")
        }
    }

    @Test
    fun retryableDiscardFailureRetainsReservationIdBlocksSendPersistsAndSucceedsOnRetry() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val applier = RaftComposerDraftUploadApplier()
        val store = mountComposerWithReadyAttachment(persistence, DraftA, ReadyId)

        val firstGate = CompletableDeferred<DataResult<Unit>>()
        val secondGate = CompletableDeferred<DataResult<Unit>>()
        val discardedIds = mutableListOf<String>()
        var quotaRefreshCalls = 0
        val failedToasts = mutableListOf<String>()

        // First removal attempt fails with 503.
        val first =
            dispatchThreadComposerAttachmentRemoval(
                attachmentId = ReadyId,
                currentState = store.state.value,
                attachmentUploads = uploads,
                dispatch = store::dispatch,
                discardCall = { id ->
                    discardedIds += id
                    firstGate.await()
                },
                onQuotaRefresh = { quotaRefreshCalls += 1 },
            )
        assertIs<ThreadComposerAttachmentRemovalDispatchResult.DiscardStarted>(first)
        runCurrent()

        firstGate.complete(DataResult.Error(ScreenError(code = "server_unavailable", retryable = true, traceId = "trace-503")))
        runCurrent()

        applier.applyDiscardsTo(
            store = store,
            uploads = uploads,
            draftKey = DraftA,
            onFailed = { outcome -> failedToasts += outcome.errorCode },
        )
        // Re-applying after delivery must not re-toast.
        applier.applyDiscardsTo(
            store = store,
            uploads = uploads,
            draftKey = DraftA,
            onFailed = { outcome -> failedToasts += outcome.errorCode },
        )

        val failedRow = store.row(ReadyId)
        assertEquals(ThreadComposerAttachmentStatusDiscardFailed, failedRow.status)
        assertEquals(ThreadComposerAttachmentDiscardFailedErrorCode, failedRow.errorCode)
        assertTrue(store.pending().hasDiscardFailedThreadAttachment())
        assertTrue(store.pending().hasBlockingThreadAttachmentUpload())
        assertTrue(store.pending().readyThreadAttachmentIdsForSend().isEmpty())
        assertFalse(store.pending().single().canOpenComposerAttachmentPreview(composerEnabled = true))
        assertEquals(listOf("server_unavailable"), failedToasts)
        assertEquals(0, quotaRefreshCalls)

        // Remounting preserves the failed discard row and its server reservation ID.
        val restored = mountComposer(persistence, DraftA, instanceId = "composer-restored")
        assertEquals(ThreadComposerAttachmentStatusDiscardFailed, restored.row(ReadyId).status)
        assertTrue(restored.pending().hasDiscardFailedThreadAttachment())
        assertTrue(restored.pending().hasBlockingThreadAttachmentUpload())

        // Retry Remove on the same reservation ID succeeds.
        val retry =
            dispatchThreadComposerAttachmentRemoval(
                attachmentId = ReadyId,
                currentState = restored.state.value,
                attachmentUploads = uploads,
                dispatch = restored::dispatch,
                discardCall = { id ->
                    discardedIds += id
                    secondGate.await()
                },
                onQuotaRefresh = { quotaRefreshCalls += 1 },
            )
        assertIs<ThreadComposerAttachmentRemovalDispatchResult.DiscardStarted>(retry)
        assertEquals(ThreadComposerAttachmentStatusRemoving, restored.row(ReadyId).status)
        runCurrent()

        secondGate.complete(DataResult.Content(Unit))
        runCurrent()
        applier.applyDiscardsTo(store = restored, uploads = uploads, draftKey = DraftA)

        assertTrue(restored.pending().isEmpty())
        assertEquals(listOf(ReadyId, ReadyId), discardedIds)
        assertEquals(1, quotaRefreshCalls)
    }

    @Test
    fun inFlightUploadRemovalCancelsOrchestratorImmediatelyWithoutCallingDraftDiscard() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val store = mountComposer(persistence, DraftA, instanceId = "composer-1")

        val row =
            SharedThreadComposerAttachment(
                id = PickedId,
                fileName = "photo.jpg",
                sizeBytes = 128L,
                mimeType = "image/jpeg",
                status = "uploading",
                fileRef = OwnedFileRef,
                source = "platform_picker",
            )
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(row), DraftA))
        val admission = assertNotNull(store.state.value.attachmentAdmissionsByLocalId[PickedId])
        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        assertNotNull(
            uploads.launch(
                draftKey = DraftA,
                row = row,
                file = PlatformFile(id = PickedId, fileName = "photo.jpg", mimeType = "image/jpeg", sizeBytes = 128L, fileRef = OwnedFileRef),
                launchAdmission = admission,
            ) {
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    cancelled.complete(Unit)
                    throw cancellation
                }
            }
        )
        runCurrent()
        assertTrue(started.isCompleted)
        assertTrue(uploads.orchestrator.isActive(PickedId, DraftA))

        var discardCalls = 0
        val outcome =
            dispatchThreadComposerAttachmentRemoval(
                attachmentId = PickedId,
                currentState = store.state.value,
                attachmentUploads = uploads,
                dispatch = store::dispatch,
                discardCall = {
                    discardCalls += 1
                    DataResult.Content(Unit)
                },
            )
        runCurrent()

        val removedLocally = assertIs<ThreadComposerAttachmentRemovalDispatchResult.RemovedLocally>(outcome)
        assertEquals(PickedId, removedLocally.attachmentId)
        assertEquals(OwnedFileRef, removedLocally.fileRefToRetire)
        assertEquals(0, discardCalls)
        assertFalse(uploads.orchestrator.isActive(PickedId, DraftA))
        assertTrue(cancelled.isCompleted)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun orphanedRemovingRowRecoversToDiscardFailedWhenNoLiveDiscardOwnsIt() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val firstUploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val firstPage = mountComposerWithReadyAttachment(persistence, DraftA, ReadyId)
        val gate = CompletableDeferred<DataResult<Unit>>()

        dispatchThreadComposerAttachmentRemoval(
            attachmentId = ReadyId,
            currentState = firstPage.state.value,
            attachmentUploads = firstUploads,
            dispatch = firstPage::dispatch,
            discardCall = { gate.await() },
        )
        runCurrent()
        assertEquals(ThreadComposerAttachmentStatusRemoving, firstPage.row(ReadyId).status)

        // While the process-owned discard is still alive, returning to the draft keeps it `removing`.
        val returningSameProcess = mountComposer(persistence, DraftA, instanceId = "composer-2")
        recoverOrphanedThreadComposerUploads(
            draftKey = DraftA,
            state = returningSameProcess.state.value,
            acceptWithState = returningSameProcess::acceptWithState,
            currentState = { returningSameProcess.state.value },
            orchestrator = firstUploads.orchestrator,
            log = {},
            ownedByDraftUpload = { id -> firstUploads.ownsUpload(DraftA, id) },
            ownedByDraftDiscard = { id -> firstUploads.ownsDiscard(DraftA, id) },
        )
        assertEquals(ThreadComposerAttachmentStatusRemoving, returningSameProcess.row(ReadyId).status)

        // After process restart (fresh owner with no live discard), recovery turns it into `discard_failed`.
        val freshUploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val afterRestart = mountComposer(persistence, DraftA, instanceId = "composer-3")
        recoverOrphanedThreadComposerUploads(
            draftKey = DraftA,
            state = afterRestart.state.value,
            acceptWithState = afterRestart::acceptWithState,
            currentState = { afterRestart.state.value },
            orchestrator = freshUploads.orchestrator,
            log = {},
            ownedByDraftUpload = { id -> freshUploads.ownsUpload(DraftA, id) },
            ownedByDraftDiscard = { id -> freshUploads.ownsDiscard(DraftA, id) },
        )
        assertEquals(ThreadComposerAttachmentStatusDiscardFailed, afterRestart.row(ReadyId).status)
        assertEquals(ThreadComposerAttachmentDiscardFailedErrorCode, afterRestart.row(ReadyId).errorCode)
        assertTrue(afterRestart.pending().hasDiscardFailedThreadAttachment())
        assertTrue(afterRestart.pending().hasBlockingThreadAttachmentUpload())

        gate.cancel()
    }

    private fun mountComposer(
        persistence: SharedThreadComposerDraftPersistence,
        draftKey: String,
        instanceId: String,
    ): SharedThreadComposerDraftStore =
        SharedThreadComposerDraftStore(
            persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence))
        ).also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = instanceId),
                    draftKey = draftKey,
                    serverId = ServerA,
                )
            )
        }

    private fun mountComposerWithReadyAttachment(
        persistence: SharedThreadComposerDraftPersistence,
        draftKey: String,
        attachmentId: String,
        fileRef: String? = null,
    ): SharedThreadComposerDraftStore =
        mountComposer(persistence, draftKey, instanceId = "composer-1").also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.AddUploadedAttachments(
                    attachments =
                        listOf(
                            SharedThreadComposerAttachment(
                                id = attachmentId,
                                fileName = "draft.jpg",
                                sizeBytes = 42L,
                                mimeType = "image/jpeg",
                                status = "ready",
                                fileRef = fileRef,
                                source = if (fileRef != null) "platform_picker" else null,
                            )
                        ),
                )
            )
        }

    private fun RaftComposerDraftUploadApplier.applyDiscardsTo(
        store: SharedThreadComposerDraftStore,
        uploads: RaftComposerServerUploads,
        draftKey: String,
        onRetireFileRef: (String) -> Unit = {},
        onFailed: (RaftComposerDraftDiscardOutcome.Failed) -> Unit = {},
    ) {
        applyDiscards(
            draftKey = draftKey,
            discards = uploads.discards(draftKey),
            currentState = { store.state.value },
            acceptWithState = store::acceptWithState,
            onSucceeded = { discard ->
                uploads.remove(draftKey, discard.attachmentId)
                discard.fileRefToRetire?.let(onRetireFileRef)
                uploads.clearDiscard(draftKey, discard.attachmentId, discard.attemptToken)
            },
            onFailed = { discard, outcome ->
                onFailed(outcome)
                uploads.markDiscardDelivered(draftKey, discard.attachmentId, discard.attemptToken)
            },
        )
    }

    private fun SharedThreadComposerDraftStore.row(id: String): SharedThreadComposerAttachment =
        state.value.uploadedAttachments.single { it.id == id && it.id !in state.value.removedAttachmentIds }

    private fun SharedThreadComposerDraftStore.pending(): List<RaftPendingAttachmentItem> =
        state.value.uploadedAttachments
            .filterNot { attachment -> attachment.id in state.value.removedAttachmentIds }
            .map { attachment ->
                RaftPendingAttachmentItem(
                    id = attachment.id,
                    fileName = attachment.fileName,
                    status = attachment.status,
                    errorCode = attachment.errorCode,
                )
            }

    private fun authenticatedSessionStore(): InMemoryRaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = AccountA, email = "user@raft.build"),
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = ServerA,
                servers = listOf(StoredAuthServer(id = ServerA, name = "Raft")),
            )
        )

    private class RecordingExecutor(
        private val respond: suspend (method: String, url: String) -> RaftHttpExecutorResponse,
    ) : RaftHttpExecutor {
        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean,
        ): RaftHttpExecutorResponse = respond(method, url)

        override suspend fun executeWithTotalTimeout(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean,
            totalTimeoutMillis: Long,
        ): RaftHttpExecutorResponse = respond(method, url)

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse = error("unused")
    }

    private companion object {
        const val ApiBaseUrl = "https://api.raft.test/api"
        const val AccountA = "account-a"
        const val ServerA = "server-a"
        const val DraftA = "send=channel-a|parent=_|mention=channel-a"
        const val PickedId = "picked-1"
        const val ReadyId = "00000000-0000-4000-8000-000000000101"
        const val OwnedFileRef = "file:///cache/draft.jpg"

        val UnusedThreadApi =
            object : RaftThreadApi {
                private fun <T> unused(): ThreadApiResult<T> = ThreadApiResult.Failure(500)

                override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?, afterSeq: Long?): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun sendMessage(channelId: String, body: String, asTask: Boolean, attachmentIds: List<String>, randomId: String?): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): ThreadApiResult<List<PendingMentionActionExecuteResult>> = unused()
                override suspend fun attachmentUrl(priority: DataRequestPriority, attachmentId: String, disposition: String?): ThreadApiResult<ThreadAttachmentOpenTarget> = unused()
                override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> = unused()
                override suspend fun attachmentComments(attachmentId: String): ThreadApiResult<ThreadAttachmentCommentsPage> = unused()
                override suspend fun sendAttachmentComment(attachmentId: String, body: String, anchor: ThreadAttachmentCommentAnchor?): ThreadApiResult<Unit> = unused()
                override suspend fun markChannelReadToSeq(channelId: String, seq: Long): ThreadApiResult<Unit> = unused()
                override suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit> = unused()
                override suspend fun saveMessage(messageId: String): ThreadApiResult<Unit> = unused()
                override suspend fun unsaveMessage(messageId: String): ThreadApiResult<Unit> = unused()
                override suspend fun addReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> = unused()
                override suspend fun removeReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> = unused()
                override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult> = unused()
                override suspend fun messageContext(messageId: String, channelId: String?): ThreadApiResult<SharedMessagesPayload> = unused()
            }
    }
}
