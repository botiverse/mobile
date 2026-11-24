package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/*
 * The per-row position rule for an Activity row (#triage:#244, inbox redesign "Fact 1"), as a pure
 * reducer: a line-for-line port of slock `packages/shared/src/activityRowPosition.ts`
 * (`reduceActivityRow`), pinned by the vendored contract cases in
 * `src/commonTest/resources/contract-cases/activity-row/` (README there). No clock, no I/O, no store:
 * SharedActivityStore runs it with `inbox_row_position_v1` on (ActivityRowPositionLedger.kt).
 *
 * A served row says what it has counted up to: its `latestActivitySeq` (P), the highest seq committed
 * and counted in that answer. It does not say that every lower seq is counted (rows commit out of seq
 * order, rarely), so:
 * - a live message above P is applied (counted if the unread predicate says so);
 * - one at or below P already applied by this client (delivered twice) is ignored;
 * - one at or below P not applied is ambiguous: re-read this one row.
 * Merging an answer (Pa): older than the row's delivered position, keep the row; otherwise take it and
 * re-apply the seqs this client applied above Pa. Applied seqs at or below Pa are trusted as counted in
 * it, unless an inversion (a live seq lower than one already applied since the last answer) was seen:
 * then re-read the row. A read covers the applied seqs at or below it: they no longer count, so an
 * answer below them does not count them again. A row with no position takes no live patches: every
 * event for it re-reads it.
 * The row keeps the highest seq the viewer read ([ActivityRowState.readSeq], set by local reads and read
 * acks). An answer at or below it counted only read messages: the server computed it before the read
 * reached it, so its unread and @ are dropped (`answer_before_read_lands`); a mention-only answer is taken as
 * it is (its @ does not follow the read cursor on every server path). A Mark as Unread clears it and keeps the
 * old value as a ceiling: a later read at or below it (a late ack of a read from before the mark) does not
 * raise it again (`mark_unread_then_late_read_ack`).
 *
 * Seqs are canonical decimal strings (`^(0|[1-9][0-9]*)$`), taken and returned as such and compared
 * exactly (`messages.seq` is a 64-bit bigserial; contract case `seq_above_2_53`).
 */

/**
 * A seq applied to the row by a live message since its last answer, with its verdict (re-applied on a
 * merge). Web `ActivityRowAppliedSeq`. [moves] false: a mention past a mute on a row with a pre-mute
 * message: it lights the @ but is not the row's shown position.
 */
@RaftCoreInternalApi data class ActivityRowAppliedSeq(val seq: String, val counts: Boolean, val mention: Boolean, val moves: Boolean = true)

/** Web `ActivityRowState`. */
@RaftCoreInternalApi data class ActivityRowState(
    val unreadCount: Int,
    val hasMention: Boolean,
    /** The position delivered by the row's last answer (its `latestActivitySeq`), or null: no live patches. */
    val position: String?,
    /** A mention-only row (nothing else unread): its position is its highest mention seq. */
    val mentionOnly: Boolean,
    /** Seqs applied above [position] since the last answer, ascending. */
    val applied: List<ActivityRowAppliedSeq> = emptyList(),
    /** A live seq lower than an applied one arrived since the last answer (two messages committed out of seq order). */
    val inversionObserved: Boolean = false,
    /**
     * The highest seq the viewer read on this client: every message at or below it is read. Kept across
     * answers; null = none known. An answer at or below it has nothing unread. Web `readSeq`.
     */
    val readSeq: String? = null,
    /**
     * Set by a Mark as Unread: the read seq it cleared. A read at or below it (a late ack of a read from
     * before the mark) does not raise [readSeq]; a read above it clears it and records normally. Web
     * `readSeqCeiling`.
     */
    val readSeqCeiling: String? = null,
)

/**
 * A served row, as an answer carries it. Web `ActivityRowAnswer`. [readSeq] (optional): the viewer's read
 * position served with the row (its `maxReadSeq`), merged as a read is: the row keeps the higher of the two,
 * and a Mark as Unread's ceiling still holds (slock #15312, `sync_upsert_equal_position_replaces`).
 */
@RaftCoreInternalApi data class ActivityRowAnswer(
    val unreadCount: Int,
    val hasMention: Boolean,
    val position: String?,
    val mentionOnly: Boolean,
    val readSeq: String? = null,
)

/** Web `ActivityRowEvent`. */
@RaftCoreInternalApi sealed interface ActivityRowEvent {
    /**
     * [counts]: the unread predicate's verdict; null = cannot be judged (re-read the row). [moves]: the
     * predicate's `moves`. False (past a mute): no mention leaves the row as it is; a mention lights the
     * @ and moves only a mention-only row.
     */
    data class Live(val seq: String, val counts: Boolean?, val mentionsViewer: Boolean, val moves: Boolean = true) : ActivityRowEvent

    data class Answer(val row: ActivityRowAnswer) : ActivityRowEvent

    /** The viewer read the conversation up to [seq] (a persisted read or a read change's `refSeq`). */
    data class Read(val seq: String) : ActivityRowEvent

    /** The viewer marked the conversation unread: the read cursor moved back, so [ActivityRowState.readSeq] no longer holds. */
    data object MarkUnread : ActivityRowEvent
}

/** Web `ActivityRowRereadReason` (`ambiguous_seq`, `inversion`, `null_position`, `unjudged`, `partial_read`). */
@RaftCoreInternalApi enum class ActivityRowRereadReason { AmbiguousSeq, Inversion, NullPosition, Unjudged, PartialRead }

/** Web `ActivityRowResult`: [reread] asks to re-read this one row (`?ids=`), never the list. */
@RaftCoreInternalApi data class ActivityRowResult(
    val row: ActivityRowState,
    val reread: Boolean,
    val rereadReason: ActivityRowRereadReason? = null,
)

private fun String?.isCanonicalSeq(): Boolean = this != null && isCanonicalActivitySeq(this)

/** A row as an answer delivers it, with no client state. Web `activityRowFromAnswer`. */
@RaftCoreInternalApi fun activityRowFromAnswer(answer: ActivityRowAnswer): ActivityRowState =
    ActivityRowState(
        unreadCount = answer.unreadCount,
        hasMention = answer.hasMention,
        position = answer.position.takeIf { it.isCanonicalSeq() },
        mentionOnly = answer.mentionOnly,
        readSeq = answer.readSeq.takeIf { it.isCanonicalSeq() },
    )

/** The position the row shows (its `latestActivitySeq` on screen): the delivered one or the highest applied above it. Web `activityRowDisplayPosition`. */
@RaftCoreInternalApi fun activityRowDisplayPosition(row: ActivityRowState): String? = row.applied.lastOrNull { it.moves }?.seq ?: row.position

/** Web `reduceActivityRow`. */
@RaftCoreInternalApi fun reduceActivityRow(row: ActivityRowState, event: ActivityRowEvent): ActivityRowResult =
    when (event) {
        is ActivityRowEvent.Live -> applyLive(row, event)
        is ActivityRowEvent.Answer -> mergeAnswer(row, event.row)
        is ActivityRowEvent.Read -> applyRead(row, event.seq)
        ActivityRowEvent.MarkUnread -> keep(row.markedUnread())
    }

/**
 * Exact order of two canonical decimal seq strings: >0, 0 or <0. Web `compareActivityRowSeq`: throws on
 * a non-canonical seq (the reducer never passes one), unlike the store's lenient [compareActivitySeq].
 */
@RaftCoreInternalApi fun compareActivityRowSeq(a: String, b: String): Int {
    require(isCanonicalActivitySeq(a) && isCanonicalActivitySeq(b)) { "not a canonical decimal seq: $a / $b" }
    if (a.length != b.length) return if (a.length > b.length) 1 else -1
    return a.compareTo(b).coerceIn(-1, 1)
}

private fun keep(row: ActivityRowState) = ActivityRowResult(row, reread = false)

private fun reread(row: ActivityRowState, reason: ActivityRowRereadReason) = ActivityRowResult(row, reread = true, rereadReason = reason)

/**
 * A counted message (a mention included) turns a mention-only row into a normal unread row; a mention
 * that does not count (a muted conversation) lights the @ and keeps it mention-only. Web `withVerdict`.
 */
private fun ActivityRowState.withVerdict(counts: Boolean, mention: Boolean): ActivityRowState {
    if (!counts) return if (mention) copy(hasMention = true) else this
    return copy(unreadCount = unreadCount + 1, hasMention = hasMention || mention, mentionOnly = false)
}

private fun applyLive(row: ActivityRowState, event: ActivityRowEvent.Live): ActivityRowResult {
    // Past a mute (RisingWave's muted prefix, #triage:#244): the server's row stays at its last message
    // before the mute. A message that does not mention the viewer changes nothing; a mention lights the
    // @, and moves only a mention-only row (its position is its highest mention).
    if (!event.moves && !event.mentionsViewer) return keep(row)
    val stays = !event.moves && !row.mentionOnly
    val position = row.position ?: return reread(row, ActivityRowRereadReason.NullPosition)
    if (!event.seq.isCanonicalSeq()) return reread(row, ActivityRowRereadReason.Unjudged)
    if (compareActivityRowSeq(event.seq, position) <= 0) return reread(row, ActivityRowRereadReason.AmbiguousSeq)
    if (row.applied.any { it.seq == event.seq }) return keep(row)
    val counts = event.counts ?: return reread(row, ActivityRowRereadReason.Unjudged)
    val highest = row.applied.lastOrNull()?.seq ?: position
    val inversion = compareActivityRowSeq(event.seq, highest) < 0
    val entry = ActivityRowAppliedSeq(event.seq, counts = counts, mention = event.mentionsViewer, moves = !stays)
    val applied = (row.applied + entry).sortedWith { a, b -> compareActivityRowSeq(a.seq, b.seq) }
    return keep(
        row.withVerdict(entry.counts, entry.mention).copy(applied = applied, inversionObserved = row.inversionObserved || inversion)
    )
}

private fun mergeAnswer(row: ActivityRowState, served: ActivityRowAnswer): ActivityRowResult {
    // A position that is not a canonical seq is no position (fail closed: no live patches).
    val answer = if (served.position.isCanonicalSeq()) served else served.copy(position = null)
    // An answer older than the one the row came from changes nothing.
    if (answer.position != null && row.position != null && compareActivityRowSeq(answer.position, row.position) < 0) return keep(row)
    // The row's read seq and ceiling, raised by the answer's own read seq as a read would (max, under the ceiling).
    val read = if (answer.readSeq == null) row else row.withReadSeq(answer.readSeq)
    val pa =
        answer.position
            ?: return keep(activityRowFromAnswer(answer).copy(readSeq = read.readSeq, readSeqCeiling = read.readSeqCeiling))
    val above = row.applied.filter { compareActivityRowSeq(it.seq, pa) > 0 }
    val trusted = row.applied.size - above.size
    // Everything the answer counted is at or below Pa: at or below the read, it is all read (the server
    // computed the answer before the read reached it). Not a mention-only answer: its @ does not follow
    // the read cursor on every server path (as applyRead).
    val counted =
        if (!answer.mentionOnly && read.readSeq != null && compareActivityRowSeq(pa, read.readSeq) <= 0) {
            answer.copy(unreadCount = 0, hasMention = false, mentionOnly = false)
        } else {
            answer
        }
    var next =
        activityRowFromAnswer(counted).copy(
            applied = above,
            inversionObserved = row.inversionObserved && above.isNotEmpty(),
            readSeq = read.readSeq,
            readSeqCeiling = read.readSeqCeiling,
        )
    for (entry in above) next = next.withVerdict(entry.counts, entry.mention)
    return if (trusted > 0 && row.inversionObserved) reread(next, ActivityRowRereadReason.Inversion) else keep(next)
}

private fun applyRead(original: ActivityRowState, seq: String): ActivityRowResult {
    // The seqs the read covers are read: a later answer below them must not count them again.
    val row = original.withAppliedRead(seq).withReadSeq(seq)
    val shown = activityRowDisplayPosition(row)
    if (row.unreadCount == 0 && !row.hasMention) return keep(row)
    // A mention-only row's @ is not a cursor matter on every server path; a partial read cannot be
    // split; a row without a position cannot be judged.
    if (shown == null) return reread(row, ActivityRowRereadReason.NullPosition)
    if (!seq.isCanonicalSeq()) return reread(row, ActivityRowRereadReason.Unjudged)
    if (row.mentionOnly || compareActivityRowSeq(seq, shown) < 0) return reread(row, ActivityRowRereadReason.PartialRead)
    return keep(row.copy(unreadCount = 0, hasMention = false))
}

/** The applied seqs at or below a read's seq no longer count or light the @ (they stay applied: a repeat is still a repeat). Web `withAppliedRead`. */
private fun ActivityRowState.withAppliedRead(seq: String): ActivityRowState {
    if (!seq.isCanonicalSeq()) return this
    val covered = { entry: ActivityRowAppliedSeq -> compareActivityRowSeq(entry.seq, seq) <= 0 && (entry.counts || entry.mention) }
    if (applied.none(covered)) return this
    return copy(applied = applied.map { if (covered(it)) it.copy(counts = false, mention = false) else it })
}

/**
 * The read raises [ActivityRowState.readSeq] (never lowers it: a late, older read ack changes nothing). At or
 * below a Mark as Unread's ceiling it does not raise it (a late ack of a read from before the mark); above it,
 * the ceiling goes. Web `withReadSeq`.
 */
private fun ActivityRowState.withReadSeq(seq: String): ActivityRowState {
    if (!seq.isCanonicalSeq()) return this
    if (readSeqCeiling != null) {
        if (compareActivityRowSeq(seq, readSeqCeiling) <= 0) return this
        return copy(readSeqCeiling = null).withReadSeq(seq)
    }
    if (readSeq != null && compareActivityRowSeq(seq, readSeq) <= 0) return this
    return copy(readSeq = seq)
}

/** Mark as Unread: the read seq goes, kept as the ceiling a late read ack must pass to raise it again. Web `markedUnread`. */
private fun ActivityRowState.markedUnread(): ActivityRowState {
    val cleared = readSeq ?: return this
    val ceiling = readSeqCeiling?.takeIf { compareActivityRowSeq(it, cleared) > 0 } ?: cleared
    return copy(readSeq = null, readSeqCeiling = ceiling)
}

/**
 * A `GET /api/sync/changes` `inbox_row` change (inbox_row capability, slock #15312 `SyncInboxRowChange`), as
 * the shared rule takes it. Web `applySyncInboxRowChange`'s `change`.
 */
@RaftCoreInternalApi sealed interface ActivitySyncRowChange {
    /** The row as `GET /api/channels/inbox?ids=` answers it: applied as that answer. */
    data class Upsert(val row: ActivityRowAnswer) : ActivitySyncRowChange

    /** The row left the inbox; [position] null (`left`) removes it whatever it shows. */
    data class Delete(val position: String?) : ActivitySyncRowChange
}

/** Web `applySyncInboxRowChange`'s result: [row] null = the row is no longer held. */
@RaftCoreInternalApi data class ActivitySyncRowResult(
    val row: ActivityRowState?,
    val reread: Boolean,
    val rereadReason: ActivityRowRereadReason? = null,
)

/**
 * Web `applySyncInboxRowChange` (slock `packages/shared/src/activityRowPosition.ts`, #15312) on the held row
 * ([held] null: none held). An upsert is an answer (the same merge: older than the held position ignored,
 * equal replaces, the read seq kept as the higher one). A delete removes the row when its position is null or
 * at or past the position the row shows (a live message applied above its answer included); below it, a newer
 * message brought the row back and it is kept. A position that is not a canonical seq keeps the row.
 */
@RaftCoreInternalApi fun applySyncInboxRowChange(held: ActivityRowState?, change: ActivitySyncRowChange): ActivitySyncRowResult =
    when (change) {
        is ActivitySyncRowChange.Upsert -> {
            val result = if (held == null) keep(activityRowFromAnswer(change.row)) else reduceActivityRow(held, ActivityRowEvent.Answer(change.row))
            ActivitySyncRowResult(result.row, result.reread, result.rereadReason)
        }
        is ActivitySyncRowChange.Delete -> {
            val removed = ActivitySyncRowResult(row = null, reread = false)
            val shown = held?.let(::activityRowDisplayPosition)
            when {
                held == null || change.position == null || shown == null -> removed
                !change.position.isCanonicalSeq() -> ActivitySyncRowResult(held, reread = false)
                compareActivityRowSeq(change.position, shown) >= 0 -> removed
                else -> ActivitySyncRowResult(held, reread = false)
            }
        }
    }
