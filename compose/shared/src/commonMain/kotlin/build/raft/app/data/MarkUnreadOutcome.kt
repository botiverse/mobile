package build.raft.app.data

/**
 * What a `POST /channels/:id/unread` answer means for the row (botiverse/slock #11792, #11795):
 * - [Counted]: the read point was rewound over something countable; the row shows its count.
 * - [Marked]: nothing countable, so the server marked the conversation unread with no count
 *   (`markedUnread: true`); the row shows unread with a dot and no number.
 * - [NothingToMark]: `unreadCount: 0` with `markedUnread: false` (empty conversation, not a member)
 *   or without the field (a server older than the mark): nothing changed; the row stays read and
 *   the app says why.
 */
internal enum class MarkUnreadOutcome {
    Counted,
    Marked,
    NothingToMark,
}

internal fun ReadReceiptResult.markUnreadOutcome(): MarkUnreadOutcome =
    when {
        unreadCount > 0 -> MarkUnreadOutcome.Counted
        markedUnread == true -> MarkUnreadOutcome.Marked
        else -> MarkUnreadOutcome.NothingToMark
    }

/** One-shot notices of the Home and Activity read toggles, shown by the page as a toast. */
enum class RaftReadToggleNotice {
    /** Mark as Unread found nothing to mark (web `thread.row.markUnreadNothing`). */
    MarkUnreadNothing,
}
