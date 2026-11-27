package build.raft.app

import build.raft.app.thread.dispatch
import build.raft.app.thread.threadComposerAggregateReceiptIsValid
import build.raft.app.transfer.testUploadTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression coverage for strict Gallery ownership and receipt validation. */
class ThreadComposerAggregateReceiptTest {
    private val draftKey = "send=gallery-channel|parent=_|mention=gallery-channel"

    @Test
    fun validAggregateReplacesExactEditorSlotsAndPreservesUnrelatedRows() {
        val store = mountedStore()
        val remoteImage = ready("remote-image")
        val unrelated = ready("unrelated")
        val local = local("edited-local", "file:///cache/edited.png", "composer_image_editor")
        store.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(remoteImage, unrelated)))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddLocalAttachments(
                attachments = listOf(local),
                replacementAttachmentIdsByLocalId = mapOf(local.id to remoteImage.id)
            )
        )
        val admission = requireNotNull(store.state.value.attachmentAdmissionsByLocalId[local.id])
        val before = store.state.value
        val replacement = ready("server-edited")

        val receipt =
            store.acceptWithState(
                strictUpload(
                    local = local,
                    admission = admission,
                    replacementSlot = remoteImage.id,
                    uploaded = listOf(replacement)
                )
            )

        assertTrue(receipt.accepted)
        assertTrue(
            threadComposerAggregateReceiptIsValid(
                accepted = receipt.accepted,
                before = before,
                after = receipt.state,
                expectedDraftKey = draftKey,
                readyAttachments = listOf(replacement),
                expectedLocalAttachmentIds = listOf(local.id),
                expectedReplacementAttachmentIds = listOf(remoteImage.id)
            )
        )
        assertEquals(
            listOf(replacement.id, unrelated.id),
            receipt.state.uploadedAttachments
                .filterNot { it.id in receipt.state.removedAttachmentIds }
                .map { it.id }
        )
    }

    @Test
    fun editorCompletionForWrongRemoteSlotIsRejected() {
        val store = mountedStore()
        val remoteA = ready("remote-a")
        val remoteB = ready("remote-b")
        val local = local("edited", "file:///cache/edited.png", "composer_image_editor")
        store.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(remoteA, remoteB)))
        store.dispatch(
            SharedThreadComposerDraftIntent.AddLocalAttachments(
                attachments = listOf(local),
                replacementAttachmentIdsByLocalId = mapOf(local.id to remoteA.id)
            )
        )
        val admission = requireNotNull(store.state.value.attachmentAdmissionsByLocalId[local.id])
        val before = store.state.value

        val receipt =
            store.acceptWithState(
                strictUpload(
                    local = local,
                    admission = admission,
                    replacementSlot = remoteB.id,
                    uploaded = listOf(ready("server-edited"))
                )
            )

        assertFalse(receipt.accepted)
        assertEquals(before, receipt.state)
        assertEquals(setOf(remoteA.id, remoteB.id, local.id), before.uploadedAttachments.map { it.id }.toSet())
    }

    @Test
    fun remountAtoBRejectsLateCallbacksEvenWhenLocalIdIsReused() {
        val store = mountedStore(instanceId = "composer-a")
        val old = local("same-id", "file:///cache/a.png", "platform_picker")
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(old)))
        val oldAdmission = requireNotNull(store.state.value.attachmentAdmissionsByLocalId[old.id])
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = "composer-b"),
                draftKey = "send=other-channel|parent=_|mention=other-channel"
            )
        )
        val newKey = requireNotNull(store.state.value.draftKey)
        val fresh = local("same-id", "file:///cache/b.png", "platform_camera")
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(fresh)))
        val before = store.state.value

        val staleFailure =
            store.acceptWithState(
                SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                    attachmentIds = setOf(old.id),
                    errorCode = "late",
                    expectedDraftKey = draftKey,
                    expectedAdmissions = mapOf(old.id to oldAdmission)
                )
            )
        val staleProgress =
            store.acceptWithState(
                SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress(
                    attachmentId = old.id,
                    transfer = testUploadTransfer(50L, 100L),
                    expectedDraftKey = draftKey,
                    expectedAdmission = oldAdmission
                )
            )
        val staleSuccess =
            store.acceptWithState(
                strictUpload(
                    local = old,
                    admission = oldAdmission,
                    replacementSlot = old.id,
                    uploaded = listOf(ready("server-a"))
                )
            )

        assertEquals("send=other-channel|parent=_|mention=other-channel", newKey)
        assertFalse(staleFailure.accepted)
        assertFalse(staleProgress.accepted)
        assertFalse(staleSuccess.accepted)
        assertEquals(before, store.state.value)
        assertEquals("file:///cache/b.png", store.state.value.uploadedAttachments.single().fileRef)
    }

    private fun mountedStore(instanceId: String = "composer"): SharedThreadComposerDraftStore {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = RaftThreadComposerPayload(instanceId = instanceId),
                draftKey = draftKey
            )
        )
        return store
    }

    private fun local(id: String, ref: String, source: String) =
        SharedThreadComposerAttachment(
            id = id,
            fileName = "$id.png",
            mimeType = "image/png",
            status = "uploading",
            fileRef = ref,
            source = source
        )

    private fun ready(id: String) =
        SharedThreadComposerAttachment(
            id = id,
            fileName = "$id.jpg",
            mimeType = "image/jpeg",
            status = "ready"
        )

    private fun strictUpload(
        local: SharedThreadComposerAttachment,
        admission: SharedThreadComposerAttachmentAdmission,
        replacementSlot: String,
        uploaded: List<SharedThreadComposerAttachment>
    ) =
        SharedThreadComposerDraftIntent.AddUploadedAttachments(
            attachments = uploaded,
            replacedAttachmentIds = setOf(local.id),
            replacedAttachmentIdsByUploadedId = uploaded.associate { it.id to replacementSlot },
            expectedDraftKey = draftKey,
            expectedAdmissions = mapOf(local.id to admission),
            expectedServerAttachmentIdsByLocalId = mapOf(local.id to uploaded.map { it.id }.toSet()),
            expectedReplacementAttachmentIdsByLocalId = mapOf(local.id to replacementSlot)
        )
}
