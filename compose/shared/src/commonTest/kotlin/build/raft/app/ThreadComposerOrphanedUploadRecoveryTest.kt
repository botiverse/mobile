package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hands 9cf9735f (Android 1.24.0): an image upload was in flight, the user left the conversation
 * and opened it again. The recreated page restored the row from the durable draft without the
 * upload's admission, so the old attempt's result could not land; the chip said "uploading"
 * forever and Send was refused with `attachment_upload_in_progress`. The restored row must turn
 * into a failed `upload_interrupted` row (Retry), whatever the old attempt does later, and a
 * retried upload must make the draft sendable again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadComposerOrphanedUploadRecoveryTest {
    @Test
    fun recreatedPageTurnsTheOrphanedUploadIntoRetryAndARetryUnblocksSend() = runTest {
        OldAttemptOutcome.entries.forEach { outcome ->
            run {
                val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
                val log = mutableListOf<String>()

                // Page 1 admits the picked image and starts its upload (60 s on a bad network).
                val firstPage = mountComposer(persistence, instanceId = "composer-1")
                val firstOrchestrator = ThreadAttachmentUploadOrchestrator(backgroundScope)
                firstPage.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(pickedImage()), DraftKey))
                firstPage.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "look"))
                launchUpload(firstOrchestrator, firstPage, outcome, uploadMillis = 60_000L)
                runCurrent()
                assertTrue(firstOrchestrator.isActive(PickedId, DraftKey), "$outcome: page 1 upload must be running")

                // The user leaves and reopens the conversation: a new page and composer store.
                firstPage.dispatch(SharedThreadComposerDraftIntent.Flush)
                val secondPage = mountComposer(persistence, instanceId = "composer-2")
                val secondOrchestrator = ThreadAttachmentUploadOrchestrator(backgroundScope)
                val restored = secondPage.state.value.uploadedAttachments.single { it.id == PickedId }
                assertFalse(restored.status == "ready", "$outcome: the restored row is not ready")
                assertTrue(
                    secondPage.state.value.pendingItems().hasBlockingThreadAttachmentUpload(),
                    "$outcome: before recovery the restored row blocks Send"
                )

                val interrupted = recover(secondPage, secondOrchestrator, log)

                assertEquals(setOf(PickedId), interrupted, "$outcome: log=$log")
                assertRetryChip(secondPage, "$outcome after reopening")
                assertTrue(
                    log.any { it.startsWith("thread_composer_attachment_upload_interrupted") && PickedId in it },
                    "$outcome: log=$log"
                )
                assertNotNull(
                    secondPage.state.value.uploadedAttachments.single { it.id == PickedId }
                        .toRestoredThreadComposerPlatformFile(),
                    "$outcome: Retry needs the restored file"
                )

                // The old attempt finishes on the process scope; it must not leave the chip stuck.
                advanceTimeBy(60_001L)
                runCurrent()
                assertFalse(firstOrchestrator.isActive(PickedId, DraftKey), "$outcome: old attempt finished")
                assertEquals(emptySet(), recover(secondPage, secondOrchestrator, log), "$outcome: nothing left to fail")
                assertRetryChip(secondPage, "$outcome after the old attempt finished")

                // Retry on the recreated page: a fresh admission and attempt, which lands.
                secondPage.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(PickedId))
                secondPage.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(pickedImage()), DraftKey))
                assertEquals(emptySet(), recover(secondPage, secondOrchestrator, log), "$outcome: the retry is admitted")
                launchUpload(secondOrchestrator, secondPage, OldAttemptOutcome.Succeeds, uploadMillis = 2_000L)
                advanceTimeBy(2_001L)
                runCurrent()

                val pending = secondPage.state.value.pendingItems()
                assertEquals(listOf(ServerId), pending.readyThreadAttachmentIdsForSend(), "$outcome: $pending")
                assertFalse(pending.hasBlockingThreadAttachmentUpload(), "$outcome: Send is unblocked")
            }
        }
    }

    @Test
    fun remountWithAnotherComposerInstanceStopsThePageAttemptThatCanNoLongerPublish() = runTest {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val log = mutableListOf<String>()
        val page = mountComposer(persistence, instanceId = "composer-1")
        val orchestrator = ThreadAttachmentUploadOrchestrator(backgroundScope)
        page.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(pickedImage()), DraftKey))
        launchUpload(orchestrator, page, OldAttemptOutcome.Succeeds, uploadMillis = 60_000L)
        runCurrent()

        // The live attempt still owns its admission: recovery leaves it alone.
        assertEquals(emptySet(), recover(page, orchestrator, log))
        assertTrue(orchestrator.isActive(PickedId, DraftKey))

        // Same store, same draft, another composer instance: the admission is dropped and the
        // running attempt can never publish into this draft.
        page.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-2"),
                draftKey = DraftKey
            )
        )
        assertEquals("uploading", page.state.value.uploadedAttachments.single { it.id == PickedId }.status)

        assertEquals(setOf(PickedId), recover(page, orchestrator, log), "log=$log")
        assertRetryChip(page, "after the remount")
        assertFalse(orchestrator.isActive(PickedId, DraftKey), "Retry must not be refused as already uploading")
        assertTrue(log.any { "stoppedAttempts=$PickedId" in it }, "log=$log")
    }

    @Test
    fun onlyUnadmittedLocalRowsAreOrphanedNeverQueuedExternalShareReadyFailedOrRemovedRows() {
        val admitted = pickedImage().copy(id = "picked-admitted")
        val state =
            SharedThreadComposerDraftState(
                draftKey = DraftKey,
                uploadedAttachments =
                    listOf(
                        pickedImage().copy(status = "queued"),
                        pickedImage().copy(id = "picked-uploading"),
                        admitted,
                        pickedImage().copy(id = "picked-removed"),
                        pickedImage().copy(id = "picked-failed", status = "failed"),
                        SharedThreadComposerAttachment(id = "server-ready", fileName = "a.png", status = "ready"),
                        SharedThreadComposerAttachment(
                            id = "external-share-1",
                            fileName = "shared.png",
                            status = "queued",
                            fileRef = "content://share/1",
                            source = "external_share"
                        )
                    ),
                removedAttachmentIds = setOf("picked-removed"),
                attachmentAdmissionsByLocalId =
                    mapOf(
                        admitted.id to
                            SharedThreadComposerAttachmentAdmission(
                                localAttachmentId = admitted.id,
                                generation = 1L,
                                fileRef = admitted.fileRef,
                                source = admitted.source,
                                replacementAttachmentId = admitted.id
                            )
                    )
            )

        assertEquals(setOf(PickedId, "picked-uploading"), state.orphanedThreadComposerUploadIds())
    }

    private enum class OldAttemptOutcome { Succeeds, Fails }

    private fun mountComposer(
        persistence: SharedThreadComposerDraftPersistence,
        instanceId: String
    ): SharedThreadComposerDraftStore =
        SharedThreadComposerDraftStore(
            persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence))
        ).also { store ->
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = instanceId),
                    draftKey = DraftKey
                )
            )
        }

    private fun recover(
        store: SharedThreadComposerDraftStore,
        orchestrator: ThreadAttachmentUploadOrchestrator,
        log: MutableList<String>
    ): Set<String> =
        recoverOrphanedThreadComposerUploads(
            draftKey = DraftKey,
            state = store.state.value,
            acceptWithState = store::acceptWithState,
            currentState = { store.state.value },
            orchestrator = orchestrator,
            log = log::add
        )

    /**
     * The production attempt's publication: the admission captured at launch, then a strict
     * success projection or failure mark through the attempt fence, after [uploadMillis].
     */
    private fun launchUpload(
        orchestrator: ThreadAttachmentUploadOrchestrator,
        store: SharedThreadComposerDraftStore,
        outcome: OldAttemptOutcome,
        uploadMillis: Long
    ) {
        val admission =
            assertNotNull(store.state.value.attachmentAdmissionsByLocalId[PickedId], "launch needs an admission")
        orchestrator.launch(PickedId, DraftKey) { attemptToken ->
            delay(uploadMillis)
            withContext(NonCancellable) {
                when (outcome) {
                    OldAttemptOutcome.Succeeds ->
                        orchestrator.publishIfAccepted(PickedId, DraftKey, attemptToken) {
                            store.acceptWithState(
                                SharedThreadComposerDraftIntent.AddUploadedAttachments(
                                    attachments = listOf(SharedThreadComposerAttachment(ServerId, "photo.jpg", status = "ready")),
                                    replacedAttachmentIds = setOf(PickedId),
                                    replacedAttachmentIdsByUploadedId = mapOf(ServerId to PickedId),
                                    expectedDraftKey = DraftKey,
                                    expectedAdmissions = mapOf(PickedId to admission),
                                    expectedServerAttachmentIdsByLocalId = mapOf(PickedId to setOf(ServerId)),
                                    expectedReplacementAttachmentIdsByLocalId = mapOf(PickedId to PickedId)
                                )
                            )
                        }
                    OldAttemptOutcome.Fails -> {
                        val failed = orchestrator.failAttempts(DraftKey, setOf(PickedId))
                        store.acceptWithState(
                            SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                                attachmentIds = failed,
                                errorCode = "socket_timeout",
                                expectedDraftKey = DraftKey,
                                expectedAdmissions = mapOf(PickedId to admission)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun assertRetryChip(store: SharedThreadComposerDraftStore, context: String) {
        val row = store.state.value.uploadedAttachments.single { it.id == PickedId }
        assertEquals("failed", row.status, context)
        assertEquals(ThreadComposerUploadInterruptedErrorCode, row.errorCode, context)
        val pending = store.state.value.pendingItems()
        assertTrue(pending.hasFailedThreadAttachmentUpload(), "$context: the chip offers Retry")
        assertFalse(pending.hasUploadingThreadAttachment(), "$context: nothing shows as uploading")
    }

    private fun pickedImage(): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = PickedId,
            fileName = "180384.jpg",
            mimeType = "image/jpeg",
            status = "uploading",
            fileRef = "cache/180384.jpg",
            source = "platform_picker"
        )

    private fun SharedThreadComposerDraftState.pendingItems(): List<RaftPendingAttachmentItem> =
        uploadedAttachments
            .filterNot { attachment -> attachment.id in removedAttachmentIds }
            .map { attachment ->
                RaftPendingAttachmentItem(
                    id = attachment.id,
                    fileName = attachment.fileName,
                    status = attachment.status,
                    errorCode = attachment.errorCode
                )
            }

    private companion object {
        const val DraftKey = "send=channel-orphan|parent=_|mention=channel-orphan"
        const val PickedId = "picked-180384"
        const val ServerId = "server-180384"
    }
}
