package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftAttachmentUploadAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
    }
}
