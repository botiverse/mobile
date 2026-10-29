package build.raft.app

import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadComposerDirectUploadDecision
import build.raft.app.data.resolveThreadComposerDirectUpload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release SOP "oversize-sibling admission": a small image and an image above the server limit are
 * picked together with "Original image" on. Each file must be admitted or rejected on its own:
 * the oversize file fails with the existing too-large error and the small sibling still uploads.
 *
 * 1.17.0/1.17.1 regression: the oversize failure retired every active attempt in the draft
 * (cancelling the small upload) and sent a two-id MarkAttachmentsFailed carrying one admission,
 * which the strict reducer rejected, so both chips stayed on "Checking file…".
 *
 * Drives the production orchestrator, size resolution and reducer the way ThreadPage's per-file
 * upload task does; the composable wiring itself is not composed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadComposerOversizeSiblingAdmissionTest {
    private val draftKey = "send=oversize-channel|parent=_|mention=oversize-channel"
    private val serverMaxBytes = 52_428_800L

    /** Fake platform file source: the authority-bound stat that production `prepare` performs. */
    private val statSizesByRef =
        mapOf(
            "content://picker/small.png" to 2_789L,
            "content://picker/oversize.png" to 57_671_680L,
        )

    private fun prepareFromFakeSource(file: PlatformFile): DataResult<PlatformFile> {
        val size =
            statSizesByRef[file.fileRef]
                ?: return DataResult.Error(ScreenError(code = "attachment_size_unavailable", retryable = false))
        return DataResult.Content(file.copy(sizeBytes = size))
    }

    @Test
    fun oversizeOriginalImageIsRejectedWhileSmallSiblingStillUploads() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = mountedStore()
            val orchestrator = ThreadAttachmentUploadOrchestrator(scope)
            val capabilities =
                AttachmentUploadCapabilities(
                    maxBytes = serverMaxBytes,
                    directUploadEnabled = true,
                    directUploadThresholdBytes = serverMaxBytes,
                )
            val small = picked("picked-small", "content://picker/small.png")
            val oversize = picked("picked-oversize", "content://picker/oversize.png")
            store.dispatch(
                SharedThreadComposerDraftIntent.AddLocalAttachments(
                    attachments = listOf(small.toLocalRow(), oversize.toLocalRow()),
                    expectedDraftKey = draftKey,
                )
            )
            val admissions = store.state.value.attachmentAdmissionsByLocalId
            assertEquals(setOf(small.id, oversize.id), admissions.keys)

            val smallTransferRelease = CompletableDeferred<Unit>()
            val decisions = mutableMapOf<String, ThreadComposerDirectUploadDecision>()
            val failureMarksAccepted = mutableMapOf<String, Boolean>()
            val successAccepted = mutableMapOf<String, Boolean>()

            for (file in listOf(small, oversize)) {
                val admission = requireNotNull(admissions[file.id])
                orchestrator.launch(file.id, draftKey) { attemptToken ->
                    val resolved =
                        resolveThreadComposerDirectUpload(
                            file = file,
                            capabilities = capabilities,
                            cancelled = false,
                            prepare = ::prepareFromFakeSource,
                        )
                    val decision =
                        (resolved as DataResult.Content).data.decision.also { decisions[file.id] = it }
                    val result: DataResult<String> =
                        when (decision) {
                            ThreadComposerDirectUploadDecision.TooLarge ->
                                DataResult.Error(ScreenError(code = "UPLOAD_TOO_LARGE", retryable = false))
                            else -> {
                                // Fake multipart transfer: still in flight when the sibling fails.
                                smallTransferRelease.await()
                                DataResult.Content("server-${file.id}")
                            }
                        }
                    orchestrator.publishIfAccepted(file.id, draftKey, attemptToken) {
                        when (result) {
                            is DataResult.Content ->
                                successAccepted[file.id] =
                                    store.accept(strictUpload(file.id, admission, result.data))
                            is DataResult.Error -> {
                                // Same sequence as ThreadPage.handleAttachmentUploadFailure.
                                val terminalIds = orchestrator.failAttempts(draftKey, setOf(file.id))
                                failureMarksAccepted[file.id] =
                                    store.accept(
                                        SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                                            attachmentIds = terminalIds,
                                            errorCode = result.error.code,
                                            expectedDraftKey = draftKey,
                                            // Production passes only this attempt's admission.
                                            expectedAdmissions =
                                                terminalIds.mapNotNull { id -> mapOf(file.id to admission)[id] }
                                                    .associateBy { it.localAttachmentId },
                                        )
                                    )
                            }
                            else -> Unit
                        }
                    }
                }
            }

            assertEquals(ThreadComposerDirectUploadDecision.TooLarge, decisions[oversize.id])
            assertEquals(ThreadComposerDirectUploadDecision.LegacyMultipart, decisions[small.id])
            assertEquals(true, failureMarksAccepted[oversize.id], "oversize failure must reach the reducer")
            val oversizeRow = store.state.value.uploadedAttachments.single { it.id == oversize.id }
            assertEquals("failed", oversizeRow.status)
            assertEquals("UPLOAD_TOO_LARGE", oversizeRow.errorCode)
            assertEquals(
                ThreadUserFacingErrorReason.AttachmentTooLarge,
                threadUserFacingErrorCodeReason(oversizeRow.errorCode),
                "the oversize file keeps the existing too-large message",
            )
            assertEquals(
                "uploading",
                store.state.value.uploadedAttachments.single { it.id == small.id }.status,
                "the oversize sibling must not fail or strand the small file",
            )
            assertTrue(orchestrator.isActive(small.id, draftKey), "the small upload must keep running")

            smallTransferRelease.complete(Unit)

            assertEquals(true, successAccepted[small.id], "the small file's upload result must be published")
            val visible =
                store.state.value.uploadedAttachments.filterNot { it.id in store.state.value.removedAttachmentIds }
            assertEquals(
                setOf("server-${small.id}" to "ready", oversize.id to "failed"),
                visible.map { it.id to it.status }.toSet(),
            )
        } finally {
            scope.cancel()
        }
    }

    private fun mountedStore(): SharedThreadComposerDraftStore {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer"),
                draftKey = draftKey
            )
        )
        return store
    }

    private fun picked(id: String, ref: String) =
        PlatformFile(
            id = id,
            fileName = "$id.png",
            mimeType = "image/png",
            sizeBytes = null,
            fileRef = ref,
            source = "composer_image_editor",
        )

    private fun PlatformFile.toLocalRow() =
        SharedThreadComposerAttachment(
            id = id,
            fileName = fileName,
            mimeType = mimeType,
            status = "uploading",
            fileRef = fileRef,
            source = source,
        )

    private fun strictUpload(
        localId: String,
        admission: SharedThreadComposerAttachmentAdmission,
        serverId: String,
    ) =
        SharedThreadComposerDraftIntent.AddUploadedAttachments(
            attachments =
                listOf(
                    SharedThreadComposerAttachment(
                        id = serverId,
                        fileName = "$localId.png",
                        mimeType = "image/png",
                        status = "ready",
                    )
                ),
            replacedAttachmentIds = setOf(localId),
            replacedAttachmentIdsByUploadedId = mapOf(serverId to localId),
            expectedDraftKey = draftKey,
            expectedAdmissions = mapOf(localId to admission),
            expectedServerAttachmentIdsByLocalId = mapOf(localId to setOf(serverId)),
            expectedReplacementAttachmentIdsByLocalId = mapOf(localId to localId),
        )
}
