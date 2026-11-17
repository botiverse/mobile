package build.raft.app

import build.raft.app.data.InboxItem

/** What an object row's toast offers to undo. */
internal enum class ActivityObjectUndoAction { Done, Unfollow }

internal data class ActivityObjectUndoPending(val item: InboxItem, val action: ActivityObjectUndoAction, val token: Long)

/**
 * The one toast of an object row (workspace Artifacts), web `markObjectRowDone` / Unfollow:
 * - [done]: the Done is already sent; Undo sends `undone` ([undo] calls back with the row).
 * - [unfollow]: nothing is sent yet; the Unfollow goes out only when the toast ends ([expire]) or
 *   gives way ([flushUnfollow]); Undo drops it.
 * One toast at a time: a new one first settles the one showing.
 */
internal data class ActivityObjectUndoState(
    val pending: ActivityObjectUndoPending? = null,
    private val nextToken: Long = 1L,
) {
    fun done(item: InboxItem): ActivityObjectUndoState =
        copy(pending = ActivityObjectUndoPending(item, ActivityObjectUndoAction.Done, nextToken), nextToken = nextToken + 1)

    fun unfollow(item: InboxItem): ActivityObjectUndoState =
        copy(pending = ActivityObjectUndoPending(item, ActivityObjectUndoAction.Unfollow, nextToken), nextToken = nextToken + 1)

    /** Undo the toast [token]: a Done is undone ([undoDone]); a waiting Unfollow is dropped unsent. */
    fun undo(token: Long, undoDone: (InboxItem) -> Unit): ActivityObjectUndoState {
        val current = pending?.takeIf { it.token == token } ?: return this
        if (current.action == ActivityObjectUndoAction.Done) undoDone(current.item)
        return copy(pending = null)
    }

    /** The toast [token] ended: a waiting Unfollow is sent now ([sendUnfollow]). */
    fun expire(token: Long, sendUnfollow: (InboxItem) -> Unit): ActivityObjectUndoState {
        val current = pending?.takeIf { it.token == token } ?: return this
        if (current.action == ActivityObjectUndoAction.Unfollow) sendUnfollow(current.item)
        return copy(pending = null)
    }

    /** Another toast or leaving Activity: a waiting Unfollow is sent now; a Done stands. */
    fun flushUnfollow(sendUnfollow: (InboxItem) -> Unit): ActivityObjectUndoState {
        val current = pending ?: return this
        if (current.action == ActivityObjectUndoAction.Unfollow) sendUnfollow(current.item)
        return copy(pending = null)
    }
}
