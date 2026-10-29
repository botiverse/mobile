package build.raft.app.data

import build.raft.app.data.generated.MarkUnreadOutcomeDto
import build.raft.app.model.PerReceiverReadStateScope

/**
 * What a `POST /channels/:id/unread` answer means for the row (botiverse/slock #11792, #11795, #11854):
 * - [Counted]: the read point was rewound over something countable; the row shows its count.
 * - [Marked]: nothing countable, so the server marked the conversation unread with no count
 *   (`markedUnread: true`); the row shows unread with a dot and no number.
 * - [NothingToMark]: nothing changed (empty conversation, not a member); the row stays read and
 *   the app says why.
 * - [Pending]: a 202, the write was admitted but its result is not known yet (#proj-mobile:#70). No
 *   toast and no rollback: the optimistic state and the thread's hold stay, and the
 *   `read_state:updated` the server pushes afterwards corrects the state.
 */
internal enum class MarkUnreadOutcome {
    Counted,
    Marked,
    NothingToMark,
    Pending,
}

/**
 * [MarkUnreadOutcome.Pending] for a 202. Otherwise the server's own `outcome` (#11854, decided in the read-state transaction) when it sent one this
 * build knows. Otherwise (a server older than the field, or a value newer than this build) the
 * inference from the rest of the answer: `unreadCount > 0` is counted, `markedUnread: true` is
 * marked, anything else (including a server older than `markedUnread`) is nothing to mark.
 *
 * `unreadCount` is a seq distance (latest unread-eligible seq minus `maxReadSeq`), not a number of
 * messages; it is only compared with zero here.
 */
internal fun ReadReceiptResult.markUnreadOutcome(): MarkUnreadOutcome =
    if (markUnreadPending) MarkUnreadOutcome.Pending else when (serverMarkUnreadOutcome) {
        MarkUnreadOutcomeDto.Counted -> MarkUnreadOutcome.Counted
        MarkUnreadOutcomeDto.Marked -> MarkUnreadOutcome.Marked
        MarkUnreadOutcomeDto.Nothing -> MarkUnreadOutcome.NothingToMark
        MarkUnreadOutcomeDto.Unknown, null ->
            when {
                unreadCount > 0 -> MarkUnreadOutcome.Counted
                markedUnread == true -> MarkUnreadOutcome.Marked
                else -> MarkUnreadOutcome.NothingToMark
            }
    }

/** One-shot notices of the Home and Activity read toggles, shown by the page as a toast. */
enum class RaftReadToggleNotice {
    /** Mark as Unread found nothing to mark (web `thread.row.markUnreadNothing`). */
    MarkUnreadNothing,
}

/**
 * What a `read_state:updated` scope says about a pending (202) Mark as Unread: marked (a count or the
 * no-count mark) or not. The event carries `unreadCount` when the server has one and `markedUnread`
 * only when set.
 */
internal fun PerReceiverReadStateScope.leftMarkedUnread(): Boolean = markedUnread || (unreadCount ?: 0) > 0
