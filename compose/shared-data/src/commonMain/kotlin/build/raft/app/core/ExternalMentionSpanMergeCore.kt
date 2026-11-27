package build.raft.app

import build.raft.app.model.ExternalMentionSpan
import build.raft.app.model.Message

/**
 * Merge policy for Slack-bridge `externalMentionSpans` (slock #17299):
 *
 * - An incoming copy that carries non-empty spans always wins (newer read or edit).
 * - When the incoming copy changes [incomingBody] (a Slack edit that rewrote or removed a mention),
 *   previous spans belong to the old body and are NOT carried over.
 * - When the incoming copy omits spans (e.g. a sparse realtime patch, durable canonical frame, or
 *   older producer) and keeps the same body (or carries a blank patch body), keep any previously
 *   known spans whose exact UTF-16 slice still matches the effective body.
 */
@RaftCoreInternalApi fun mergeExternalMentionSpans(
    incomingBody: String,
    incomingSpans: List<ExternalMentionSpan>,
    knownBody: String,
    knownSpans: List<ExternalMentionSpan>,
): List<ExternalMentionSpan> {
    if (incomingSpans.isNotEmpty()) return incomingSpans
    if (knownSpans.isEmpty()) return emptyList()
    if (incomingBody.isNotBlank() && incomingBody != knownBody) return emptyList()
    val effectiveBody = incomingBody.ifBlank { knownBody }
    return knownSpans.filter { span -> span.matchesBodySlice(effectiveBody) }
}

@RaftCoreInternalApi fun ExternalMentionSpan.matchesBodySlice(body: String): Boolean =
    id.isNotEmpty() &&
        label.isNotEmpty() &&
        start >= 0 &&
        end > start &&
        end <= body.length &&
        body.substring(start, end) == label

/** The domain-message merge preserving known `externalMentionSpans` across sparse refreshes. */
@RaftCoreInternalApi fun Message.withKnownExternalMentionSpansFrom(known: Message?): Message {
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
