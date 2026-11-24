package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
@RaftCoreInternalApi const val ActivityRowRereadDelayMillis: Long = 1_500L

/** One live message in this many applied in place with no re-read is audited (web `POSITION_AUDIT_EVERY`, a counter). */
@RaftCoreInternalApi const val ActivityPositionAuditEvery: Int = 20

/** An audit whose answer was behind its row is read once more this long after that answer (web `AUDIT_FOLLOWUP_DELAY_MS`). */
@RaftCoreInternalApi const val ActivityAuditFollowupDelayMillis: Long = 5_000L

/** The route's `ids` limit (server `INBOX_ROW_IDS_MAX`). */
@RaftCoreInternalApi const val ActivityRowRereadIdsMax: Int = 50

/** Rows with a kept state; past this the oldest go (web `ROW_STATE_CAP`). */
@RaftCoreInternalApi const val ActivityRowPositionStateCap: Int = 500

/** What the reducer needs per row beyond the row shown. Web `RowClientState`. */
@RaftCoreInternalApi data class ActivityRowClientState(
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
@RaftCoreInternalApi class ActivityRowPositionLedger(private val cap: Int = ActivityRowPositionStateCap) {
    private val lock = SynchronizedObject()
    private val states = LinkedHashMap<String, ActivityRowClientState>()
    private val readSeqs = LinkedHashMap<String, String>()
    /** Per row, the read seq a Mark as Unread cleared ([ActivityRowState.readSeqCeiling]). */
    private val readSeqCeilings = LinkedHashMap<String, String>()
    /** Per row, the accepted read state last folded into its read seq ([foldLedgerRead]). Web `folded`. */
    private val folded = LinkedHashMap<String, ActivityLedgerReadState>()
    /** The reducer's row: [row] as shown, with the state kept for it while it still shows its position. */
    fun stateOf(row: InboxItem): ActivityRowState {
        val scopeId = row.activityRowScopeId()
        val kept = scopeId?.let { synchronized(lock) { states[it] } }
        val (readSeq, readSeqCeiling) = scopeId?.let { synchronized(lock) { readSeqs[it] to readSeqCeilings[it] } } ?: (null to null)
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
            readSeqCeiling = readSeqCeiling,
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

    /**
     * A read of the conversation through [seq] (a local read of the row, a read ack): raises its read seq, never
     * lowers it; at or below a Mark as Unread's ceiling it changes nothing, above it the ceiling goes.
     */
    fun noteRead(scopeId: String, seq: String?) {
        if (seq == null || !isCanonicalActivitySeq(seq)) return
        synchronized(lock) { raiseReadSeqLocked(scopeId, seq) }
    }

    /**
     * The server's read cursor for the conversation, from the durable read-state path (read state v1), which
     * keeps its own versions: its read seq becomes [seq], lower too. The last fold's cursor follows it (its
     * version stays: the checkpoint's), so a later checkpoint fold judges a rewind from the cursor applied here.
     */
    fun setReadCursor(scopeId: String, seq: String?) {
        synchronized(lock) {
            readSeqs.remove(scopeId)
            readSeqCeilings.remove(scopeId)
            if (seq != null && isCanonicalActivitySeq(seq)) {
                putReadSeqLocked(scopeId, seq)
                val cursor = seq.toLongOrNull()
                folded[scopeId]?.let { last -> if (cursor != null) folded[scopeId] = last.copy(maxReadSeq = cursor) }
            }
        }
    }

    /**
     * Web `foldLedgerReadByPosition` / `takeLedgerReadEvents` (botiverse/slock #15307): the read state the store's
     * ledger (the per-receiver read checkpoint) holds for the conversation, [accepted] (null: none), into its read
     * seq, whichever path accepted it (this store, Home, which shares the checkpoints). Once per accepted version:
     * - marked unread ([markedUnread]: nothing countable), or its cursor below the last folded one or the read seq,
     *   whichever is higher (a counted Mark as Unread elsewhere: the server moves the cursor back and sends no flag;
     *   an own send raises the read seq with no version): Mark as Unread ([forgetRead]), so an answer at or below
     *   the old read keeps its unread; then, unless marked, a read at the cursor ([noteRead]), which also reads
     *   the applied live seqs at or below it (web `withAppliedRead`: a later answer below them does not count them
     *   again). A false alarm only drops the read seq (safe); a missed mark would hide an unread;
     * - no accepted state any more, or an older version than the one folded (the cursor row deleted, e.g. access
     *   lost, and begun again from v1): the fold, the read seq and its ceiling go, so a new v1 is not ignored.
     */
    fun foldLedgerRead(scopeId: String, accepted: ActivityLedgerReadState?, markedUnread: Boolean) {
        synchronized(lock) {
            val last = folded[scopeId]
            if (last != null && (accepted == null || accepted.readStateVersion < last.readStateVersion)) {
                folded.remove(scopeId)
                readSeqs.remove(scopeId)
                readSeqCeilings.remove(scopeId)
                return@synchronized
            }
            if (accepted == null || (last != null && accepted.readStateVersion <= last.readStateVersion)) return@synchronized
            folded.remove(scopeId)
            folded[scopeId] = accepted
            while (folded.size > cap) folded.remove(folded.keys.first())
            if (markedUnread) {
                forgetReadLocked(scopeId)
                return@synchronized
            }
            val cursor = accepted.maxReadSeq.toString()
            val readSeq = readSeqs[scopeId]
            val rewound =
                (last != null && accepted.maxReadSeq < last.maxReadSeq) ||
                    (readSeq != null && compareActivityRowSeq(cursor, readSeq) < 0)
            if (rewound) forgetReadLocked(scopeId)
            if (isCanonicalActivitySeq(cursor)) {
                readAppliedLocked(scopeId, cursor)
                raiseReadSeqLocked(scopeId, cursor)
            }
        }
    }

    /**
     * Mark as Unread here: the read cursor moved back, the read seq no longer holds; it is kept as the ceiling a
     * later read ack must pass ([ActivityRowEvent.MarkUnread]).
     */
    fun forgetRead(scopeId: String) {
        synchronized(lock) { forgetReadLocked(scopeId) }
    }

    /** The kept applied seqs at or below [seq] no longer count or light the @ (they stay applied). Web `withAppliedRead`. */
    private fun readAppliedLocked(scopeId: String, seq: String) {
        val kept = states[scopeId] ?: return
        val covered = { entry: ActivityRowAppliedSeq ->
            isCanonicalActivitySeq(entry.seq) && compareActivityRowSeq(entry.seq, seq) <= 0 && (entry.counts || entry.mention)
        }
        if (kept.applied.none(covered)) return
        states[scopeId] = kept.copy(applied = kept.applied.map { if (covered(it)) it.copy(counts = false, mention = false) else it })
    }

    private fun forgetReadLocked(scopeId: String) {
        val cleared = readSeqs.remove(scopeId) ?: return
        val ceiling = readSeqCeilings[scopeId]?.takeIf { compareActivityRowSeq(it, cleared) > 0 } ?: cleared
        readSeqCeilings.remove(scopeId)
        readSeqCeilings[scopeId] = ceiling
        while (readSeqCeilings.size > cap) readSeqCeilings.remove(readSeqCeilings.keys.first())
    }

    private fun raiseReadSeqLocked(scopeId: String, seq: String) {
        val ceiling = readSeqCeilings[scopeId]
        if (ceiling != null) {
            if (compareActivityRowSeq(seq, ceiling) <= 0) return
            readSeqCeilings.remove(scopeId)
        }
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
            readSeqCeilings.clear()
            folded.clear()
        }
    }

    @RaftCoreInternalApi fun keptForTest(scopeId: String): ActivityRowClientState? = synchronized(lock) { states[scopeId] }

    @RaftCoreInternalApi fun readSeqForTest(scopeId: String): String? = synchronized(lock) { readSeqs[scopeId] }

    @RaftCoreInternalApi fun readSeqCeilingForTest(scopeId: String): String? = synchronized(lock) { readSeqCeilings[scopeId] }
}

/** The conversation id the row's position state and its re-read (`?ids=`) go by: the thread's own channel for a thread row. */
@RaftCoreInternalApi fun InboxItem.activityRowScopeId(): String? =
    when (kind) {
        InboxItemKind.Thread -> (threadChannelId ?: channelId).value
        InboxItemKind.Channel, InboxItemKind.DirectMessage -> channelId.value
        else -> null
    }

/**
 * A row the position rule keeps: a channel, DM, thread or task conversation row, not a joint one (its
 * frontiers are in another server's seq space; web `isJoint`).
 */
@RaftCoreInternalApi fun InboxItem.isActivityPositionRow(): Boolean = activityRowScopeId() != null && !isJointActivityRow()

/** A read only this screen knows of (a local read hold on the row): the applied seqs it covers do not count again. Web `withReadApplied` (`all`). */
@RaftCoreInternalApi fun ActivityRowState.withAllAppliedRead(): ActivityRowState {
    if (applied.none { it.counts || it.mention }) return this
    return copy(applied = applied.map { it.copy(counts = false, mention = false) })
}

/** The rule's message as `message:new` carries it. [provenance] null: the frame did not carry the system provenance. */
@RaftCoreInternalApi fun Message.toActivityUnreadMessage(provenance: MessageSystemProvenance?): ActivityUnreadMessage =
    ActivityUnreadMessage(
        senderType = author.kind.activitySenderType(),
        senderId = author.id.value,
        messageType = if (messageType == MessageType.System) "system" else "chat",
        causalActorType = provenance?.causalActorType,
        causalActorId = provenance?.causalActorId,
        systemSubtype = provenance?.systemSubtype,
        carriesSystemProvenance = provenance != null,
        countsUnread = provenance?.countsUnread ?: if (!countsUnread) false else null,
        mentions = mentions.map { ActivityUnreadTarget(it.type, it.id) },
    )

/** The wire `senderType` a member kind stands for. */
@RaftCoreInternalApi fun MemberKind.activitySenderType(): String =
    when (this) {
        MemberKind.Human -> "user"
        MemberKind.Agent -> "agent"
        MemberKind.System -> "system"
        MemberKind.External -> "external"
    }

/** The rule's row kind for [row]. */
@RaftCoreInternalApi fun InboxItem.activityUnreadRowKind(): ActivityUnreadRowKind =
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
@RaftCoreInternalApi fun activityRowLiveVerdict(
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
@RaftCoreInternalApi fun ActivityRowRereadReason?.rereadTrigger(): InboxRefreshTrigger =
    when (this) {
        ActivityRowRereadReason.AmbiguousSeq, null -> InboxRefreshTrigger.AmbiguousSeq
        ActivityRowRereadReason.Inversion -> InboxRefreshTrigger.RowInversion
        ActivityRowRereadReason.NullPosition -> InboxRefreshTrigger.RowNullPosition
        ActivityRowRereadReason.Unjudged -> InboxRefreshTrigger.RowUnjudged
        ActivityRowRereadReason.PartialRead -> InboxRefreshTrigger.RowPartialRead
    }

/** The row as the reducer leaves it: its unread and @, the first-unread / first-mention anchors with them. Web `unreadFields`. */
@RaftCoreInternalApi fun InboxItem.withActivityRowUnread(after: ActivityRowState, messageId: MessageId?): InboxItem =
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
@RaftCoreInternalApi fun InboxItem.withActivityFrontiersMovedTo(seq: String): InboxItem {
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
@RaftCoreInternalApi data class ActivityRowRereadPending(
    val trigger: InboxRefreshTriggerCount,
    val audit: InboxItem? = null,
    val followup: Boolean = false,
    val serverId: String? = null,
)

/** A follow-up audit waiting for its delay (web `followupTimers`). */
@RaftCoreInternalApi class ActivityAuditFollowup(val job: kotlinx.coroutines.Job, val row: InboxItem, val serverId: String?)
