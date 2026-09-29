package build.raft.app

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
    fun removedGallerySiblingRejectsWholeAggregateWithoutServerProjection() {
        val store = mountedStore()
        val first = local("local-first", "file:///cache/first.png", "platform_picker")
        val second = local("local-second", "file:///cache/second.png", "platform_picker")
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(first, second)))
        val admissions = store.state.value.attachmentAdmissionsByLocalId
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(second.id))
        val before = store.state.value

        val receipt =
            store.acceptWithState(
                SharedThreadComposerDraftIntent.AddUploadedAttachments(
                    attachments = listOf(ready("server-first"), ready("server-second")),
                    replacedAttachmentIds = setOf(first.id, second.id),
                    replacedAttachmentIdsByUploadedId =
                        mapOf("server-first" to first.id, "server-second" to second.id),
                    expectedDraftKey = draftKey,
                    expectedAdmissions = admissions,
                    expectedServerAttachmentIdsByLocalId =
                        mapOf(first.id to setOf("server-first"), second.id to setOf("server-second")),
                    expectedReplacementAttachmentIdsByLocalId =
                        mapOf(first.id to first.id, second.id to second.id)
                )
            )

        assertFalse(receipt.accepted)
        assertEquals(before, receipt.state)
        assertTrue(receipt.state.uploadedAttachments.none { it.id.startsWith("server-") })
    }

    @Test
    fun removeReaddSameIdRejectsOldSuccessFailureAndProgressWitnesses() {
        val store = mountedStore()
        val old = local("reused", "file:///cache/old.png", "platform_picker")
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(old)))
        val oldAdmission = requireNotNull(store.state.value.attachmentAdmissionsByLocalId[old.id])
        store.dispatch(SharedThreadComposerDraftIntent.RemoveAttachment(old.id))
        val fresh = local("reused", "file:///cache/new.png", "platform_camera")
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(fresh)))
        val before = store.state.value

        val staleSuccess =
            store.acceptWithState(
                strictUpload(
                    local = old,
                    admission = oldAdmission,
                    replacementSlot = old.id,
                    uploaded = listOf(ready("server-old"))
                )
            )
        val staleFailure =
            store.acceptWithState(
                SharedThreadComposerDraftIntent.MarkAttachmentsFailed(
                    attachmentIds = setOf(old.id),
                    errorCode = "stale",
                    expectedDraftKey = draftKey,
                    expectedAdmissions = mapOf(old.id to oldAdmission)
                )
            )
        val staleProgress =
            store.acceptWithState(
                SharedThreadComposerDraftIntent.UpdateAttachmentUploadProgress(
                    attachmentId = old.id,
                    bytesTransferred = 99L,
                    bytesTotal = 100L,
                    expectedDraftKey = draftKey,
                    expectedAdmission = oldAdmission
                )
            )

        assertFalse(staleSuccess.accepted)
        assertFalse(staleFailure.accepted)
        assertFalse(staleProgress.accepted)
        assertEquals(before, store.state.value)
        assertEquals("file:///cache/new.png", store.state.value.uploadedAttachments.single().fileRef)
        assertEquals("platform_camera", store.state.value.uploadedAttachments.single().source)
    }

    @Test
    fun remountAtoBRejectsLateCallbacksEvenWhenLocalIdIsReused() {
        val store = mountedStore(instanceId = "composer-a")
        val old = local("same-id", "file:///cache/a.png", "platform_picker")
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(old)))
        val oldAdmission = requireNotNull(store.state.value.attachmentAdmissionsByLocalId[old.id])
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = "composer-b"),
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
                    bytesTransferred = 50L,
                    bytesTotal = 100L,
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

    @Test
    fun strictReceiptRejectsMissingSubsetDuplicateAndUnexpectedIds() {
        val store = mountedStore()
        val first = local("first", "file:///cache/first.png", "platform_picker")
        val second = local("second", "file:///cache/second.png", "platform_picker")
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(first, second)))
        val admissions = store.state.value.attachmentAdmissionsByLocalId
        val before = store.state.value
        val expected = mapOf(first.id to setOf("server-first"), second.id to setOf("server-second"))
        val replacements = mapOf(first.id to first.id, second.id to second.id)

        fun attempt(attachments: List<SharedThreadComposerAttachment>, ids: Map<String, Set<String>>) =
            store.acceptWithState(
                SharedThreadComposerDraftIntent.AddUploadedAttachments(
                    attachments = attachments,
                    replacedAttachmentIds = setOf(first.id, second.id),
                    replacedAttachmentIdsByUploadedId =
                        attachments.associate { it.id to if (it.id == "server-first") first.id else second.id },
                    expectedDraftKey = draftKey,
                    expectedAdmissions = admissions,
                    expectedServerAttachmentIdsByLocalId = ids,
                    expectedReplacementAttachmentIdsByLocalId = replacements
                )
            )

        val missing = attempt(listOf(ready("server-first")), expected)
        val subset = attempt(listOf(ready("server-first")), mapOf(first.id to setOf("server-first"), second.id to emptySet()))
        val duplicate =
            attempt(
                listOf(ready("server-first"), ready("server-first")),
                mapOf(first.id to setOf("server-first"), second.id to setOf("server-first"))
            )
        val extra =
            attempt(
                listOf(ready("server-first"), ready("server-second"), ready("server-extra")),
                mapOf(first.id to setOf("server-first"), second.id to setOf("server-second"))
            )

        assertFalse(missing.accepted)
        assertFalse(subset.accepted)
        assertFalse(duplicate.accepted)
        assertFalse(extra.accepted)
        assertEquals(before, store.state.value)
    }

    @Test
    fun receiptHelperRejectsUnexpectedNewRowButAllowsExistingUnrelatedRow() {
        val store = mountedStore()
        val local = local("local", "file:///cache/local.png", "platform_picker")
        val unrelated = ready("existing")
        store.dispatch(SharedThreadComposerDraftIntent.AddUploadedAttachments(listOf(unrelated)))
        store.dispatch(SharedThreadComposerDraftIntent.AddLocalAttachments(listOf(local)))
        val admission = requireNotNull(store.state.value.attachmentAdmissionsByLocalId[local.id])
        val before = store.state.value
        val valid = ready("server")
        val receipt =
            store.acceptWithState(
                strictUpload(local, admission, local.id, listOf(valid))
            )
        assertTrue(
            threadComposerAggregateReceiptIsValid(
                accepted = receipt.accepted,
                before = before,
                after = receipt.state,
                expectedDraftKey = draftKey,
                readyAttachments = listOf(valid),
                expectedLocalAttachmentIds = listOf(local.id),
                expectedReplacementAttachmentIds = listOf(local.id)
            )
        )

        val malformedAfter =
            receipt.state.copy(
                uploadedAttachments = receipt.state.uploadedAttachments + ready("unexpected")
            )
        assertFalse(
            threadComposerAggregateReceiptIsValid(
                accepted = true,
                before = before,
                after = malformedAfter,
                expectedDraftKey = draftKey,
                readyAttachments = listOf(valid),
                expectedLocalAttachmentIds = listOf(local.id),
                expectedReplacementAttachmentIds = listOf(local.id)
            )
        )
    }

    @Test
    fun localAdmissionReceiptRejectsUnexpectedNewUploadedRow() {
        val store = mountedStore()
        val local = local("local", "file:///cache/local.png", "platform_picker")
        val before = store.state.value
        val admissionIntent =
            SharedThreadComposerDraftIntent.AddLocalAttachments(
                attachments = listOf(local),
                replacementAttachmentIdsByLocalId = mapOf(local.id to local.id)
            )
        val admissionReceipt = store.acceptWithState(admissionIntent)
        assertTrue(admissionReceipt.accepted)
        val accepted = admissionReceipt.state.uploadedAttachments.single { it.id == local.id }
        assertEquals("uploading", accepted.status)
        val malformedAfter =
            admissionReceipt.state.copy(
                uploadedAttachments = admissionReceipt.state.uploadedAttachments + ready("forged")
            )
        assertFalse(
            threadComposerAttachmentAdmissionReceiptIsValid(
                accepted = true,
                before = before,
                after = malformedAfter,
                expectedDraftKey = draftKey,
                localAttachments = listOf(local),
                replacementAttachmentIdsByLocalId = mapOf(local.id to local.id)
            )
        )
    }

    @Test
    fun localAdmissionReceiptRejectsDuplicateReplacementSlotsAndNormalizedAdmissionKeys() {
        val store = mountedStore()
        val first = local("first", "file:///cache/first.png", "platform_picker")
        val second = local("second", "file:///cache/second.png", "platform_picker")
        val before = store.state.value
        val receipt =
            store.acceptWithState(
                SharedThreadComposerDraftIntent.AddLocalAttachments(
                    attachments = listOf(first, second),
                    replacementAttachmentIdsByLocalId = mapOf(first.id to first.id, second.id to second.id)
                )
            )
        assertTrue(receipt.accepted)
        val duplicateSlots =
            threadComposerAttachmentAdmissionReceiptIsValid(
                accepted = true,
                before = before,
                after = receipt.state,
                expectedDraftKey = draftKey,
                localAttachments = listOf(first, second),
                replacementAttachmentIdsByLocalId = mapOf(first.id to "slot", second.id to "slot")
            )
        assertFalse(duplicateSlots)

        val malformedKeys =
            receipt.state.copy(
                attachmentAdmissionsByLocalId =
                    receipt.state.attachmentAdmissionsByLocalId +
                        (" ${first.id} " to receipt.state.attachmentAdmissionsByLocalId.getValue(first.id))
            )
        assertFalse(
            threadComposerAttachmentAdmissionReceiptIsValid(
                accepted = true,
                before = before,
                after = malformedKeys,
                expectedDraftKey = draftKey,
                localAttachments = listOf(first, second),
                replacementAttachmentIdsByLocalId = mapOf(first.id to first.id, second.id to second.id)
            )
        )
    }

    private fun mountedStore(instanceId: String = "composer"): SharedThreadComposerDraftStore {
        val store = SharedThreadComposerDraftStore()
        store.dispatch(
            SharedThreadComposerDraftIntent.Start(
                composer = KuiklyThreadComposerPayload(instanceId = instanceId),
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
            source = source,
            uploadBytesTotal = 100L
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
