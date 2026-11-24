package build.raft.app

// RFC 077 (web #10751): RaftThreadMessageItem overloads; pure Message / InboxItem merge helpers live in core/DelegatedLabelMergeCore.kt.

/** This (incoming) copy of a message, keeping [known]'s label when this copy has none. */
internal fun RaftThreadMessageItem.withKnownDelegatedByFrom(known: RaftThreadMessageItem?): RaftThreadMessageItem =
    if (delegatedBy != null || known == null || known.id != id || known.delegatedBy == null) {
        this
    } else {
        copy(delegatedBy = known.delegatedBy)
    }

/** [withKnownDelegatedByFrom] by id over a list; returns this list itself when nothing changes. */
internal fun List<RaftThreadMessageItem>.withKnownDelegatedLabelsFrom(
    known: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    if (isEmpty() || none { it.delegatedBy == null }) return this
    val labelled = known.filter { it.delegatedBy != null }.associateBy { it.id }
    if (labelled.isEmpty()) return this
    var changed = false
    val merged =
        map { message ->
            val next = message.withKnownDelegatedByFrom(labelled[message.id])
            if (next !== message) changed = true
            next
        }
    return if (changed) merged else this
}

/**
 * For merges where the already-loaded copy stays and a same-id incoming copy is dropped (paging,
 * sync resume): the incoming copy's explicit label is newer, so it replaces the loaded one; an
 * incoming copy without a label changes nothing.
 */
internal fun List<RaftThreadMessageItem>.withIncomingDelegatedLabels(
    incoming: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    val labels = incoming.filter { it.delegatedBy != null }.associateBy({ it.id }, { it.delegatedBy })
    if (labels.isEmpty()) return this
    var changed = false
    val merged =
        map { message ->
            val label = labels[message.id]
            if (label == null || label == message.delegatedBy) {
                message
            } else {
                changed = true
                message.copy(delegatedBy = label)
            }
        }
    return if (changed) merged else this
}
