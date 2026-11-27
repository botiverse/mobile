package build.raft.app.thread

import build.raft.app.mergeExternalMentionSpans

// slock #17299: RaftThreadMessageItem merge helpers parallel to DelegatedLabelMerge.kt;
// pure Message merge lives in core/ExternalMentionSpanMergeCore.kt.

/** This (incoming) copy of a message, keeping [known]'s spans when this copy omits them for the same body. */
internal fun RaftThreadMessageItem.withKnownExternalMentionSpansFrom(
    known: RaftThreadMessageItem?,
): RaftThreadMessageItem {
    if (known == null || known.id != id) return this
    val merged =
        mergeExternalMentionSpans(
            incomingBody = body,
            incomingSpans = externalMentionSpans,
            knownBody = known.body,
            knownSpans = known.externalMentionSpans,
        )
    return if (merged == externalMentionSpans) this else copy(externalMentionSpans = merged)
}

/** [withKnownExternalMentionSpansFrom] by id over a list; returns this list itself when nothing changes. */
internal fun List<RaftThreadMessageItem>.withKnownExternalMentionSpansFrom(
    known: List<RaftThreadMessageItem>,
): List<RaftThreadMessageItem> {
    if (isEmpty() || none { it.externalMentionSpans.isEmpty() }) return this
    val spanned = known.filter { it.externalMentionSpans.isNotEmpty() }.associateBy { it.id }
    if (spanned.isEmpty()) return this
    var changed = false
    val merged =
        map { message ->
            val next = message.withKnownExternalMentionSpansFrom(spanned[message.id])
            if (next !== message) changed = true
            next
        }
    return if (changed) merged else this
}

/**
 * For merges where the already-loaded copy stays and a same-id incoming copy is dropped (paging,
 * sync resume): an incoming copy carrying explicit spans fills or replaces the loaded copy's spans
 * when its body matches, while an incoming copy without spans never clears known spans.
 */
internal fun List<RaftThreadMessageItem>.withIncomingExternalMentionSpans(
    incoming: List<RaftThreadMessageItem>,
): List<RaftThreadMessageItem> {
    val incomingWithSpans = incoming.filter { it.externalMentionSpans.isNotEmpty() }.associateBy { it.id }
    if (incomingWithSpans.isEmpty()) return this
    var changed = false
    val merged =
        map { message ->
            val update = incomingWithSpans[message.id]
            if (update == null || update.externalMentionSpans == message.externalMentionSpans) {
                message
            } else {
                val nextSpans =
                    mergeExternalMentionSpans(
                        incomingBody = update.body,
                        incomingSpans = update.externalMentionSpans,
                        knownBody = message.body,
                        knownSpans = message.externalMentionSpans,
                    )
                if (nextSpans == message.externalMentionSpans || update.body != message.body) {
                    message
                } else {
                    changed = true
                    message.copy(externalMentionSpans = nextSpans)
                }
            }
        }
    return if (changed) merged else this
}
