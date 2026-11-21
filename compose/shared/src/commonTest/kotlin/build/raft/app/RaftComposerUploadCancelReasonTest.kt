package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftAttachmentUploadAttempt
import build.raft.app.data.RaftAttachmentUploadCancelReason
import build.raft.app.data.RaftUploadCancelSiteOrphanRecovery
import build.raft.app.data.RaftUploadCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `slock.attachment.upload_finished`'s `cancel_reason`, end to end through the draft-owned upload
 * owner: each place that cancels an upload's job says why, and the upload task's catch
 * ([RaftAttachmentUploadAttempt.reportTaskCancellation], as `ThreadRouteAttachmentUploader`) reads it.
 * One event per attempt either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftComposerUploadCancelReasonTest {
    private class Reported {
        val events = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()

        fun single(): JsonObject = events.single()
    }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content

    @Test
    fun removingTheTileMidUploadIsUserRemoved() = runTest {
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val reported = startUpload(uploads, DraftA, PickedId)
        runCurrent()

        uploads.remove(DraftA, PickedId)
        runCurrent()

        assertEquals("cancelled", reported.single().text("outcome"))
        assertEquals("user_removed", reported.single().text("cancel_reason"))
        assertEquals(1, reported.logs.size, "logs=${reported.logs}")
    }

    @Test
    fun retiringTheDraftSaysWhetherItWasSentOrCleared() = runTest {
        listOf(RaftAttachmentUploadCancelReason.DraftSent, RaftAttachmentUploadCancelReason.DraftCleared).forEach { reason ->
            val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
            val first = startUpload(uploads, DraftA, PickedId)
            val second = startUpload(uploads, DraftA, OtherPickedId)
            val elsewhere = startUpload(uploads, DraftB, PickedId)
            runCurrent()

            uploads.retireDraft(DraftA, reason)
            runCurrent()

            assertEquals(reason.wire, first.single().text("cancel_reason"), "$reason")
            assertEquals(reason.wire, second.single().text("cancel_reason"), "$reason")
            assertTrue(elsewhere.events.isEmpty(), "$reason: another draft's upload keeps going")
            uploads.dispose()
            runCurrent()
        }
    }

    @Test
    fun signOutAndLosingTheServerAreSessionEnded() = runTest {
        val owner = RaftComposerDraftUploads(backgroundScope)
        val signedOut = startUpload(owner.forOwner(AccountA, ServerA), DraftA, PickedId)
        val otherServer = startUpload(owner.forOwner(AccountA, ServerB), DraftB, PickedId)
        runCurrent()
        owner.cancelAll()
        runCurrent()
        assertEquals("session_ended", signedOut.single().text("cancel_reason"))
        assertEquals("session_ended", otherServer.single().text("cancel_reason"))

        val lostServer = startUpload(owner.forOwner(AccountA, ServerA), DraftA, PickedId)
        val keptServer = startUpload(owner.forOwner(AccountA, ServerB), DraftB, PickedId)
        runCurrent()
        owner.dropServer(ServerA)
        runCurrent()
        assertEquals("session_ended", lostServer.single().text("cancel_reason"))
        assertTrue(keptServer.events.isEmpty(), "the other server's upload keeps going")
    }

    @Test
    fun aCancelWithoutAReasonIsOtherWithOneLineNamingWhereItCameFrom() = runTest {
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)

        // A site that cancels for none of the four reasons names itself.
        val orphaned = startUpload(uploads, DraftA, PickedId)
        runCurrent()
        uploads.orchestrator.remove(
            PickedId,
            DraftA,
            RaftUploadCancelled(RaftAttachmentUploadCancelReason.Other, RaftUploadCancelSiteOrphanRecovery),
        )
        runCurrent()
        assertEquals("other", orphaned.single().text("cancel_reason"))
        assertEquals(2, orphaned.logs.size, "logs=${orphaned.logs}")
        assertTrue(orphaned.logs[1].startsWith("thread_composer_attachment_upload_cancel_other site=orphan_recovery traceId="), orphaned.logs[1])

        val failed = startUpload(uploads, DraftA, OtherPickedId)
        runCurrent()
        uploads.orchestrator.failAttempts(DraftA, setOf(OtherPickedId))
        runCurrent()
        assertEquals("other", failed.single().text("cancel_reason"))
        assertTrue(failed.logs[1].contains(" site=fail_attempts "), failed.logs[1])

        // The job cancelled with no reason at all (here: its scope).
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val scoped = RaftComposerServerUploads(AccountA, ServerA, scope, Semaphore(4))
        val untagged = startUpload(scoped, DraftA, PickedId)
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals("cancelled", untagged.single().text("outcome"))
        assertEquals("other", untagged.single().text("cancel_reason"))
        assertEquals(2, untagged.logs.size, "logs=${untagged.logs}")
        assertTrue(untagged.logs[1].contains(" site=untagged "), untagged.logs[1])
    }

    @Test
    fun aCancellationFromBelowIsAnErrorWithoutAReason() = runTest {
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val reported = startUpload(uploads, DraftA, PickedId) { throw CancellationException("transport retired") }
        runCurrent()

        assertEquals("error", reported.single().text("outcome"))
        assertEquals("upload_interrupted", reported.single().text("error_code"))
        assertNull(reported.single()["cancel_reason"])
        assertEquals(1, reported.logs.size, "logs=${reported.logs}")
    }

    @Test
    fun aFinishedUploadThenRemovedStaysOkWithOneEvent() = runTest {
        val uploads = RaftComposerDraftUploads(backgroundScope).forOwner(AccountA, ServerA)
        val reported = startUpload(uploads, DraftA, PickedId) { DataResult.Content(Unit) }
        runCurrent()

        uploads.remove(DraftA, PickedId)
        runCurrent()

        assertEquals("ok", reported.single().text("outcome"))
        assertNull(reported.single()["cancel_reason"])
    }

    /** The upload task as `ThreadRouteAttachmentUploader` runs it: settle inside the context, report once, the catch reads the cancel. */
    private fun startUpload(
        uploads: RaftComposerServerUploads,
        draftKey: String,
        localId: String,
        upload: suspend () -> DataResult<*> = { awaitCancellation() },
    ): Reported {
        val reported = Reported()
        val row = picked(localId)
        val token =
            uploads.launch(draftKey, row, row.toFile(), admissionFor(localId)) { _ ->
                val attempt =
                    RaftAttachmentUploadAttempt(
                        serverId = uploads.serverId,
                        attempt = 1,
                        pickedSizeBytes = row.sizeBytes,
                        elapsedMs = { 250L },
                        log = { line -> reported.logs += line },
                        emit = { _, attrs, _, _ -> reported.events += attrs },
                    )
                try {
                    withContext(attempt.recorder) { upload().also(attempt::settle) }
                    attempt.report()
                } catch (cancellation: CancellationException) {
                    attempt.reportTaskCancellation(cancellation)
                }
            }
        assertNotNull(token, "the upload starts")
        return reported
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

    private companion object {
        const val AccountA = "account-a"
        const val ServerA = "server-a"
        const val ServerB = "server-b"
        const val DraftA = "send=channel-a|parent=_|mention=channel-a"
        const val DraftB = "send=channel-b|parent=_|mention=channel-b"
        const val PickedId = "picked-180384"
        const val OtherPickedId = "picked-180385"
    }
}
