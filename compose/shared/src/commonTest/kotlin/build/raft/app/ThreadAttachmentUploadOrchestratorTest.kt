package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadAttachmentUploadOrchestratorTest {
    @Test
    fun siblingFailureMarksEveryUnfinishedTileAndDropsCancelledLateCompletion() = runBlocking {
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
            launchControlledUpload(orchestrator, store, "picked-first", first)
            launchControlledUpload(orchestrator, store, "picked-second", second)

            first.release.complete(ControlledUploadResult.Failure("network_error"))
            first.finished.await()
            val failedIds = setOf("picked-first", "picked-second")

            assertEquals(setOf("picked-first", "picked-second"), failedIds)
            assertTrue(store.state.value.uploadedAttachments.all { it.status == "failed" })
            assertFalse(store.state.value.uploadedAttachments.toPendingItems().hasUploadingThreadAttachment())

            second.release.complete(ControlledUploadResult.Success("server-second"))
            second.finished.await()

            assertEquals(
                listOf("picked-first", "picked-second"),
                store.state.value.uploadedAttachments.map { it.id }
            )
            assertTrue(store.state.value.uploadedAttachments.none { it.id == "server-second" })

            store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment("picked-first"))
            store.dispatch(SharedThreadComposerDraftIntent.RetryAttachment("picked-second"))
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(
                    listOf(localAttachment("picked-second"))
                )
            )
            assertTrue("picked-first" in store.state.value.removedAttachmentIds)
            assertEquals("uploading", store.state.value.uploadedAttachments.single { it.id == "picked-second" }.status)
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
            launchControlledUpload(oldOrchestrator, store, "picked-old", old)

            oldOrchestrator.dispose()
            store.dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = KuiklyThreadComposerPayload(instanceId = "remounted", seedText = "new draft")
                )
            )
            val newOrchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val fresh = ControlledUpload()
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localAttachment("picked-new")))
            )
            launchControlledUpload(newOrchestrator, store, "picked-new", fresh)

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
            launchControlledUpload(orchestrator, store, "picked-retry", failedAttempt)

            val failedIds = orchestrator.failActive(setOf("picked-retry"))
            store.dispatch(SharedThreadComposerDraftIntent.MarkAttachmentsFailed(failedIds, "timeout"))
            store.dispatch(SharedThreadComposerDraftIntent.RetryAttachment("picked-retry"))
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(localAttachment("picked-retry")))
            )
            val retry = ControlledUpload()
            launchControlledUpload(orchestrator, store, "picked-retry", retry)

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

    private fun launchControlledUpload(
        orchestrator: ThreadAttachmentUploadOrchestrator,
        store: SharedThreadComposerDraftStore,
        localAttachmentId: String,
        controlled: ControlledUpload
    ) {
        orchestrator.launch(localAttachmentId) { attemptToken ->
            withContext(NonCancellable) {
                when (val result = controlled.release.await()) {
                    is ControlledUploadResult.Success ->
                        orchestrator.publishIfAccepted(localAttachmentId, attemptToken) {
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
                        val failedIds = orchestrator.failActive(setOf(localAttachmentId))
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

    private fun List<SharedThreadComposerAttachment>.toPendingItems(): List<KuiklyPendingAttachmentItem> =
        map { attachment ->
            KuiklyPendingAttachmentItem(
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
