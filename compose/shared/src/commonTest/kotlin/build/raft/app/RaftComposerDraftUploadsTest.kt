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
        val uploads = RaftComposerDraftUploads(backgroundScope).forServer(ServerA)
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
            val uploads = RaftComposerDraftUploads(backgroundScope).forServer(ServerA)
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
                    assertFalse(secondPage.pending().hasBlockingThreadAttachmentUpload())
                }
            }
        }
    }

    @Test
    fun removingTheTileCancelsItsUpload() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val uploads = RaftComposerDraftUploads(backgroundScope).forServer(ServerA)
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
        val serverA = owner.forServer(ServerA)
        val serverB = owner.forServer(ServerB)
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
        assertNotSame(serverA, owner.forServer(ServerA))

        // A page restored after that has no upload to wait for: the tile offers Retry.
        val log = mutableListOf<String>()
        val restored = mountComposer(persistence, DraftA, instanceId = "composer-a2")
        val fresh = owner.forServer(ServerA)
        assertEquals(setOf(PickedId), recover(restored, DraftA, fresh, log), "log=$log")
        assertEquals(ThreadComposerUploadInterruptedErrorCode, restored.row(PickedId).errorCode)
        assertFalse(restored.pending().hasBlockingThreadAttachmentUpload())
    }

    @Test
    fun losingAServerCancelsOnlyThatServersUploads() = runTest {
        val owner = RaftComposerDraftUploads(backgroundScope)
        val serverA = owner.forServer(ServerA)
        val serverB = owner.forServer(ServerB)
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
        val uploads = owner.forServer(ServerA)
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
        assertTrue(owner.forServer(ServerB).uploads(DraftA).isEmpty())
    }

    private enum class FinishedAway { Succeeded, Failed }

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
            ownedByDraftUpload = { id -> uploads.upload(draftKey, id) != null },
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
        const val ServerA = "server-a"
        const val ServerB = "server-b"
        const val DraftA = "send=channel-a|parent=_|mention=channel-a"
        const val DraftB = "send=channel-b|parent=_|mention=channel-b"
        const val PickedId = "picked-180384"
        const val UploadedId = "attachment-180384"
    }
}
