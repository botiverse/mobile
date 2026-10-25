package build.raft.app

import build.raft.app.model.Message

// RFC 077 PR 2b (botiverse/slock `assistantSendAsOwnerService.ts` `retractDelegatedMessage`): the
// owner retracts a message their assistant sent for them. The server keeps the row (seq, thread
// anchor, replies), replaces `content` with a fixed English placeholder, sets `deletedAt`,
// revokes the attachments and deletes the reactions, then emits the full row as
// `message:updated`. A retraction cannot be undone, so once any copy of a message is known to be
// retracted, an older copy of it (a cached page, a stale catch-up frame, the durable last-known
// frame) never brings its words, attachments or reactions back.
//
// Web source of truth: `packages/web/src/components/message/delegatedMessage.ts`
// `isRetractedMessage` and `MessageItem.tsx` (`content: "", attachments: [], reactions: []`).

/** Web `isRetractedMessage`: `deletedAt` alone is the signal (only delegated messages retract). */
internal val RaftThreadMessageItem.isRetracted: Boolean
    get() = !deletedAt.isNullOrBlank()

internal val Message.isRetracted: Boolean
    get() = !deletedAt.isNullOrBlank()

/**
 * This message without anything its owner took back: no attachments, reactions or forwarded
 * bundle. The body stays the server's placeholder (previews and copies read it); the message row
 * renders its own localized words instead. Returns this item itself when nothing changes.
 */
internal fun RaftThreadMessageItem.withoutRetractedContent(): RaftThreadMessageItem =
    if (!isRetracted || (attachments.isEmpty() && reactions.isEmpty() && forwardBundle == null)) {
        this
    } else {
        copy(attachments = emptyList(), reactions = emptyList(), forwardBundle = null)
    }

/** The domain-message form of [withoutRetractedContent] (shared thread message cache). */
internal fun Message.withoutRetractedContent(): Message =
    if (!isRetracted || (attachments.isEmpty() && reactions.isNullOrEmpty() && forwardBundle == null)) {
        this
    } else {
        copy(attachments = emptyList(), reactions = emptyList(), forwardBundle = null)
    }

/** [incoming] as retracted when [known] (the same message) already is; otherwise [incoming]. */
internal fun RaftThreadMessageItem.withKnownRetractionFrom(known: RaftThreadMessageItem?): RaftThreadMessageItem =
    if (isRetracted || known == null || known.id != id || !known.isRetracted) {
        this
    } else {
        copy(deletedAt = known.deletedAt, body = known.body).withoutRetractedContent()
    }

/** The domain-message form of [withKnownRetractionFrom] (shared thread message cache). */
internal fun Message.withKnownRetractionFrom(known: Message?): Message =
    if (isRetracted || known == null || known.id != id || !known.isRetracted) {
        withoutRetractedContent()
    } else {
        copy(deletedAt = known.deletedAt, body = known.body).withoutRetractedContent()
    }

/**
 * [withKnownRetractionFrom] by id over a list (a refreshed / restored copy never un-retracts a
 * message); returns this list itself when nothing changes.
 */
internal fun List<RaftThreadMessageItem>.withKnownRetractionsFrom(
    known: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    if (isEmpty()) return this
    val retracted = known.filter { it.isRetracted }.associateBy { it.id }
    if (retracted.isEmpty()) return this
    var changed = false
    val merged =
        map { message ->
            val next = message.withKnownRetractionFrom(retracted[message.id])
            if (next !== message) changed = true
            next
        }
    return if (changed) merged else this
}

