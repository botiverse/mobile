package build.raft.app.data

import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExternalShareStoreTest {
    @Test
    fun multiSelectContinuesToConfirmationAndEnforcesLimit() = runBlocking {
        val targets = listOf(target("one"), target("two"), target("three"))
        val store = store(scope = this, targets = targets, maximumTargetCount = 2)

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        store.toggle(targets[0])
        store.toggle(targets[1])
        store.toggle(targets[2])

        val selecting = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals(listOf("one", "two"), selecting.selectedTargets.map { it.id })
        assertEquals("external_share_target_limit", selecting.selectionErrorCode)

        store.dispatch(ExternalShareIntent.Continue)
        val confirming = assertIs<ExternalShareState.Confirming>(store.state.value)
        assertEquals(listOf("one", "two"), confirming.selectedTargets.map { it.id })
        store.clear()
    }

    @Test
    fun partialFailureRetryNeverResendsSuccessfulTarget() = runBlocking {
        val targets = listOf(target("success"), target("retry"))
        val calls = mutableListOf<String>()
        var retryCalls = 0
        val sender =
            object : ExternalShareSender {
                override suspend fun send(request: ExternalShareSendRequest): DataResult<Unit> {
                    calls += request.target.id
                    return if (request.target.id == "retry" && retryCalls++ == 0) {
                        DataResult.Error(ScreenError("network_error"))
                    } else {
                        DataResult.Content(Unit)
                    }
                }
            }
        val store = store(scope = this, targets = targets, sender = sender)

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        targets.forEach(store::toggle)
        store.dispatch(ExternalShareIntent.Continue)
        store.dispatch(ExternalShareIntent.ChangeComment("Take a look"))
        store.dispatch(ExternalShareIntent.Send)
        yield()

        val first = assertIs<ExternalShareState.Completed>(store.state.value)
        assertEquals(listOf("success"), first.sentTargets.map { it.id })
        assertEquals(listOf("retry"), first.failedTargets.map { it.id })
        assertEquals(listOf("success", "retry"), calls)

        store.dispatch(ExternalShareIntent.RetryFailed)
        yield()
        val second = assertIs<ExternalShareState.Completed>(store.state.value)
        assertEquals(listOf("success", "retry"), second.sentTargets.map { it.id })
        assertTrue(second.failedTargets.isEmpty())
        assertEquals(listOf("success", "retry", "retry"), calls)
        store.clear()
    }

    @Test
    fun canceledOlderSearchCannotOverwriteNewerResults() = runBlocking {
        val oldResult = CompletableDeferred<DataResult<List<ForwardMessageTarget>>>()
        val newResult = CompletableDeferred<DataResult<List<ForwardMessageTarget>>>()
        val repository =
            object : StubTargetRepository(emptyList()) {
                override suspend fun searchTargets(
                    serverId: ServerId,
                    query: String,
                    limit: Int
                ): DataResult<List<ForwardMessageTarget>> =
                    when (query) {
                        "old" -> oldResult.await()
                        else -> newResult.await()
                    }
            }
        val store = store(scope = this, targetRepository = repository)

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        store.dispatch(ExternalShareIntent.Search("old"))
        yield()
        store.dispatch(ExternalShareIntent.Search("new"))
        yield()
        newResult.complete(DataResult.Content(listOf(target("new"))))
        yield()
        oldResult.complete(DataResult.Content(listOf(target("old"))))
        yield()

        val state = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals("new", state.query)
        assertEquals(listOf("new"), state.targets.map { it.id })
        store.clear()
    }

    @Test
    fun blankDirectoryUsesCanonicalCursorAndAppendsWithoutDuplication() = runBlocking {
        val first = target("one")
        val second = target("two")
        val repository =
            object : StubTargetRepository(emptyList()) {
                override suspend fun directoryTargets(
                    serverId: ServerId,
                    sessionId: String,
                    cursor: ForwardMessageTargetDirectoryCursor,
                    pageSize: Int
                ): DataResult<ForwardMessageTargetDirectoryPage> =
                    if (cursor.snapshotId == null) {
                        DataResult.Content(
                            ForwardMessageTargetDirectoryPage(
                                listOf(first),
                                nextCursor = this@ExternalShareStoreTest.cursorFor(sessionId)
                            )
                        )
                    } else {
                        DataResult.Content(ForwardMessageTargetDirectoryPage(listOf(first, second), nextCursor = null))
                    }
            }
        val store = store(scope = this, targetRepository = repository)

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        val firstPage = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals(listOf("one"), firstPage.targets.map { it.id })
        assertTrue(firstPage.nextCursor != null)

        store.dispatch(ExternalShareIntent.LoadMore)
        yield()
        val secondPage = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals(listOf("one", "two"), secondPage.targets.map { it.id })
        assertEquals(null, secondPage.nextCursor)
        store.clear()
    }

    @Test
    fun queuedOldRenderIntentCannotSelectAfterNewQueryAdmission() = runBlocking {
        val old = target("old")
        val repository = StubTargetRepository(listOf(old))
        val store = store(scope = this, targetRepository = repository)
        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        val rendered = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        val queued =
            ExternalShareIntent.ToggleTarget(
                target = old,
                sessionId = rendered.externalShareDirectorySessionId(),
                renderGeneration = rendered.requestGeneration
            )

        store.dispatch(ExternalShareIntent.Search("new"))
        yield()
        store.dispatch(queued)

        val current = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertTrue(current.selectedTargets.isEmpty())
        assertTrue(current.requestGeneration > rendered.requestGeneration)
        store.clear()
    }

    @Test
    fun unjoinedChannelFailsSelectionClosedUntilExplicitJoinUiExists() = runBlocking {
        val unjoined =
            target("unjoined").copy(
                requiredAction = ForwardMessageTargetRequiredAction.JoinChannel,
                joined = false
            )
        val store = store(scope = this, targets = listOf(unjoined))
        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()

        store.toggle(unjoined)

        val current = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertTrue(current.selectedTargets.isEmpty())
        assertEquals("external_share_join_confirmation_required", current.selectionErrorCode)
        store.clear()
    }

    @Test
    fun realSenderResolvesUploadsAndBindsStableDeliveryIdToNormalMessageRandomId() = runBlocking {
        val destination = target("destination")
        var uploaded: AttachmentUploadRequest? = null
        var sentChannel: ChannelId? = null
        var sentBody: String? = null
        var sentAttachments: List<AttachmentId>? = null
        var sentRandomId: String? = null
        val sender =
            RealExternalShareSender(
                targetRepository = StubTargetRepository(listOf(destination)),
                uploadAttachments = { request, _ ->
                    uploaded = request
                    DataResult.Content(
                        AttachmentUploadResult(
                            listOf(
                                Attachment(
                                    id = AttachmentId("attachment-1"),
                                    name = "photo.png",
                                    mimeType = "image/png",
                                    sizeLabel = "4 B",
                                    sizeBytes = 4L
                                )
                            )
                        )
                    )
                },
                sendMessage = { channelId, body, attachmentIds, randomId ->
                    sentChannel = channelId
                    sentBody = body
                    sentAttachments = attachmentIds
                    sentRandomId = randomId
                    DataResult.Error(ScreenError("send_probe", retryable = true))
                },
                currentAuthority = {
                    ExternalShareSendAuthority("account", "access", "refresh", Server.value)
                }
            )

        val result =
            sender.send(
                ExternalShareSendRequest(
                    attemptId = 1,
                    deliveryId = "delivery-stable",
                    sourceServerId = Server.value,
                    envelope =
                        ExternalShareEnvelope(
                            token = "share-1",
                            createdAtEpochMillis = 1L,
                            sourcePlatform = "android",
                            text = "Shared body",
                            subject = "Subject",
                            items = listOf(ExternalShareItem("private/photo", "photo.png", "image/png", 4L))
                        ),
                    target = destination,
                    comment = "Comment"
                )
            )

        assertEquals("send_probe", assertIs<DataResult.Error>(result).error.code)
        assertEquals(ChannelId("channel-destination"), uploaded?.channelId)
        assertEquals("private/photo", uploaded?.files?.single()?.fileRef)
        assertEquals(ChannelId("channel-destination"), sentChannel)
        assertEquals("Comment\n\nSubject\n\nShared body", sentBody)
        assertEquals(listOf(AttachmentId("attachment-1")), sentAttachments)
        assertEquals("delivery-stable", sentRandomId)
    }

    @Test
    fun accountSwitchWhileUploadIsParkedPreventsIrreversibleSend() = runBlocking {
        val destination = target("destination")
        var authority = ExternalShareSendAuthority("account-a", "access-a", "refresh-a", Server.value)
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        var sendCalls = 0
        val sender =
            RealExternalShareSender(
                targetRepository = StubTargetRepository(listOf(destination)),
                uploadAttachments = { _, _ ->
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    DataResult.Content(
                        AttachmentUploadResult(
                            listOf(Attachment(AttachmentId("attachment-1"), "photo.png", "image/png", "4 B", 4L))
                        )
                    )
                },
                sendMessage = { _, _, _, _ ->
                    sendCalls += 1
                    DataResult.Error(ScreenError("unexpected_send"))
                },
                currentAuthority = { authority }
            )
        val sending =
            async {
                sender.send(
                    ExternalShareSendRequest(
                        attemptId = 1,
                        deliveryId = "delivery-stable",
                        sourceServerId = Server.value,
                        envelope =
                            ExternalShareEnvelope(
                                "share-1",
                                1L,
                                "android",
                                items = listOf(ExternalShareItem("private/photo", "photo.png", "image/png", 4L))
                            ),
                        target = destination,
                        comment = ""
                    )
                )
            }
        uploadStarted.await()
        authority = ExternalShareSendAuthority("account-b", "access-b", "refresh-b", Server.value)
        releaseUpload.complete(Unit)

        val result = assertIs<DataResult.Error>(sending.await())
        assertEquals("external_share_authority_changed", result.error.code)
        assertEquals(0, sendCalls)
    }

    @Test
    fun accountSwitchWhileResolveIsParkedPreventsUploadAndSend() = runBlocking {
        val destination = target("destination")
        val resolveStarted = CompletableDeferred<Unit>()
        val releaseResolve = CompletableDeferred<Unit>()
        val repository =
            object : StubTargetRepository(listOf(destination)) {
                override suspend fun resolveTargetChannel(
                    sourceServerId: ServerId,
                    target: ForwardMessageTarget,
                    allowJoin: Boolean
                ): DataResult<ForwardMessageDestination> {
                    resolveStarted.complete(Unit)
                    releaseResolve.await()
                    return super.resolveTargetChannel(sourceServerId, target, allowJoin)
                }
            }
        var authority = ExternalShareSendAuthority("account-a", "access-a", "refresh-a", Server.value)
        var uploadCalls = 0
        var sendCalls = 0
        val sender =
            RealExternalShareSender(
                targetRepository = repository,
                uploadAttachments = { _, _ ->
                    uploadCalls += 1
                    DataResult.Error(ScreenError("unexpected_upload"))
                },
                sendMessage = { _, _, _, _ ->
                    sendCalls += 1
                    DataResult.Error(ScreenError("unexpected_send"))
                },
                currentAuthority = { authority }
            )
        val sending =
            async {
                sender.send(
                    ExternalShareSendRequest(
                        attemptId = 1,
                        deliveryId = "delivery-stable",
                        sourceServerId = Server.value,
                        envelope =
                            ExternalShareEnvelope(
                                "share-1",
                                1L,
                                "android",
                                items = listOf(ExternalShareItem("private/photo", "photo.png", "image/png", 4L))
                            ),
                        target = destination,
                        comment = ""
                    )
                )
            }
        resolveStarted.await()
        authority = ExternalShareSendAuthority("account-b", "access-b", "refresh-b", Server.value)
        releaseResolve.complete(Unit)

        val result = assertIs<DataResult.Error>(sending.await())
        assertEquals("external_share_authority_changed", result.error.code)
        assertEquals(0, uploadCalls)
        assertEquals(0, sendCalls)
    }

    @Test
    fun invalidEnvelopeIdentityFailsClosedBeforeTargetLoad() = runBlocking {
        var envelopeCalls = 0
        var targetCalls = 0
        val store =
            ExternalShareStore(
                envelopeRepository =
                    object : ExternalShareEnvelopeRepository {
                        override suspend fun load(token: String): DataResult<ExternalShareEnvelope> {
                            envelopeCalls += 1
                            return DataResult.Content(envelope())
                        }

                        override suspend fun delete(token: String) = Unit
                    },
                draftRepository = MemoryDraftRepository(),
                targetRepository =
                    object : StubTargetRepository(emptyList()) {
                        override suspend fun recentTargets(
                            serverId: ServerId,
                            limit: Int
                        ): DataResult<List<ForwardMessageTarget>> {
                            targetCalls += 1
                            return super.recentTargets(serverId, limit)
                        }
                    },
                sender = SuccessfulSender,
                scope = this
            )

        store.dispatch(ExternalShareIntent.Load("", Server.value))

        val state = assertIs<ExternalShareState.Error>(store.state.value)
        assertEquals("external_share_identity_invalid", state.errorCode)
        assertEquals(0, envelopeCalls)
        assertEquals(0, targetCalls)
        store.clear()
    }

    @Test
    fun firstLoadTransfersEnvelopeToDurableDraftBeforeDeletingOneShotEnvelope() = runBlocking {
        val events = mutableListOf<String>()
        val draftRepository =
            object : ExternalShareDraftRepository {
                private var draft: ExternalShareDraft? = null

                override suspend fun load(token: String): DataResult<ExternalShareDraft> {
                    events += "draft_load"
                    return draft?.let { DataResult.Content(it) }
                        ?: DataResult.Empty(EmptyReason.NoMessages)
                }

                override suspend fun save(draft: ExternalShareDraft): DataResult<Unit> {
                    events += "draft_save_consumed_${draft.envelopeConsumed}"
                    this.draft = draft
                    return DataResult.Content(Unit)
                }

                override suspend fun delete(token: String): DataResult<Unit> = DataResult.Content(Unit)
            }
        val envelopeRepository =
            object : ExternalShareEnvelopeRepository {
                override suspend fun load(token: String): DataResult<ExternalShareEnvelope> {
                    events += "envelope_load"
                    return DataResult.Content(envelope(token))
                }

                override suspend fun delete(token: String) {
                    events += "envelope_delete"
                }
            }
        val store =
            store(
                scope = this,
                envelopeRepository = envelopeRepository,
                draftRepository = draftRepository
            )

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()

        assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals(
            listOf(
                "draft_load",
                "envelope_load",
                "draft_save_consumed_false",
                "envelope_delete",
                "draft_save_consumed_true"
            ),
            events
        )
        store.clear()
    }

    @Test
    fun processRestoreNeverAutoSendsAndRetryReusesStableDeliveryId() = runBlocking {
        val sent = target("sent")
        val interrupted = target("interrupted")
        val draftRepository =
            MemoryDraftRepository(
                ExternalShareDraft(
                    envelope = envelope(),
                    serverId = Server.value,
                    stage = ExternalShareDraftStage.Delivering,
                    selectedTargets = listOf(sent, interrupted),
                    comment = "Restored comment",
                    deliveries =
                        listOf(
                            ExternalShareDelivery(
                                target = sent,
                                deliveryId = "delivery-sent",
                                status = ExternalShareDeliveryStatus.Sent
                            ),
                            ExternalShareDelivery(
                                target = interrupted,
                                deliveryId = "delivery-interrupted",
                                status = ExternalShareDeliveryStatus.Sending
                            )
                        ),
                    envelopeConsumed = true
                )
            )
        val calls = mutableListOf<ExternalShareSendRequest>()
        val sender =
            object : ExternalShareSender {
                override suspend fun send(request: ExternalShareSendRequest): DataResult<Unit> {
                    calls += request
                    return DataResult.Content(Unit)
                }
            }
        val store =
            store(
                scope = this,
                draftRepository = draftRepository,
                sender = sender
            )

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()

        val restored = assertIs<ExternalShareState.Completed>(store.state.value)
        assertEquals(listOf("sent"), restored.sentTargets.map { it.id })
        assertEquals(listOf("interrupted"), restored.failedTargets.map { it.id })
        assertTrue(calls.isEmpty())

        store.dispatch(ExternalShareIntent.RetryFailed)
        yield()

        val completed = assertIs<ExternalShareState.Completed>(store.state.value)
        assertEquals(listOf("sent", "interrupted"), completed.sentTargets.map { it.id })
        assertEquals(listOf("interrupted"), calls.map { it.target.id })
        assertEquals(listOf("delivery-interrupted"), calls.map { it.deliveryId })
        store.clear()
    }

    private fun store(
        scope: CoroutineScope,
        targets: List<ForwardMessageTarget> = emptyList(),
        envelopeRepository: ExternalShareEnvelopeRepository =
            object : ExternalShareEnvelopeRepository {
                override suspend fun load(token: String): DataResult<ExternalShareEnvelope> =
                    DataResult.Content(envelope(token))

                override suspend fun delete(token: String) = Unit
            },
        draftRepository: ExternalShareDraftRepository = MemoryDraftRepository(),
        targetRepository: ForwardMessageTargetRepository = StubTargetRepository(targets),
        sender: ExternalShareSender = SuccessfulSender,
        maximumTargetCount: Int = ExternalShareMaximumTargetCount
    ): ExternalShareStore =
        ExternalShareStore(
            envelopeRepository = envelopeRepository,
            draftRepository = draftRepository,
            targetRepository = targetRepository,
            sender = sender,
            scope = scope,
            maximumTargetCount = maximumTargetCount
        )

    private fun envelope(token: String = "share-1"): ExternalShareEnvelope =
        ExternalShareEnvelope(
            token = token,
            createdAtEpochMillis = 1L,
            sourcePlatform = "test",
            text = "Shared text"
        )

    private fun target(id: String): ForwardMessageTarget =
        ForwardMessageTarget(
            serverId = Server,
            kind = ForwardMessageTargetKind.RecentChannel,
            id = id,
            title = id,
            channelId = ChannelId("channel-$id"),
            channelKind = ConversationKind.Channel
        )

    private fun cursorFor(sessionId: String): ForwardMessageTargetDirectoryCursor =
        ForwardMessageTargetDirectoryCursor(
            snapshotId = "snapshot",
            sessionId = sessionId,
            normalizedQuery = "",
            authorityRevision = "revision"
        )

    private fun ExternalShareStore.toggle(target: ForwardMessageTarget) {
        val current = assertIs<ExternalShareState.SelectingTargets>(state.value)
        dispatch(
            ExternalShareIntent.ToggleTarget(
                target = target,
                sessionId = current.externalShareDirectorySessionId(),
                renderGeneration = current.requestGeneration
            )
        )
    }

    private open class StubTargetRepository(
        private val targets: List<ForwardMessageTarget>
    ) : ForwardMessageTargetRepository {
        override suspend fun recentTargets(
            serverId: ServerId,
            limit: Int
        ): DataResult<List<ForwardMessageTarget>> = DataResult.Content(targets)

        override suspend fun searchTargets(
            serverId: ServerId,
            query: String,
            limit: Int
        ): DataResult<List<ForwardMessageTarget>> = DataResult.Content(targets)

        override suspend fun resolveTargetChannel(
            sourceServerId: ServerId,
            target: ForwardMessageTarget,
            allowJoin: Boolean
        ): DataResult<ForwardMessageDestination> =
            DataResult.Content(
                ForwardMessageDestination(
                    serverId = target.serverId,
                    channelId = requireNotNull(target.channelId),
                    channelKind = target.channelKind
                )
            )

        override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<Unit> =
            DataResult.Content(Unit)
    }

    private object SuccessfulSender : ExternalShareSender {
        override suspend fun send(request: ExternalShareSendRequest): DataResult<Unit> =
            DataResult.Content(Unit)
    }

    private class MemoryDraftRepository(
        initialDraft: ExternalShareDraft? = null
    ) : ExternalShareDraftRepository {
        var draft: ExternalShareDraft? = initialDraft

        override suspend fun load(token: String): DataResult<ExternalShareDraft> =
            draft
                ?.takeIf { it.envelope.token == token }
                ?.let { DataResult.Content(it) }
                ?: DataResult.Empty(EmptyReason.NoMessages)

        override suspend fun save(draft: ExternalShareDraft): DataResult<Unit> {
            this.draft = draft
            return DataResult.Content(Unit)
        }

        override suspend fun delete(token: String): DataResult<Unit> {
            if (draft?.envelope?.token == token) draft = null
            return DataResult.Content(Unit)
        }
    }

    private companion object {
        val Server = ServerId("server-1")
    }
}
