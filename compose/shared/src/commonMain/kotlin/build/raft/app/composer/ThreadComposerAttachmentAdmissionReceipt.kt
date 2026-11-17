package build.raft.app

private fun normalizeComposerAdmissionPart(value: String?): String? =
    value?.trim()?.takeIf { it.isNotBlank() }

/**
 * Validate the exact reducer delta which admits local attachment rows.
 *
 * A composer may already have other uploading rows. The receipt must therefore reject missing or
 * extra changes without requiring the global admission map to contain only this batch.
 */
internal fun threadComposerAttachmentAdmissionReceiptIsValid(
    accepted: Boolean,
    before: SharedThreadComposerDraftState,
    after: SharedThreadComposerDraftState,
    expectedDraftKey: String,
    localAttachments: List<SharedThreadComposerAttachment>,
    replacementAttachmentIdsByLocalId: Map<String, String>
): Boolean {
    if (!accepted) return false
    val expectedKey = normalizeThreadAttachmentUploadDraftKey(expectedDraftKey) ?: return false
    if (
        normalizeThreadAttachmentUploadDraftKey(before.draftKey) != expectedKey ||
            normalizeThreadAttachmentUploadDraftKey(after.draftKey) != expectedKey
    ) return false

    val expectedIds = localAttachments.map { it.id.trim() }
    if (expectedIds.isEmpty() || expectedIds.any(String::isBlank) || expectedIds.toSet().size != expectedIds.size) {
        return false
    }
    val expectedIdSet = expectedIds.toSet()
    val replacementSlots =
        replacementAttachmentIdsByLocalId
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
    if (
        replacementSlots.keys != expectedIdSet ||
            replacementSlots.size != replacementAttachmentIdsByLocalId.size ||
            replacementSlots.values.any(String::isBlank) ||
            replacementSlots.values.toSet().size != replacementSlots.size
    ) return false

    val beforeAdmissions = before.attachmentAdmissionsByLocalId
    val afterAdmissions = after.attachmentAdmissionsByLocalId
    // Reject maps whose raw keys collapse after normalization.  Otherwise a malformed reducer
    // snapshot containing both `id` and ` id ` could satisfy the normalized set comparison while
    // silently replacing one admission witness.
    if (
        beforeAdmissions.keys.map(String::trim).toSet().size != beforeAdmissions.size ||
            afterAdmissions.keys.map(String::trim).toSet().size != afterAdmissions.size
    ) return false
    val unrelatedBefore = beforeAdmissions.filterKeys { it.trim() !in expectedIdSet }
    val unrelatedAfter = afterAdmissions.filterKeys { it.trim() !in expectedIdSet }
    if (unrelatedAfter != unrelatedBefore) return false
    if (afterAdmissions.keys.map(String::trim).toSet() != unrelatedBefore.keys.map(String::trim).toSet() + expectedIdSet) {
        return false
    }
    if (
        before.nextAttachmentAdmissionGeneration < 0L ||
            expectedIds.size.toLong() > Long.MAX_VALUE - before.nextAttachmentAdmissionGeneration ||
            after.nextAttachmentAdmissionGeneration !=
                before.nextAttachmentAdmissionGeneration + expectedIds.size
    ) return false

    val beforeRows = before.uploadedAttachments.groupBy { it.id.trim() }
    val afterRows = after.uploadedAttachments.groupBy { it.id.trim() }
    if (
        beforeRows.keys.any(String::isBlank) ||
            beforeRows.values.any { it.size != 1 } ||
            afterRows.keys.any(String::isBlank) ||
            afterRows.values.any { it.size != 1 }
    ) return false
    val unchangedIds = beforeRows.keys - expectedIdSet
    // The reducer delta may replace an existing local row, but it must never append a row which
    // this admission did not request.  Checking only the pre-existing ids would let an accidental
    // or forged extra upload become visible while the receipt still reported success.
    if ((afterRows.keys - beforeRows.keys - expectedIdSet).isNotEmpty()) return false
    if (unchangedIds.any { id -> afterRows[id]?.singleOrNull() != beforeRows[id]?.singleOrNull() }) return false

    return localAttachments.withIndex().all { (index, expected) ->
        val id = expected.id.trim()
        val admission = afterAdmissions[id] ?: return@all false
        val row = afterRows[id]?.singleOrNull() ?: return@all false
        val expectedRef = normalizeComposerAdmissionPart(expected.fileRef)
        val expectedSource = normalizeComposerAdmissionPart(expected.source)
        val expectedGeneration = before.nextAttachmentAdmissionGeneration + index + 1L
        admission.localAttachmentId.trim() == id &&
            admission.generation == expectedGeneration &&
            normalizeComposerAdmissionPart(admission.fileRef) == expectedRef &&
            normalizeComposerAdmissionPart(admission.source) == expectedSource &&
            admission.replacementAttachmentId.trim().ifBlank { id } == replacementSlots[id] &&
            id !in after.removedAttachmentIds.map(String::trim).toSet() &&
            row.status == "uploading" &&
            normalizeComposerAdmissionPart(row.fileRef) == expectedRef &&
            normalizeComposerAdmissionPart(row.source) == expectedSource
    }
}
