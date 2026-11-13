package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.PlatformFile
import build.raft.app.transfer.RaftTransferMeter
import build.raft.app.transfer.RaftTransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Composer attachment uploads belong to the draft, not to the conversation page (artin: "为啥这个会跟
 * 离开会话有关，不能背后上传吗"). The user picks an image, the upload starts, they leave the
 * conversation: it keeps going, and when they come back the tile shows where it is, or the ready
 * attachment to send. Only removing the tile, sending, or signing out cancels it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftComposerDraftUploadsTest {
    @Test
    fun leavingAndReturningKeepsTheProgressAndTheResult() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val transport = ControlledTransport()

        // Page 1: the image is picked, its upload starts and reports 30%.
        val firstPage = mountComposer(persistence, DraftA, instanceId = "composer-1")
        val firstApplier = RaftComposerDraftUploadApplier()
        startUpload(uploads, firstPage, DraftA, PickedId, transport)
        runCurrent()
        transport.progress(30)
        firstApplier.applyTo(firstPage, uploads, DraftA)
        assertEquals(transport.lastTransfer, firstPage.row(PickedId).uploadTransfer)

        // The user typed a caption and leaves the conversation: page 1 goes away.
        firstPage.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "look"))
        firstPage.dispatch(SharedThreadComposerDraftIntent.Flush)

        // The upload keeps going without any page.
        transport.progress(80)
        assertTrue(uploads.orchestrator.isActive(PickedId, DraftA), "the upload outlives its page")

        // Page 2 opens the same draft: the tile is still uploading, at the current progress.
        val log = mutableListOf<String>()
        val secondPage = mountComposer(persistence, DraftA, instanceId = "composer-2")
        val secondApplier = RaftComposerDraftUploadApplier(log = log::add)
        assertEquals(
            emptySet(),
            recover(secondPage, DraftA, uploads, log),
            "a draft-owned upload is never failed as interrupted"
        )
        secondApplier.applyTo(secondPage, uploads, DraftA)
        val adopted = secondPage.row(PickedId)
        assertEquals("uploading", adopted.status)
        assertEquals(transport.lastTransfer, adopted.uploadTransfer, "the tile shows the current progress")
        assertNotNull(secondPage.state.value.attachmentAdmissionsByLocalId[PickedId], "page 2 adopted the upload")
        assertTrue(secondPage.pending().hasBlockingThreadAttachmentUpload(), "Send waits for the upload in flight")
        assertTrue(log.any { it.startsWith("thread_composer_attachment_upload_adopted") }, "log=$log")

        // It finishes: the ready attachment replaces the tile and Send is enabled.
        transport.succeed(UploadedId)
        runCurrent()
        secondApplier.applyTo(secondPage, uploads, DraftA)
        assertEquals(listOf(UploadedId), secondPage.pending().readyThreadAttachmentIdsForSend())
        assertFalse(secondPage.pending().hasBlockingThreadAttachmentUpload())
        assertEquals("look", secondPage.state.value.seedText)
        assertFalse(uploads.orchestrator.isActive(PickedId, DraftA))
        assertTrue(assertNotNull(uploads.upload(DraftA, PickedId)).delivered)

        // The ready attachment is part of the stored draft: a later page shows it as is.
        val thirdPage = mountComposer(persistence, DraftA, instanceId = "composer-3")
        RaftComposerDraftUploadApplier().applyTo(thirdPage, uploads, DraftA)
        assertEquals(listOf(UploadedId), thirdPage.pending().readyThreadAttachmentIdsForSend())
        assertTrue(thirdPage.state.value.uploadedAttachments.none { it.id == PickedId })

        // Sending forgets the draft's uploads.
        uploads.retireDraft(DraftA)
        assertTrue(uploads.uploads(DraftA).isEmpty())
    }

    @Test
    fun anUploadThatFinishedWhileAwayIsShownEvenIfTheDraftNeverStoredItsTile() = runTest {
        FinishedAway.entries.forEach { outcome ->
            val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
            val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
            val transport = ControlledTransport()
            val firstPage = mountComposer(persistence, DraftA, instanceId = "composer-1")
            startUpload(uploads, firstPage, DraftA, PickedId, transport)
            runCurrent()

            // Left right after picking (nothing written yet); the upload ends while away.
            when (outcome) {
                FinishedAway.Succeeded -> transport.succeed(UploadedId)
                FinishedAway.Failed -> transport.fail("socket_timeout")
            }
            runCurrent()

            val secondPage = mountComposer(persistence, DraftA, instanceId = "composer-2")
            assertTrue(secondPage.state.value.uploadedAttachments.isEmpty(), "$outcome: the stored draft has no tile")
            val failures = mutableListOf<String>()
            RaftComposerDraftUploadApplier().applyTo(secondPage, uploads, DraftA, onFailed = failures::add)
            when (outcome) {
                FinishedAway.Succeeded -> {
                    assertEquals(listOf(UploadedId), secondPage.pending().readyThreadAttachmentIdsForSend(), "$outcome")
                    assertEquals(emptyList(), failures)
                }
                FinishedAway.Failed -> {
                    val row = secondPage.row(PickedId)
                    assertEquals("failed", row.status, "$outcome: the tile offers Retry")
                    assertEquals("socket_timeout", row.errorCode)
                    assertEquals(listOf("socket_timeout"), failures)
                    // No longer uploading; Send stays blocked by the failed tile (attachment_upload_failed).
                    assertFalse(secondPage.pending().hasUploadingThreadAttachment())
                    assertTrue(secondPage.pending().hasFailedThreadAttachmentUpload())
                }
            }
        }
    }

    @Test
    fun removingTheTileCancelsItsUpload() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val transport = ControlledTransport()
        val page = mountComposer(persistence, DraftA, instanceId = "composer-1")
        val applier = RaftComposerDraftUploadApplier()
        startUpload(uploads, page, DraftA, PickedId, transport)
        runCurrent()
        transport.progress(40)
        applier.applyTo(page, uploads, DraftA)

        // The page's removeAttachment: cancel the draft's upload, hide the tile.
        uploads.remove(DraftA, PickedId)
        page.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(PickedId))
        runCurrent()

        assertFalse(uploads.orchestrator.isActive(PickedId, DraftA), "the upload is cancelled")
        assertTrue(transport.cancelled.isCompleted, "the transfer itself was cancelled")
        assertNull(uploads.upload(DraftA, PickedId))
        // A late result has nowhere to go, and no page brings the tile back.
        transport.succeed(UploadedId)
        runCurrent()
        assertTrue(uploads.uploads(DraftA).isEmpty())
        val returning = mountComposer(persistence, DraftA, instanceId = "composer-2")
        RaftComposerDraftUploadApplier().applyTo(returning, uploads, DraftA)
        assertTrue(returning.pending().isEmpty(), "${returning.pending()}")
    }

    @Test
    fun signingOutCancelsEveryUpload() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val owner = RaftComposerDraftUploads(backgroundScope)
        val serverA = owner.forOwner(AccountA, ServerA)
        val serverB = owner.forOwner(AccountA, ServerB)
        val first = ControlledTransport()
        val second = ControlledTransport()
        val pageA = mountComposer(persistence, DraftA, instanceId = "composer-a")
        val pageB = mountComposer(persistence, DraftB, instanceId = "composer-b")
        startUpload(serverA, pageA, DraftA, PickedId, first)
        startUpload(serverB, pageB, DraftB, PickedId, second)
        pageA.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "a"))
        pageA.dispatch(SharedThreadComposerDraftIntent.Flush)
        runCurrent()

        owner.cancelAll()
        runCurrent()

        assertFalse(serverA.orchestrator.isActive(PickedId, DraftA))
        assertFalse(serverB.orchestrator.isActive(PickedId, DraftB))
        assertTrue(first.cancelled.isCompleted && second.cancelled.isCompleted, "both transfers were cancelled")
        assertTrue(serverA.byDraft.value.isEmpty() && serverB.byDraft.value.isEmpty())
        // The signed-out owner takes no new upload; the next sign-in gets a fresh one.
        assertNull(serverA.launch(DraftA, picked(PickedId), picked(PickedId).toFile(), admissionFor(PickedId)) {})
        assertNotSame(serverA, owner.forOwner(AccountA, ServerA))

        // A page restored after that has no upload to wait for: the tile offers Retry.
        val log = mutableListOf<String>()
        val restored = mountComposer(persistence, DraftA, instanceId = "composer-a2")
        val fresh = owner.forOwner(AccountA, ServerA)
        assertEquals(setOf(PickedId), recover(restored, DraftA, fresh, log), "log=$log")
        assertEquals(ThreadComposerUploadInterruptedErrorCode, restored.row(PickedId).errorCode)
        assertFalse(restored.pending().hasUploadingThreadAttachment())
        assertTrue(restored.pending().hasFailedThreadAttachmentUpload(), "the tile offers Retry")
    }

    @Test
    fun losingAServerCancelsOnlyThatServersUploads() = runTest {
        val owner = RaftComposerDraftUploads(backgroundScope)
        val serverA = owner.forOwner(AccountA, ServerA)
        val serverB = owner.forOwner(AccountA, ServerB)
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        startUpload(serverA, mountComposer(persistence, DraftA, "composer-a"), DraftA, PickedId, ControlledTransport())
        startUpload(serverB, mountComposer(persistence, DraftB, "composer-b"), DraftB, PickedId, ControlledTransport())
        runCurrent()

        owner.dropServer(ServerA)
        runCurrent()

        assertFalse(serverA.orchestrator.isActive(PickedId, DraftA))
        assertTrue(serverB.orchestrator.isActive(PickedId, DraftB), "another server's upload keeps going")
        assertNotNull(serverB.upload(DraftB, PickedId))
    }

    @Test
    fun twoConversationsDoNotMixTheirUploads() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val owner = RaftComposerDraftUploads(backgroundScope)
        val uploads = owner.forOwner(AccountA, ServerA)
        val inA = ControlledTransport()
        val inB = ControlledTransport()
        // The same local id in two conversations of one server.
        val pageA = mountComposer(persistence, DraftA, instanceId = "composer-a")
        val pageB = mountComposer(persistence, DraftB, instanceId = "composer-b")
        val applierA = RaftComposerDraftUploadApplier()
        val applierB = RaftComposerDraftUploadApplier()
        startUpload(uploads, pageA, DraftA, PickedId, inA)
        startUpload(uploads, pageB, DraftB, PickedId, inB)
        runCurrent()
        inA.progress(30)
        inB.progress(70)

        applierA.applyTo(pageA, uploads, DraftA)
        applierB.applyTo(pageB, uploads, DraftB)
        assertEquals(inA.lastTransfer, pageA.row(PickedId).uploadTransfer)
        assertEquals(inB.lastTransfer, pageB.row(PickedId).uploadTransfer)

        // A's result lands in A only.
        inA.succeed(UploadedId)
        runCurrent()
        applierA.applyTo(pageA, uploads, DraftA)
        applierB.applyTo(pageB, uploads, DraftB)
        assertEquals(listOf(UploadedId), pageA.pending().readyThreadAttachmentIdsForSend())
        assertEquals("uploading", pageB.row(PickedId).status)
        assertTrue(pageB.pending().hasBlockingThreadAttachmentUpload())

        // A's uploads offered to B's composer change nothing.
        val before = pageB.state.value
        RaftComposerDraftUploadApplier().applyTo(pageB, uploads, DraftA)
        assertEquals(before, pageB.state.value)

        // Removing B's tile leaves A's result alone; the same draft key on another server is separate.
        uploads.remove(DraftB, PickedId)
        assertNotNull(uploads.upload(DraftA, PickedId))
        assertTrue(owner.forOwner(AccountA, ServerB).uploads(DraftA).isEmpty())
    }

    @Test
    fun anUploadThatEndsWithoutAResultOffersRetryInsteadOfStayingOwned() = runTest {
        EndedWithoutResult.entries.forEach { ending ->
            val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
            val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
            val transport = ControlledTransport()
            val firstPage = mountComposer(persistence, DraftA, instanceId = "composer-1")
            when (ending) {
                // Something other than the user's remove cancels the job (the orphan sweep's
                // stoppedAttempts path calls the orchestrator directly).
                EndedWithoutResult.CancelledElsewhere -> startUpload(uploads, firstPage, DraftA, PickedId, transport)
                // The page's upload block swallows the cancellation and returns without a result.
                EndedWithoutResult.ReturnedEarly -> {
                    val row = picked(PickedId)
                    firstPage.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(row), DraftA))
                    val admission = assertNotNull(firstPage.state.value.attachmentAdmissionsByLocalId[PickedId])
                    assertNotNull(
                        uploads.launch(DraftA, row, row.toFile(), admission) {
                            try {
                                transport.result.await()
                            } catch (_: kotlinx.coroutines.CancellationException) {
                                transport.cancelled.complete(Unit)
                            }
                        }
                    )
                }
            }
            firstPage.dispatch(SharedThreadComposerDraftIntent.Flush)
            runCurrent()
            assertTrue(uploads.ownsUpload(DraftA, PickedId), "$ending: a running upload is owned")

            uploads.orchestrator.remove(PickedId, DraftA)
            runCurrent()

            assertTrue(transport.cancelled.isCompleted, "$ending: the transfer was cancelled")
            assertFalse(uploads.orchestrator.isActive(PickedId, DraftA))
            assertEquals(
                RaftComposerDraftUploadOutcome.Failed(ThreadComposerUploadInterruptedErrorCode),
                assertNotNull(uploads.upload(DraftA, PickedId), "$ending").outcome,
                "$ending: the record is not left in flight with no job behind it"
            )

            // The page that opens the draft again shows Retry rather than waiting forever.
            val log = mutableListOf<String>()
            val returning = mountComposer(persistence, DraftA, instanceId = "composer-2")
            recover(returning, DraftA, uploads, log)
            val failures = mutableListOf<String>()
            RaftComposerDraftUploadApplier(log = log::add).applyTo(returning, uploads, DraftA, onFailed = failures::add)
            assertEquals("failed", returning.row(PickedId).status, "$ending log=$log")
            assertEquals(ThreadComposerUploadInterruptedErrorCode, returning.row(PickedId).errorCode)
            assertFalse(returning.pending().hasUploadingThreadAttachment(), "$ending")
            assertTrue(returning.pending().hasFailedThreadAttachmentUpload(), "$ending: the tile offers Retry")
        }
    }

    @Test
    fun retryingAnUploadThatFailedWhileAwayFinishesInTheReturningComposer() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val firstAttempt = ControlledTransport()
        val firstPage = mountComposer(persistence, DraftA, instanceId = "composer-1")
        startUpload(uploads, firstPage, DraftA, PickedId, firstAttempt)
        firstPage.dispatch(SharedThreadComposerDraftIntent.Flush)
        runCurrent()
        // Fails while the user is away.
        firstAttempt.fail("socket_timeout")
        runCurrent()

        val secondPage = mountComposer(persistence, DraftA, instanceId = "composer-2")
        val applier = RaftComposerDraftUploadApplier()
        val failures = mutableListOf<String>()
        val adoptedFiles = mutableListOf<PlatformFile?>()
        applier.apply(
            draftKey = DraftA,
            uploads = uploads.uploads(DraftA),
            currentState = { secondPage.state.value },
            acceptWithState = secondPage::acceptWithState,
            onAdopted = { upload -> adoptedFiles += upload.file },
            onFailed = { _, errorCode -> failures += errorCode },
            onDelivered = { upload -> uploads.markDelivered(DraftA, upload.localAttachmentId, upload.attemptToken) },
        )
        assertEquals("failed", secondPage.row(PickedId).status)
        assertEquals(listOf("socket_timeout"), failures)
        assertEquals<List<PlatformFile?>>(listOf(picked(PickedId).toFile()), adoptedFiles, "the returning page has the file for Retry")

        // Retry (the page's retryAttachment): a new attempt of the same tile, from the returning page.
        secondPage.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(PickedId))
        val retry = ControlledTransport()
        startUpload(uploads, secondPage, DraftA, PickedId, retry)
        runCurrent()
        applier.applyTo(secondPage, uploads, DraftA, onFailed = failures::add)
        assertEquals("uploading", secondPage.row(PickedId).status, "the old failure is not applied to the retry")
        assertTrue(secondPage.pending().hasUploadingThreadAttachment())

        retry.succeed(UploadedId)
        runCurrent()
        applier.applyTo(secondPage, uploads, DraftA, onFailed = failures::add)
        assertEquals(listOf(UploadedId), secondPage.pending().readyThreadAttachmentIdsForSend())
        assertFalse(secondPage.pending().hasBlockingThreadAttachmentUpload())
        assertEquals(listOf("socket_timeout"), failures, "the failure is reported once")
    }

    @Test
    fun removingAReadyTileForgetsItsUploadAndTheFile() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val transport = ControlledTransport()
        val page = mountComposer(persistence, DraftA, instanceId = "composer-1")
        val applier = RaftComposerDraftUploadApplier()
        startUpload(uploads, page, DraftA, PickedId, transport)
        runCurrent()
        transport.succeed(UploadedId)
        runCurrent()
        applier.applyTo(page, uploads, DraftA)
        assertEquals(listOf(UploadedId), page.pending().readyThreadAttachmentIdsForSend())
        val delivered = assertNotNull(uploads.upload(DraftA, PickedId))
        assertTrue(delivered.delivered)
        assertNull(delivered.file, "a delivered success drops the picked file")

        // The tile now carries the server attachment id; the upload is recorded under the picked id.
        uploads.remove(DraftA, UploadedId)
        page.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(UploadedId))
        assertTrue(uploads.uploads(DraftA).isEmpty(), "the ready tile's upload record is not leaked")
        assertTrue(page.pending().isEmpty(), "${page.pending()}")
    }

    @Test
    fun aThreadDraftAndItsChannelDraftKeepTheirOwnUploads() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val inChannel = ControlledTransport()
        val inThread = ControlledTransport()
        // The channel composer and the composer of a thread under it, the same local id in both.
        val channelPage = mountComposer(persistence, DraftA, instanceId = "composer-channel")
        val threadPage = mountComposer(persistence, ThreadDraftA, instanceId = "composer-thread")
        val channelApplier = RaftComposerDraftUploadApplier()
        val threadApplier = RaftComposerDraftUploadApplier()
        startUpload(uploads, channelPage, DraftA, PickedId, inChannel)
        startUpload(uploads, threadPage, ThreadDraftA, PickedId, inThread)
        runCurrent()

        inThread.succeed(UploadedId)
        runCurrent()
        channelApplier.applyTo(channelPage, uploads, DraftA)
        threadApplier.applyTo(threadPage, uploads, ThreadDraftA)
        assertEquals(listOf(UploadedId), threadPage.pending().readyThreadAttachmentIdsForSend())
        assertEquals("uploading", channelPage.row(PickedId).status, "the thread's result stays in the thread")

        // Sending the thread reply forgets only the thread draft's uploads.
        uploads.retireDraft(ThreadDraftA)
        assertTrue(uploads.uploads(ThreadDraftA).isEmpty())
        assertTrue(uploads.orchestrator.isActive(PickedId, DraftA), "the channel's upload keeps going")
        inChannel.progress(60)
        channelApplier.applyTo(channelPage, uploads, DraftA)
        assertEquals(inChannel.lastTransfer, channelPage.row(PickedId).uploadTransfer)
        assertFalse(inChannel.cancelled.isCompleted)
    }

    @Test
    fun anotherAccountOnTheSameServerHasItsOwnUploads() = runTest {
        val owner = RaftComposerDraftUploads(backgroundScope)
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val first = owner.forOwner(AccountA, ServerA)
        startUpload(first, mountComposer(persistence, DraftA, "composer-a"), DraftA, PickedId, ControlledTransport())
        runCurrent()

        // The session changed identity without a sign-out: the next account sees none of these.
        val second = owner.forOwner(AccountB, ServerA)
        assertNotSame(first, second)
        assertTrue(second.uploads(DraftA).isEmpty())
        assertFalse(second.ownsUpload(DraftA, PickedId))
        assertTrue(first.ownsUpload(DraftA, PickedId))

        // Losing the server cancels it for every account.
        owner.dropServer(ServerA)
        runCurrent()
        assertFalse(first.orchestrator.isActive(PickedId, DraftA))
    }

    @Test
    fun theUploadOwnerIsThePagesOwnServer() {
        assertEquals("shell", threadComposerUploadOwnerServerId("shell", "page", "selected"))
        assertEquals("page", threadComposerUploadOwnerServerId(" ", "page", "selected"))
        assertEquals("selected", threadComposerUploadOwnerServerId(null, null, "selected"))
        assertNull(threadComposerUploadOwnerServerId(null, "", null))
    }

    private enum class FinishedAway { Succeeded, Failed }

    private enum class EndedWithoutResult { CancelledElsewhere, ReturnedEarly }

    /** One upload's transport, driven by the test. */
    private class ControlledTransport {
        val result = CompletableDeferred<Result<String>>()
        val cancelled = CompletableDeferred<Unit>()
        private val meter = RaftTransferMeter(bytesTotal = 100L)
        private var report: ((RaftTransferProgress) -> Unit)? = null
        var lastTransfer: RaftTransferProgress? = null
            private set

        fun attach(report: (RaftTransferProgress) -> Unit) {
            this.report = report
        }

        fun progress(percent: Long) {
            val transfer = meter.bytes(percent, 100L)
            lastTransfer = transfer
            assertNotNull(report, "the upload has not started")(transfer)
        }

        fun succeed(uploadedId: String) {
            result.complete(Result.success(uploadedId))
        }

        fun fail(errorCode: String) {
            result.complete(Result.failure(IllegalStateException(errorCode)))
        }
    }

    /** The page's pick: admit the tile in [store], then start the draft-owned upload. */
    private fun startUpload(
        uploads: RaftComposerServerUploads,
        store: SharedThreadComposerDraftStore,
        draftKey: String,
        localId: String,
        transport: ControlledTransport,
    ) {
        val row = picked(localId)
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(row), draftKey))
        val admission = assertNotNull(store.state.value.attachmentAdmissionsByLocalId[localId], "the pick is admitted")
        val token =
            uploads.launch(draftKey, row, row.toFile(), admission) { attemptToken ->
                transport.attach { transfer -> uploads.progress(draftKey, localId, attemptToken, transfer) }
                val outcome =
                    try {
                        transport.result.await()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        transport.cancelled.complete(Unit)
                        throw cancelled
                    }
                outcome.fold(
                    onSuccess = { uploadedId ->
                        uploads.succeed(
                            draftKey,
                            localId,
                            attemptToken,
                            listOf(SharedThreadComposerAttachment(uploadedId, row.fileName, status = "ready"))
                        )
                    },
                    onFailure = { error -> uploads.fail(draftKey, localId, attemptToken, error.message.orEmpty()) }
                )
            }
        assertNotNull(token, "the upload starts")
    }

    private fun RaftComposerDraftUploadApplier.applyTo(
        store: SharedThreadComposerDraftStore,
        uploads: RaftComposerServerUploads,
        draftKey: String,
        onFailed: (String) -> Unit = {},
    ) {
        apply(
            draftKey = draftKey,
            uploads = uploads.uploads(draftKey),
            currentState = { store.state.value },
            acceptWithState = store::acceptWithState,
            onFailed = { _, errorCode -> onFailed(errorCode) },
            onDelivered = { upload -> uploads.markDelivered(draftKey, upload.localAttachmentId, upload.attemptToken) },
        )
    }

    private fun recover(
        store: SharedThreadComposerDraftStore,
        draftKey: String,
        uploads: RaftComposerServerUploads,
        log: MutableList<String>,
    ): Set<String> =
        recoverOrphanedThreadComposerUploads(
            draftKey = draftKey,
            state = store.state.value,
            acceptWithState = store::acceptWithState,
            currentState = { store.state.value },
            orchestrator = uploads.orchestrator,
            log = log::add,
            ownedByDraftUpload = { id -> uploads.ownsUpload(draftKey, id) },
        )

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
                    draftKey = draftKey
                )
            )
        }

    private fun picked(id: String): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = id,
            fileName = "180384.jpg",
            sizeBytes = 100L,
            mimeType = "image/jpeg",
            status = "uploading",
            fileRef = "cache/$id.jpg",
            source = "platform_picker"
        )

    private fun SharedThreadComposerAttachment.toFile(): PlatformFile =
        PlatformFile(id = id, fileName = fileName, mimeType = mimeType, sizeBytes = sizeBytes, fileRef = fileRef, source = source.orEmpty())

    private fun admissionFor(id: String): SharedThreadComposerAttachmentAdmission =
        SharedThreadComposerAttachmentAdmission(
            localAttachmentId = id,
            generation = 1L,
            fileRef = "cache/$id.jpg",
            source = "platform_picker",
            replacementAttachmentId = id
        )

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
                    errorCode = attachment.errorCode
                )
            }

    private companion object {
        const val AccountA = "account-a"
        const val AccountB = "account-b"
        const val ServerA = "server-a"
        const val ServerB = "server-b"
        const val DraftA = "send=channel-a|parent=_|mention=channel-a"
        const val DraftB = "send=channel-b|parent=_|mention=channel-b"
        const val ThreadDraftA = "send=thread-a1|parent=message-a1|mention=channel-a"
        const val PickedId = "picked-180384"
        const val UploadedId = "attachment-180384"
    }
}
