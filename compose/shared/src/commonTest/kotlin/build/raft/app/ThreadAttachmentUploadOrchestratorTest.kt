package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThreadAttachmentUploadOrchestratorTest {
    private val draftA = "draft-a"
    private val draftB = "draft-b"

    @Test
    fun localPreparationFailureDoesNotBeginOrCompleteSend() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = SharedThreadComposerDraftStore(scope = scope)
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val finished = CompletableDeferred<Unit>()
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "preserve draft"))
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(
                    listOf(localAttachment("picked-large"))
                )
            )

            orchestrator.launch("picked-large", draftA) {
                val failedIds = orchestrator.failAttempts(draftA, setOf("picked-large"))
                store.dispatch(
                    SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                        attachmentIds = failedIds,
                        errorCode = "attachment_size_unavailable"
                    )
                )
                finished.complete(Unit)
            }
            finished.await()

            assertEquals("preserve draft", store.state.value.seedText)
            assertFalse(store.state.value.sending, "attachment preparation must not dispatch BeginSend")
            assertEquals(null, store.state.value.failureLabel)
            assertEquals(
                listOf("picked-large"),
                store.state.value.uploadedAttachments.map { it.id }
            )
            assertEquals("failed", store.state.value.uploadedAttachments.single().status)
            assertTrue(store.state.value.uploadedAttachments.none { it.id.startsWith("local-") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun siblingFailureFailsOnlyItsOwnTileAndSiblingStillPublishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = SharedThreadComposerDraftStore(scope = scope)
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val first = ControlledUpload()
            val second = ControlledUpload()
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(
                    listOf(localAttachment("picked-first"), localAttachment("picked-second"))
                )
            )
            launchControlledUpload(orchestrator, store, draftA, "picked-first", first)
            launchControlledUpload(orchestrator, store, draftA, "picked-second", second)

            first.release.complete(ControlledUploadResult.Failure("network_error"))
            first.finished.await()

            assertEquals(
                mapOf("picked-first" to "failed", "picked-second" to "uploading"),
                store.state.value.uploadedAttachments.associate { it.id to it.status }
            )
            assertTrue(orchestrator.isActive("picked-second", draftA))

            second.release.complete(ControlledUploadResult.Success("server-second"))
            second.finished.await()

            assertEquals(
                listOf("picked-first" to "failed", "server-second" to "ready"),
                store.state.value.visibleAttachments().map { it.id to it.status }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disposalAndRemountKeepOldLateCompletionOutOfTheNewDraft() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = SharedThreadComposerDraftStore(scope = scope)
            val oldOrchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val old = ControlledUpload()
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "old draft"))
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localAttachment("picked-old")))
            )
            launchControlledUpload(oldOrchestrator, store, draftA, "picked-old", old)

            oldOrchestrator.dispose()
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = "remounted", seedText = "new draft"),
                    draftKey = draftB
                )
            )
            val newOrchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val fresh = ControlledUpload()
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localAttachment("picked-new")))
            )
            launchControlledUpload(newOrchestrator, store, draftB, "picked-new", fresh)

            fresh.release.complete(ControlledUploadResult.Success("server-new"))
            fresh.finished.await()
            old.release.complete(ControlledUploadResult.Success("server-old"))
            old.finished.await()

            assertEquals("new draft", store.state.value.seedText)
            assertEquals(listOf("server-new"), store.state.value.visibleAttachments().map { it.id })
            assertTrue(store.state.value.uploadedAttachments.none { it.id == "server-old" || it.id == "picked-old" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retryGenerationExclusivelyOwnsTheReadyPublication() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = SharedThreadComposerDraftStore(scope = scope)
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val failedAttempt = ControlledUpload()
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localAttachment("picked-retry")))
            )
            launchControlledUpload(orchestrator, store, draftA, "picked-retry", failedAttempt)

            val failedIds = orchestrator.failAttempts(draftA, setOf("picked-retry"))
            store.dispatch(SharedThreadComposerDraftIntent.MarkAttachmentsFailed(failedIds, "timeout"))
            store.dispatch(SharedThreadComposerDraftIntent.RetryAttachment("picked-retry"))
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localAttachment("picked-retry")))
            )
            val retry = ControlledUpload()
            launchControlledUpload(orchestrator, store, draftA, "picked-retry", retry)

            failedAttempt.release.complete(ControlledUploadResult.Success("server-stale"))
            failedAttempt.finished.await()
            assertEquals(listOf("picked-retry"), store.state.value.uploadedAttachments.map { it.id })

            retry.release.complete(ControlledUploadResult.Success("server-retry"))
            retry.finished.await()
            assertEquals(listOf("server-retry"), store.state.value.visibleAttachments().map { it.id })
            assertTrue(store.state.value.uploadedAttachments.none { it.id == "server-stale" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun draftSwitchRejectsLateProgressSuccessAndFailurePublications() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val oldRelease = CompletableDeferred<Unit>()
            val oldStarted = CompletableDeferred<Long>()
            val oldFinished = CompletableDeferred<Unit>()
            val oldPublications = mutableListOf<String>()
            val newRelease = CompletableDeferred<Unit>()
            val newStarted = CompletableDeferred<Unit>()
            val oldToken =
                requireNotNull(
                    orchestrator.launch("reused-id", draftA) { token ->
                        oldStarted.complete(token)
                        withContext(NonCancellable) {
                            oldRelease.await()
                            if (orchestrator.publishIfAccepted("reused-id", draftA, token) { oldPublications += "progress" }) {
                                oldPublications += "progress-accepted"
                            }
                            if (orchestrator.publishIfAccepted("reused-id", draftA, token) { oldPublications += "success" }) {
                                oldPublications += "success-accepted"
                            }
                            if (orchestrator.publishIfAccepted("reused-id", draftA, token) { oldPublications += "failure" }) {
                                oldPublications += "failure-accepted"
                            }
                            oldFinished.complete(Unit)
                        }
                    }
                )
            assertEquals(oldToken, oldStarted.await())

            assertEquals(setOf("reused-id"), orchestrator.retireDraft(" $draftA "))
            val newToken =
                requireNotNull(
                    orchestrator.launch("reused-id", draftB) { token ->
                        newStarted.complete(Unit)
                        withContext(NonCancellable) {
                            assertTrue(orchestrator.publishIfAccepted("reused-id", draftB, token) {})
                            newRelease.await()
                        }
                    }
                )
            assertNotEquals(oldToken, newToken)
            newStarted.await()

            oldRelease.complete(Unit)
            oldFinished.await()
            assertTrue(oldPublications.isEmpty(), "late A callbacks must not publish after A -> B")
            assertTrue(orchestrator.isActive("reused-id", draftB))
            newRelease.complete(Unit)
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retireDraftOnlyCancelsDraftAAndLeavesSameIdDraftBActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val aRelease = CompletableDeferred<Unit>()
            val bRelease = CompletableDeferred<Unit>()
            val aStarted = CompletableDeferred<Unit>()
            val bStarted = CompletableDeferred<Unit>()
            val aFinished = CompletableDeferred<Unit>()
            val bFinished = CompletableDeferred<Unit>()
            orchestrator.launch("same-id", draftA) {
                aStarted.complete(Unit)
                withContext(NonCancellable) {
                    aRelease.await()
                    aFinished.complete(Unit)
                }
            }
            orchestrator.launch("same-id", draftB) {
                bStarted.complete(Unit)
                withContext(NonCancellable) {
                    bRelease.await()
                    bFinished.complete(Unit)
                }
            }
            aStarted.await()
            bStarted.await()

            assertEquals(setOf("same-id"), orchestrator.retireDraft(draftA))
            assertFalse(orchestrator.isActive("same-id", draftA))
            assertTrue(orchestrator.isActive("same-id", draftB))

            bRelease.complete(Unit)
            bFinished.await()
            aRelease.complete(Unit)
            aFinished.await()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nullOrBlankDraftKeyCannotStartOrPublishAnAttempt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            assertEquals(null, orchestrator.launch("id", null) {})
            assertEquals(null, orchestrator.launch("id", "  ") {})
            assertFalse(orchestrator.publishIfAccepted("id", null, 1L) {})
            assertFalse(orchestrator.publishIfAccepted("id", "  ", 1L) {})
            assertTrue(orchestrator.activeAttemptIds.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledBeforeStartSignalsCompletionAndRetiresAdmission() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        try {
            parent.cancel()
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val completion = CompletableDeferred<Unit>()
            var blockRan = false

            val token =
                orchestrator.launch(
                    localAttachmentId = "cancelled-before-start",
                    draftKey = draftA,
                    onCompletion = { completion.complete(Unit) },
                    block = { blockRan = true }
                )

            assertNotEquals(null, token)
            withTimeout(1_000L) { completion.await() }
            assertFalse(blockRan, "a cancelled LAZY child must not enter its upload block")
            assertTrue(
                orchestrator.activeAttemptIds(draftA).isEmpty(),
                "completion handling must retire the admitted token even without block finally"
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun admissionFailureSignalsCompletionExactlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            var completionCount = 0

            val token =
                orchestrator.launch(
                    localAttachmentId = "admission-failure",
                    draftKey = "  ",
                    onCompletion = { completionCount += 1 },
                    block = { error("an inadmissible attempt must not run") }
                )

            assertEquals(null, token)
            assertEquals(1, completionCount)
            assertTrue(orchestrator.activeAttemptIds.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun launchControlledUpload(
        orchestrator: ThreadAttachmentUploadOrchestrator,
        store: SharedThreadComposerDraftStore,
        draftKey: String,
        localAttachmentId: String,
        controlled: ControlledUpload
    ) {
        orchestrator.launch(localAttachmentId, draftKey) { attemptToken ->
            withContext(NonCancellable) {
                when (val result = controlled.release.await()) {
                    is ControlledUploadResult.Success ->
                        orchestrator.publishIfAccepted(localAttachmentId, draftKey, attemptToken) {
                            store.dispatch(
                                SharedThreadComposerDraftIntent.AddUploadedAttachments(
                                    attachments = listOf(readyAttachment(result.serverAttachmentId)),
                                    replacedAttachmentIds = setOf(localAttachmentId),
                                    replacedAttachmentIdsByUploadedId =
                                        mapOf(result.serverAttachmentId to localAttachmentId)
                                )
                            )
                        }
                    is ControlledUploadResult.Failure -> {
                        val failedIds = orchestrator.failAttempts(draftKey, setOf(localAttachmentId))
                        store.dispatch(
                            SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                                attachmentIds = failedIds,
                                errorCode = result.errorCode
                            )
                        )
                    }
                }
                controlled.finished.complete(Unit)
            }
        }
    }

    private fun localAttachment(id: String): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = id,
            fileName = "$id.png",
            sizeLabel = "1 KB",
            status = "uploading"
        )

    private fun readyAttachment(id: String): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = id,
            fileName = "$id.png",
            sizeLabel = "1 KB",
            status = "ready"
        )

    private fun List<SharedThreadComposerAttachment>.toPendingItems(): List<RaftPendingAttachmentItem> =
        map { attachment ->
            RaftPendingAttachmentItem(
                id = attachment.id,
                fileName = attachment.fileName,
                status = attachment.status,
                errorCode = attachment.errorCode
            )
        }

    private fun SharedThreadComposerDraftState.visibleAttachments(): List<SharedThreadComposerAttachment> =
        uploadedAttachments.filterNot { attachment -> attachment.id in removedAttachmentIds }

    private data class ControlledUpload(
        val release: CompletableDeferred<ControlledUploadResult> = CompletableDeferred(),
        val finished: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private sealed interface ControlledUploadResult {
        data class Success(val serverAttachmentId: String) : ControlledUploadResult
        data class Failure(val errorCode: String) : ControlledUploadResult
    }
}
