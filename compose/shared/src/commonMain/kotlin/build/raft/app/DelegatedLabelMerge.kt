package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.InboxItemKind
import build.raft.app.model.Message
import build.raft.app.model.MessageDelegatedBy

// RFC 077 (web #10751): a known "sent by" label is never replaced by a copy of the same message
// that lacks it. A re-fetched / catch-up / cached copy may come from a producer that omits
// `delegatedBy` (slock #10752, the durable canonical frame, older servers); an explicit label on
// the incoming copy — `deleted = true` included — is newer and still wins.

/** The label for a merged copy: the incoming one when it has a label, else the known one. */
internal fun keepKnownDelegatedBy(incoming: MessageDelegatedBy?, known: MessageDelegatedBy?): MessageDelegatedBy? =
    incoming ?: known

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

/** The domain-message form of [withKnownDelegatedByFrom] (shared thread message cache). */
internal fun Message.withKnownDelegatedByFrom(known: Message?): Message =
    if (delegatedBy != null || known == null || known.id != id || known.delegatedBy == null) {
        this
    } else {
        copy(delegatedBy = known.delegatedBy)
    }

/** The message an Activity row / search hit previews: its label belongs to this message only. */
private fun InboxItem.delegatedLabelMessageId(): String? =
    (if (kind == InboxItemKind.Thread) latestActivityMessageId ?: lastMessageId else lastMessageId ?: latestActivityMessageId)
        ?.value

/**
 * Activity rows and search hits: a row keeps the known `latestDelegatedBy` when the incoming copy
 * has none and still previews the same message (same key, same latest message). A row whose
 * latest message moved on takes the incoming value, null included.
 */
internal fun List<InboxItem>.withKnownLatestDelegatedLabelsFrom(known: List<InboxItem>): List<InboxItem> {
    if (isEmpty() || none { it.latestDelegatedBy == null }) return this
    val labelled = known.filter { it.latestDelegatedBy != null }.associateBy { it.key }
    if (labelled.isEmpty()) return this
    var changed = false
    val merged =
        map { item ->
            val previous = labelled[item.key]
            val messageId = item.delegatedLabelMessageId()
            if (
                item.latestDelegatedBy == null &&
                previous != null &&
                messageId != null &&
                messageId == previous.delegatedLabelMessageId()
            ) {
                changed = true
                item.copy(latestDelegatedBy = previous.latestDelegatedBy)
            } else {
                item
            }
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
