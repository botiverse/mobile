package build.raft.app.data

import build.raft.app.isJointActivityRow
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageSystemProvenance
import build.raft.app.model.MessageType
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/*
 * `inbox_row_position_v1` (#triage:#244, inbox redesign step 2), the app side of web
 * `packages/web/src/store/inboxRowPosition.ts`: SharedActivityStore keeps each listed Activity row by
 * the shared per-row position rule ([reduceActivityRow], [activityLiveUnread]). This file holds what
 * the store needs beyond the row it shows (the ledger) and the pure helpers that turn store rows and
 * live messages into the reducer's inputs and back. The store owns when they run.
 */

/** How long a row asked for waits before its read (web `ROW_REREAD_DELAY_MS`): the view lags the write by ~1-2 s. */
internal const val ActivityRowRereadDelayMillis: Long = 1_500L

/** One live message in this many applied in place with no re-read is audited (web `POSITION_AUDIT_EVERY`, a counter). */
internal const val ActivityPositionAuditEvery: Int = 20

/** An audit whose answer was behind its row is read once more this long after that answer (web `AUDIT_FOLLOWUP_DELAY_MS`). */
internal const val ActivityAuditFollowupDelayMillis: Long = 5_000L

/** The route's `ids` limit (server `INBOX_ROW_IDS_MAX`). */
internal const val ActivityRowRereadIdsMax: Int = 50

/** Rows with a kept state; past this the oldest go (web `ROW_STATE_CAP`). */
internal const val ActivityRowPositionStateCap: Int = 500

/** What the reducer needs per row beyond the row shown. Web `RowClientState`. */
internal data class ActivityRowClientState(
    val position: String?,
    val applied: List<ActivityRowAppliedSeq>,
    val inversionObserved: Boolean,
) {
    /** The position the row shows with this state ([activityRowDisplayPosition]). */
    fun displayPosition(): String? = applied.lastOrNull { it.moves }?.seq ?: position
}

/**
 * Per row (by its conversation: the channel, DM, thread or task conversation id) the position its
 * last answer delivered, the seqs applied above it since and whether an inversion was seen. Valid
 * only while the row still shows the position it implies: another path moved the row, and the
 * state starts again from the row as shown (web `rowPositionStateOf`).
 *
 * Beside it, per row, the highest seq the viewer read ([ActivityRowState.readSeq]): a fact about the
 * conversation's reads, not about the row's position, so it is kept when the position state is not.
 */
internal class ActivityRowPositionLedger(private val cap: Int = ActivityRowPositionStateCap) {
    private val lock = SynchronizedObject()
    private val states = LinkedHashMap<String, ActivityRowClientState>()
    private val readSeqs = LinkedHashMap<String, String>()

    /** The reducer's row: [row] as shown, with the state kept for it while it still shows its position. */
    fun stateOf(row: InboxItem): ActivityRowState {
        val scopeId = row.activityRowScopeId()
        val kept = scopeId?.let { synchronized(lock) { states[it] } }
        val readSeq = scopeId?.let { synchronized(lock) { readSeqs[it] } }
        val shown = row.latestActivitySeq
        val valid = kept != null && kept.displayPosition() == shown
        return ActivityRowState(
            unreadCount = row.unreadCount,
            hasMention = row.hasMention,
            position = if (valid) kept!!.position else shown?.takeIf(::isCanonicalActivitySeq),
            mentionOnly = row.unreadCount == 0 && row.hasMention,
            applied = if (valid) kept!!.applied else emptyList(),
            inversionObserved = if (valid) kept!!.inversionObserved else false,
            readSeq = readSeq,
        )
    }

    /** The state kept for [row] is the one [stateOf] uses (web `kept_state=1`). */
    fun keptStateValid(row: InboxItem): Boolean {
        val kept = row.activityRowScopeId()?.let { synchronized(lock) { states[it] } } ?: return false
        return kept.displayPosition() == row.latestActivitySeq
    }

    fun keep(scopeId: String, row: ActivityRowState) {
        synchronized(lock) {
            states.remove(scopeId)
            states[scopeId] = ActivityRowClientState(row.position, row.applied, row.inversionObserved)
            while (states.size > cap) states.remove(states.keys.first())
            row.readSeq?.let { raiseReadSeqLocked(scopeId, it) }
        }
    }

    /** A read of the conversation through [seq] (a local read of the row, a read ack): raises its read seq, never lowers it. */
    fun noteRead(scopeId: String, seq: String?) {
        if (seq == null || !isCanonicalActivitySeq(seq)) return
        synchronized(lock) { raiseReadSeqLocked(scopeId, seq) }
    }

    /**
     * The server's read cursor for the conversation, from a read state newer than any before it (the
     * store orders them by version): its read seq becomes [seq], lower too (Mark as Unread elsewhere).
     */
    fun setReadCursor(scopeId: String, seq: String?) {
        synchronized(lock) {
            readSeqs.remove(scopeId)
            if (seq != null && isCanonicalActivitySeq(seq)) putReadSeqLocked(scopeId, seq)
        }
    }

    /** Mark as Unread here: the read cursor moved back, the read seq no longer holds ([ActivityRowEvent.MarkUnread]). */
    fun forgetRead(scopeId: String) {
        synchronized(lock) { readSeqs.remove(scopeId) }
    }

    private fun raiseReadSeqLocked(scopeId: String, seq: String) {
        val kept = readSeqs[scopeId]
        if (kept != null && compareActivityRowSeq(seq, kept) <= 0) return
        readSeqs.remove(scopeId)
        putReadSeqLocked(scopeId, seq)
    }

    private fun putReadSeqLocked(scopeId: String, seq: String) {
        readSeqs[scopeId] = seq
        while (readSeqs.size > cap) readSeqs.remove(readSeqs.keys.first())
    }

    /** A row first listed by an answer: its state starts at the answer's position. */
    fun start(scopeId: String, position: String?) {
        keep(
            scopeId,
            ActivityRowState(
                unreadCount = 0,
                hasMention = false,
                position = position?.takeIf(::isCanonicalActivitySeq),
                mentionOnly = false,
            ),
        )
    }

    fun clear() {
        synchronized(lock) {
            states.clear()
            readSeqs.clear()
        }
    }

    internal fun keptForTest(scopeId: String): ActivityRowClientState? = synchronized(lock) { states[scopeId] }

    internal fun readSeqForTest(scopeId: String): String? = synchronized(lock) { readSeqs[scopeId] }
}

/** The conversation id the row's position state and its re-read (`?ids=`) go by: the thread's own channel for a thread row. */
internal fun InboxItem.activityRowScopeId(): String? =
    when (kind) {
        InboxItemKind.Thread -> (threadChannelId ?: channelId).value
        InboxItemKind.Channel, InboxItemKind.DirectMessage -> channelId.value
        else -> null
    }

/**
 * A row the position rule keeps: a channel, DM, thread or task conversation row, not a joint one (its
 * frontiers are in another server's seq space; web `isJoint`).
 */
internal fun InboxItem.isActivityPositionRow(): Boolean = activityRowScopeId() != null && !isJointActivityRow()

/** A read only this screen knows of (a local read hold on the row): the applied seqs it covers do not count again. Web `withReadApplied` (`all`). */
internal fun ActivityRowState.withAllAppliedRead(): ActivityRowState {
    if (applied.none { it.counts || it.mention }) return this
    return copy(applied = applied.map { it.copy(counts = false, mention = false) })
}

/** The rule's message as `message:new` carries it. [provenance] null: the frame did not carry the system provenance. */
internal fun Message.toActivityUnreadMessage(provenance: MessageSystemProvenance?): ActivityUnreadMessage =
    ActivityUnreadMessage(
        senderType = author.kind.activitySenderType(),
        senderId = author.id.value,
        messageType = if (messageType == MessageType.System) "system" else "chat",
        causalActorType = provenance?.causalActorType,
        causalActorId = provenance?.causalActorId,
        systemSubtype = provenance?.systemSubtype,
        carriesSystemProvenance = provenance != null,
        mentions = mentions.map { ActivityUnreadTarget(it.type, it.id) },
    )

/** The wire `senderType` a member kind stands for. */
internal fun MemberKind.activitySenderType(): String =
    when (this) {
        MemberKind.Human -> "user"
        MemberKind.Agent -> "agent"
        MemberKind.System -> "system"
        MemberKind.External -> "external"
    }

/** The rule's row kind for [row]. */
internal fun InboxItem.activityUnreadRowKind(): ActivityUnreadRowKind =
    when {
        kind == InboxItemKind.Thread && isTaskConversation -> ActivityUnreadRowKind.Task
        kind == InboxItemKind.Thread -> ActivityUnreadRowKind.Thread
        kind == InboxItemKind.DirectMessage -> ActivityUnreadRowKind.Dm
        else -> ActivityUnreadRowKind.Channel
    }

/**
 * The shared predicate's verdict for [message] on [row] (web `rowPositionVerdict`). The app's reader
 * is a person (`user`). [activityMuted]: the message is past the conversation's Activity mute (the
 * shell knows it; it counts only on channel and DM rows). [readOnArrival]: the conversation is open
 * on screen.
 */
internal fun activityRowLiveVerdict(
    row: InboxItem,
    message: Message,
    provenance: MessageSystemProvenance?,
    principalId: String,
    activityMuted: Boolean,
    readOnArrival: Boolean,
): ActivityUnreadVerdict {
    val rowKind = row.activityUnreadRowKind()
    val threadRow = rowKind == ActivityUnreadRowKind.Thread || rowKind == ActivityUnreadRowKind.Task
    return activityLiveUnread(
        message.toActivityUnreadMessage(provenance),
        ActivityUnreadViewer(type = "user", id = principalId),
        ActivityUnreadScope(
            rowKind = rowKind,
            threadFollowed = !row.isUnfollowedActivityThread,
            activityMuted = activityMuted && !threadRow,
            readOnArrival = readOnArrival,
        ),
    )
}

/** The re-read trigger for a reducer reason (web `REREAD_TRIGGER`). */
internal fun ActivityRowRereadReason?.rereadTrigger(): InboxRefreshTrigger =
    when (this) {
        ActivityRowRereadReason.AmbiguousSeq, null -> InboxRefreshTrigger.AmbiguousSeq
        ActivityRowRereadReason.Inversion -> InboxRefreshTrigger.RowInversion
        ActivityRowRereadReason.NullPosition -> InboxRefreshTrigger.RowNullPosition
        ActivityRowRereadReason.Unjudged -> InboxRefreshTrigger.RowUnjudged
        ActivityRowRereadReason.PartialRead -> InboxRefreshTrigger.RowPartialRead
    }

/** The row as the reducer leaves it: its unread and @, the first-unread / first-mention anchors with them. Web `unreadFields`. */
internal fun InboxItem.withActivityRowUnread(after: ActivityRowState, messageId: MessageId?): InboxItem =
    copy(
        unreadCount = after.unreadCount,
        hasMention = after.hasMention,
        firstUnreadMessageId =
            when {
                after.unreadCount == 0 -> null
                unreadCount == 0 -> messageId ?: firstUnreadMessageId
                else -> firstUnreadMessageId
            },
        firstMentionMessageId =
            when {
                !after.hasMention -> null
                hasMention -> firstMentionMessageId
                else -> messageId ?: firstMentionMessageId
            },
    )

/**
 * The read / Done frontiers move with the shown seq when they were at it (web `movedFrontiers` +
 * `skippedRereadFrontiers`): a Done or read of the patched row then covers the message it shows, and a
 * served row with a newer message is not mistaken for one the local read covered (#15049).
 */
internal fun InboxItem.withActivityFrontiersMovedTo(seq: String): InboxItem {
    val shown = latestActivitySeq
    if (readStateLatestActivitySeq != shown) return this
    if (doneFrontierSeqPresent && doneFrontierSeq != shown) return this
    return copy(
        readStateLatestActivitySeq = seq,
        doneFrontierSeq = if (doneFrontierSeqPresent) seq else doneFrontierSeq,
    )
}

/**
 * A row waiting for its re-read (web `PendingRow`): the first trigger it was asked for with and how
 * many asks it stands for; for an audit, the row as the patch left it ([audit]) and whether it is the
 * follow-up read. [serverId]: the server it was asked on (where a not-judged audit is reported).
 */
internal data class ActivityRowRereadPending(
    val trigger: InboxRefreshTriggerCount,
    val audit: InboxItem? = null,
    val followup: Boolean = false,
    val serverId: String? = null,
)

/** A follow-up audit waiting for its delay (web `followupTimers`). */
internal class ActivityAuditFollowup(val job: kotlinx.coroutines.Job, val row: InboxItem, val serverId: String?)
